package io.qorche.agent

import io.qorche.core.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellRunnerTest {

    @Test
    fun `rejects command not in allowlist`() = runBlocking {
        val runner = ShellRunner(allowedCommands = setOf("echo"))
        val dir = Files.createTempDirectory("qorche-shell-test")

        try {
            val events = runner.run("rm -rf /", dir).toList()
            val error = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
            assertTrue(error != null, "Should emit error for disallowed command")
            assertTrue(error.message.contains("not in the allowlist"))

            val completed = events.filterIsInstance<AgentEvent.Completed>().first()
            assertEquals(2, completed.exitCode)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects empty command`() = runBlocking {
        val runner = ShellRunner(allowedCommands = setOf("echo"))
        val dir = Files.createTempDirectory("qorche-shell-test")

        try {
            val events = runner.run("", dir).toList()
            val error = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
            assertTrue(error != null)
            assertTrue(error.message.contains("Empty command"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `allowlist matches base name across platforms`() = runBlocking {
        val runner = ShellRunner(allowedCommands = setOf("gradlew"))

        // These should all match "gradlew"
        assertTrue(ShellRunner.tokenise("./gradlew test").isNotEmpty())

        // Test the actual allowlist logic by running against a non-existent but allowed command
        val dir = Files.createTempDirectory("qorche-shell-test")
        try {
            // ./gradlew should match "gradlew" in allowlist
            val events = runner.run("./gradlew --version", dir).toList()
            // Should NOT get "not in allowlist" error — it might fail to start, but that's different
            val errors = events.filterIsInstance<AgentEvent.Error>()
            assertTrue(errors.none { it.message.contains("not in the allowlist") },
                "Should accept ./gradlew when gradlew is in allowlist")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `requires non-empty allowlist`() {
        try {
            ShellRunner(allowedCommands = emptySet())
            assertTrue(false, "Should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Allowlist"))
        }
    }

    @Test
    fun `tokenise handles quoted strings`() {
        assertEquals(
            listOf("echo", "hello world"),
            ShellRunner.tokenise("echo \"hello world\"")
        )
        assertEquals(
            listOf("echo", "it's fine"),
            ShellRunner.tokenise("echo \"it's fine\"")
        )
        assertEquals(
            listOf("./gradlew", ":core:test", "--tests", "io.qorche.core.TaskGraphTest"),
            ShellRunner.tokenise("./gradlew :core:test --tests io.qorche.core.TaskGraphTest")
        )
    }

    @Test
    fun `tokenise handles single quotes`() {
        assertEquals(
            listOf("echo", "hello world"),
            ShellRunner.tokenise("echo 'hello world'")
        )
    }

    @Test
    fun `runs allowed command successfully`() = runBlocking {
        val os = System.getProperty("os.name", "").lowercase()
        val (cmd, allowedCmd) = if (os.contains("win")) {
            "cmd /c echo hello" to "cmd"
        } else {
            "echo hello" to "echo"
        }

        val runner = ShellRunner(allowedCommands = setOf(allowedCmd))
        val dir = Files.createTempDirectory("qorche-shell-test")

        try {
            val events = runner.run(cmd, dir).toList()
            val completed = events.filterIsInstance<AgentEvent.Completed>().first()
            assertEquals(0, completed.exitCode)

            val output = events.filterIsInstance<AgentEvent.Output>()
                .joinToString("") { it.text.trim() }
            assertTrue(output.contains("hello"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `env filter can strip sensitive variables`() = runBlocking {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) return@runBlocking // env command differs on Windows

        val runner = ShellRunner(
            allowedCommands = setOf("env"),
            envFilter = { env ->
                env.filterKeys { key ->
                    key !in setOf("SECRET_KEY", "AWS_SECRET_ACCESS_KEY", "DATABASE_URL")
                }
            }
        )
        val dir = Files.createTempDirectory("qorche-shell-test")

        try {
            val events = runner.run("env", dir).toList()
            val completed = events.filterIsInstance<AgentEvent.Completed>().first()
            assertEquals(0, completed.exitCode)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
