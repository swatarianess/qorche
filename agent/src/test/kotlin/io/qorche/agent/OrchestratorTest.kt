package io.qorche.agent

import io.qorche.core.Orchestrator
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorTest {

    @Test
    fun `run task takes snapshots and logs to WAL`() = runBlocking {
        val root = Files.createTempDirectory("qorche-orch-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(
                filesToTouch = listOf("src/output.txt"),
                delayMs = 50
            )

            val result = orchestrator.runTask(
                taskId = "test-task",
                instruction = "create output",
                runner = runner
            ).getOrThrow()

            assertEquals(0, result.agentResult.exitCode)
            assertTrue(result.diff.added.isNotEmpty() || result.diff.modified.isNotEmpty(),
                "Should detect file changes")

            // Verify snapshots persisted
            val history = orchestrator.history()
            assertTrue(history.size >= 2, "Should have before and after snapshots")

            // Verify WAL entries
            val entries = orchestrator.walEntries()
            assertTrue(entries.any { it is WALEntry.TaskStarted && it.taskId == "test-task" })
            assertTrue(entries.any { it is WALEntry.TaskCompleted && it.taskId == "test-task" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed task logs TaskFailed to WAL`() = runBlocking {
        val root = Files.createTempDirectory("qorche-orch-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(shouldFail = true, delayMs = 50)

            val result = orchestrator.runTask(
                taskId = "fail-task",
                instruction = "this will fail",
                runner = runner
            ).getOrThrow()

            assertEquals(1, result.agentResult.exitCode)

            val entries = orchestrator.walEntries()
            assertTrue(entries.any { it is WALEntry.TaskFailed && it.taskId == "fail-task" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `file index is persisted between runs`() = runBlocking {
        val root = Files.createTempDirectory("qorche-orch-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            orchestrator.runTask("t1", "first run", runner).getOrThrow()

            // Verify file index was persisted
            assertTrue(root.resolve(".qorche/file-index.json").exists())

            // Second orchestrator instance should load the persisted index
            val orchestrator2 = Orchestrator(root)
            val history = orchestrator2.history()
            assertTrue(history.isNotEmpty(), "Second instance should see persisted snapshots")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scoped task only snapshots relevant files`() = runBlocking {
        val root = Files.createTempDirectory("qorche-orch-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")
            root.resolve("docs").createDirectories()
            root.resolve("docs/readme.md").writeText("# Readme")
            root.resolve("other.txt").writeText("unrelated")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            val result = orchestrator.runTask(
                taskId = "scoped-task",
                instruction = "only touch src",
                runner = runner,
                scopePaths = listOf("src")
            ).getOrThrow()

            // Before snapshot should only contain src/ files
            val beforeSnap = orchestrator.loadSnapshot(result.beforeSnapshot.id)!!
            assertTrue(beforeSnap.fileHashes.keys.all { it.startsWith("src/") },
                "Scoped snapshot should only contain src/ files, got: ${beforeSnap.fileHashes.keys}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diff between two snapshot IDs`() = runBlocking {
        val root = Files.createTempDirectory("qorche-orch-test")
        try {
            root.resolve("a.txt").writeText("original\n")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("a.txt"), delayMs = 50)

            val result = orchestrator.runTask("t1", "modify a", runner).getOrThrow()

            val diff = orchestrator.diffSnapshots(result.beforeSnapshot.id, result.afterSnapshot.id)!!
            assertTrue(diff.modified.contains("a.txt") || diff.added.contains("a.txt"),
                "Diff should show a.txt was changed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `task outcome includes elapsed time`() = runBlocking {
        val root = Files.createTempDirectory("qorche-elapsed-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: elapsed-test
                tasks:
                  - id: task-a
                    instruction: "First task"
                  - id: task-b
                    instruction: "Second task"
                    depends_on: [task-a]
            """.trimIndent()

            val graph = io.qorche.core.TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 50)

            val result = orchestrator.runGraph(
                project = "elapsed-test",
                graph = graph,
                runner = runner
            )

            assertTrue(result.success)
            for ((taskId, outcome) in result.taskResults) {
                assertTrue(outcome.elapsedMs > 0, "Task $taskId should have elapsed time > 0, got ${outcome.elapsedMs}")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `per-task runners dispatch to correct runner`() = runBlocking {
        val root = Files.createTempDirectory("qorche-runner-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: runner-dispatch
                runners:
                  fast:
                    type: claude-code
                  slow:
                    type: claude-code
                tasks:
                  - id: fast-task
                    instruction: "touch fast"
                    runner: fast
                    files: [src/fast.txt]
                  - id: slow-task
                    instruction: "touch slow"
                    runner: slow
                    files: [src/slow.txt]
                  - id: default-task
                    instruction: "touch default"
                    files: [src/default.txt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)

            // Each runner writes a different marker to verify dispatch
            val fastRunner = MockAgentRunner(filesToTouch = listOf("src/fast.txt"), delayMs = 10)
            val slowRunner = MockAgentRunner(filesToTouch = listOf("src/slow.txt"), delayMs = 10)
            val defaultRunner = MockAgentRunner(filesToTouch = listOf("src/default.txt"), delayMs = 10)

            val result = orchestrator.runGraph(
                project = "runner-dispatch",
                graph = graph,
                runner = defaultRunner,
                runners = mapOf("fast" to fastRunner, "slow" to slowRunner)
            )

            assertTrue(result.success, "All tasks should succeed")
            assertEquals(3, result.completedTasks)

            // Verify each runner was actually invoked by checking its touched files exist
            assertTrue(root.resolve("src/fast.txt").exists(), "fast runner should have created src/fast.txt")
            assertTrue(root.resolve("src/slow.txt").exists(), "slow runner should have created src/slow.txt")
            assertTrue(root.resolve("src/default.txt").exists(), "default runner should have created src/default.txt")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `per-task runners work with parallel execution`() = runBlocking {
        val root = Files.createTempDirectory("qorche-parallel-runner-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: parallel-runners
                runners:
                  runner-a:
                    type: claude-code
                  runner-b:
                    type: claude-code
                tasks:
                  - id: task-a
                    instruction: "touch a"
                    runner: runner-a
                    files: [src/a.txt]
                  - id: task-b
                    instruction: "touch b"
                    runner: runner-b
                    files: [src/b.txt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)

            val runnerA = MockAgentRunner(filesToTouch = listOf("src/a.txt"), delayMs = 10)
            val runnerB = MockAgentRunner(filesToTouch = listOf("src/b.txt"), delayMs = 10)
            val defaultRunner = MockAgentRunner(delayMs = 10)

            val result = orchestrator.runGraphParallel(
                project = "parallel-runners",
                graph = graph,
                runner = defaultRunner,
                runners = mapOf("runner-a" to runnerA, "runner-b" to runnerB)
            )

            assertTrue(result.success)
            assertEquals(2, result.completedTasks)
            assertTrue(root.resolve("src/a.txt").exists())
            assertTrue(root.resolve("src/b.txt").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `skipped tasks have zero elapsed time`() = runBlocking {
        val root = Files.createTempDirectory("qorche-elapsed-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: skip-test
                tasks:
                  - id: will-fail
                    instruction: "This will fail"
                  - id: will-skip
                    instruction: "This depends on failure"
                    depends_on: [will-fail]
            """.trimIndent()

            val graph = io.qorche.core.TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10, shouldFail = true)

            val result = orchestrator.runGraph(
                project = "skip-test",
                graph = graph,
                runner = runner
            )

            val skipped = result.taskResults["will-skip"]!!
            assertEquals(io.qorche.core.TaskStatus.SKIPPED, skipped.status)
            assertEquals(0, skipped.elapsedMs)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
