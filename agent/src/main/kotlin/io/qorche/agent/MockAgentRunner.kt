package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test-only [AgentRunner] that simulates agent behaviour without invoking external processes.
 *
 * @param filesToTouch Relative paths to create or modify during execution.
 * @param delayMs Simulated execution time in milliseconds.
 * @param shouldFail When true, the agent emits an error and exits with code 1.
 * @param failMessage Error message used when [shouldFail] is true.
 */
class MockAgentRunner(
    private val filesToTouch: List<String> = emptyList(),
    private val delayMs: Long = 100,
    private val shouldFail: Boolean = false,
    private val failMessage: String = "Mock agent failure"
) : AgentRunner {

    override fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit
    ): Flow<AgentEvent> = flow {
	    emit(AgentEvent.Output("Mock agent starting: $instruction"))
	    delay(delayMs)

	    if (shouldFail) {
		    emit(AgentEvent.Error(failMessage))
		    emit(AgentEvent.Completed(exitCode = 1))
		    return@flow
	    }

	    for (relativePath in filesToTouch) {
		    val file = workingDirectory.resolve(relativePath)
		    Files.createDirectories(file.parent)
		    Files.writeString(file, "// Modified by mock agent\n")
		    val normalised = relativePath.replace("\\", "/")
		    emit(AgentEvent.FileModified(normalised))
		    emit(AgentEvent.Output("Modified: $normalised"))
	    }

	    emit(AgentEvent.Output("Mock agent completed successfully"))
	    emit(AgentEvent.Completed(exitCode = 0))
    }.flowOn(Dispatchers.IO)
}
