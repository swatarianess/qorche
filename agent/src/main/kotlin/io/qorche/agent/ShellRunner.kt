package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Executes shell commands as an AgentRunner implementation.
 *
 * Security model:
 * - Commands are NOT passed through a shell interpreter (no `sh -c` or `cmd /c`).
 *   ProcessBuilder receives a tokenised argument list, preventing shell injection.
 * - An allowlist restricts which executables can be invoked. Only explicitly
 *   permitted binaries are accepted.
 * - Working directory is validated to exist and be a directory.
 * - Environment variables are inherited from the parent process but can be
 *   restricted via [envFilter].
 * - No network access control (out of scope — use OS-level sandboxing for that).
 *
 * The [instruction] string is parsed as a space-delimited command. The first
 * token is the executable, validated against the allowlist. Remaining tokens
 * are arguments.
 */
class ShellRunner(
    private val allowedCommands: Set<String>,
    private val timeoutSeconds: Long = 300,
    private val envFilter: (Map<String, String>) -> Map<String, String> = { it }
) : AgentRunner {

    init {
        require(allowedCommands.isNotEmpty()) { "Allowlist must contain at least one permitted command" }
    }

    override fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit
    ): Flow<AgentEvent> = flow {
        val tokens = tokenise(instruction)
        if (tokens.isEmpty()) {
            emit(AgentEvent.Error("Empty command"))
            emit(AgentEvent.Completed(exitCode = 2))
            return@flow
        }

        val executable = tokens[0]

        // Security: validate against allowlist
        if (!isAllowed(executable)) {
            val msg = "Command '${executable}' is not in the allowlist. " +
                "Permitted: ${allowedCommands.sorted().joinToString(", ")}"
            emit(AgentEvent.Error(msg))
            emit(AgentEvent.Completed(exitCode = 2))
            return@flow
        }

        // Validate working directory
        if (!workingDirectory.exists() || !workingDirectory.isDirectory()) {
            emit(AgentEvent.Error("Working directory does not exist: $workingDirectory"))
            emit(AgentEvent.Completed(exitCode = 2))
            return@flow
        }

        val processBuilder = ProcessBuilder(tokens)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)

        // Apply environment filter
        val filteredEnv = envFilter(processBuilder.environment().toMap())
        processBuilder.environment().clear()
        processBuilder.environment().putAll(filteredEnv)

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            emit(AgentEvent.Error("Failed to start process: ${e.message}"))
            emit(AgentEvent.Completed(exitCode = 2))
            return@flow
        }

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    onOutput(line)
                    emit(AgentEvent.Output(line))
                }
            }

            val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                emit(AgentEvent.Error("Process timed out after ${timeoutSeconds}s"))
                emit(AgentEvent.Completed(exitCode = 124))
                return@flow
            }

            emit(AgentEvent.Completed(exitCode = process.exitValue()))
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
            emit(AgentEvent.Completed(exitCode = 1))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun isAllowed(executable: String): Boolean {
        // Match against the base name (e.g. "gradlew" matches "./gradlew")
        val baseName = executable
            .replace("\\", "/")
            .substringAfterLast("/")
            .removeSuffix(".exe")
            .removeSuffix(".cmd")
            .removeSuffix(".bat")
            .removeSuffix(".sh")

        return allowedCommands.any { allowed ->
            val allowedBase = allowed
                .replace("\\", "/")
                .substringAfterLast("/")
                .removeSuffix(".exe")
                .removeSuffix(".cmd")
                .removeSuffix(".bat")
                .removeSuffix(".sh")
            baseName == allowedBase || executable == allowed
        }
    }

    companion object {
        /**
         * Tokenise a command string into executable + arguments.
         * Handles quoted strings (single and double quotes).
         */
        internal fun tokenise(command: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inSingleQuote = false
            var inDoubleQuote = false

            for (char in command.trim()) {
                when {
                    char == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                    char == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                    char == ' ' && !inSingleQuote && !inDoubleQuote -> {
                        if (current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
            }
            return tokens
        }
    }
}
