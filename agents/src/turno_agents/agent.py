"""The agent loop: the model proposes, the code decides.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import logging
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any, assert_never
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from turno_agents.conversation import (
    Conversation,
    ConversationState,
    PendingConsent,
    TraceStep,
)
from turno_agents.core_client import CoreApi, CoreRefusalError, CoreUnavailableError
from turno_agents.guards import Triage, check_outbound, is_affirmative, triage
from turno_agents.llm import LlmClient, LlmError
from turno_agents.prompts import (
    agent_block,
    build_system_prompt,
    fixed_text,
    patient_block,
    result_block,
)
from turno_agents.proposals import (
    AgentTurn,
    CancelAppointment,
    ConfirmAppointment,
    ConsentAction,
    CoreAction,
    Escalate,
    HoldSlot,
    ListAppointments,
    NoAction,
    ReleaseHold,
    RescheduleAppointment,
    SearchSlots,
    proposal_schema,
)

LOG = logging.getLogger(__name__)

MAX_STEPS_PER_TURN = 5
MAX_TRANSCRIPT_BLOCKS = 40
MAX_SLOTS_SHOWN = 12
MAX_CORRECTIONS = 2

_CORRECTIONS = {
    "unbacked_state_claim": (
        "You stated the state of an appointment without reading it in this turn. Use the action "
        "list_appointments first, or write the reply without saying its state."
    ),
    "empty_reply": "Write a reply for the patient.",
    "reply_too_long": "Write a much shorter reply.",
}


class AgentService:
    """Answers one patient message at a time."""

    def __init__(
        self,
        llm: LlmClient,
        core: CoreApi,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        """Create the service.

        Args:
            llm: Access to the model.
            core: Access to the core.
            now: Provides the current instant.
        """
        self._llm = llm
        self._core = core
        self._now = now
        self._schema = proposal_schema()
        self._emergency_number: str | None = None

    def warm_up(self) -> None:
        """Learn the emergency number in advance, so that no emergency waits for the core."""
        try:
            self._read_clinic()
        except (CoreRefusalError, CoreUnavailableError):
            LOG.warning("The clinic could not be read at startup")

    def answer_emergency(self, conversation: Conversation) -> str:
        """Answer an emergency at once, even while another message of this patient is in progress.

        Args:
            conversation: The conversation.

        Returns:
            The reply to show to the patient.
        """
        return self._hand_over(
            conversation, self._emergency_text(conversation.language), "triage_emergency"
        )

    def handle(self, conversation: Conversation, message: str) -> str:
        """Answer a patient's message.

        Args:
            conversation: The conversation, whose lock the caller holds.
            message: What the patient wrote.

        Returns:
            The reply to show to the patient.
        """
        conversation.turn += 1
        language = conversation.language
        # Kept even when no model will read it, for the person who takes over.
        conversation.transcript.append(patient_block(message))

        # First of all, and depending on nothing else: an emergency gets its answer even with the
        # conversation already handed over or with the core down.
        urgency = triage(message)
        if urgency is Triage.EMERGENCY:
            return self._hand_over(conversation, self._emergency_text(language), "triage_emergency")

        if conversation.state is ConversationState.WITH_A_PERSON:
            return fixed_text(language, "waiting")
        try:
            clinic = self._read_clinic()
        except (CoreRefusalError, CoreUnavailableError):
            return self._hand_over(conversation, fixed_text(language, "failure"), "core_down")

        # Decided before the model sees anything, so no message can talk its way past this.
        if urgency is Triage.MEDICAL_QUESTION:
            text = fixed_text(language, "medical", number=clinic["emergencyNumber"])
            return self._hand_over(conversation, text, "triage_medical")

        state_backed = False
        pending = conversation.pending
        conversation.pending = None
        if pending is not None:
            if is_affirmative(message):
                # Runs exactly what the patient agreed to. The model has no part in this.
                conversation.trace.append(TraceStep("consent", f"given:{pending.action.type}"))
                state_backed = self._execute(conversation, pending.action, clinic)
            else:
                conversation.trace.append(TraceStep("consent", "not_given"))
                conversation.transcript.append(
                    result_block(pending.action.type, {"status": "NOT_DONE_PATIENT_DID_NOT_AGREE"})
                )
                # The code read this appointment to ask about it, and nothing changed since.
                state_backed = True
        return self._model_loop(conversation, clinic, state_backed)

    def _read_clinic(self) -> dict[str, Any]:
        clinic = self._core.clinic()
        self._emergency_number = clinic["emergencyNumber"]
        return clinic

    def _emergency_text(self, language: str) -> str:
        """Write the emergency text, with the clinic's number if it can be known."""
        if self._emergency_number is None:
            try:
                self._read_clinic()
            except (CoreRefusalError, CoreUnavailableError):
                return fixed_text(language, "emergency_no_number")
        return fixed_text(language, "emergency", number=self._emergency_number)

    def _model_loop(
        self, conversation: Conversation, clinic: dict[str, Any], state_backed: bool
    ) -> str:
        language = conversation.language
        corrections = 0
        system = build_system_prompt(clinic, language, self._now())
        for _ in range(MAX_STEPS_PER_TURN):
            prompt = "\n".join(conversation.transcript[-MAX_TRANSCRIPT_BLOCKS:])
            try:
                completion = self._llm.complete(system, prompt, self._schema)
            except LlmError:
                LOG.warning("The model gave no usable answer")
                text = fixed_text(language, "failure")
                return self._hand_over(conversation, text, "model_failure")
            conversation.trace.append(
                TraceStep(
                    "model",
                    completion.model,
                    completion.input_tokens,
                    completion.output_tokens,
                    completion.estimated_cost_usd,
                    round(completion.seconds, 2),
                )
            )
            try:
                turn = AgentTurn.model_validate(completion.output)
            except ValidationError:
                conversation.trace.append(TraceStep("rejected", "invalid_format"))
                corrections += 1
                if corrections > MAX_CORRECTIONS:
                    text = fixed_text(language, "failure")
                    return self._hand_over(conversation, text, "invalid_format")
                continue

            action = turn.action
            if isinstance(action, NoAction):
                violation = check_outbound(turn.reply, state_backed)
                if violation is None:
                    return self._finish(conversation, turn.reply)
                conversation.trace.append(TraceStep("blocked", violation.reason))
                corrections += 1
                if not violation.correctable or corrections > MAX_CORRECTIONS:
                    text = fixed_text(language, "failure")
                    return self._hand_over(conversation, text, violation.reason)
                conversation.transcript.append(
                    result_block(
                        "reply",
                        {
                            "status": "REPLY_REJECTED",
                            "reason": violation.reason,
                            "how_to_fix": _CORRECTIONS.get(violation.reason, "Write it again."),
                        },
                    )
                )
                continue
            if isinstance(action, Escalate):
                text = fixed_text(language, "handoff")
                return self._hand_over(conversation, text, "model_escalated")

            if isinstance(action, ConfirmAppointment | CancelAppointment | RescheduleAppointment):
                question = self._ask_for_consent(conversation, action, clinic)
                if question is not None:
                    return self._finish(conversation, question)
                continue
            state_backed |= self._execute(conversation, action, clinic)

        text = fixed_text(language, "failure")
        return self._hand_over(conversation, text, "too_many_steps")

    def _finish(self, conversation: Conversation, reply: str) -> str:
        conversation.transcript.append(agent_block(reply))
        return reply

    def _hand_over(self, conversation: Conversation, text: str, reason: str) -> str:
        conversation.state = ConversationState.WITH_A_PERSON
        conversation.pending = None
        conversation.trace.append(TraceStep("handover", reason))
        conversation.transcript.append(agent_block(text))
        return text

    def _ask_for_consent(
        self, conversation: Conversation, action: ConsentAction, clinic: dict[str, Any]
    ) -> str | None:
        """Prepare a change for consent, describing it in words written by the code.

        Returns:
            The question for the patient, or None if the appointment could not be read, in which
            case the model is told why.
        """
        appointment_id = str(action.appointment_id)
        try:
            appointment = self._core.appointment(conversation.patient_id, appointment_id)
        except CoreRefusalError as error:
            self._record_refusal(conversation, action.type, error)
            return None
        except CoreUnavailableError:
            self._record_core_failure(conversation, action.type)
            return None
        description = self._describe(conversation, action, appointment, clinic)
        conversation.pending = PendingConsent(action, description)
        conversation.trace.append(TraceStep("consent", f"asked:{action.type}"))
        conversation.transcript.append(
            result_block(action.type, {"status": "NEEDS_PATIENT_CONFIRMATION"})
        )
        return description

    def _describe(
        self,
        conversation: Conversation,
        action: ConsentAction,
        appointment: dict[str, Any],
        clinic: dict[str, Any],
    ) -> str:
        language = conversation.language
        what = fixed_text(
            language,
            "appointment",
            treatment=_treatment_name(clinic, appointment["treatmentId"], language),
            when=_local_time(appointment["startsAt"], clinic),
            price=_money(appointment["priceCents"], clinic),
            deposit=_money(appointment["depositCents"], clinic),
        )
        if isinstance(action, ConfirmAppointment):
            return fixed_text(language, "ask_confirm", what=what)
        if isinstance(action, RescheduleAppointment):
            to = _local_time(action.starts_at.isoformat(), clinic)
            return fixed_text(language, "ask_move", what=what, to=to)
        question = fixed_text(language, "ask_cancel", what=what)
        # The core has the final word on the deposit, but the patient has a right to know first.
        warning = self._deposit_warning(appointment, clinic, language)
        return f"{warning} {question}" if warning else question

    def _deposit_warning(
        self, appointment: dict[str, Any], clinic: dict[str, Any], language: str
    ) -> str | None:
        policy = clinic["cancellation"]
        deposit = appointment["depositCents"]
        if not policy["lateCancellationKeepsDeposit"] or deposit <= 0:
            return None
        hours = policy["freeUntilHoursBefore"]
        limit = _parse_instant(appointment["startsAt"]) - timedelta(hours=hours)
        if self._now() <= limit:
            return None
        return fixed_text(language, "deposit", hours=hours, amount=_money(deposit, clinic))

    def _execute(
        self, conversation: Conversation, action: CoreAction, clinic: dict[str, Any]
    ) -> bool:
        """Run an action against the core and record the outcome for the model.

        Returns:
            Whether the core answered with something a claim about an appointment can rest on.
        """
        try:
            result, backs_claims = self._call_core(conversation, action, clinic)
        except CoreRefusalError as error:
            self._record_refusal(conversation, action.type, error)
            return False
        except CoreUnavailableError:
            self._record_core_failure(conversation, action.type)
            return False
        conversation.trace.append(TraceStep("action", f"{action.type}:ok"))
        conversation.transcript.append(result_block(action.type, result))
        return backs_claims

    def _call_core(
        self, conversation: Conversation, action: CoreAction, clinic: dict[str, Any]
    ) -> tuple[object, bool]:
        patient = conversation.patient_id
        # Different for each step, and never built from anything the model wrote.
        key = f"{conversation.id}-{conversation.turn}-{len(conversation.trace)}"
        language = conversation.language
        appointment: dict[str, Any]
        match action:
            case SearchSlots():
                slots = self._core.availability(
                    action.treatment_id,
                    action.date_from.isoformat(),
                    action.date_to.isoformat(),
                    action.practitioner_id,
                )
                shown = [_slot_view(slot, clinic) for slot in slots[:MAX_SLOTS_SHOWN]]
                return {"slots": shown, "total_found": len(slots)}, False
            case ListAppointments():
                found = self._core.appointments(patient)
                return {"appointments": [_view(a, clinic, language) for a in found]}, True
            case HoldSlot():
                appointment = self._core.hold(
                    patient,
                    action.practitioner_id,
                    action.treatment_id,
                    action.starts_at.isoformat(),
                    key,
                )
            case ReleaseHold():
                appointment = self._core.release(patient, str(action.appointment_id), key)
            case ConfirmAppointment():
                appointment = self._core.confirm(patient, str(action.appointment_id), key)
            case CancelAppointment():
                appointment = self._core.cancel(patient, str(action.appointment_id), key)
            case RescheduleAppointment():
                appointment = self._core.reschedule(
                    patient,
                    str(action.appointment_id),
                    action.practitioner_id,
                    action.starts_at.isoformat(),
                    key,
                )
            case _:
                assert_never(action)
        return _view(appointment, clinic, language), True

    def _record_refusal(
        self, conversation: Conversation, action: str, error: CoreRefusalError
    ) -> None:
        conversation.trace.append(TraceStep("refused", f"{action}:{error.code}"))
        conversation.transcript.append(result_block(action, {"error": error.code}))

    def _record_core_failure(self, conversation: Conversation, action: str) -> None:
        conversation.trace.append(TraceStep("refused", f"{action}:CORE_UNAVAILABLE"))
        conversation.transcript.append(result_block(action, {"error": "CORE_UNAVAILABLE"}))


def _parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _local_time(value: str, clinic: dict[str, Any]) -> str:
    local = _parse_instant(value).astimezone(ZoneInfo(clinic["timezone"]))
    return local.strftime("%Y-%m-%d %H:%M")


def _treatment_name(clinic: dict[str, Any], treatment_id: str, language: str) -> str:
    for treatment in clinic["treatments"]:
        if treatment["id"] == treatment_id:
            names: dict[str, str] = treatment["name"]
            return names.get(language) or next(iter(names.values()))
    return treatment_id


def _money(cents: int, clinic: dict[str, Any]) -> str:
    return f"{cents / 100:.2f} {clinic['currency']}"


def _slot_view(slot: dict[str, Any], clinic: dict[str, Any]) -> dict[str, Any]:
    local = _parse_instant(slot["startsAt"]).astimezone(ZoneInfo(clinic["timezone"]))
    return {
        "practitioner_id": slot["practitionerId"],
        "treatment_id": slot["treatmentId"],
        "starts_at": slot["startsAt"],
        "local": local.strftime("%A %Y-%m-%d %H:%M"),
    }


def _view(appointment: dict[str, Any], clinic: dict[str, Any], language: str) -> dict[str, Any]:
    """Keep what the model needs, leaving out the patient's identifier."""
    return {
        "appointment_id": appointment["id"],
        "status": appointment["status"],
        "treatment": _treatment_name(clinic, appointment["treatmentId"], language),
        "practitioner_id": appointment["practitionerId"],
        "local": _local_time(appointment["startsAt"], clinic),
        "price_cents": appointment["priceCents"],
        "deposit_cents": appointment["depositCents"],
        "deposit_forfeited": appointment["depositForfeited"],
    }
