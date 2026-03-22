package io.qorche.core

import kotlinx.coroutines.flow.toList
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Coordinates agent execution with snapshot lifecycle and WAL logging.
 *
 * For each agent run:
 * 1. Load FileIndex from disk (warm cache)
 * 2. Take a before-snapshot
 * 3. Log TaskStarted to WAL
 * 4. Run the agent
 * 5. Take an after-snapshot
 * 6. Compute diff
 * 7. Log TaskCompleted/TaskFailed to WAL
 * 8. Persist FileIndex and snapshots
 */
class Orchestrator(private val workDir: Path) {

    private val qorcheDir: Path = workDir.resolve(".qorche").also { it.createDirectories() }
    private val snapshotStore = SnapshotStore(qorcheDir.resolve("snapshots"))
    private val walWriter = WALWriter(qorcheDir.resolve("wal.jsonl"))
    private val fileIndex = FileIndex()
    private val fileIndexPath = qorcheDir.resolve("file-index.json")

    init {
        fileIndex.loadFrom(fileIndexPath)
    }

    data class RunResult(
        val agentResult: AgentResult,
        val diff: SnapshotDiff,
        val beforeSnapshot: Snapshot,
        val afterSnapshot: Snapshot
    )

    suspend fun runTask(
        taskId: String,
        instruction: String,
        runner: AgentRunner,
        scopePaths: List<String> = emptyList(),
        onOutput: (String) -> Unit = {}
    ): RunResult {
        // 1. Before snapshot
        val beforeSnapshot = if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "before: $taskId", fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "before: $taskId", fileIndex = fileIndex)
        }
        snapshotStore.save(beforeSnapshot)

        // 2. Log start
        walWriter.append(WALEntry.TaskStarted(
            taskId = taskId,
            instruction = instruction,
            snapshotId = beforeSnapshot.id
        ))

        // 3. Run agent
        val events = mutableListOf<AgentEvent>()
        val filesModified = mutableListOf<String>()
        var exitCode = 1

        try {
            runner.run(instruction, workDir, onOutput).toList(events)

            for (event in events) {
                when (event) {
                    is AgentEvent.FileModified -> filesModified.add(event.path)
                    is AgentEvent.Completed -> exitCode = event.exitCode
                    else -> {}
                }
            }
        } catch (e: Exception) {
            walWriter.append(WALEntry.TaskFailed(taskId = taskId, error = e.message ?: "Unknown error"))
            throw e
        }

        // 4. After snapshot
        val afterSnapshot = if (scopePaths.isNotEmpty()) {
            SnapshotCreator.createScoped(workDir, scopePaths, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
        } else {
            SnapshotCreator.create(workDir, "after: $taskId",
                parentId = beforeSnapshot.id, fileIndex = fileIndex)
        }
        snapshotStore.save(afterSnapshot)

        // 5. Compute diff
        val diff = SnapshotCreator.diff(beforeSnapshot, afterSnapshot)

        // 6. Log completion
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

        // 7. Persist file index
        fileIndex.saveTo(fileIndexPath)

        return RunResult(agentResult, diff, beforeSnapshot, afterSnapshot)
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
