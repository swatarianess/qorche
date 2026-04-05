# Module agent

Concrete [AgentRunner][io.qorche.core.AgentRunner] implementations and the runner registry
that maps YAML configuration to runner instances.

## Adapters

| Runner | Description |
|--------|-------------|
| [ClaudeCodeAdapter][io.qorche.agent.ClaudeCodeAdapter] | Invokes the Claude Code CLI as a subprocess |
| [ShellRunner][io.qorche.agent.ShellRunner] | Runs allow-listed shell commands with security constraints |
| [MockAgentRunner][io.qorche.agent.MockAgentRunner] | Test-only runner for unit and integration tests |

## Runner Registry

[RunnerRegistry][io.qorche.agent.RunnerRegistry] builds a `Map<String, AgentRunner>` from
the `runners:` block in `tasks.yaml`, mapping type strings like `"claude-code"` and `"shell"`
to their concrete implementations.

# Package io.qorche.agent

Agent runner implementations and runner registry for YAML-configured task dispatch.
