# Module cli

Command-line interface built with [Clikt](https://ajalt.github.io/clikt/). Provides the
`qorche` command with subcommands for running tasks, inspecting snapshots, viewing history,
and generating JSON Schema for editor integration.

## Commands

| Command | Description |
|---------|-------------|
| `run` | Execute a task graph from a YAML file |
| `history` | List stored snapshots |
| `diff` | Compare two snapshots |
| `clean` | Remove `.qorche/` data |
| `plan` | Preview execution order without running |
| `schema` | Print JSON Schema for `tasks.yaml` |

# Package io.qorche.cli

CLI commands and output formatting for the `qorche` tool.
