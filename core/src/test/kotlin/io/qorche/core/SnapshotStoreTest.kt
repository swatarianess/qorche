package io.qorche.core

import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotStoreTest {

    private fun snapshot(id: String, fileHashes: Map<String, String> = emptyMap()) = Snapshot(
        id = id,
        timestamp = Clock.System.now(),
        fileHashes = fileHashes,
        description = id
    )

    @Test
    fun `save and load snapshot round-trips`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            val snap = snapshot("test-id", mapOf("a.kt" to "abc123", "b.kt" to "def456"))

            store.save(snap)
            val loaded = store.load("test-id")

            assertNotNull(loaded)
            assertEquals(snap.id, loaded.id)
            assertEquals(snap.description, loaded.description)
            assertEquals(snap.fileHashes, loaded.fileHashes)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load returns null for missing snapshot`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            assertNull(store.load("nonexistent"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load returns null for corrupted file`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val snapshotsDir = dir.resolve("snapshots")
            val store = SnapshotStore(snapshotsDir)
            snapshotsDir.resolve("corrupt.json").writeText("not valid json{{{")

            assertNull(store.load("corrupt"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `list returns all snapshots sorted by timestamp descending`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            store.save(snapshot("first", mapOf("a.kt" to "h1")))
            store.save(snapshot("second", mapOf("b.kt" to "h2")))

            val listed = store.list()
            assertEquals(2, listed.size)
            assertTrue(listed[0].timestamp >= listed[1].timestamp)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `list skips corrupted files`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val snapshotsDir = dir.resolve("snapshots")
            val store = SnapshotStore(snapshotsDir)
            store.save(snapshot("valid", mapOf("a.kt" to "h1")))
            snapshotsDir.resolve("broken.json").writeText("garbage")

            val listed = store.list()
            assertEquals(1, listed.size)
            assertEquals("valid", listed[0].id)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `list returns empty for nonexistent directory`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            Files.delete(dir.resolve("snapshots"))

            val listed = store.list()
            assertTrue(listed.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `latest returns most recent snapshot`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            store.save(snapshot("old"))
            store.save(snapshot("new"))

            val latest = store.latest()
            assertNotNull(latest)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `latest returns null when empty`() {
        val dir = Files.createTempDirectory("qorche-store-test")
        try {
            val store = SnapshotStore(dir.resolve("snapshots"))
            assertNull(store.latest())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
