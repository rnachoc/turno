"""The slot refill from end to end: real core, real Temporal and the real worker. No model involved.

It needs the stack running with the ``e2e-clinic`` tenant, which may contact patients at any hour, so
that the result does not depend on the time of day. Run with ``make test-e2e``.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import os
import secrets
import time
from collections.abc import Callable, Iterator
from datetime import date, timedelta
from typing import Any

import httpx2
import pytest

from turno_agents.core_client import HttpCoreClient

pytestmark = pytest.mark.e2e

# The worker looks for freed slots every 10 seconds, and for answers every minute.
PICK_UP_SECONDS = 60
SETTLE_SECONDS = 5
ANSWER_SECONDS = 100


@pytest.fixture
def core() -> Iterator[HttpCoreClient]:
    """Provide a client of the running core, as the agents use it."""
    url = os.environ.get("TURNO_CORE_URL")
    token = os.environ.get("TURNO_API_TOKEN_AGENTS")
    if not url or not token:
        pytest.fail("Set TURNO_CORE_URL and TURNO_API_TOKEN_AGENTS: this test must not be skipped")
    client = HttpCoreClient(url, token)
    yield client
    client.close()


@pytest.fixture(autouse=True)
def no_slots_left_from_other_tests() -> None:
    """Let the worker deal with earlier cancellations, or their slots would go to this waitlist."""
    url = os.environ["TURNO_CORE_URL"]
    token = os.environ.get("TURNO_API_TOKEN_WORKFLOWS")
    if not token:
        pytest.fail("Set TURNO_API_TOKEN_WORKFLOWS: this test must not be skipped")
    headers = {"Authorization": f"Bearer {token}"}

    def nothing_pending() -> bool:
        pending = httpx2.get(f"{url}/api/v1/workflows/freed-slots", headers=headers, timeout=5)
        return pending.status_code == 200 and pending.json() == []

    _wait_for(nothing_pending, PICK_UP_SECONDS)
    # A workflow that has just started still has to ask, and finds nobody waiting yet.
    time.sleep(SETTLE_SECONDS)


def _key() -> str:
    return secrets.token_hex(16)


def _patient(core: HttpCoreClient) -> str:
    phone = f"+3469{secrets.randbelow(10**7):07d}"
    patient: dict[str, Any] = core.register_patient("E2E Patient", phone, "es")
    return str(patient["id"])


def _join(core: HttpCoreClient, patient: str) -> None:
    core._request(
        "POST",
        f"/patients/{patient}/waitlist",
        headers={"Idempotency-Key": _key()},
        json={"treatmentId": "check-up"},
    )


def _wait_for(condition: Callable[[], Any], seconds: int) -> Any:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(1)
    pytest.fail(f"Nothing happened in {seconds} seconds")


def _hold_of(core: HttpCoreClient, patient: str) -> dict[str, Any] | None:
    holds = [a for a in core.appointments(patient) if a["status"] == "HOLD"]
    return holds[0] if holds else None


def _free_a_slot(core: HttpCoreClient) -> dict[str, Any]:
    """Book the first free slot for someone and cancel it, which starts the workflow."""
    start = date.today() + timedelta(days=10)
    slot = core.availability(
        "check-up", start.isoformat(), (start + timedelta(days=20)).isoformat(), None
    )[0]
    owner = _patient(core)
    held = core.hold(owner, slot["practitionerId"], slot["treatmentId"], slot["startsAt"], _key())
    core.confirm(owner, held["id"], _key())
    core.cancel(owner, held["id"], _key())
    return slot


def test_a_cancelled_slot_goes_down_the_waitlist_until_someone_takes_it(
    core: HttpCoreClient,
) -> None:
    """The first patient declines, so the second gets it, books it, and the third is left alone."""
    first, second, third = _patient(core), _patient(core), _patient(core)
    for patient in (first, second, third):
        _join(core, patient)
        time.sleep(0.01)

    slot = _free_a_slot(core)

    offer = _wait_for(lambda: _hold_of(core, first), PICK_UP_SECONDS)
    assert offer["startsAt"] == slot["startsAt"]
    assert _hold_of(core, second) is None

    core.release(first, offer["id"], _key())

    next_offer = _wait_for(lambda: _hold_of(core, second), ANSWER_SECONDS)
    assert next_offer["startsAt"] == slot["startsAt"]

    confirmed = core.confirm(second, next_offer["id"], _key())
    assert confirmed["status"] == "CONFIRMED"

    # Enough for the workflow to notice and to have offered it to anybody else, if it were to.
    time.sleep(ANSWER_SECONDS / 1.5)
    assert _hold_of(core, third) is None
    assert core.appointments(third) == []
