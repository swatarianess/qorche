package io.qorche.agent

import io.qorche.core.Orchestrator
import io.qorche.core.SnapshotCreator
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskGraph
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for WAL replay and snapshot consistency checking.
 *
 * These simulate the full execution → replay → verify consistency pipeline,
 * exercising the same logic that `qorche replay --check` uses.
 */
@Tag("smoke")
class ReplayConsistencyTest {

    @Test
    fun `WAL captures complete lifecycle for single task`() = runBlocking {
        val root = Files.createTempDirectory("qorche-replay-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            orchestrator.runTask(
                taskId = "replay-task",
                instruction = "create output",
                runner = runner
            ).getOrThrow()

            // Replay: read WAL and verify complete lifecycle
            val entries = orchestrator.walEntries()
            assertTrue(entries.size >= 2, "Should have at least start + completed entries")

            val started = entries.filterIsInstance<WALEntry.TaskStarted>()
            val completed = entries.filterIsInstance<WALEntry.TaskCompleted>()

            assertEquals(1, started.size)
            assertEquals(1, completed.size)
            assertEquals("replay-task", started[0].taskId)
            assertEquals("replay-task", completed[0].taskId)
            assertEquals("create output", started[0].instruction)
            assertEquals(0, completed[0].exitCode)

            // Snapshot IDs should link: started has before, completed has after
            val beforeSnapshotId = started[0].snapshotId
            val afterSnapshotId = completed[0].snapshotId
            assertTrue(beforeSnapshotId != afterSnapshotId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot consistency check passes after clean execution`() = runBlocking {
        val root = Files.createTempDirectory("qorche-consistency-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            orchestrator.runTask(
                taskId = "consistency-task",
                instruction = "create output",
                runner = runner
            ).getOrThrow()

            // Take a fresh snapshot and compare to latest stored
            val snapshots = orchestrator.history()
            assertTrue(snapshots.isNotEmpty(), "Should have stored snapshots")

            val latest = snapshots.first() // Most recent
            val currentSnapshot = SnapshotCreator.create(root, "consistency-check")
            val diff = SnapshotCreator.diff(latest, currentSnapshot)

            assertEquals(0, diff.totalChanges,
                "Filesystem should match latest snapshot immediately after execution. " +
                "Diff: ${diff.summary()}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot consistency check detects drift after external modification`() = runBlocking {
        val root = Files.createTempDirectory("qorche-drift-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            orchestrator.runTask(
                taskId = "drift-task",
                instruction = "create output",
                runner = runner
            ).getOrThrow()

            // Simulate external modification (someone edits a file after qorche ran)
            root.resolve("src/main.kt").writeText("fun main() { println(\"modified externally\") }")

            // Consistency check should detect the drift
            val snapshots = orchestrator.history()
            val latest = snapshots.first()
            val currentSnapshot = SnapshotCreator.create(root, "drift-check")
            val diff = SnapshotCreator.diff(latest, currentSnapshot)

            assertTrue(diff.totalChanges > 0,
                "Should detect external modification as drift")
            assertTrue(diff.modified.any { it.contains("main.kt") },
                "Should identify main.kt as modified")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WAL captures multi-task graph lifecycle`() = runBlocking {
        val root = Files.createTempDirectory("qorche-replay-graph-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/output.txt"), delayMs = 50)

            val task1 = TaskDefinition(id = "task-a", instruction = "first task")
            val task2 = TaskDefinition(id = "task-b", instruction = "second task", dependsOn = listOf("task-a"))
            val graph = TaskGraph(listOf(task1, task2))

            orchestrator.runGraphParallel(
                project = "replay-graph",
                graph = graph,
                runner = runner
            )

            val entries = orchestrator.walEntries()

            // Both tasks should have start + completed entries
            val starts = entries.filterIsInstance<WALEntry.TaskStarted>()
            val completes = entries.filterIsInstance<WALEntry.TaskCompleted>()

            assertEquals(2, starts.size, "Both tasks should have started")
            assertEquals(2, completes.size, "Both tasks should have completed")

            // Verify ordering: task-a started before task-b
            val startOrder = starts.map { it.taskId }
            assertEquals("task-a", startOrder[0])
            assertEquals("task-b", startOrder[1])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WAL captures failed task with error message`() = runBlocking {
        val root = Files.createTempDirectory("qorche-replay-fail-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(shouldFail = true, failMessage = "intentional failure", delayMs = 50)

            val result = orchestrator.runTask(
                taskId = "fail-task",
                instruction = "will fail",
                runner = runner
            )

            // Task should have completed (runner returns exit code 1, not exception)
            assertTrue(result.isSuccess, "Runner failure is exit code 1, not exception")
            assertEquals(1, result.getOrThrow().agentResult.exitCode)

            val entries = orchestrator.walEntries()
            val failed = entries.filterIsInstance<WALEntry.TaskFailed>()
            assertEquals(1, failed.size, "Should log TaskFailed for non-zero exit")
            assertTrue(failed[0].error.contains("exit"), "Error should mention exit code")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
