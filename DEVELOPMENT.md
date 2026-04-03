# Development Guide

This guide covers everything you need to contribute to Qorche. For machine-readable project conventions used by Claude Code, see `CLAUDE.md`.

## Building and running

Qorche is a multi-module Kotlin project built with Gradle. JDK 21+ is required.

```bash
./gradlew test                        # Run all tests (64MB heap, excludes benchmarks)
./gradlew :agent:benchmark            # Run benchmark suite (100–20k files)
./gradlew :agent:largeBenchmark       # Run large-scale benchmarks (50k–100k files, 512MB heap)
./gradlew :cli:run --args="plan tasks.yaml"    # Dry-run a task graph
./gradlew :cli:run --args="run tasks.yaml"     # Execute a task graph
./gradlew :cli:nativeCompile          # Build native CLI binary (requires GraalVM)
./gradlew :native:nativeCompile       # Build shared library (libqorche)
```

The CLI always runs from the project root directory (`workingDir = rootProject.projectDir` is set in `cli/build.gradle.kts`). This matters because the orchestrator takes snapshots relative to the working directory.

## Project structure

```
qorche/
├── core/       # Orchestrator, snapshots, MVCC, DAG, WAL with zero domain-specific deps
├── agent/      # AgentRunner implementations (MockAgentRunner, ShellRunner, ClaudeCodeAdapter)
├── cli/        # CLI entry point via Clikt (run, plan, init, validate, status, logs, diff, clean)
├── native/     # Shared library (libqorche) via GraalVM --shared, C FFI entry points
└── docs/       # Architecture docs and planning
```

## Module boundaries

These are strict and enforced by Gradle's dependency graph:

- **core/** depends on nothing except Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization, kotlinx.datetime, and kaml (YAML). It has zero references to AI, LLMs, agents, or any specific domain. If you need an external system, define an interface in core/ and implement it elsewhere.
- **agent/** depends on core/ only. Contains concrete `AgentRunner` implementations. Each adapter is self-contained with no adapter depending on another.
- **cli/** depends on core/ and agent/. This is the composition root and the only module that produces user-facing terminal output.
- **native/** depends on core/ only. Provides C FFI entry points for the shared library.

Never import from agent/ or cli/ in core/. If you're tempted to, define an interface in core/ instead.

## Design principles

### Agents are untrusted reporters of their own side effects

This is the most important design axiom. Never rely on an agent's self-reported file modifications for correctness. LLM agents may not report all writes (Claude Code in `--print` mode emits no FileModified events), may report writes that didn't happen, or may crash after partial writes.

The MVCC system independently verifies filesystem state through before/after SHA-256 snapshots. Agent reports are hints for performance optimisation only, snapshots are ground truth.

This principle drives several concrete decisions:
- `FileIndex` is fully cleared before after-snapshots (not selectively invalidated based on agent reports)
- Scope audit operates at the group level (can't attribute undeclared writes to a specific task)
- After-snapshots are always taken, even when the agent throws an exception
- Conflict detection compares snapshot hashes, not agent-reported file lists

### Snapshot-first, not event-first

Correctness comes from filesystem snapshots, not from tracking individual file operations. This makes Qorche work with any worker type regardless of whether it reports what it does, and catches unexpected side effects that event-based approaches would miss.

### Crash-safe

If a worker crashes mid-execution, the after-snapshot still captures the dirty filesystem state. Partial writes are visible to conflict detection. The WAL logs the failure so the audit trail is complete.

## Key components

### Snapshot system (`core/Snapshot.kt`, `core/FileIndex.kt`)
- `SnapshotCreator.create()` walks a directory tree, hashes every file, and produces an immutable `Snapshot`
- `FileIndex` is an mtime-based cache that avoids re-hashing unchanged files (same optimisation as `git status`)
- Line endings are normalised to `\n` before hashing for cross-platform consistency
- All paths stored with forward slashes as canonical form
- Hash algorithm is configurable: SHA-1 (default, same as git), CRC32C (fastest, hardware-accelerated), SHA-256 (cryptographic)

### Task graph (`core/TaskGraph.kt`)
- Hand-rolled DAG with adjacency list with no graph library needed
- Topological sort via DFS for execution order
- Cycle detection via three-color DFS marking
- `parallelGroups()` identifies tasks that can run concurrently

### Conflict detection (`core/ConflictDetector.kt`)
- `detectGroupConflicts()`: O(n²) pairwise comparison of changed file sets across a parallel group
- `resolveConflicts()`: deterministic winner selection based on YAML definition order
- `detectScopeViolations()`: group-level audit comparing actual filesystem changes against declared scopes

### Orchestrator (`core/Orchestrator.kt`)
- `runGraphParallel()`: executes task DAG with concurrent groups, MVCC conflict detection, retry, and scope audit
- `snapshotAndRun()`: core lifecycle: before-snapshot → run agent → clear cache → after-snapshot → WAL log
- Loser rollback deletes the loser's non-conflicting files before retry so the retried task starts from clean state

### WAL (`core/WAL.kt`)
- Append-only JSON Lines file at `.qorche/wal.jsonl`
- Sealed class hierarchy: TaskStarted, TaskCompleted, TaskFailed, ConflictDetected, TaskRetryScheduled, TaskRetried, ScopeViolation
- Every state-changing action is logged before the change is applied
- Concurrent WAL writes (from parallel tasks) are serialized via `kotlinx.coroutines.sync.Mutex`

### Shared library (`native/`)
- `LibQorcheEntryPoints.java`: thin Java layer handling GraalVM `@CEntryPoint` constraints
- `QorcheApi.kt`: pure Kotlin API accepting and returning JSON strings
- Functions: `qorche_version`, `qorche_validate_yaml`, `qorche_plan`, `qorche_snapshot`, `qorche_diff`, `qorche_free`
- Memory contract: every function returning `CCharPointer` allocates via `UnmanagedMemory`; caller must free via `qorche_free()`
- Python test harness in `native/examples/test_libqorche.py`

## GraalVM native-image compatibility

This is critical. The CLI and shared library are compiled to native binaries via GraalVM native-image. These constraints must be followed for all code:

- **No runtime reflection** ever. No `Class.forName()`, no `field.setAccessible()`.
- **No dynamic class loading** or runtime proxy generation.
- **No Gson or Jackson** use `kotlinx.serialization` exclusively.
- **No `java.io.Serializable`** for data transfer.
- Use `@Serializable` on all persistent data classes.
- If adding a new dependency, verify GraalVM compatibility first.
- Native-image configuration lives in `src/main/resources/META-INF/native-image/`.

## Cross-platform rules

Qorche runs on Windows, macOS, and Linux. CI tests on both Ubuntu and Windows.

- Always use `java.nio.file.Path` for file operations, never string concatenation
- Store all paths with forward slashes: `path.replace("\\", "/")`
- Normalise line endings to `\n` before hashing
- Process spawning: use binary name without extension (e.g., `claude` not `claude.exe`). The OS resolves extensions automatically.

## Coding conventions

- Data classes for all value types
- Sealed classes for algebraic types (`AgentEvent`, `TaskStatus`, `WALEntry`)
- Kotlin coroutines for all async work, no raw threads, no `CompletableFuture`
- `val` over `var`, immutable collections over mutable
- No wildcard imports
- `Result<T>` or sealed class results for expected failures; exceptions only for programmer errors
- Agent failures are expected, model them in the type system
- Always clean up child processes on error (shutdown hooks, `try`/`finally`)

## Serialization

- `kotlinx.serialization` with `@Serializable` on all persistent data classes
- JSON as primary format (`kotlinx-serialization-json`)
- YAML for task definitions (`kaml` library)
- WAL uses JSON Lines format (`.jsonl`), one JSON object per line, append-only
- Timestamps: `kotlinx.datetime.Instant`
- Paths: stored as `String` (forward-slash canonical), not `java.nio.file.Path`

## Memory discipline

- Target: < 30MB RSS idle with `-Xmx64m`
- Stream file contents through `MessageDigest`, never read entire files into memory
- Use `Sequence`/`Flow` instead of intermediate `List` copies for large collections
- Minimise object allocations in hot paths (file hashing, snapshot comparison)
- Tests run with `-Xmx64m`; benchmarks with `-Xmx128m`; large-scale with `-Xmx512m`

## Testing

- Core logic tested against `MockAgentRunner`, no LLM calls needed
- `kotlinx-coroutines-test` for async testing
- Integration tests with real processes (`RealProcessRunner`) are included in the standard test suite, these caught a real `ConcurrentHashMap` corruption bug that mock tests missed
- Cross-platform path handling tested explicitly
- Benchmark tests are tagged and excluded from the default `./gradlew test` task
- Test fixtures live in `cli/src/test/resources/fixtures/`

## Local data directory

Qorche stores all local state in `.qorche/` (similar to `.git/`):

```
.qorche/
├── snapshots/        # Snapshot JSON files ({uuid}.json)
├── logs/             # Per-task agent output logs ({taskId}.log)
├── wal.jsonl         # Write-ahead log (JSON Lines, append-only)
└── file-index.json   # Mtime/hash cache for fast re-snapshots
```

This directory should be in `.gitignore` for user projects. The `qorche init` command handles this automatically.

## Adding a new adapter

1. Create a new file in `agent/src/main/kotlin/io/qorche/agent/` (e.g., `OllamaAdapter.kt`)
2. Implement the `AgentRunner` interface from `core/`
3. Your adapter receives an instruction string, a working directory, and an output callback
4. Return a `Flow<AgentEvent>` with lifecycle events (Output, FileModified, Completed, Error)
5. The orchestrator handles everything else, snapshots, conflict detection, retry, WAL logging
6. Add tests using the same patterns as `ShellRunnerTest` or `ClaudeCodeAdapterTest`
7. Do not depend on other adapters, each adapter is self-contained

## CI/CD

- GitHub Actions runs tests on every push to `main`/`develop` and on PRs
- Matrix: `ubuntu-latest` + `windows-latest`
- Releases triggered by semantic-release on merge to `main`
- Native binaries built for Linux (amd64), Windows (amd64), and macOS (arm64) on tagged releases
- Linux binaries compressed with UPX for smaller download size