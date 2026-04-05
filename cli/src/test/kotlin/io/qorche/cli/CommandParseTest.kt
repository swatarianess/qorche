package io.qorche.cli

import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.qorche.core.Orchestrator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * CLI command tests using Clikt's test() helper and parse() method.
 *
 * test() captures echo output and returns a CliktCommandTestResult.
 * parse() throws exceptions instead of calling exitProcess(), making it
 * safe for unit testing.
 */
@Tag("smoke")
class CommandParseTest {

    // --- Helper to run command in a temp working directory ---

    private fun <T> withWorkDir(workDir: String, block: () -> T): T {
        val origDir = System.getProperty("user.dir")
        System.setProperty("user.dir", workDir)
        return try {
            block()
        } finally {
            System.setProperty("user.dir", origDir)
        }
    }

    private fun <T> withTempDir(block: (java.nio.file.Path) -> T): T {
        val root = Files.createTempDirectory("qorche-cmd-test")
        return try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- ReplayCommand ---

    @Test
    fun `replay with no WAL shows empty message`() = withTempDir { root ->
        val result = withWorkDir(root.toString()) {
            ReplayCommand().test(emptyList())
        }
        assertTrue(result.output.contains("No WAL entries found"), "Should indicate empty WAL: ${result.output}")
    }

    @Test
    fun `replay shows task entries after execution`() = withTempDir { root ->
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")

        val orchestrator = Orchestrator(root)
        val runner = io.qorche.agent.MockAgentRunner(
            filesToTouch = listOf("src/output.txt"), delayMs = 10
        )
        runBlocking { orchestrator.runTask("test-task", "create output", runner) }

        val result = withWorkDir(root.toString()) {
            ReplayCommand().test(emptyList())
        }
        assertTrue(result.output.contains("test-task"), "Should show task ID: ${result.output}")
        assertTrue(result.output.contains("Started"), "Should show started entry: ${result.output}")
        assertTrue(
            result.output.contains("Completed") || result.output.contains("Failed"),
            "Should show completion: ${result.output}"
        )
        assertTrue(result.output.contains("Summary:"), "Should show summary: ${result.output}")
    }

    @Test
    fun `replay --check detects consistent state`() = withTempDir { root ->
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")

        val orchestrator = Orchestrator(root)
        val runner = io.qorche.agent.MockAgentRunner(
            filesToTouch = listOf("src/output.txt"), delayMs = 10
        )
        runBlocking { orchestrator.runTask("test-task", "create output", runner) }

        val result = withWorkDir(root.toString()) {
            ReplayCommand().test(listOf("--check"))
        }
        assertTrue(result.output.contains("CONSISTENT"), "Should show consistent: ${result.output}")
    }

    @Test
    fun `replay --check detects diverged state`() = withTempDir { root ->
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")

        val orchestrator = Orchestrator(root)
        val runner = io.qorche.agent.MockAgentRunner(
            filesToTouch = listOf("src/output.txt"), delayMs = 10
        )
        runBlocking { orchestrator.runTask("test-task", "create output", runner) }

        // Modify after snapshot
        root.resolve("src/extra.kt").writeText("val x = 1")

        val result = withWorkDir(root.toString()) {
            ReplayCommand().test(listOf("--check"))
        }
        assertTrue(result.output.contains("DIVERGED"), "Should show diverged: ${result.output}")
        assertEquals(1, result.statusCode, "Should exit with TASK_FAILURE")
    }

    // --- VerifyCommand ---

    @Test
    fun `verify with missing file argument throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            VerifyCommand().parse(emptyList())
        }
    }

    @Test
    fun `verify with no verify section in YAML exits with error`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: test
            tasks:
              - id: task1
                instruction: do thing
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            VerifyCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("No 'verify' section"), "Should indicate missing verify: ${result.output}")
        assertEquals(2, result.statusCode, "Should exit with CONFIG_ERROR")
    }

    @Test
    fun `verify runs command and reports success`() = withTempDir { root ->
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val echoCmd = if (isWindows) "cmd /c echo ok" else "echo ok"

        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: test
            verify:
              command: "$echoCmd"
              timeout_seconds: 30
            tasks:
              - id: task1
                instruction: do thing
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            VerifyCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("PASSED"), "Should show passed: ${result.output}")
        assertEquals(0, result.statusCode, "Should exit with SUCCESS")
    }

    @Test
    fun `verify with failing command exits with error`() = withTempDir { root ->
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val failCmd = if (isWindows) "cmd /c exit 1" else "false"

        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: test
            verify:
              command: "$failCmd"
              timeout_seconds: 30
            tasks:
              - id: task1
                instruction: do thing
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            VerifyCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("FAILED"), "Should show failed: ${result.output}")
        assertEquals(1, result.statusCode, "Should exit with TASK_FAILURE")
    }

    // --- PlanCommand ---

    @Test
    fun `plan with missing file argument throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            PlanCommand().parse(emptyList())
        }
    }

    @Test
    fun `plan shows execution order for valid YAML`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: test-plan
            tasks:
              - id: explore
                instruction: explore
              - id: build
                instruction: build
                depends_on: [explore]
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            PlanCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("test-plan"), "Should show project name: ${result.output}")
        assertTrue(result.output.contains("explore"), "Should show first task: ${result.output}")
        assertTrue(result.output.contains("build"), "Should show second task: ${result.output}")
        assertTrue(result.output.contains("Execution order"), "Should show order header: ${result.output}")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `plan --output json produces valid JSON`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: json-plan
            tasks:
              - id: a
                instruction: task a
              - id: b
                instruction: task b
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            PlanCommand().test(listOf("--output", "json", yamlFile.toString()))
        }
        assertTrue(result.output.contains("\"project\""), "Should be JSON: ${result.output}")
        assertTrue(result.output.contains("json-plan"), "Should have project name: ${result.output}")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `plan with invalid YAML exits with CONFIG_ERROR`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("not valid yaml [[[")

        val result = withWorkDir(root.toString()) {
            PlanCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("Error:"), "Should show error: ${result.output}")
        assertEquals(2, result.statusCode, "Should exit with CONFIG_ERROR")
    }

    // --- ValidateCommand ---

    @Test
    fun `validate with missing file argument throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            ValidateCommand().parse(emptyList())
        }
    }

    @Test
    fun `validate valid YAML shows success`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: valid-test
            tasks:
              - id: a
                instruction: task a
              - id: b
                instruction: task b
                depends_on: [a]
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            ValidateCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("Valid"), "Should show valid: ${result.output}")
        assertTrue(result.output.contains("2 tasks"), "Should show task count: ${result.output}")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `validate with cycle exits with CONFIG_ERROR`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: cycle-test
            tasks:
              - id: a
                instruction: task a
                depends_on: [b]
              - id: b
                instruction: task b
                depends_on: [a]
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            ValidateCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("Invalid"), "Should show invalid: ${result.output}")
        assertEquals(2, result.statusCode, "Should exit with CONFIG_ERROR")
    }

    @Test
    fun `validate detects scope overlaps as warnings`() = withTempDir { root ->
        val yamlFile = root.resolve("tasks.yaml")
        yamlFile.writeText("""
            project: overlap
            tasks:
              - id: a
                instruction: task a
                files: [src/shared.kt]
              - id: b
                instruction: task b
                files: [src/shared.kt]
        """.trimIndent())

        val result = withWorkDir(root.toString()) {
            ValidateCommand().test(listOf(yamlFile.toString()))
        }
        assertTrue(result.output.contains("Valid"), "Should still be valid: ${result.output}")
        assertTrue(result.output.contains("Warning"), "Should show overlap warning: ${result.output}")
        assertTrue(result.output.contains("shared.kt"), "Should name overlapping file: ${result.output}")
        assertEquals(0, result.statusCode)
    }

    // --- SchemaCommand ---

    @Test
    fun `schema outputs valid JSON schema`() {
        val result = SchemaCommand().test(emptyList())
        assertTrue(result.output.contains("\"\$schema\""), "Should contain schema key: ${result.output}")
        assertTrue(result.output.contains("qorche"), "Should reference qorche: ${result.output}")
        assertTrue(result.output.contains("verify"), "Should include verify config: ${result.output}")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `schema --output writes to file`() = withTempDir { root ->
        val outFile = root.resolve("schema.json")
        withWorkDir(root.toString()) {
            SchemaCommand().test(listOf("--output", outFile.toString()))
        }
        assertTrue(Files.exists(outFile), "Should create file")
        val content = outFile.toFile().readText()
        assertTrue(content.contains("\"\$schema\""), "File should contain schema")
    }

    // --- InitCommand ---

    @Test
    fun `init creates qorche directory and tasks yaml`() = withTempDir { root ->
        val result = withWorkDir(root.toString()) {
            InitCommand().test(emptyList())
        }
        assertTrue(result.output.contains("Created .qorche/"), "Should create .qorche/: ${result.output}")
        assertTrue(result.output.contains("Created tasks.yaml"), "Should create tasks.yaml: ${result.output}")
        assertTrue(Files.exists(root.resolve(".qorche")), ".qorche/ should exist")
        assertTrue(Files.exists(root.resolve("tasks.yaml")), "tasks.yaml should exist")
        assertTrue(Files.exists(root.resolve(".qorignore")), ".qorignore should exist")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `init skips existing tasks yaml without force`() = withTempDir { root ->
        root.resolve("tasks.yaml").writeText("existing content")

        val result = withWorkDir(root.toString()) {
            InitCommand().test(emptyList())
        }
        assertTrue(result.output.contains("Skipped"), "Should skip: ${result.output}")
        assertEquals("existing content", root.resolve("tasks.yaml").toFile().readText())
    }

    @Test
    fun `init --force overwrites existing tasks yaml`() = withTempDir { root ->
        root.resolve("tasks.yaml").writeText("old content")

        val result = withWorkDir(root.toString()) {
            InitCommand().test(listOf("--force"))
        }
        assertTrue(result.output.contains("Created tasks.yaml"), "Should overwrite: ${result.output}")
        val content = root.resolve("tasks.yaml").toFile().readText()
        assertTrue(content.contains("project:"), "Should have generated YAML content")
    }

    @Test
    fun `init detects kotlin gradle project`() = withTempDir { root ->
        root.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")

        val result = withWorkDir(root.toString()) {
            InitCommand().test(emptyList())
        }
        assertTrue(result.output.contains("Kotlin/Gradle"), "Should detect Kotlin/Gradle: ${result.output}")
    }

    // --- StatusCommand ---

    @Test
    fun `status with no qorche directory shows message`() = withTempDir { root ->
        val result = withWorkDir(root.toString()) {
            StatusCommand().test(emptyList())
        }
        assertTrue(result.output.contains("No .qorche/ directory"), "Should indicate no workspace: ${result.output}")
    }

    @Test
    fun `status shows workspace info after task run`() = withTempDir { root ->
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")

        val orchestrator = Orchestrator(root)
        val runner = io.qorche.agent.MockAgentRunner(
            filesToTouch = listOf("src/output.txt"), delayMs = 10
        )
        runBlocking { orchestrator.runTask("test-task", "create output", runner) }

        val result = withWorkDir(root.toString()) {
            StatusCommand().test(emptyList())
        }
        assertTrue(result.output.contains("Snapshots:"), "Should show snapshots: ${result.output}")
        assertTrue(result.output.contains("WAL entries:"), "Should show WAL entries: ${result.output}")
    }

    // --- HistoryCommand ---

    @Test
    fun `history with no snapshots shows message`() = withTempDir { root ->
        root.resolve(".qorche").createDirectories()

        val result = withWorkDir(root.toString()) {
            HistoryCommand().test(emptyList())
        }
        assertTrue(result.output.contains("No snapshots found"), "Should indicate no snapshots: ${result.output}")
    }

    @Test
    fun `history shows snapshots after task run`() = withTempDir { root ->
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")

        val orchestrator = Orchestrator(root)
        val runner = io.qorche.agent.MockAgentRunner(
            filesToTouch = listOf("src/output.txt"), delayMs = 10
        )
        runBlocking { orchestrator.runTask("test-task", "create output", runner) }

        val result = withWorkDir(root.toString()) {
            HistoryCommand().test(emptyList())
        }
        assertTrue(result.output.contains("files)"), "Should show file count: ${result.output}")
    }

    // --- LogsCommand ---

    @Test
    fun `logs with no logs directory shows message`() = withTempDir { root ->
        val result = withWorkDir(root.toString()) {
            LogsCommand().test(emptyList())
        }
        assertTrue(result.output.contains("No logs found"), "Should indicate no logs: ${result.output}")
    }

    @Test
    fun `logs lists log files when present`() = withTempDir { root ->
        root.resolve(".qorche/logs").createDirectories()
        root.resolve(".qorche/logs/test-task.log").writeText("Starting: do thing\nCompleted\n")

        val result = withWorkDir(root.toString()) {
            LogsCommand().test(emptyList())
        }
        assertTrue(result.output.contains("test-task"), "Should list task log: ${result.output}")
    }

    @Test
    fun `logs with specific task ID shows log content`() = withTempDir { root ->
        root.resolve(".qorche/logs").createDirectories()
        root.resolve(".qorche/logs/test-task.log").writeText("Starting: do thing\nCompleted\n")

        val result = withWorkDir(root.toString()) {
            LogsCommand().test(listOf("test-task"))
        }
        assertTrue(result.output.contains("Starting: do thing"), "Should show log content: ${result.output}")
    }

    @Test
    fun `logs with unknown task ID shows error`() = withTempDir { root ->
        root.resolve(".qorche/logs").createDirectories()

        val result = withWorkDir(root.toString()) {
            LogsCommand().test(listOf("nonexistent"))
        }
        assertTrue(result.output.contains("No log found"), "Should show not found: ${result.output}")
    }

    // --- CleanCommand ---

    @Test
    fun `clean with nothing to clean shows message`() = withTempDir { root ->
        root.resolve(".qorche").createDirectories()

        val result = withWorkDir(root.toString()) {
            CleanCommand().test(emptyList())
        }
        assertTrue(result.output.contains("Nothing to clean"), "Should show nothing: ${result.output}")
    }

    @Test
    fun `clean --wal clears write-ahead log`() = withTempDir { root ->
        root.resolve(".qorche").createDirectories()
        root.resolve(".qorche/wal.jsonl").writeText("{\"type\":\"test\"}\n")

        val result = withWorkDir(root.toString()) {
            CleanCommand().test(listOf("--wal"))
        }
        assertTrue(result.output.contains("Cleared") && result.output.contains("write-ahead log"),
            "Should confirm WAL cleared: ${result.output}")
    }

    // --- DiffCommand ---

    @Test
    fun `diff with missing arguments throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            DiffCommand().parse(emptyList())
        }
    }

    @Test
    fun `diff with invalid snapshot IDs shows error`() = withTempDir { root ->
        root.resolve(".qorche").createDirectories()

        val result = withWorkDir(root.toString()) {
            DiffCommand().test(listOf("abc", "def"))
        }
        assertTrue(result.output.contains("not found"), "Should show not found: ${result.output}")
    }

    // --- VersionCommand ---

    @Test
    fun `version outputs version string`() {
        val result = VersionCommand().test(emptyList())
        assertTrue(result.output.contains("qorche"), "Should contain qorche: ${result.output}")
        assertEquals(0, result.statusCode)
    }
}
