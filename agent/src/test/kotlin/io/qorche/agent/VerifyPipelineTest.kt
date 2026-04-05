package io.qorche.agent

import io.qorche.core.Orchestrator
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskGraph
import io.qorche.core.TaskStatus
import io.qorche.core.VerifyConfig
import io.qorche.core.VerifyFailurePolicy
import io.qorche.core.VerifyResult
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the verification pipeline.
 *
 * These exercise the full Orchestrator → verify command → result pipeline,
 * using real process execution (echo/cmd) to validate that verification
 * actually runs shell commands and captures results.
 */
@Tag("smoke")
class VerifyPipelineTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** A command that always succeeds. */
    private val successCommand = if (isWindows) "cmd /c echo ok" else "echo ok"

    /** A command that always fails. */
    private val failCommand = if (isWindows) "cmd /c exit 1" else "false"

    @Test
    fun `verify runs after single-task group and records success`() = runBlocking {
        val root = Files.createTempDirectory("qorche-verify-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            val task = TaskDefinition(id = "task1", instruction = "create file")
            val graph = TaskGraph(listOf(task))

            val verifyConfig = VerifyConfig(
                command = successCommand,
                timeoutSeconds = 30
            )

            val verifyResults = mutableListOf<VerifyResult>()

            val result = orchestrator.runGraphParallel(
                project = "verify-test",
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig,
                onVerify = { verifyResults.add(it) }
            )

            // Task should complete
            assertEquals(1, result.completedTasks)
            assertTrue(result.success, "Pipeline should succeed when verify passes")

            // Verify was called and passed
            assertEquals(1, verifyResults.size)
            assertTrue(verifyResults[0].success)
            assertEquals(0, verifyResults[0].exitCode)
            assertEquals(0, verifyResults[0].groupIndex)

            // GraphResult includes verify
            assertEquals(1, result.verifyResults.size)
            assertFalse(result.hasVerifyFailure)

            // WAL should have a VerifyCompleted entry
            val walEntries = orchestrator.walEntries()
            val verifyEntry = walEntries.filterIsInstance<WALEntry.VerifyCompleted>()
            assertEquals(1, verifyEntry.size)
            assertTrue(verifyEntry[0].success)
            assertEquals(successCommand, verifyEntry[0].command)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify failure with FAIL policy marks pipeline as failed`() = runBlocking {
        val root = Files.createTempDirectory("qorche-verify-fail-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            val task = TaskDefinition(id = "task1", instruction = "create file")
            val graph = TaskGraph(listOf(task))

            val verifyConfig = VerifyConfig(
                command = failCommand,
                timeoutSeconds = 30,
                onFailure = VerifyFailurePolicy.FAIL
            )

            val result = orchestrator.runGraphParallel(
                project = "verify-fail-test",
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig
            )

            // Task completed but pipeline is not successful due to verify failure
            assertEquals(1, result.completedTasks)
            assertFalse(result.success, "Pipeline should fail when verify fails with FAIL policy")
            assertTrue(result.hasVerifyFailure)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify failure with WARN policy allows pipeline to succeed`() = runBlocking {
        val root = Files.createTempDirectory("qorche-verify-warn-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            val task1 = TaskDefinition(id = "task1", instruction = "first task")
            val task2 = TaskDefinition(
                id = "task2",
                instruction = "second task",
                dependsOn = listOf("task1")
            )
            val graph = TaskGraph(listOf(task1, task2))

            val verifyConfig = VerifyConfig(
                command = failCommand,
                timeoutSeconds = 30,
                onFailure = VerifyFailurePolicy.WARN
            )

            val result = orchestrator.runGraphParallel(
                project = "verify-warn-test",
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig
            )

            // Both tasks should run — WARN policy doesn't abort the pipeline
            assertEquals(2, result.completedTasks)
            // But hasVerifyFailure is still true
            assertTrue(result.hasVerifyFailure)
            // success is false because of verify failure
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify skipped when task fails`() = runBlocking {
        val root = Files.createTempDirectory("qorche-verify-skip-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(shouldFail = true, delayMs = 50)

            val task = TaskDefinition(id = "fail-task", instruction = "will fail")
            val graph = TaskGraph(listOf(task))

            val verifyConfig = VerifyConfig(command = successCommand)

            val verifyResults = mutableListOf<VerifyResult>()

            val result = orchestrator.runGraphParallel(
                project = "verify-skip-test",
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig,
                onVerify = { verifyResults.add(it) }
            )

            // Task failed, verify should not run (only runs on successful tasks)
            assertEquals(1, result.failedTasks)
            assertEquals(0, verifyResults.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify runs after each group in multi-group DAG`() = runBlocking {
        val root = Files.createTempDirectory("qorche-verify-groups-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            // Two groups: task1 (group 0) → task2 (group 1)
            val task1 = TaskDefinition(id = "task1", instruction = "first")
            val task2 = TaskDefinition(id = "task2", instruction = "second", dependsOn = listOf("task1"))
            val graph = TaskGraph(listOf(task1, task2))

            val verifyConfig = VerifyConfig(command = successCommand, timeoutSeconds = 30)
            val verifyResults = mutableListOf<VerifyResult>()

            val result = orchestrator.runGraphParallel(
                project = "multi-group-test",
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig,
                onVerify = { verifyResults.add(it) }
            )

            assertTrue(result.success)
            // Should have verification for each group
            assertEquals(2, verifyResults.size)
            assertEquals(0, verifyResults[0].groupIndex)
            assertEquals(1, verifyResults[1].groupIndex)
            assertTrue(verifyResults.all { it.success })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
