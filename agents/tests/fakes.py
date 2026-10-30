"""A small in-memory core. tests/integration/test_core_contract.py keeps it honest.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import uuid
from typing import Any

from turno_agents.core_client import CoreRefusalError, CoreUnavailableError

CLINIC: dict[str, Any] = {
    "id": "test",
    "name": "Test Clinic",
    "timezone": "Europe/Madrid",
    "currency": "EUR",
    "emergencyNumber": "112",
    "languages": ["es", "en"],
    "openingHours": {"MONDAY": [{"open": "09:00:00", "close": "14:00:00"}]},
    "treatments": [
        {
            "id": "filling",
            "name": {"es": "Empaste", "en": "Filling"},
            "durationMinutes": 45,
            "priceCents": 8000,
            "depositCents": 1500,
        }
    ],
    "practitioners": [{"id": "ana", "name": "Ana", "treatments": ["filling"]}],
    "cancellation": {"freeUntilHoursBefore": 24, "lateCancellationKeepsDeposit": True},
}

SLOT = "2027-03-09T08:00:00Z"
PATIENT = "11111111-1111-4111-8111-111111111111"
OTHER_PATIENT = "22222222-2222-4222-8222-222222222222"


class FakeCore:
    """Follows the rules of the real API that matter to the agent, and records every write."""

    def __init__(self) -> None:
        """Create an empty core."""
        self.appointments_by_id: dict[str, dict[str, Any]] = {}
        self.writes: list[tuple[str, str, str | None]] = []
        self.keys: list[str] = []
        self.down = False
        self.availability_fails = False

    def _check(self) -> None:
        if self.down:
            raise CoreUnavailableError("down")

    def _owned(self, patient_id: str, appointment_id: str) -> dict[str, Any]:
        appointment = self.appointments_by_id.get(appointment_id)
        if appointment is None or appointment["patientId"] != patient_id:
            raise CoreRefusalError("APPOINTMENT_NOT_FOUND", 404)
        return appointment

    def add(self, patient_id: str, status: str = "CONFIRMED", starts_at: str = SLOT) -> str:
        """Create an appointment directly.

        Args:
            patient_id: Its owner.
            status: Its status.
            starts_at: Its start.

        Returns:
            Its identifier.
        """
        appointment_id = str(uuid.uuid4())
        self.appointments_by_id[appointment_id] = {
            "id": appointment_id,
            "patientId": patient_id,
            "practitionerId": "ana",
            "treatmentId": "filling",
            "startsAt": starts_at,
            "endsAt": starts_at,
            "status": status,
            "holdExpiresAt": None,
            "priceCents": 8000,
            "depositCents": 1500,
            "depositForfeited": False,
            "createdAt": "2027-03-01T07:00:00Z",
            "updatedAt": "2027-03-01T07:00:00Z",
        }
        return appointment_id

    def clinic(self) -> dict[str, Any]:
        """Read the clinic."""
        self._check()
        return CLINIC

    def find_patient(self, phone: str) -> dict[str, Any]:
        """Find a patient: only one number exists."""
        self._check()
        if phone != "+34600000001":
            raise CoreRefusalError("PATIENT_NOT_FOUND", 404)
        return {"id": PATIENT, "language": "es", "phone": phone, "fullName": "Laura Secretname"}

    def register_patient(self, full_name: str, phone: str, language: str) -> dict[str, Any]:
        """Register a patient."""
        self._check()
        self.writes.append(("register", phone, None))
        return {"id": OTHER_PATIENT, "language": language, "phone": phone, "fullName": full_name}

    def availability(
        self, treatment_id: str, date_from: str, date_to: str, practitioner_id: str | None
    ) -> list[dict[str, Any]]:
        """List one slot, unless the treatment does not exist."""
        self._check()
        if self.availability_fails:
            raise CoreUnavailableError("down")
        if treatment_id != "filling":
            raise CoreRefusalError("TREATMENT_NOT_FOUND", 422)
        return [
            {"practitionerId": "ana", "treatmentId": "filling", "startsAt": SLOT, "endsAt": SLOT}
        ]

    def appointments(self, patient_id: str) -> list[dict[str, Any]]:
        """List the appointments of a patient."""
        self._check()
        return [a for a in self.appointments_by_id.values() if a["patientId"] == patient_id]

    def appointment(self, patient_id: str, appointment_id: str) -> dict[str, Any]:
        """Read an appointment through its owner."""
        self._check()
        return self._owned(patient_id, appointment_id)

    def hold(
        self, patient_id: str, practitioner_id: str, treatment_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Hold a slot."""
        self._check()
        self.keys.append(key)
        if practitioner_id != "ana":
            raise CoreRefusalError("PRACTITIONER_NOT_FOUND", 422)
        appointment_id = self.add(patient_id, "HOLD", starts_at)
        self.writes.append(("hold", patient_id, appointment_id))
        return self.appointments_by_id[appointment_id]

    def _change(
        self,
        action: str,
        patient_id: str,
        appointment_id: str,
        required: str,
        status: str,
        key: str,
    ) -> dict[str, Any]:
        self._check()
        self.keys.append(key)
        appointment = self._owned(patient_id, appointment_id)
        if appointment["status"] != required:
            raise CoreRefusalError("INVALID_STATE", 409)
        appointment["status"] = status
        self.writes.append((action, patient_id, appointment_id))
        return appointment

    def confirm(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Confirm a hold."""
        return self._change("confirm", patient_id, appointment_id, "HOLD", "CONFIRMED", key)

    def release(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Give up a hold."""
        return self._change("release", patient_id, appointment_id, "HOLD", "RELEASED", key)

    def cancel(self, patient_id: str, appointment_id: str, key: str) -> dict[str, Any]:
        """Cancel a booking."""
        return self._change("cancel", patient_id, appointment_id, "CONFIRMED", "CANCELLED", key)

    def reschedule(
        self, patient_id: str, appointment_id: str, practitioner_id: str, starts_at: str, key: str
    ) -> dict[str, Any]:
        """Move a booking."""
        moved = self._change(
            "reschedule", patient_id, appointment_id, "CONFIRMED", "CONFIRMED", key
        )
        moved["startsAt"] = starts_at
        return moved
