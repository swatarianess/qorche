package io.qorche.core

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnorePatternsTest {

    @AfterTest
    fun reset() {
        SnapshotCreator.resetIgnorePatterns()
    }

    @Test
    fun `default patterns ignore common directories`() {
        assertTrue(SnapshotCreator.isIgnored(".git/config"))
        assertTrue(SnapshotCreator.isIgnored(".gradle/cache/file.bin"))
        assertTrue(SnapshotCreator.isIgnored(".idea/workspace.xml"))
        assertTrue(SnapshotCreator.isIgnored(".qorche/snapshots/abc.json"))
        assertTrue(SnapshotCreator.isIgnored("build/classes/Main.class"))
        assertTrue(SnapshotCreator.isIgnored("node_modules/express/index.js"))
        assertTrue(SnapshotCreator.isIgnored(".venv/lib/python3.12/site.py"))
        assertTrue(SnapshotCreator.isIgnored("target/debug/binary"))
        assertTrue(SnapshotCreator.isIgnored("dist/bundle.js"))
        assertTrue(SnapshotCreator.isIgnored("__pycache__/module.pyc"))
        assertTrue(SnapshotCreator.isIgnored(".next/cache/data.json"))
        assertTrue(SnapshotCreator.isIgnored(".kotlin/sessions/session.lock"))
        assertTrue(SnapshotCreator.isIgnored(".vscode/settings.json"))
    }

    @Test
    fun `default patterns ignore OS artifacts`() {
        assertTrue(SnapshotCreator.isIgnored(".DS_Store"))
        assertTrue(SnapshotCreator.isIgnored("Thumbs.db"))
    }

    @Test
    fun `default patterns do not ignore source files`() {
        assertFalse(SnapshotCreator.isIgnored("src/main.kt"))
        assertFalse(SnapshotCreator.isIgnored("README.md"))
        assertFalse(SnapshotCreator.isIgnored("package.json"))
        assertFalse(SnapshotCreator.isIgnored("build.gradle.kts"))
        assertFalse(SnapshotCreator.isIgnored("src/components/App.tsx"))
    }

    @Test
    fun `qorignore file adds custom patterns`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            root.resolve(".qorignore").writeText("vendor/\ncustom-cache/\n")

            SnapshotCreator.loadIgnoreFile(root)

            // Custom patterns work
            assertTrue(SnapshotCreator.isIgnored("vendor/lib/something.jar"))
            assertTrue(SnapshotCreator.isIgnored("custom-cache/data.bin"))

            // Defaults still work
            assertTrue(SnapshotCreator.isIgnored("node_modules/express/index.js"))
            assertTrue(SnapshotCreator.isIgnored(".git/config"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `qorignore comments and blank lines are skipped`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            root.resolve(".qorignore").writeText("""
                # This is a comment
                vendor/

                # Another comment
                tmp/
            """.trimIndent())

            SnapshotCreator.loadIgnoreFile(root)

            assertTrue(SnapshotCreator.isIgnored("vendor/lib.jar"))
            assertTrue(SnapshotCreator.isIgnored("tmp/data.bin"))
            assertFalse(SnapshotCreator.isIgnored("# This is a comment"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `qorignore reset clears defaults`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            root.resolve(".qorignore").writeText("""
                !reset
                .qorche/
                custom-only/
            """.trimIndent())

            SnapshotCreator.loadIgnoreFile(root)

            // Custom patterns work
            assertTrue(SnapshotCreator.isIgnored(".qorche/snapshots/abc.json"))
            assertTrue(SnapshotCreator.isIgnored("custom-only/file.txt"))

            // Defaults are gone
            assertFalse(SnapshotCreator.isIgnored("node_modules/express/index.js"))
            assertFalse(SnapshotCreator.isIgnored(".git/config"))
            assertFalse(SnapshotCreator.isIgnored("build/classes/Main.class"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing qorignore file uses defaults`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            SnapshotCreator.loadIgnoreFile(root)

            assertTrue(SnapshotCreator.isIgnored("node_modules/express/index.js"))
            assertTrue(SnapshotCreator.isIgnored(".git/config"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resetIgnorePatterns restores defaults`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            root.resolve(".qorignore").writeText("!reset\ncustom/\n")
            SnapshotCreator.loadIgnoreFile(root)

            assertFalse(SnapshotCreator.isIgnored("node_modules/foo.js"))

            SnapshotCreator.resetIgnorePatterns()

            assertTrue(SnapshotCreator.isIgnored("node_modules/foo.js"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot excludes ignored directories`() {
        val root = Files.createTempDirectory("qorche-ignore-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")
            root.resolve("node_modules/express").createDirectories()
            root.resolve("node_modules/express/index.js").writeText("module.exports = {}")
            root.resolve("build/classes").createDirectories()
            root.resolve("build/classes/Main.class").writeText("bytecode")
            root.resolve("__pycache__").createDirectories()
            root.resolve("__pycache__/module.pyc").writeText("compiled")

            SnapshotCreator.resetIgnorePatterns()

            val snapshot = kotlinx.coroutines.runBlocking {
                SnapshotCreator.create(root, "test")
            }

            assertTrue(snapshot.fileHashes.containsKey("src/main.kt"))
            assertFalse(snapshot.fileHashes.keys.any { it.startsWith("node_modules/") })
            assertFalse(snapshot.fileHashes.keys.any { it.startsWith("build/") })
            assertFalse(snapshot.fileHashes.keys.any { it.startsWith("__pycache__/") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
