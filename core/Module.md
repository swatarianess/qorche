# Module core

The domain-agnostic orchestration engine. Contains MVCC-inspired filesystem concurrency
control, task DAG scheduling, snapshot-based conflict detection, and write-ahead logging.

**Zero domain references** — no AI, LLM, CI/CD, or agent-specific concepts. Workers are
anything that modifies files, managed via the [AgentRunner][io.qorche.core.AgentRunner] interface.

## Key types

| Type | Purpose |
|------|---------|
| [Orchestrator][io.qorche.core.Orchestrator] | Coordinates task execution with snapshots and WAL |
| [AgentRunner][io.qorche.core.AgentRunner] | Interface for any worker that modifies files |
| [TaskGraph][io.qorche.core.TaskGraph] | Dependency-aware DAG with topological scheduling |
| [SnapshotCreator][io.qorche.core.SnapshotCreator] | Creates and compares filesystem snapshots |
| [ConflictDetector][io.qorche.core.ConflictDetector] | Detects write-write conflicts between parallel tasks |
| [WALEntry][io.qorche.core.WALEntry] | Sealed hierarchy of write-ahead log entries |
| [FileIndex][io.qorche.core.FileIndex] | mtime-based cache for fast re-snapshots |

# Package io.qorche.core

Core orchestration types, snapshot engine, conflict detection, and task graph scheduling.
