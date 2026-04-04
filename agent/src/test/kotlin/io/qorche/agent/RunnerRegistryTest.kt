package io.qorche.agent

import io.qorche.core.RunnerConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunnerRegistryTest {

    @Test
    fun `builds claude-code runner from config`() {
        val configs = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                extraArgs = listOf("--dangerously-skip-permissions"),
                timeoutSeconds = 600
            )
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["claude"] is ClaudeCodeAdapter)
    }

    @Test
    fun `builds shell runner from config`() {
        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("npm", "gradle")
            )
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["shell"] is ShellRunner)
    }

    @Test
    fun `rejects shell runner without allowed commands`() {
        val configs = mapOf(
            "bad-shell" to RunnerConfig(type = "shell")
        )

        assertFailsWith<IllegalArgumentException> {
            RunnerRegistry.build(configs)
        }
    }

    @Test
    fun `rejects unknown runner type`() {
        val configs = mapOf(
            "unknown" to RunnerConfig(type = "gpt-99")
        )

        assertFailsWith<IllegalArgumentException> {
            RunnerRegistry.build(configs)
        }
    }

    @Test
    fun `builds multiple runners from mixed config`() {
        val configs = mapOf(
            "claude" to RunnerConfig(type = "claude-code"),
            "shell" to RunnerConfig(type = "shell", allowedCommands = listOf("npm"))
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners.size == 2)
        assertTrue(runners["claude"] is ClaudeCodeAdapter)
        assertTrue(runners["shell"] is ShellRunner)
    }

    @Test
    fun `empty config returns empty map`() {
        val runners = RunnerRegistry.build(emptyMap())
        assertTrue(runners.isEmpty())
    }
}
