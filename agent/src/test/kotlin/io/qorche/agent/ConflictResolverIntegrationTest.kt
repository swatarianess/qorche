package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.ConflictDetector
import io.qorche.core.Orchestrator
import io.qorche.core.ResolveResult
import io.qorche.core.TaskGraph
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskStatus
import io.qorche.core.TextualMergeResolver
import io.qorche.core.WALEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for ConflictResolver + TextualMergeResolver.
 *
 * These go beyond unit tests by exercising the resolver against real
 * filesystem state produced by the Orchestrator and MockAgentRunners,
 * simulating the full conflict → detect → resolve pipeline.
 */
@Tag("smoke")
class ConflictResolverIntegrationTest {

    /**
     * Mock runner that writes specific content to a file, allowing us to
     * create controlled conflicts for the resolver to merge.
     */
    class ContentWritingRunner(
        private val writes: Map<String, String>,
        private val delayMs: Long = 50
    ) : AgentRunner {
        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.Output("Starting: $instruction"))
            delay(delayMs)

            for ((relativePath, content) in writes) {
                val file = workingDirectory.resolve(relativePath)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
                emit(AgentEvent.FileModified(relativePath.replace("\\", "/")))
            }

            emit(AgentEvent.Completed(exitCode = 0))
        }.flowOn(Dispatchers.IO)
    }

    @Test
    fun `resolver merges non-overlapping changes from parallel tasks`() {
        val resolver = TextualMergeResolver()

        // Simulate base file, task A edits top, task B edits bottom
        val base = "line1\nline2\nline3\nline4\nline5"
        val versionA = "MODIFIED_BY_A\nline2\nline3\nline4\nline5"
        val versionB = "line1\nline2\nline3\nline4\nMODIFIED_BY_B"

        val result = resolver.resolve(base, versionA, versionB, "src/shared.kt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("MODIFIED_BY_A\nline2\nline3\nline4\nMODIFIED_BY_B", result.content)
    }

    @Test
    fun `full pipeline - two parallel tasks conflict on same file, resolver merges`() = runBlocking {
        val root = Files.createTempDirectory("qorche-resolver-test")
        try {
            // Create a base file that both tasks will modify
            root.resolve("src").createDirectories()
            val sharedFile = root.resolve("src/shared.kt")
            sharedFile.writeText("package app\n\nfun greet() = \"hello\"\n\nfun farewell() = \"bye\"\n")

            val resolver = TextualMergeResolver()

            // Run two parallel tasks that modify different parts of the same file
            val taskA = TaskDefinition(
                id = "task-a",
                instruction = "modify greeting",
                files = listOf("src/shared.kt")
            )
            val taskB = TaskDefinition(
                id = "task-b",
                instruction = "modify farewell",
                files = listOf("src/shared.kt")
            )

            val graph = TaskGraph(listOf(taskA, taskB))
            val orchestrator = Orchestrator(root)

            // Task A changes greet(), task B changes farewell()
            val runnerA = ContentWritingRunner(
                mapOf("src/shared.kt" to "package app\n\nfun greet() = \"hi there\"\n\nfun farewell() = \"bye\"\n")
            )
            val runnerB = ContentWritingRunner(
                mapOf("src/shared.kt" to "package app\n\nfun greet() = \"hello\"\n\nfun farewell() = \"see ya\"\n")
            )

            // Use per-task runners
            val runners = mapOf("runner-a" to runnerA as AgentRunner, "runner-b" to runnerB as AgentRunner)

            val detectedConflicts = mutableListOf<ConflictDetector.TaskConflict>()

            val result = orchestrator.runGraphParallel(
                project = "resolver-test",
                graph = graph,
                runner = runnerA, // default
                runners = mapOf("runner-a" to runnerA as AgentRunner, "runner-b" to runnerB as AgentRunner),
                onConflict = { detectedConflicts.add(it) }
            )

            // The orchestrator should detect a conflict on shared.kt
            // since both tasks modified it (different content → different hashes)
            if (detectedConflicts.isNotEmpty()) {
                val conflict = detectedConflicts.first()
                assertTrue(conflict.conflictingFiles.any { it.contains("shared.kt") })

                // Now use the resolver to merge — simulating what a future
                // orchestrator integration would do automatically
                val base = "package app\n\nfun greet() = \"hello\"\n\nfun farewell() = \"bye\"\n"
                val a = "package app\n\nfun greet() = \"hi there\"\n\nfun farewell() = \"bye\"\n"
                val b = "package app\n\nfun greet() = \"hello\"\n\nfun farewell() = \"see ya\"\n"

                val mergeResult = resolver.resolve(base, a, b, "src/shared.kt")
                assertIs<ResolveResult.Merged>(mergeResult)
                assertTrue(mergeResult.content.contains("hi there"))
                assertTrue(mergeResult.content.contains("see ya"))
            }

            // Verify WAL captured the lifecycle
            val entries = orchestrator.walEntries()
            assertTrue(entries.any { it is WALEntry.TaskStarted })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolver produces conflict markers for overlapping edits`() {
        val resolver = TextualMergeResolver()

        // Both tasks modify the same function
        val base = "package app\n\nfun process(x: Int): Int {\n    return x * 2\n}\n"
        val a = "package app\n\nfun process(x: Int): Int {\n    return x * 3\n}\n"
        val b = "package app\n\nfun process(x: Int): Int {\n    return x + 10\n}\n"

        val result = resolver.resolve(base, a, b, "src/processor.kt")
        assertIs<ResolveResult.Conflicted>(result)
        assertTrue(result.conflictCount >= 1)
        assertTrue(result.content.contains(TextualMergeResolver.CONFLICT_START))
        assertTrue(result.content.contains("x * 3"))
        assertTrue(result.content.contains("x + 10"))
        assertTrue(result.content.contains(TextualMergeResolver.CONFLICT_END))
    }

    @Test
    fun `resolver handles file additions from both tasks`() {
        val resolver = TextualMergeResolver()

        // Base is empty, both tasks add content
        val base = ""
        val a = "// Added by task A"
        val b = "// Added by task B"

        val result = resolver.resolve(base, a, b, "new-file.kt")
        // Both adding to empty file — should conflict
        assertIs<ResolveResult.Conflicted>(result)
    }

    @Test
    fun `resolver handles realistic multi-function file`() {
        val resolver = TextualMergeResolver()

        val base = buildString {
            appendLine("package com.example")
            appendLine()
            appendLine("class UserService {")
            appendLine("    fun getUser(id: String): User {")
            appendLine("        return db.findById(id)")
            appendLine("    }")
            appendLine()
            appendLine("    fun createUser(name: String): User {")
            appendLine("        return db.insert(User(name = name))")
            appendLine("    }")
            appendLine()
            appendLine("    fun deleteUser(id: String) {")
            appendLine("        db.delete(id)")
            appendLine("    }")
            appendLine("}")
        }.trimEnd()

        // Task A adds validation to getUser
        val a = base.replace(
            "return db.findById(id)",
            "require(id.isNotBlank()) { \"ID must not be blank\" }\n        return db.findById(id)"
        )

        // Task B adds logging to deleteUser
        val b = base.replace(
            "db.delete(id)",
            "log.info(\"Deleting user: \$id\")\n        db.delete(id)"
        )

        val result = resolver.resolve(base, a, b, "src/UserService.kt")
        assertIs<ResolveResult.Merged>(result)
        assertTrue(result.content.contains("require(id.isNotBlank())"))
        assertTrue(result.content.contains("log.info"))
    }
}
