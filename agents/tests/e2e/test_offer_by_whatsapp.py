"""A freed slot reaches a patient by WhatsApp, and their answer settles it, through the whole stack.

Only the model is a script, which is not asked anything until after the patient has said yes.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import os
import secrets
import time
from collections.abc import Callable
from datetime import date, timedelta
from typing import Any

import httpx2
import pytest
from fastapi.testclient import TestClient

from turno_agents.channels.gateway import SimulatedGateway
from turno_agents.channels.webhooks import sign
from turno_agents.conversation import ConversationStore
from turno_agents.core_client import HttpCoreClient
from turno_agents.llm import ScriptedLlm
from turno_agents.main import create_app
from turno_agents.settings import Settings

pytestmark = pytest.mark.e2e

SECRET = "e2e-channel-secret-0123456789-abcdefg"
PICK_UP_SECONDS = 90


def _key() -> str:
    return secrets.token_hex(16)


def _wait_for(condition: Callable[[], Any], seconds: int, what: str) -> Any:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(1)
    pytest.fail(f"Nothing happened in {seconds} seconds: {what}")


def _deliver(client: TestClient, phone: str, text: str) -> None:
    payload = {
        "entry": [
            {
                "changes": [
                    {
                        "value": {
                            "contacts": [],
                            "messages": [
                                {
                                    "id": secrets.token_hex(8),
                                    "from": phone.removeprefix("+"),
                                    "timestamp": str(int(time.time())),
                                    "type": "text",
                                    "text": {"body": text},
                                }
                            ],
                        }
                    }
                ]
            }
        ]
    }
    body = json.dumps(payload).encode("utf-8")
    response = client.post(
        "/webhooks/whatsapp",
        content=body,
        headers={"X-Hub-Signature-256": sign(SECRET, body)},
    )
    assert response.status_code == 200, response.text


def _hold_of(core: HttpCoreClient, patient: str) -> dict[str, Any] | None:
    holds = [a for a in core.appointments(patient) if a["status"] == "HOLD"]
    return holds[0] if holds else None


def test_a_no_passes_the_slot_on_and_a_yes_books_it() -> None:
    """Two people wait: the first declines by WhatsApp, the second accepts by WhatsApp."""
    url = os.environ.get("TURNO_CORE_URL")
    token = os.environ.get("TURNO_API_TOKEN_AGENTS")
    if not url or not token:
        pytest.fail("Set TURNO_CORE_URL and TURNO_API_TOKEN_AGENTS: this test must not be skipped")
    core = HttpCoreClient(url, token)
    workflows = os.environ.get("TURNO_API_TOKEN_WORKFLOWS")
    if not workflows:
        pytest.fail("Set TURNO_API_TOKEN_WORKFLOWS: this test must not be skipped")

    def no_slots_pending() -> bool:
        pending = httpx2.get(
            f"{url}/api/v1/workflows/freed-slots",
            headers={"Authorization": f"Bearer {workflows}"},
            timeout=5,
        )
        return pending.status_code == 200 and pending.json() == []

    # Slots left from earlier tests would go to this waitlist, once the worker has dealt with them.
    _wait_for(no_slots_pending, PICK_UP_SECONDS, "older slots to be picked up")
    time.sleep(5)
    # Nobody sends notices in the stack without a model, so older offers would be announced here.
    for notice in core.pending_notifications():
        core.mark_notification_sent(notice["id"], _key())

    def patient() -> tuple[str, str]:
        phone = f"+3465{secrets.randbelow(10**7):07d}"
        return str(core.register_patient("WhatsApp Test", phone, "es")["id"]), phone

    (first, first_phone), (second, second_phone) = patient(), patient()
    for waiting in (first, second):
        core._request(
            "POST",
            f"/patients/{waiting}/waitlist",
            headers={"Idempotency-Key": _key()},
            json={"treatmentId": "check-up"},
        )
        time.sleep(0.01)

    gateway = SimulatedGateway()
    settings = Settings(
        _env_file=None,  # type: ignore[call-arg]
        allowed_hosts=["127.0.0.1"],
        channel_webhook_secret=SECRET,  # type: ignore[arg-type]
    )
    llm = ScriptedLlm([{"reply": "Listo, nos vemos.", "action": {"type": "none"}}])
    app = create_app(settings, llm, core, ConversationStore(), gateway)

    start = date.today() + timedelta(days=10)
    slot = core.availability(
        "check-up", start.isoformat(), (start + timedelta(days=20)).isoformat(), None
    )[0]
    owner = str(core.register_patient("Leaves", f"+3465{secrets.randbelow(10**7):07d}", "es")["id"])
    held = core.hold(owner, slot["practitionerId"], slot["treatmentId"], slot["startsAt"], _key())
    core.confirm(owner, held["id"], _key())
    core.cancel(owner, held["id"], _key())

    def sent_to(phone: str) -> list[str]:
        return [message.text for message in gateway.sent if message.to == phone]

    with TestClient(app, base_url="http://127.0.0.1") as client:
        offered = _wait_for(lambda: sent_to(first_phone), PICK_UP_SECONDS, "the first offer")
        assert "SÍ" in offered[0]
        hold = _hold_of(core, first)
        assert hold is not None
        assert hold["startsAt"] == slot["startsAt"]
        assert sent_to(second_phone) == []

        _deliver(client, first_phone, "No, gracias")
        _wait_for(lambda: _hold_of(core, first) is None, 30, "the first hold to be released")
        assert (
            "otra persona"
            in _wait_for(lambda: sent_to(first_phone)[1:], 30, "the answer to the no")[0]
        )
        assert llm.prompts == []

        _wait_for(lambda: sent_to(second_phone), PICK_UP_SECONDS, "the offer to move on")
        assert _hold_of(core, second) is not None

        _deliver(client, second_phone, "Sí")
        _wait_for(
            lambda: [a for a in core.appointments(second) if a["status"] == "CONFIRMED"],
            30,
            "the second patient's booking",
        )
    booked = [a for a in core.appointments(second) if a["status"] == "CONFIRMED"]
    assert booked[0]["startsAt"] == slot["startsAt"]
    assert not core.pending_notifications()

    # Saying no keeps someone on the list, and they would be first for the next test's slot.
    for entry in core._request("GET", f"/patients/{first}/waitlist"):
        if entry["status"] == "WAITING":
            core._request(
                "POST",
                f"/patients/{first}/waitlist/{entry['id']}/leave",
                headers={"Idempotency-Key": _key()},
            )
    core.close()
