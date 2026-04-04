# Qorche

Run multiple workers in parallel on the same repo. Conflicts are detected automatically, not at merge time.

**In practice:** Three parallel Claude Code agents completed in 62s vs ~180s sequential on a real codebase, with zero conflicts detected automatically via MVCC snapshots.

Qorche is an MVCC engine for codebases , it tracks concurrent filesystem mutations, detects conflicts in real-time, resolves them deterministically, and maintains a complete audit trail. It includes a task orchestrator and works with any process that modifies files: LLM agents, build tools, formatters, code generators, CI steps.

[![CI](https://github.com/swatarianess/qorche/actions/workflows/ci.yml/badge.svg)](https://github.com/swatarianess/qorche/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Why Qorche?

If you're running parallel agents today, you're probably using git worktrees + tmux. That works until two agents modify the same file and you discover the conflict at merge time, after both have finished.

|                    | Git Worktrees                       | Composio Agent Orchestrator  | AgentFS                               | Qorche                                         |
|--------------------|-------------------------------------|------------------------------|---------------------------------------|------------------------------------------------|
| Conflict detection | At merge time (after agents finish) | At merge time (git-based)    | N/A (single-agent isolation)          | After each parallel group completes            |
| Retry on conflict  | Manual, re-run from scratch         | Manual                       | N/A                                   | Automatic, loser retries against updated state |
| Scope audit        | None                                | None                         | None                                  | Detects writes outside declared file scope     |
| Multi-agent coord  | None (agents unaware of each other) | Fleet management, CI routing | Single agent per sandbox              | DAG scheduling + MVCC across concurrent agents |
| Isolation model    | Full repo copy per worktree         | Full repo copy per worktree  | SQLite-backed copy-on-write per agent | Single checkout, snapshot-based diffing        |
| Audit trail        | Git history only                    | Session logs                 | SQLite queryable history              | Append-only WAL + before/after snapshots       |
| Agent-agnostic     | Yes                                 | Claude Code, Codex, Aider    | Any (via SDK/FUSE)                    | Yes, any process that modifies files           |

## Quick start

```bash
# Install (download native binary, no JVM required)
# https://github.com/swatarianess/qorche/releases

# Initialize a new project (detects language, generates tasks.yaml + .qorignore)
qorche init

# Preview execution plan
qorche plan tasks.yaml

# Validate task file without running
qorche validate tasks.yaml

# Run the task graph
qorche run tasks.yaml

# Check workspace state
qorche status
```

`qorche init` detects your project type (Kotlin, Java, Node, Python, Rust, Go, Maven) and generates an appropriate `tasks.yaml` and `.qorignore` file automatically.

### Building from source

```bash
./gradlew test                            # Run tests
./gradlew :cli:run --args="plan tasks.yaml"    # Dry-run
./gradlew :cli:run --args="run tasks.yaml"     # Execute
./gradlew :cli:nativeCompile              # Build native binary (requires GraalVM)
./gradlew :native:nativeCompile           # Build shared library (libqorche)
```

## Task definition (YAML)

```yaml
project: my-project
tasks:
  - id: lint
    instruction: "Run linter on source files"
    files: [src/]

  - id: test
    instruction: "Run test suite"
    files: [test/]

  - id: refactor
    instruction: "Refactor shared utilities"
    files: [src/utils/]       # overlaps with lint's src/ scope, conflict detected if both modify same file
    max_retries: 1            # on conflict, retry once against the winner's changes

  - id: build
    instruction: "Build output"
    depends_on: [lint, test]  # waits for both to complete
    files: [dist/]
```

Tasks without dependencies run in parallel. If two parallel tasks modify the same file, the earlier task in YAML order wins and the loser is retried automatically (up to `max_retries`).

### Per-task runners

Each task can specify which runner executes it via the `runner` field, referencing a named entry in the top-level `runners` map. This lets you mix different tools in the same DAG — cheap local models for simple tasks, frontier models for complex analysis, shell commands for deterministic steps.

```yaml
project: my-project
runners:
  claude:
    type: claude-code
    extra_args: [--dangerously-skip-permissions]
  shell:
    type: shell
    allowed_commands: [npm, pytest]
    timeout_seconds: 120

tasks:
  - id: analyze
    instruction: "Identify root causes from build logs"
    runner: claude
    files: [analysis/]

  - id: run-tests
    instruction: "pytest src/"
    runner: shell
    files: [src/, tests/]

  - id: review
    instruction: "Final review of changes"
    # no runner = uses default CLI runner
    depends_on: [analyze, run-tests]
```

Supported runner types: `claude-code`, `shell`. Tasks without a `runner` field use the default CLI runner (Claude Code).

## Terminal output

```
$ qorche plan tasks.yaml
Project: my-project
Task graph: 4 tasks

Execution order (sequential):
  1. lint (implement) - no dependencies [src/]
  2. test (implement) - no dependencies [test/]
  3. refactor (implement) - no dependencies [src/utils/]
  4. build (implement) - depends on: lint, test [dist/]

Parallel groups:
  Group 1: lint, test, refactor
  Group 2: build

  Warning: lint and refactor overlap on src/

Use 'qorche run tasks.yaml' to execute.
```

```
$ qorche run tasks.yaml
Project: my-project
Tasks: 4

[lint] Starting: Run linter on source files
[test] Starting: Run test suite
[refactor] Starting: Refactor shared utilities
[lint] Done: +0 added, ~2 modified, -0 deleted (1.2s)
[test] Done (no changes) (0.8s)
[CONFLICT] lint <-> refactor: src/utils/helpers.kt
  → lint won (YAML position 1), refactor retrying (attempt 1/1)
[refactor] Done: +0 added, ~1 modified, -0 deleted (1.1s)
[build] Starting: Build output
[build] Done: +3 added, ~0 modified, -0 deleted (2.3s)

Conflicts: 1 detected, 1 resolved via retry
Results: 4 completed, 0 failed, 0 skipped
Logs: .qorche/logs/
Total time: 47230ms
```

## How it works

1. **Define** tasks in YAML with dependencies and (optional) file scopes
2. **Plan** with `qorche plan tasks.yaml` to see execution order, parallel groups, and scope overlap warnings
3. **Execute** with `qorche run tasks.yaml`, parallel groups run concurrently, SHA snapshots taken before/after each task
4. **Detect** write-write conflicts via snapshot diff after each group completes, conflicting tasks identified, non-conflicting tasks succeed
5. **Retry** losers automatically against updated filesystem state (configurable via `max_retries`, deterministic winner by YAML order)
6. **Audit** scope violations when workers write outside their declared file scope
7. **Log** everything to `.qorche/wal.jsonl`, complete audit trail for replay and debugging

## Correctness & Guarantees

**What Qorche guarantees:**
- No silent write-write conflicts within a run, every file-level collision is detected and reported
- Deterministic conflict resolution, earlier task in YAML order always wins, reproducible across runs
- Append-only WAL for every state transition, TaskStarted, TaskCompleted, TaskFailed, ConflictDetected, TaskRetryScheduled, TaskRetried, ScopeViolation
- After-snapshots taken even on worker crash, partial writes are visible to conflict detection
- Loser rollback before retry, the retried task starts from clean state, not its own partial output

**What Qorche does not guarantee:**
- Semantic correctness of merged changes, two agents may write to different files and still produce code that doesn't compile
- That declared file scopes are complete, but scope audit catches undeclared writes
- That a retried worker will avoid the conflict, stubborn workers exhaust `max_retries` and fail

## Performance

Benchmarked on a standard dev machine (Windows, JDK 21, `-Xmx64m`). Step duration simulated at 250ms to isolate orchestration overhead.

**Parallelism scales linearly.** 12 independent scoped tasks complete in ~300ms parallel vs 3,157ms sequential, **10x speedup**, approaching theoretical maximum.

| Tasks | Sequential | Parallel | Speedup |
|-------|------------|----------|---------|
| 2     | 523ms      | 296ms    | 1.8x    |
| 4     | 1,052ms    | 297ms    | 3.5x    |
| 8     | 2,099ms    | 314ms    | 6.7x    |
| 12    | 3,157ms    | 317ms    | 10.0x   |

**Conflict detection is nearly free.** Sub-millisecond at any repo size. The cost is snapshot hashing, not conflict checking.

| Files  | Warm Snapshot | Conflict Detection | Overhead per step |
|--------|---------------|--------------------|-------------------|
| 100    | 6ms           | 0.0ms              | 12ms (4.8%)       |
| 1,000  | 41ms          | 0.2ms              | 82ms (32.9%)      |
| 5,000  | 211ms         | 0.8ms              | 423ms             |
| 20,000 | 824ms         | 2.5ms              | 1,650ms           |

For real-world agent tasks that take 30–120 seconds, the overhead of a 1,000-file repo is under 0.3%.

**DAG traversal is negligible.** 500-node chain: 23ms total (0.05ms/node).

**Hash algorithm is configurable.** SHA-1 (default, same as Git), CRC32C (fastest, hardware-accelerated), or SHA-256 (cryptographic). Set via `--hash crc32c|sha1|sha256`.

### Scaling to large repos

With file scoping, a 50k-file monorepo where each task scopes to a 200-file module behaves like a 200-file repo:

```yaml
tasks:
  - id: auth-refactor
    instruction: "Refactor auth middleware"
    files: [services/auth/]           # only hashes files under services/auth/

  - id: payments-feature
    instruction: "Add payment retry logic"
    files: [services/payments/]       # only hashes files under services/payments/

  - id: shared-types
    instruction: "Update shared type definitions"
    files: [libs/shared-types/]
    max_retries: 1                    # might conflict if auth or payments also touch shared types
```

Scoping makes conflict detection sharper, two agents with non-overlapping scopes can never conflict. And scope audit catches the case where an agent writes outside its declared area.

Full benchmark suite: `./gradlew :agent:benchmark`

## Design principles

**Agents are untrusted.** Qorche never relies on workers to report what files they modified. Instead, it takes SHA snapshots of the filesystem before and after each task. This catches all side effects, expected or not, regardless of whether the worker reports them. This principle was validated during dogfooding: Claude Code in `--print` mode emits no FileModified events, yet Qorche detected all changes correctly via snapshots.

**Snapshots are ground truth.** Conflict detection compares snapshot hashes, not event streams. This makes Qorche work with any worker type: LLM agents that don't report file changes, shell scripts, build tools, or anything else that modifies files.

**Crash-safe.** If a worker crashes mid-execution, the after-snapshot still captures the dirty filesystem state. Partial writes are visible to conflict detection.

## Use cases

**Multi-agent coding** 

Run multiple LLM agents (Claude Code, Aider, OpenHands) on the same repo simultaneously. Qorche detects when two agents modify the same file and retries the loser against the winner's changes.

**Monorepo parallel tasks**

Tools like Nx and Gradle `--parallel` assume tasks are isolated by package, but shared config files (`tsconfig.base.json`, `package.json`) break that assumption. Qorche adds per-file conflict detection on top.

**Code generation**

Run protobuf, OpenAPI, and GraphQL generators in parallel. When generators write to overlapping output directories, Qorche detects the conflict instead of silently producing corrupt output.

**Database migrations**

Two developers generate migrations concurrently and both get number `0042`. Qorche detects the collision immediately. The retry re-runs the loser, which now sees `0042` exists and generates `0043`.

**Parallel formatting/linting**

Run `prettier --write` and `eslint --fix` in parallel. Formatters are idempotent, so retry always produces the correct result.

**IaC code generation**

Concurrent `cdk synth` or Terragrunt runs with conflict detection on shared output directories.

## Embedding (shared library)

Qorche is also available as a shared library (`libqorche`) for embedding in non-JVM applications via C FFI. Build with `./gradlew :native:nativeCompile`.

Exported functions: `qorche_version`, `qorche_validate_yaml`, `qorche_plan`, `qorche_snapshot`, `qorche_diff`, `qorche_free`. All return JSON strings. See `native/examples/test_libqorche.py` for a working Python example using ctypes.

```python
# Python example
lib = ctypes.CDLL("libqorche.so")
# ... GraalVM isolate setup ...
result = lib.qorche_plan(thread, b"tasks.yaml")
data = json.loads(result.decode("utf-8"))
print(f"Project: {data['project']}, Tasks: {data['task_count']}")
```

## CLI commands

| Command    | Description                                                                |
|------------|----------------------------------------------------------------------------|
| `init`     | Initialize project, detects language, generates tasks.yaml and .qorignore |
| `run`      | Execute a task graph (or single instruction)                               |
| `plan`     | Preview execution order and parallel groups                                |
| `validate` | Check a YAML task file for errors                                          |
| `status`   | Show workspace state (snapshots, WAL, logs)                                |
| `logs`     | List or view per-task output logs                                          |
| `history`  | List past snapshots                                                        |
| `diff`     | Show file changes between two snapshots                                    |
| `clean`    | Remove stored data from .qorche/                                           |
| `schema`   | Print JSON Schema for tasks.yaml (editor autocomplete and validation)      |
| `version`  | Print version                                                              |

JSON output available via `--output json` on `run` and `plan` commands.

### Editor integration

Use `qorche schema` to get autocomplete and validation in your editor:

```bash
# Add modeline to your tasks.yaml
# yaml-language-server: $schema=https://qorche.dev/schema/tasks.json

# Or export locally for offline use
qorche schema --output .qorche/tasks.schema.json
```

Supports VS Code (Red Hat YAML), IntelliJ (native), and nvim (yaml-language-server).

## Project structure

```
qorche/
├── core/       # Orchestrator, snapshots, MVCC, DAG, WAL (zero domain-specific deps)
├── agent/      # Runner implementations (MockAgentRunner, ShellRunner, ClaudeCodeAdapter)
├── cli/        # CLI entry point via Clikt (run, plan, init, validate, status, logs, diff, clean, schema)
└── native/     # Shared library (libqorche) via GraalVM --shared, C FFI entry points
```

Module boundaries are strict: `core/` depends on nothing, `agent/` depends on `core/`, `cli/` depends on both. See [DEVELOPMENT.md](DEVELOPMENT.md) for the full development guide.

## Status

**Core (complete):** M0–M3, project scaffold, snapshot system, task graph execution, parallel execution with MVCC conflict detection, retry-on-conflict, scope audit, loser rollback.

**CLI (complete):** JSON output, colored terminal output, progress reporting, native binary builds, GitHub Releases, per-task logs, status command, init with project detection, validate command, clean command.

**Shared library (complete):** C FFI entry points for version, validate, plan, snapshot, diff. Python test harness.

**Planned:** MCP server for agent integration, ConflictResolver interface (pluggable merge strategies), validation pipeline (run tests before accepting merged state), TUI monitor, AgentFS integration.

## Requirements

- JDK 21+ (for building from source)
- Or download a [pre-built native binary](https://github.com/swatarianess/qorche/releases) (no JVM required)

## Contributing

Contributions welcome. See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions, architecture overview, and coding conventions. The project uses `CLAUDE.md` files in each module for Claude Code integration, these are also useful as quick-reference guides for any contributor.

## License

Apache 2.0