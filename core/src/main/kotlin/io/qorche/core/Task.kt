package io.qorche.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Classification of task work. Used for reporting and future scheduling heuristics. */
@Serializable
enum class TaskType {
    @SerialName("explore") EXPLORE,
    @SerialName("implement") IMPLEMENT,
    @SerialName("verify") VERIFY,
    @SerialName("test") TEST
}

/** Lifecycle state of a task node during graph execution. */
@Serializable
enum class TaskStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    /** Task was not executed because a dependency failed. */
    @SerialName("skipped") SKIPPED
}

/**
 * Immutable definition of a task loaded from YAML.
 *
 * @property id Unique identifier within the project, used as the DAG node key.
 * @property instruction Free-text instruction passed to the [AgentRunner].
 * @property type Classification of work (defaults to IMPLEMENT).
 * @property dependsOn IDs of tasks that must complete before this one starts.
 * @property files Declared file scope — paths this task is expected to modify.
 *   Used for scoped snapshots and scope-violation auditing.
 * @property maxRetries Maximum retry attempts on MVCC conflict (0 = no retry).
 * @property runner Optional runner name referencing the [TaskProject.runners] map.
 *   When null, the default runner is used.
 */
@Serializable
data class TaskDefinition(
    val id: String,
    val instruction: String,
    val type: TaskType = TaskType.IMPLEMENT,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    @SerialName("max_retries")
    val maxRetries: Int = 0,
    val runner: String? = null
)

/**
 * Configuration for a named runner in the YAML runners map.
 *
 * @property type Runner type identifier (e.g. "claude-code", "shell", "ollama").
 * @property model Optional model name (for LLM-backed runners).
 * @property endpoint Optional service endpoint URL.
 * @property extraArgs Additional command-line arguments passed to the runner.
 * @property allowedCommands Permitted commands (for shell runners).
 * @property timeoutSeconds Maximum execution time in seconds (default: 300).
 */
@Serializable
data class RunnerConfig(
    val type: String,
    val model: String? = null,
    val endpoint: String? = null,
    @SerialName("extra_args")
    val extraArgs: List<String> = emptyList(),
    @SerialName("allowed_commands")
    val allowedCommands: List<String> = emptyList(),
    @SerialName("timeout_seconds")
    val timeoutSeconds: Long = 300
)

/**
 * Configuration for a post-execution verification step.
 *
 * After each parallel group completes (post-conflict resolution), the orchestrator
 * can optionally run a verification command (test suite, type checker, linter) to
 * validate that the merged state is correct.
 *
 * @property command Shell command to execute for verification.
 * @property timeoutSeconds Maximum time in seconds before the verification is killed.
 * @property onFailure Policy when verification fails: FAIL aborts the pipeline,
 *   WARN logs the failure but continues execution.
 */
@Serializable
data class VerifyConfig(
    val command: String,
    @SerialName("timeout_seconds")
    val timeoutSeconds: Long = 300,
    @SerialName("on_failure")
    val onFailure: VerifyFailurePolicy = VerifyFailurePolicy.FAIL
)

/** What to do when a verification step fails. */
@Serializable
enum class VerifyFailurePolicy {
    @SerialName("fail") FAIL,
    @SerialName("warn") WARN
}

/**
 * Result of running a verification step.
 *
 * @property success Whether the verification command exited with code 0.
 * @property exitCode The process exit code.
 * @property output Captured stdout/stderr from the verification command.
 * @property elapsedMs Wall-clock time for the verification run.
 * @property groupIndex Which parallel group triggered this verification (0-indexed).
 */
@Serializable
data class VerifyResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String = "",
    val elapsedMs: Long = 0,
    val groupIndex: Int = 0
)

/**
 * Top-level YAML structure: a named project with an ordered list of task definitions.
 *
 * @property defaultRunner Optional name of the runner to use for tasks that don't specify one.
 *   Must reference a key in [runners]. When null, the shell runner is used as the default.
 * @property verify Optional verification step run after each parallel group completes.
 */
@Serializable
data class TaskProject(
    val project: String,
    val runners: Map<String, RunnerConfig> = emptyMap(),
    @SerialName("default_runner")
    val defaultRunner: String? = null,
    val verify: VerifyConfig? = null,
    val tasks: List<TaskDefinition>
)

/**
 * Mutable runtime state for a task during graph execution.
 *
 * Wraps an immutable [TaskDefinition] with execution-time fields that the
 * orchestrator updates as the task progresses through its lifecycle.
 */
data class TaskNode(
    val definition: TaskDefinition,
    var status: TaskStatus = TaskStatus.PENDING,
    var beforeSnapshotId: String? = null,
    var afterSnapshotId: String? = null,
    var result: AgentResult? = null,
    var retryCount: Int = 0
)
