"""The owner's MCP server as an assistant runs it: a separate process, over standard input and output.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import os
import sys
from datetime import date

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.types import TextContent

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_an_assistant_can_ask_the_real_core_through_the_real_server() -> None:
    """Anything else on standard output would break the protocol, so this also proves there is none."""
    url = os.environ.get("TURNO_CORE_URL")
    token = os.environ.get("TURNO_API_TOKEN_STAFF")
    if not url or not token:
        pytest.fail("Set TURNO_CORE_URL and TURNO_API_TOKEN_STAFF: this test must not be skipped")
    parameters = StdioServerParameters(
        command=sys.executable,
        args=["-m", "turno_agents.mcp_server"],
        env={**os.environ, "TURNO_CORE_URL": url, "TURNO_API_TOKEN_STAFF": token},
    )
    today = date.today().isoformat()
    async with (
        stdio_client(parameters) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        tools = await session.list_tools()
        report = await session.call_tool("daily_report", {"date": today})
        wrong = await session.call_tool("daily_report", {"date": "not a date"})

    assert sorted(tool.name for tool in tools.tools) == ["attendance", "daily_report"]
    assert not report.isError
    content = report.content[0]
    assert isinstance(content, TextContent)
    assert json.loads(content.text)["date"] == today
    assert wrong.isError
