package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.Orchestrator
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.WALEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchestratorEdgeCaseTest {

    @Test
    fun `empty task list is rejected by parser`() {
        val yaml = """
            project: empty-project
            tasks: []
        """.trimIndent()

        assertFailsWith<IllegalArgumentException>("Should reject empty task list") {
            TaskYamlParser.parseToGraph(yaml)
        }
    }

    @Test
    fun `single task graph executes and completes`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: single-task
                tasks:
                  - id: only-task
                    instruction: "Do the thing"
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10)

            val result = orchestrator.runGraph(
                project = "single-task",
                graph = graph,
                runner = runner
            )

            assertEquals(1, result.totalTasks)
            assertEquals(1, result.completedTasks)
            assertTrue(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cascading failure skips entire dependency chain`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: cascade-test
                tasks:
                  - id: root-task
                    instruction: "This will fail"
                  - id: child-a
                    instruction: "Depends on root"
                    depends_on: [root-task]
                  - id: child-b
                    instruction: "Depends on root"
                    depends_on: [root-task]
                  - id: grandchild
                    instruction: "Depends on both children"
                    depends_on: [child-a, child-b]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(shouldFail = true, delayMs = 10)

            val result = orchestrator.runGraph(
                project = "cascade-test",
                graph = graph,
                runner = runner
            )

            assertEquals(4, result.totalTasks)
            assertEquals(1, result.failedTasks, "Only root-task should fail")
            assertEquals(3, result.skippedTasks, "All dependents should be skipped")
            assertFalse(result.success)

            assertEquals(TaskStatus.FAILED, result.taskResults["root-task"]!!.status)
            assertEquals(TaskStatus.SKIPPED, result.taskResults["child-a"]!!.status)
            assertEquals(TaskStatus.SKIPPED, result.taskResults["child-b"]!!.status)
            assertEquals(TaskStatus.SKIPPED, result.taskResults["grandchild"]!!.status)

            assertTrue(result.taskResults["child-a"]!!.skipReason!!.contains("root-task"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runTask returns Result failure when agent throws exception`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val throwingRunner = object : AgentRunner {
                override fun run(
                    instruction: String,
                    workingDirectory: Path,
                    onOutput: (String) -> Unit
                ): Flow<AgentEvent> = flow {
                    throw RuntimeException("Process crashed")
                }
            }

            val result = orchestrator.runTask(
                taskId = "crash-task",
                instruction = "this will throw",
                runner = throwingRunner
            )

            assertTrue(result.isFailure, "Should return Result.failure for thrown exception")
            assertEquals("Process crashed", result.exceptionOrNull()!!.message)

            // WAL should still log the failure
            val entries = orchestrator.walEntries()
            assertTrue(entries.any { it is WALEntry.TaskStarted && it.taskId == "crash-task" })
            assertTrue(entries.any { it is WALEntry.TaskFailed && it.taskId == "crash-task" })

            // After-snapshot should still be taken (design principle: always snapshot)
            val history = orchestrator.history()
            assertTrue(history.size >= 2, "Should have before and after snapshots even on crash")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runTask returns Result success with non-zero exit code`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(shouldFail = true, delayMs = 10)

            val result = orchestrator.runTask(
                taskId = "fail-task",
                instruction = "exit non-zero",
                runner = runner
            )

            assertTrue(result.isSuccess, "Non-zero exit is a successful Result (agent ran, didn't crash)")
            assertEquals(1, result.getOrThrow().agentResult.exitCode)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `graph handles agent exception gracefully without crashing other tasks`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: exception-test
                tasks:
                  - id: good-task
                    instruction: "This succeeds"
                  - id: bad-task
                    instruction: "This throws"
                    depends_on: [good-task]
                  - id: after-bad
                    instruction: "Should be skipped"
                    depends_on: [bad-task]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)

            var callCount = 0
            val runner = object : AgentRunner {
                override fun run(
                    instruction: String,
                    workingDirectory: Path,
                    onOutput: (String) -> Unit
                ): Flow<AgentEvent> = flow {
                    callCount++
                    delay(10)
                    if (callCount == 2) {
                        throw RuntimeException("Agent process died")
                    }
                    emit(AgentEvent.Completed(exitCode = 0))
                }
            }

            val result = orchestrator.runGraph(
                project = "exception-test",
                graph = graph,
                runner = runner
            )

            assertEquals(1, result.completedTasks, "good-task should complete")
            assertEquals(1, result.failedTasks, "bad-task should fail")
            assertEquals(1, result.skippedTasks, "after-bad should be skipped")

            val badOutcome = result.taskResults["bad-task"]!!
            assertEquals(TaskStatus.FAILED, badOutcome.status)
            assertTrue(badOutcome.skipReason!!.contains("Agent process died"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parallel graph single task in group skips conflict detection`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: single-parallel
                tasks:
                  - id: alone
                    instruction: "Only task in its group"
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)

            val result = orchestrator.runGraphParallel(
                project = "single-parallel",
                graph = graph,
                runner = runner
            )

            assertEquals(1, result.completedTasks)
            assertTrue(result.conflicts.isEmpty())
            assertTrue(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `callbacks are invoked for task start and complete`() = runBlocking {
        val root = Files.createTempDirectory("qorche-edge-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: callback-test
                tasks:
                  - id: task-a
                    instruction: "First"
                  - id: task-b
                    instruction: "Second"
                    depends_on: [task-a]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10)

            val started = mutableListOf<String>()
            val completed = mutableListOf<String>()

            orchestrator.runGraph(
                project = "callback-test",
                graph = graph,
                runner = runner,
                onTaskStart = { def -> started.add(def.id) },
                onTaskComplete = { taskId, _ -> completed.add(taskId) }
            )

            assertEquals(listOf("task-a", "task-b"), started)
            assertEquals(listOf("task-a", "task-b"), completed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
