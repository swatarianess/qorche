package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.qorche.core.Orchestrator
import java.nio.file.Path

class CleanCommand : CliktCommand(name = "clean") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Remove stored data from the .qorche/ directory"

    private val all by option("--all", help = "Remove all stored data (default if no flags specified)").flag()
    private val snapshots by option("--snapshots", help = "Remove snapshot files").flag()
    private val logs by option("--logs", help = "Remove task log files").flag()
    private val wal by option("--wal", help = "Clear the write-ahead log").flag()
    private val cache by option("--cache", help = "Clear the file hash cache").flag()
    private val keepLast by option("--keep-last", help = "Keep the N most recent snapshots").int().default(0)

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val orchestrator = Orchestrator(workDir)

        val cleanAll = all || (!snapshots && !logs && !wal && !cache)

        val result = orchestrator.clean(
            snapshots = cleanAll || snapshots,
            logs = cleanAll || logs,
            wal = cleanAll || wal,
            fileIndexCache = cleanAll || cache,
            keepLastSnapshots = keepLast
        )

        if (result.totalRemoved == 0 && result.snapshotsKept == 0) {
            echo("Nothing to clean")
            return
        }

        if (result.snapshotsRemoved > 0) echo("${Terminal.green("Removed")} ${result.snapshotsRemoved} snapshot(s)")
        if (result.snapshotsKept > 0) echo("${Terminal.dim("Kept")} ${result.snapshotsKept} snapshot(s)")
        if (result.logsRemoved > 0) echo("${Terminal.green("Removed")} ${result.logsRemoved} log file(s)")
        if (result.walCleared) echo("${Terminal.green("Cleared")} write-ahead log")
        if (result.fileIndexCleared) echo("${Terminal.green("Cleared")} file hash cache")
    }
}
