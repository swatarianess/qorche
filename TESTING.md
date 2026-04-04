# Testing

## Overview

Qorche uses Kotlin Test (JUnit 5 platform) with `kotlinx-coroutines-test` for async
testing. The suite contains **185 tests** across 20 test files in three modules, covering
DAG scheduling, MVCC snapshots, conflict detection, CLI output, and full orchestrator
integration. No external mocking frameworks are used -- all test doubles are hand-written.

## Running tests

### Standard test suite

```bash
./gradlew test
```

Excludes benchmarks and large-scale tests. JVM is constrained to `-Xmx64m` to catch
memory regressions early.

### With linting (recommended)

```bash
./gradlew test detekt
```

Detekt runs static analysis and uploads SARIF reports. Always run both before committing.

### Benchmarks

```bash
./gradlew :agent:benchmark          # Standard benchmarks (100-20k files, -Xmx128m)
./gradlew :agent:largeBenchmark     # Large-scale (50k-100k files, -Xmx512m)
```

Benchmarks are tagged and excluded from `./gradlew test`. Run them manually to measure
MVCC overhead, snapshot scaling, and conflict detection throughput.

## Test architecture

### Module breakdown

| Module   | Test files | Tests | Focus |
|----------|-----------|-------|-------|
| `core/`  | 9         | 92    | DAG scheduling, MVCC snapshots, WAL serialization, conflict detection, file index caching, ignore patterns, YAML parsing, serialization round-trips, snapshot store persistence |
| `agent/` | 8         | 72    | Orchestrator integration, parallel execution, conflict retry/rollback, scope audit, shell runner process spawning, runner registry, benchmarks, cleanup |
| `cli/`   | 3         | 21    | End-to-end CLI pipeline, init command project detection, validate command ANSI output |

### Key test patterns

- **MockAgentRunner**: Custom mock (`agent/src/main/kotlin/io/qorche/agent/MockAgentRunner.kt`) that simulates file mutations with configurable write behavior. No external mocking framework needed.
- **Fixture YAML files**: Realistic task graphs in `cli/src/test/resources/fixtures/` (`parallel-no-conflict.yaml`, `diamond-dag.yaml`, `scope-overlap.yaml`, `cycle-error.yaml`).
- **Temp directory isolation**: Every test creates a fresh temp directory, cleaned up in `finally` blocks. No test depends on another test's filesystem state.
- **Cross-platform**: CI runs on Ubuntu and Windows. Path normalization (forward slashes, line ending normalization) is tested explicitly in `SnapshotTest`.
- **Coroutine testing**: Uses `kotlinx-coroutines-test` and `runBlocking` for async tests. No raw threads.
- **Memory-constrained execution**: Standard tests run with `-Xmx64m`, benchmarks with `-Xmx128m` or `-Xmx512m`, matching the project's memory discipline targets.

## Test categories

### Unit tests (core/)

The core module tests cover the foundational data structures and algorithms with no
dependency on the orchestrator or agent infrastructure:

- **TaskGraphTest** (6 tests): Topological sort, cycle detection, dependency resolution, execution group ordering.
- **SnapshotTest** (27 tests): File hashing (SHA-256, SHA-1, CRC32C), line ending normalization, directory traversal, scoped snapshots, hash algorithm configuration, diff detection for added/modified/deleted files.
- **ConflictDetectorTest** (13 tests): Group conflict detection for overlapping file modifications, conflict resolution (earlier task wins), scope violation detection for undeclared writes, write-write conflict detection between snapshots.
- **FileIndexTest** (4 tests): Mtime-based cache hits/misses, persistence save/load round-trips.
- **WALTest** (3 tests): Write-ahead log append and read-back in JSON Lines format.
- **TaskYamlParserTest** (13 tests): YAML parsing validation, runner references, scope declarations, error cases.
- **SerializationRoundTripTest** (9 tests): JSON round-trip for all persistent data classes (RunResult, TaskOutcome, GraphResult, TaskConflict, ConflictResolution, ConflictReport, ConflictRetryPolicy, ScopeViolation).
- **SnapshotStoreTest** (8 tests): Snapshot save/load, listing with sort order, corrupted file handling, latest snapshot retrieval.
- **IgnorePatternsTest** (9 tests): Default ignore patterns (`.git/`, `build/`, OS artifacts), `.qorignore` file parsing, reset behavior, snapshot integration.

### Integration tests (agent/)

The agent module tests verify the full orchestrator pipeline -- parse, snapshot, execute,
snapshot, detect:

- **OrchestratorTest** (9 tests): Single-task execution, WAL recording, file index persistence, scoped snapshots, snapshot diffing, elapsed time tracking, per-task runner dispatch.
- **OrchestratorGraphTest** (5 tests): Multi-task DAG execution, topological ordering, WAL history, scoped file paths.
- **OrchestratorEdgeCaseTest** (8 tests): Agent exceptions, non-zero exit codes, cascading failures, single-task groups skipping conflict detection, event callbacks.
- **OrchestratorCleanTest** (7 tests): Cleanup of snapshots/logs/WAL/cache, selective clean, `keepLast` retention, clean-then-run workflow.
- **ParallelExecutionTest** (18 tests): Concurrent task execution, conflict detection and retry, rollback before retry, scope audit, WAL retry entries, stubborn retry exhaustion, mixed conflict scenarios.
- **ShellRunnerTest** (8 tests): Real process spawning with conflict detection, parallel shell execution.
- **RunnerRegistryTest** (6 tests): Registry instantiation from config, runner lookup, validation.

### End-to-end tests (cli/)

- **CliEndToEndTest** (9 tests): Full CLI pipeline from YAML fixtures to JSON output. Tests `plan` (DAG grouping, scope overlap, cycle errors) and `run` (parallel execution, diamond DAG ordering, conflict output, status reporting).
- **InitCommandTest** (8 tests): Project type detection (Gradle/Kotlin, Node, Python, Rust, Go, generic), YAML parseability of generated files, `.qorignore` header format.
- **ValidateCommandTest** (4 tests): Elapsed time formatting, `--no-color` flag, ANSI terminal output.

### Benchmarks

Benchmark tests live in `agent/src/test/kotlin/io/qorche/agent/BenchmarkTest.kt` (11 tests)
and are excluded from normal `./gradlew test` runs via JUnit 5 tags:

- **`@Tag("benchmark")`** (9 tests): MVCC overhead across file counts (100-20k), sequential vs parallel execution, scaling with step count, conflict detection at scale, realistic file sizes, DAG propagation overhead, cold/warm start comparison, diamond DAG parallel throughput.
- **`@Tag("large-scale")`** (2 tests): 50k and 100k file scenarios for MVCC overhead and end-to-end execution.

## CI integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and PR to `main`/`develop`:

- **Platform matrix**: Ubuntu and Windows.
- **Detekt first**: Static analysis runs before tests. SARIF report uploaded to GitHub Code Scanning (Ubuntu only).
- **Test reports**: Uploaded as artifacts on failure for debugging.
- **Benchmarks not in CI**: Tagged tests are excluded. Run benchmarks locally to avoid CI timeout and flakiness from variable runner performance.
- **JDK 21**: Temurin distribution via `actions/setup-java`.

## Adding new tests

### Conventions

1. **Location**: Place test files in `{module}/src/test/kotlin/io/qorche/{module}/` following the `{ClassName}Test.kt` naming pattern.
2. **Use MockAgentRunner**: For any test that needs agent execution, use `MockAgentRunner` with a configured write lambda. Never call real LLM APIs in unit/integration tests.
3. **Temp directories**: Create a fresh temp directory per test. Clean up in a `finally` block or use `@AfterEach`. Never rely on shared filesystem state.
4. **Coroutines**: Use `runBlocking` for coroutine tests. Import from `kotlinx.coroutines.test` when you need `runTest` or test dispatchers.
5. **No mocking frameworks**: Write hand-rolled test doubles. This keeps the dependency graph clean and GraalVM-compatible.
6. **Tags**: If writing a benchmark, annotate with `@Tag("benchmark")` or `@Tag("large-scale")`. These are automatically excluded from `./gradlew test`.
7. **Run both**: Always verify with `./gradlew test detekt` before committing.
