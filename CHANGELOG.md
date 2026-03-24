# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - 2026-03-24

### Added
- **`Orchestrator.runGraphParallel()`** — concurrent execution within parallel groups via coroutines
- **MVCC conflict detection** — write-write conflicts detected after each parallel group completes
- **Scope audit** — detects undeclared writes outside task file scopes with group-level attribution
- **`WALEntry.ConflictDetected`** — WAL entry for conflict events with file list and base snapshot
- **`WALEntry.ScopeViolation`** — WAL entry for out-of-scope writes with suspect task list
- **`ConflictDetector.detectGroupConflicts()`** — pairwise conflict detection across parallel groups
- **`ConflictDetector.detectScopeViolations()`** — group-level audit for undeclared file modifications
- **Realistic file size benchmarks** — validates mtime cache with real file size distributions
- **DAG propagation benchmarks** — proves failure skip logic is sub-millisecond at 500+ tasks
- **Parallel execution benchmarks** — 10.7x speedup at 12 independent tasks, diamond DAG benchmarks
- Real-process integration tests with concurrent disk writes
- Tests: ParallelExecutionTest (12), new benchmark tests (3)

### Fixed
- **FileIndex concurrency bug** — `HashMap` → `ConcurrentHashMap` to prevent corruption under parallel hashing

### Changed
- CLI `run` command now uses `runGraphParallel()` for YAML task files
- `GraphResult` includes `conflicts` and `scopeViolations` fields
- Removed inline comments from source, replaced with KDoc where needed

## [0.3.0] - 2026-03-22

### Added
- **YAML task definitions** — parse task files via kaml with dependencies, types, and scoped files
- **`TaskYamlParser`** — validates task definitions, detects cycles, builds TaskGraph from YAML
- **`Orchestrator.runGraph()`** — sequential execution in topological order with failure propagation
- **`ShellRunner`** — secure shell command executor with command allowlisting, timeout, and env filtering
- **`qorche plan <file>`** — dry-run showing execution order and parallel group opportunities
- **`qorche run <file.yaml>`** — execute a full task graph from a YAML definition file
- Failed task propagation — downstream dependents automatically skipped with clear reason
- Tests: TaskYamlParserTest (10), OrchestratorGraphTest (5), ShellRunnerTest (8)

## [0.2.0] - 2026-03-22

### Added
- **Parallel file hashing** via coroutine-batched `Dispatchers.IO` — ~2x faster warm snapshots
- **Scoped snapshots** (`createScoped`) — hash only task-relevant paths, not the entire repo
- **Orchestrator** — coordinates agent runs with snapshot lifecycle, WAL logging, and persistence
- **SnapshotStore** — persists snapshots to `.qorche/snapshots/{id}.json`
- **FileIndex persistence** — save/load to `.qorche/file-index.json` for warm cache on startup
- **`SnapshotDiff.summary()`** — human-readable change reports ("+3 added, ~1 modified")
- CLI `history` command — lists past snapshots with timestamps and file counts
- CLI `diff <id1> <id2>` command — shows file changes between two snapshots
- CLI `run` now takes before/after snapshots and displays diff report
- Tests: SnapshotTest (9), WALTest (3), FileIndexTest (4), OrchestratorTest (5)

### Changed
- `SnapshotCreator.create()` is now a `suspend` function for parallel hashing
- Large-scale benchmark task uses separate heap config (`-Xmx512m`)

### Performance improvements (M1 vs M0)
| Files  | M0 Warm Snap | M1 Warm Snap | Improvement |
|--------|-------------|-------------|-------------|
| 1,000  | 89ms        | 41ms        | 2.2x faster |
| 5,000  | 387ms       | 197ms       | 2.0x faster |
| 10,000 | 773ms       | 409ms       | 1.9x faster |
| 20,000 | 1,579ms     | 789ms       | 2.0x faster |

- End-to-end crossover (parallel+MVCC beats sequential) moved from ~1k to ~8k files
- At 5k files with 5 steps: M0 was 0.6x (slower), M1 is **1.4x (faster)**
- At 5k files with 12 steps: **3.2x speedup**
- For repos beyond 10k files, scoped snapshots (`files` field) keep overhead low

## [0.1.0] - 2026-03-22

### Added
- Multi-module Gradle project setup (core, agent, cli) with strict dependency boundaries
- Core domain models: TaskDefinition, TaskStatus, TaskType, TaskNode, AgentEvent, AgentResult
- TaskGraph — DAG with topological sort, cycle detection (DFS three-color), readyTasks(), parallelGroups()
- Snapshot system — SHA-256 file hashing with line-ending normalisation and cross-platform path handling
- FileIndex — mtime+size cache to skip re-hashing unchanged files (same optimisation as git status)
- ConflictDetector — MVCC write-write conflict detection between concurrent agents
- WAL — append-only JSON Lines write-ahead log (TaskStarted, TaskCompleted, TaskFailed)
- MockAgentRunner — configurable test double for pipeline testing without LLM calls
- ClaudeCodeAdapter — cross-platform Claude Code CLI process spawning
- CLI entry point with `run` and `version` commands via Clikt
- Benchmark suite comparing sequential vs parallel execution with MVCC overhead analysis
- Large-scale benchmarks (50k, 100k files) as opt-in via `./gradlew :agent:largeBenchmark`
- docs/IMPLEMENTATION.md for tracking milestone progress
- docs/PHASE1_PLAN.md with full roadmap and architecture decisions
