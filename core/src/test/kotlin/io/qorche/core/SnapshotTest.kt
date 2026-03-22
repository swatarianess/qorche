package io.qorche.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnapshotTest {

    @Test
    fun `hash is consistent for same content`() {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("a.txt").writeText("hello world\n")

            val hash1 = hashFile(root.resolve("a.txt"))
            val hash2 = hashFile(root.resolve("a.txt"))
            assertEquals(hash1, hash2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hash differs for different content`() {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("a.txt").writeText("hello\n")
            root.resolve("b.txt").writeText("world\n")

            assertNotEquals(hashFile(root.resolve("a.txt")), hashFile(root.resolve("b.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `line ending normalisation produces same hash`() {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("unix.txt").writeText("line1\nline2\n")
            root.resolve("windows.txt").writeText("line1\r\nline2\r\n")

            assertEquals(
                hashFile(root.resolve("unix.txt")),
                hashFile(root.resolve("windows.txt")),
                "Unix and Windows line endings should produce the same hash"
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot captures all files`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/a.txt").writeText("a\n")
            root.resolve("src/b.txt").writeText("b\n")
            root.resolve("c.txt").writeText("c\n")

            val snapshot = SnapshotCreator.create(root, "test")

            assertEquals(3, snapshot.fileHashes.size)
            assertTrue("src/a.txt" in snapshot.fileHashes)
            assertTrue("src/b.txt" in snapshot.fileHashes)
            assertTrue("c.txt" in snapshot.fileHashes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot ignores git and build directories`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve(".git").createDirectories()
            root.resolve(".git/config").writeText("git stuff")
            root.resolve("build").createDirectories()
            root.resolve("build/output.jar").writeText("jar content")
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val snapshot = SnapshotCreator.create(root, "test")

            assertEquals(1, snapshot.fileHashes.size)
            assertTrue("src/main.kt" in snapshot.fileHashes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paths use forward slashes`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/nested").createDirectories()
            root.resolve("src/nested/file.txt").writeText("content")

            val snapshot = SnapshotCreator.create(root, "test")

            assertTrue(snapshot.fileHashes.keys.all { "/" in it || !it.contains("\\") })
            assertTrue("src/nested/file.txt" in snapshot.fileHashes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diff detects added modified and deleted files`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("keep.txt").writeText("unchanged\n")
            root.resolve("modify.txt").writeText("original\n")
            root.resolve("delete.txt").writeText("will be deleted\n")

            val before = SnapshotCreator.create(root, "before")

            root.resolve("modify.txt").writeText("modified\n")
            Files.delete(root.resolve("delete.txt"))
            root.resolve("added.txt").writeText("new file\n")

            val after = SnapshotCreator.create(root, "after")
            val diff = SnapshotCreator.diff(before, after)

            assertEquals(setOf("added.txt"), diff.added)
            assertEquals(setOf("modify.txt"), diff.modified)
            assertEquals(setOf("delete.txt"), diff.deleted)
            assertEquals("+1 added, ~1 modified, -1 deleted", diff.summary())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scoped snapshot only hashes specified paths`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/a.txt").writeText("a\n")
            root.resolve("src/b.txt").writeText("b\n")
            root.resolve("docs").createDirectories()
            root.resolve("docs/readme.txt").writeText("readme\n")
            root.resolve("other.txt").writeText("other\n")

            val scoped = SnapshotCreator.createScoped(root, listOf("src"), "scoped")

            assertEquals(2, scoped.fileHashes.size)
            assertTrue("src/a.txt" in scoped.fileHashes)
            assertTrue("src/b.txt" in scoped.fileHashes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scoped snapshot with individual files`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("a.txt").writeText("a\n")
            root.resolve("b.txt").writeText("b\n")
            root.resolve("c.txt").writeText("c\n")

            val scoped = SnapshotCreator.createScoped(root, listOf("a.txt", "c.txt"), "scoped")

            assertEquals(2, scoped.fileHashes.size)
            assertTrue("a.txt" in scoped.fileHashes)
            assertTrue("c.txt" in scoped.fileHashes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diff summary with no changes`() = runBlocking {
        val root = Files.createTempDirectory("qorche-snap-test")
        try {
            root.resolve("a.txt").writeText("unchanged\n")

            val snap1 = SnapshotCreator.create(root, "first")
            val snap2 = SnapshotCreator.create(root, "second")
            val diff = SnapshotCreator.diff(snap1, snap2)

            assertEquals(0, diff.totalChanges)
            assertEquals("no changes", diff.summary())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
