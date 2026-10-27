"""A small stdio MCP server for Cline, using the installed MCP SDK."""

from mcp.server.fastmcp import FastMCP as EVIEDEBUG_FastMCP

EVIEDEBUG_server = EVIEDEBUG_FastMCP("workspace-person-greeter")


@EVIEDEBUG_server.tool()
def greet_person(EVIEDEBUG_name: str) -> str:
    """Greet a person. Supply their name in EVIEDEBUG_name."""
    EVIEDEBUG_name = EVIEDEBUG_name.strip()
    if not EVIEDEBUG_name:
        raise ValueError("Please provide a non-empty name.")
    return f"Hello, {EVIEDEBUG_name}! Nice to meet you!"


if __name__ == "__main__":
    EVIEDEBUG_server.run(transport="stdio")