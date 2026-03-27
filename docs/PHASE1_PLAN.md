# Qorche — Phase 1 Planning Document

> **STATUS: COMPLETE** — All Phase 1 milestones (M0, M1, M2) are done.
> Parallel execution + MVCC conflict detection (originally M3/Phase 2) is also implemented.
> This document is retained as architectural reference only.

**Project:** Qorche (qorche.io)
**Package:** io.qorche
**License:** Apache 2.0

---

## Vision

A deterministic, editor-agnostic, agent-agnostic orchestrator for concurrent
filesystem mutations. Qorche runs locally as a daemon, manages concurrent agent
execution using MVCC-inspired concurrency control, and exposes an API that any
editor or CI system can consume.

Think "Kubernetes for LLM agents" — the control plane is deterministic code,
the workers are LLM agents (or any autonomous process that modifies files).

### What Qorche is NOT
- Not an LLM agent framework (Koog, LangGraph, CrewAI handle that)
- Not an editor or IDE (Zed, VS Code, JetBrains Air handle that)
- Not a prompt orchestrator (Marvin, LangChain handle that)
- Not a per-agent sandbox (AgentFS handles that)

### What Qorche IS
- A task DAG scheduler that knows which workers can run in parallel
- An MVCC-style filesystem concurrency controller that prevents conflicts
- A context bus that gives agents awareness of each other's activity
- An observability layer that logs everything for replay and audit

### Domain-agnostic core
io.qorche.core contains ZERO references to AI, LLMs, or agents. It is a
general-purpose concurrent filesystem coordination engine. The AI-specific
adapters live in io.qorche.agent. This means Qorche can expand to:
- **CI/CD**: Eliminate redundant git clones — parallel jobs share one snapshot,
  each gets a copy-on-write delta. Saves compute time and cost.
- **Build systems**: Parallel compilation with conflict detection.
- **Infrastructure-as-code**: Concurrent Terraform/Pulumi plans with overlap detection.
- **Document processing**: Multiple tools (formatters, linters, generators) on same codebase.

AI agent orchestration is the starting market (acute pain, hot market, no solution).
The architecture supports pivoting to any domain where concurrent filesystem
mutation coordination is needed.

### Business model
- Open-source the local daemon (Apache 2.0)
- Monetise the team coordination server and enterprise features (Level 2+)
- Moat is execution quality, protocol adapters, ecosystem, and community

---

## Key concepts

### DAG (Directed Acyclic Graph)
The task scheduler's core data structure. Tasks are nodes, dependencies are directed
edges. "Directed" means A->B (A must finish before B). "Acyclic" means no circular
dependencies. Topological sort determines execution order and identifies which tasks
can run in parallel.

Example:
```
explore --> backend-api --> integration-tests --> verify
        \-> frontend-form -/
```
backend-api and frontend-form are independent and can run in parallel.
integration-tests depends on both and waits.

### MVCC (Multi-Version Concurrency Control)
Borrowed from PostgreSQL. Instead of locking files when an agent starts working
(pessimistic, slow), we let agents work freely and detect conflicts when they try
to commit (optimistic, fast).

Each agent gets a "snapshot" (record of file hashes) when its task begins.
When it finishes, we check: did any file the agent modified also get modified
by another agent since the snapshot? If yes -> conflict. If no -> fast-forward commit.

Key properties:
- Readers never block writers
- Writers never block readers
- Write-write conflicts detected at commit time, not prevented upfront

This is NOT a library or plugin — it's a pattern we implement with file hashing
in Kotlin. No special database engine needed.

**Key resource**: CMU 15-445 Lecture #18 (best single resource on MVCC):
15445.courses.cs.cmu.edu/spring2023/notes/18-multiversioning.pdf

### RAG (Retrieval-Augmented Generation) — NOT used in Phase 1
An LLM technique where relevant documents are retrieved and injected into prompts.
Potentially useful in Phase 3+ for smarter context injection. Not related to DAG.

### SQLite: standard is sufficient
We use standard SQLite with `PRAGMA journal_mode=WAL` for persistence.
We do NOT need MVCC at the database level (FrankenSQLite, DuckDB, etc.) because
our orchestrator is a single process with a single writer. Our MVCC operates at
the filesystem level above SQLite, not inside it. WAL mode gives us concurrent
reads (for dashboard/CLI queries) with a single writer, which is our exact
access pattern.

---

## Relationship to existing projects

### AgentFS (Turso) — complementary, not competing
AgentFS provides per-agent filesystem isolation via SQLite-backed copy-on-write
overlays. It sandboxes ONE agent safely. Qorche coordinates MULTIPLE agents
concurrently — the DAG, conflict detection across agents, context bus, and
merge-back to the real filesystem. AgentFS is to Qorche what Docker containers
are to Kubernetes.

**Phase 1**: Build our own snapshot/hashing system (Option B — simpler, we own it,
pure Kotlin, zero native dependencies, teaches us the problem space).
**Phase 2+**: Evaluate AgentFS as an optional per-agent isolation layer underneath
our orchestration. AgentFS is MIT licensed — free to use, fork, or build on.

### Koog (JetBrains) — potential adapter target
Koog is an agent RUNTIME (manages LLM conversations, tool calls, strategies).
Qorche sits ABOVE runtimes. Koog could be one of our agent adapters in Phase 4.
Relevant features to study:
- ACP integration — for editor integration
- RollbackToolRegistry — for undoing agent file changes on conflict
- GOAP PR (#1520) — goal-oriented action planning
- Uses same stack: kotlinx.coroutines, kotlinx.serialization

### CASS (Dicklesworthstone) — potential Phase 3+ integration
Cross-Agent Search System — indexes session history across all agent providers
into a unified searchable store. Relevant for historical context awareness
(what did any agent learn about X?). Our context bus provides real-time awareness
(what is another agent doing right now?). Both are needed eventually.
CASS also has a memory system (cm) that extracts reusable patterns from sessions —
maps to our WAL evolution in later phases.

### NTM file reservations vs Qorche MVCC
NTM uses pessimistic file reservation (agents explicitly lock files before editing).
Qorche uses optimistic MVCC (agents work freely, conflicts detected at commit).
Our approach is faster but requires conflict resolution logic. Could offer both
as configurable strategies: pessimistic for critical files, optimistic for everything else.

### Composio Agent Orchestrator — closest existing competitor
Uses git worktrees for agent isolation, Node.js/TypeScript, tmux for process
management. No MVCC, no file index optimisation, no context bus. Coarse-grained
isolation compared to our snapshot-based approach.

### Marvin (Prefect) — supersedes ControlFlow
ControlFlow was archived and merged into Marvin 3.0. Both are Python LLM call
orchestrators, not filesystem concurrency tools. Different problem space.

---

## Tech stack decisions

| Choice | Rationale |
|---|---|
| Kotlin 2.1+ | Coroutines, sealed classes, data classes map perfectly to task/agent state |
| Gradle (Kotlin DSL) | Standard for Kotlin, good GraalVM plugin support |
| JDK 21 LTS | Virtual threads as fallback, long-term support |
| GraalVM native | Ship target only — develop on standard JVM, compile native for releases |
| kotlinx.coroutines | Agent lifecycle management, parallel task execution |
| kotlinx.serialization | JSON for task definitions, snapshots, WAL entries (GraalVM-friendly, no reflection) |
| SQLite (via sqlite-jdbc) | Local persistence with WAL mode. Standard SQLite — no MVCC engine needed |
| Clikt | CLI argument parsing — lightweight, Kotlin-idiomatic |
| kaml | YAML parsing for task definitions (kotlinx.serialization backend) |

### What we're NOT using (and why)

- **gRPC / Protobuf**: Adds Netty dependency (~15MB), complicates GraalVM. Phase 4.
- **Ktor / any web framework**: No HTTP server in Phase 1.
- **Spring / Compose Multiplatform**: Way too heavy. This is a CLI daemon.
- **JGraphT**: Overkill for a DAG with <100 nodes. Hand-roll the graph.
- **Koog**: Potential adapter dependency in Phase 4. Too heavy for Phase 1.
- **AgentFS**: Evaluate in Phase 2. Build own snapshot system first.
- **Gradle plugin**: Distribution concern. Post-beta.
- **FrankenSQLite / DuckDB**: MVCC at DB level not needed. Standard SQLite + WAL suffices.

### Memory strategy

- Develop on standard JVM with `-Xmx64m` to stay disciplined
- Compile to GraalVM native-image for releases (~15-30MB baseline, sub-100ms startup)
- GraalVM Serial GC for lowest memory footprint
- Ship with `-Xmx64m` cap for predictable usage
- GraalVM closed-world assumption (no reflection) aligns with our constraints

---

## Project structure (multi-module Gradle)

```
qorche/
├── settings.gradle.kts
├── build.gradle.kts              (shared config, GraalVM plugin)
├── gradle.properties
├── gradlew / gradlew.bat         (Gradle wrapper — already generated)
├── gradle/wrapper/
├── CLAUDE.md                     (project-wide conventions)
├── docs/
│   ├── PHASE1_PLAN.md            (this file)
│   ├── ARCHITECTURE.md
│   └── TASK_FORMAT.md
│
├── core/                         (snapshot, task graph, WAL, conflict detection)
│   ├── build.gradle.kts
│   ├── CLAUDE.md
│   └── src/
│       ├── main/kotlin/io/qorche/core/
│       │   ├── TaskGraph.kt
│       │   ├── Task.kt
│       │   ├── Snapshot.kt
│       │   ├── FileIndex.kt
│       │   ├── ConflictDetector.kt
│       │   └── WAL.kt
│       └── test/kotlin/io/qorche/core/
│           ├── TaskGraphTest.kt
│           ├── SnapshotTest.kt
│           └── ConflictDetectorTest.kt
│
├── agent/                        (runner interface, adapters)
│   ├── build.gradle.kts
│   ├── CLAUDE.md
│   └── src/
│       ├── main/kotlin/io/qorche/agent/
│       │   ├── AgentRunner.kt
│       │   ├── AgentResult.kt
│       │   ├── ClaudeCodeAdapter.kt
│       │   └── MockAgentRunner.kt
│       └── test/kotlin/io/qorche/agent/
│           └── ClaudeCodeAdapterTest.kt
│
└── cli/                          (entry point, terminal output)
    ├── build.gradle.kts
    ├── CLAUDE.md
    └── src/
        ├── main/kotlin/io/qorche/cli/
        │   ├── Main.kt
        │   ├── Commands.kt
        │   └── Output.kt
        └── test/kotlin/io/qorche/cli/
            └── CommandsTest.kt
```

### Why multi-module from day one
- core/ has ZERO dependencies on agent/ or cli/ — pure domain logic
- agent/ depends on core/ only — each adapter is isolated
- cli/ depends on both — it's the composition root
- Module-scoped CLAUDE.md files keep Claude Code focused per module
- Same pattern as RuneMate bot multi-module setup

---

## Cross-platform considerations (Windows, macOS, Linux)

### File paths
- ALWAYS use java.nio.file.Path, never string concatenation with `/` or `\`
- Store paths in snapshots with forward slashes as canonical form
- Use Kotlin: `Path("src") / "auth" / "login.ts"`

### Line endings
- Normalise to `\n` before hashing to prevent false conflicts across platforms

### Process spawning
- Claude Code CLI: `claude` on macOS/Linux, `claude.cmd` or `claude.exe` on Windows
- Detect via `System.getProperty("os.name")`

### SQLite
- sqlite-jdbc bundles native binaries for all three platforms — just works

---

## M0: Project scaffold + single agent runner — COMPLETE

### Goal
Run `qorche run "refactor the auth module"` and have it delegate to
Claude Code, stream output, and report completion.

### Tasks

1. **Gradle project setup** (wrapper already generated)
   - Kotlin 2.1, JDK 21 target
   - Multi-module: core, agent, cli
   - Dependencies: kotlinx-coroutines-core, kotlinx-serialization-json, clikt
   - Test: kotlinx-coroutines-test, kotlin-test
   - GraalVM native-image plugin configured (not required for dev builds)

2. **CLI entry point** — Main.kt + Commands.kt using Clikt
   - Commands: `run <instruction>`, `version`

3. **AgentRunner interface** (in core/ — note: interface only, no agent-specific code)
   ```kotlin
   interface AgentRunner {
       fun run(
           instruction: String,
           workingDirectory: Path,
           onOutput: (String) -> Unit
       ): Flow<AgentEvent>
   }

   sealed class AgentEvent {
       data class Output(val text: String) : AgentEvent()
       data class FileModified(val path: Path) : AgentEvent()
       data class Completed(val exitCode: Int) : AgentEvent()
       data class Error(val message: String) : AgentEvent()
   }
   ```

4. **MockAgentRunner** (in agent/) — build and test BEFORE ClaudeCodeAdapter

5. **ClaudeCodeAdapter** (in agent/) — cross-platform process spawning

6. **Terminal output** — real-time streaming with elapsed time

### Definition of done
- `./qorche run "list all files in src/"` works on Windows, macOS, Linux
- Agent process cleaned up on Ctrl+C
- All core logic tested against MockAgentRunner
- Memory: < 30MB RSS idle with `-Xmx64m`

---

## M1: File snapshot system — COMPLETE

### Goal
Snapshot the working directory before/after each agent run.
Detect which files changed. Produce a diff report.

### Project index optimisation
Don't re-hash every file on every snapshot. FileIndex caches path + size + mtime + hash.
If size + mtime match -> reuse cached hash (skip SHA-256). Same optimisation as `git status`.
Reduces snapshot time from O(total bytes) to O(changed bytes).

### Data model
```kotlin
@Serializable
data class FileIndexEntry(
    val relativePath: String,       // forward-slash canonical
    val size: Long,
    val lastModifiedEpochMs: Long,
    val hash: String                // SHA-256
)

@Serializable
data class Snapshot(
    val id: String,                 // UUID
    val timestamp: Instant,
    val fileHashes: Map<String, String>,
    val description: String,
    val parentId: String? = null
)

@Serializable
data class SnapshotDiff(
    val added: Set<String>,
    val modified: Set<String>,
    val deleted: Set<String>,
    val beforeId: String,
    val afterId: String
)
```

### Performance targets
- 10k files: < 2s first run, < 200ms cached
- 100k files: < 10s first run, < 1s cached
- Parallel hashing via Dispatchers.IO

### Definition of done
- "3 files modified, 1 file added" after each agent run
- `qorche history` shows past snapshots
- `qorche diff <id1> <id2>` shows changes
- Second snapshot on unchanged repo: < 200ms

---

## M2: Task graph + dependency model — COMPLETE

### Goal
Accept a list of tasks with dependencies, build a DAG,
execute in topological order (still sequential).

### Task definition format (YAML)
```yaml
project: auth-refactor
tasks:
  - id: explore
    instruction: "Map the auth module structure"
    type: explore
  - id: backend-api
    instruction: "Implement JWT refresh endpoint"
    depends_on: [explore]
    files: [src/auth/login.ts, src/auth/types.ts]
  - id: frontend-form
    instruction: "Build login form component"
    depends_on: [explore]
    files: [src/ui/LoginForm.tsx]
  - id: integration-tests
    instruction: "Write integration tests for auth"
    depends_on: [backend-api, frontend-form]
  - id: verify
    instruction: "Run full test suite"
    depends_on: [integration-tests]
    type: verify
```

### DAG implementation
Hand-rolled adjacency list. Cycle detection via DFS three-color marking.
`parallelGroups()` identifies tasks that COULD run concurrently (bridge to M3).

### Definition of done
- Execute 5-task graph against MockAgentRunner
- `qorche plan tasks.yaml` shows execution order + parallel opportunities
- WAL (JSON Lines at `.qorche/wal.jsonl`) contains complete history
- Cycle detection rejects invalid graphs with clear error

### What this unlocks
After M2, Qorche is a usable product you can dogfood. Define task graphs in YAML,
hand them to the orchestrator, get reliable sequential execution with full history.
The `plan` output showing parallelisable groups is the bridge to Phase 2.

---

## GraalVM compatibility checklist — COMPLETE

- [x] No runtime reflection (kotlinx.serialization only)
- [x] No dynamic class loading
- [x] No java.io.Serializable
- [x] No SQLite (removed — JSON file persistence only)
- [x] Native compilation working (20MB binary with UPX)
- [x] Minimal dependencies
- [x] kaml GraalVM compatibility verified

---

## Phase 2+ preview (informs Phase 1 design)

- M3: Parallel execution + MVCC conflict detection — **COMPLETE** (implemented during Phase 1)
- M4: Context bus + agent awareness
- M5: WAL + verification pipeline — **partially complete** (WAL done, verification TBD)
- M6: Observability dashboard
- M7: Multi-agent adapters (Claude Code, Codex, Gemini, Junie)
- M8: ACP + editor integration
- 1.0.0-beta: ~25 weeks from M0

---

## Useful resources

### MVCC
- CMU 15-445 Lecture #18: 15445.courses.cs.cmu.edu/spring2023/notes/18-multiversioning.pdf
- PostgreSQL MVCC docs: postgresql.org/docs/current/mvcc-intro.html
- "Database Internals" by Alex Petrov (book)

### System design
- github.com/donnemartin/system-design-primer
- Relevant: consistent hashing, data partitioning, message queues, CAP theorem

### Agent orchestration landscape
- Koog: github.com/JetBrains/koog
- AgentFS: github.com/tursodatabase/agentfs (MIT, per-agent isolation)
- CASS: github.com/Dicklesworthstone/coding_agent_session_search (cross-agent search)
- Embabel: GOAP planning, typed blackboard pattern (Rod Johnson / Spring)
- Marvin: github.com/PrefectHQ/marvin (supersedes ControlFlow)
- A2A protocol: a2a-protocol.org
- ACP: Agent Client Protocol (Zed + JetBrains co-sponsor)
- Composio Agent Orchestrator: github.com/ComposioHQ/agent-orchestrator

---

## Development approach

1. Gradle wrapper already generated — start with build config + empty modules
2. MockAgentRunner first — test full pipeline without LLM calls
3. ClaudeCodeAdapter after core pipeline works
4. Dogfood from M1 onwards
5. Keep DECISIONS.md for architectural choices
6. GitHub repo early (private -> public at beta)
7. Monthly GraalVM native-image compatibility test

---

## Open questions

- **Maven Central group**: io.qorche — verify availability
- **Agent output parsing**: Claude Code CLI format may change — consider Agent SDK
- **Task format**: YAML primary, JSON secondary, Kotlin DSL later
- **Repo size target**: 10k comfortable, 100k with caching
- **.qorche/ directory**: Local data store (snapshots, WAL, db) — add to .gitignore templates
