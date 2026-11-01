"""An MCP server for the clinic's owner, to ask from an assistant such as Claude Desktop.

It only reads, and only figures: a tool here has no way of changing anything, and no patient's name
or message passes through. That is a defence as well as a privacy choice. A name is text that anyone
can choose when registering, and here it would reach a model with other tools at its disposal.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import os
import re
import sys
from typing import Any, Protocol

import httpx2
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

_TIMEOUT_SECONDS = 15.0
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_READ_ONLY = ToolAnnotations(read_only_hint=True, destructive_hint=False, open_world_hint=False)


class OwnerApiError(ToolError):
    """The core could not answer. This message reaches the model, and hides what the core said."""


class OwnerApi(Protocol):
    """What the owner's tools can ask of the core."""

    def daily_report(self, date: str | None) -> dict[str, Any]:
        """Read the report of a day."""
        ...

    def attendance(self, date_from: str, date_to: str) -> dict[str, Any]:
        """Read attendance over a period."""
        ...


class HttpOwnerApi:
    """Reads from the real core with the staff's token. It has no method that writes."""

    def __init__(
        self, base_url: str, token: str, transport: httpx2.BaseTransport | None = None
    ) -> None:
        """Create the client.

        Args:
            base_url: Address of the core.
            token: The staff's API token.
            transport: Replaces the network, for tests.
        """
        self._http = httpx2.Client(
            base_url=f"{base_url.rstrip('/')}/api/v1/staff",
            headers={"Authorization": f"Bearer {token}"},
            timeout=_TIMEOUT_SECONDS,
            transport=transport,
        )

    def _read(self, path: str, params: dict[str, str]) -> dict[str, Any]:
        try:
            response = self._http.get(path, params=params)
        except httpx2.HTTPError as error:
            raise OwnerApiError("The clinic's system cannot be reached right now.") from error
        if response.status_code == 400:
            raise OwnerApiError("Those dates are not valid.")
        if response.status_code != 200:
            raise OwnerApiError("The clinic's system could not answer that.")
        result: dict[str, Any] = response.json()
        return result

    def daily_report(self, date: str | None) -> dict[str, Any]:
        """Read the report of a day.

        Args:
            date: The day at the clinic, or today if not given.

        Returns:
            The report.
        """
        return self._read("/reports/daily", {"date": date} if date else {})

    def attendance(self, date_from: str, date_to: str) -> dict[str, Any]:
        """Read attendance over a period.

        Args:
            date_from: The first day.
            date_to: The last day.

        Returns:
            The figures by treatment and practitioner.
        """
        return self._read("/reports/attendance", {"from": date_from, "to": date_to})


def _valid_date(value: str) -> None:
    if not _DATE.match(value):
        raise OwnerApiError("Dates are written as YYYY-MM-DD.")


def build_server(api: OwnerApi) -> MCPServer:
    """Build the server.

    Args:
        api: Access to the core.

    Returns:
        The server, with its tools.
    """
    server = MCPServer(
        "turno-owner",
        instructions=(
            "Figures of a small clinic, read from its records. Quote them as they are, and say "
            "which dates they cover. Amounts are records: the clinic charges nothing through this."
        ),
    )

    @server.tool(annotations=_READ_ONLY)
    def daily_report(date: str | None = None) -> dict[str, Any]:
        """Report one day: appointments, money, occupancy, waitlist, agents and what waits.

        Args:
            date: The day at the clinic as YYYY-MM-DD, or today if left out.

        Returns:
            The figures of the day.
        """
        if date is not None:
            _valid_date(date)
        return api.daily_report(date)

    @server.tool(annotations=_READ_ONLY)
    def attendance(date_from: str, date_to: str) -> dict[str, Any]:
        """Report attended, missed and cancelled appointments by treatment and practitioner.

        Use it for questions such as how many no-shows there were this month and which treatment
        loses the most appointments. It covers at most a year.

        Args:
            date_from: The first day as YYYY-MM-DD.
            date_to: The last day as YYYY-MM-DD.

        Returns:
            One row per treatment and practitioner, with the value of what was missed.
        """
        _valid_date(date_from)
        _valid_date(date_to)
        return api.attendance(date_from, date_to)

    return server


def main() -> int:
    """Run the server over standard input and output, which is how an assistant starts it.

    Returns:
        The exit code.
    """
    token = os.environ.get("TURNO_API_TOKEN_STAFF")
    if not token:
        # Standard output belongs to the protocol.
        print("Set TURNO_API_TOKEN_STAFF", file=sys.stderr)
        return 2
    url = os.environ.get("TURNO_CORE_URL", "http://127.0.0.1:8080")
    build_server(HttpOwnerApi(url, token)).run("stdio")
    return 0


if __name__ == "__main__":
    sys.exit(main())
