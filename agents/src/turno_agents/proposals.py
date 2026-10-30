"""What the model is allowed to propose: one reply and at most one action, in a closed format.

No action has a patient, a price or a status among its fields, so the model has no way to even
express them. Anything outside this format is rejected before the code looks at it. Appointment
identifiers must be UUIDs because they end up in a URL, where anything else could climb out of the
patient's own path.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from datetime import date
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field


class _Action(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class NoAction(_Action):
    """Nothing to do: the reply is the final answer of this turn."""

    type: Literal["none"]


class SearchSlots(_Action):
    """Look for free slots between two local dates."""

    type: Literal["search_slots"]
    treatment_id: str
    date_from: date
    date_to: date
    practitioner_id: str | None = None


class ListAppointments(_Action):
    """Read the patient's appointments."""

    type: Literal["list_appointments"]


class HoldSlot(_Action):
    """Reserve a slot for a few minutes while the patient decides."""

    type: Literal["hold_slot"]
    practitioner_id: str
    treatment_id: str
    starts_at: AwareDatetime


class ReleaseHold(_Action):
    """Give up a hold."""

    type: Literal["release_hold"]
    appointment_id: UUID


class ConfirmAppointment(_Action):
    """Turn a hold into a booking. It needs the patient's consent."""

    type: Literal["confirm_appointment"]
    appointment_id: UUID


class CancelAppointment(_Action):
    """Cancel a booking. It needs the patient's consent."""

    type: Literal["cancel_appointment"]
    appointment_id: UUID


class RescheduleAppointment(_Action):
    """Move a booking. It needs the patient's consent."""

    type: Literal["reschedule_appointment"]
    appointment_id: UUID
    practitioner_id: str
    starts_at: AwareDatetime


class Escalate(_Action):
    """Hand the conversation over to a person."""

    type: Literal["escalate"]


Action = Annotated[
    NoAction
    | SearchSlots
    | ListAppointments
    | HoldSlot
    | ReleaseHold
    | ConfirmAppointment
    | CancelAppointment
    | RescheduleAppointment
    | Escalate,
    Field(discriminator="type"),
]

# Changes that take effect only after the patient says yes to their exact description.
ConsentAction = ConfirmAppointment | CancelAppointment | RescheduleAppointment

# What can be sent to the core.
CoreAction = SearchSlots | ListAppointments | HoldSlot | ReleaseHold | ConsentAction


class AgentTurn(BaseModel):
    """One answer of the model."""

    model_config = ConfigDict(extra="forbid")

    reply: str
    action: Action


def proposal_schema() -> dict[str, Any]:
    """Return the JSON Schema that the model's answers must follow.

    Pydantic adds a "discriminator" keyword that comes from OpenAPI and is not JSON Schema, which
    the CLI's strict validator rejects. The answers are validated by ``AgentTurn`` anyway.

    Returns:
        The schema.
    """
    schema = AgentTurn.model_json_schema()
    del schema["properties"]["action"]["discriminator"]
    return schema
