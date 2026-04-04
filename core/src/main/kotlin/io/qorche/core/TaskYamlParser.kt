package io.qorche.core

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Parses YAML task definition files into TaskProject instances.
 *
 * Expected format:
 * ```yaml
 * project: my-project
 * tasks:
 *   - id: task-1
 *     instruction: "Do something"
 *     type: implement
 *     depends_on: []
 *     files: [src/a.kt]
 * ```
 */
object TaskYamlParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    fun parse(content: String): TaskProject {
        require(content.isNotBlank()) { "Task definition file is empty" }
        val project = try {
            yaml.decodeFromString(TaskProject.serializer(), content)
        } catch (e: Exception) {
            throw TaskParseException("Failed to parse task definition: ${e.message}", e)
        }
        validateRunnerReferences(project)
        return project
    }

    /**
     * Validate that every task's runner reference points to a defined runner.
     */
    private fun validateRunnerReferences(project: TaskProject) {
        val definedRunners = project.runners.keys
        for (task in project.tasks) {
            val runnerName = task.runner ?: continue
            require(runnerName in definedRunners) {
                "Task '${task.id}' references undefined runner '$runnerName'. " +
                    "Defined runners: ${definedRunners.ifEmpty { setOf("(none)") }.joinToString(", ")}"
            }
        }
    }

    fun parseFile(path: Path): TaskProject {
        require(path.toFile().exists()) { "Task file does not exist: $path" }
        return parse(path.readText())
    }

    /**
     * Parse and build a TaskGraph, validating dependencies and detecting cycles.
     */
    fun parseToGraph(content: String): TaskGraph {
        val project = parse(content)
        require(project.tasks.isNotEmpty()) { "Task definition contains no tasks" }
        return TaskGraph(project.tasks)
    }

    fun parseFileToGraph(path: Path): Pair<TaskProject, TaskGraph> {
        val project = parseFile(path)
        require(project.tasks.isNotEmpty()) { "Task definition contains no tasks" }
        return project to TaskGraph(project.tasks)
    }
}

class TaskParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
