package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import io.qorche.core.ExitCode
import java.nio.file.Path

class ValidateCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) }
) : CliktCommand(name = "validate") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Validate a YAML task file without running"

    private val file by argument(help = "Path to a YAML task file")

    override fun run() {
        val workDir = workDirProvider()
        val filePath = workDir.resolve(file)

        val (project, graph) = when (val loaded = loadTaskGraph(filePath)) {
            is TaskGraphLoadResult.Success -> loaded.project to loaded.graph
            is TaskGraphLoadResult.ParseError -> {
                echo("${Terminal.red("Invalid:")} ${loaded.message}", err = true)
                throw ProgramResult(ExitCode.CONFIG_ERROR.code)
            }
        }

        val taskCount = project.tasks.size
        val depCount = project.tasks.sumOf { it.dependsOn.size }
        val groups = graph.parallelGroups()

        echo("${Terminal.green("Valid:")} $taskCount tasks, $depCount dependencies, ${groups.size} execution groups")

        val warnings = detectScopeOverlaps(project.tasks)
        for (w in warnings) {
            echo("  ${Terminal.yellow("Warning:")} ${w.taskA} and ${w.taskB} overlap on ${w.overlappingFiles.joinToString(", ")}")
        }
    }
}
