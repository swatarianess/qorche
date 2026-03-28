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

    // ── Hash algorithm tests ──────────────────────────────────

    @Test
    fun `crc32c is consistent for same content`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("hello world\n")
            val h1 = hashFile(root.resolve("a.txt"), HashAlgorithm.CRC32C)
            val h2 = hashFile(root.resolve("a.txt"), HashAlgorithm.CRC32C)
            assertEquals(h1, h2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sha256 is consistent for same content`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("hello world\n")
            val h1 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA256)
            val h2 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA256)
            assertEquals(h1, h2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sha1 is consistent for same content`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("hello world\n")
            val h1 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA1)
            val h2 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA1)
            assertEquals(h1, h2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sha1 normalises line endings`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("unix.txt").writeText("line1\nline2\n")
            root.resolve("windows.txt").writeText("line1\r\nline2\r\n")
            assertEquals(
                hashFile(root.resolve("unix.txt"), HashAlgorithm.SHA1),
                hashFile(root.resolve("windows.txt"), HashAlgorithm.SHA1)
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `all algorithms produce different length hashes`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("hello world\n")
            val crc = hashFile(root.resolve("a.txt"), HashAlgorithm.CRC32C)
            val sha1 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA1)
            val sha256 = hashFile(root.resolve("a.txt"), HashAlgorithm.SHA256)
            assertEquals(8, crc.length, "CRC32C should be 8 hex chars")
            assertEquals(40, sha1.length, "SHA-1 should be 40 hex chars")
            assertEquals(64, sha256.length, "SHA-256 should be 64 hex chars")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `crc32c differs for different content`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("hello\n")
            root.resolve("b.txt").writeText("world\n")
            assertNotEquals(
                hashFile(root.resolve("a.txt"), HashAlgorithm.CRC32C),
                hashFile(root.resolve("b.txt"), HashAlgorithm.CRC32C)
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `crc32c normalises line endings`() {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("unix.txt").writeText("line1\nline2\n")
            root.resolve("windows.txt").writeText("line1\r\nline2\r\n")
            assertEquals(
                hashFile(root.resolve("unix.txt"), HashAlgorithm.CRC32C),
                hashFile(root.resolve("windows.txt"), HashAlgorithm.CRC32C)
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot uses configured hash algorithm`() = runBlocking {
        val root = Files.createTempDirectory("qorche-hash-test")
        try {
            root.resolve("a.txt").writeText("content\n")

            val prevAlgo = SnapshotCreator.hashAlgorithm

            SnapshotCreator.hashAlgorithm = HashAlgorithm.CRC32C
            val crcSnap = SnapshotCreator.create(root, "crc")

            SnapshotCreator.hashAlgorithm = HashAlgorithm.SHA1
            val sha1Snap = SnapshotCreator.create(root, "sha1")

            SnapshotCreator.hashAlgorithm = HashAlgorithm.SHA256
            val sha256Snap = SnapshotCreator.create(root, "sha256")

            SnapshotCreator.hashAlgorithm = prevAlgo

            val crcHash = crcSnap.fileHashes["a.txt"]!!
            val sha1Hash = sha1Snap.fileHashes["a.txt"]!!
            val sha256Hash = sha256Snap.fileHashes["a.txt"]!!

            assertEquals(8, crcHash.length)
            assertEquals(40, sha1Hash.length)
            assertEquals(64, sha256Hash.length)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── Preflight check tests ─────────────────────────────────

    @Test
    fun `preflight returns null for small repo`() {
        val root = Files.createTempDirectory("qorche-preflight-test")
        try {
            for (i in 1..100) {
                root.resolve("file$i.txt").writeText("content $i\n")
            }

            val prevAlgo = SnapshotCreator.hashAlgorithm
            SnapshotCreator.hashAlgorithm = HashAlgorithm.SHA1
            val result = SnapshotCreator.preflightCheck(root)
            SnapshotCreator.hashAlgorithm = prevAlgo

            assertEquals(null, result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preflight returns null when already using crc32c`() {
        val root = Files.createTempDirectory("qorche-preflight-test")
        try {
            val dir = root.resolve("src").createDirectories()
            for (i in 1..SnapshotCreator.DEFAULT_LARGE_REPO_THRESHOLD) {
                dir.resolve("file$i.txt").writeText("content $i\n")
            }

            val prevAlgo = SnapshotCreator.hashAlgorithm
            SnapshotCreator.hashAlgorithm = HashAlgorithm.CRC32C
            val result = SnapshotCreator.preflightCheck(root)
            SnapshotCreator.hashAlgorithm = prevAlgo

            assertEquals(null, result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preflight suggests crc32c for large repo with sha1`() {
        val root = Files.createTempDirectory("qorche-preflight-test")
        try {
            val dir = root.resolve("src").createDirectories()
            for (i in 1..SnapshotCreator.DEFAULT_LARGE_REPO_THRESHOLD) {
                dir.resolve("file$i.txt").writeText("content $i\n")
            }

            val prevAlgo = SnapshotCreator.hashAlgorithm
            SnapshotCreator.hashAlgorithm = HashAlgorithm.SHA1
            val result = SnapshotCreator.preflightCheck(root)
            SnapshotCreator.hashAlgorithm = prevAlgo

            requireNotNull(result)
            assertEquals(SnapshotCreator.DEFAULT_LARGE_REPO_THRESHOLD, result.fileCount)
            assertEquals(HashAlgorithm.SHA1, result.currentAlgorithm)
            assertEquals(HashAlgorithm.CRC32C, result.suggestedAlgorithm)
            assertTrue("--hash crc32c" in result.message())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preflight message includes file count and threshold`() {
        val result = PreflightResult(
            fileCount = 12345,
            threshold = 5000,
            currentAlgorithm = HashAlgorithm.SHA256,
            suggestedAlgorithm = HashAlgorithm.CRC32C
        )
        val msg = result.message()
        assertTrue("12345 files in scope" in msg)
        assertTrue("threshold: 5000" in msg)
        assertTrue("--hash crc32c" in msg)
        assertTrue("scope tasks" in msg)
    }

    @Test
    fun `preflight uses custom threshold`() {
        val root = Files.createTempDirectory("qorche-preflight-test")
        try {
            for (i in 1..10) {
                root.resolve("file$i.txt").writeText("content $i\n")
            }

            val prevAlgo = SnapshotCreator.hashAlgorithm
            SnapshotCreator.hashAlgorithm = HashAlgorithm.SHA1

            // Default threshold (5000) — no suggestion
            assertEquals(null, SnapshotCreator.preflightCheck(root))
            // Custom threshold (5) — triggers suggestion
            val result = SnapshotCreator.preflightCheck(root, threshold = 5)
            requireNotNull(result)
            assertEquals(10, result.fileCount)

            SnapshotCreator.hashAlgorithm = prevAlgo
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `countFiles respects ignore patterns`() {
        val root = Files.createTempDirectory("qorche-preflight-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/a.txt").writeText("a\n")
            root.resolve("build").createDirectories()
            root.resolve("build/out.jar").writeText("jar\n")
            root.resolve(".git").createDirectories()
            root.resolve(".git/config").writeText("git\n")

            assertEquals(1, SnapshotCreator.countFiles(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── Progress callback tests ────────────────────────────────

    @Test
    fun `progress callback fires during full snapshot`() = runBlocking {
        val root = Files.createTempDirectory("qorche-progress-test")
        try {
            for (i in 1..10) {
                root.resolve("file$i.txt").writeText("content $i\n")
            }

            val events = mutableListOf<SnapshotProgress>()
            SnapshotCreator.create(root, "test") { progress ->
                events.add(progress)
            }

            val scanning = events.filter { it.phase == "scanning" }
            val hashing = events.filter { it.phase == "hashing" }

            assertEquals(1, scanning.size)
            assertEquals(10, scanning[0].total)
            assertTrue(hashing.isNotEmpty(), "Should have hashing progress events")
            assertEquals(10, hashing.last().total)
            assertEquals(10, hashing.last().current)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `progress callback fires during scoped snapshot`() = runBlocking {
        val root = Files.createTempDirectory("qorche-progress-test")
        try {
            root.resolve("src").createDirectories()
            for (i in 1..5) {
                root.resolve("src/file$i.txt").writeText("content $i\n")
            }
            root.resolve("other.txt").writeText("other\n")

            val events = mutableListOf<SnapshotProgress>()
            SnapshotCreator.createScoped(root, listOf("src"), "test") { progress ->
                events.add(progress)
            }

            val scanning = events.filter { it.phase == "scanning" }
            assertEquals(1, scanning.size)
            assertEquals(5, scanning[0].total)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `progress callback is optional`() = runBlocking {
        val root = Files.createTempDirectory("qorche-progress-test")
        try {
            root.resolve("a.txt").writeText("content\n")
            // Should not throw when no callback provided
            val snapshot = SnapshotCreator.create(root, "test")
            assertEquals(1, snapshot.fileHashes.size)
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
