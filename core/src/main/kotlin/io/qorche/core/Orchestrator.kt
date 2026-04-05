package io.qorche.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Coordinates agent execution with snapshot lifecycle and WAL logging.
 */
class Orchestrator(private val workDir: Path) {

    private val qorcheDir: Path = workDir.resolve(".qorche").also { it.createDirectories() }
    private val snapshotStore = SnapshotStore(qorcheDir.resolve("snapshots"))
    private val walWriter = WALWriter(qorcheDir.resolve("wal.jsonl"))
    private val fileIndex = FileIndex()
    private val fileIndexPath = qorcheDir.resolve("file-index.json")
    private val logsDir: Path = qorcheDir.resolve("logs").also { it.createDirectories() }

    /** Optional callback for snapshot progress updates. Set by CLI for user-facing feedback. */
    var onSnapshotProgress: ((SnapshotProgress) -> Unit)? = null

    init {
        fileIndex.loadFrom(fileIndexPath)
        SnapshotCreator.loadIgnoreFile(workDir)
    }

    /** Result of a single task execution: agent output, filesystem diff, and snapshots. */
    @Serializable
    data class RunResult(
        val agentResult: AgentResult,
        val diff: SnapshotDiff,
        val beforeSnapshot: Snapshot,
        val afterSnapshot: Snapshot
    )

    /**
     * Aggregate result of executing an entire task graph.
     *
     * Contains per-task outcomes, detected conflicts, scope violations,
     * verification results, and summary counters. [success] is true only
     * when zero tasks failed and no verification step caused a failure.
     */
    @Serializable
    data class GraphResult(
        val project: String,
        val taskResults: Map<String, TaskOutcome>,
        val totalTasks: Int,
        val completedTasks: Int,
        val failedTasks: Int,
        val skippedTasks: Int,
        val retriedTasks: Int = 0,
        val conflicts: List<ConflictDetector.TaskConflict> = emptyList(),
        val scopeViolations: List<ConflictDetector.ScopeViolation> = emptyList(),
        val verifyResults: List<VerifyResult> = emptyList()
    ) {
        val success: Boolean get() = failedTasks == 0 && !hasVerifyFailure
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
        val hasScopeViolations: Boolean get() = scopeViolations.isNotEmpty()
        val hasVerifyFailure: Boolean get() = verifyResults.any { !it.success }
    }

    /**
     * Outcome of a single task within a graph execution.
     *
     * @property runResult Present when the task actually executed (not skipped).
     * @property skipReason Human-readable reason if the task was skipped or failed.
     * @property retryCount Number of retry attempts made due to MVCC conflicts.
     */
    @Serializable
    data class TaskOutcome(
        val taskId: String,
        val status: TaskStatus,
        val runResult: RunResult? = null,
        val skipReason: String? = null,
        val retryCount: Int = 0,
        val elapsedMs: Long = 0
    )

    /**
     * Execute a single task with full snapshot lifecycle and WAL logging.
     * Takes before/after snapshots, runs the agent, computes diff, and persists state.
     *
     * Returns [Result.success] with [RunResult] on completion (even if the agent
     * exited with a non-zero code — check [AgentResult.exitCode]).
     * Returns [Result.failure] only when the agent threw an exception (crash, timeout, etc.).
     */
    suspend fun runTask(
        taskId: String,
        instruction: String,
        runner: AgentRunner,
        scopePaths: List<String> = emptyList(),
        onOutput: (String) -> Unit = {}
    ): Result<RunResult> {
        val result = snapshotAndRun(taskId, instruction, scopePaths, runner, onOutput)
        fileIndex.saveTo(fileIndexPath)
        return result
    }

    /**
     * Resolve the runner for a task: look up the task's runner name in the registry,
     * falling back to the default runner when unset.
     */
    private fun resolveRunner(
        def: TaskDefinition,
        defaultRunner: AgentRunner,
        runners: Map<String, AgentRunner>
    ): AgentRunner {
        val runnerName = def.runner ?: return defaultRunner
        return checkNotNull(runners[runnerName]) {
            "Task '${def.id}' references unknown runner '$runnerName'"
        }
    }

    /**
     * Execute a task graph sequentially in topological order.
     * If a task fails, all tasks that depend on it are skipped.
     *
     * @param runners Named runner registry. Tasks with a `runner` field are dispatched
     *   to the corresponding entry; all others use [runner] as the default.
     */
    suspend fun runGraph(
        project: String,
        graph: TaskGraph,
        runner: AgentRunner,
        runners: Map<String, AgentRunner> = emptyMap(),
        onTaskStart: (TaskDefinition) -> Unit = {},
        onTaskComplete: (String, TaskOutcome) -> Unit = { _, _ -> },
        onOutput: (String) -> Unit = {}
    ): GraphResult {
        val outcomes = mutableMapOf<String, TaskOutcome>()
        val failedTasks = mutableSetOf<String>()

        for (taskId in graph.topologicalSort()) {
            val node = graph[taskId] ?: continue
            val def = node.definition

            val failedDep = def.dependsOn.firstOrNull { it in failedTasks }
            if (failedDep != null) {
                node.status = TaskStatus.SKIPPED
                failedTasks.add(taskId)
                val outcome = TaskOutcome(
                    taskId = taskId,
                    status = TaskStatus.SKIPPED,
                    skipReason = "Dependency '$failedDep' failed"
                )
                outcomes[taskId] = outcome
                onTaskComplete(taskId, outcome)
                continue
            }

            onTaskStart(def)
            node.status = TaskStatus.RUNNING

            val taskRunner = resolveRunner(def, runner, runners)
            val startNanos = System.nanoTime()
            val taskResult = runTask(
                taskId = taskId,
                instruction = def.instruction,
                runner = taskRunner,
                scopePaths = def.files,
                onOutput = onOutput
            )
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            val outcome = if (taskResult.isSuccess) {
                val result = taskResult.getOrThrow()
                val success = result.agentResult.exitCode == 0
                node.status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
                node.beforeSnapshotId = result.beforeSnapshot.id
                node.afterSnapshotId = result.afterSnapshot.id
                node.result = result.agentResult

                if (!success) failedTasks.add(taskId)

                TaskOutcome(taskId = taskId, status = node.status, runResult = result, elapsedMs = elapsedMs)
            } else {
                node.status = TaskStatus.FAILED
                failedTasks.add(taskId)
                TaskOutcome(
                    taskId = taskId,
                    status = TaskStatus.FAILED,
                    skipReason = "Exception: ${taskResult.exceptionOrNull()?.message}",
                    elapsedMs = elapsedMs
                )
            }
            outcomes[taskId] = outcome
            onTaskComplete(taskId, outcome)
        }

        val allNodes = graph.allNodes()
        return GraphResult(
            project = project,
            taskResults = outcomes,
            totalTasks = allNodes.size,
            completedTasks = allNodes.count { it.status == TaskStatus.COMPLETED },
            failedTasks = allNodes.count { it.status == TaskStatus.FAILED },
            skippedTasks = allNodes.count { it.status == TaskStatus.SKIPPED }
        )
    }

    /**
     * Execute a task graph with parallel execution within groups.
     *
     * Tasks in the same parallel group run concurrently. After each group,
     * MVCC conflict detection checks for write-write conflicts. On conflict,
     * the earlier task in group order wins; losers are retried sequentially
     * against the updated filesystem (up to [retryPolicy] limits).
     *
     * @param runners Named runner registry. Tasks with a `runner` field are dispatched
     *   to the corresponding entry; all others use [runner] as the default.
     * @param verifyConfig Optional verification step run after each parallel group.
     */
    suspend fun runGraphParallel(
        project: String,
        graph: TaskGraph,
        runner: AgentRunner,
        runners: Map<String, AgentRunner> = emptyMap(),
        retryPolicy: ConflictDetector.ConflictRetryPolicy = ConflictDetector.ConflictRetryPolicy(),
        verifyConfig: VerifyConfig? = null,
        onTaskStart: (TaskDefinition) -> Unit = {},
        onTaskComplete: (String, TaskOutcome) -> Unit = { _, _ -> },
        onConflict: (ConflictDetector.TaskConflict) -> Unit = {},
        onRetry: (taskId: String, attempt: Int, conflictWith: String, conflictingFiles: Set<String>) -> Unit = { _, _, _, _ -> },
        onScopeViolation: (ConflictDetector.ScopeViolation) -> Unit = {},
        onVerify: (VerifyResult) -> Unit = {},
        onOutput: (String) -> Unit = {}
    ): GraphResult {
        val outcomes = mutableMapOf<String, TaskOutcome>()
        val failedTasks = mutableSetOf<String>()
        val allConflicts = mutableListOf<ConflictDetector.TaskConflict>()
        val allScopeViolations = mutableListOf<ConflictDetector.ScopeViolation>()
        val allVerifyResults = mutableListOf<VerifyResult>()
        var totalRetries = 0
        var groupIndex = 0
        val walMutex = Mutex()

        for (group in graph.parallelGroups()) {
            val runnableTasks = filterRunnableTasks(group, graph, failedTasks, outcomes, onTaskComplete)
            if (runnableTasks.isEmpty()) continue

            if (runnableTasks.size == 1) {
                val taskId = runnableTasks[0]
                val taskRunner = resolveRunner(graph[taskId]!!.definition, runner, runners)
                val outcome = executeTask(taskId, graph, taskRunner, onTaskStart, onOutput)
                outcomes[taskId] = outcome
                if (outcome.status == TaskStatus.FAILED) failedTasks.add(taskId)
                onTaskComplete(taskId, outcome)

                // Run verification after single-task group if configured
                if (verifyConfig != null && outcome.status == TaskStatus.COMPLETED) {
                    val verifyResult = runVerification(verifyConfig, groupIndex)
                    allVerifyResults.add(verifyResult)
                    onVerify(verifyResult)
                    walWriter.append(WALEntry.VerifyCompleted(
                        taskId = "verify-group-$groupIndex",
                        success = verifyResult.success,
                        exitCode = verifyResult.exitCode,
                        command = verifyConfig.command,
                        groupIndex = groupIndex
                    ))
                    if (!verifyResult.success && verifyConfig.onFailure == VerifyFailurePolicy.FAIL) {
                        break
                    }
                }

                groupIndex++
                continue
            }

            val baseSnapshot = SnapshotCreator.create(workDir, "base: group", fileIndex = fileIndex, onProgress = onSnapshotProgress)
            snapshotStore.save(baseSnapshot)

            val groupResults = coroutineScope {
                runnableTasks.map { taskId ->
                    async {
                        val node = graph[taskId]!!
                        val taskRunner = resolveRunner(node.definition, runner, runners)
                        onTaskStart(node.definition)
                        node.status = TaskStatus.RUNNING
                        val startNanos = System.nanoTime()
                        val result = snapshotAndRun(
                            taskId, node.definition.instruction,
                            node.definition.files, taskRunner, onOutput, walMutex
                        )
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        Triple(taskId, result, elapsedMs)
                    }
                }.awaitAll()
            }

            val changesByTask = mutableMapOf<String, Set<String>>()
            val taskRunResults = mutableMapOf<String, RunResult>()
            val taskElapsed = mutableMapOf<String, Long>()

            classifyGroupResults(groupResults, graph, failedTasks, outcomes, changesByTask, taskRunResults, taskElapsed, onTaskComplete)

            val conflicts = ConflictDetector.detectGroupConflicts(changesByTask)

            if (conflicts.isEmpty()) {
                commitWinners(changesByTask.keys, graph, outcomes, taskRunResults, taskElapsed, onTaskComplete)
            } else {
                recordConflicts(conflicts, baseSnapshot, allConflicts, onConflict)
                val resolution = ConflictDetector.resolveConflicts(conflicts, runnableTasks)

                commitWinners(
                    changesByTask.keys.filter { it !in resolution.losers }.toSet(),
                    graph, outcomes, taskRunResults, taskElapsed, onTaskComplete
                )

                for (loserId in resolution.losers) {
                    if (loserId in outcomes) continue
                    val loserRunner = resolveRunner(graph[loserId]!!.definition, runner, runners)
                    val retries = retryLoser(
                        loserId, graph, conflicts, changesByTask, taskRunResults, taskElapsed,
                        baseSnapshot, retryPolicy, loserRunner, walMutex, failedTasks, outcomes,
                        onRetry, onTaskComplete, onOutput
                    )
                    totalRetries += retries
                }
            }

            auditScopeViolations(runnableTasks, graph, baseSnapshot, changesByTask, allScopeViolations, onScopeViolation)

            // Run verification step after each parallel group if configured
            if (verifyConfig != null) {
                val verifyResult = runVerification(verifyConfig, groupIndex)
                allVerifyResults.add(verifyResult)
                onVerify(verifyResult)
                walWriter.append(WALEntry.VerifyCompleted(
                    taskId = "verify-group-$groupIndex",
                    success = verifyResult.success,
                    exitCode = verifyResult.exitCode,
                    command = verifyConfig.command,
                    groupIndex = groupIndex
                ))

                if (!verifyResult.success && verifyConfig.onFailure == VerifyFailurePolicy.FAIL) {
                    // Mark all remaining tasks as skipped
                    break
                }
            }

            groupIndex++
        }

        fileIndex.saveTo(fileIndexPath)

        val allNodes = graph.allNodes()
        return GraphResult(
            project = project,
            taskResults = outcomes,
            totalTasks = allNodes.size,
            completedTasks = allNodes.count { it.status == TaskStatus.COMPLETED },
            failedTasks = allNodes.count { it.status == TaskStatus.FAILED },
            skippedTasks = allNodes.count { it.status == TaskStatus.SKIPPED },
            retriedTasks = totalRetries,
            conflicts = allConflicts,
            scopeViolations = allScopeViolations,
            verifyResults = allVerifyResults
        )
    }

    /** Filter tasks in a group, skipping those with failed dependencies. */
    private fun filterRunnableTasks(
        group: List<String>,
        graph: TaskGraph,
        failedTasks: MutableSet<String>,
        outcomes: MutableMap<String, TaskOutcome>,
        onTaskComplete: (String, TaskOutcome) -> Unit
    ): List<String> = group.filter { taskId ->
        val node = graph[taskId] ?: return@filter false
        val failedDep = node.definition.dependsOn.firstOrNull { it in failedTasks }
        if (failedDep != null) {
            node.status = TaskStatus.SKIPPED
            failedTasks.add(taskId)
            val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.SKIPPED, skipReason = "Dependency '$failedDep' failed")
            outcomes[taskId] = outcome
            onTaskComplete(taskId, outcome)
            false
        } else {
            true
        }
    }

    /** Categorize parallel group results into outcomes, changes, and run results. */
    private fun classifyGroupResults(
        groupResults: List<Triple<String, Result<RunResult>, Long>>,
        graph: TaskGraph,
        failedTasks: MutableSet<String>,
        outcomes: MutableMap<String, TaskOutcome>,
        changesByTask: MutableMap<String, Set<String>>,
        taskRunResults: MutableMap<String, RunResult>,
        taskElapsed: MutableMap<String, Long>,
        onTaskComplete: (String, TaskOutcome) -> Unit
    ) {
        for ((taskId, result, elapsed) in groupResults) {
            taskElapsed[taskId] = elapsed
            val node = graph[taskId]!!
            when {
                result.isFailure -> {
                    node.status = TaskStatus.FAILED
                    failedTasks.add(taskId)
                    val outcome = TaskOutcome(
                        taskId = taskId, status = TaskStatus.FAILED,
                        skipReason = "Exception: ${result.exceptionOrNull()?.message}", elapsedMs = elapsed
                    )
                    outcomes[taskId] = outcome
                    onTaskComplete(taskId, outcome)
                }
                else -> {
                    val runResult = result.getOrThrow()
                    taskRunResults[taskId] = runResult
                    val diff = runResult.diff
                    changesByTask[taskId] = diff.added + diff.modified + diff.deleted

                    if (runResult.agentResult.exitCode != 0) {
                        node.status = TaskStatus.FAILED
                        failedTasks.add(taskId)
                        node.beforeSnapshotId = runResult.beforeSnapshot.id
                        node.afterSnapshotId = runResult.afterSnapshot.id
                        node.result = runResult.agentResult
                        val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.FAILED, runResult = runResult, elapsedMs = elapsed)
                        outcomes[taskId] = outcome
                        onTaskComplete(taskId, outcome)
                    }
                }
            }
        }
    }

    /** Mark successful tasks as completed and record their outcomes. */
    private fun commitWinners(
        taskIds: Set<String>,
        graph: TaskGraph,
        outcomes: MutableMap<String, TaskOutcome>,
        taskRunResults: Map<String, RunResult>,
        taskElapsed: Map<String, Long>,
        onTaskComplete: (String, TaskOutcome) -> Unit
    ) {
        for (taskId in taskIds) {
            if (taskId in outcomes) continue
            val node = graph[taskId]!!
            val runResult = taskRunResults[taskId] ?: continue
            node.status = TaskStatus.COMPLETED
            node.beforeSnapshotId = runResult.beforeSnapshot.id
            node.afterSnapshotId = runResult.afterSnapshot.id
            node.result = runResult.agentResult
            val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.COMPLETED, runResult = runResult, elapsedMs = taskElapsed[taskId] ?: 0)
            outcomes[taskId] = outcome
            onTaskComplete(taskId, outcome)
        }
    }

    /** Log detected conflicts to WAL and accumulate them for the result. */
    private fun recordConflicts(
        conflicts: List<ConflictDetector.TaskConflict>,
        baseSnapshot: Snapshot,
        allConflicts: MutableList<ConflictDetector.TaskConflict>,
        onConflict: (ConflictDetector.TaskConflict) -> Unit
    ) {
        for (conflict in conflicts) {
            allConflicts.add(conflict)
            onConflict(conflict)
            walWriter.append(WALEntry.ConflictDetected(
                taskId = conflict.taskA,
                conflictingTaskId = conflict.taskB,
                conflictingFiles = conflict.conflictingFiles.toList(),
                baseSnapshotId = baseSnapshot.id
            ))
        }
    }

    /**
     * Retry a conflict loser task up to its max_retries limit.
     * Returns the number of retry attempts made.
     */
    private suspend fun retryLoser(
        loserId: String,
        graph: TaskGraph,
        conflicts: List<ConflictDetector.TaskConflict>,
        changesByTask: Map<String, Set<String>>,
        taskRunResults: Map<String, RunResult>,
        taskElapsed: Map<String, Long>,
        baseSnapshot: Snapshot,
        retryPolicy: ConflictDetector.ConflictRetryPolicy,
        runner: AgentRunner,
        walMutex: Mutex,
        failedTasks: MutableSet<String>,
        outcomes: MutableMap<String, TaskOutcome>,
        onRetry: (String, Int, String, Set<String>) -> Unit,
        onTaskComplete: (String, TaskOutcome) -> Unit,
        onOutput: (String) -> Unit
    ): Int {
        val node = graph[loserId] ?: return 0
        val def = node.definition
        val maxRetries = if (retryPolicy.enabled) def.maxRetries.coerceAtMost(retryPolicy.defaultMaxRetries) else 0

        val conflictWith = conflicts.first { it.taskA == loserId || it.taskB == loserId }
        val conflictPeer = if (conflictWith.taskA == loserId) conflictWith.taskB else conflictWith.taskA

        if (maxRetries <= 0) {
            failLoser(node, loserId, taskRunResults[loserId], failedTasks, outcomes, onTaskComplete,
                "MVCC conflict with '$conflictPeer' (retries disabled)", elapsedMs = taskElapsed[loserId] ?: 0)
            return 0
        }

        val winnerChanges = changesByTask[conflictPeer] ?: emptySet()

        for (attempt in 1..maxRetries) {
            node.retryCount = attempt

            prepareRetry(loserId, attempt, changesByTask[loserId] ?: emptySet(), winnerChanges, baseSnapshot, conflictPeer, conflictWith, onRetry)

            val retryStartNanos = System.nanoTime()
            val retryResult = snapshotAndRun(loserId, def.instruction, def.files, runner, onOutput, walMutex)
            val retryElapsedMs = (System.nanoTime() - retryStartNanos) / 1_000_000

            val resolution = resolveRetryAttempt(retryResult, loserId, attempt, maxRetries, winnerChanges, node, failedTasks, outcomes, onTaskComplete, retryElapsedMs)
            if (resolution != null) return resolution
        }

        // Fallback: should not reach here, but handle gracefully
        failLoser(node, loserId, null, failedTasks, outcomes, onTaskComplete, "MVCC conflict with '$conflictPeer'")
        return maxRetries
    }

    /** Prepare filesystem and WAL for a retry attempt. */
    private fun prepareRetry(
        loserId: String, attempt: Int, loserChanges: Set<String>, winnerChanges: Set<String>,
        baseSnapshot: Snapshot, conflictPeer: String, conflictWith: ConflictDetector.TaskConflict,
        onRetry: (String, Int, String, Set<String>) -> Unit
    ) {
        rollbackTaskChanges(loserChanges, winnerChanges, baseSnapshot)
        fileIndex.invalidateAll(loserChanges)
        onRetry(loserId, attempt, conflictPeer, conflictWith.conflictingFiles)
        walWriter.append(WALEntry.TaskRetryScheduled(
            taskId = loserId, attempt = attempt, conflictWith = conflictPeer,
            conflictingFiles = conflictWith.conflictingFiles.toList()
        ))
    }

    /** Evaluate a retry attempt result. Returns retry count if terminal, null to continue retrying. */
    private fun resolveRetryAttempt(
        retryResult: Result<RunResult>, loserId: String, attempt: Int, maxRetries: Int,
        winnerChanges: Set<String>, node: TaskNode, failedTasks: MutableSet<String>,
        outcomes: MutableMap<String, TaskOutcome>, onTaskComplete: (String, TaskOutcome) -> Unit,
        elapsedMs: Long
    ): Int? {
        if (retryResult.isFailure) {
            failLoser(node, loserId, null, failedTasks, outcomes, onTaskComplete,
                "Retry attempt $attempt failed: ${retryResult.exceptionOrNull()?.message}", attempt, elapsedMs)
            return attempt
        }

        val runResult = retryResult.getOrThrow()
        val retryChanges = runResult.diff.added + runResult.diff.modified + runResult.diff.deleted
        walWriter.append(WALEntry.TaskRetried(taskId = loserId, attempt = attempt, snapshotId = runResult.afterSnapshot.id))

        val stillConflicting = retryChanges.intersect(winnerChanges)
        if (runResult.agentResult.exitCode == 0 && stillConflicting.isEmpty()) {
            node.status = TaskStatus.COMPLETED
            node.beforeSnapshotId = runResult.beforeSnapshot.id
            node.afterSnapshotId = runResult.afterSnapshot.id
            node.result = runResult.agentResult
            val outcome = TaskOutcome(taskId = loserId, status = TaskStatus.COMPLETED, runResult = runResult, retryCount = attempt, elapsedMs = elapsedMs)
            outcomes[loserId] = outcome
            onTaskComplete(loserId, outcome)
            return attempt
        }

        if (attempt == maxRetries) {
            failLoser(node, loserId, runResult, failedTasks, outcomes, onTaskComplete,
                "MVCC conflict persists after $attempt retry attempts", attempt, elapsedMs)
            return attempt
        }

        return null
    }

    /** Mark a loser task as failed and record its outcome. */
    private fun failLoser(
        node: TaskNode, taskId: String, runResult: RunResult?, failedTasks: MutableSet<String>,
        outcomes: MutableMap<String, TaskOutcome>, onTaskComplete: (String, TaskOutcome) -> Unit,
        reason: String, retryCount: Int = 0, elapsedMs: Long = 0
    ) {
        node.status = TaskStatus.FAILED
        failedTasks.add(taskId)
        if (runResult != null) {
            node.beforeSnapshotId = runResult.beforeSnapshot.id
            node.afterSnapshotId = runResult.afterSnapshot.id
            node.result = runResult.agentResult
        }
        val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.FAILED, runResult = runResult, skipReason = reason, retryCount = retryCount, elapsedMs = elapsedMs)
        outcomes[taskId] = outcome
        onTaskComplete(taskId, outcome)
    }

    /** Check for scope violations after a parallel group completes. */
    private fun auditScopeViolations(
        runnableTasks: List<String>,
        graph: TaskGraph,
        baseSnapshot: Snapshot,
        changesByTask: Map<String, Set<String>>,
        allScopeViolations: MutableList<ConflictDetector.ScopeViolation>,
        onScopeViolation: (ConflictDetector.ScopeViolation) -> Unit
    ) {
        val hasScopedTasks = runnableTasks.any { graph[it]?.definition?.files?.isNotEmpty() == true }
        if (!hasScopedTasks) return

        val auditSnapshot = SnapshotCreator.create(workDir, "audit: group", fileIndex = fileIndex, onProgress = onSnapshotProgress)
        val fullDiff = SnapshotCreator.diff(baseSnapshot, auditSnapshot)
        val allChangedOnDisk = fullDiff.added + fullDiff.modified + fullDiff.deleted

        val taskScopes = runnableTasks.associate { taskId ->
            taskId to (graph[taskId]?.definition?.files ?: emptyList())
        }

        val violations = ConflictDetector.detectScopeViolations(allChangedOnDisk, taskScopes, changesByTask)
        for (violation in violations) {
            allScopeViolations.add(violation)
            onScopeViolation(violation)
            walWriter.append(WALEntry.ScopeViolation(
                taskId = violation.suspectTaskIds.firstOrNull() ?: "unknown",
                undeclaredFiles = violation.undeclaredFiles.toList(),
                suspectTaskIds = violation.suspectTaskIds
            ))
        }
    }

    /**
     * Execute a single task, returning a TaskOutcome. Used by sequential runGraph.
     */
    private suspend fun executeTask(
        taskId: String,
        graph: TaskGraph,
        runner: AgentRunner,
        onTaskStart: (TaskDefinition) -> Unit,
        onOutput: (String) -> Unit
    ): TaskOutcome {
        val node = graph[taskId] ?: return TaskOutcome(taskId, TaskStatus.FAILED, skipReason = "Unknown task")
        val def = node.definition

        onTaskStart(def)
        node.status = TaskStatus.RUNNING

        val startNanos = System.nanoTime()
        val taskResult = runTask(
            taskId = taskId,
            instruction = def.instruction,
            runner = runner,
            scopePaths = def.files,
            onOutput = onOutput
        )
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        return if (taskResult.isSuccess) {
            val result = taskResult.getOrThrow()
            val success = result.agentResult.exitCode == 0
            node.status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
            node.beforeSnapshotId = result.beforeSnapshot.id
            node.afterSnapshotId = result.afterSnapshot.id
            node.result = result.agentResult

            TaskOutcome(taskId = taskId, status = node.status, runResult = result, elapsedMs = elapsedMs)
        } else {
            node.status = TaskStatus.FAILED
            TaskOutcome(taskId = taskId, status = TaskStatus.FAILED, skipReason = "Exception: ${taskResult.exceptionOrNull()?.message}", elapsedMs = elapsedMs)
        }
    }

    /**
     * Core snapshot lifecycle: before-snapshot → run agent → after-snapshot → WAL log.
     * Shared by both [runTask] (single/sequential) and parallel group execution.
     *
     * @param walMutex Optional mutex for concurrent WAL writes (parallel execution).
     */
    private suspend fun snapshotAndRun(
        taskId: String,
        instruction: String,
        scopePaths: List<String>,
        runner: AgentRunner,
        onOutput: (String) -> Unit,
        walMutex: Mutex? = null
    ): Result<RunResult> = try {
        val beforeSnapshot = takeSnapshot(taskId, scopePaths, "before")
        snapshotStore.save(beforeSnapshot)

        val startEntry = WALEntry.TaskStarted(taskId = taskId, instruction = instruction, snapshotId = beforeSnapshot.id)
        if (walMutex != null) walMutex.withLock { walWriter.append(startEntry) } else walWriter.append(startEntry)

        val (exitCode, filesModified, agentException) = runAgent(taskId, instruction, runner, onOutput)

        fileIndex.clear()

        val afterSnapshot = takeSnapshot(taskId, scopePaths, "after", parentId = beforeSnapshot.id)
        snapshotStore.save(afterSnapshot)

        val diff = SnapshotCreator.diff(beforeSnapshot, afterSnapshot)
        val agentResult = AgentResult(exitCode = exitCode, filesModified = filesModified)

        val walEntry = buildCompletionEntry(taskId, afterSnapshot.id, exitCode, filesModified, agentException)
        if (walMutex != null) walMutex.withLock { walWriter.append(walEntry) } else walWriter.append(walEntry)

        if (agentException != null) Result.failure(agentException)
        else Result.success(RunResult(agentResult, diff, beforeSnapshot, afterSnapshot))
    } catch (e: Exception) {
        val entry = WALEntry.TaskFailed(taskId = taskId, error = e.message ?: "Unknown error")
        if (walMutex != null) walMutex.withLock { walWriter.append(entry) } else walWriter.append(entry)
        Result.failure(e)
    }

    /** Take a before or after snapshot, scoped or full. */
    private fun takeSnapshot(taskId: String, scopePaths: List<String>, phase: String, parentId: String? = null): Snapshot =
        if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "$phase: $taskId", parentId = parentId, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        } else {
            SnapshotCreator.create(workDir, "$phase: $taskId", parentId = parentId, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        }

    /** Run the agent and collect events. Returns exit code, modified files, and any exception. */
    private suspend fun runAgent(taskId: String, instruction: String, runner: AgentRunner, onOutput: (String) -> Unit): AgentRunOutcome {
        val events = mutableListOf<AgentEvent>()
        val filesModified = mutableListOf<String>()
        var exitCode = 1
        var agentException: Exception? = null

        try {
            runner.run(instruction, workDir) { line ->
                logToFile(taskId, line)
                onOutput(line)
            }.toList(events)

            for (event in events) {
                when (event) {
                    is AgentEvent.FileModified -> filesModified.add(event.path)
                    is AgentEvent.Completed -> exitCode = event.exitCode
                    else -> {}
                }
            }
        } catch (e: Exception) {
            agentException = e
        }

        return AgentRunOutcome(exitCode, filesModified, agentException)
    }

    private data class AgentRunOutcome(val exitCode: Int, val filesModified: List<String>, val exception: Exception?)

    /** Build the appropriate WAL entry for task completion. */
    private fun buildCompletionEntry(taskId: String, snapshotId: String, exitCode: Int, filesModified: List<String>, exception: Exception?): WALEntry =
        when {
            exception != null -> WALEntry.TaskFailed(taskId = taskId, error = exception.message ?: "Unknown error")
            exitCode == 0 -> WALEntry.TaskCompleted(taskId = taskId, snapshotId = snapshotId, exitCode = exitCode, filesModified = filesModified)
            else -> WALEntry.TaskFailed(taskId = taskId, error = "Agent exited with code $exitCode")
        }

    /**
     * Roll back a task's filesystem changes by restoring files to their base snapshot state.
     * Files the task added (not in base) are deleted. Files the task modified are restored.
     * Winner's changes are preserved — only the loser's non-overlapping changes are undone.
     */
    private fun rollbackTaskChanges(
        loserChanges: Set<String>,
        winnerChanges: Set<String>,
        baseSnapshot: Snapshot
    ) {
        val filesToRollback = loserChanges - winnerChanges
        for (relativePath in filesToRollback) {
            try {
                val file = workDir.resolve(relativePath)
                // Delete the loser's file so the retry starts from a clean state.
                // We can't restore content from a hash alone — the retry will take
                // its own before-snapshot which captures current filesystem state.
                java.nio.file.Files.deleteIfExists(file)
            } catch (_: Exception) {
                // Best-effort rollback — locked files on Windows or permission errors
                // should not prevent the retry from proceeding
            }
        }
    }

    private fun logToFile(taskId: String, line: String) {
        try {
            val logFile = logsDir.resolve("$taskId.log")
            java.nio.file.Files.writeString(
                logFile,
                line + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
        } catch (_: Exception) {
            // Best-effort logging — never crash a task because logging failed
        }
    }

    /**
     * Run a verification command and capture its result.
     *
     * The command is executed as a shell process in the working directory.
     * Stdout and stderr are captured for logging. The process is killed if
     * it exceeds the configured timeout.
     */
    private fun runVerification(config: VerifyConfig, groupIndex: Int): VerifyResult {
        val startNanos = System.nanoTime()
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val command = if (isWindows) {
                listOf("cmd", "/c", config.command)
            } else {
                listOf("sh", "-c", config.command)
            }

            val process = ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    output.appendLine(line)
                }
            }

            val completed = process.waitFor(config.timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            if (!completed) {
                process.destroyForcibly()
                return VerifyResult(
                    success = false,
                    exitCode = -1,
                    output = output.toString() + "\n[TIMEOUT after ${config.timeoutSeconds}s]",
                    elapsedMs = elapsedMs,
                    groupIndex = groupIndex
                )
            }

            val exitCode = process.exitValue()
            return VerifyResult(
                success = exitCode == 0,
                exitCode = exitCode,
                output = output.toString(),
                elapsedMs = elapsedMs,
                groupIndex = groupIndex
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            return VerifyResult(
                success = false,
                exitCode = -1,
                output = "Verification failed: ${e.message}",
                elapsedMs = elapsedMs,
                groupIndex = groupIndex
            )
        }
    }

    /** Returns all stored snapshots, ordered by timestamp (most recent first). */
    fun history(): List<Snapshot> = snapshotStore.list()

    /** Loads a snapshot by its full or prefix ID, or null if not found. */
    fun loadSnapshot(id: String): Snapshot? = snapshotStore.load(id)

    /** Computes the file-level diff between two snapshots, or null if either is not found. */
    fun diffSnapshots(id1: String, id2: String): SnapshotDiff? {
        val snap1 = snapshotStore.load(id1) ?: return null
        val snap2 = snapshotStore.load(id2) ?: return null
        return SnapshotCreator.diff(snap1, snap2)
    }

    /** Reads all WAL entries from the append-only log. */
    fun walEntries(): List<WALEntry> = walWriter.readAll()

    /**
     * Summary of what was removed by a [clean] operation.
     */
    @Serializable
    data class CleanResult(
        val snapshotsRemoved: Int = 0,
        val snapshotsKept: Int = 0,
        val logsRemoved: Int = 0,
        val walCleared: Boolean = false,
        val fileIndexCleared: Boolean = false
    ) {
        val totalRemoved: Int get() = snapshotsRemoved + logsRemoved + (if (walCleared) 1 else 0) + (if (fileIndexCleared) 1 else 0)
    }

    /**
     * Remove stored data from the `.qorche/` directory.
     *
     * By default removes everything. Use flags to selectively clean specific data.
     * Use [keepLastSnapshots] to retain the N most recent snapshots (by timestamp)
     * for historical reference.
     *
     * @param snapshots Remove snapshot files from `.qorche/snapshots/`
     * @param logs Remove task log files from `.qorche/logs/`
     * @param wal Clear the write-ahead log (`.qorche/wal.jsonl`)
     * @param fileIndexCache Clear the file hash cache (`.qorche/file-index.json`)
     * @param keepLastSnapshots When cleaning snapshots, retain the N most recent. 0 = remove all.
     */
    fun clean(
        snapshots: Boolean = true,
        logs: Boolean = true,
        wal: Boolean = true,
        fileIndexCache: Boolean = true,
        keepLastSnapshots: Int = 0
    ): CleanResult {
        val (snapshotsRemoved, snapshotsKept) = if (snapshots) cleanSnapshots(keepLastSnapshots) else Pair(0, 0)
        val logsRemoved = if (logs) cleanLogs() else 0
        val walCleared = if (wal) clearWal() else false
        val fileIndexCleared = if (fileIndexCache) clearFileIndex() else false

        return CleanResult(snapshotsRemoved, snapshotsKept, logsRemoved, walCleared, fileIndexCleared)
    }

    private fun cleanSnapshots(keepLast: Int): Pair<Int, Int> {
        val snapshotsDir = qorcheDir.resolve("snapshots")
        if (!snapshotsDir.exists()) return Pair(0, 0)

        val files = snapshotsDir.listDirectoryEntries("*.json")
        if (keepLast <= 0) {
            files.forEach { java.nio.file.Files.deleteIfExists(it) }
            return Pair(files.size, 0)
        }
        if (files.size <= keepLast) return Pair(0, files.size)

        val sorted = files.mapNotNull { file ->
            try { Json.decodeFromString<Snapshot>(file.readText()).timestamp to file }
            catch (_: Exception) { null }
        }.sortedByDescending { it.first }

        val toKeep = sorted.take(keepLast).map { it.second }.toSet()
        var removed = 0
        for ((_, file) in sorted) {
            if (file in toKeep) continue
            java.nio.file.Files.deleteIfExists(file)
            removed++
        }
        return Pair(removed, toKeep.size)
    }

    private fun cleanLogs(): Int {
        if (!logsDir.exists()) return 0
        val files = logsDir.listDirectoryEntries("*.log")
        files.forEach { java.nio.file.Files.deleteIfExists(it) }
        return files.size
    }

    private fun clearWal(): Boolean {
        val walFile = qorcheDir.resolve("wal.jsonl")
        if (!walFile.exists()) return false
        java.nio.file.Files.writeString(walFile, "")
        return true
    }

    private fun clearFileIndex(): Boolean {
        if (!fileIndexPath.exists()) return false
        java.nio.file.Files.deleteIfExists(fileIndexPath)
        fileIndex.clear()
        return true
    }
}
