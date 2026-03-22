package io.qorche.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WALTest {

    @Test
    fun `append and read back entries`() {
        val dir = Files.createTempDirectory("qorche-wal-test")
        try {
            val wal = WALWriter(dir.resolve("wal.jsonl"))

            wal.append(WALEntry.TaskStarted(
                taskId = "task-1",
                instruction = "do something",
                snapshotId = "snap-1"
            ))
            wal.append(WALEntry.TaskCompleted(
                taskId = "task-1",
                snapshotId = "snap-2",
                exitCode = 0,
                filesModified = listOf("src/a.txt")
            ))
            wal.append(WALEntry.TaskFailed(
                taskId = "task-2",
                error = "something broke"
            ))

            val entries = wal.readAll()
            assertEquals(3, entries.size)

            val started = entries[0] as WALEntry.TaskStarted
            assertEquals("task-1", started.taskId)
            assertEquals("do something", started.instruction)

            val completed = entries[1] as WALEntry.TaskCompleted
            assertEquals(0, completed.exitCode)
            assertEquals(listOf("src/a.txt"), completed.filesModified)

            val failed = entries[2] as WALEntry.TaskFailed
            assertEquals("something broke", failed.error)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readAll on empty file returns empty list`() {
        val dir = Files.createTempDirectory("qorche-wal-test")
        try {
            val wal = WALWriter(dir.resolve("wal.jsonl"))
            assertTrue(wal.readAll().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `entries preserve timestamps`() {
        val dir = Files.createTempDirectory("qorche-wal-test")
        try {
            val wal = WALWriter(dir.resolve("wal.jsonl"))

            wal.append(WALEntry.TaskStarted(
                taskId = "t1",
                instruction = "test",
                snapshotId = "s1"
            ))

            val entries = wal.readAll()
            val entry = entries[0] as WALEntry.TaskStarted
            assertTrue(entry.timestamp.toEpochMilliseconds() > 0)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
