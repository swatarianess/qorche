# Qorche CLI Roadmap — Ship-Ready Developer Experience

> **STATUS:** P0, P1a, P1b, P2a, and P4 are **COMPLETE**.
> P2b (Homebrew), P2c (npm), P3a (init), P3b (templates) are **deferred** —
> qorche is positioned as an infrastructure layer, not an end-user CLI product.
> The CLI serves as a reference implementation. See WORKPLAN.md for current priorities.

## Dependency Graph

```
                    ┌─────────────────────┐
                    │  P0: JSON Output    │
                    │  (--output json)    │
                    └────────┬────────────┘
                             │
              ┌──────────────┼──────────────────┐
              ▼              ▼                   │
   ┌──────────────────┐  ┌──────────────────┐   │
   │ P1a: CLI Polish  │  │ P1b: GraalVM     │   │
   │ (colors, progress│  │ Native Image     │   │
   │  plan visual)    │  │ Build            │   │
   └──────────────────┘  └───────┬──────────┘   │
                                 │              │
                    ┌────────────┼──────────┐   │
                    ▼            ▼          ▼   │
             ┌───────────┐ ┌────────┐ ┌──────┐ │
             │ P2a:      │ │ P2b:   │ │ P2c: │ │
             │ GitHub    │ │ Homebrew│ │ npm  │ │
             │ Releases  │ │ Formula│ │ Wrap │ │
             └───────────┘ └────────┘ └──────┘ │
                                               │
              ┌────────────────────────────────┘
              ▼
   ┌──────────────────┐     ┌──────────────────┐
   │ P3a: qorche init │────▶│ P3b: Templates   │
   │ (scaffolding)    │     │ (kdoc, refactor,  │
   └──────────────────┘     │  ci-parallel)     │
                            └──────────────────┘

   ┌──────────────────┐
   │ P4: qorche logs  │  (independent, can start anytime after P0)
   │ + qorche status  │
   └──────────────────┘
```

---

## P0: Structured JSON Output

**Why first:** Everything downstream (MCP integration, agent consumption, `qorche status`, programmatic tooling) needs machine-readable output. This is the foundation.

**What to build:**

- `--output json` flag on `qorche run` and `qorche plan`
- Serialize `GraphResult` to JSON: task statuses, conflicts, retries, scope violations, timing, file changes
- `qorche plan --output json` returns the parsed DAG with parallel groups, estimated structure, file scope overlap warnings
- stdout gets JSON, stderr gets human-readable progress (so both are available simultaneously)
- Exit codes: 0 = all tasks succeeded, 1 = any task failed, 2 = invalid input/config

**Schema (run output):**

```json
{
  "version": "0.3.0",
  "project": "my-project",
  "success": true,
  "wallTimeMs": 62340,
  "tasks": [
    {
      "id": "kdoc-snapshot",
      "status": "COMPLETED",
      "durationMs": 27100,
      "retryCount": 0,
      "filesChanged": ["core/src/.../Snapshot.kt"]
    }
  ],
  "conflicts": [],
  "scopeViolations": [],
  "retriedTasks": 0,
  "groups": [
    {
      "index": 0,
      "taskIds": ["kdoc-snapshot", "kdoc-taskgraph", "kdoc-fileindex"],
      "parallel": true,
      "durationMs": 27100
    }
  ]
}
```

**Schema (plan output):**

```json
{
  "version": "0.3.0",
  "tasks": 3,
  "groups": [
    {
      "index": 0,
      "taskIds": ["kdoc-snapshot", "kdoc-taskgraph", "kdoc-fileindex"],
      "parallel": true
    }
  ],
  "warnings": [
    {
      "type": "scope_overlap",
      "taskA": "agent-a",
      "taskB": "agent-b",
      "overlappingFiles": ["src/auth.kt"],
      "message": "These tasks may conflict — consider splitting file scopes"
    }
  ],
  "estimatedSequentialMs": null,
  "estimatedParallelMs": null
}
```

**Estimated effort:** 1–2 days. GraphResult already has all the data — this is pure serialization via kotlinx.serialization.

**Files to change:**
- `cli/src/main/kotlin/io/qorche/cli/Commands.kt` — add `--output` flag, JSON formatting
- `core/src/main/kotlin/io/qorche/core/Orchestrator.kt` — add per-group timing to GraphResult
- New: `cli/src/main/kotlin/io/qorche/cli/JsonOutput.kt` — serialization helpers

**Definition of done:**
- `qorche run plan.yaml --output json` pipes valid JSON to stdout
- `qorche plan plan.yaml --output json` returns DAG structure with scope overlap warnings
- JSON schema is documented in README
- At least 2 tests: run output matches schema, plan output flags overlapping scopes

---

## P1a: CLI Polish (Colors, Progress, Plan Visual)

**Why now:** Improves human experience immediately. Independent of distribution.

**What to build:**

### Colored terminal output
- Green: task completed
- Yellow: task retrying
- Red: task failed
- Dim/gray: task skipped
- Cyan: task running
- Use ANSI escape codes directly — no library dependency. Detect `NO_COLOR` env var and `--no-color` flag for piped/CI output.

### Live progress line
```
[3/5 running]  kdoc-snapshot ● kdoc-taskgraph ● kdoc-fileindex ○ integrate ○
               ^^green(done)   ^^cyan(running)   ^^dim(pending)
```
- Single line, updated via `\r` carriage return
- Shows current parallel group and task states
- Falls back to line-per-event when output isn't a TTY

### `qorche plan` visual output
```
$ qorche plan plan.yaml

  DAG: 6 tasks, 3 groups

  Group 0 (sequential):
    → explore

  Group 1 (parallel, 3 tasks):
    ├── refactor-auth     [src/auth.kt, src/auth-utils.kt]
    ├── refactor-login    [src/login.kt]
    └── refactor-signup   [src/signup.kt]
    ⚠ Scope overlap: refactor-auth ↔ refactor-session on src/auth.kt

  Group 2 (sequential):
    → integrate

  Estimated: 3 sequential phases, max 3 parallel agents
```

### Actionable error messages
Replace informational messages with prescriptive ones:
```
# Before
[CONFLICT] agent-a <-> agent-b: src/auth.kt

# After
[CONFLICT] agent-a ↔ agent-b on src/auth.kt
  → agent-a won (YAML position 1), agent-b retrying (attempt 1/1)
  Tip: To prevent, give these tasks non-overlapping 'files' scopes
```

**Estimated effort:** 2–3 days.

**Files to change:**
- New: `cli/src/main/kotlin/io/qorche/cli/Terminal.kt` — color helpers, TTY detection, progress rendering
- `cli/src/main/kotlin/io/qorche/cli/Commands.kt` — wire up callbacks to Terminal, enhance PlanCommand

**Definition of done:**
- Colored output on supported terminals, plain text when piped or NO_COLOR set
- Progress line updates during parallel execution
- `qorche plan` shows visual DAG with scope overlap warnings
- Error messages include actionable next steps

---

## P1b: GraalVM Native Image Build

**Why now:** Blocks all distribution channels. No one will install JDK 21 to try your tool.

**What to build:**

- Gradle task for native-image compilation via GraalVM
- You already use zero reflection — this should be straightforward
- Target three platforms: linux-amd64, macos-amd64 (Intel), macos-arm64 (Apple Silicon), windows-amd64
- GitHub Actions CI matrix to build all four binaries on push to main/tag
- Verify all tests pass under native-image (some kotlinx.serialization edge cases may surface)
- Binary size target: <30MB (typical for Kotlin native-image with kotlinx libs)

**Key considerations:**
- kotlinx.serialization uses some reflection for polymorphic types — you may need `reflect-config.json` for WALEntry sealed class hierarchy
- File I/O and coroutines work well under native-image as of GraalVM 21
- Test with `-Xmx64m` equivalent (`-R:MaxHeapSize=64m`) to verify memory behavior matches JVM benchmarks

**Estimated effort:** 2–3 days (mostly CI configuration and native-image config troubleshooting).

**Files to change:**
- `build.gradle.kts` (root) — GraalVM native-image plugin
- `cli/build.gradle.kts` — native-image task configuration, reflect-config
- New: `.github/workflows/release.yml` — multi-platform build matrix
- New: `cli/src/main/resources/META-INF/native-image/` — native-image configuration files

**Definition of done:**
- `./gradlew nativeCompile` produces a working binary
- Binary runs all tests successfully
- CI builds four platform binaries on tagged releases
- Binary starts in <50ms (vs ~1-2s JVM cold start)

---

## P2a: GitHub Releases

**Depends on:** P1b (native image binaries)

**What to build:**

- GitHub Actions workflow: on version tag (`v*`), build native binaries for all platforms, create GitHub Release with attached binaries
- Release notes auto-generated from conventional commits or a CHANGELOG.md
- Binary naming: `qorche-{version}-{os}-{arch}` (e.g., `qorche-0.3.0-linux-amd64`)
- Include SHA256 checksums file for verification
- Install script: `curl -fsSL https://qorche.dev/install.sh | sh` (downloads correct binary for platform)

**Estimated effort:** 1 day (mostly CI workflow).

**Files to change:**
- `.github/workflows/release.yml` — extend with release creation, asset upload
- New: `scripts/install.sh` — platform-detect and download correct binary
- New: `CHANGELOG.md`

**Definition of done:**
- Pushing a version tag triggers automated release with all binaries
- Install script works on Linux and macOS
- Checksums published alongside binaries

---

## P2b: Homebrew Formula

**Depends on:** P2a (GitHub Releases with binaries)

**What to build:**

- Homebrew formula that downloads the correct native binary from GitHub Releases
- Host in a tap initially: `brew install qorche/tap/qorche`
- Formula includes SHA256 verification, binary-only (no build from source)
- Submit to homebrew-core once adoption justifies it

**Estimated effort:** Half a day.

**Files to change:**
- New repo: `qorche/homebrew-tap` with `Formula/qorche.rb`

**Definition of done:**
- `brew install qorche/tap/qorche` installs working binary on macOS
- Formula auto-updates via CI when new release is published

---

## P2c: npm Wrapper

**Depends on:** P2a (GitHub Releases with binaries)

**What to build:**

- Thin npm package that downloads the correct native binary on `postinstall`
- `npx qorche` works without global install
- Package detects OS/arch and fetches from GitHub Releases
- No Node.js runtime dependency — just the download wrapper

**Estimated effort:** Half a day.

**Files to change:**
- New: `npm/package.json`, `npm/index.js`, `npm/install.js`

**Definition of done:**
- `npx qorche run plan.yaml` works
- `npm install -g qorche` installs binary globally
- Works on Linux, macOS, Windows

---

## P3a: `qorche init` Scaffolding

**Depends on:** P0 (JSON output schema established — init generates files that produce valid output)

**What to build:**

- `qorche init` interactive flow:
    1. Detect repo root (find `.git` parent)
    2. Ask: runner type (Claude Code / shell / custom)
    3. Ask: describe tasks (free text, 1-5 tasks)
    4. Ask: any dependencies between them? (default: all parallel)
    5. Generate `qorche.yaml` with sensible defaults
- `qorche init --template <name>` skips interactive, copies template
- Creates `.qorche/` directory if not present
- Adds `.qorche/` to `.gitignore` if not already there

**Estimated effort:** 1–2 days.

**Files to change:**
- New: `cli/src/main/kotlin/io/qorche/cli/InitCommand.kt`
- `cli/src/main/kotlin/io/qorche/cli/Commands.kt` — register init subcommand

**Definition of done:**
- `qorche init` produces a valid `qorche.yaml` through interactive prompts
- `qorche init --template kdoc` copies a working template
- Generated YAML passes `qorche plan` validation

---

## P3b: Starter Templates

**Depends on:** P3a (init command to consume them)

**What to build:**

Three templates bundled with the binary:

### `kdoc` — Parallel documentation
```yaml
project: my-project-kdoc
tasks:
  - id: kdoc-models
    instruction: "Add KDoc to all public classes and functions in {{file}}"
    files: ["{{file}}"]
# Duplicated per file — init asks which files to document
```

### `refactor` — Diamond DAG
```yaml
project: my-project-refactor
tasks:
  - id: explore
    instruction: "Analyze {{module}} and produce a refactoring plan in PLAN.md"
    files: ["PLAN.md"]
  - id: refactor-a
    instruction: "Refactor {{file_a}} following PLAN.md"
    depends_on: [explore]
    files: ["{{file_a}}"]
  - id: refactor-b
    instruction: "Refactor {{file_b}} following PLAN.md"
    depends_on: [explore]
    files: ["{{file_b}}"]
  - id: integrate
    instruction: "Review all changes, fix imports and cross-references"
    depends_on: [refactor-a, refactor-b]
```

### `ci-parallel` — Shell tasks
```yaml
project: my-project-ci
tasks:
  - id: lint
    instruction: "Run linter"
    type: shell
    command: "npm run lint"
    files: ["src/**"]
  - id: test
    instruction: "Run tests"
    type: shell
    command: "npm test"
    files: ["src/**", "test/**"]
  - id: typecheck
    instruction: "Type check"
    type: shell
    command: "npx tsc --noEmit"
```

Templates use `{{placeholder}}` syntax that `qorche init` fills in interactively.

**Estimated effort:** 1 day.

**Files to change:**
- New: `cli/src/main/resources/templates/kdoc.yaml`
- New: `cli/src/main/resources/templates/refactor.yaml`
- New: `cli/src/main/resources/templates/ci-parallel.yaml`
- `cli/src/main/kotlin/io/qorche/cli/InitCommand.kt` — template loading and placeholder substitution

**Definition of done:**
- All three templates generate valid YAML that passes `qorche plan`
- `qorche init --template` lists available templates
- Placeholders are filled via interactive prompts

---

## P4: `qorche logs` + `qorche status`

**Depends on:** P0 (JSON output for status), P1a (terminal output helpers)
**Can start anytime after P0 — independent track.**

**What to build:**

### Per-task log files
- Each task writes output to `.qorche/logs/{taskId}.log`
- `qorche logs <taskId>` tails the log (live during execution, full after)
- `qorche logs` lists available logs with task status and size
- Settle the logging architecture decision: per-task files for output, WAL for lifecycle events

### `qorche status`
- Shows current/last run state:
```
$ qorche status

  Last run: 2026-03-25T14:32:00Z (62.3s)
  Status: COMPLETED (3/3 tasks)

  Tasks:
    ✓ kdoc-snapshot     27.1s   1 file changed
    ✓ kdoc-taskgraph    24.8s   1 file changed
    ✓ kdoc-fileindex    25.2s   1 file changed

  Conflicts: none
  Retries: none
```
- Reads from WAL + GraphResult JSON (written by P0)

**Estimated effort:** 1–2 days.

**Files to change:**
- New: `cli/src/main/kotlin/io/qorche/cli/LogsCommand.kt`
- New: `cli/src/main/kotlin/io/qorche/cli/StatusCommand.kt`
- `core/src/main/kotlin/io/qorche/core/Orchestrator.kt` — write per-task logs via callback

**Definition of done:**
- `qorche logs agent-a` shows that task's output
- `qorche status` shows last run summary
- Works with both completed and in-progress runs

---

## Execution Timeline

```
Week 1:  P0 (JSON output)  ─────────  P1a (CLI polish) starts
Week 1:                               P1b (native image) starts
Week 2:  P1a done ──────────────────  P1b done
Week 2:  P2a (GitHub Releases) ─────  P2b + P2c (Homebrew + npm)
Week 3:  P3a (qorche init) ─────────  P3b (templates)
Week 3:  P4 (logs + status) ────────

Total: ~3 weeks to ship-ready CLI
```

P1a and P1b can run in parallel (they touch different code). P2b and P2c can run in parallel once P2a lands. P4 is an independent track.

---

## What This Unlocks

After this roadmap:
- `brew install qorche/tap/qorche` → `qorche init --template kdoc` → `qorche run` — zero to running in 60 seconds
- `qorche run --output json` → MCP tool integration with Claude Code (next roadmap)
- Actionable CLI output that teaches users the system as they use it
- Per-task logs and status for debugging without reading raw WAL
- Native binary with <50ms startup — fast enough for CI pipelines