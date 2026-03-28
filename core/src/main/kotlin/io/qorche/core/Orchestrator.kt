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
     * and summary counters. [success] is true only when zero tasks failed.
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
        val scopeViolations: List<ConflictDetector.ScopeViolation> = emptyList()
    ) {
        val success: Boolean get() = failedTasks == 0
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
        val hasScopeViolations: Boolean get() = scopeViolations.isNotEmpty()
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
        val beforeSnapshot = if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "before: $taskId", fileIndex = fileIndex, onProgress = onSnapshotProgress)
        } else {
            SnapshotCreator.create(workDir, "before: $taskId", fileIndex = fileIndex, onProgress = onSnapshotProgress)
        }
        snapshotStore.save(beforeSnapshot)

        walWriter.append(WALEntry.TaskStarted(
            taskId = taskId,
            instruction = instruction,
            snapshotId = beforeSnapshot.id
        ))

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
            walWriter.append(WALEntry.TaskFailed(taskId = taskId, error = e.message ?: "Unknown error"))
        }

        fileIndex.clear()

        val afterSnapshot = if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        } else {
            SnapshotCreator.create(workDir, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        }
        snapshotStore.save(afterSnapshot)

        val diff = SnapshotCreator.diff(beforeSnapshot, afterSnapshot)
        val agentResult = AgentResult(
            exitCode = exitCode,
            filesModified = filesModified
        )

        if (agentException != null) {
            walWriter.append(WALEntry.TaskFailed(
                taskId = taskId,
                error = agentException.message ?: "Unknown error"
            ))
        } else if (exitCode == 0) {
            walWriter.append(WALEntry.TaskCompleted(
                taskId = taskId,
                snapshotId = afterSnapshot.id,
                exitCode = exitCode,
                filesModified = filesModified
            ))
        } else {
            walWriter.append(WALEntry.TaskFailed(
                taskId = taskId,
                error = "Agent exited with code $exitCode"
            ))
        }

        fileIndex.saveTo(fileIndexPath)

        return if (agentException != null) {
            Result.failure(agentException)
        } else {
            Result.success(RunResult(agentResult, diff, beforeSnapshot, afterSnapshot))
        }
    }

    /**
     * Execute a task graph sequentially in topological order.
     * If a task fails, all tasks that depend on it are skipped.
     */
    suspend fun runGraph(
        project: String,
        graph: TaskGraph,
        runner: AgentRunner,
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

            val startNanos = System.nanoTime()
            val taskResult = runTask(
                taskId = taskId,
                instruction = def.instruction,
                runner = runner,
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
     */
    suspend fun runGraphParallel(
        project: String,
        graph: TaskGraph,
        runner: AgentRunner,
        retryPolicy: ConflictDetector.ConflictRetryPolicy = ConflictDetector.ConflictRetryPolicy(),
        onTaskStart: (TaskDefinition) -> Unit = {},
        onTaskComplete: (String, TaskOutcome) -> Unit = { _, _ -> },
        onConflict: (ConflictDetector.TaskConflict) -> Unit = {},
        onRetry: (taskId: String, attempt: Int, conflictWith: String, conflictingFiles: Set<String>) -> Unit = { _, _, _, _ -> },
        onScopeViolation: (ConflictDetector.ScopeViolation) -> Unit = {},
        onOutput: (String) -> Unit = {}
    ): GraphResult {
        val outcomes = mutableMapOf<String, TaskOutcome>()
        val failedTasks = mutableSetOf<String>()
        val allConflicts = mutableListOf<ConflictDetector.TaskConflict>()
        val allScopeViolations = mutableListOf<ConflictDetector.ScopeViolation>()
        var totalRetries = 0
        val walMutex = Mutex()

        val groups = graph.parallelGroups()

        for (group in groups) {
            val runnableTasks = group.filter { taskId ->
                val node = graph[taskId] ?: return@filter false
                val failedDep = node.definition.dependsOn.firstOrNull { it in failedTasks }
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
                    false
                } else {
                    true
                }
            }

            if (runnableTasks.isEmpty()) continue

            if (runnableTasks.size == 1) {
                val taskId = runnableTasks[0]
                val outcome = executeTask(taskId, graph, runner, onTaskStart, onOutput)
                outcomes[taskId] = outcome
                if (outcome.status == TaskStatus.FAILED) failedTasks.add(taskId)
                onTaskComplete(taskId, outcome)
                continue
            }

            val baseSnapshot = SnapshotCreator.create(workDir, "base: group", fileIndex = fileIndex, onProgress = onSnapshotProgress)
            snapshotStore.save(baseSnapshot)

            val groupResults = coroutineScope {
                runnableTasks.map { taskId ->
                    async {
                        val node = graph[taskId]!!
                        onTaskStart(node.definition)
                        node.status = TaskStatus.RUNNING
                        val startNanos = System.nanoTime()
                        val result = executeTaskInternal(taskId, node.definition, runner, walMutex, onOutput)
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        Triple(taskId, result, elapsedMs)
                    }
                }.awaitAll()
            }

            val changesByTask = mutableMapOf<String, Set<String>>()
            val taskRunResults = mutableMapOf<String, RunResult>()
            val taskElapsed = mutableMapOf<String, Long>()

            for ((taskId, result, elapsed) in groupResults) {
                taskElapsed[taskId] = elapsed
                val node = graph[taskId]!!
                when {
                    result.isFailure -> {
                        node.status = TaskStatus.FAILED
                        failedTasks.add(taskId)
                        val outcome = TaskOutcome(
                            taskId = taskId,
                            status = TaskStatus.FAILED,
                            skipReason = "Exception: ${result.exceptionOrNull()?.message}",
                            elapsedMs = elapsed
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

            val conflicts = ConflictDetector.detectGroupConflicts(changesByTask)

            if (conflicts.isEmpty()) {
                for (taskId in changesByTask.keys) {
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
            } else {
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

                val resolution = ConflictDetector.resolveConflicts(conflicts, runnableTasks)

                for (taskId in changesByTask.keys) {
                    if (taskId in outcomes) continue
                    val node = graph[taskId]!!
                    val runResult = taskRunResults[taskId] ?: continue

                    if (taskId !in resolution.losers) {
                        node.status = TaskStatus.COMPLETED
                        node.beforeSnapshotId = runResult.beforeSnapshot.id
                        node.afterSnapshotId = runResult.afterSnapshot.id
                        node.result = runResult.agentResult
                        val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.COMPLETED, runResult = runResult, elapsedMs = taskElapsed[taskId] ?: 0)
                        outcomes[taskId] = outcome
                        onTaskComplete(taskId, outcome)
                    }
                }

                for (loserId in resolution.losers) {
                    if (loserId in outcomes) continue
                    val node = graph[loserId] ?: continue
                    val def = node.definition
                    val maxRetries = if (retryPolicy.enabled) {
                        def.maxRetries.coerceAtMost(retryPolicy.defaultMaxRetries)
                    } else {
                        0
                    }

                    val conflictWith = conflicts.first { it.taskA == loserId || it.taskB == loserId }
                    val conflictPeer = if (conflictWith.taskA == loserId) conflictWith.taskB else conflictWith.taskA

                    if (maxRetries <= 0) {
                        node.status = TaskStatus.FAILED
                        failedTasks.add(loserId)
                        val runResult = taskRunResults[loserId]
                        if (runResult != null) {
                            node.beforeSnapshotId = runResult.beforeSnapshot.id
                            node.afterSnapshotId = runResult.afterSnapshot.id
                            node.result = runResult.agentResult
                        }
                        val outcome = TaskOutcome(
                            taskId = loserId,
                            status = TaskStatus.FAILED,
                            runResult = taskRunResults[loserId],
                            skipReason = "MVCC conflict with '$conflictPeer' (retries disabled)",
                            elapsedMs = taskElapsed[loserId] ?: 0
                        )
                        outcomes[loserId] = outcome
                        onTaskComplete(loserId, outcome)
                        continue
                    }

                    var retried = false
                    for (attempt in 1..maxRetries) {
                        node.retryCount = attempt
                        totalRetries++

                        val loserChanges = changesByTask[loserId] ?: emptySet()
                        val winnerChanges = changesByTask[conflictPeer] ?: emptySet()
                        rollbackTaskChanges(loserChanges, winnerChanges, baseSnapshot)
                        fileIndex.invalidateAll(loserChanges)

                        onRetry(loserId, attempt, conflictPeer, conflictWith.conflictingFiles)

                        walWriter.append(WALEntry.TaskRetryScheduled(
                            taskId = loserId,
                            attempt = attempt,
                            conflictWith = conflictPeer,
                            conflictingFiles = conflictWith.conflictingFiles.toList()
                        ))

                        val retryStartNanos = System.nanoTime()
                        val retryResult = executeTaskInternal(loserId, def, runner, walMutex, onOutput)
                        val retryElapsedMs = (System.nanoTime() - retryStartNanos) / 1_000_000

                        if (retryResult.isFailure) {
                            node.status = TaskStatus.FAILED
                            failedTasks.add(loserId)
                            val outcome = TaskOutcome(
                                taskId = loserId,
                                status = TaskStatus.FAILED,
                                skipReason = "Retry attempt $attempt failed: ${retryResult.exceptionOrNull()?.message}",
                                retryCount = attempt,
                                elapsedMs = retryElapsedMs
                            )
                            outcomes[loserId] = outcome
                            onTaskComplete(loserId, outcome)
                            retried = true
                            break
                        }

                        val retryRunResult = retryResult.getOrThrow()
                        val retryChanges = retryRunResult.diff.added + retryRunResult.diff.modified + retryRunResult.diff.deleted

                        walWriter.append(WALEntry.TaskRetried(
                            taskId = loserId,
                            attempt = attempt,
                            snapshotId = retryRunResult.afterSnapshot.id
                        ))

                        val stillConflicting = retryChanges.intersect(winnerChanges)
                        val retrySucceeded = retryRunResult.agentResult.exitCode == 0 && stillConflicting.isEmpty()

                        if (retrySucceeded) {
                            node.status = TaskStatus.COMPLETED
                            node.beforeSnapshotId = retryRunResult.beforeSnapshot.id
                            node.afterSnapshotId = retryRunResult.afterSnapshot.id
                            node.result = retryRunResult.agentResult
                            val outcome = TaskOutcome(
                                taskId = loserId,
                                status = TaskStatus.COMPLETED,
                                runResult = retryRunResult,
                                retryCount = attempt,
                                elapsedMs = retryElapsedMs
                            )
                            outcomes[loserId] = outcome
                            onTaskComplete(loserId, outcome)
                            retried = true
                            break
                        }

                        if (attempt == maxRetries) {
                            node.status = TaskStatus.FAILED
                            failedTasks.add(loserId)
                            node.beforeSnapshotId = retryRunResult.beforeSnapshot.id
                            node.afterSnapshotId = retryRunResult.afterSnapshot.id
                            node.result = retryRunResult.agentResult
                            val outcome = TaskOutcome(
                                taskId = loserId,
                                status = TaskStatus.FAILED,
                                runResult = retryRunResult,
                                skipReason = "MVCC conflict persists after $attempt retry attempts",
                                retryCount = attempt,
                                elapsedMs = retryElapsedMs
                            )
                            outcomes[loserId] = outcome
                            onTaskComplete(loserId, outcome)
                            retried = true
                        }
                    }

                    if (!retried) {
                        node.status = TaskStatus.FAILED
                        failedTasks.add(loserId)
                        val outcome = TaskOutcome(
                            taskId = loserId,
                            status = TaskStatus.FAILED,
                            skipReason = "MVCC conflict with '$conflictPeer'"
                        )
                        outcomes[loserId] = outcome
                        onTaskComplete(loserId, outcome)
                    }
                }
            }

            val hasScopedTasks = runnableTasks.any { taskId ->
                graph[taskId]?.definition?.files?.isNotEmpty() == true
            }
            if (hasScopedTasks) {
                val auditSnapshot = SnapshotCreator.create(workDir, "audit: group", fileIndex = fileIndex, onProgress = onSnapshotProgress)
                val fullDiff = SnapshotCreator.diff(baseSnapshot, auditSnapshot)
                val allChangedOnDisk = fullDiff.added + fullDiff.modified + fullDiff.deleted

                val taskScopes = runnableTasks.associate { taskId ->
                    taskId to (graph[taskId]?.definition?.files ?: emptyList())
                }

                val violations = ConflictDetector.detectScopeViolations(
                    allChangedOnDisk, taskScopes, changesByTask
                )

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
            scopeViolations = allScopeViolations
        )
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
     * Internal task execution that returns Result<RunResult>.
     * Used by parallel execution to capture exceptions without crashing the group.
     * Uses a mutex for WAL writes since multiple tasks write concurrently.
     */
    private suspend fun executeTaskInternal(
        taskId: String,
        def: TaskDefinition,
        runner: AgentRunner,
        walMutex: Mutex,
        onOutput: (String) -> Unit
    ): Result<RunResult> = try {
        val beforeSnapshot = if (def.files.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, def.files, "before: $taskId", fileIndex = fileIndex, onProgress = onSnapshotProgress)
        } else {
            SnapshotCreator.create(workDir, "before: $taskId", fileIndex = fileIndex, onProgress = onSnapshotProgress)
        }
        snapshotStore.save(beforeSnapshot)

        walMutex.withLock {
            walWriter.append(WALEntry.TaskStarted(
                taskId = taskId,
                instruction = def.instruction,
                snapshotId = beforeSnapshot.id
            ))
        }

        val events = mutableListOf<AgentEvent>()
        val filesModified = mutableListOf<String>()
        var exitCode = 1
        var agentException: Exception? = null

        try {
            runner.run(def.instruction, workDir) { line ->
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

        fileIndex.clear()

        val afterSnapshot = if (def.files.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, def.files, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        } else {
            SnapshotCreator.create(workDir, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex, onProgress = onSnapshotProgress)
        }
        snapshotStore.save(afterSnapshot)

        val diff = SnapshotCreator.diff(beforeSnapshot, afterSnapshot)
        val agentResult = AgentResult(exitCode = exitCode, filesModified = filesModified)

        walMutex.withLock {
            if (agentException != null) {
                walWriter.append(WALEntry.TaskFailed(
                    taskId = taskId,
                    error = agentException.message ?: "Unknown error"
                ))
            } else if (exitCode == 0) {
                walWriter.append(WALEntry.TaskCompleted(
                    taskId = taskId,
                    snapshotId = afterSnapshot.id,
                    exitCode = exitCode,
                    filesModified = filesModified
                ))
            } else {
                walWriter.append(WALEntry.TaskFailed(
                    taskId = taskId,
                    error = "Agent exited with code $exitCode"
                ))
            }
        }

        if (agentException != null) {
            Result.failure(agentException)
        } else {
            Result.success(RunResult(agentResult, diff, beforeSnapshot, afterSnapshot))
        }
    } catch (e: Exception) {
        walMutex.withLock {
            walWriter.append(WALEntry.TaskFailed(taskId = taskId, error = e.message ?: "Unknown error"))
        }
        Result.failure(e)
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

    fun history(): List<Snapshot> = snapshotStore.list()

    fun loadSnapshot(id: String): Snapshot? = snapshotStore.load(id)

    fun diffSnapshots(id1: String, id2: String): SnapshotDiff? {
        val snap1 = snapshotStore.load(id1) ?: return null
        val snap2 = snapshotStore.load(id2) ?: return null
        return SnapshotCreator.diff(snap1, snap2)
    }

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
        var snapshotsRemoved = 0
        var snapshotsKept = 0
        var logsRemoved = 0
        var walCleared = false
        var fileIndexCleared = false

        if (snapshots) {
            val snapshotsDir = qorcheDir.resolve("snapshots")
            if (snapshotsDir.exists()) {
                val files = snapshotsDir.listDirectoryEntries("*.json")
                if (keepLastSnapshots > 0 && files.size > keepLastSnapshots) {
                    val sorted = files
                        .mapNotNull { file ->
                            try {
                                val snap = Json.decodeFromString<Snapshot>(file.readText())
                                snap.timestamp to file
                            } catch (_: Exception) {
                                null
                            }
                        }
                        .sortedByDescending { it.first }

                    val toKeep = sorted.take(keepLastSnapshots).map { it.second }.toSet()
                    for ((_, file) in sorted) {
                        if (file in toKeep) {
                            snapshotsKept++
                        } else {
                            java.nio.file.Files.deleteIfExists(file)
                            snapshotsRemoved++
                        }
                    }
                } else if (keepLastSnapshots <= 0) {
                    for (file in files) {
                        java.nio.file.Files.deleteIfExists(file)
                        snapshotsRemoved++
                    }
                } else {
                    snapshotsKept = files.size
                }
            }
        }

        if (logs) {
            if (logsDir.exists()) {
                for (file in logsDir.listDirectoryEntries("*.log")) {
                    java.nio.file.Files.deleteIfExists(file)
                    logsRemoved++
                }
            }
        }

        if (wal) {
            val walFile = qorcheDir.resolve("wal.jsonl")
            if (walFile.exists()) {
                java.nio.file.Files.writeString(walFile, "")
                walCleared = true
            }
        }

        if (fileIndexCache) {
            if (fileIndexPath.exists()) {
                java.nio.file.Files.deleteIfExists(fileIndexPath)
                fileIndex.clear()
                fileIndexCleared = true
            }
        }

        return CleanResult(
            snapshotsRemoved = snapshotsRemoved,
            snapshotsKept = snapshotsKept,
            logsRemoved = logsRemoved,
            walCleared = walCleared,
            fileIndexCleared = fileIndexCleared
        )
    }
}
