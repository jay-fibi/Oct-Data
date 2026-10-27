# Cline greeting MCP server

Uses the installed Python MCP SDK (tested with version 1.18.0) over stdio.
Registered in Cline as `workspace-person-greeter`; existing servers are unchanged.
Tool approvals remain manual.

Ask Cline: **Use workspace-person-greeter to greet Jay.**

Tool: `greet_person`, arguments: `{"EVIEDEBUG_name": "Jay"}`.
Result: `Hello, Jay! Nice to meet you!`

The parameter prefix follows this workspace's Python variable naming rule.
Whitespace is trimmed, and empty names are rejected.

Run the integration test:

```sh
/Library/Frameworks/Python.framework/Versions/3.12/bin/python3 /Users/jaygohil/Desktop/27Oct-JB/mcp-greeter/test_server.py
```

Cline settings: `/Users/jaygohil/Library/Application Support/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`.
If the new server does not appear automatically, reload the VS Code window.