"""What an attack is, and how a result is told apart from a breach.

A result is what was observed, never what the attack claims. An attack that finds its way through
raises ``Breach``, and one that is stopped says which defence did it, which the report then publishes.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import secrets
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum
from typing import Any

import httpx2


class Stopper(Enum):
    """The defence that stopped an attack."""

    AUTHENTICATION = "Authentication"
    PERMISSION = "Permission by token"
    OWNERSHIP = "Route through the patient"
    VALIDATION = "Request validation"
    FIXED_ERRORS = "Errors with fixed text"
    RULE = "Rule of the core"
    DATABASE = "Database constraint"
    PARAMETERS = "Parameterised SQL"
    PROXY = "Console proxy"
    LIMIT = "Rate limit"
    SCHEMA = "Schema of the proposal"
    CONSENT = "Patient's consent"
    GUARD = "Guard on the reply"
    TRIAGE = "Triage by fixed rules"
    ESCAPING = "Escaping of the transcript"
    NO_CAPABILITY = "No such capability"


class Breach(Exception):
    """An attack got what it was after."""


@dataclass(frozen=True)
class Attack:
    """One attempt.

    Attributes:
        id: Stable identifier.
        category: What kind of attack it is.
        goal: What the attacker is after.
        run: Tries it, and says what stopped it.
    """

    id: str
    category: str
    goal: str
    run: Callable[["Stack"], Stopper]


class Stack:
    """The running stack, as attackers and as its legitimate users reach it."""

    def __init__(self, core_url: str, console_url: str, tokens: dict[str, str]) -> None:
        """Connect to the stack.

        Args:
            core_url: Address of the core.
            console_url: Address of the console.
            tokens: The tokens of ``agents``, ``staff`` and ``workflows``.
        """
        self.core_url = core_url.rstrip("/")
        self.console_url = console_url.rstrip("/")
        self.tokens = tokens
        self.anonymous = self._client({})
        self.agents = self._client(tokens)
        self.staff = self._client({"agents": tokens["staff"]})
        self.worker = self._client({"agents": tokens["workflows"]})

    def _client(self, tokens: dict[str, str]) -> httpx2.Client:
        headers = {"Authorization": f"Bearer {tokens['agents']}"} if tokens else {}
        return httpx2.Client(base_url=self.core_url, headers=headers, timeout=20)

    def post(
        self, client: httpx2.Client, path: str, body: Any = None, key: str | None = None
    ) -> httpx2.Response:
        """Write through the API, with a new idempotency key unless one is given.

        Args:
            client: Who is writing.
            path: Where.
            body: What, as JSON.
            key: The idempotency key.

        Returns:
            The answer.
        """
        headers = {"Idempotency-Key": key or secrets.token_hex(16)}
        return client.post(path, json=body, headers=headers)

    def new_patient(self, name: str = "Attack Test") -> str:
        """Register a patient.

        Args:
            name: Their name.

        Returns:
            Their identifier.
        """
        phone = f"+3466{secrets.randbelow(10**7):07d}"
        response = self.post(
            self.agents, "/api/v1/patients", {"fullName": name, "phone": phone, "language": "es"}
        )
        must_be(response, 201)
        return str(response.json()["id"])

    def free_slot(self, treatment: str = "check-up") -> dict[str, Any]:
        """Find a slot far enough ahead to be free to cancel.

        Args:
            treatment: The treatment.

        Returns:
            A slot nobody holds.
        """
        start = date.today() + timedelta(days=5 + secrets.randbelow(20))
        response = self.agents.get(
            "/api/v1/availability",
            params={
                "treatmentId": treatment,
                "from": start.isoformat(),
                "to": (start + timedelta(days=14)).isoformat(),
            },
        )
        must_be(response, 200)
        slots: list[dict[str, Any]] = response.json()
        return slots[secrets.randbelow(len(slots))]

    def hold(self, patient: str, slot: dict[str, Any]) -> str:
        """Hold a slot for a patient.

        Args:
            patient: The patient.
            slot: The slot.

        Returns:
            The appointment.
        """
        response = self.post(
            self.agents,
            f"/api/v1/patients/{patient}/appointments",
            {
                "practitionerId": slot["practitionerId"],
                "treatmentId": slot["treatmentId"],
                "startsAt": slot["startsAt"],
            },
        )
        must_be(response, 201)
        return str(response.json()["id"])

    def book(self, patient: str) -> str:
        """Book a free slot for a patient.

        Args:
            patient: The patient.

        Returns:
            The appointment.
        """
        appointment = self.hold(patient, self.free_slot())
        must_be(
            self.post(
                self.agents, f"/api/v1/patients/{patient}/appointments/{appointment}/confirm"
            ),
            200,
        )
        return appointment

    def status_of(self, patient: str, appointment: str) -> str:
        """Read where an appointment stands.

        Args:
            patient: Its owner.
            appointment: The appointment.

        Returns:
            Its status.
        """
        response = self.agents.get(f"/api/v1/patients/{patient}/appointments/{appointment}")
        must_be(response, 200)
        return str(response.json()["status"])


def must_be(response: httpx2.Response, *statuses: int) -> None:
    """Fail unless the answer is one of the expected ones.

    Args:
        response: The answer.
        *statuses: What it may be.

    Raises:
        Breach: If it is anything else. An error 500 counts: it means the input got further than it
            should have, and nothing can be said about what it did on the way.
    """
    if response.status_code not in statuses:
        raise Breach(f"answered {response.status_code} instead of {statuses}")


def code_of(response: httpx2.Response) -> str:
    """Read the core's code of a refusal.

    Args:
        response: The answer.

    Returns:
        The code, or nothing if it has none.
    """
    try:
        return str(response.json().get("code", ""))
    except ValueError:
        return ""


def refused_with(response: httpx2.Response, status: int, code: str) -> None:
    """Fail unless the answer is exactly this refusal.

    Args:
        response: The answer.
        status: Its status.
        code: Its code.

    Raises:
        Breach: If it is anything else.
    """
    must_be(response, status)
    if code_of(response) != code:
        raise Breach(f"refused with {code_of(response)} instead of {code}")
