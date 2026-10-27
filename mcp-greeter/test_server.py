"""Exercise initialization, discovery, and tool calls over MCP stdio."""

import asyncio as EVIEDEBUG_asyncio
from pathlib import Path as EVIEDEBUG_Path
import sys as EVIEDEBUG_sys

from mcp import ClientSession as EVIEDEBUG_ClientSession
from mcp import StdioServerParameters as EVIEDEBUG_StdioServerParameters
from mcp.client.stdio import stdio_client as EVIEDEBUG_stdio_client


async def test_greeting():
    EVIEDEBUG_parameters = EVIEDEBUG_StdioServerParameters(
        command=EVIEDEBUG_sys.executable,
        args=[str(EVIEDEBUG_Path(__file__).with_name("server.py"))],
    )
    async with EVIEDEBUG_stdio_client(EVIEDEBUG_parameters) as (
        EVIEDEBUG_read, EVIEDEBUG_write
    ):
        async with EVIEDEBUG_ClientSession(
            EVIEDEBUG_read, EVIEDEBUG_write
        ) as EVIEDEBUG_session:
            await EVIEDEBUG_session.initialize()
            EVIEDEBUG_tools = await EVIEDEBUG_session.list_tools()
            assert [EVIEDEBUG_tool.name for EVIEDEBUG_tool in EVIEDEBUG_tools.tools] == ["greet_person"]
            EVIEDEBUG_result = await EVIEDEBUG_session.call_tool(
                "greet_person", {"EVIEDEBUG_name": "  Jay  "}
            )
            assert not EVIEDEBUG_result.isError
            assert EVIEDEBUG_result.content[0].text == "Hello, Jay! Nice to meet you!"
            EVIEDEBUG_result = await EVIEDEBUG_session.call_tool(
                "greet_person", {"EVIEDEBUG_name": "   "}
            )
            assert EVIEDEBUG_result.isError
            EVIEDEBUG_result = await EVIEDEBUG_session.call_tool("greet_person", {})
            assert EVIEDEBUG_result.isError
    print("PASS: MCP initialization, discovery, greeting, blank and missing names")


if __name__ == "__main__":
    EVIEDEBUG_asyncio.run(test_greeting())