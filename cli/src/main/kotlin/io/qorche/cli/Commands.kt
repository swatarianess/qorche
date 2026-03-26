package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.qorche.agent.ClaudeCodeAdapter
import io.qorche.core.CycleDetectedException
import io.qorche.core.Orchestrator
import io.qorche.core.TaskParseException
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

private fun cliVersion(): String =
    object {}.javaClass.getResourceAsStream("/io/qorche/cli/version.txt")
        ?.bufferedReader()?.readText()?.trim() ?: "dev"

class QorcheCommand : CliktCommand(name = "qorche") {
    override fun run() = Unit

    init {
        subcommands(RunCommand(), PlanCommand(), StatusCommand(), LogsCommand(), HistoryCommand(), DiffCommand(), VersionCommand())
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val instructionOrFile by argument()
    private val verbose by option("--verbose", "-v").flag()
    private val skipPermissions by option("--skip-permissions").flag()
    private val output by option("--output", "-o").default("text")

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val orchestrator = Orchestrator(workDir)
        val extraArgs = if (skipPermissions) listOf("--dangerously-skip-permissions") else emptyList()
        val runner = ClaudeCodeAdapter(extraArgs = extraArgs)
        val startTime = System.currentTimeMillis()

        val isYamlFile = instructionOrFile.endsWith(".yaml") || instructionOrFile.endsWith(".yml")

        if (isYamlFile) {
            runGraphFromFile(workDir, orchestrator, runner, startTime)
        } else {
            runSingleTask(orchestrator, runner, startTime)
        }
    }

    private fun runSingleTask(orchestrator: Orchestrator, runner: ClaudeCodeAdapter, startTime: Long) {
        if (output == "text") echo("Starting: $instructionOrFile")

        runBlocking {
            val result = orchestrator.runTask(
                taskId = "cli-run",
                instruction = instructionOrFile,
                runner = runner
            ) { line ->
                if (verbose && output == "text") echo("[agent] $line", err = true)
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (output == "json") {
                val graphResult = Orchestrator.GraphResult(
                    project = "cli-run",
                    taskResults = mapOf("cli-run" to Orchestrator.TaskOutcome(
                        taskId = "cli-run",
                        status = if (result.agentResult.exitCode == 0) TaskStatus.COMPLETED else TaskStatus.FAILED,
                        runResult = result
                    )),
                    totalTasks = 1,
                    completedTasks = if (result.agentResult.exitCode == 0) 1 else 0,
                    failedTasks = if (result.agentResult.exitCode == 0) 0 else 1,
                    skippedTasks = 0
                )
                echo(graphResult.toJson("cli-run", cliVersion(), elapsed))
            } else {
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

            if (result.agentResult.exitCode != 0) exitProcess(1)
        }
    }

    private fun runGraphFromFile(
        workDir: Path,
        orchestrator: Orchestrator,
        runner: ClaudeCodeAdapter,
        startTime: Long
    ) {
        val filePath = workDir.resolve(instructionOrFile)
        val (project, graph) = try {
            TaskYamlParser.parseFileToGraph(filePath)
        } catch (e: TaskParseException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        } catch (e: CycleDetectedException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        }

        if (output == "text") {
            echo("Project: ${project.project}")
            echo("Tasks: ${project.tasks.size}")
            echo("")
        }

        runBlocking {
            val result = orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner,
                onTaskStart = { def ->
                    if (output == "text") echo("${Terminal.cyan("[${def.id}]")} Starting: ${def.instruction}")
                },
                onTaskComplete = { taskId, outcome ->
                    if (output == "text") {
                        when (outcome.status) {
                            TaskStatus.COMPLETED -> {
                                val diff = outcome.runResult?.diff
                                if (diff != null && diff.totalChanges > 0) {
                                    echo("${Terminal.green("[${taskId}]")} Done: ${diff.summary()}")
                                } else {
                                    echo("${Terminal.green("[${taskId}]")} Done (no changes)")
                                }
                            }
                            TaskStatus.FAILED -> echo("${Terminal.red("[${taskId}]")} FAILED: ${outcome.skipReason ?: "non-zero exit"}")
                            TaskStatus.SKIPPED -> echo("${Terminal.dim("[${taskId}]")} SKIPPED: ${outcome.skipReason}")
                            else -> {}
                        }
                    }
                },
                onConflict = { conflict ->
                    if (output == "text") {
                        echo("${Terminal.red("[CONFLICT]")} ${conflict.taskA} <-> ${conflict.taskB}: ${conflict.conflictingFiles.joinToString(", ")}")
                    }
                },
                onOutput = { line ->
                    if (verbose) echo("[agent] $line", err = output == "json")
                }
            )

            val elapsed = System.currentTimeMillis() - startTime

            if (output == "json") {
                echo(result.toJson(project.project, cliVersion(), elapsed))
            } else {
                echo("")
                if (result.hasConflicts) {
                    echo("Conflicts: ${result.conflicts.size} detected")
                }
                echo("Results: ${result.completedTasks} completed, ${result.failedTasks} failed, ${result.skippedTasks} skipped")
                echo("Logs: .qorche/logs/")
                echo("Total time: ${elapsed}ms")
            }

            if (!result.success) exitProcess(1)
        }
    }
}

class PlanCommand : CliktCommand(name = "plan") {
    private val file by argument()
    private val output by option("--output", "-o").default("text")

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val filePath = workDir.resolve(file)

        val (project, graph) = try {
            TaskYamlParser.parseFileToGraph(filePath)
        } catch (e: TaskParseException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        } catch (e: CycleDetectedException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            exitProcess(2)
        }

        if (output == "json") {
            echo(buildPlanJson(project.project, cliVersion(), graph, project.tasks))
            return
        }

        echo("Project: ${project.project}")
        echo("Task graph: ${project.tasks.size} tasks")
        echo("")

        echo("Execution order (sequential):")
        val order = graph.topologicalSort()
        for ((i, taskId) in order.withIndex()) {
            val def = graph[taskId]?.definition ?: continue
            val deps = if (def.dependsOn.isEmpty()) "no dependencies"
                else "depends on: ${def.dependsOn.joinToString(", ")}"
            val files = if (def.files.isEmpty()) ""
                else " [${def.files.joinToString(", ")}]"
            echo("  ${i + 1}. ${def.id} (${def.type.name.lowercase()}) — $deps$files")
        }

        val groups = graph.parallelGroups()
        if (groups.any { it.size > 1 }) {
            echo("")
            echo("Parallel groups:")
            for ((i, group) in groups.withIndex()) {
                val label = if (group.size == 1) group[0]
                    else group.joinToString(", ")
                echo("  Group ${i + 1}: $label")
            }
        }

        val warnings = detectScopeOverlaps(project.tasks)
        if (warnings.isNotEmpty()) {
            echo("")
            for (w in warnings) {
                echo("  Warning: ${w.taskA} and ${w.taskB} overlap on ${w.overlappingFiles.joinToString(", ")}")
            }
        }

        echo("")
        echo("Use 'qorche run ${file}' to execute.")
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

        val resolvedId2 = id2 ?: run {
            val snap = orchestrator.history().find { it.id.startsWith(id1) }
            snap?.parentId ?: run {
                echo("Cannot determine comparison snapshot. Provide two IDs.", err = true)
                return
            }
        }

        val allSnapshots = orchestrator.history()
        val fullId1 = allSnapshots.find { it.id.startsWith(id1) }?.id ?: id1
        val fullId2 = allSnapshots.find { it.id.startsWith(resolvedId2) }?.id ?: resolvedId2

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
        echo("qorche ${cliVersion()}")
    }
}
