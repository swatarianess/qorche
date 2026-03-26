# Qorche — Project conventions

**Domain**: qorche.io
**Package**: io.qorche
**License**: Apache 2.0

## What this project is

Qorche is a deterministic, domain-agnostic orchestrator for concurrent filesystem
mutations. It uses MVCC-inspired filesystem concurrency control, task DAG scheduling,
and snapshot-based conflict detection to coordinate any concurrent workers that
modify files.

The core (io.qorche.core) has ZERO references to AI, LLMs, agents, CI/CD, or any
specific domain. It is a general-purpose concurrent filesystem coordination engine.
Workers are anything that modifies files — LLM agents, CI steps, build tools,
formatters, code generators — managed via the AgentRunner interface.

Domain-specific adapters live only in io.qorche.agent (e.g. ClaudeCodeAdapter).
The architecture supports any worker type without changes to core.

## Project planning

See docs/PHASE1_PLAN.md for the full roadmap, data models, milestone definitions
(M0 -> M1 -> M2), architecture decisions, and relationship to AgentFS, Koog, CASS,
and other projects. Follow the task sequence defined there.

See docs/WORKPLAN.md for active tasks and backlog. Check this first when starting a new session.

## Design principles

### Agents are untrusted reporters of their own side effects
Never rely on an agent's self-reported file modifications for correctness.
Agents may not report all writes (Claude Code `--print` mode emits no
FileModified events), may report writes that didn't happen, or may crash
after partial writes. The MVCC system must independently verify filesystem
state through before/after snapshots. Agent reports are hints for performance
optimisation only — snapshots are ground truth.

This principle drives several design decisions:
- FileIndex is fully cleared before after-snapshots (not selectively invalidated)
- Scope audit operates at the group level (can't attribute to specific agents)
- After-snapshots are always taken, even when the agent throws an exception
- Conflict detection compares snapshot hashes, not agent-reported file lists

### Snapshot-first, not event-first
Qorche's correctness comes from filesystem snapshots (SHA-256 hashes), not
from tracking individual file operations. This makes it work with any worker
regardless of whether it reports what it does, and catches unexpected side
effects that event-based approaches miss.

## Architecture constraints

### GraalVM native-image compatibility (CRITICAL)
- NO runtime reflection — ever. No Class.forName(), no field.setAccessible().
- NO dynamic class loading or runtime proxy generation.
- NO Gson or Jackson — use kotlinx.serialization exclusively.
- NO java.io.Serializable for data transfer.
- Use @Serializable annotation on all persistent data classes.
- If adding a new dependency, verify GraalVM compatibility FIRST.

### Cross-platform (Windows, macOS, Linux)
- ALWAYS use java.nio.file.Path for file operations.
- Store all paths with forward slashes as the canonical form.
- Normalise on read: path.replace("\\", "/")
- Process spawning: use binary name without extension (e.g., `claude`).
  The OS resolves `.exe` on Windows automatically when on PATH.
- Normalise line endings to `\n` before hashing files.

### Module boundaries (STRICT)
Multi-module Gradle project. Respect the dependency graph:
- core/ depends on: nothing (stdlib + kotlinx only)
- agent/ depends on: core/ only
- cli/ depends on: core/ and agent/

NEVER import from agent/ or cli/ in core/. If you need to, define an interface
in core/ and implement it in the appropriate module.

### Memory discipline
- Target: < 30MB RSS idle on standard JVM with -Xmx64m
- Stream file contents through MessageDigest — don't read entire files into memory
- Use Sequence/Flow instead of intermediate List copies for large collections
- Minimise object allocations in hot paths (file hashing, snapshot comparison)

### No frameworks
- No Ktor, Spring, Compose Multiplatform, or any application framework.
- Plain Kotlin with kotlinx.coroutines, kotlinx.serialization, Clikt (CLI).
- This is a CLI tool, not a web app or GUI application.
- All persistence uses JSON files — no database dependency.

## Coding style

### Kotlin conventions
- Data classes for all value types
- Sealed classes/interfaces for algebraic types (AgentEvent, TaskStatus, WALEntry)
- Kotlin coroutines for all async work — no raw threads, no CompletableFuture
- Extension functions over utility classes
- Prefer val over var, immutable collections over mutable
- No wildcard imports

### Naming
- Package: io.qorche.{module}
- Files named after their primary class/interface
- Test files: {ClassName}Test.kt

### Serialization
- kotlinx.serialization with @Serializable on all persistent data classes
- JSON as primary format (kotlinx-serialization-json)
- YAML for task definitions (kaml library)
- WAL uses JSON Lines format (.jsonl) — one JSON object per line, append-only
- Timestamps: kotlinx.datetime.Instant

### Error handling
- Use Result<T> or sealed class results for expected failures
- Throw exceptions only for programmer errors (bugs)
- Agent failures are expected — model them in the type system
- Always clean up child processes on error (shutdown hooks, try/finally)

## Testing
- Core logic tested against MockAgentRunner — no LLM calls needed
- Use kotlinx-coroutines-test for async testing
- Every public function in core/ should have tests
- Integration tests with real agents are separate and opt-in
- Test cross-platform path handling explicitly

## Local data directory
- `.qorche/` is the local data store (similar to `.git/`)
- `.qorche/snapshots/` — snapshot files (JSON)
- `.qorche/wal.jsonl` — write-ahead log (JSON Lines)
- `.qorche/file-index.json` — mtime/hash cache for fast re-snapshots
- `.qorche/logs/` — per-task agent output logs
- `.qorche/` should be in .gitignore for user projects
