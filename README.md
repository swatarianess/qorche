# Qorche

A deterministic, domain-agnostic orchestrator for concurrent filesystem mutations.

Qorche uses MVCC-inspired filesystem concurrency control, task DAG scheduling, and snapshot-based conflict detection to coordinate any concurrent workers that modify files.

## What it does

- Runs multiple workers (LLM agents, build tools, formatters, code generators) in parallel on the same repo
- Detects write-write conflicts via SHA-256 file snapshots — no file locking needed
- Schedules tasks based on dependency DAGs with topological ordering
- Audits scope violations when workers write outside their declared file scope
- Logs everything to an append-only WAL for replay and debugging

## Project structure

```
qorche/
├── core/       # Orchestrator, snapshots, MVCC, DAG, WAL (zero domain-specific deps)
├── agent/      # Runner implementations (MockAgentRunner, ShellRunner, ClaudeCodeAdapter)
├── cli/        # CLI entry point via Clikt (run, plan, history, diff)
└── docs/       # Phase plan and implementation progress
```

Module boundaries are strict: `core/` depends on nothing, `agent/` depends on `core/`, `cli/` depends on both.

## Quick start

```bash
# Run tests
./gradlew test

# Dry-run a task graph
./gradlew :cli:run --args="plan tasks.yaml"

# Run a task graph
./gradlew :cli:run --args="run tasks.yaml"
```

## Task definition (YAML)

```yaml
project: my-project
tasks:
  - id: lint
    instruction: "Run linter"
    files: [src/]
  - id: test
    instruction: "Run tests"
    files: [test/]
  - id: build
    instruction: "Build output"
    depends_on: [lint, test]
    files: [dist/]
```

Tasks without dependencies run in parallel. Qorche detects conflicts if two parallel tasks modify the same file.

## Requirements

- JDK 21+
- Gradle 9.x (wrapper included)

## License

Apache 2.0
