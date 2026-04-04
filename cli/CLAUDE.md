# cli/ module — Entry point and terminal interface

**Package**: io.qorche.cli

## Purpose

The composition root. Wires together core/ and agent/, provides the CLI
interface using Clikt, and handles all terminal output formatting.

## Dependency rule

Depends on:
- core/ (task graph, snapshots, WAL, data models)
- agent/ (AgentRunner implementations)
- Clikt (CLI argument parsing)
- Kotlin stdlib, kotlinx.coroutines

This is the ONLY module that produces user-facing output. core/ and agent/
never print to stdout/stderr directly.

## Key classes

### Main.kt
Entry point. Configures Clikt and dispatches to command handlers. Minimal code.

### Commands.kt
Clikt command definitions:
- `run <instruction-or-file>`: Execute single instruction or task YAML file
- `plan <file>`: Show execution plan without running (dry run)
- `status`: Show current task graph state
- `history`: Show past snapshots and task executions
- `diff <id1> <id2>`: Show file differences between two snapshots
- `version`: Print version info

### Output.kt
All terminal formatting goes through this class for testability:
- Task graph ASCII visualisation (tree with status indicators)
- Snapshot diff summaries
- Agent output streaming (with timestamps and elapsed time)
- Error formatting
- Colour output (detect terminal capability, fall back to plain text)

## CLI design principles
- No interactive prompts in Phase 1 — all input via arguments and files
- Exit codes defined in core/ExitCode: 0 = success, 1 = task failure, 2 = config error, 3 = conflict
- Stream agent output in real-time — don't buffer until completion
- Show elapsed time for each task and total execution
- `plan` command is critical — users should always preview before running
- Support `--verbose` flag for debug output
- Support `--no-color` flag for piping to files

## Testing
- Test Commands with MockAgentRunner (no LLM calls)
- Test Output formatting with known inputs and expected output
- Test CLI argument parsing edge cases (missing files, invalid YAML)
