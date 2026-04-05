package io.qorche.ffi

import io.qorche.agent.RunnerRegistry
import io.qorche.core.Orchestrator
import io.qorche.core.SnapshotCreator
import io.qorche.core.TaskYamlParser
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Pure Kotlin API for libqorche.
 *
 * Every public method accepts and returns plain Strings (JSON).
 * The Java entry point layer ([LibQorcheEntryPoints]) handles
 * C-type conversion and memory management.
 */
object QorcheApi {

    fun version(): String =
        javaClass.getResourceAsStream("/io/qorche/ffi/version.txt")
            ?.bufferedReader()?.readText()?.trim() ?: "dev"

    fun parseYaml(yamlPath: String): String = try {
        val project = TaskYamlParser.parseFile(Path.of(yamlPath))
        Json.encodeToString(project)
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun validateYaml(yamlPath: String): String = try {
        val (project, _) = TaskYamlParser.parseFileToGraph(Path.of(yamlPath))
        buildJsonObject {
            put("valid", true)
            put("project", project.project)
            put("task_count", project.tasks.size)
            put("dependency_count", project.tasks.sumOf { it.dependsOn.size })
        }.toString()
    } catch (e: Exception) {
        buildJsonObject {
            put("valid", false)
            put("error", e.message ?: "Unknown error")
        }.toString()
    }

    fun plan(yamlPath: String): String = try {
        val (project, graph) = TaskYamlParser.parseFileToGraph(Path.of(yamlPath))
        val groups = graph.parallelGroups()
        buildJsonObject {
            put("project", project.project)
            put("task_count", project.tasks.size)
            put("execution_order", Json.encodeToString(graph.topologicalSort()))
            put("parallel_groups", Json.encodeToString(groups))
        }.toString()
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun snapshot(workDirPath: String, description: String): String = try {
        val snap = SnapshotCreator.create(Path.of(workDirPath), description)
        Json.encodeToString(snap)
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun diff(workDirPath: String, snapshotId1: String, snapshotId2: String): String = try {
        val orchestrator = Orchestrator(Path.of(workDirPath))
        val d = orchestrator.diffSnapshots(snapshotId1, snapshotId2)
        if (d != null) Json.encodeToString(d)
        else errorJson("Snapshot not found")
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    /**
     * Execute a task graph from a YAML file with full runner support.
     *
     * Parses the YAML, builds runners from the `runners:` config via [RunnerRegistry],
     * and executes the graph (parallel groups when possible). Returns a JSON result
     * with per-task outcomes, conflicts, and summary counters.
     *
     * @param yamlPath Path to the tasks.yaml file.
     * @param workDirPath Working directory for task execution and snapshots.
     * @return JSON string with execution results or `{"error": "..."}`.
     */
    fun run(yamlPath: String, workDirPath: String): String = try {
        val workDir = Path.of(workDirPath)
        val (project, graph) = TaskYamlParser.parseFileToGraph(Path.of(yamlPath))
        val orchestrator = Orchestrator(workDir)
        val runners = RunnerRegistry.build(project.runners)
        val defaultRunner = runners.values.firstOrNull()
            ?: io.qorche.agent.ClaudeCodeAdapter()

        val result = runBlocking {
            orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = defaultRunner,
                runners = runners
            )
        }

        Json.encodeToString(result)
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun listSnapshots(workDirPath: String): String = try {
        val orchestrator = Orchestrator(Path.of(workDirPath))
        Json.encodeToString(orchestrator.history())
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun walEntries(workDirPath: String): String = try {
        val orchestrator = Orchestrator(Path.of(workDirPath))
        Json.encodeToString<List<WALEntry>>(orchestrator.walEntries())
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun schema(): String = try {
        javaClass.getResourceAsStream("/io/qorche/ffi/tasks.schema.json")
            ?.bufferedReader()?.readText()
            ?: errorJson("Schema resource not found")
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    fun clean(workDirPath: String, optionsJson: String): String = try {
        val orchestrator = Orchestrator(Path.of(workDirPath))
        val options = Json.decodeFromString<CleanOptions>(optionsJson)
        val result = orchestrator.clean(
            snapshots = options.snapshots,
            logs = options.logs,
            wal = options.wal,
            fileIndexCache = options.fileIndexCache,
            keepLastSnapshots = options.keepLastSnapshots
        )
        Json.encodeToString(result)
    } catch (e: Exception) {
        errorJson(e.message ?: "Unknown error")
    }

    @kotlinx.serialization.Serializable
    internal data class CleanOptions(
        val snapshots: Boolean = true,
        val logs: Boolean = true,
        val wal: Boolean = true,
        val fileIndexCache: Boolean = true,
        val keepLastSnapshots: Int = 0
    )

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
