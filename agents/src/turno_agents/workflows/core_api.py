"""The part of the core's API that the workflow worker can use, with its own token.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from typing import Any, Protocol

import httpx2

_TIMEOUT_SECONDS = 15.0


class WorkflowCoreError(Exception):
    """The core could not answer. Temporal retries the activity that called it."""


class WorkflowCore(Protocol):
    """What a workflow can ask of the core."""

    def pending_slots(self) -> list[dict[str, Any]]:
        """List the freed slots that no workflow has picked up."""
        ...

    def acknowledge(self, slot_id: str) -> None:
        """Record that a workflow is taking care of a slot."""
        ...

    def next_offer(self, slot_id: str, key: str) -> dict[str, Any]:
        """Ask the core what to do next with a slot."""
        ...

    def offer_outcome(self, offer_id: str) -> str:
        """Read how an offer ended."""
        ...


class HttpWorkflowCore:
    """Talks to the real core over HTTP."""

    def __init__(
        self, base_url: str, token: str, transport: httpx2.BaseTransport | None = None
    ) -> None:
        """Create the client.

        Args:
            base_url: Address of the core.
            token: The worker's API token.
            transport: Replaces the network, for tests.
        """
        self._http = httpx2.Client(
            base_url=f"{base_url.rstrip('/')}/api/v1/workflows",
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
            raise WorkflowCoreError("The core could not be reached") from error
        if response.status_code >= 400:
            raise WorkflowCoreError(f"The core answered {response.status_code}")
        return response.json()

    def pending_slots(self) -> list[dict[str, Any]]:
        """List the freed slots that no workflow has picked up.

        Returns:
            The slots, oldest first.
        """
        result: list[dict[str, Any]] = self._request("GET", "/freed-slots")
        return result

    def acknowledge(self, slot_id: str) -> None:
        """Record that a workflow is taking care of a slot.

        Args:
            slot_id: The slot.
        """
        self._request("POST", f"/freed-slots/{slot_id}/acknowledge")

    def next_offer(self, slot_id: str, key: str) -> dict[str, Any]:
        """Ask the core what to do next with a slot.

        Args:
            slot_id: The slot.
            key: The idempotency key, so that a retried activity gets the same answer.

        Returns:
            The core's decision.
        """
        headers = {"Idempotency-Key": key}
        result: dict[str, Any] = self._request(
            "POST", f"/freed-slots/{slot_id}/next-offer", headers=headers
        )
        return result

    def offer_outcome(self, offer_id: str) -> str:
        """Read how an offer ended.

        Args:
            offer_id: The offer.

        Returns:
            ``PENDING``, ``ACCEPTED`` or ``DECLINED``.
        """
        return str(self._request("GET", f"/offers/{offer_id}")["outcome"])
