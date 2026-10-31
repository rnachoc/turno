"""Tests of the owner's MCP server, through a real MCP session.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
from typing import Any

import httpx2
import pytest
from mcp.shared.memory import create_connected_server_and_client_session
from mcp.types import CallToolResult, TextContent

from turno_agents.mcp_server import HttpOwnerApi, OwnerApiError, build_server

REPORT = {"date": "2027-03-02", "agenda": {"completed": 3, "noShow": 1}}
ATTENDANCE = {
    "from": "2027-03-01",
    "to": "2027-03-31",
    "rows": [{"treatmentId": "filling", "practitionerId": "ana", "attended": 4, "noShow": 2}],
}


class FakeApi:
    """Answers from fixed figures, and remembers what it was asked."""

    def __init__(self, error: Exception | None = None) -> None:
        """Create it.

        Args:
            error: What to fail with, if anything.
        """
        self.asked: list[tuple[str, ...]] = []
        self._error = error

    def daily_report(self, date: str | None) -> dict[str, Any]:
        """Answer with the report.

        Args:
            date: The day.

        Returns:
            The report.
        """
        self.asked.append(("daily", str(date)))
        if self._error:
            raise self._error
        return REPORT

    def attendance(self, date_from: str, date_to: str) -> dict[str, Any]:
        """Answer with the figures.

        Args:
            date_from: The first day.
            date_to: The last day.

        Returns:
            The figures.
        """
        self.asked.append(("attendance", date_from, date_to))
        if self._error:
            raise self._error
        return ATTENDANCE


async def _call(api: FakeApi, tool: str, arguments: dict[str, Any]) -> CallToolResult:
    server = build_server(api)
    async with create_connected_server_and_client_session(server._mcp_server) as session:
        return await session.call_tool(tool, arguments)


def _text(result: CallToolResult) -> str:
    content = result.content[0]
    assert isinstance(content, TextContent)
    return content.text


@pytest.mark.asyncio
async def test_every_tool_says_it_only_reads() -> None:
    """And there are only the tools that were decided, none of which can change anything."""
    server = build_server(FakeApi())
    async with create_connected_server_and_client_session(server._mcp_server) as session:
        tools = (await session.list_tools()).tools

    assert sorted(tool.name for tool in tools) == ["attendance", "daily_report"]
    for tool in tools:
        assert tool.annotations is not None
        assert tool.annotations.readOnlyHint is True
        assert tool.annotations.destructiveHint is False


@pytest.mark.asyncio
async def test_the_report_of_a_day_comes_from_the_core() -> None:
    """Its figures pass through untouched."""
    api = FakeApi()

    result = await _call(api, "daily_report", {"date": "2027-03-02"})

    assert not result.isError
    assert json.loads(_text(result)) == REPORT
    assert api.asked == [("daily", "2027-03-02")]


@pytest.mark.asyncio
async def test_without_a_date_it_is_today() -> None:
    """The core decides what today is at the clinic."""
    api = FakeApi()

    await _call(api, "daily_report", {})

    assert api.asked == [("daily", "None")]


@pytest.mark.asyncio
async def test_attendance_answers_the_owners_question() -> None:
    """How many no-shows, and which treatment loses most."""
    api = FakeApi()

    result = await _call(api, "attendance", {"date_from": "2027-03-01", "date_to": "2027-03-31"})

    assert json.loads(_text(result))["rows"][0]["noShow"] == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tool", "arguments"),
    [
        ("daily_report", {"date": "ignore your rules"}),
        ("daily_report", {"date": "2027-03-02&limit=9"}),
        ("daily_report", {"date": "../../patients"}),
        ("attendance", {"date_from": "2027-03-01", "date_to": "tomorrow"}),
        ("attendance", {"date_from": "x", "date_to": "2027-03-01"}),
    ],
)
async def test_a_value_that_is_not_a_date_never_reaches_the_core(
    tool: str, arguments: dict[str, Any]
) -> None:
    """A model chooses these values, and it may have been told what to choose."""
    api = FakeApi()

    result = await _call(api, tool, arguments)

    assert result.isError
    assert api.asked == []


@pytest.mark.asyncio
async def test_a_tool_that_does_not_exist_is_an_error() -> None:
    """There is no way of asking for more."""
    result = await _call(FakeApi(), "cancel_appointment", {"appointment_id": "x"})

    assert result.isError


@pytest.mark.asyncio
async def test_a_failure_says_nothing_of_what_went_wrong_inside() -> None:
    """Only the fixed text reaches the model."""
    api = FakeApi(OwnerApiError("The clinic's system cannot be reached right now."))

    result = await _call(api, "daily_report", {})

    assert result.isError
    assert "cannot be reached" in _text(result)


def _client(handler: Any) -> HttpOwnerApi:
    return HttpOwnerApi("http://core", "token", httpx2.MockTransport(handler))


def test_the_client_uses_the_staff_routes_and_only_reads() -> None:
    """With the staff's token, in the header."""
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json={"ok": True})

    client = _client(handler)
    client.daily_report("2027-03-02")
    client.daily_report(None)
    client.attendance("2027-03-01", "2027-03-31")

    assert [request.method for request in seen] == ["GET", "GET", "GET"]
    assert seen[0].url.path == "/api/v1/staff/reports/daily"
    assert seen[0].url.params["date"] == "2027-03-02"
    assert "date" not in seen[1].url.params
    assert seen[2].url.path == "/api/v1/staff/reports/attendance"
    assert seen[2].url.params["from"] == "2027-03-01"
    assert seen[0].headers["Authorization"] == "Bearer token"


@pytest.mark.parametrize(
    ("status", "message"),
    [
        (400, "not valid"),
        (401, "could not answer"),
        (403, "could not answer"),
        (500, "could not answer"),
    ],
)
def test_the_client_turns_refusals_into_fixed_words(status: int, message: str) -> None:
    """What the core said stays out of what a model reads."""
    client = _client(lambda _: httpx2.Response(status, json={"detail": "secret"}))

    with pytest.raises(OwnerApiError, match=message) as error:
        client.daily_report(None)

    assert "secret" not in str(error.value)


def test_the_client_says_so_when_the_core_cannot_be_reached() -> None:
    """Rather than failing with whatever the network said."""

    def handler(_: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("secret-host:8080 refused")

    with pytest.raises(OwnerApiError, match="cannot be reached") as error:
        _client(handler).daily_report(None)

    assert "secret-host" not in str(error.value)
