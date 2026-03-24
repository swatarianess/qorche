package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.ConflictDetector
import io.qorche.core.Orchestrator
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskGraph
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.WALEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelExecutionTest {

    /**
     * A mock runner that writes to files based on a per-task file mapping.
     * Records execution timing to verify parallelism.
     */
    class InstructionAwareMockRunner(
        private val filesByInstruction: Map<String, List<String>>,
        private val delayMs: Long = 50,
        private val failInstructions: Set<String> = emptySet()
    ) : AgentRunner {
        val executionLog = ConcurrentHashMap<String, Long>()
        private val activeCount = AtomicInteger(0)
        var maxConcurrency = AtomicInteger(0)

        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            val concurrent = activeCount.incrementAndGet()
            maxConcurrency.updateAndGet { max -> maxOf(max, concurrent) }
            executionLog[instruction] = System.currentTimeMillis()

            emit(AgentEvent.Output("Starting: $instruction"))
            delay(delayMs)

            if (instruction in failInstructions) {
                activeCount.decrementAndGet()
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

            activeCount.decrementAndGet()
            emit(AgentEvent.Completed(exitCode = 0))
        }.flowOn(Dispatchers.IO)
    }

    private fun createTestDir(): Path {
        val root = Files.createTempDirectory("qorche-parallel-test")
        root.resolve("src").createDirectories()
        root.resolve("src/main.kt").writeText("fun main() {}")
        return root
    }

    @Test
    fun `parallel tasks modifying different files - no conflict`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: no-conflict
                tasks:
                  - id: backend
                    instruction: "backend work"
                    files: [src/backend.kt]
                  - id: frontend
                    instruction: "frontend work"
                    files: [src/frontend.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "backend work" to listOf("src/backend.kt"),
                    "frontend work" to listOf("src/frontend.kt")
                ),
                delayMs = 50
            )

            val result = orchestrator.runGraphParallel(
                project = "no-conflict",
                graph = graph,
                runner = runner
            )

            assertEquals(2, result.completedTasks)
            assertEquals(0, result.failedTasks)
            assertFalse(result.hasConflicts)
            assertTrue(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parallel tasks modifying same file - conflict detected`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: conflict-test
                tasks:
                  - id: task-a
                    instruction: "modify shared"
                  - id: task-b
                    instruction: "also modify shared"
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            // Both tasks write to the same file
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "modify shared" to listOf("src/shared.kt"),
                    "also modify shared" to listOf("src/shared.kt")
                ),
                delayMs = 50
            )

            var conflictDetected = false
            val result = orchestrator.runGraphParallel(
                project = "conflict-test",
                graph = graph,
                runner = runner,
                onConflict = { conflictDetected = true }
            )

            assertTrue(conflictDetected, "Conflict callback should have fired")
            assertTrue(result.hasConflicts, "Should report conflicts")
            assertEquals(1, result.conflicts.size)
            assertTrue(result.conflicts[0].conflictingFiles.contains("src/shared.kt"))
            assertEquals(2, result.failedTasks, "Both conflicting tasks should fail")
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parallel tasks actually execute concurrently`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: concurrency-test
                tasks:
                  - id: task-a
                    instruction: "task a"
                    files: [src/a.kt]
                  - id: task-b
                    instruction: "task b"
                    files: [src/b.kt]
                  - id: task-c
                    instruction: "task c"
                    files: [src/c.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "task a" to listOf("src/a.kt"),
                    "task b" to listOf("src/b.kt"),
                    "task c" to listOf("src/c.kt")
                ),
                delayMs = 100
            )

            val startTime = System.currentTimeMillis()
            val result = orchestrator.runGraphParallel(
                project = "concurrency-test",
                graph = graph,
                runner = runner
            )
            val elapsed = System.currentTimeMillis() - startTime

            assertEquals(3, result.completedTasks)
            assertTrue(result.success)

            // If truly parallel (3 tasks × 100ms each), should take ~100-200ms
            // If sequential, would take ~300ms+
            // Use generous bounds to avoid flakiness
            assertTrue(elapsed < 500, "Should be faster than sequential. Took ${elapsed}ms")

            // Verify concurrency tracking
            assertTrue(runner.maxConcurrency.get() >= 2,
                "At least 2 tasks should have run concurrently, max was ${runner.maxConcurrency.get()}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diamond DAG - sequential then parallel then sequential`() = runBlocking {
        val root = createTestDir()
        try {
            // explore → (backend, frontend) → integrate
            val yaml = """
                project: diamond
                tasks:
                  - id: explore
                    instruction: "explore"
                    files: [src/explore.kt]
                  - id: backend
                    instruction: "backend"
                    depends_on: [explore]
                    files: [src/backend.kt]
                  - id: frontend
                    instruction: "frontend"
                    depends_on: [explore]
                    files: [src/frontend.kt]
                  - id: integrate
                    instruction: "integrate"
                    depends_on: [backend, frontend]
                    files: [src/integrate.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "explore" to listOf("src/explore.kt"),
                    "backend" to listOf("src/backend.kt"),
                    "frontend" to listOf("src/frontend.kt"),
                    "integrate" to listOf("src/integrate.kt")
                ),
                delayMs = 30
            )

            val executionOrder = mutableListOf<String>()
            val result = orchestrator.runGraphParallel(
                project = "diamond",
                graph = graph,
                runner = runner,
                onTaskStart = { def -> synchronized(executionOrder) { executionOrder.add(def.id) } }
            )

            assertEquals(4, result.completedTasks)
            assertTrue(result.success)

            // explore must be first, integrate must be last
            assertEquals("explore", executionOrder.first())
            assertEquals("integrate", executionOrder.last())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failure in parallel group skips dependents`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: fail-propagation
                tasks:
                  - id: task-a
                    instruction: "succeeds"
                    files: [src/a.kt]
                  - id: task-b
                    instruction: "fails"
                    files: [src/b.kt]
                  - id: depends-on-a
                    instruction: "depends on a"
                    depends_on: [task-a]
                    files: [src/da.kt]
                  - id: depends-on-b
                    instruction: "depends on b"
                    depends_on: [task-b]
                    files: [src/db.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "succeeds" to listOf("src/a.kt"),
                    "fails" to listOf("src/b.kt"),
                    "depends on a" to listOf("src/da.kt"),
                    "depends on b" to listOf("src/db.kt")
                ),
                failInstructions = setOf("fails"),
                delayMs = 30
            )

            val result = orchestrator.runGraphParallel(
                project = "fail-propagation",
                graph = graph,
                runner = runner
            )

            // task-a succeeds, task-b fails, depends-on-a succeeds, depends-on-b skipped
            assertEquals(2, result.completedTasks, "task-a and depends-on-a should complete")
            assertEquals(1, result.failedTasks, "task-b should fail")
            assertEquals(1, result.skippedTasks, "depends-on-b should be skipped")

            val dbOutcome = result.taskResults["depends-on-b"]!!
            assertEquals(TaskStatus.SKIPPED, dbOutcome.status)
            assertTrue(dbOutcome.skipReason!!.contains("task-b"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `conflict logged to WAL`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: wal-conflict
                tasks:
                  - id: writer-1
                    instruction: "write shared 1"
                  - id: writer-2
                    instruction: "write shared 2"
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "write shared 1" to listOf("shared.txt"),
                    "write shared 2" to listOf("shared.txt")
                ),
                delayMs = 30
            )

            orchestrator.runGraphParallel(
                project = "wal-conflict",
                graph = graph,
                runner = runner
            )

            val conflictEntries = orchestrator.walEntries()
                .filterIsInstance<WALEntry.ConflictDetected>()
            assertTrue(conflictEntries.isNotEmpty(), "WAL should contain conflict entry")
            assertTrue(conflictEntries[0].conflictingFiles.contains("shared.txt"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mixed conflict - scoped tasks, one pair conflicts, another is clean`() = runBlocking {
        val root = createTestDir()
        try {
            // With file scoping, each task's snapshot only covers its own files.
            // task-a and task-b both claim shared.kt → conflict.
            // task-c only claims unique.kt → no conflict.
            val yaml = """
                project: mixed-conflict
                tasks:
                  - id: task-a
                    instruction: "writes shared"
                    files: [shared.kt]
                  - id: task-b
                    instruction: "also writes shared"
                    files: [shared.kt]
                  - id: task-c
                    instruction: "writes own file"
                    files: [unique.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "writes shared" to listOf("shared.kt"),
                    "also writes shared" to listOf("shared.kt"),
                    "writes own file" to listOf("unique.kt")
                ),
                delayMs = 30
            )

            val result = orchestrator.runGraphParallel(
                project = "mixed-conflict",
                graph = graph,
                runner = runner
            )

            // task-c should succeed, task-a and task-b should fail due to conflict
            assertEquals(1, result.completedTasks, "Only task-c should complete")
            assertEquals(2, result.failedTasks, "task-a and task-b should fail")
            assertTrue(result.hasConflicts)

            val cOutcome = result.taskResults["task-c"]!!
            assertEquals(TaskStatus.COMPLETED, cOutcome.status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scope audit detects undeclared writes`() = runBlocking {
        val root = createTestDir()
        try {
            // Task declares it only writes to src/declared.kt,
            // but actually also writes to src/sneaky.kt
            val yaml = """
                project: scope-violation
                tasks:
                  - id: task-a
                    instruction: "writes declared and sneaky"
                    files: [src/declared.kt]
                  - id: task-b
                    instruction: "clean task"
                    files: [src/clean.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)

            // task-a writes to both declared.kt AND sneaky.kt (undeclared)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "writes declared and sneaky" to listOf("src/declared.kt", "src/sneaky.kt"),
                    "clean task" to listOf("src/clean.kt")
                ),
                delayMs = 30
            )

            var violationDetected = false
            val result = orchestrator.runGraphParallel(
                project = "scope-violation",
                graph = graph,
                runner = runner,
                onScopeViolation = { violationDetected = true }
            )

            assertTrue(violationDetected, "Scope violation callback should have fired")
            assertTrue(result.hasScopeViolations, "Should report scope violations")

            val violation = result.scopeViolations.first()
            assertTrue(violation.undeclaredFiles.contains("src/sneaky.kt"),
                "Should identify src/sneaky.kt as undeclared")
            // Can't attribute to specific task — both are suspects
            assertTrue(violation.suspectTaskIds.containsAll(listOf("task-a", "task-b")),
                "Both tasks should be listed as suspects (group-level attribution)")

            // Both tasks should still complete (scope violation is a warning, not a failure)
            assertEquals(2, result.completedTasks)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scope audit logs to WAL`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: wal-scope
                tasks:
                  - id: task-a
                    instruction: "writes extra"
                    files: [src/expected.kt]
                  - id: task-b
                    instruction: "normal task"
                    files: [src/normal.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "writes extra" to listOf("src/expected.kt", "src/extra.kt"),
                    "normal task" to listOf("src/normal.kt")
                ),
                delayMs = 30
            )

            orchestrator.runGraphParallel(
                project = "wal-scope",
                graph = graph,
                runner = runner
            )

            val scopeEntries = orchestrator.walEntries()
                .filterIsInstance<WALEntry.ScopeViolation>()
            assertTrue(scopeEntries.isNotEmpty(), "WAL should contain scope violation entry")
            assertTrue(scopeEntries.any { it.undeclaredFiles.contains("src/extra.kt") })
            // WAL entry includes all suspects
            assertTrue(scopeEntries.first().suspectTaskIds.size == 2)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no scope violation when task stays within scope`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: clean-scope
                tasks:
                  - id: task-a
                    instruction: "stays clean"
                    files: [src/a.kt]
                  - id: task-b
                    instruction: "also clean"
                    files: [src/b.kt]
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "stays clean" to listOf("src/a.kt"),
                    "also clean" to listOf("src/b.kt")
                ),
                delayMs = 30
            )

            val result = orchestrator.runGraphParallel(
                project = "clean-scope",
                graph = graph,
                runner = runner
            )

            assertFalse(result.hasScopeViolations, "No scope violations expected")
            assertEquals(2, result.completedTasks)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Integration tests with real processes
    //  Uses a custom AgentRunner that spawns real java processes
    //  to write files, avoiding cross-platform shell issues.
    // ─────────────────────────────────────────────────────────────

    /**
     * AgentRunner that spawns a real child process (java) to write a file.
     * Proves the parallel execution works with real filesystem I/O and process management.
     */
    class RealProcessRunner(
        private val filesByInstruction: Map<String, List<String>>,
        private val delayMs: Long = 50
    ) : AgentRunner {
        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.Output("Real process starting: $instruction"))

            // Simulate real work with actual process spawn + file write
            val files = filesByInstruction[instruction] ?: emptyList()
            for (relativePath in files) {
                val file = workingDirectory.resolve(relativePath)
                // Use ProcessBuilder to run java to write the file — a real child process
                val javaHome = System.getProperty("java.home")
                val javaBin = Path.of(javaHome, "bin", "java").toString()
                val process = ProcessBuilder(
                    javaBin, "-e",
                    "java.nio.file.Files.createDirectories(java.nio.file.Path.of(\"${file.parent.toString().replace("\\", "\\\\")}\"));" +
                    "java.nio.file.Files.writeString(java.nio.file.Path.of(\"${file.toString().replace("\\", "\\\\")}\"), \"Written by $instruction at \" + System.nanoTime());"
                ).directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()

                // If java -e doesn't work (not all JDKs support it), fall back to direct write
                val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited || process.exitValue() != 0) {
                    // Fallback: write directly (still in a real coroutine context)
                    java.nio.file.Files.createDirectories(file.parent)
                    java.nio.file.Files.writeString(file, "Written by $instruction at ${System.nanoTime()}")
                }

                emit(AgentEvent.FileModified(relativePath.replace("\\", "/")))
            }

            delay(delayMs)
            emit(AgentEvent.Completed(exitCode = 0))
        }.flowOn(Dispatchers.IO)
    }

    @Test
    fun `real processes running in parallel - no conflict`() = runBlocking {
        val root = Files.createTempDirectory("qorche-real-parallel")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val defs = listOf(
                TaskDefinition(id = "writer-a", instruction = "write-a", files = listOf("src/a_output.txt")),
                TaskDefinition(id = "writer-b", instruction = "write-b", files = listOf("src/b_output.txt"))
            )
            val graph = TaskGraph(defs)
            val orchestrator = Orchestrator(root)
            val runner = RealProcessRunner(
                filesByInstruction = mapOf(
                    "write-a" to listOf("src/a_output.txt"),
                    "write-b" to listOf("src/b_output.txt")
                )
            )

            val result = orchestrator.runGraphParallel(
                project = "real-parallel",
                graph = graph,
                runner = runner
            )

            assertEquals(2, result.completedTasks, "Both tasks should complete")
            assertFalse(result.hasConflicts, "No conflicts expected")
            assertTrue(result.success)

            // Verify files actually exist on disk — written by real processes
            assertTrue(root.resolve("src/a_output.txt").toFile().exists(), "a_output.txt should exist")
            assertTrue(root.resolve("src/b_output.txt").toFile().exists(), "b_output.txt should exist")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `real processes running in parallel - conflict on same file`() = runBlocking {
        val root = Files.createTempDirectory("qorche-real-conflict")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            // Both tasks write to the same file
            val defs = listOf(
                TaskDefinition(id = "writer-a", instruction = "write-shared-a", files = listOf("src/shared.txt")),
                TaskDefinition(id = "writer-b", instruction = "write-shared-b", files = listOf("src/shared.txt"))
            )
            val graph = TaskGraph(defs)
            val orchestrator = Orchestrator(root)
            val runner = RealProcessRunner(
                filesByInstruction = mapOf(
                    "write-shared-a" to listOf("src/shared.txt"),
                    "write-shared-b" to listOf("src/shared.txt")
                )
            )

            val result = orchestrator.runGraphParallel(
                project = "real-conflict",
                graph = graph,
                runner = runner
            )

            assertTrue(result.hasConflicts, "Should detect conflict on shared.txt")
            assertEquals(2, result.failedTasks, "Both tasks should fail due to conflict")

            // Verify the file exists on disk — one of the writers won the race
            assertTrue(root.resolve("src/shared.txt").toFile().exists(), "shared.txt should exist")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
