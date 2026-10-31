"""The slot refill workflow: offer a freed slot to the waitlist, one patient at a time.

The workflow only knows how to wait and whom to ask. Who gets the offer, whether it is too late
and what counts as an answer are all decided by the core, so these rules have one home and the
workflow's history holds no patient data, only opaque identifiers.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from temporalio import activity, workflow
from temporalio.common import RetryPolicy

with workflow.unsafe.imports_passed_through():
    from turno_agents.workflows.core_api import WorkflowCore

TASK_QUEUE = "turno-slot-refill"
# Answers are noticed this often. It only affects how soon the workflow ends after an acceptance,
# because the core is what decides when an offer is over.
POLL_INTERVAL = timedelta(minutes=1)
# Far more than a slot can ever need, as a guard against a bug looping forever.
MAX_STEPS = 500

_ACTIVITY_TIMEOUT = timedelta(seconds=30)
_RETRY = RetryPolicy(
    initial_interval=timedelta(seconds=2),
    backoff_coefficient=2.0,
    maximum_interval=timedelta(minutes=1),
    # Keeps trying while the core is down, within the timeout set just below.
    maximum_attempts=0,
)
_OUTAGE_LIMIT = timedelta(hours=1)


@dataclass
class OfferDecision:
    """The core's answer, as the workflow needs it.

    Attributes:
        decision: What to do next.
        offer_id: The offer to wait for, if any.
        wait_until: When to ask again, as an ISO 8601 instant, if there is a wait.
    """

    decision: str
    offer_id: str | None = None
    wait_until: str | None = None


@dataclass
class RefillResult:
    """How the workflow ended.

    Attributes:
        outcome: The core's final decision, or ``ACCEPTED``.
        offers: The offers made, in order.
    """

    outcome: str
    offers: list[str] = field(default_factory=list)


class RefillActivities:
    """The workflow's only contact with the outside world."""

    def __init__(self, core: WorkflowCore) -> None:
        """Create the activities.

        Args:
            core: Access to the core.
        """
        self._core = core

    @activity.defn
    def next_offer(self, slot_id: str, step: int) -> OfferDecision:
        """Ask the core what to do next.

        Args:
            slot_id: The freed slot.
            step: The workflow's step, which makes the idempotency key stable across retries.

        Returns:
            The decision.
        """
        answer = self._core.next_offer(slot_id, f"refill-{slot_id}-{step}")
        return OfferDecision(answer["decision"], answer.get("offerId"), answer.get("waitUntil"))

    @activity.defn
    def offer_outcome(self, offer_id: str) -> str:
        """Read how an offer ended.

        Args:
            offer_id: The offer.

        Returns:
            ``PENDING``, ``ACCEPTED`` or ``DECLINED``.
        """
        return self._core.offer_outcome(offer_id)


def _seconds_until(instant: str, now: datetime) -> float:
    target = datetime.fromisoformat(instant.replace("Z", "+00:00"))
    return max((target - now).total_seconds(), 1.0)


@workflow.defn
class SlotRefillWorkflow:
    """Offers one freed slot until someone takes it or the core says it is over."""

    @workflow.run
    async def run(self, slot_id: str) -> RefillResult:
        """Run the workflow.

        Args:
            slot_id: The freed slot.

        Returns:
            How it ended.
        """
        offers: list[str] = []
        for step in range(MAX_STEPS):
            answer = await workflow.execute_activity_method(
                RefillActivities.next_offer,
                args=[slot_id, step],
                start_to_close_timeout=_ACTIVITY_TIMEOUT,
                schedule_to_close_timeout=_OUTAGE_LIMIT,
                retry_policy=_RETRY,
            )
            if answer.decision == "WAIT" and answer.wait_until is not None:
                await workflow.sleep(_seconds_until(answer.wait_until, workflow.now()))
                continue
            if answer.decision != "OFFERED" or answer.offer_id is None:
                return RefillResult(answer.decision, offers)
            if answer.offer_id not in offers:
                offers.append(answer.offer_id)
            if await self._wait_for_answer(answer.offer_id) == "ACCEPTED":
                return RefillResult("ACCEPTED", offers)
        return RefillResult("STEP_LIMIT", offers)

    async def _wait_for_answer(self, offer_id: str) -> str:
        while True:
            outcome = await workflow.execute_activity_method(
                RefillActivities.offer_outcome,
                offer_id,
                start_to_close_timeout=_ACTIVITY_TIMEOUT,
                schedule_to_close_timeout=_OUTAGE_LIMIT,
                retry_policy=_RETRY,
            )
            if outcome != "PENDING":
                return outcome
            await workflow.sleep(POLL_INTERVAL)
