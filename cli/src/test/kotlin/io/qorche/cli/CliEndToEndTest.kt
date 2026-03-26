package io.qorche.cli

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.ConflictDetector
import io.qorche.core.Orchestrator
import io.qorche.core.TaskYamlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    private fun createTestDir(): Path {
        val root = Files.createTempDirectory("qorche-e2e")
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")
        return root
    }

    private class TestRunner(
        private val filesByInstruction: Map<String, List<String>> = emptyMap(),
        private val delayMs: Long = 10
    ) : AgentRunner {
        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.Output("Starting: $instruction"))
            delay(delayMs)

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

    @Test
    fun `plan parallel-no-conflict produces valid JSON`() {
        val yaml = loadFixture("parallel-no-conflict.yaml")
        val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

        val jsonStr = buildPlanJson(project.project, "test", graph, project.tasks)
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("test-parallel", obj["project"]!!.jsonPrimitive.content)
        assertEquals(3, obj["tasks"]!!.jsonPrimitive.int)

        val groups = obj["groups"]!!.jsonArray
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].jsonObject["taskIds"]!!.jsonArray.size)
        assertTrue(groups[0].jsonObject["parallel"]!!.jsonPrimitive.boolean)

        val warnings = obj["warnings"]!!.jsonArray
        assertEquals(0, warnings.size)
    }

    @Test
    fun `plan scope-overlap detects overlapping files`() {
        val yaml = loadFixture("scope-overlap.yaml")
        val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

        val jsonStr = buildPlanJson(project.project, "test", graph, project.tasks)
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        val warnings = obj["warnings"]!!.jsonArray
        assertEquals(1, warnings.size)

        val warning = warnings[0].jsonObject
        assertEquals("scope_overlap", warning["type"]!!.jsonPrimitive.content)
        assertEquals("task-a", warning["taskA"]!!.jsonPrimitive.content)
        assertEquals("task-b", warning["taskB"]!!.jsonPrimitive.content)

        val overlapping = warning["overlappingFiles"]!!.jsonArray
        assertEquals(1, overlapping.size)
        assertEquals("src/shared.kt", overlapping[0].jsonPrimitive.content)
    }

    @Test
    fun `plan diamond-dag shows correct groups`() {
        val yaml = loadFixture("diamond-dag.yaml")
        val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

        val jsonStr = buildPlanJson(project.project, "test", graph, project.tasks)
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(4, obj["tasks"]!!.jsonPrimitive.int)

        val groups = obj["groups"]!!.jsonArray
        assertEquals(3, groups.size)

        assertFalse(groups[0].jsonObject["parallel"]!!.jsonPrimitive.boolean)
        assertTrue(groups[1].jsonObject["parallel"]!!.jsonPrimitive.boolean)
        assertFalse(groups[2].jsonObject["parallel"]!!.jsonPrimitive.boolean)

        val parallelGroup = groups[1].jsonObject["taskIds"]!!.jsonArray
        assertEquals(2, parallelGroup.size)
    }

    @Test
    fun `plan cycle-error throws CycleDetectedException`() {
        val yaml = loadFixture("cycle-error.yaml")

        try {
            TaskYamlParser.parseToGraph(yaml)
            assertTrue(false, "Should throw CycleDetectedException")
        } catch (e: io.qorche.core.CycleDetectedException) {
            assertTrue(e.message!!.contains("Cycle detected"))
        }
    }

    @Test
    fun `run parallel produces valid JSON output`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = loadFixture("parallel-no-conflict.yaml")
            val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

            val orchestrator = Orchestrator(root)
            val runner = TestRunner(
                filesByInstruction = mapOf(
                    "task a" to listOf("src/a.kt"),
                    "task b" to listOf("src/b.kt"),
                    "task c" to listOf("src/c.kt")
                )
            )

            val startTime = System.currentTimeMillis()
            val result = orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner
            )
            val elapsed = System.currentTimeMillis() - startTime

            val jsonStr = result.toJson(project.project, "test", elapsed)
            val obj = json.parseToJsonElement(jsonStr).jsonObject

            assertTrue(obj["success"]!!.jsonPrimitive.boolean)
            assertEquals("test-parallel", obj["project"]!!.jsonPrimitive.content)

            val tasks = obj["tasks"]!!.jsonArray
            assertEquals(3, tasks.size)
            assertTrue(tasks.all { it.jsonObject["status"]!!.jsonPrimitive.content == "COMPLETED" })

            assertEquals(0, obj["conflicts"]!!.jsonArray.size)
            assertEquals(0, obj["retriedTasks"]!!.jsonPrimitive.int)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run diamond-dag executes in correct order`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = loadFixture("diamond-dag.yaml")
            val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

            val orchestrator = Orchestrator(root)
            val executionOrder = mutableListOf<String>()
            val runner = TestRunner(
                filesByInstruction = mapOf(
                    "explore" to listOf("src/explore.kt"),
                    "backend" to listOf("src/backend.kt"),
                    "frontend" to listOf("src/frontend.kt"),
                    "integrate" to listOf("src/integrate.kt")
                )
            )

            val result = orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner,
                onTaskStart = { def -> synchronized(executionOrder) { executionOrder.add(def.id) } }
            )

            assertTrue(result.success)
            assertEquals(4, result.completedTasks)
            assertEquals("explore", executionOrder.first())
            assertEquals("integrate", executionOrder.last())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run with conflict produces correct JSON`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = loadFixture("scope-overlap.yaml")
            val (project, graph) = TaskYamlParser.parse(yaml) to TaskYamlParser.parseToGraph(yaml)

            val orchestrator = Orchestrator(root)
            val runner = TestRunner(
                filesByInstruction = mapOf(
                    "modify auth" to listOf("src/auth.kt", "src/shared.kt"),
                    "modify login" to listOf("src/login.kt", "src/shared.kt")
                )
            )

            val startTime = System.currentTimeMillis()
            val result = orchestrator.runGraphParallel(
                project = project.project,
                graph = graph,
                runner = runner
            )
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue(result.hasConflicts)

            val jsonStr = result.toJson(project.project, "test", elapsed)
            val obj = json.parseToJsonElement(jsonStr).jsonObject

            assertFalse(obj["success"]!!.jsonPrimitive.boolean)

            val conflicts = obj["conflicts"]!!.jsonArray
            assertEquals(1, conflicts.size)
            assertTrue(conflicts[0].jsonObject["files"]!!.jsonArray.any {
                it.jsonPrimitive.content == "src/shared.kt"
            })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `status command reads workspace state`() {
        val root = createTestDir()
        try {
            val orchestrator = Orchestrator(root)
            val runner = TestRunner(
                filesByInstruction = mapOf("task a" to listOf("src/a.kt"))
            )

            runBlocking {
                orchestrator.runTask("test-task", "task a", runner)
            }

            val snapshots = orchestrator.history()
            assertTrue(snapshots.size >= 2)

            val walEntries = orchestrator.walEntries()
            assertTrue(walEntries.isNotEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scope overlap detection ignores dependent tasks`() {
        val yaml = """
            project: test
            tasks:
              - id: task-a
                instruction: "first"
                files: [src/shared.kt]
              - id: task-b
                instruction: "second"
                depends_on: [task-a]
                files: [src/shared.kt]
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val warnings = detectScopeOverlaps(project.tasks)

        assertEquals(0, warnings.size, "Dependent tasks should not produce overlap warnings")
    }
}
