package io.qorche.core

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileIndexTest {

    @Test
    fun `cache hit on unchanged file`() {
        val root = Files.createTempDirectory("qorche-idx-test")
        try {
            val file = root.resolve("a.txt")
            file.writeText("content\n")

            val index = FileIndex()
            val hash1 = index.getOrComputeHash(file, "a.txt")
            val hash2 = index.getOrComputeHash(file, "a.txt")

            assertEquals(hash1, hash2)
            assertEquals(1, index.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cache miss on modified file`() {
        val root = Files.createTempDirectory("qorche-idx-test")
        try {
            val file = root.resolve("a.txt")
            file.writeText("original\n")

            val index = FileIndex()
            val hash1 = index.getOrComputeHash(file, "a.txt")

            // Ensure mtime changes (some filesystems have 1s resolution)
            Thread.sleep(50)
            file.writeText("modified\n")

            val hash2 = index.getOrComputeHash(file, "a.txt")

            assertTrue(hash1 != hash2, "Hash should change after file modification")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persistence save and load`() {
        val root = Files.createTempDirectory("qorche-idx-test")
        try {
            val file = root.resolve("a.txt")
            file.writeText("content\n")
            val indexPath = root.resolve("file-index.json")

            val index1 = FileIndex()
            index1.getOrComputeHash(file, "a.txt")
            index1.saveTo(indexPath)

            val index2 = FileIndex()
            assertTrue(index2.loadFrom(indexPath))
            assertEquals(1, index2.size)

            // Should get cache hit from loaded data
            val hash = index2.getOrComputeHash(file, "a.txt")
            assertEquals(index1.exportEntries()[0].hash, hash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadFrom returns false for missing file`() {
        val root = Files.createTempDirectory("qorche-idx-test")
        try {
            val index = FileIndex()
            val result = index.loadFrom(root.resolve("nonexistent.json"))
            assertTrue(!result)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
