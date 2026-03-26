package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class LogsCommand : CliktCommand(name = "logs") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List task logs or view a specific task's output"

    private val taskId by argument(help = "Task ID to view log for (omit to list all)").optional()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val logsDir = workDir.resolve(".qorche/logs")

        if (!logsDir.exists()) {
            echo("No logs found. Run a task first.")
            return
        }

        if (taskId != null) {
            showTaskLog(logsDir)
        } else {
            listLogs(logsDir)
        }
    }

    private fun listLogs(logsDir: Path) {
        val logFiles = logsDir.listDirectoryEntries("*.log").sortedBy { it.name }

        if (logFiles.isEmpty()) {
            echo("No log files found.")
            return
        }

        echo("Task logs (.qorche/logs/):")
        for (log in logFiles) {
            val name = log.name.removeSuffix(".log")
            val sizeKb = log.fileSize() / 1024
            echo("  $name (${sizeKb}KB)")
        }
    }

    private fun showTaskLog(logsDir: Path) {
        val logFile = logsDir.resolve("$taskId.log")

        if (!logFile.exists()) {
            val available = logsDir.listDirectoryEntries("*.log").map { it.name.removeSuffix(".log") }
            echo("No log found for task '$taskId'.", err = true)
            if (available.isNotEmpty()) {
                echo("Available: ${available.joinToString(", ")}", err = true)
            }
            return
        }

        echo(logFile.readText())
    }
}
