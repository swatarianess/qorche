package io.qorche.core

/**
 * Detects write-write conflicts between concurrent agent runs.
 *
 * Given a base snapshot and multiple modified snapshots (from parallel agents),
 * identifies files that were modified by more than one agent since the base.
 */
object ConflictDetector {

    data class ConflictRetryPolicy(
        val defaultMaxRetries: Int = 1,
        val enabled: Boolean = true
    )

    data class ConflictReport(
        val conflicts: Set<String>,
        val agentAOnly: Set<String>,
        val agentBOnly: Set<String>
    ) {
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    }

    /**
     * Pairwise conflict detection between two agent snapshots against a shared base.
     */
    fun detectConflicts(
        base: Snapshot,
        agentA: Snapshot,
        agentB: Snapshot
    ): ConflictReport {
        val diffA = SnapshotCreator.diff(base, agentA)
        val diffB = SnapshotCreator.diff(base, agentB)

        val changedByA = diffA.added + diffA.modified + diffA.deleted
        val changedByB = diffB.added + diffB.modified + diffB.deleted

        val conflicts = changedByA.intersect(changedByB)

        return ConflictReport(
            conflicts = conflicts,
            agentAOnly = changedByA - conflicts,
            agentBOnly = changedByB - conflicts
        )
    }

    /**
     * Conflict between a specific task and its file set.
     */
    data class TaskConflict(
        val taskA: String,
        val taskB: String,
        val conflictingFiles: Set<String>
    )

    /**
     * Detect conflicts across all tasks that ran in a parallel group.
     * Each entry maps taskId to the set of files it changed.
     *
     * Complexity: O(n² × m) where n = tasks in group, m = average changed files per task.
     * Each pair comparison is a hash set intersection (O(min(|A|, |B|))).
     * At typical group sizes (2-10 tasks), this is sub-millisecond.
     * At 50 tasks: 1,225 pairs — still fast since each comparison is a set intersection.
     * Beyond ~200 tasks per group, consider a file→task inverted index instead.
     *
     * Returns all pairwise conflicts found.
     */
    fun detectGroupConflicts(
        changesByTask: Map<String, Set<String>>
    ): List<TaskConflict> {
        val conflicts = mutableListOf<TaskConflict>()
        val taskIds = changesByTask.keys.toList()

        for (i in taskIds.indices) {
            for (j in i + 1 until taskIds.size) {
                val taskA = taskIds[i]
                val taskB = taskIds[j]
                val changedByA = changesByTask[taskA] ?: emptySet()
                val changedByB = changesByTask[taskB] ?: emptySet()
                val overlap = changedByA.intersect(changedByB)
                if (overlap.isNotEmpty()) {
                    conflicts.add(TaskConflict(taskA, taskB, overlap))
                }
            }
        }

        return conflicts
    }

    /**
     * Result of conflict resolution: which tasks keep their changes (winners)
     * and which must retry (losers). A task that is a loser in any conflict
     * must retry, even if it won against a different task.
     */
    data class ConflictResolution(
        val winners: Set<String>,
        val losers: Set<String>,
        val conflicts: List<TaskConflict>
    )

    /**
     * Resolve conflicts by selecting winners based on group order.
     * Earlier tasks in [groupOrder] win over later ones.
     * A task that loses any conflict must retry, even if it won others.
     */
    fun resolveConflicts(
        conflicts: List<TaskConflict>,
        groupOrder: List<String>
    ): ConflictResolution {
        val winners = mutableSetOf<String>()
        val losers = mutableSetOf<String>()

        for (conflict in conflicts) {
            val indexA = groupOrder.indexOf(conflict.taskA)
            val indexB = groupOrder.indexOf(conflict.taskB)
            if (indexA < indexB) {
                winners.add(conflict.taskA)
                losers.add(conflict.taskB)
            } else {
                winners.add(conflict.taskB)
                losers.add(conflict.taskA)
            }
        }

        return ConflictResolution(
            winners = winners - losers,
            losers = losers,
            conflicts = conflicts
        )
    }

    /**
     * Scope violation detected at the group level.
     *
     * IMPORTANT: We cannot attribute undeclared writes to a specific task.
     * The audit snapshot is a group-level diff (pre-group baseline vs post-group state).
     * We only know that files outside declared scopes changed — not which task wrote them.
     * [suspectTaskIds] lists all tasks in the group that could be responsible.
     */
    data class ScopeViolation(
        val undeclaredFiles: Set<String>,
        val suspectTaskIds: List<String>,
        val declaredScopes: Map<String, List<String>>
    )

    /**
     * Detect scope violations by comparing actual filesystem changes against
     * the union of all tasks' declared file scopes in a parallel group.
     *
     * This is a GROUP-LEVEL check, not per-task. We take a full-repo diff
     * (pre-group baseline vs post-group state) and identify files that changed
     * but aren't covered by any task's declared scope. We cannot determine
     * which specific task wrote them — only that something in the group did.
     *
     * External writes (editor saves, git hooks, other processes) during
     * execution will also appear as violations. This is by design — any
     * unaccounted filesystem mutation is worth flagging.
     *
     * @param allChangedFiles All files changed on disk since the group started
     * @param taskScopes Map of taskId to declared scope paths
     * @param changesByTask Map of taskId to files detected as changed by scoped snapshots
     */
    fun detectScopeViolations(
        allChangedFiles: Set<String>,
        taskScopes: Map<String, List<String>>,
        changesByTask: Map<String, Set<String>>
    ): List<ScopeViolation> {
        val allDeclaredChanges = changesByTask.values.flatten().toSet()
        val unaccountedFiles = allChangedFiles - allDeclaredChanges

        if (unaccountedFiles.isEmpty()) return emptyList()

        val suspectTaskIds = taskScopes.keys.filter { it in changesByTask }

        return listOf(ScopeViolation(
            undeclaredFiles = unaccountedFiles,
            suspectTaskIds = suspectTaskIds,
            declaredScopes = taskScopes
        ))
    }
}
