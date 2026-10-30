"""Tests for the format of what the model can propose.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json

import pytest
from pydantic import ValidationError

from turno_agents.proposals import AgentTurn, proposal_schema


def test_the_schema_only_uses_standard_keywords() -> None:
    """The CLI rejects a schema with OpenAPI extensions, and with it every conversation."""
    assert "discriminator" not in json.dumps(proposal_schema())


def test_the_schema_offers_every_action_and_no_more() -> None:
    """The list of actions is the whole of what the model can ask for."""
    options = proposal_schema()["properties"]["action"]["oneOf"]

    assert {option["$ref"].rsplit("/", 1)[1] for option in options} == {
        "NoAction",
        "SearchSlots",
        "ListAppointments",
        "HoldSlot",
        "ReleaseHold",
        "ConfirmAppointment",
        "CancelAppointment",
        "RescheduleAppointment",
        "Escalate",
    }


def test_no_action_can_name_a_patient_a_price_or_a_status() -> None:
    """The model has no way of even expressing what it must not decide."""
    fields = {
        name
        for definition in proposal_schema()["$defs"].values()
        for name in definition.get("properties", {})
    }

    assert not {name for name in fields if "patient" in name or "price" in name or "status" in name}


def test_the_schema_forbids_extra_fields_everywhere() -> None:
    """Invented fields are not ignored: they are refused."""
    schema = proposal_schema()

    assert schema["additionalProperties"] is False
    assert all(d["additionalProperties"] is False for d in schema["$defs"].values())


@pytest.mark.parametrize(
    "action",
    [
        {"type": "confirm_appointment", "appointment_id": "../../other/appointments/1"},
        {"type": "release_hold", "appointment_id": "1 OR 1=1"},
        {"type": "hold_slot", "practitioner_id": "a", "treatment_id": "b", "starts_at": "tomorrow"},
        {
            "type": "hold_slot",
            "practitioner_id": "a",
            "treatment_id": "b",
            "starts_at": "2027-03-09T09:00:00",
        },
        {"type": "search_slots", "treatment_id": "a", "date_from": "soon", "date_to": "2027-03-09"},
    ],
)
def test_malformed_values_are_refused(action: dict[str, str]) -> None:
    """Identifiers must be UUIDs, instants must have a time zone, and dates must be dates."""
    with pytest.raises(ValidationError):
        AgentTurn.model_validate({"reply": "x", "action": action})
