package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.qorche.core.Orchestrator
import io.qorche.core.WALEntry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.fileSize

class StatusCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
) : CliktCommand(name = "status") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Show workspace state: snapshots, WAL entries, logs, last activity"

    override fun run() {
        val workDir = workDirProvider()
        val qorcheDir = workDir.resolve(".qorche")

        if (!qorcheDir.exists()) {
            echo("No .qorche/ directory found. Run a task first.")
            return
        }

        val orchestrator = Orchestrator(workDir)

        val snapshots = orchestrator.history()
        val walEntries = orchestrator.walEntries()
        val logsDir = qorcheDir.resolve("logs")
        val logFiles = if (logsDir.exists()) logsDir.listDirectoryEntries("*.log") else emptyList()
        val indexPath = qorcheDir.resolve("file-index.json")

        echo("Qorche workspace: .qorche/")
        echo("  Snapshots: ${snapshots.size} stored")
        echo("  WAL entries: ${walEntries.size}")
        echo("  Logs: ${logFiles.size} task logs")

        if (indexPath.exists()) {
            val sizeKb = indexPath.fileSize() / 1024
            echo("  File index: ${sizeKb}KB")
        }

        val lastCompleted = walEntries.filterIsInstance<WALEntry.TaskCompleted>().lastOrNull()
        val lastFailed = walEntries.filterIsInstance<WALEntry.TaskFailed>().lastOrNull()
        val lastEvent = listOfNotNull(lastCompleted, lastFailed).maxByOrNull { it.timestamp }

        if (lastEvent != null) {
            echo("")
            echo("Last activity:")
            when (lastEvent) {
                is WALEntry.TaskCompleted -> echo("  ${lastEvent.timestamp} — ${lastEvent.taskId} completed")
                is WALEntry.TaskFailed -> echo("  ${lastEvent.timestamp} — ${lastEvent.taskId} failed: ${lastEvent.error}")
                else -> {}
            }
        }

        val conflicts = walEntries.filterIsInstance<WALEntry.ConflictDetected>()
        if (conflicts.isNotEmpty()) {
            echo("  Conflicts: ${conflicts.size} detected")
        }

        val retries = walEntries.filterIsInstance<WALEntry.TaskRetryScheduled>()
        if (retries.isNotEmpty()) {
            echo("  Retries: ${retries.size} attempted")
        }
    }
}
