package io.qorche.cli

import io.qorche.core.AgentRunner
import io.qorche.core.CycleDetectedException
import io.qorche.core.ExitCode
import io.qorche.core.HashAlgorithm
import io.qorche.core.Orchestrator
import io.qorche.core.RunnerConfig
import io.qorche.core.Snapshot
import io.qorche.core.SnapshotCreator
import io.qorche.core.SnapshotDiff
import io.qorche.core.TaskGraph
import io.qorche.core.TaskParseException
import io.qorche.core.TaskProject
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.VerifyConfig
import io.qorche.core.WALEntry
import java.nio.file.Path

// --- Utilities ---

internal fun formatElapsed(ms: Long): String = when {
    ms >= 1000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms}ms"
}

internal fun cliVersion(): String =
    object {}.javaClass.getResourceAsStream("/io/qorche/cli/version.txt")
        ?.bufferedReader()?.readText()?.trim() ?: "dev"

internal fun parseHashAlgorithm(raw: String?): HashAlgorithm = when (raw?.lowercase()) {
    "crc32c", "crc32" -> HashAlgorithm.CRC32C
    "sha256", "sha-256" -> HashAlgorithm.SHA256
    else -> HashAlgorithm.SHA1
}

internal fun isYamlFile(path: String): Boolean =
    path.endsWith(".yaml") || path.endsWith(".yml")

// --- Shared: YAML loading ---

sealed class TaskGraphLoadResult {
    data class Success(val project: TaskProject, val graph: TaskGraph) : TaskGraphLoadResult()
    data class ParseError(val message: String) : TaskGraphLoadResult()
}

fun loadTaskGraph(filePath: Path): TaskGraphLoadResult = try {
    val (project, graph) = TaskYamlParser.parseFileToGraph(filePath)
    TaskGraphLoadResult.Success(project, graph)
} catch (e: TaskParseException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Unknown parse error")
} catch (e: CycleDetectedException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Cycle detected")
} catch (e: IllegalArgumentException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Invalid task file")
}

// --- Verify ---

sealed class VerifyLoadResult {
    data class Success(val config: VerifyConfig) : VerifyLoadResult()
    data class ParseError(val message: String) : VerifyLoadResult()
    data class NoVerifySection(val fileName: String) : VerifyLoadResult()
}

fun loadVerifyConfig(filePath: Path): VerifyLoadResult = try {
    val project = TaskYamlParser.parseFile(filePath)
    val config = project.verify
    if (config != null) {
        VerifyLoadResult.Success(config)
    } else {
        VerifyLoadResult.NoVerifySection(filePath.fileName.toString())
    }
} catch (e: TaskParseException) {
    VerifyLoadResult.ParseError(e.message ?: "Unknown parse error")
}

sealed class VerifyOutcome {
    data class Passed(val elapsedMs: Long) : VerifyOutcome()
    data class Failed(val exitCode: Int, val elapsedMs: Long) : VerifyOutcome()
    data class Timeout(val timeoutSeconds: Long) : VerifyOutcome()
    data class Error(val message: String) : VerifyOutcome()
}

fun executeVerification(
    config: VerifyConfig,
    workDir: Path,
    onOutput: (String) -> Unit = {}
): VerifyOutcome {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val command = if (isWindows) {
        listOf("cmd", "/c", config.command)
    } else {
        listOf("sh", "-c", config.command)
    }

    val startTime = System.currentTimeMillis()
    return try {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line -> onOutput(line) }
        }

        val completed = process.waitFor(
            config.timeoutSeconds,
            java.util.concurrent.TimeUnit.SECONDS
        )
        val elapsed = System.currentTimeMillis() - startTime

        if (!completed) {
            process.destroyForcibly()
            return VerifyOutcome.Timeout(config.timeoutSeconds)
        }

        val exitCode = process.exitValue()
        if (exitCode == 0) {
            VerifyOutcome.Passed(elapsed)
        } else {
            VerifyOutcome.Failed(exitCode, elapsed)
        }
    } catch (e: java.io.IOException) {
        VerifyOutcome.Error(e.message ?: "I/O error")
    } catch (e: InterruptedException) {
        VerifyOutcome.Error(e.message ?: "Interrupted")
    }
}

// --- Replay ---

enum class WalEntryType {
    STARTED, COMPLETED, FAILED, CONFLICT,
    RETRY_SCHEDULED, RETRIED, SCOPE_VIOLATION, VERIFY
}

data class FormattedWalEntry(
    val type: WalEntryType,
    val taskId: String,
    val headline: String,
    val details: List<String> = emptyList()
)

data class ReplaySummary(
    val totalEntries: Int,
    val taskCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val retryCount: Int,
    val conflictCount: Int,
    val verifyCount: Int,
    val formattedEntries: List<FormattedWalEntry>
)

fun summarizeReplay(entries: List<WALEntry>, verbose: Boolean): ReplaySummary {
    var taskCount = 0
    var completedCount = 0
    var failedCount = 0
    var retryCount = 0
    var conflictCount = 0
    var verifyCount = 0
    val formatted = mutableListOf<FormattedWalEntry>()

    for (entry in entries) {
        formatted.add(formatWalEntry(entry, verbose))
        when (entry) {
            is WALEntry.TaskStarted -> taskCount++
            is WALEntry.TaskCompleted -> completedCount++
            is WALEntry.TaskFailed -> failedCount++
            is WALEntry.TaskRetryScheduled -> retryCount++
            is WALEntry.ConflictDetected -> conflictCount++
            is WALEntry.VerifyCompleted -> verifyCount++
            is WALEntry.TaskRetried -> {}
            is WALEntry.ScopeViolation -> {}
        }
    }

    return ReplaySummary(
        totalEntries = entries.size,
        taskCount = taskCount,
        completedCount = completedCount,
        failedCount = failedCount,
        retryCount = retryCount,
        conflictCount = conflictCount,
        verifyCount = verifyCount,
        formattedEntries = formatted
    )
}

private fun formatWalEntry(entry: WALEntry, verbose: Boolean): FormattedWalEntry = when (entry) {
    is WALEntry.TaskStarted -> formatTaskStarted(entry, verbose)
    is WALEntry.TaskCompleted -> formatTaskCompleted(entry, verbose)
    is WALEntry.TaskFailed -> FormattedWalEntry(WalEntryType.FAILED, entry.taskId, "Failed: ${entry.error}")
    is WALEntry.ConflictDetected -> formatConflict(entry, verbose)
    is WALEntry.TaskRetryScheduled -> formatRetryScheduled(entry, verbose)
    is WALEntry.TaskRetried -> formatRetried(entry, verbose)
    is WALEntry.ScopeViolation -> formatScopeViolation(entry, verbose)
    is WALEntry.VerifyCompleted -> formatVerifyCompleted(entry, verbose)
}

private fun formatTaskStarted(entry: WALEntry.TaskStarted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.STARTED, taskId = entry.taskId,
    headline = "Started at ${entry.timestamp}",
    details = if (verbose) listOf("Instruction: ${entry.instruction}", "Snapshot: ${entry.snapshotId.take(8)}") else emptyList()
)

private fun formatTaskCompleted(entry: WALEntry.TaskCompleted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.COMPLETED, taskId = entry.taskId,
    headline = "Completed (exit ${entry.exitCode})",
    details = if (verbose) buildList {
        add("Snapshot: ${entry.snapshotId.take(8)}")
        if (entry.filesModified.isNotEmpty()) add("Files: ${entry.filesModified.joinToString(", ")}")
    } else emptyList()
)

private fun formatConflict(entry: WALEntry.ConflictDetected, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.CONFLICT, taskId = entry.taskId,
    headline = "${entry.taskId} <-> ${entry.conflictingTaskId}",
    details = if (verbose) listOf(
        "Files: ${entry.conflictingFiles.joinToString(", ")}", "Base: ${entry.baseSnapshotId.take(8)}"
    ) else emptyList()
)

private fun formatRetryScheduled(entry: WALEntry.TaskRetryScheduled, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.RETRY_SCHEDULED, taskId = entry.taskId,
    headline = "Retry #${entry.attempt} (conflict with ${entry.conflictWith})",
    details = if (verbose) listOf("Files: ${entry.conflictingFiles.joinToString(", ")}") else emptyList()
)

private fun formatRetried(entry: WALEntry.TaskRetried, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.RETRIED, taskId = entry.taskId,
    headline = "Retried #${entry.attempt}",
    details = if (verbose) listOf("Snapshot: ${entry.snapshotId.take(8)}") else emptyList()
)

private fun formatScopeViolation(entry: WALEntry.ScopeViolation, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.SCOPE_VIOLATION, taskId = "SCOPE",
    headline = "Undeclared: ${entry.undeclaredFiles.joinToString(", ")}",
    details = if (verbose) listOf("Suspects: ${entry.suspectTaskIds.joinToString(", ")}") else emptyList()
)

private fun formatVerifyCompleted(entry: WALEntry.VerifyCompleted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.VERIFY, taskId = "VERIFY",
    headline = if (entry.success) "Group ${entry.groupIndex} passed"
        else "Group ${entry.groupIndex} failed (exit ${entry.exitCode})",
    details = if (verbose) listOf("Command: ${entry.command}") else emptyList()
)

// --- Consistency check ---

sealed class ConsistencyResult {
    data class Consistent(val snapshotIdPrefix: String) : ConsistencyResult()
    data class Diverged(
        val snapshotIdPrefix: String,
        val diff: SnapshotDiff
    ) : ConsistencyResult()
    object NoSnapshots : ConsistencyResult()
}

fun checkConsistency(snapshots: List<Snapshot>, workDir: Path): ConsistencyResult {
    if (snapshots.isEmpty()) return ConsistencyResult.NoSnapshots

    val latest = snapshots.first()
    val currentSnapshot = SnapshotCreator.create(workDir, "consistency-check")
    val diff = SnapshotCreator.diff(latest, currentSnapshot)

    return if (diff.totalChanges == 0) {
        ConsistencyResult.Consistent(latest.id.take(8))
    } else {
        ConsistencyResult.Diverged(latest.id.take(8), diff)
    }
}

// --- Plan ---

data class PlanTaskEntry(
    val index: Int,
    val id: String,
    val type: String,
    val dependencies: String,
    val files: String
)

data class PlanSummary(
    val projectName: String,
    val taskCount: Int,
    val executionOrder: List<PlanTaskEntry>,
    val parallelGroups: List<List<String>>,
    val warnings: List<PlanWarning>
)

fun buildPlanSummary(project: TaskProject, graph: TaskGraph): PlanSummary {
    val order = graph.topologicalSort()
    val entries = order.mapIndexed { i, taskId ->
        val def = graph[taskId]?.definition ?: return@mapIndexed null
        val deps = if (def.dependsOn.isEmpty()) "no dependencies"
            else "depends on: ${def.dependsOn.joinToString(", ")}"
        val files = if (def.files.isEmpty()) ""
            else " [${def.files.joinToString(", ")}]"
        PlanTaskEntry(
            index = i + 1,
            id = def.id,
            type = def.type.name.lowercase(),
            dependencies = deps,
            files = files
        )
    }.filterNotNull()

    val groups = graph.parallelGroups()

    return PlanSummary(
        projectName = project.project,
        taskCount = project.tasks.size,
        executionOrder = entries,
        parallelGroups = groups.filter { it.size > 1 },
        warnings = detectScopeOverlaps(project.tasks)
    )
}

// --- Run result interpretation ---

fun interpretGraphResult(result: Orchestrator.GraphResult): ExitCode = when {
    result.success -> ExitCode.SUCCESS
    result.hasConflicts -> ExitCode.CONFLICT
    else -> ExitCode.TASK_FAILURE
}

fun formatGraphTextSummary(result: Orchestrator.GraphResult, elapsedMs: Long): List<String> =
    buildList {
        add("")
        if (result.hasConflicts) {
            add("Conflicts: ${result.conflicts.size} detected")
        }
        if (result.verifyResults.isNotEmpty()) {
            val passed = result.verifyResults.count { it.success }
            val failed = result.verifyResults.size - passed
            add("Verify: $passed passed, $failed failed")
        }
        add("Results: ${result.completedTasks} completed, ${result.failedTasks} failed, ${result.skippedTasks} skipped")
        add("Logs: .qorche/logs/")
        add("Total time: ${elapsedMs}ms")
    }

// --- Single-task result formatting ---

fun formatSingleTaskText(result: Orchestrator.RunResult, elapsedMs: Long): List<String> = buildList {
    add("")
    val diff = result.diff
    if (diff.totalChanges > 0) {
        add("Changes: ${diff.summary()}")
        for (f in diff.added) add("  + $f")
        for (f in diff.modified) add("  ~ $f")
        for (f in diff.deleted) add("  - $f")
    } else {
        add("No file changes detected")
    }
    add("Completed (exit ${result.agentResult.exitCode}) in ${elapsedMs}ms")
}

fun buildSingleTaskGraphResult(
    result: Orchestrator.RunResult
): Orchestrator.GraphResult {
    val succeeded = result.agentResult.exitCode == 0
    return Orchestrator.GraphResult(
        project = "cli-run",
        taskResults = mapOf(
            "cli-run" to Orchestrator.TaskOutcome(
                taskId = "cli-run",
                status = if (succeeded) TaskStatus.COMPLETED else TaskStatus.FAILED,
                runResult = result
            )
        ),
        totalTasks = 1,
        completedTasks = if (succeeded) 1 else 0,
        failedTasks = if (succeeded) 0 else 1,
        skippedTasks = 0
    )
}

// --- Graph run preparation ---

sealed class GraphRunSetup {
    data class Ready(
        val project: TaskProject,
        val graph: TaskGraph,
        val runners: Map<String, AgentRunner>,
        val defaultRunner: AgentRunner
    ) : GraphRunSetup()

    data class Failed(
        val message: String,
        val exitCode: ExitCode
    ) : GraphRunSetup()
}

fun prepareGraphRun(
    filePath: Path,
    buildRunners: (Map<String, RunnerConfig>) -> Map<String, AgentRunner>,
    fallbackRunner: () -> AgentRunner
): GraphRunSetup {
    val (project, graph) = when (val loaded = loadTaskGraph(filePath)) {
        is TaskGraphLoadResult.Success -> loaded.project to loaded.graph
        is TaskGraphLoadResult.ParseError -> return GraphRunSetup.Failed(loaded.message, ExitCode.CONFIG_ERROR)
    }

    val runners = try {
        buildRunners(project.runners)
    } catch (e: IllegalArgumentException) {
        return GraphRunSetup.Failed(e.message ?: "Invalid runner configuration", ExitCode.CONFIG_ERROR)
    }

    val defaultRunner = if (project.defaultRunner != null) {
        runners[project.defaultRunner]
            ?: return GraphRunSetup.Failed(
                "default_runner '${project.defaultRunner}' not found in runners",
                ExitCode.CONFIG_ERROR
            )
    } else {
        fallbackRunner()
    }

    return GraphRunSetup.Ready(project, graph, runners, defaultRunner)
}

// --- History formatting ---

data class HistoryLine(
    val idPrefix: String,
    val timestamp: String,
    val description: String,
    val fileCount: Int
)

data class HistoryOutput(
    val lines: List<HistoryLine>,
    val truncatedCount: Int
)

fun formatHistory(snapshots: List<Snapshot>, limit: Int?): HistoryOutput {
    val shown = if (limit != null) snapshots.take(limit) else snapshots
    val lines = shown.map { snap ->
        HistoryLine(
            idPrefix = snap.id.take(8),
            timestamp = snap.timestamp.toString(),
            description = snap.description,
            fileCount = snap.fileHashes.size
        )
    }
    val truncated = if (limit != null && snapshots.size > limit) snapshots.size - limit else 0
    return HistoryOutput(lines, truncated)
}

// --- Diff resolution ---

sealed class DiffResolution {
    data class Resolved(val fullId1: String, val fullId2: String) : DiffResolution()
    data class NoComparison(val message: String) : DiffResolution()
}

fun resolveSnapshotIds(
    id1: String,
    id2: String?,
    snapshots: List<Snapshot>
): DiffResolution {
    val resolvedId2 = id2 ?: run {
        val snap = snapshots.find { it.id.startsWith(id1) }
        snap?.parentId ?: return DiffResolution.NoComparison(
            "Cannot determine comparison snapshot. Provide two IDs."
        )
    }

    val fullId1 = snapshots.find { it.id.startsWith(id1) }?.id
        ?: return DiffResolution.NoComparison("Snapshot with prefix '$id1' not found.")
    val fullId2 = snapshots.find { it.id.startsWith(resolvedId2) }?.id
        ?: return DiffResolution.NoComparison("Snapshot with prefix '$resolvedId2' not found.")

    return DiffResolution.Resolved(fullId1, fullId2)
}
