"""Tests for the slot refill workflow on a real Temporal test server, with time that skips ahead.

The core is scripted, because its decisions are tested in the core. These check the waiting, the
retries and that nothing is lost, including when a worker dies halfway.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import asyncio
import uuid
from collections.abc import AsyncIterator, Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

import pytest
import pytest_asyncio
from temporalio.client import Client, WorkflowExecutionStatus
from temporalio.common import WorkflowIDReusePolicy
from temporalio.exceptions import WorkflowAlreadyStartedError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from turno_agents.workflows.core_api import WorkflowCoreError
from turno_agents.workflows.slot_refill import (
    POLL_INTERVAL,
    TASK_QUEUE,
    RefillActivities,
    RefillResult,
    SlotRefillWorkflow,
)
from turno_agents.workflows.worker import start_pending_workflows, workflow_id


@dataclass
class ScriptedCore:
    """Answers from a script, and records what it was asked."""

    decisions: list[dict[str, Any]] = field(default_factory=list)
    outcomes: dict[str, list[str]] = field(default_factory=dict)
    slots: list[dict[str, Any]] = field(default_factory=list)
    acknowledged: list[str] = field(default_factory=list)
    keys: list[str] = field(default_factory=list)
    failures_left: int = 0

    def pending_slots(self) -> list[dict[str, Any]]:
        """List the slots not acknowledged yet."""
        return [slot for slot in self.slots if slot["id"] not in self.acknowledged]

    def acknowledge(self, slot_id: str) -> None:
        """Record the acknowledgement."""
        self.acknowledged.append(slot_id)

    def next_offer(self, slot_id: str, key: str) -> dict[str, Any]:
        """Give the next decision, unless an outage is being simulated."""
        self.keys.append(key)
        if self.failures_left > 0:
            self.failures_left -= 1
            raise WorkflowCoreError("down")
        return self.decisions.pop(0)

    def offer_outcome(self, offer_id: str) -> str:
        """Give the next outcome of an offer."""
        return self.outcomes[offer_id].pop(0)


@pytest_asyncio.fixture
async def env() -> AsyncIterator[WorkflowEnvironment]:
    """Provide a Temporal test server whose timers skip ahead."""
    environment = await WorkflowEnvironment.start_time_skipping()
    yield environment
    await environment.shutdown()


def _worker(
    client: Client, core: ScriptedCore, executor: ThreadPoolExecutor, cached_workflows: int = 1000
) -> Worker:
    activities = RefillActivities(core)
    return Worker(
        client,
        task_queue=TASK_QUEUE,
        workflows=[SlotRefillWorkflow],
        activities=[activities.next_offer, activities.offer_outcome],
        activity_executor=executor,
        max_cached_workflows=cached_workflows,
    )


async def _run(env: WorkflowEnvironment, core: ScriptedCore, slot_id: str = "s1") -> RefillResult:
    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor):
            return await env.client.execute_workflow(
                SlotRefillWorkflow.run,
                slot_id,
                id=f"test-{uuid.uuid4()}",
                task_queue=TASK_QUEUE,
            )


def offered(offer_id: str) -> dict[str, Any]:
    """Build the core's answer for an offer."""
    return {"decision": "OFFERED", "offerId": offer_id, "waitUntil": "2030-01-01T10:00:00Z"}


@pytest.mark.asyncio
async def test_an_accepted_offer_ends_the_workflow(env: WorkflowEnvironment) -> None:
    """Nobody else is bothered once the slot has been taken."""
    core = ScriptedCore(decisions=[offered("o1")], outcomes={"o1": ["ACCEPTED"]})

    result = await _run(env, core)

    assert result == RefillResult("ACCEPTED", ["o1"])
    assert core.decisions == []


@pytest.mark.asyncio
async def test_a_declined_offer_passes_to_the_next_patient(env: WorkflowEnvironment) -> None:
    """Each patient is asked in turn, in the order the core decides."""
    core = ScriptedCore(
        decisions=[offered("o1"), offered("o2"), {"decision": "NO_CANDIDATE"}],
        outcomes={"o1": ["DECLINED"], "o2": ["DECLINED"]},
    )

    result = await _run(env, core)

    assert result == RefillResult("NO_CANDIDATE", ["o1", "o2"])


@pytest.mark.parametrize("decision", ["NO_CANDIDATE", "LIMIT_REACHED", "TOO_LATE", "SLOT_TAKEN"])
@pytest.mark.asyncio
async def test_the_core_decides_when_it_is_over(env: WorkflowEnvironment, decision: str) -> None:
    """The workflow has no opinion on this."""
    result = await _run(env, ScriptedCore(decisions=[{"decision": decision}]))

    assert result == RefillResult(decision, [])


@pytest.mark.asyncio
async def test_it_waits_as_long_as_the_core_says(env: WorkflowEnvironment) -> None:
    """Outside contact hours it sleeps until the time given, then asks again."""
    start = await env.get_current_time()
    wake = (start + timedelta(hours=11)).isoformat()
    core = ScriptedCore(
        decisions=[{"decision": "WAIT", "waitUntil": wake}, {"decision": "NO_CANDIDATE"}]
    )

    await _run(env, core)

    assert await env.get_current_time() >= start + timedelta(hours=11)


@pytest.mark.asyncio
async def test_it_keeps_checking_until_the_offer_is_answered(env: WorkflowEnvironment) -> None:
    """Pending means wait, and ask again.

    The timers are read from the history, because the test server's clock skips ahead by itself and
    so says little about how long the workflow meant to wait.
    """
    core = ScriptedCore(
        decisions=[offered("o1"), {"decision": "NO_CANDIDATE"}],
        outcomes={"o1": ["PENDING", "PENDING", "PENDING", "DECLINED"]},
    )
    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor):
            handle = await env.client.start_workflow(
                SlotRefillWorkflow.run, "s1", id=f"test-{uuid.uuid4()}", task_queue=TASK_QUEUE
            )
            result = await handle.result()

    assert result.outcome == "NO_CANDIDATE"
    assert core.outcomes["o1"] == []
    history = await handle.fetch_history()
    timers = [
        event.timer_started_event_attributes.start_to_fire_timeout.ToTimedelta()
        for event in history.events
        if event.HasField("timer_started_event_attributes")
    ]
    assert timers == [POLL_INTERVAL] * 3


@pytest.mark.asyncio
async def test_an_outage_of_the_core_is_retried_with_the_same_key(env: WorkflowEnvironment) -> None:
    """A retried request has to be recognised as such, or it would make a second offer."""
    core = ScriptedCore(decisions=[{"decision": "NO_CANDIDATE"}], failures_left=3)

    result = await _run(env, core)

    assert result.outcome == "NO_CANDIDATE"
    assert len(core.keys) == 4
    assert len(set(core.keys)) == 1


@pytest.mark.asyncio
async def test_each_step_uses_a_different_key(env: WorkflowEnvironment) -> None:
    """Otherwise the core would keep replaying its first answer."""
    core = ScriptedCore(
        decisions=[offered("o1"), offered("o2"), {"decision": "NO_CANDIDATE"}],
        outcomes={"o1": ["DECLINED"], "o2": ["DECLINED"]},
    )

    await _run(env, core)

    assert len(set(core.keys)) == 3
    assert all(key.startswith("refill-s1-") for key in core.keys)


@pytest.mark.asyncio
async def test_the_same_offer_returned_again_is_not_counted_twice(env: WorkflowEnvironment) -> None:
    """A running offer comes back from the core as it is."""
    core = ScriptedCore(
        decisions=[offered("o1"), offered("o1"), {"decision": "NO_CANDIDATE"}],
        outcomes={"o1": ["DECLINED", "DECLINED"]},
    )

    result = await _run(env, core)

    assert result.offers == ["o1"]


@pytest.mark.asyncio
async def test_a_new_worker_carries_on_where_a_dead_one_left_off(env: WorkflowEnvironment) -> None:
    """This is what durable means: the wait survives the process.

    With no cache, the second worker has to rebuild the workflow from its history, as it would after
    a real crash, and that rebuild must not make the offer again.
    """
    core = ScriptedCore(
        decisions=[offered("o1"), {"decision": "NO_CANDIDATE"}],
        outcomes={"o1": ["PENDING", "DECLINED"]},
    )

    async def until(condition: Callable[[], bool]) -> None:
        while not condition():
            await asyncio.sleep(0.05)

    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor, cached_workflows=0):
            handle = await env.client.start_workflow(
                SlotRefillWorkflow.run, "s1", id=f"test-{uuid.uuid4()}", task_queue=TASK_QUEUE
            )
            # The first worker goes away during the wait for the patient's answer.
            await asyncio.wait_for(until(lambda: core.outcomes["o1"] == ["DECLINED"]), 30)

    assert (await handle.describe()).status == WorkflowExecutionStatus.RUNNING
    keys_before = list(core.keys)

    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor, cached_workflows=0):
            result = await asyncio.wait_for(handle.result(), 60)

    assert result.outcome == "NO_CANDIDATE"
    assert result.offers == ["o1"]
    assert keys_before == core.keys[: len(keys_before)]
    assert len(core.keys) == len(keys_before) + 1, "the rebuild must not ask the core again"


@pytest.mark.asyncio
async def test_each_freed_slot_gets_one_workflow(env: WorkflowEnvironment) -> None:
    """Started once, acknowledged, and not started again."""
    core = ScriptedCore(
        slots=[{"id": "a"}, {"id": "b"}],
        decisions=[{"decision": "NO_CANDIDATE"}, {"decision": "NO_CANDIDATE"}],
    )
    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor):
            assert await start_pending_workflows(env.client, core) == 2
            assert await start_pending_workflows(env.client, core) == 0
            await env.client.get_workflow_handle(workflow_id("a")).result()
            await env.client.get_workflow_handle(workflow_id("b")).result()

    assert core.acknowledged == ["a", "b"]


@pytest.mark.asyncio
async def test_a_crash_before_acknowledging_does_not_start_a_second_workflow(
    env: WorkflowEnvironment,
) -> None:
    """The slot is listed again, Temporal refuses the duplicate, and it finally gets acknowledged."""
    core = ScriptedCore(slots=[{"id": "c"}], decisions=[{"decision": "NO_CANDIDATE"}])
    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor):
            await env.client.start_workflow(
                SlotRefillWorkflow.run, "c", id=workflow_id("c"), task_queue=TASK_QUEUE
            )

            assert await start_pending_workflows(env.client, core) == 0
            await env.client.get_workflow_handle(workflow_id("c")).result()

    assert core.acknowledged == ["c"]


@pytest.mark.asyncio
async def test_a_second_workflow_for_a_slot_is_refused_even_after_it_finished(
    env: WorkflowEnvironment,
) -> None:
    """Otherwise a late retry could start offering a slot all over again."""
    core = ScriptedCore(slots=[{"id": "d"}], decisions=[{"decision": "NO_CANDIDATE"}])
    with ThreadPoolExecutor(max_workers=2) as executor:
        async with _worker(env.client, core, executor):
            await start_pending_workflows(env.client, core)
            await env.client.get_workflow_handle(workflow_id("d")).result()

            core.acknowledged.clear()
            assert await start_pending_workflows(env.client, core) == 0

    with pytest.raises(WorkflowAlreadyStartedError):
        await env.client.start_workflow(
            SlotRefillWorkflow.run,
            "d",
            id=workflow_id("d"),
            task_queue=TASK_QUEUE,
            id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        )
