package io.qorche.agent

import io.qorche.core.Orchestrator
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
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
            )

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
            )

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

            orchestrator.runTask("t1", "first run", runner)

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
            )

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

            val result = orchestrator.runTask("t1", "modify a", runner)

            val diff = orchestrator.diffSnapshots(result.beforeSnapshot.id, result.afterSnapshot.id)!!
            assertTrue(diff.modified.contains("a.txt") || diff.added.contains("a.txt"),
                "Diff should show a.txt was changed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
