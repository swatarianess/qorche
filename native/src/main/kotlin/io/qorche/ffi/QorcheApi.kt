package io.qorche.ffi

import io.qorche.core.Orchestrator
import io.qorche.core.SnapshotCreator
import io.qorche.core.TaskYamlParser
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

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
