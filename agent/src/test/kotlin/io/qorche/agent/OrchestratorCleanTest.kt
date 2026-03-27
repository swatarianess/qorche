package io.qorche.agent

import io.qorche.core.Orchestrator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchestratorCleanTest {

    @Test
    fun `clean all removes snapshots, logs, wal, and cache`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)

            orchestrator.runTask("t1", "first", runner).getOrThrow()
            orchestrator.runTask("t2", "second", runner).getOrThrow()

            // Verify data exists before clean
            val qorcheDir = root.resolve(".qorche")
            assertTrue(qorcheDir.resolve("snapshots").listDirectoryEntries("*.json").isNotEmpty())
            assertTrue(qorcheDir.resolve("wal.jsonl").readText().isNotBlank())
            assertTrue(qorcheDir.resolve("file-index.json").exists())

            val result = orchestrator.clean()

            assertEquals(4, result.snapshotsRemoved, "Should remove 4 snapshots (2 before + 2 after)")
            assertEquals(0, result.snapshotsKept)
            assertTrue(result.walCleared)
            assertTrue(result.fileIndexCleared)

            // Verify data is gone
            assertTrue(qorcheDir.resolve("snapshots").listDirectoryEntries("*.json").isEmpty())
            assertEquals("", qorcheDir.resolve("wal.jsonl").readText())
            assertFalse(qorcheDir.resolve("file-index.json").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clean with keepLast retains recent snapshots`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)

            orchestrator.runTask("t1", "first", runner).getOrThrow()
            orchestrator.runTask("t2", "second", runner).getOrThrow()

            val snapshotsBefore = root.resolve(".qorche/snapshots").listDirectoryEntries("*.json").size
            assertTrue(snapshotsBefore >= 4, "Should have at least 4 snapshots")

            val result = orchestrator.clean(keepLastSnapshots = 2)

            assertEquals(2, result.snapshotsKept)
            assertEquals(snapshotsBefore - 2, result.snapshotsRemoved)

            val snapshotsAfter = root.resolve(".qorche/snapshots").listDirectoryEntries("*.json").size
            assertEquals(2, snapshotsAfter)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `selective clean only removes specified data`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)
            orchestrator.runTask("t1", "task", runner).getOrThrow()

            val result = orchestrator.clean(
                snapshots = false,
                logs = true,
                wal = false,
                fileIndexCache = false
            )

            // Only logs should be cleaned
            assertEquals(0, result.snapshotsRemoved)
            assertFalse(result.walCleared)
            assertFalse(result.fileIndexCleared)

            // Snapshots and WAL should still exist
            val qorcheDir = root.resolve(".qorche")
            assertTrue(qorcheDir.resolve("snapshots").listDirectoryEntries("*.json").isNotEmpty())
            assertTrue(qorcheDir.resolve("wal.jsonl").readText().isNotBlank())
            assertTrue(qorcheDir.resolve("file-index.json").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clean on empty qorche dir returns zero counts`() {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            val orchestrator = Orchestrator(root)
            val result = orchestrator.clean()

            assertEquals(0, result.snapshotsRemoved)
            assertEquals(0, result.logsRemoved)
            assertFalse(result.walCleared)
            assertFalse(result.fileIndexCleared)
            assertEquals(0, result.totalRemoved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clean snapshots only leaves wal and cache intact`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)
            orchestrator.runTask("t1", "task", runner).getOrThrow()

            val result = orchestrator.clean(
                snapshots = true,
                logs = false,
                wal = false,
                fileIndexCache = false
            )

            assertTrue(result.snapshotsRemoved > 0)
            assertFalse(result.walCleared)
            assertFalse(result.fileIndexCleared)

            // WAL and cache should still exist
            val qorcheDir = root.resolve(".qorche")
            assertTrue(qorcheDir.resolve("wal.jsonl").readText().isNotBlank())
            assertTrue(qorcheDir.resolve("file-index.json").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keepLast larger than snapshot count removes nothing`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)
            orchestrator.runTask("t1", "task", runner).getOrThrow()

            val snapshotsBefore = root.resolve(".qorche/snapshots").listDirectoryEntries("*.json").size

            val result = orchestrator.clean(
                snapshots = true,
                logs = false,
                wal = false,
                fileIndexCache = false,
                keepLastSnapshots = 100
            )

            assertEquals(0, result.snapshotsRemoved)
            assertEquals(snapshotsBefore, result.snapshotsKept)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `orchestrator still works after clean`() = runBlocking {
        val root = Files.createTempDirectory("qorche-clean-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = MockAgentRunner(filesToTouch = listOf("src/out.txt"), delayMs = 10)

            orchestrator.runTask("t1", "first", runner).getOrThrow()
            orchestrator.clean()

            // Should work fine after clean
            val result = orchestrator.runTask("t2", "second", runner).getOrThrow()
            assertEquals(0, result.agentResult.exitCode)

            // New data should be present
            assertTrue(orchestrator.history().isNotEmpty())
            assertTrue(orchestrator.walEntries().isNotEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
