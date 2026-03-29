# Qorche

Run multiple workers in parallel on the same repo. Conflicts detected automatically, not at merge time.

A deterministic, domain-agnostic orchestrator for concurrent filesystem mutations using MVCC-inspired snapshot-based conflict detection.

## Why Qorche?

If you're running parallel agents today, you're probably using git worktrees + tmux. That works until two agents modify the same file and you discover the conflict at merge time, after both have finished.

|                    | Git Worktrees                        | Qorche                                         |
| ------------------ | ------------------------------------ | ---------------------------------------------- |
| Conflict detection | At merge time (after agents finish)  | After each parallel group completes            |
| Retry on conflict  | Manual, re-run from scratch          | Automatic, loser retries against updated state  |
| Scope audit        | None                                 | Detects writes outside declared file scope     |
| Agent-agnostic     | Yes                                  | Yes, any process that modifies files           |
| Isolation model    | Full repo copy per worktree          | Single checkout, snapshot-based diffing        |
| Audit trail        | Git history only                     | Append-only WAL + before/after snapshots       |

Qorche works with any worker type: LLM agents, build tools, formatters, code generators, CI steps. Anything that modifies files.

## Quick start

```bash
# Download the latest binary from GitHub Releases
# https://github.com/swatarianess/qorche/releases

# Preview execution plan
qorche plan tasks.yaml

# Run the task graph
qorche run tasks.yaml
```

### Building from source

```bash
./gradlew test               # Run tests
./gradlew :cli:run --args="plan tasks.yaml"   # Dry-run
./gradlew :cli:run --args="run tasks.yaml"    # Execute
./gradlew :cli:nativeCompile     # Build native binary (requires GraalVM)
./gradlew :native:nativeCompile  # Build shared library (libqorche.dll/.so/.dylib)
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
    files: [src/utils/]       # overlaps with lint, conflict detected if both modify same file
    max_retries: 1            # on conflict, retry once against the winner's changes

  - id: build
    instruction: "Build output"
    depends_on: [lint, test]  # waits for both to complete
    files: [dist/]
```

Tasks without dependencies run in parallel. If two parallel tasks modify the same file, the earlier task in YAML order wins and the loser is retried automatically (up to `max_retries`).

## Terminal output

```
$ qorche plan tasks.yaml
Project: my-project
Task graph: 4 tasks

Execution order (sequential):
  1. lint (implement) — no dependencies [src/]
  2. test (implement) — no dependencies [test/]
  3. refactor (implement) — no dependencies [src/utils/]
  4. build (implement) — depends on: lint, test [dist/]

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
[lint] Done: +0 added, ~2 modified, -0 deleted
[test] Done (no changes)
[CONFLICT] lint <-> refactor: src/utils/helpers.kt
[refactor] Retrying (attempt 1/1, conflict with lint)
[refactor] Done: +0 added, ~1 modified, -0 deleted
[build] Starting: Build output
[build] Done: +3 added, ~0 modified, -0 deleted

Conflicts: 1 detected
Results: 4 completed, 0 failed, 0 skipped
Logs: .qorche/logs/
Total time: 47230ms
```

## How it works

1. **Define** tasks in YAML with dependencies and (optional) file scopes
2. **Plan** with `qorche plan tasks.yaml` to see execution order, parallel groups, and scope overlap warnings
3. **Execute** with `qorche run tasks.yaml`. Parallel groups run concurrently, snapshots taken before/after each task
4. **Detect** write-write conflicts via snapshot diff after each group completes. Conflicting tasks fail, dependents skip
5. **Retry** losers automatically against updated filesystem state (configurable via `max_retries`)
6. **Audit** scope violations when workers write outside their declared file scope
7. **Log** everything to `.qorche/wal.jsonl` for replay and debugging

## Performance

Benchmarked on a standard dev machine (Windows, JDK 21, `-Xmx64m`). Step duration simulated at 250ms to isolate orchestration overhead.

**Parallelism scales linearly.** 12 independent tasks complete in 317ms parallel vs 3,157ms sequential: **10x speedup**.

| Tasks | Sequential | Parallel | Speedup |
| ----- | ---------- | -------- | ------- |
| 2     | 523ms      | 296ms    | 1.8x    |
| 4     | 1,052ms    | 297ms    | 3.5x    |
| 8     | 2,099ms    | 314ms    | 6.7x    |
| 12    | 3,157ms    | 317ms    | 10.0x   |

**Conflict detection is nearly free.** Sub-millisecond at any repo size. The cost is the snapshot hashing, not the conflict check.

| Files  | Warm Snapshot | Conflict Detection | Overhead per step |
| ------ | ------------ | ------------------ | ----------------- |
| 100    | 6ms          | 0.0ms              | 12ms (4.8%)       |
| 1,000  | 41ms         | 0.2ms              | 82ms (32.9%)      |
| 5,000  | 211ms        | 0.8ms              | 423ms             |
| 20,000 | 824ms        | 2.5ms              | 1,650ms           |

**Where's the overhead?** Snapshot hashing over every file. It scales with file count, not agent count. For a 1,000-file repo, the overhead is ~82ms per step. For real-world agent tasks that take 30-120 seconds, that's under 0.3%.

**Hash algorithm is configurable.** SHA-1 is the default (same algorithm Git uses, fast, no collision risk for change detection). CRC32C is available for maximum speed (hardware-accelerated, ~20 GB/s). SHA-256 is available when cryptographic guarantees are needed. Set via `--hash crc32c|sha1|sha256`.

### Scaling to large repos

The overhead numbers above assume full-repo snapshots (no scoping). In practice, large repos already have module boundaries, and your tasks should declare them:

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

When tasks declare `files:` scopes, Qorche only hashes those paths. A 50k-file monorepo where each task scopes to a 200-file module behaves like a 200-file repo for snapshot overhead. The total repo size stops mattering.

Scoping also makes conflict detection sharper. Two agents with non-overlapping scopes can never conflict. And scope audit catches the case where an agent writes outside its declared area, which is exactly the kind of surprise you want to know about in a shared codebase.

**DAG traversal is negligible.** 500-node chain: 23ms total (0.05ms/node). Graph overhead never becomes a bottleneck.

Full benchmark suite: `./gradlew :agent:benchmark`

## Design principles

**Agents are untrusted.** Qorche never relies on workers to report what files they modified. Instead, it takes snapshots of the filesystem before and after each task. This catches all side effects, expected or not, regardless of whether the worker reports them.

**Snapshots are ground truth.** Conflict detection compares snapshot hashes, not event streams. This makes Qorche work with any worker type: LLM agents that don't report file changes, shell scripts, build tools, or anything else that modifies files.

**Crash-safe.** If a worker crashes mid-execution, the after-snapshot still captures the dirty filesystem state. Partial writes are visible to conflict detection.

## Project structure

```
qorche/
├── core/       # Orchestrator, snapshots, MVCC, DAG, WAL (zero domain-specific deps)
├── agent/      # Runner implementations (MockAgentRunner, ShellRunner, ClaudeCodeAdapter)
├── cli/        # CLI entry point via Clikt (run, plan, history, diff, status, logs, clean)
├── native/     # Shared library (libqorche) via GraalVM --shared, C FFI entry points
└── docs/       # Phase plan and architecture docs
```

Module boundaries are strict: `core/` depends on nothing, `agent/` depends on `core/`, `cli/` depends on both.

## Requirements

- JDK 21+ (for building from source)
- Or download a [pre-built native binary](https://github.com/swatarianess/qorche/releases) (no JVM required)

## License

Apache 2.0
