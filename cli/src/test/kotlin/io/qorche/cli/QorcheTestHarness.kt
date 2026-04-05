package io.qorche.cli

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.Orchestrator
import io.qorche.core.TaskYamlParser
import io.qorche.core.VerifyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Test DSL for CLI-level integration tests.
 *
 * Handles temp directory setup, YAML parsing, orchestrator wiring, and cleanup.
 * Tests focus on assertions rather than plumbing.
 *
 * Usage:
 * ```kotlin
 * qorcheTest {
 *     files("src/a.kt" to "content")
 *     yaml("""
 *         project: test
 *         tasks:
 *           - id: task1
 *             instruction: do thing
 *     """)
 *     writes("do thing" to listOf("src/output.kt"))
 *
 *     run { result ->
 *         assertTrue(result.success)
 *     }
 * }
 * ```
 */
fun qorcheTest(block: QorcheTestHarness.() -> Unit) {
    val harness = QorcheTestHarness()
    try {
        harness.block()
    } finally {
        harness.cleanup()
    }
}

class QorcheTestHarness {
    private val root: Path = Files.createTempDirectory("qorche-harness")
    private val fileWrites = mutableMapOf<String, List<String>>()
    private val seedFiles = mutableMapOf<String, String>()
    private var yamlContent: String? = null
    private var verifyConfig: VerifyConfig? = null
    private var failInstructions = mutableSetOf<String>()

    val json = Json { ignoreUnknownKeys = true }

    /** The working directory for this test. */
    val workDir: Path get() = root

    /** Seed files into the working directory before execution. */
    fun files(vararg entries: Pair<String, String>) {
        for ((path, content) in entries) {
            seedFiles[path] = content
        }
    }

    /** Set the YAML task definition (inline, trimIndent applied automatically). */
    fun yaml(content: String) {
        yamlContent = content.trimIndent()
    }

    /** Load a YAML fixture from test resources. */
    fun fixture(name: String) {
        yamlContent = javaClass.getResourceAsStream("/fixtures/$name")!!
            .bufferedReader().readText()
    }

    /** Map instruction text to files the mock runner should create/modify. */
    fun writes(vararg mappings: Pair<String, List<String>>) {
        for ((instruction, files) in mappings) {
            fileWrites[instruction] = files
        }
    }

    /** Mark instructions that should fail (exit code 1). */
    fun fails(vararg instructions: String) {
        failInstructions.addAll(instructions)
    }

    /** Set a verification config for the run. */
    fun verify(command: String, timeoutSeconds: Long = 30, onFailure: String = "fail") {
        verifyConfig = VerifyConfig(
            command = command,
            timeoutSeconds = timeoutSeconds,
            onFailure = when (onFailure) {
                "warn" -> io.qorche.core.VerifyFailurePolicy.WARN
                else -> io.qorche.core.VerifyFailurePolicy.FAIL
            }
        )
    }

    /** Run the task graph and assert against the result. */
    fun run(block: (Orchestrator.GraphResult) -> Unit) {
        setup()
        val yaml = requireNotNull(yamlContent) { "Call yaml() or fixture() before run()" }
        val (project, graph) = TaskYamlParser.parseFileToGraph(writeYamlFile(yaml))
        val orchestrator = Orchestrator(root)
        val runner = HarnessRunner(fileWrites, failInstructions)

        val result = runBlocking {
            orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig ?: project.verify
            )
        }

        block(result)
    }

    /** Run and get the JSON output, asserting against the parsed JSON object. */
    fun runJson(block: (JsonObject) -> Unit) {
        setup()
        val yaml = requireNotNull(yamlContent) { "Call yaml() or fixture() before runJson()" }
        val (project, graph) = TaskYamlParser.parseFileToGraph(writeYamlFile(yaml))
        val orchestrator = Orchestrator(root)
        val runner = HarnessRunner(fileWrites, failInstructions)

        val startTime = System.currentTimeMillis()
        val result = runBlocking {
            orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner,
                verifyConfig = verifyConfig ?: project.verify
            )
        }
        val elapsed = System.currentTimeMillis() - startTime

        val jsonStr = result.toJson(project.project, "test", elapsed)
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        block(obj)
    }

    /** Plan (dry run) and assert against the JSON output. */
    fun plan(block: (JsonObject) -> Unit) {
        val yaml = requireNotNull(yamlContent) { "Call yaml() or fixture() before plan()" }
        val project = TaskYamlParser.parse(yaml)
        val graph = TaskYamlParser.parseToGraph(yaml)

        val jsonStr = buildPlanJson(project.project, "test", graph, project.tasks)
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        block(obj)
    }

    /** Get the orchestrator for direct assertions (WAL, history, etc.). */
    fun orchestrator(): Orchestrator {
        setup()
        return Orchestrator(root)
    }

    internal fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun setup() {
        root.resolve("src").createDirectories()
        if (seedFiles.isEmpty()) {
            root.resolve("src/main.kt").writeText("fun main() {}")
        }
        for ((path, content) in seedFiles) {
            val file = root.resolve(path)
            file.parent.createDirectories()
            file.writeText(content)
        }
    }

    private fun writeYamlFile(yaml: String): Path {
        val file = root.resolve("tasks.yaml")
        file.writeText(yaml)
        return file
    }

    private class HarnessRunner(
        private val filesByInstruction: Map<String, List<String>>,
        private val failInstructions: Set<String>,
        private val delayMs: Long = 10
    ) : AgentRunner {
        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.Output("Starting: $instruction"))
            delay(delayMs)

            if (instruction in failInstructions) {
                emit(AgentEvent.Error("Failed: $instruction"))
                emit(AgentEvent.Completed(exitCode = 1))
                return@flow
            }

            val files = filesByInstruction[instruction] ?: emptyList()
            for (relativePath in files) {
                val file = workingDirectory.resolve(relativePath)
                Files.createDirectories(file.parent)
                Files.writeString(file, "// Modified by: $instruction\n")
                emit(AgentEvent.FileModified(relativePath.replace("\\", "/")))
            }

            emit(AgentEvent.Completed(exitCode = 0))
        }.flowOn(Dispatchers.IO)
    }
}
