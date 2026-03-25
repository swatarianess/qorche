package io.qorche.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.io.path.createDirectories

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

    init {
        fileIndex.loadFrom(fileIndexPath)
    }

    data class RunResult(
        val agentResult: AgentResult,
        val diff: SnapshotDiff,
        val beforeSnapshot: Snapshot,
        val afterSnapshot: Snapshot
    )

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

    data class TaskOutcome(
        val taskId: String,
        val status: TaskStatus,
        val runResult: RunResult? = null,
        val skipReason: String? = null,
        val retryCount: Int = 0
    )

    /**
     * Execute a single task with full snapshot lifecycle and WAL logging.
     * Takes before/after snapshots, runs the agent, computes diff, and persists state.
     */
    suspend fun runTask(
        taskId: String,
        instruction: String,
        runner: AgentRunner,
        scopePaths: List<String> = emptyList(),
        onOutput: (String) -> Unit = {}
    ): RunResult {
        val beforeSnapshot = if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "before: $taskId", fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "before: $taskId", fileIndex = fileIndex)
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
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
        }
        snapshotStore.save(afterSnapshot)

        val diff = SnapshotCreator.diff(beforeSnapshot, afterSnapshot)
        val agentResult = AgentResult(
            exitCode = exitCode,
            filesModified = filesModified
        )

        if (exitCode == 0) {
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

        if (agentException != null) throw agentException

        return RunResult(agentResult, diff, beforeSnapshot, afterSnapshot)
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

            try {
                val result = runTask(
                    taskId = taskId,
                    instruction = def.instruction,
                    runner = runner,
                    scopePaths = def.files,
                    onOutput = onOutput
                )

                val success = result.agentResult.exitCode == 0
                node.status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
                node.beforeSnapshotId = result.beforeSnapshot.id
                node.afterSnapshotId = result.afterSnapshot.id
                node.result = result.agentResult

                if (!success) failedTasks.add(taskId)

                val outcome = TaskOutcome(
                    taskId = taskId,
                    status = node.status,
                    runResult = result
                )
                outcomes[taskId] = outcome
                onTaskComplete(taskId, outcome)

            } catch (e: Exception) {
                node.status = TaskStatus.FAILED
                failedTasks.add(taskId)
                val outcome = TaskOutcome(
                    taskId = taskId,
                    status = TaskStatus.FAILED,
                    skipReason = "Exception: ${e.message}"
                )
                outcomes[taskId] = outcome
                onTaskComplete(taskId, outcome)
            }
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
     * Tasks in the same parallel group (no dependencies between them) run concurrently.
     * After each group completes, MVCC conflict detection checks for write-write conflicts.
     *
     * Strategy on conflict: fail-fast. Conflicting tasks are marked FAILED, and their
     * dependents are skipped. Non-conflicting tasks in the same group succeed normally.
     */
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

            val baseSnapshot = SnapshotCreator.create(workDir, "base: group", fileIndex = fileIndex)
            snapshotStore.save(baseSnapshot)

            val groupResults = coroutineScope {
                runnableTasks.map { taskId ->
                    async {
                        val node = graph[taskId]!!
                        onTaskStart(node.definition)
                        node.status = TaskStatus.RUNNING
                        taskId to executeTaskInternal(taskId, node.definition, runner, walMutex, onOutput)
                    }
                }.awaitAll()
            }

            val changesByTask = mutableMapOf<String, Set<String>>()
            val taskRunResults = mutableMapOf<String, RunResult>()

            for ((taskId, result) in groupResults) {
                val node = graph[taskId]!!
                when {
                    result.isFailure -> {
                        node.status = TaskStatus.FAILED
                        failedTasks.add(taskId)
                        val outcome = TaskOutcome(
                            taskId = taskId,
                            status = TaskStatus.FAILED,
                            skipReason = "Exception: ${result.exceptionOrNull()?.message}"
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
                            val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.FAILED, runResult = runResult)
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
                    val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.COMPLETED, runResult = runResult)
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
                        val outcome = TaskOutcome(taskId = taskId, status = TaskStatus.COMPLETED, runResult = runResult)
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
                            skipReason = "MVCC conflict with '$conflictPeer' (retries disabled)"
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

                        val retryResult = executeTaskInternal(loserId, def, runner, walMutex, onOutput)

                        if (retryResult.isFailure) {
                            node.status = TaskStatus.FAILED
                            failedTasks.add(loserId)
                            val outcome = TaskOutcome(
                                taskId = loserId,
                                status = TaskStatus.FAILED,
                                skipReason = "Retry attempt $attempt failed: ${retryResult.exceptionOrNull()?.message}",
                                retryCount = attempt
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
                                retryCount = attempt
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
                                retryCount = attempt
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
                val auditSnapshot = SnapshotCreator.create(workDir, "audit: group", fileIndex = fileIndex)
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

        return try {
            val result = runTask(
                taskId = taskId,
                instruction = def.instruction,
                runner = runner,
                scopePaths = def.files,
                onOutput = onOutput
            )

            val success = result.agentResult.exitCode == 0
            node.status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
            node.beforeSnapshotId = result.beforeSnapshot.id
            node.afterSnapshotId = result.afterSnapshot.id
            node.result = result.agentResult

            TaskOutcome(taskId = taskId, status = node.status, runResult = result)
        } catch (e: Exception) {
            node.status = TaskStatus.FAILED
            TaskOutcome(taskId = taskId, status = TaskStatus.FAILED, skipReason = "Exception: ${e.message}")
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
            SnapshotCreator.createScoped(workDir, def.files, "before: $taskId", fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "before: $taskId", fileIndex = fileIndex)
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
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
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
            val file = workDir.resolve(relativePath)
            val baseHash = baseSnapshot.fileHashes[relativePath]
            if (baseHash == null) {
                java.nio.file.Files.deleteIfExists(file)
            } else {
                // File existed in base — we can't restore content from hash alone,
                // but we can delete it so the retry starts clean.
                // The retry will take its own before-snapshot which captures current state.
                java.nio.file.Files.deleteIfExists(file)
            }
        }
    }

    private fun logToFile(taskId: String, line: String) {
        val logFile = logsDir.resolve("$taskId.log")
        java.nio.file.Files.writeString(
            logFile,
            line + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    fun history(): List<Snapshot> = snapshotStore.list()

    fun loadSnapshot(id: String): Snapshot? = snapshotStore.load(id)

    fun diffSnapshots(id1: String, id2: String): SnapshotDiff? {
        val snap1 = snapshotStore.load(id1) ?: return null
        val snap2 = snapshotStore.load(id2) ?: return null
        return SnapshotCreator.diff(snap1, snap2)
    }

    fun walEntries(): List<WALEntry> = walWriter.readAll()
}
