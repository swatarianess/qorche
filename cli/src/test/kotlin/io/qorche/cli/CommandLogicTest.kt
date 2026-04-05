package io.qorche.cli

import io.qorche.core.AgentResult
import io.qorche.core.AgentRunner
import io.qorche.core.ExitCode
import io.qorche.core.HashAlgorithm
import io.qorche.core.Orchestrator
import io.qorche.core.RunnerConfig
import io.qorche.core.Snapshot
import io.qorche.core.SnapshotDiff
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.VerifyConfig
import io.qorche.core.VerifyResult
import io.qorche.core.WALEntry
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for extracted CLI business logic.
 *
 * These test the pure data transformations in CommandLogic.kt without
 * Clikt, terminal output, or any process spawning.
 */
@Tag("smoke")
class CommandLogicTest {

    // --- loadTaskGraph ---

    @Test
    fun `loadTaskGraph returns Success for valid YAML`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("""
                project: test
                tasks:
                  - id: task1
                    instruction: do thing
            """.trimIndent())

            val result = loadTaskGraph(yaml)
            assertIs<TaskGraphLoadResult.Success>(result)
            assertEquals("test", result.project.project)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadTaskGraph returns ParseError for invalid YAML`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("not: valid: yaml: [[[")

            val result = loadTaskGraph(yaml)
            assertIs<TaskGraphLoadResult.ParseError>(result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadTaskGraph returns ParseError for cyclic dependencies`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("""
                project: cycle-test
                tasks:
                  - id: a
                    instruction: do a
                    depends_on: [b]
                  - id: b
                    instruction: do b
                    depends_on: [a]
            """.trimIndent())

            val result = loadTaskGraph(yaml)
            assertIs<TaskGraphLoadResult.ParseError>(result)
            assertTrue(result.message.contains("ycle"), "Should mention cycle: ${result.message}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- loadVerifyConfig ---

    @Test
    fun `loadVerifyConfig returns Success when verify section present`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("""
                project: test
                verify:
                  command: echo ok
                tasks:
                  - id: task1
                    instruction: do thing
            """.trimIndent())

            val result = loadVerifyConfig(yaml)
            assertIs<VerifyLoadResult.Success>(result)
            assertEquals("echo ok", result.config.command)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadVerifyConfig returns NoVerifySection when absent`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("""
                project: test
                tasks:
                  - id: task1
                    instruction: do thing
            """.trimIndent())

            val result = loadVerifyConfig(yaml)
            assertIs<VerifyLoadResult.NoVerifySection>(result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadVerifyConfig returns ParseError for bad YAML`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val yaml = root.resolve("tasks.yaml")
            yaml.writeText("garbage: [[[")

            val result = loadVerifyConfig(yaml)
            assertIs<VerifyLoadResult.ParseError>(result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- executeVerification ---

    @Test
    fun `executeVerification returns Passed for successful command`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) "cmd /c echo ok" else "echo ok"
            val config = VerifyConfig(command = cmd, timeoutSeconds = 30)

            val outcome = executeVerification(config, root)
            assertIs<VerifyOutcome.Passed>(outcome)
            assertTrue(outcome.elapsedMs >= 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `executeVerification returns Failed for failing command`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) "cmd /c exit 1" else "false"
            val config = VerifyConfig(command = cmd, timeoutSeconds = 30)

            val outcome = executeVerification(config, root)
            assertIs<VerifyOutcome.Failed>(outcome)
            assertEquals(1, outcome.exitCode)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `executeVerification captures output via callback`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) "cmd /c echo hello" else "echo hello"
            val config = VerifyConfig(command = cmd, timeoutSeconds = 30)
            val lines = mutableListOf<String>()

            executeVerification(config, root) { lines.add(it) }
            assertTrue(lines.any { it.contains("hello") }, "Should capture output: $lines")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- summarizeReplay ---

    @Test
    fun `summarizeReplay counts entry types correctly`() {
        val entries = listOf(
            WALEntry.TaskStarted(taskId = "t1", instruction = "do thing", snapshotId = "abc123"),
            WALEntry.TaskCompleted(taskId = "t1", snapshotId = "def456", exitCode = 0, filesModified = listOf("a.kt")),
            WALEntry.TaskStarted(taskId = "t2", instruction = "other", snapshotId = "ghi789"),
            WALEntry.TaskFailed(taskId = "t2", error = "boom")
        )

        val result = summarizeReplay(entries, verbose = false)
        assertEquals(4, result.totalEntries)
        assertEquals(2, result.taskCount)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.failedCount)
        assertEquals(0, result.retryCount)
        assertEquals(0, result.conflictCount)
        assertEquals(0, result.verifyCount)
    }

    @Test
    fun `summarizeReplay non-verbose has no details`() {
        val entries = listOf(
            WALEntry.TaskStarted(taskId = "t1", instruction = "do thing", snapshotId = "abc123def456")
        )

        val result = summarizeReplay(entries, verbose = false)
        assertEquals(1, result.formattedEntries.size)
        assertTrue(result.formattedEntries[0].details.isEmpty())
    }

    @Test
    fun `summarizeReplay verbose includes details`() {
        val entries = listOf(
            WALEntry.TaskStarted(taskId = "t1", instruction = "do thing", snapshotId = "abc123def456")
        )

        val result = summarizeReplay(entries, verbose = true)
        val details = result.formattedEntries[0].details
        assertTrue(details.any { it.contains("Instruction:") }, "Should have instruction: $details")
        assertTrue(details.any { it.contains("abc123de") }, "Should have snapshot prefix: $details")
    }

    @Test
    fun `summarizeReplay formats all entry types`() {
        val entries = listOf(
            WALEntry.TaskStarted(taskId = "t1", instruction = "a", snapshotId = "s1"),
            WALEntry.TaskCompleted(taskId = "t1", snapshotId = "s2", exitCode = 0, filesModified = emptyList()),
            WALEntry.TaskFailed(taskId = "t2", error = "err"),
            WALEntry.ConflictDetected(taskId = "t3", conflictingTaskId = "t4", conflictingFiles = listOf("f.kt"), baseSnapshotId = "bs"),
            WALEntry.TaskRetryScheduled(taskId = "t3", attempt = 1, conflictWith = "t4", conflictingFiles = listOf("f.kt")),
            WALEntry.TaskRetried(taskId = "t3", attempt = 1, snapshotId = "s3"),
            WALEntry.ScopeViolation(taskId = "scope-check", undeclaredFiles = listOf("x.kt"), suspectTaskIds = listOf("t5")),
            WALEntry.VerifyCompleted(taskId = "verify-0", success = true, exitCode = 0, command = "echo ok", groupIndex = 0)
        )

        val result = summarizeReplay(entries, verbose = false)
        assertEquals(8, result.formattedEntries.size)
        assertEquals(WalEntryType.STARTED, result.formattedEntries[0].type)
        assertEquals(WalEntryType.COMPLETED, result.formattedEntries[1].type)
        assertEquals(WalEntryType.FAILED, result.formattedEntries[2].type)
        assertEquals(WalEntryType.CONFLICT, result.formattedEntries[3].type)
        assertEquals(WalEntryType.RETRY_SCHEDULED, result.formattedEntries[4].type)
        assertEquals(WalEntryType.RETRIED, result.formattedEntries[5].type)
        assertEquals(WalEntryType.SCOPE_VIOLATION, result.formattedEntries[6].type)
        assertEquals(WalEntryType.VERIFY, result.formattedEntries[7].type)
    }

    @Test
    fun `summarizeReplay counts verify entries`() {
        val entries = listOf(
            WALEntry.VerifyCompleted(taskId = "v0", success = true, exitCode = 0, command = "echo ok", groupIndex = 0),
            WALEntry.VerifyCompleted(taskId = "v1", success = false, exitCode = 1, command = "false", groupIndex = 1)
        )

        val result = summarizeReplay(entries, verbose = false)
        assertEquals(2, result.verifyCount)
        assertTrue(result.formattedEntries[0].headline.contains("passed"))
        assertTrue(result.formattedEntries[1].headline.contains("failed"))
    }

    // --- checkConsistency ---

    @Test
    fun `checkConsistency returns NoSnapshots when empty`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            val result = checkConsistency(emptyList(), root)
            assertIs<ConsistencyResult.NoSnapshots>(result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `checkConsistency returns Consistent when unchanged`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = io.qorche.agent.MockAgentRunner(
                filesToTouch = listOf("src/output.txt"), delayMs = 10
            )
            kotlinx.coroutines.runBlocking {
                orchestrator.runTask("t1", "do thing", runner)
            }

            val snapshots = orchestrator.history()
            val result = checkConsistency(snapshots, root)
            assertIs<ConsistencyResult.Consistent>(result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `checkConsistency returns Diverged when files changed`() {
        val root = Files.createTempDirectory("logic-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = io.qorche.agent.MockAgentRunner(
                filesToTouch = listOf("src/output.txt"), delayMs = 10
            )
            kotlinx.coroutines.runBlocking {
                orchestrator.runTask("t1", "do thing", runner)
            }

            // Modify a file after the snapshot
            root.resolve("src/extra.kt").writeText("val x = 1")

            val snapshots = orchestrator.history()
            val result = checkConsistency(snapshots, root)
            assertIs<ConsistencyResult.Diverged>(result)
            assertTrue(result.diff.totalChanges > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- buildPlanSummary ---

    @Test
    fun `buildPlanSummary computes execution order`() {
        val yaml = """
            project: plan-test
            tasks:
              - id: explore
                instruction: explore codebase
              - id: backend
                instruction: build backend
                depends_on: [explore]
              - id: frontend
                instruction: build frontend
                depends_on: [explore]
              - id: integrate
                instruction: integrate
                depends_on: [backend, frontend]
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val graph = TaskYamlParser.parseToGraph(yaml)
        val summary = buildPlanSummary(project, graph)

        assertEquals("plan-test", summary.projectName)
        assertEquals(4, summary.taskCount)
        assertEquals("explore", summary.executionOrder.first().id)
        assertEquals("integrate", summary.executionOrder.last().id)
        assertEquals(1, summary.executionOrder.first().index)
    }

    @Test
    fun `buildPlanSummary detects parallel groups`() {
        val yaml = """
            project: parallel-test
            tasks:
              - id: a
                instruction: task a
              - id: b
                instruction: task b
              - id: c
                instruction: task c
                depends_on: [a, b]
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val graph = TaskYamlParser.parseToGraph(yaml)
        val summary = buildPlanSummary(project, graph)

        assertTrue(summary.parallelGroups.isNotEmpty(), "Should have parallel groups")
        assertTrue(summary.parallelGroups.any { it.size == 2 }, "Should have a group of 2: ${summary.parallelGroups}")
    }

    @Test
    fun `buildPlanSummary detects scope overlaps`() {
        val yaml = """
            project: overlap-test
            tasks:
              - id: a
                instruction: task a
                files: [src/shared.kt, src/a.kt]
              - id: b
                instruction: task b
                files: [src/shared.kt, src/b.kt]
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val graph = TaskYamlParser.parseToGraph(yaml)
        val summary = buildPlanSummary(project, graph)

        assertEquals(1, summary.warnings.size)
        assertTrue(summary.warnings[0].overlappingFiles.contains("src/shared.kt"))
    }

    @Test
    fun `buildPlanSummary shows dependencies text`() {
        val yaml = """
            project: dep-test
            tasks:
              - id: root
                instruction: root task
              - id: child
                instruction: child task
                depends_on: [root]
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val graph = TaskYamlParser.parseToGraph(yaml)
        val summary = buildPlanSummary(project, graph)

        val rootEntry = summary.executionOrder.find { it.id == "root" }!!
        val childEntry = summary.executionOrder.find { it.id == "child" }!!
        assertEquals("no dependencies", rootEntry.dependencies)
        assertTrue(childEntry.dependencies.contains("root"))
    }

    // --- interpretGraphResult ---

    @Test
    fun `interpretGraphResult returns SUCCESS for successful result`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 1,
            completedTasks = 1,
            failedTasks = 0,
            skippedTasks = 0
        )
        assertEquals(ExitCode.SUCCESS, interpretGraphResult(result))
    }

    @Test
    fun `interpretGraphResult returns TASK_FAILURE for failed result`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 1,
            completedTasks = 0,
            failedTasks = 1,
            skippedTasks = 0
        )
        assertEquals(ExitCode.TASK_FAILURE, interpretGraphResult(result))
    }

    @Test
    fun `interpretGraphResult returns CONFLICT when conflicts present`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 2,
            completedTasks = 0,
            failedTasks = 2,
            skippedTasks = 0,
            conflicts = listOf(
                io.qorche.core.ConflictDetector.TaskConflict(
                    taskA = "t1", taskB = "t2",
                    conflictingFiles = setOf("f.kt")
                )
            )
        )
        assertEquals(ExitCode.CONFLICT, interpretGraphResult(result))
    }

    // --- formatGraphTextSummary ---

    @Test
    fun `formatGraphTextSummary includes task counts`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 3,
            completedTasks = 2,
            failedTasks = 1,
            skippedTasks = 0
        )
        val lines = formatGraphTextSummary(result, 500)
        assertTrue(lines.any { it.contains("2 completed") }, "Should show completed: $lines")
        assertTrue(lines.any { it.contains("1 failed") }, "Should show failed: $lines")
        assertTrue(lines.any { it.contains("500ms") }, "Should show elapsed: $lines")
    }

    @Test
    fun `formatGraphTextSummary includes verify results when present`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 1,
            completedTasks = 1,
            failedTasks = 0,
            skippedTasks = 0,
            verifyResults = listOf(
                VerifyResult(success = true, exitCode = 0, elapsedMs = 100, groupIndex = 0),
                VerifyResult(success = false, exitCode = 1, elapsedMs = 200, groupIndex = 1)
            )
        )
        val lines = formatGraphTextSummary(result, 1000)
        assertTrue(lines.any { it.contains("1 passed") && it.contains("1 failed") }, "Should show verify: $lines")
    }

    @Test
    fun `formatGraphTextSummary includes conflicts when present`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 2,
            completedTasks = 0,
            failedTasks = 2,
            skippedTasks = 0,
            conflicts = listOf(
                io.qorche.core.ConflictDetector.TaskConflict(
                    taskA = "t1", taskB = "t2",
                    conflictingFiles = setOf("f.kt")
                )
            )
        )
        val lines = formatGraphTextSummary(result, 100)
        assertTrue(lines.any { it.contains("Conflicts: 1") }, "Should show conflicts: $lines")
    }

    // --- Utility functions ---

    @Test
    fun `formatElapsed returns ms for short durations`() {
        assertEquals("500ms", formatElapsed(500))
        assertEquals("0ms", formatElapsed(0))
    }

    @Test
    fun `formatElapsed returns seconds for long durations`() {
        assertEquals("1.0s", formatElapsed(1000))
        assertEquals("1.5s", formatElapsed(1500))
    }

    @Test
    fun `parseHashAlgorithm maps known algorithms`() {
        assertEquals(HashAlgorithm.CRC32C, parseHashAlgorithm("crc32c"))
        assertEquals(HashAlgorithm.CRC32C, parseHashAlgorithm("crc32"))
        assertEquals(HashAlgorithm.SHA256, parseHashAlgorithm("sha256"))
        assertEquals(HashAlgorithm.SHA256, parseHashAlgorithm("sha-256"))
        assertEquals(HashAlgorithm.SHA1, parseHashAlgorithm(null))
        assertEquals(HashAlgorithm.SHA1, parseHashAlgorithm("sha1"))
    }

    @Test
    fun `isYamlFile detects yaml extensions`() {
        assertTrue(isYamlFile("tasks.yaml"))
        assertTrue(isYamlFile("tasks.yml"))
        assertTrue(!isYamlFile("Run linter"))
        assertTrue(!isYamlFile("main.kt"))
    }

    @Test
    fun `cliVersion returns non-blank string`() {
        val version = cliVersion()
        assertTrue(version.isNotBlank())
    }

    // --- formatSingleTaskText ---

    @Test
    fun `formatSingleTaskText shows changes when present`() {
        val diff = SnapshotDiff(
            added = setOf("src/new.kt"),
            modified = setOf("src/main.kt"),
            deleted = emptySet(),
            beforeId = "before",
            afterId = "after"
        )
        val result = Orchestrator.RunResult(
            agentResult = AgentResult(exitCode = 0),
            diff = diff,
            beforeSnapshot = makeSnapshot("before"),
            afterSnapshot = makeSnapshot("after")
        )

        val lines = formatSingleTaskText(result, 250)
        assertTrue(lines.any { it.contains("Changes:") }, "Should show changes: $lines")
        assertTrue(lines.any { it.contains("+ src/new.kt") }, "Should show added: $lines")
        assertTrue(lines.any { it.contains("~ src/main.kt") }, "Should show modified: $lines")
        assertTrue(lines.any { it.contains("250ms") }, "Should show elapsed: $lines")
    }

    @Test
    fun `formatSingleTaskText shows no changes message`() {
        val diff = SnapshotDiff(added = emptySet(), modified = emptySet(), deleted = emptySet(), beforeId = "b", afterId = "a")
        val result = Orchestrator.RunResult(
            agentResult = AgentResult(exitCode = 0),
            diff = diff,
            beforeSnapshot = makeSnapshot("before"),
            afterSnapshot = makeSnapshot("after")
        )

        val lines = formatSingleTaskText(result, 100)
        assertTrue(lines.any { it.contains("No file changes") }, "Should show no changes: $lines")
    }

    @Test
    fun `buildSingleTaskGraphResult wraps successful result`() {
        val diff = SnapshotDiff(added = emptySet(), modified = emptySet(), deleted = emptySet(), beforeId = "b", afterId = "a")
        val runResult = Orchestrator.RunResult(
            agentResult = AgentResult(exitCode = 0),
            diff = diff,
            beforeSnapshot = makeSnapshot("b"),
            afterSnapshot = makeSnapshot("a")
        )

        val graph = buildSingleTaskGraphResult(runResult)
        assertTrue(graph.success)
        assertEquals(1, graph.completedTasks)
        assertEquals(0, graph.failedTasks)
        assertEquals("cli-run", graph.project)
    }

    @Test
    fun `buildSingleTaskGraphResult wraps failed result`() {
        val diff = SnapshotDiff(added = emptySet(), modified = emptySet(), deleted = emptySet(), beforeId = "b", afterId = "a")
        val runResult = Orchestrator.RunResult(
            agentResult = AgentResult(exitCode = 1),
            diff = diff,
            beforeSnapshot = makeSnapshot("b"),
            afterSnapshot = makeSnapshot("a")
        )

        val graph = buildSingleTaskGraphResult(runResult)
        assertTrue(!graph.success)
        assertEquals(0, graph.completedTasks)
        assertEquals(1, graph.failedTasks)
    }

    // --- prepareGraphRun ---

    @Test
    fun `prepareGraphRun returns Ready for valid YAML`() {
        val fixture = fixtureFile("simple-task.yaml")
        val mockRunner = io.qorche.agent.MockAgentRunner()

        val result = prepareGraphRun(
            filePath = fixture,
            buildRunners = { emptyMap() },
            fallbackRunner = { mockRunner }
        )

        assertIs<GraphRunSetup.Ready>(result)
        assertEquals("test", result.project.project)
        assertEquals(mockRunner, result.defaultRunner)
    }

    @Test
    fun `prepareGraphRun returns Failed for invalid YAML`() {
        val fixture = fixtureFile("invalid.yaml")

        val result = prepareGraphRun(
            filePath = fixture,
            buildRunners = { emptyMap() },
            fallbackRunner = { io.qorche.agent.MockAgentRunner() }
        )

        assertIs<GraphRunSetup.Failed>(result)
        assertEquals(ExitCode.CONFIG_ERROR, result.exitCode)
    }

    @Test
    fun `prepareGraphRun returns Failed when runner build throws`() {
        val fixture = fixtureFile("simple-task.yaml")

        val result = prepareGraphRun(
            filePath = fixture,
            buildRunners = { throw IllegalArgumentException("bad runner") },
            fallbackRunner = { io.qorche.agent.MockAgentRunner() }
        )

        assertIs<GraphRunSetup.Failed>(result)
        assertTrue(result.message.contains("bad runner"))
    }

    @Test
    fun `prepareGraphRun returns Failed for missing default runner`() {
        val fixture = fixtureFile("missing-default-runner.yaml")
        val mockRunner = io.qorche.agent.MockAgentRunner()

        val result = prepareGraphRun(
            filePath = fixture,
            buildRunners = { configs ->
                configs.mapValues { mockRunner as AgentRunner }
            },
            fallbackRunner = { mockRunner }
        )

        assertIs<GraphRunSetup.Failed>(result)
        assertTrue(result.message.contains("missing"), "Should mention missing runner: ${result.message}")
    }

    @Test
    fun `prepareGraphRun wires named runners from config`() {
        val fixture = fixtureFile("with-runners.yaml")
        val shellRunner = io.qorche.agent.MockAgentRunner()

        val result = prepareGraphRun(
            filePath = fixture,
            buildRunners = { configs ->
                configs.mapValues { shellRunner as AgentRunner }
            },
            fallbackRunner = { io.qorche.agent.MockAgentRunner() }
        )

        assertIs<GraphRunSetup.Ready>(result)
        assertTrue(result.runners.containsKey("shell"))
    }

    // --- formatHistory ---

    @Test
    fun `formatHistory with no limit shows all`() {
        val snapshots = listOf(
            makeSnapshot("snap-1", description = "before task-1"),
            makeSnapshot("snap-2", description = "after task-1")
        )

        val output = formatHistory(snapshots, null)
        assertEquals(2, output.lines.size)
        assertEquals(0, output.truncatedCount)
        assertEquals("snap-1".take(8), output.lines[0].idPrefix)
    }

    @Test
    fun `formatHistory with limit truncates`() {
        val snapshots = listOf(
            makeSnapshot("snapshot-1"),
            makeSnapshot("snapshot-2"),
            makeSnapshot("snapshot-3")
        )

        val output = formatHistory(snapshots, 2)
        assertEquals(2, output.lines.size)
        assertEquals(1, output.truncatedCount)
    }

    @Test
    fun `formatHistory empty list`() {
        val output = formatHistory(emptyList(), null)
        assertTrue(output.lines.isEmpty())
        assertEquals(0, output.truncatedCount)
    }

    // --- resolveSnapshotIds ---

    @Test
    fun `resolveSnapshotIds with explicit ids`() {
        val snapshots = listOf(
            makeSnapshot("abc12345-full-id"),
            makeSnapshot("def67890-full-id")
        )

        val result = resolveSnapshotIds("abc12345", "def67890", snapshots)
        assertIs<DiffResolution.Resolved>(result)
        assertEquals("abc12345-full-id", result.fullId1)
        assertEquals("def67890-full-id", result.fullId2)
    }

    @Test
    fun `resolveSnapshotIds with null id2 uses parent`() {
        val snapshots = listOf(
            makeSnapshot("child-id", parentId = "parent-full-id"),
            makeSnapshot("parent-full-id")
        )

        val result = resolveSnapshotIds("child", null, snapshots)
        assertIs<DiffResolution.Resolved>(result)
        assertEquals("child-id", result.fullId1)
        assertEquals("parent-full-id", result.fullId2)
    }

    @Test
    fun `resolveSnapshotIds returns NoComparison when no parent`() {
        val snapshots = listOf(makeSnapshot("orphan-id"))

        val result = resolveSnapshotIds("orphan", null, snapshots)
        assertIs<DiffResolution.NoComparison>(result)
    }

    @Test
    fun `resolveSnapshotIds returns NoComparison when id1 prefix not found`() {
        val snapshots = listOf(makeSnapshot("abc12345-full-id"))

        val result = resolveSnapshotIds("zzz-missing", "abc12345", snapshots)
        assertIs<DiffResolution.NoComparison>(result)
        assertTrue(result.message.contains("zzz-missing"))
    }

    @Test
    fun `resolveSnapshotIds returns NoComparison when id2 prefix not found`() {
        val snapshots = listOf(makeSnapshot("abc12345-full-id"))

        val result = resolveSnapshotIds("abc12345", "zzz-missing", snapshots)
        assertIs<DiffResolution.NoComparison>(result)
        assertTrue(result.message.contains("zzz-missing"))
    }

    // --- loadTaskGraph with fixtures ---

    @Test
    fun `loadTaskGraph with fixture file`() {
        val result = loadTaskGraph(fixtureFile("simple-task.yaml"))
        assertIs<TaskGraphLoadResult.Success>(result)
        assertEquals("test", result.project.project)
    }

    @Test
    fun `loadTaskGraph with parallel fixture`() {
        val result = loadTaskGraph(fixtureFile("parallel-with-join.yaml"))
        assertIs<TaskGraphLoadResult.Success>(result)
        assertEquals(3, result.project.tasks.size)
        val groups = result.graph.parallelGroups()
        assertTrue(groups.any { it.size == 2 }, "Should have parallel group: $groups")
    }

    @Test
    fun `loadVerifyConfig with fixture`() {
        val result = loadVerifyConfig(fixtureFile("with-verify.yaml"))
        assertIs<VerifyLoadResult.Success>(result)
        assertEquals("echo ok", result.config.command)
    }

    // --- Test helpers ---

    private fun makeSnapshot(
        id: String = "test-snap",
        description: String = "test",
        parentId: String? = null
    ): Snapshot = Snapshot(
        id = id,
        timestamp = Clock.System.now(),
        description = description,
        fileHashes = mapOf("src/main.kt" to "abc123"),
        parentId = parentId
    )

    private fun fixtureFile(name: String): Path {
        val url = javaClass.getResource("/fixtures/$name")
        assertNotNull(url, "Fixture file not found: $name")
        return Path.of(url.toURI())
    }
}
