"""Runs the agents against the real core, with a scripted model. No model is called.

This is what keeps ``tests/fakes.py`` honest: if the core's API changes, or the agent assumes a field
that does not exist, these tests fail in CI.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import os
import secrets
from collections.abc import Iterator
from datetime import date, timedelta
from typing import Any

import pytest

from tests.fakes import CLINIC, FakeCore
from turno_agents.agent import AgentService
from turno_agents.conversation import Conversation, ConversationState
from turno_agents.core_client import CoreRefusalError, CoreUnavailableError, HttpCoreClient
from turno_agents.llm import ScriptedLlm

pytestmark = pytest.mark.integration

MISSING_APPOINTMENT = "99999999-9999-4999-8999-999999999999"


@pytest.fixture
def core() -> Iterator[HttpCoreClient]:
    """Provide a client of the running core."""
    url = os.environ.get("TURNO_CORE_URL")
    token = os.environ.get("TURNO_API_TOKEN_AGENTS")
    if not url or not token:
        pytest.fail("Set TURNO_CORE_URL and TURNO_API_TOKEN_AGENTS: this test must not be skipped")
    client = HttpCoreClient(url, token)
    yield client
    client.close()


def _new_patient(core: HttpCoreClient) -> dict[str, Any]:
    phone = f"+3469{secrets.randbelow(10**7):07d}"
    return core.register_patient("Contract Test", phone, "es")


def _free_slots(core: HttpCoreClient, treatment: str = "check-up") -> list[dict[str, Any]]:
    """Find slots far enough ahead to be inside the free cancellation period."""
    start = date.today() + timedelta(days=7)
    end = start + timedelta(days=14)
    slots = core.availability(treatment, start.isoformat(), end.isoformat(), None)
    assert slots
    return slots


def _key() -> str:
    return secrets.token_hex(16)


def test_the_clinic_has_every_field_the_agent_reads(core: HttpCoreClient) -> None:
    """The prompts and the agent rely on these names."""
    clinic = core.clinic()

    for field in ("name", "timezone", "currency", "emergencyNumber", "openingHours"):
        assert field in clinic
    treatment = clinic["treatments"][0]
    for field in ("id", "name", "durationMinutes", "priceCents", "depositCents"):
        assert field in treatment
    practitioner = clinic["practitioners"][0]
    for field in ("id", "name", "treatments"):
        assert field in practitioner
    for field in ("freeUntilHoursBefore", "lateCancellationKeepsDeposit"):
        assert field in clinic["cancellation"]


def test_the_fake_clinic_has_the_same_shape_as_the_real_one(core: HttpCoreClient) -> None:
    """Otherwise the unit tests would be testing a different world."""
    real = core.clinic()

    assert set(CLINIC) <= set(real)
    assert set(CLINIC["treatments"][0]) <= set(real["treatments"][0])
    assert set(CLINIC["practitioners"][0]) <= set(real["practitioners"][0])
    assert set(CLINIC["cancellation"]) <= set(real["cancellation"])


def test_the_fake_appointment_has_the_same_shape_as_the_real_one(core: HttpCoreClient) -> None:
    """The same for appointments."""
    patient = _new_patient(core)
    slot = _free_slots(core)[0]
    real = core.hold(
        patient["id"], slot["practitionerId"], slot["treatmentId"], slot["startsAt"], _key()
    )
    fake_core = FakeCore()
    fake = fake_core.appointments_by_id[fake_core.add("anyone")]

    assert set(fake) == set(real)
    core.release(patient["id"], real["id"], _key())


def test_patients_can_be_found_and_are_refused_when_unknown(core: HttpCoreClient) -> None:
    """The lookup follows the documented codes."""
    patient = _new_patient(core)

    assert core.find_patient(patient["phone"])["id"] == patient["id"]
    with pytest.raises(CoreRefusalError) as error:
        core.find_patient("+34600000000")
    assert error.value.code == "PATIENT_NOT_FOUND"
    assert error.value.status == 404


def test_a_whole_life_of_an_appointment(core: HttpCoreClient) -> None:
    """Hold, confirm, move and cancel, each through the client."""
    patient = _new_patient(core)
    slots = _free_slots(core)
    first = slots[0]
    other = next(
        slot
        for slot in slots
        if slot["practitionerId"] == first["practitionerId"] and slot["startsAt"] >= first["endsAt"]
    )

    held = core.hold(
        patient["id"], first["practitionerId"], first["treatmentId"], first["startsAt"], _key()
    )
    assert held["status"] == "HOLD"
    assert core.appointment(patient["id"], held["id"])["status"] == "HOLD"

    confirmed = core.confirm(patient["id"], held["id"], _key())
    assert confirmed["status"] == "CONFIRMED"

    moved = core.reschedule(
        patient["id"], held["id"], other["practitionerId"], other["startsAt"], _key()
    )
    assert moved["startsAt"] == other["startsAt"]

    cancelled = core.cancel(patient["id"], held["id"], _key())
    assert cancelled["status"] == "CANCELLED"
    assert cancelled["depositForfeited"] is False
    assert [a["id"] for a in core.appointments(patient["id"])] == [held["id"]]


def test_a_retry_with_the_same_key_runs_once(core: HttpCoreClient) -> None:
    """The client sends the header the core expects."""
    patient = _new_patient(core)
    slot = _free_slots(core)[0]
    key = _key()

    first = core.hold(
        patient["id"], slot["practitionerId"], slot["treatmentId"], slot["startsAt"], key
    )
    second = core.hold(
        patient["id"], slot["practitionerId"], slot["treatmentId"], slot["startsAt"], key
    )

    assert second["id"] == first["id"]
    assert len(core.appointments(patient["id"])) == 1
    core.release(patient["id"], first["id"], _key())


def test_refusals_arrive_with_their_code(core: HttpCoreClient) -> None:
    """The agent acts on these codes, and it shows them to the model."""
    patient = _new_patient(core)

    with pytest.raises(CoreRefusalError) as missing:
        core.appointment(patient["id"], MISSING_APPOINTMENT)
    assert missing.value.code == "APPOINTMENT_NOT_FOUND"

    with pytest.raises(CoreRefusalError) as treatment:
        core.availability("laser", "2030-01-01", "2030-01-02", None)
    assert treatment.value.code == "TREATMENT_NOT_FOUND"

    slot = _free_slots(core)[0]
    with pytest.raises(CoreRefusalError) as off_grid:
        core.hold(patient["id"], slot["practitionerId"], "check-up", "2030-01-01T03:07:00Z", _key())
    assert off_grid.value.code == "SLOT_NOT_BOOKABLE"


def test_another_patient_cannot_reach_an_appointment(core: HttpCoreClient) -> None:
    """The real core enforces what the agent relies on."""
    owner = _new_patient(core)
    stranger = _new_patient(core)
    slot = _free_slots(core)[0]
    held = core.hold(
        owner["id"], slot["practitionerId"], slot["treatmentId"], slot["startsAt"], _key()
    )

    with pytest.raises(CoreRefusalError) as refusal:
        core.cancel(stranger["id"], held["id"], _key())

    assert refusal.value.code == "APPOINTMENT_NOT_FOUND"
    core.release(owner["id"], held["id"], _key())


def test_the_token_is_checked_and_a_dead_core_is_detected() -> None:
    """A wrong token is a refusal, and an unreachable core is not."""
    url = os.environ["TURNO_CORE_URL"]

    wrong_token = HttpCoreClient(url, "wrong-token")
    with pytest.raises(CoreRefusalError) as refusal:
        wrong_token.clinic()
    wrong_token.close()
    assert refusal.value.code == "UNAUTHENTICATED"

    dead = HttpCoreClient("http://127.0.0.1:9", "any")
    with pytest.raises(CoreUnavailableError):
        dead.clinic()
    dead.close()


def test_a_conversation_books_for_real(core: HttpCoreClient) -> None:
    """The whole agent loop against the real core: only the model is scripted."""
    patient = _new_patient(core)
    slot = _free_slots(core)[0]
    llm = ScriptedLlm()
    agent = AgentService(llm, core)
    conversation = Conversation(id=secrets.token_hex(8), patient_id=patient["id"], language="es")
    start = date.fromisoformat(slot["startsAt"][:10])
    action_search = {
        "type": "search_slots",
        "treatment_id": slot["treatmentId"],
        "date_from": start.isoformat(),
        "date_to": start.isoformat(),
    }
    llm.outputs = [
        {"reply": "...", "action": action_search},
        {"reply": "Hay hueco ese día.", "action": {"type": "none"}},
    ]
    assert agent.handle(conversation, "quiero una revisión") == "Hay hueco ese día."
    assert slot["startsAt"] in llm.prompts[-1]

    def confirm() -> dict[str, Any]:
        appointment_id = core.appointments(patient["id"])[0]["id"]
        return {
            "reply": "...",
            "action": {"type": "confirm_appointment", "appointment_id": appointment_id},
        }

    llm.outputs = [
        {
            "reply": "...",
            "action": {
                "type": "hold_slot",
                "practitioner_id": slot["practitionerId"],
                "treatment_id": slot["treatmentId"],
                "starts_at": slot["startsAt"],
            },
        },
        confirm,
    ]
    question = agent.handle(conversation, "ese me vale")
    assert "responde «sí»" in question
    assert core.appointments(patient["id"])[0]["status"] == "HOLD"

    llm.outputs = [{"reply": "Tu cita está confirmada.", "action": {"type": "none"}}]
    assert agent.handle(conversation, "sí") == "Tu cita está confirmada."

    booked = core.appointments(patient["id"])[0]
    assert booked["status"] == "CONFIRMED"
    assert conversation.state is ConversationState.ACTIVE
    core.cancel(patient["id"], booked["id"], _key())
