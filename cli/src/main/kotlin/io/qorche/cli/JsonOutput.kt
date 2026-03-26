package io.qorche.cli

import io.qorche.core.ConflictDetector
import io.qorche.core.Orchestrator
import io.qorche.core.TaskGraph
import io.qorche.core.TaskDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true }

@Serializable
data class RunOutput(
    val version: String,
    val project: String,
    val success: Boolean,
    val wallTimeMs: Long,
    val tasks: List<TaskOutput>,
    val conflicts: List<ConflictOutput>,
    val scopeViolations: List<ScopeViolationOutput>,
    val retriedTasks: Int,
    val groups: List<GroupOutput>
)

@Serializable
data class TaskOutput(
    val id: String,
    val status: String,
    val retryCount: Int = 0,
    val filesChanged: List<String> = emptyList(),
    val skipReason: String? = null
)

@Serializable
data class ConflictOutput(
    val taskA: String,
    val taskB: String,
    val files: List<String>
)

@Serializable
data class ScopeViolationOutput(
    val undeclaredFiles: List<String>,
    val suspectTaskIds: List<String>
)

@Serializable
data class GroupOutput(
    val index: Int,
    val taskIds: List<String>,
    val parallel: Boolean
)

@Serializable
data class PlanOutput(
    val version: String,
    val project: String,
    val tasks: Int,
    val groups: List<GroupOutput>,
    val warnings: List<PlanWarning>
)

@Serializable
data class PlanWarning(
    val type: String,
    val taskA: String,
    val taskB: String,
    val overlappingFiles: List<String>,
    val message: String
)

fun Orchestrator.GraphResult.toJson(project: String, version: String, wallTimeMs: Long): String {
    val output = RunOutput(
        version = version,
        project = project,
        success = success,
        wallTimeMs = wallTimeMs,
        tasks = taskResults.values.map { outcome ->
            val changed = outcome.runResult?.diff?.let { diff ->
                (diff.added + diff.modified).sorted()
            } ?: emptyList()
            TaskOutput(
                id = outcome.taskId,
                status = outcome.status.name,
                retryCount = outcome.retryCount,
                filesChanged = changed,
                skipReason = outcome.skipReason
            )
        },
        conflicts = conflicts.map { conflict ->
            ConflictOutput(
                taskA = conflict.taskA,
                taskB = conflict.taskB,
                files = conflict.conflictingFiles.sorted()
            )
        },
        scopeViolations = scopeViolations.map { violation ->
            ScopeViolationOutput(
                undeclaredFiles = violation.undeclaredFiles.sorted(),
                suspectTaskIds = violation.suspectTaskIds
            )
        },
        retriedTasks = retriedTasks,
        groups = emptyList()
    )
    return json.encodeToString(output)
}

fun buildPlanJson(
    project: String,
    version: String,
    graph: TaskGraph,
    definitions: List<TaskDefinition>
): String {
    val groups = graph.parallelGroups()
    val warnings = detectScopeOverlaps(definitions)

    val output = PlanOutput(
        version = version,
        project = project,
        tasks = definitions.size,
        groups = groups.mapIndexed { index, taskIds ->
            GroupOutput(
                index = index,
                taskIds = taskIds,
                parallel = taskIds.size > 1
            )
        },
        warnings = warnings
    )
    return json.encodeToString(output)
}

fun detectScopeOverlaps(definitions: List<TaskDefinition>): List<PlanWarning> {
    val warnings = mutableListOf<PlanWarning>()
    for (i in definitions.indices) {
        for (j in i + 1 until definitions.size) {
            val a = definitions[i]
            val b = definitions[j]
            if (a.files.isEmpty() || b.files.isEmpty()) continue
            if (a.dependsOn.contains(b.id) || b.dependsOn.contains(a.id)) continue

            val overlap = a.files.intersect(b.files.toSet())
            if (overlap.isNotEmpty()) {
                warnings.add(PlanWarning(
                    type = "scope_overlap",
                    taskA = a.id,
                    taskB = b.id,
                    overlappingFiles = overlap.sorted(),
                    message = "These tasks may conflict — consider splitting file scopes"
                ))
            }
        }
    }
    return warnings
}
