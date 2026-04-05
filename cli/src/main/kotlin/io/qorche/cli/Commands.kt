package io.qorche.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.qorche.agent.ClaudeCodeAdapter
import io.qorche.agent.RunnerRegistry
import io.qorche.agent.ShellRunner
import io.qorche.core.AgentRunner
import io.qorche.core.ExitCode
import io.qorche.core.Orchestrator
import io.qorche.core.RunnerConfig
import io.qorche.core.SnapshotCreator
import io.qorche.core.TaskStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class QorcheCommand : CliktCommand(name = "qorche") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Orchestrate concurrent filesystem mutations with MVCC conflict detection"

    private val noColor by option("--no-color", help = "Disable colored output").flag()

    override fun run() {
        if (noColor) Terminal.forceColor = false
    }

    init {
        completionOption()
        subcommands(
            InitCommand(), RunCommand(), PlanCommand(), ValidateCommand(),
            VerifyCommand(), ReplayCommand(),
            StatusCommand(), LogsCommand(), HistoryCommand(), DiffCommand(),
            CleanCommand(), SchemaCommand(), VersionCommand()
        )
    }
}

class RunCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
    internal val orchestratorFactory: (Path) -> Orchestrator = ::Orchestrator,
    internal val singleRunnerFactory: (List<String>) -> AgentRunner = { ClaudeCodeAdapter(extraArgs = it) },
    internal val graphRunnerBuilder: (Map<String, RunnerConfig>) -> Map<String, AgentRunner> = RunnerRegistry::build,
    internal val graphFallbackRunner: () -> AgentRunner = {
        ShellRunner(allowedCommands = setOf("sh", "bash", "cmd", "powershell"))
    }
) : CliktCommand(name = "run") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Execute a task instruction or YAML task graph"

    private val instructionOrFile by argument(help = "Instruction string or path to a YAML task file")
    private val verbose by option("--verbose", "-v", help = "Show agent output").flag()
    private val skipPermissions by option("--skip-permissions", help = "Pass --dangerously-skip-permissions to Claude Code").flag()
    private val output by option("--output", "-o", help = "Output format: text or json").default("text")
    private val hashAlgorithm by option("--hash", help = "Hash algorithm: crc32c (fastest), sha1 (default, same as Git), sha256 (cryptographic)")
    private val preflightThreshold by option("--preflight-threshold", help = "File count above which a faster hash algorithm is suggested (default: ${SnapshotCreator.DEFAULT_LARGE_REPO_THRESHOLD})").int()

    override fun run() {
        val hashExplicit = hashAlgorithm != null
        SnapshotCreator.hashAlgorithm = parseHashAlgorithm(hashAlgorithm)
        val workDir = workDirProvider()
        val orchestrator = orchestratorFactory(workDir)
        if (output == "text") {
            orchestrator.onSnapshotProgress = { progress ->
                if (progress.total >= 1000) {
                    when (progress.phase) {
                        "scanning" -> echo("${Terminal.dim("Scanning ${progress.total} files...")}")
                        "hashing" -> echo("\r${Terminal.dim("Hashing ${progress.current}/${progress.total} files...")}", trailingNewline = false)
                    }
                }
            }
        }
        val startTime = System.currentTimeMillis()

        if (!hashExplicit && output == "text") {
            val threshold = preflightThreshold ?: SnapshotCreator.DEFAULT_LARGE_REPO_THRESHOLD
            val preflight = SnapshotCreator.preflightCheck(workDir, threshold)
            if (preflight != null) {
                echo("${Terminal.dim("Hint:")} ${preflight.message()}")
            }
        }

        if (isYamlFile(instructionOrFile)) {
            runGraphFromFile(workDir, orchestrator, startTime)
        } else {
            val extraArgs = if (skipPermissions) listOf("--dangerously-skip-permissions") else emptyList()
            val runner = singleRunnerFactory(extraArgs)
            runSingleTask(orchestrator, runner, startTime)
        }
    }

    private fun runSingleTask(orchestrator: Orchestrator, runner: AgentRunner, startTime: Long) {
        if (output == "text") echo("Starting: $instructionOrFile")

        runBlocking {
            val taskResult = orchestrator.runTask(
                taskId = "cli-run",
                instruction = instructionOrFile,
                runner = runner
            ) { line ->
                if (verbose && output == "text") echo("[agent] $line", err = true)
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (taskResult.isFailure) {
                echo("Error: ${taskResult.exceptionOrNull()?.message}", err = true)
                throw ProgramResult(ExitCode.TASK_FAILURE.code)
            }

            val result = taskResult.getOrThrow()

            if (output == "json") {
                val graphResult = buildSingleTaskGraphResult(result)
                echo(graphResult.toJson("cli-run", cliVersion(), elapsed))
            } else {
                for (line in formatSingleTaskText(result, elapsed)) {
                    echo(line)
                }
            }

            if (result.agentResult.exitCode != 0) throw ProgramResult(ExitCode.TASK_FAILURE.code)
        }
    }

    private fun runGraphFromFile(
        workDir: Path,
        orchestrator: Orchestrator,
        startTime: Long
    ) {
        val filePath = workDir.resolve(instructionOrFile)
        val setup = prepareGraphRun(filePath, graphRunnerBuilder, graphFallbackRunner)

        when (setup) {
            is GraphRunSetup.Failed -> {
                echo("Error: ${setup.message}", err = true)
                throw ProgramResult(setup.exitCode.code)
            }
            is GraphRunSetup.Ready -> runGraph(orchestrator, setup, startTime)
        }
    }

    private fun runGraph(
        orchestrator: Orchestrator,
        setup: GraphRunSetup.Ready,
        startTime: Long
    ) {
        if (output == "text") {
            echo("Project: ${setup.project.project}")
            echo("Tasks: ${setup.project.tasks.size}")
            if (setup.runners.isNotEmpty()) {
                echo("Runners: ${setup.runners.keys.joinToString(", ")}")
            }
            echo("")
        }

        runBlocking {
            val result = orchestrator.runGraphParallel(
                project = setup.project.project,
                graph = setup.graph,
                runner = setup.defaultRunner,
                runners = setup.runners,
                verifyConfig = setup.project.verify,
                onTaskStart = { def ->
                    if (output == "text") echo("${Terminal.cyan("[${def.id}]")} Starting: ${def.instruction}")
                },
                onTaskComplete = { taskId, outcome ->
                    if (output == "text") {
                        when (outcome.status) {
                            TaskStatus.COMPLETED -> {
                                val diff = outcome.runResult?.diff
                                val elapsed = Terminal.dim("(${formatElapsed(outcome.elapsedMs)})")
                                if (diff != null && diff.totalChanges > 0) {
                                    echo("${Terminal.green("[${taskId}]")} Done: ${diff.summary()} $elapsed")
                                } else {
                                    echo("${Terminal.green("[${taskId}]")} Done (no changes) $elapsed")
                                }
                            }
                            TaskStatus.FAILED -> echo("${Terminal.red("[${taskId}]")} FAILED: ${outcome.skipReason ?: "non-zero exit"} ${Terminal.dim("(${formatElapsed(outcome.elapsedMs)})")}")
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
                onVerify = { verifyResult ->
                    if (output == "text") {
                        if (verifyResult.success) {
                            echo("${Terminal.green("[VERIFY]")} Group ${verifyResult.groupIndex} passed ${Terminal.dim("(${formatElapsed(verifyResult.elapsedMs)})")}")
                        } else {
                            echo("${Terminal.red("[VERIFY]")} Group ${verifyResult.groupIndex} FAILED (exit ${verifyResult.exitCode}) ${Terminal.dim("(${formatElapsed(verifyResult.elapsedMs)})")}")
                        }
                    }
                },
                onOutput = { line ->
                    if (verbose) echo("[agent] $line", err = output == "json")
                }
            )

            val elapsed = System.currentTimeMillis() - startTime

            if (output == "json") {
                echo(result.toJson(setup.project.project, cliVersion(), elapsed))
            } else {
                for (line in formatGraphTextSummary(result, elapsed)) {
                    echo(line)
                }
            }

            val exitCode = interpretGraphResult(result)
            if (exitCode != ExitCode.SUCCESS) {
                throw ProgramResult(exitCode.code)
            }
        }
    }
}

class PlanCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
) : CliktCommand(name = "plan") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Preview execution order and parallel groups without running"

    private val file by argument(help = "Path to a YAML task file")
    private val output by option("--output", "-o", help = "Output format: text or json").default("text")

    override fun run() {
        val workDir = workDirProvider()
        val filePath = workDir.resolve(file)

        val (project, graph) = when (val loaded = loadTaskGraph(filePath)) {
            is TaskGraphLoadResult.Success -> loaded.project to loaded.graph
            is TaskGraphLoadResult.ParseError -> {
                echo("Error: ${loaded.message}", err = true)
                throw ProgramResult(ExitCode.CONFIG_ERROR.code)
            }
        }

        if (output == "json") {
            echo(buildPlanJson(project.project, cliVersion(), graph, project.tasks))
            return
        }

        val summary = buildPlanSummary(project, graph)

        echo("Project: ${summary.projectName}")
        echo("Task graph: ${summary.taskCount} tasks")
        echo("")

        echo("Execution order (sequential):")
        for (entry in summary.executionOrder) {
            echo("  ${entry.index}. ${entry.id} (${entry.type}) — ${entry.dependencies}${entry.files}")
        }

        if (summary.parallelGroups.isNotEmpty()) {
            echo("")
            echo("Parallel groups:")
            for ((i, group) in summary.parallelGroups.withIndex()) {
                val label = group.joinToString(", ")
                echo("  Group ${i + 1}: $label")
            }
        }

        if (summary.warnings.isNotEmpty()) {
            echo("")
            for (w in summary.warnings) {
                echo("  Warning: ${w.taskA} and ${w.taskB} overlap on ${w.overlappingFiles.joinToString(", ")}")
            }
        }

        echo("")
        echo("Use 'qorche run ${file}' to execute.")
    }
}

class VerifyCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
) : CliktCommand(name = "verify") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Run the verification step from a YAML task file against the current working directory"

    private val file by argument(help = "Path to a YAML task file with a 'verify' section")
    private val verbose by option("--verbose", "-v", help = "Show verification output").flag()

    override fun run() {
        val workDir = workDirProvider()
        val filePath = workDir.resolve(file)

        val config = when (val loaded = loadVerifyConfig(filePath)) {
            is VerifyLoadResult.Success -> loaded.config
            is VerifyLoadResult.ParseError -> {
                echo("Error: ${loaded.message}", err = true)
                throw ProgramResult(ExitCode.CONFIG_ERROR.code)
            }
            is VerifyLoadResult.NoVerifySection -> {
                echo("No 'verify' section found in $file", err = true)
                throw ProgramResult(ExitCode.CONFIG_ERROR.code)
            }
        }

        echo("Running: ${config.command}")
        echo("")

        val outcome = executeVerification(config, workDir) { line ->
            if (verbose) echo(line)
        }

        when (outcome) {
            is VerifyOutcome.Passed -> {
                echo("${Terminal.green("PASSED")} ${Terminal.dim("(${formatElapsed(outcome.elapsedMs)})")}")
            }
            is VerifyOutcome.Failed -> {
                echo("${Terminal.red("FAILED")} (exit ${outcome.exitCode}) ${Terminal.dim("(${formatElapsed(outcome.elapsedMs)})")}")
                throw ProgramResult(ExitCode.TASK_FAILURE.code)
            }
            is VerifyOutcome.Timeout -> {
                echo("${Terminal.red("TIMEOUT")} after ${outcome.timeoutSeconds}s")
                throw ProgramResult(ExitCode.TASK_FAILURE.code)
            }
            is VerifyOutcome.Error -> {
                echo("Error: ${outcome.message}", err = true)
                throw ProgramResult(ExitCode.TASK_FAILURE.code)
            }
        }
    }
}

class ReplayCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
    internal val orchestratorFactory: (Path) -> Orchestrator = ::Orchestrator
) : CliktCommand(name = "replay") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Replay WAL history and verify snapshot consistency"

    private val verbose by option("--verbose", "-v", help = "Show detailed entry information").flag()
    private val checkConsistency by option("--check", help = "Verify current filesystem matches the latest snapshot").flag()

    override fun run() {
        val workDir = workDirProvider()
        val orchestrator = orchestratorFactory(workDir)

        val entries = orchestrator.walEntries()
        if (entries.isEmpty()) {
            echo("No WAL entries found. Run tasks first.")
            return
        }

        val summary = summarizeReplay(entries, verbose)

        echo("WAL replay: ${summary.totalEntries} entries")
        echo("")

        for (entry in summary.formattedEntries) {
            val prefix = when (entry.type) {
                WalEntryType.STARTED -> Terminal.cyan("[${entry.taskId}]")
                WalEntryType.COMPLETED -> Terminal.green("[${entry.taskId}]")
                WalEntryType.FAILED -> Terminal.red("[${entry.taskId}]")
                WalEntryType.CONFLICT -> Terminal.red("[CONFLICT]")
                WalEntryType.RETRY_SCHEDULED -> Terminal.cyan("[${entry.taskId}]")
                WalEntryType.RETRIED -> Terminal.cyan("[${entry.taskId}]")
                WalEntryType.SCOPE_VIOLATION -> Terminal.red("[SCOPE]")
                WalEntryType.VERIFY -> if (entry.headline.contains("passed")) Terminal.green("[VERIFY]")
                    else Terminal.red("[VERIFY]")
            }
            echo("$prefix ${entry.headline}")
            for (detail in entry.details) {
                echo("  $detail")
            }
        }

        echo("")
        echo("Summary: ${summary.taskCount} started, ${summary.completedCount} completed, ${summary.failedCount} failed")
        if (summary.retryCount > 0) echo("  Retries: ${summary.retryCount}")
        if (summary.conflictCount > 0) echo("  Conflicts: ${summary.conflictCount}")
        if (summary.verifyCount > 0) echo("  Verifications: ${summary.verifyCount}")

        if (checkConsistency) {
            echo("")
            echo("Checking snapshot consistency...")

            when (val result = checkConsistency(orchestrator.history(), workDir)) {
                is ConsistencyResult.NoSnapshots -> {
                    echo("No snapshots found for consistency check.")
                }
                is ConsistencyResult.Consistent -> {
                    echo("${Terminal.green("CONSISTENT")} Current filesystem matches latest snapshot (${result.snapshotIdPrefix})")
                }
                is ConsistencyResult.Diverged -> {
                    echo("${Terminal.red("DIVERGED")} ${result.diff.summary()} since snapshot ${result.snapshotIdPrefix}")
                    for (f in result.diff.added.sorted()) echo("  + $f")
                    for (f in result.diff.modified.sorted()) echo("  ~ $f")
                    for (f in result.diff.deleted.sorted()) echo("  - $f")
                    throw ProgramResult(ExitCode.TASK_FAILURE.code)
                }
            }
        }
    }
}

class HistoryCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
    internal val orchestratorFactory: (Path) -> Orchestrator = ::Orchestrator
) : CliktCommand(name = "history") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List past snapshots with timestamps and file counts"

    private val limit by option("--limit", "-n", help = "Maximum number of snapshots to show").int()

    override fun run() {
        val workDir = workDirProvider()
        val orchestrator = orchestratorFactory(workDir)
        val snapshots = orchestrator.history()

        if (snapshots.isEmpty()) {
            echo("No snapshots found. Run a task first.")
            return
        }

        val historyOutput = formatHistory(snapshots, limit)
        for (line in historyOutput.lines) {
            echo("${line.idPrefix}  ${line.timestamp}  ${line.description}  (${line.fileCount} files)")
        }
        if (historyOutput.truncatedCount > 0) {
            echo("... and ${historyOutput.truncatedCount} more (use --limit to show more)")
        }
    }
}

class DiffCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
    internal val orchestratorFactory: (Path) -> Orchestrator = ::Orchestrator
) : CliktCommand(name = "diff") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Show file changes between two snapshots"

    private val id1 by argument(help = "First snapshot ID (or prefix)")
    private val id2 by argument(help = "Second snapshot ID (or prefix, defaults to parent)").optional()

    override fun run() {
        val workDir = workDirProvider()
        val orchestrator = orchestratorFactory(workDir)
        val allSnapshots = orchestrator.history()

        when (val resolution = resolveSnapshotIds(id1, id2, allSnapshots)) {
            is DiffResolution.NoComparison -> {
                echo(resolution.message, err = true)
                return
            }
            is DiffResolution.Resolved -> {
                val diff = orchestrator.diffSnapshots(resolution.fullId1, resolution.fullId2)
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
    }
}

class SchemaCommand : CliktCommand(name = "schema") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Print the JSON Schema for tasks.yaml (for editor autocomplete and validation)"

    private val output by option("--output", "-o", help = "Write schema to a file instead of stdout")

    override fun run() {
        val schema = javaClass.getResourceAsStream("/io/qorche/cli/tasks.schema.json")
            ?.bufferedReader()?.readText()
            ?: error("Schema resource not found")

        if (output != null) {
            val path = java.nio.file.Path.of(output!!)
            path.toFile().writeText(schema)
            echo("Schema written to $output")
        } else {
            echo(schema)
        }
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Print version info"
    override fun run() {
        echo("qorche ${cliVersion()}")
    }
}
