package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.qorche.agent.ClaudeCodeAdapter
import io.qorche.core.AgentEvent
import io.qorche.core.Orchestrator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class QorcheCommand : CliktCommand(name = "qorche") {
    override fun run() = Unit

    init {
        subcommands(RunCommand(), HistoryCommand(), DiffCommand(), VersionCommand())
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val instruction by argument()
    private val verbose by option("--verbose", "-v").flag()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val orchestrator = Orchestrator(workDir)
        val runner = ClaudeCodeAdapter()
        val startTime = System.currentTimeMillis()

        echo("Starting: $instruction")

        runBlocking {
            val result = orchestrator.runTask(
                taskId = "cli-run",
                instruction = instruction,
                runner = runner
            ) { line ->
                if (verbose) echo("[agent] $line")
            }

            val elapsed = System.currentTimeMillis() - startTime
            val diff = result.diff

            echo("")
            if (diff.totalChanges > 0) {
                echo("Changes: ${diff.summary()}")
                for (f in diff.added) echo("  + $f")
                for (f in diff.modified) echo("  ~ $f")
                for (f in diff.deleted) echo("  - $f")
            } else {
                echo("No file changes detected")
            }
            echo("Completed (exit ${result.agentResult.exitCode}) in ${elapsed}ms")
        }
    }
}

class HistoryCommand : CliktCommand(name = "history") {
    private val limit by option("--limit", "-n").int()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val orchestrator = Orchestrator(workDir)
        val snapshots = orchestrator.history()

        if (snapshots.isEmpty()) {
            echo("No snapshots found. Run a task first.")
            return
        }

        val shown = if (limit != null) snapshots.take(limit!!) else snapshots
        for (snap in shown) {
            echo("${snap.id.take(8)}  ${snap.timestamp}  ${snap.description}  (${snap.fileHashes.size} files)")
        }
        if (limit != null && snapshots.size > limit!!) {
            echo("... and ${snapshots.size - limit!!} more (use --limit to show more)")
        }
    }
}

class DiffCommand : CliktCommand(name = "diff") {
    private val id1 by argument()
    private val id2 by argument().optional()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val orchestrator = Orchestrator(workDir)

        // If only one ID given, diff against its parent or latest
        val resolvedId2 = id2 ?: run {
            val snap = orchestrator.history().find { it.id.startsWith(id1) }
            snap?.parentId ?: run {
                echo("Cannot determine comparison snapshot. Provide two IDs.", err = true)
                return
            }
        }

        // Resolve short IDs to full IDs
        val allSnapshots = orchestrator.history()
        val fullId1 = allSnapshots.find { it.id.startsWith(resolvedId2) }?.id ?: resolvedId2
        val fullId2 = allSnapshots.find { it.id.startsWith(id1) }?.id ?: id1

        val diff = orchestrator.diffSnapshots(fullId1, fullId2)
        if (diff == null) {
            echo("Could not find one or both snapshots", err = true)
            return
        }

        echo("Diff: ${diff.summary()}")
        for (f in diff.added.sorted()) echo("  + $f")
        for (f in diff.modified.sorted()) echo("  ~ $f")
        for (f in diff.deleted.sorted()) echo("  - $f")
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("qorche 0.2.0-SNAPSHOT")
    }
}
