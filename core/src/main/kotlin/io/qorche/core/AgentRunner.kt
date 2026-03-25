package io.qorche.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** Defines how external workers execute tasks. */
interface AgentRunner {
    fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit = {}
    ): Flow<AgentEvent>
}

/** Lifecycle events produced by an agent run. */
sealed class AgentEvent {
    data class Output(val text: String) : AgentEvent()
    data class FileModified(val path: String) : AgentEvent()
    data class Completed(val exitCode: Int) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

/** Outcome of a completed agent execution. */
@Serializable
data class AgentResult(
    val exitCode: Int,
    val filesModified: List<String> = emptyList(),
    val durationMs: Long = 0,
    val output: String = ""
)
