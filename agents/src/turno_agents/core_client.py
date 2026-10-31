"""Client of the core API: the only way the agents can change anything.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import uuid
from collections.abc import Sequence
from typing import Any, Protocol

import httpx2

from turno_agents.conversation import TraceStep

_TIMEOUT_SECONDS = 15.0


class CoreRefusalError(Exception):
    """The core refused a request. This is a normal outcome, which the agent has to handle."""

    def __init__(self, code: str, status: int) -> None:
        """Create the error.

        Args:
            code: The stable error code of the core.
            status: The HTTP status.
        """
        super().__init__(code)
        self.code = code
        self.status = status


class CoreUnavailableError(Exception):
    """The core could not be reached, or it failed."""


class CoreApi(Protocol):
    """What the agents can ask of the core."""

    def clinic(self) -> dict[str, Any]:
        """Read the clinic's configuration."""
        ...

    def find_patient(self, phone: str) -> dict[str, Any]:
        """Find a patient by exact phone number."""
        ...

    def register_patient(self, full_name: str, phone: str, language: str) -> dict[str, Any]:
        """Register a patient."""
        ...

    def availability(
        self, treatment_id: str, date_from: str, date_to: str, practitioner_id: str | None
    ) -> list[dict[str, Any]]:
        """List free slots."""
        ...

    def appointments(self, patient_id: str) -> list[dict[str, Any]]:
        """List the appointments of a patient."""
        ...

    def appointment(self, patient_id: str, appointment_id: str) -> dict[str, Any]:
        """Read one appointment of a patient."""
        ...

    def hold(
        self, patient_id: str, practitioner_id: str, treatment_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Hold a slot."""
        ...

    def confirm(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Confirm a hold."""
        ...

    def release(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Give up a hold."""
        ...

    def cancel(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Cancel an appointment."""
        ...

    def reschedule(
        self, patient_id: str, appointment_id: str, practitioner_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Move an appointment."""
        ...

    def record_steps(
        self, patient_id: str, conversation_id: str, first_position: int, steps: Sequence[TraceStep]
    ) -> dict[str, Any]:
        """Report what happened in a conversation."""
        ...

    def find_patient_by_email(self, email: str) -> dict[str, Any]:
        """Find the one patient with an address that a provider has verified."""
        ...

    def pending_notifications(self) -> list[dict[str, Any]]:
        """List what has yet to be told to patients."""
        ...

    def mark_notification_sent(self, notification_id: str, key: str) -> dict[str, Any]:
        """Record that a notice went out."""
        ...


class HttpCoreClient:
    """Talks to the real core over HTTP with the agents' token."""

    def __init__(
        self, base_url: str, token: str, transport: httpx2.BaseTransport | None = None
    ) -> None:
        """Create the client.

        Args:
            base_url: Address of the core.
            token: The agents' API token.
            transport: Replaces the network, for tests.
        """
        self._http = httpx2.Client(
            base_url=f"{base_url.rstrip('/')}/api/v1",
            headers={"Authorization": f"Bearer {token}"},
            timeout=_TIMEOUT_SECONDS,
            transport=transport,
        )

    def close(self) -> None:
        """Release the connections to the core."""
        self._http.close()

    def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = self._http.request(method, path, **kwargs)
        except httpx2.HTTPError as error:
            raise CoreUnavailableError("The core could not be reached") from error
        if response.status_code >= 500:
            raise CoreUnavailableError(f"The core failed with status {response.status_code}")
        if response.status_code >= 400:
            try:
                code = str(response.json().get("code", "UNKNOWN"))
            except ValueError:
                code = "UNKNOWN"
            raise CoreRefusalError(code, response.status_code)
        return response.json()

    def _write(self, path: str, key: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        headers = {"Idempotency-Key": key}
        result: dict[str, Any] = self._request("POST", path, headers=headers, json=body)
        return result

    def clinic(self) -> dict[str, Any]:
        """Read the clinic's configuration.

        Returns:
            The clinic as the core describes it.
        """
        result: dict[str, Any] = self._request("GET", "/clinic")
        return result

    def find_patient(self, phone: str) -> dict[str, Any]:
        """Find a patient by exact phone number, which travels in the body to stay out of logs.

        Args:
            phone: The phone number.

        Returns:
            The patient.
        """
        result: dict[str, Any] = self._request("POST", "/patients/lookup", json={"phone": phone})
        return result

    def register_patient(self, full_name: str, phone: str, language: str) -> dict[str, Any]:
        """Register a patient.

        Args:
            full_name: Full name.
            phone: Phone number.
            language: Language code.

        Returns:
            The new patient.
        """
        body = {"fullName": full_name, "phone": phone, "language": language}
        return self._write("/patients", str(uuid.uuid4()), body)

    def availability(
        self, treatment_id: str, date_from: str, date_to: str, practitioner_id: str | None
    ) -> list[dict[str, Any]]:
        """List free slots.

        Args:
            treatment_id: The treatment.
            date_from: First local date.
            date_to: Last local date.
            practitioner_id: Restricts the result to one practitioner, if given.

        Returns:
            The free slots.
        """
        params = {"treatmentId": treatment_id, "from": date_from, "to": date_to}
        if practitioner_id is not None:
            params["practitionerId"] = practitioner_id
        result: list[dict[str, Any]] = self._request("GET", "/availability", params=params)
        return result

    def appointments(self, patient_id: str) -> list[dict[str, Any]]:
        """List the appointments of a patient.

        Args:
            patient_id: The patient.

        Returns:
            The appointments.
        """
        result: list[dict[str, Any]] = self._request("GET", f"/patients/{patient_id}/appointments")
        return result

    def appointment(self, patient_id: str, appointment_id: str) -> dict[str, Any]:
        """Read one appointment of a patient.

        Args:
            patient_id: The patient.
            appointment_id: The appointment.

        Returns:
            The appointment.
        """
        result: dict[str, Any] = self._request(
            "GET", f"/patients/{patient_id}/appointments/{appointment_id}"
        )
        return result

    def hold(
        self, patient_id: str, practitioner_id: str, treatment_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Hold a slot.

        Args:
            patient_id: The patient.
            practitioner_id: The practitioner.
            treatment_id: The treatment.
            starts_at: The start, as an ISO 8601 instant.
            key: The idempotency key.

        Returns:
            The appointment, on hold.
        """
        body = {
            "practitionerId": practitioner_id,
            "treatmentId": treatment_id,
            "startsAt": starts_at,
        }
        return self._write(f"/patients/{patient_id}/appointments", key, body)

    def confirm(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Confirm a hold.

        Args:
            patient_id: The patient.
            appointment_id: The appointment.
            key: The idempotency key.

        Returns:
            The confirmed appointment.
        """
        return self._write(f"/patients/{patient_id}/appointments/{appointment_id}/confirm", key)

    def release(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Give up a hold.

        Args:
            patient_id: The patient.
            appointment_id: The appointment.
            key: The idempotency key.

        Returns:
            The released appointment.
        """
        return self._write(f"/patients/{patient_id}/appointments/{appointment_id}/release", key)

    def cancel(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Cancel an appointment. The core decides whether the deposit is lost.

        Args:
            patient_id: The patient.
            appointment_id: The appointment.
            key: The idempotency key.

        Returns:
            The cancelled appointment.
        """
        return self._write(f"/patients/{patient_id}/appointments/{appointment_id}/cancel", key)

    def reschedule(
        self, patient_id: str, appointment_id: str, practitioner_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Move an appointment.

        Args:
            patient_id: The patient.
            appointment_id: The appointment.
            practitioner_id: The practitioner for the new time.
            starts_at: The new start, as an ISO 8601 instant.
            key: The idempotency key.

        Returns:
            The moved appointment.
        """
        body = {"practitionerId": practitioner_id, "startsAt": starts_at}
        return self._write(
            f"/patients/{patient_id}/appointments/{appointment_id}/reschedule", key, body
        )

    def record_steps(
        self, patient_id: str, conversation_id: str, first_position: int, steps: Sequence[TraceStep]
    ) -> dict[str, Any]:
        """Report what happened in a conversation. The core ignores the steps it already has.

        Args:
            patient_id: The patient.
            conversation_id: The conversation.
            first_position: The place in the conversation of the first step.
            steps: Consecutive steps.

        Returns:
            How many were new.
        """
        return self._write(
            f"/patients/{patient_id}/conversations/{conversation_id}/steps",
            f"steps-{conversation_id}-{first_position}-{len(steps)}",
            {
                "firstPosition": first_position,
                "steps": [
                    {
                        "kind": step.kind,
                        "detail": step.detail,
                        "inputTokens": step.input_tokens,
                        "outputTokens": step.output_tokens,
                        "estimatedCostUsd": step.estimated_cost_usd,
                        "seconds": step.seconds,
                    }
                    for step in steps
                ],
            },
        )

    def find_patient_by_email(self, email: str) -> dict[str, Any]:
        """Find the one patient with an address, which travels in the body to stay out of logs.

        Args:
            email: An address that a provider has verified.

        Returns:
            The patient.
        """
        result: dict[str, Any] = self._request(
            "POST", "/patients/lookup-email", json={"email": email}
        )
        return result

    def pending_notifications(self) -> list[dict[str, Any]]:
        """List what has yet to be told to patients.

        Returns:
            The notices, oldest first.
        """
        result: list[dict[str, Any]] = self._request("GET", "/notifications/pending")
        return result

    def mark_notification_sent(self, notification_id: str, key: str) -> dict[str, Any]:
        """Record that a notice went out.

        Args:
            notification_id: The notice.
            key: The idempotency key.

        Returns:
            The answer of the core.
        """
        return self._write(f"/notifications/{notification_id}/sent", key)
