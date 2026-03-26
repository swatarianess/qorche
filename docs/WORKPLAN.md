# Qorche — Active Work Plan

Tasks for upcoming sessions. Pick from the top, mark as in-progress with your session ID, complete, and move to done.

---

## Next: CLI Improvements

### 1. Help text for all commands
**Status:** TODO
**Effort:** Small
**Files:** `cli/.../Commands.kt`

Add Clikt help descriptions to every command and option. `qorche --help` should show useful descriptions, not bare names.

### 2. Per-task prefixed stdout
**Status:** TODO
**Effort:** Small
**Files:** `Orchestrator.kt`, `Commands.kt`

Add `onTaskOutput: (taskId: String, line: String) -> Unit` callback to `runGraphParallel`. CLI shows `[task-id] output line` instead of `[agent] output line`.

### 3. Exit codes
**Status:** TODO
**Effort:** Small
**Files:** `Commands.kt`

- Exit 0: all tasks succeeded
- Exit 1: one or more tasks failed
- Exit 2: orchestrator error (bad YAML, cycle, file not found)

### 4. `qorche status` command
**Status:** TODO
**Effort:** Medium
**Files:** `Commands.kt`

Show `.qorche/` state: snapshot count, WAL entries, file index size, log files, last run timestamp.

### 5. `qorche logs` command
**Status:** TODO
**Effort:** Small
**Files:** `Commands.kt`

- `qorche logs` — list log files
- `qorche logs <taskId>` — show a specific task's log

### 6. `--no-color` flag
**Status:** TODO
**Effort:** Small
**Files:** `Commands.kt`

Add to parent `QorcheCommand`. No-op for now, establishes CLI contract for future color support.

---

## Backlog

### `.qorignore` file
**Status:** TODO
**Effort:** Medium
**Files:** `Snapshot.kt`

User-configurable ignore patterns. Consider reading `.gitignore` as baseline. Current hardcoded list misses `.kotlin/`, `node_modules/`, etc.

### GraalVM shared library spike
**Status:** TODO
**Effort:** Medium
**Files:** New `native/` module

Export one function via `--shared`, call from Python ctypes. Design in `memory/project_graalvm_shared_library.md`.

### More dogfood testing
**Status:** TODO
**Effort:** Medium

- Retry with real agents (`max_retries: 1`)
- Diamond DAG with real agents
- Scope violation with real agents

### Maven Local publish
**Status:** TODO
**Effort:** Small

Publish `io.qorche:core` for JVM consumers.

---

## Done

- Per-task log files (v0.7.2)
- Cold-start benchmark (v0.7.2)
- Native binary optimization 55MB → 20MB (v0.7.2)
- UPX compression Linux/Windows (v0.7.2)
- Remove unused sqlite-jdbc (v0.7.2)
- Dogfood with Claude Code — parallel + conflict detection (v0.7.0)
- Retry-on-conflict with rollback (v0.6.0)
- Scope audit with group-level attribution (v0.4.0)
- Parallel execution + MVCC (v0.4.0)
- CI/CD pipeline (v0.5.0)
- Semantic-release + git-cliff (v0.5.0)
