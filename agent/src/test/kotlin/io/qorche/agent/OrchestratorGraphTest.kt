package io.qorche.agent

import io.qorche.core.Orchestrator
import io.qorche.core.TaskGraph
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorGraphTest {

    @Test
    fun `execute 5-task graph sequentially`() = runBlocking {
        val root = Files.createTempDirectory("qorche-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: test-project
                tasks:
                  - id: explore
                    instruction: "Map the codebase"
                    type: explore
                  - id: backend
                    instruction: "Implement backend"
                    depends_on: [explore]
                    files: [src/backend.kt]
                  - id: frontend
                    instruction: "Build frontend"
                    depends_on: [explore]
                    files: [src/frontend.kt]
                  - id: tests
                    instruction: "Write tests"
                    depends_on: [backend, frontend]
                  - id: verify
                    instruction: "Run verification"
                    depends_on: [tests]
                    type: verify
            """.trimIndent()

            val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(
                filesToTouch = listOf("src/output.txt"),
                delayMs = 10
            )

            val result = orchestrator.runGraph(
                project = project.project,
                graph = graph,
                runner = runner
            )

            assertEquals(5, result.totalTasks)
            assertEquals(5, result.completedTasks)
            assertEquals(0, result.failedTasks)
            assertEquals(0, result.skippedTasks)
            assertTrue(result.success)

            // Verify WAL has entries for all tasks
            val entries = orchestrator.walEntries()
            val startedIds = entries.filterIsInstance<WALEntry.TaskStarted>().map { it.taskId }.toSet()
            val completedIds = entries.filterIsInstance<WALEntry.TaskCompleted>().map { it.taskId }.toSet()
            assertTrue("explore" in startedIds)
            assertTrue("backend" in startedIds)
            assertTrue("frontend" in startedIds)
            assertTrue("tests" in startedIds)
            assertTrue("verify" in startedIds)
            assertEquals(startedIds, completedIds)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed task causes dependents to be skipped`() = runBlocking {
        val root = Files.createTempDirectory("qorche-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: test-failure
                tasks:
                  - id: step1
                    instruction: "First step"
                  - id: step2
                    instruction: "Second step (will fail)"
                    depends_on: [step1]
                  - id: step3
                    instruction: "Third step (should be skipped)"
                    depends_on: [step2]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)

            // step1 succeeds, step2 fails
            var taskCount = 0
            val runner = object : io.qorche.core.AgentRunner {
                override fun run(
                    instruction: String,
                    workingDirectory: java.nio.file.Path,
                    onOutput: (String) -> Unit
                ): kotlinx.coroutines.flow.Flow<io.qorche.core.AgentEvent> = kotlinx.coroutines.flow.flow {
                    taskCount++
                    kotlinx.coroutines.delay(10)
                    if (taskCount == 2) {
                        // Second task fails
                        emit(io.qorche.core.AgentEvent.Completed(exitCode = 1))
                    } else {
                        emit(io.qorche.core.AgentEvent.Completed(exitCode = 0))
                    }
                }
            }

            val result = orchestrator.runGraph(
                project = "test-failure",
                graph = graph,
                runner = runner
            )

            assertEquals(3, result.totalTasks)
            assertEquals(1, result.completedTasks, "Only step1 should complete")
            assertEquals(1, result.failedTasks, "step2 should fail")
            assertEquals(1, result.skippedTasks, "step3 should be skipped")

            // Verify step3 was skipped with reason
            val step3Outcome = result.taskResults["step3"]!!
            assertEquals(TaskStatus.SKIPPED, step3Outcome.status)
            assertTrue(step3Outcome.skipReason!!.contains("step2"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `graph execution respects topological order`() = runBlocking {
        val root = Files.createTempDirectory("qorche-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: order-test
                tasks:
                  - id: c
                    instruction: "third"
                    depends_on: [b]
                  - id: a
                    instruction: "first"
                  - id: b
                    instruction: "second"
                    depends_on: [a]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10)

            val executionOrder = mutableListOf<String>()
            orchestrator.runGraph(
                project = "order-test",
                graph = graph,
                runner = runner,
                onTaskStart = { def -> executionOrder.add(def.id) }
            )

            assertEquals(listOf("a", "b", "c"), executionOrder)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `graph with scoped tasks uses file paths`() = runBlocking {
        val root = Files.createTempDirectory("qorche-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")
            root.resolve("docs").createDirectories()
            root.resolve("docs/readme.md").writeText("# Readme")

            val yaml = """
                project: scoped-test
                tasks:
                  - id: backend
                    instruction: "Backend work"
                    files: [src]
                  - id: docs
                    instruction: "Update docs"
                    files: [docs]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10)

            val result = orchestrator.runGraph(
                project = "scoped-test",
                graph = graph,
                runner = runner
            )

            assertEquals(2, result.completedTasks)
            assertTrue(result.success)

            // Backend task's before-snapshot should only have src/ files
            val backendOutcome = result.taskResults["backend"]!!
            val beforeSnap = orchestrator.loadSnapshot(backendOutcome.runResult!!.beforeSnapshot.id)!!
            assertTrue(beforeSnap.fileHashes.keys.all { it.startsWith("src/") },
                "Scoped snapshot should only contain src/ files, got: ${beforeSnap.fileHashes.keys}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WAL records complete history for graph execution`() = runBlocking {
        val root = Files.createTempDirectory("qorche-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val yaml = """
                project: wal-test
                tasks:
                  - id: first
                    instruction: "First task"
                  - id: second
                    instruction: "Second task"
                    depends_on: [first]
                  - id: third
                    instruction: "Third task"
                    depends_on: [second]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(delayMs = 10)

            orchestrator.runGraph(
                project = "wal-test",
                graph = graph,
                runner = runner
            )

            val entries = orchestrator.walEntries()
            // Should have 3 starts + 3 completions = 6 entries
            assertEquals(6, entries.size)

            // Verify chronological order: start1, complete1, start2, complete2, start3, complete3
            val taskStarted = entries.filterIsInstance<WALEntry.TaskStarted>()
            val taskCompleted = entries.filterIsInstance<WALEntry.TaskCompleted>()
            assertEquals(3, taskStarted.size)
            assertEquals(3, taskCompleted.size)

            // First started should be before first completed
            assertTrue(taskStarted[0].timestamp <= taskCompleted[0].timestamp)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
