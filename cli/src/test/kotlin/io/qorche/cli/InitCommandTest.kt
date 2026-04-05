package io.qorche.cli

import io.qorche.core.TaskYamlParser
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitCommandTest {

    @Test
    fun `detects Gradle Kotlin project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")
            assertEquals(ProjectType.GRADLE_KOTLIN, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Node project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("package.json").writeText("{}")
            assertEquals(ProjectType.NODE, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Python project via pyproject`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("pyproject.toml").writeText("[project]")
            assertEquals(ProjectType.PYTHON, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Python project via requirements txt`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("requirements.txt").writeText("flask>=2.0")
            assertEquals(ProjectType.PYTHON, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Rust project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("Cargo.toml").writeText("[package]")
            assertEquals(ProjectType.RUST, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Go project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("go.mod").writeText("module example.com/foo")
            assertEquals(ProjectType.GO, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `falls back to generic`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            assertEquals(ProjectType.GENERIC, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generated YAML is parseable for all project types`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            for (type in ProjectType.entries) {
                val yaml = generateTasksYaml(type, root)
                val project = TaskYamlParser.parse(yaml)
                assertTrue(project.tasks.isNotEmpty(), "Generated YAML for ${type.label} should have tasks")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `qorignore has comment header`() {
        for (type in ProjectType.entries) {
            val content = generateQorignore(type)
            assertTrue(content.startsWith("# Qorche ignore patterns"), "Should start with header comment")
        }
    }
}
