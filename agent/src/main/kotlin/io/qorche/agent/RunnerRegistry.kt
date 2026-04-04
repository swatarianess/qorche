package io.qorche.agent

import io.qorche.core.AgentRunner
import io.qorche.core.RunnerConfig

/**
 * Builds a named [AgentRunner] registry from [RunnerConfig] definitions.
 *
 * Each config's [RunnerConfig.type] maps to a concrete runner implementation:
 * - `claude-code` → [ClaudeCodeAdapter]
 * - `shell` → [ShellRunner]
 *
 * Unknown types produce a descriptive error at construction time (fail-fast).
 */
object RunnerRegistry {

    /**
     * Build a map of named runners from their YAML-declared configs.
     *
     * @param configs The `runners` map from [io.qorche.core.TaskProject].
     * @return Map of runner name to [AgentRunner] instance.
     * @throws IllegalArgumentException if a config references an unknown runner type.
     */
    fun build(configs: Map<String, RunnerConfig>): Map<String, AgentRunner> =
        configs.mapValues { (name, config) -> createRunner(name, config) }

    private fun createRunner(name: String, config: RunnerConfig): AgentRunner =
        when (config.type) {
            "claude-code" -> ClaudeCodeAdapter(
                timeoutSeconds = config.timeoutSeconds,
                extraArgs = config.extraArgs
            )

            "shell" -> {
                require(config.allowedCommands.isNotEmpty()) {
                    "Runner '$name' (type=shell) must specify at least one allowed_command"
                }
                ShellRunner(
                    allowedCommands = config.allowedCommands.toSet(),
                    timeoutSeconds = config.timeoutSeconds
                )
            }

            else -> throw IllegalArgumentException(
                "Runner '$name' has unknown type '${config.type}'. " +
                    "Supported types: claude-code, shell"
            )
        }
}
