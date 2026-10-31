"""Fills a running stack with synthetic activity, so that the console has something to show.

Everything goes through the public API, under the same rules as anything else. The one exception
is moving appointments into the past, which the API rightly refuses and this does in the database.
So this is for a local demo only, and it needs the stack started with Docker Compose.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import os
import secrets
import subprocess
import sys
import uuid
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx2

_NAMES = [
    "Laura Test",
    "Marcos Prueba",
    "Elena Ficticia",
    "Tomás Demo",
    "Sara Ejemplo",
    "Hugo Sintético",
    "Nuria Muestra",
    "Iván Simulado",
    "Paula Ensayo",
    "Óscar Inventado",
    "Clara Supuesta",
    "Raúl Modelo",
]


class Api:
    """The core, with one client per token."""

    def __init__(self, url: str, agents_token: str, staff_token: str) -> None:
        """Create the clients.

        Args:
            url: Address of the core.
            agents_token: The agents' token.
            staff_token: The staff's token.
        """
        base = f"{url.rstrip('/')}/api/v1"
        self.agents = httpx2.Client(base_url=base, headers=_auth(agents_token), timeout=15)
        self.staff = httpx2.Client(base_url=base, headers=_auth(staff_token), timeout=15)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _post(client: httpx2.Client, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
    response = client.post(path, json=body, headers={"Idempotency-Key": secrets.token_hex(16)})
    response.raise_for_status()
    result: dict[str, Any] = response.json()
    return result


def _new_patient(api: Api, name: str) -> str:
    phone = f"+3469{secrets.randbelow(10**7):07d}"
    return str(
        _post(api.agents, "/patients", {"fullName": name, "phone": phone, "language": "es"})["id"]
    )


def _open_days(clinic: dict[str, Any], start: date, step: int) -> list[date]:
    """List the next two months of days on which the clinic opens, going forwards or backwards."""
    days = [start + timedelta(days=i * step) for i in range(60)]
    return [
        day
        for day in days
        if clinic["openingHours"].get(day.strftime("%A").upper())
        and day.isoformat() not in clinic["closures"]
    ]


def _free_slots(api: Api, treatment: str, day: date) -> list[dict[str, Any]]:
    response = api.agents.get(
        "/availability",
        params={"treatmentId": treatment, "from": day.isoformat(), "to": day.isoformat()},
    )
    response.raise_for_status()
    slots: list[dict[str, Any]] = response.json()
    return slots


def _book(api: Api, patient: str, slot: dict[str, Any], treatment: str) -> str:
    path = f"/patients/{patient}/appointments"
    body = {
        "practitionerId": slot["practitionerId"],
        "treatmentId": treatment,
        "startsAt": slot["startsAt"],
    }
    hold = _post(api.agents, path, body)
    _post(api.agents, f"{path}/{hold['id']}/confirm")
    return str(hold["id"])


def _book_on(api: Api, day: date, treatments: list[str], names: list[str]) -> list[tuple[str, str]]:
    """Book one appointment per name on a day, spread over the free slots.

    Returns:
        Each patient with their appointment.
    """
    booked: list[tuple[str, str]] = []
    for index, name in enumerate(names):
        treatment = treatments[index % len(treatments)]
        slots = _free_slots(api, treatment, day)
        if not slots:
            continue
        patient = _new_patient(api, name)
        booked.append((patient, _book(api, patient, slots[(index * 7) % len(slots)], treatment)))
    return booked


def _move_to_the_past(appointments: list[str], days: int, timezone: str) -> bool:
    """Move appointments back by whole days, which the API refuses. The clock stays the same.

    Returns:
        Whether they fitted, since an earlier run may have left appointments on that day.
    """
    ids = ",".join(f"'{uuid.UUID(appointment)}'" for appointment in appointments)
    sql = (
        f"SET timezone = '{ZoneInfo(timezone).key}'; "
        f"UPDATE appointment SET starts_at = starts_at - interval '{days} days', "
        f"ends_at = ends_at - interval '{days} days' WHERE id IN ({ids});"
    )
    command = 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "sh", "-c", command],
        input=sql,
        text=True,
        capture_output=True,
        check=False,
    )
    if "appointment_no_practitioner_overlap" in result.stderr:
        return False
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return True


def _step(kind: str, detail: str) -> dict[str, Any]:
    return {
        "kind": kind,
        "detail": detail,
        "inputTokens": 0,
        "outputTokens": 0,
        "estimatedCostUsd": 0,
        "seconds": 0,
    }


def _model_call(input_tokens: int, output_tokens: int) -> dict[str, Any]:
    return {
        "kind": "model",
        "detail": "claude-opus-5-5",
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "estimatedCostUsd": round(input_tokens * 5e-6 + output_tokens * 25e-6, 6),
        "seconds": 2.4,
    }


def _report_conversation(api: Api, patient: str, steps: list[dict[str, Any]]) -> None:
    path = f"/patients/{patient}/conversations/{uuid.uuid4()}/steps"
    _post(api.agents, path, {"firstPosition": 0, "steps": steps})


def seed(api: Api, today: date) -> None:
    """Create a closed day, a day ahead, a waitlist and some conversations.

    Args:
        api: The core.
        today: Today at the clinic.
    """
    response = api.agents.get("/clinic")
    response.raise_for_status()
    clinic: dict[str, Any] = response.json()
    treatments = [treatment["id"] for treatment in clinic["treatments"]]

    last_open = _open_days(clinic, today - timedelta(days=1), -1)[0]
    # The same weekday a week ahead has the same people working.
    ahead = last_open + timedelta(days=7)
    if ahead not in _open_days(clinic, ahead, 1):
        ahead += timedelta(days=7)
    past = _book_on(api, ahead, treatments, _NAMES[:7])
    moved = [appointment for _, appointment in past]
    closed_day = next(
        day
        for day in (
            ahead - timedelta(days=(ahead - last_open).days + 7 * week) for week in range(52)
        )
        if _move_to_the_past(moved, (ahead - day).days, clinic["timezone"])
    )
    for _, appointment in past[:3]:
        _post(api.staff, f"/staff/appointments/{appointment}/complete")
    if len(past) > 3:
        _post(api.staff, f"/staff/appointments/{past[3][1]}/no-show")

    next_open = next(
        day for day in _open_days(clinic, today + timedelta(days=1), 1) if day != ahead
    )
    upcoming = _book_on(api, next_open, treatments, _NAMES[7:10])
    if upcoming:
        patient, appointment = upcoming[0]
        waiting = _new_patient(api, _NAMES[10])
        _post(api.agents, f"/patients/{waiting}/waitlist", {"treatmentId": treatments[0]})
        _post(api.agents, f"/patients/{patient}/appointments/{appointment}/cancel")

    someone = _new_patient(api, _NAMES[11])
    _report_conversation(
        api,
        someone,
        [
            _model_call(2100, 180),
            _step("consent", "asked:book"),
            _step("consent", "given:book"),
            _step("action", "book:ok"),
            _model_call(2400, 90),
        ],
    )
    _report_conversation(
        api, past[0][0], [_model_call(1900, 160), _step("handover", "triage_medical")]
    )
    _report_conversation(api, past[1][0], [_step("handover", "triage_emergency")])
    _report_conversation(
        api,
        past[2][0],
        [
            _model_call(2200, 210),
            _step("blocked", "unbacked_state_claim"),
            _model_call(2300, 140),
            _step("refused", "book:SLOT_UNAVAILABLE"),
            _step("handover", "model_failure"),
        ],
    )
    print(
        f"Done. See the report of {closed_day} and the agenda of {next_open} at http://127.0.0.1:4200"
    )


def main() -> int:
    """Run against the stack described by the environment.

    Returns:
        The exit code.
    """
    url = os.environ.get("TURNO_CORE_URL", "http://127.0.0.1:8080")
    agents = os.environ.get("TURNO_API_TOKEN_AGENTS")
    staff = os.environ.get("TURNO_API_TOKEN_STAFF")
    if not agents or not staff:
        print("Set TURNO_API_TOKEN_AGENTS and TURNO_API_TOKEN_STAFF", file=sys.stderr)
        return 2
    api = Api(url, agents, staff)
    timezone = api.agents.get("/clinic").json()["timezone"]
    seed(api, datetime.now(ZoneInfo(timezone)).date())
    return 0


if __name__ == "__main__":
    sys.exit(main())
