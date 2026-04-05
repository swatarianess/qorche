package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskProject
import io.qorche.core.TaskYamlParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InitCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
) : CliktCommand(name = "init") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Initialize a new Qorche project in the current directory"

    private val force by option("--force", help = "Overwrite existing tasks.yaml").flag()

    override fun run() {
        val workDir = workDirProvider()
        val projectType = detectProjectType(workDir)

        val qorcheDir = workDir.resolve(".qorche")
        if (!Files.exists(qorcheDir)) {
            Files.createDirectories(qorcheDir)
            echo("Created .qorche/")
        }

        val tasksFile = workDir.resolve("tasks.yaml")
        if (Files.exists(tasksFile) && !force) {
            echo("${Terminal.yellow("Skipped:")} tasks.yaml already exists (use --force to overwrite)")
        } else {
            val yaml = generateTasksYaml(projectType, workDir)
            Files.writeString(tasksFile, yaml)
            echo("Created tasks.yaml (${projectType.label} project)")
        }

        val qorignoreFile = workDir.resolve(".qorignore")
        if (!Files.exists(qorignoreFile)) {
            val ignoreContent = generateQorignore(projectType)
            Files.writeString(qorignoreFile, ignoreContent)
            echo("Created .qorignore")
        }

        val gitDir = workDir.resolve(".git")
        if (Files.isDirectory(gitDir)) {
            val gitignore = workDir.resolve(".gitignore")
            val alreadyIgnored = if (Files.exists(gitignore)) {
                Files.readString(gitignore).lines().any { it.trim() == ".qorche/" || it.trim() == ".qorche" }
            } else {
                false
            }
            if (!alreadyIgnored) {
                val entry = if (Files.exists(gitignore)) "\n.qorche/\n" else ".qorche/\n"
                Files.writeString(gitignore, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                echo("Added .qorche/ to .gitignore")
            }
        }

        echo("")
        echo("Next steps:")
        echo("  qorche validate tasks.yaml   # Check your task file")
        echo("  qorche plan tasks.yaml       # Preview execution plan")
        echo("  qorche run tasks.yaml        # Execute tasks")
    }
}

// --- Project type detection ---

enum class ProjectType(val label: String) {
    GRADLE_KOTLIN("Kotlin/Gradle"),
    GRADLE_JAVA("Java/Gradle"),
    MAVEN("Maven"),
    NODE("Node.js"),
    PYTHON("Python"),
    RUST("Rust"),
    GO("Go"),
    GENERIC("Generic")
}

internal fun detectProjectType(workDir: Path): ProjectType = when {
    Files.exists(workDir.resolve("build.gradle.kts")) -> ProjectType.GRADLE_KOTLIN
    Files.exists(workDir.resolve("build.gradle")) -> ProjectType.GRADLE_JAVA
    Files.exists(workDir.resolve("pom.xml")) -> ProjectType.MAVEN
    Files.exists(workDir.resolve("package.json")) -> ProjectType.NODE
    Files.exists(workDir.resolve("pyproject.toml")) ||
        Files.exists(workDir.resolve("setup.py")) -> ProjectType.PYTHON
    Files.exists(workDir.resolve("Cargo.toml")) -> ProjectType.RUST
    Files.exists(workDir.resolve("go.mod")) -> ProjectType.GO
    else -> ProjectType.GENERIC
}

// --- Task YAML generation via serialization ---

/**
 * DSL builder for creating task definitions concisely.
 *
 * Usage:
 * ```kotlin
 * val project = taskProject("my-project") {
 *     task("lint", "Run linter") { files("src/") }
 *     task("test", "Run tests") { files("src/", "test/") }
 *     task("build", "Build project") { dependsOn("lint", "test") }
 * }
 * ```
 */
class TaskProjectBuilder(private val projectName: String) {
    private val tasks = mutableListOf<TaskDefinition>()

    fun task(id: String, instruction: String, configure: TaskBuilder.() -> Unit = {}) {
        val builder = TaskBuilder(id, instruction)
        builder.configure()
        tasks.add(builder.build())
    }

    fun build(): TaskProject = TaskProject(project = projectName, tasks = tasks)
}

class TaskBuilder(private val id: String, private val instruction: String) {
    private val dependsOn = mutableListOf<String>()
    private val files = mutableListOf<String>()

    fun dependsOn(vararg ids: String) { dependsOn.addAll(ids) }
    fun files(vararg paths: String) { files.addAll(paths) }

    fun build(): TaskDefinition = TaskDefinition(
        id = id,
        instruction = instruction,
        dependsOn = dependsOn.toList(),
        files = files.toList()
    )
}

internal fun taskProject(name: String, configure: TaskProjectBuilder.() -> Unit): TaskProject {
    val builder = TaskProjectBuilder(name)
    builder.configure()
    return builder.build()
}

internal fun generateTasksYaml(type: ProjectType, workDir: Path): String {
    val projectName = workDir.fileName?.toString() ?: "my-project"
    val project = buildProjectForType(type, projectName)
    return TaskYamlParser.encode(project)
}

private fun buildProjectForType(type: ProjectType, name: String): TaskProject = when (type) {
    ProjectType.GRADLE_KOTLIN, ProjectType.GRADLE_JAVA -> taskProject(name) {
        task("lint", "Run linter and fix style issues") { files("src/") }
        task("test", "Run the test suite") { files("src/", "test/") }
        task("build", "Build the project") { dependsOn("lint", "test") }
    }
    ProjectType.MAVEN -> taskProject(name) {
        task("lint", "Run linter and fix style issues") { files("src/") }
        task("test", "Run the test suite") { files("src/") }
        task("package", "Package the application") { dependsOn("lint", "test") }
    }
    ProjectType.NODE -> taskProject(name) {
        task("lint", "Run linter and fix issues") { files("src/") }
        task("test", "Run the test suite") { files("src/", "test/") }
        task("build", "Build the project") { dependsOn("lint", "test"); files("dist/") }
    }
    ProjectType.PYTHON -> taskProject(name) {
        task("lint", "Run linter and fix style issues") { files("src/") }
        task("test", "Run the test suite") { files("src/", "tests/") }
    }
    ProjectType.RUST -> taskProject(name) {
        task("lint", "Run clippy and fix warnings") { files("src/") }
        task("test", "Run the test suite") { files("src/") }
        task("build", "Build the project") { dependsOn("lint", "test") }
    }
    ProjectType.GO -> taskProject(name) {
        task("lint", "Run go vet and staticcheck") { files(".") }
        task("test", "Run the test suite") { files(".") }
    }
    ProjectType.GENERIC -> taskProject(name) {
        task("task-a", "First task") { files("src/") }
        task("task-b", "Second task") { files("src/") }
        task("finalize", "Final task") { dependsOn("task-a", "task-b") }
    }
}

// --- .qorignore generation ---

private data class IgnoreSection(val comment: String, val patterns: List<String>)

private val baseIgnoreHeader = listOf(
    "# Qorche ignore patterns",
    "# Each line is a path prefix to exclude from snapshots",
    "# Lines starting with # are comments",
    "# Use !reset as the first line to clear default patterns"
)

private val projectIgnorePatterns: Map<ProjectType, IgnoreSection> = mapOf(
    ProjectType.GRADLE_KOTLIN to IgnoreSection("Gradle/JVM extras", listOf(".gradle/", "build/", ".kotlin/", "*.class")),
    ProjectType.GRADLE_JAVA to IgnoreSection("Gradle/JVM extras", listOf(".gradle/", "build/", ".kotlin/", "*.class")),
    ProjectType.MAVEN to IgnoreSection("Maven extras", listOf("target/", "*.class")),
    ProjectType.NODE to IgnoreSection("Node extras", listOf("node_modules/", ".next/", ".nuxt/", "coverage/")),
    ProjectType.PYTHON to IgnoreSection(
        "Python extras",
        listOf(".venv/", "venv/", "__pycache__/", "*.pyc", ".mypy_cache/", ".ruff_cache/")
    ),
    ProjectType.RUST to IgnoreSection("Rust extras", listOf("target/")),
    ProjectType.GO to IgnoreSection("Go extras", listOf("vendor/"))
)

internal fun generateQorignore(type: ProjectType): String = buildString {
    for (line in baseIgnoreHeader) appendLine(line)
    appendLine()
    val section = projectIgnorePatterns[type]
    if (section != null) {
        appendLine("# ${section.comment}")
        for (pattern in section.patterns) appendLine(pattern)
    }
}
