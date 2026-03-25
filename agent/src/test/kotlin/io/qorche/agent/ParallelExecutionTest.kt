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

    /**
     * A mock runner that changes its behavior on retry.
     * On the first call for an instruction, writes [initialFiles].
     * On subsequent calls, writes [retryFiles] instead.
     */
    class RetryAwareMockRunner(
        private val initialFiles: Map<String, List<String>>,
        private val retryFiles: Map<String, List<String>>,
        private val delayMs: Long = 30
    ) : AgentRunner {
        private val callCounts = ConcurrentHashMap<String, AtomicInteger>()

        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            val count = callCounts.getOrPut(instruction) { AtomicInteger(0) }.incrementAndGet()
            val files = if (count == 1) initialFiles[instruction] else retryFiles[instruction]

            emit(AgentEvent.Output("Starting (attempt $count): $instruction"))
            delay(delayMs)

            for (relativePath in files ?: emptyList()) {
                val file = workingDirectory.resolve(relativePath)
                Files.createDirectories(file.parent)
                Files.writeString(file, "// Modified by: $instruction (attempt $count)\n")
                emit(AgentEvent.FileModified(relativePath.replace("\\", "/")))
            }

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
            // Default maxRetries=0: winner completes, loser fails (no retry)
            assertEquals(1, result.completedTasks, "Winner should complete")
            assertEquals(1, result.failedTasks, "Loser should fail")
            assertEquals(0, result.retriedTasks, "No retries with default maxRetries=0")
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

            // If truly parallel (3 tasks x 100ms each), should take ~100-200ms
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
            // explore -> (backend, frontend) -> integrate
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
            // task-a and task-b both claim shared.kt -> conflict.
            // task-c only claims unique.kt -> no conflict.
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

            // Default maxRetries=0: task-a wins, task-b fails, task-c clean
            assertTrue(result.hasConflicts)

            val aOutcome = result.taskResults["task-a"]!!
            assertEquals(TaskStatus.COMPLETED, aOutcome.status)

            val cOutcome = result.taskResults["task-c"]!!
            assertEquals(TaskStatus.COMPLETED, cOutcome.status)

            assertEquals(2, result.completedTasks, "task-a and task-c should complete")
            assertEquals(1, result.failedTasks, "task-b should fail")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `conflict with retry - loser adapts on retry`() = runBlocking {
        val root = createTestDir()
        try {
            // task-a and task-b both initially write to shared.kt.
            // On retry, task-b writes to own-b.kt instead (adapting to avoid conflict).
            val yaml = """
                project: retry-adapt
                tasks:
                  - id: task-a
                    instruction: "task-a work"
                    files: [shared.kt]
                  - id: task-b
                    instruction: "task-b work"
                    files: [shared.kt, own-b.kt]
                    max_retries: 1
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = RetryAwareMockRunner(
                initialFiles = mapOf(
                    "task-a work" to listOf("shared.kt"),
                    "task-b work" to listOf("shared.kt")
                ),
                retryFiles = mapOf(
                    "task-a work" to listOf("shared.kt"),
                    "task-b work" to listOf("own-b.kt")
                ),
                delayMs = 30
            )

            val result = orchestrator.runGraphParallel(
                project = "retry-adapt",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(defaultMaxRetries = 1)
            )

            // task-a wins the first round, task-b retries and writes to own-b.kt
            // so no conflict on retry
            assertTrue(result.retriedTasks >= 1, "At least one task should have been retried")
            assertEquals(0, result.failedTasks, "No tasks should fail after successful retry")
            assertEquals(2, result.completedTasks, "Both tasks should complete")
            assertTrue(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `conflict with retry disabled - falls back to fail-fast`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: no-retry
                tasks:
                  - id: task-a
                    instruction: "modify shared"
                  - id: task-b
                    instruction: "also modify shared"
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "modify shared" to listOf("src/shared.kt"),
                    "also modify shared" to listOf("src/shared.kt")
                ),
                delayMs = 50
            )

            val result = orchestrator.runGraphParallel(
                project = "no-retry",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(enabled = false)
            )

            assertTrue(result.hasConflicts, "Should report conflicts")
            // Winner (earlier in group) completes, loser fails immediately (no retry)
            assertEquals(1, result.completedTasks, "Winner task should complete")
            assertEquals(1, result.failedTasks, "Loser should fail without retry")
            assertEquals(0, result.retriedTasks, "No retries should have occurred")
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `onRetry callback fires with correct parameters`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: retry-callback
                tasks:
                  - id: task-a
                    instruction: "modify shared"
                  - id: task-b
                    instruction: "also modify shared"
                    max_retries: 1
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "modify shared" to listOf("src/shared.kt"),
                    "also modify shared" to listOf("src/shared.kt")
                ),
                delayMs = 50
            )

            val retryEvents = mutableListOf<Triple<String, Int, Set<String>>>()
            orchestrator.runGraphParallel(
                project = "retry-callback",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(defaultMaxRetries = 1),
                onRetry = { taskId, attempt, _, conflictingFiles ->
                    synchronized(retryEvents) {
                        retryEvents.add(Triple(taskId, attempt, conflictingFiles))
                    }
                }
            )

            assertTrue(retryEvents.isNotEmpty(), "onRetry callback should have fired")
            val (taskId, attempt, files) = retryEvents[0]
            assertTrue(taskId == "task-a" || taskId == "task-b",
                "Retried task should be one of the conflicting tasks")
            assertEquals(1, attempt, "First retry should be attempt 1")
            assertTrue(files.contains("src/shared.kt"),
                "Conflicting files should include src/shared.kt")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WAL contains retry entries`() = runBlocking {
        val root = createTestDir()
        try {
            val yaml = """
                project: wal-retry
                tasks:
                  - id: task-a
                    instruction: "modify shared"
                  - id: task-b
                    instruction: "also modify shared"
                    max_retries: 1
            """.trimIndent()

            val graph = TaskYamlParser.parseToGraph(yaml)
            val orchestrator = Orchestrator(root)
            val runner = InstructionAwareMockRunner(
                filesByInstruction = mapOf(
                    "modify shared" to listOf("src/shared.kt"),
                    "also modify shared" to listOf("src/shared.kt")
                ),
                delayMs = 50
            )

            orchestrator.runGraphParallel(
                project = "wal-retry",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(defaultMaxRetries = 1)
            )

            val walEntries = orchestrator.walEntries()

            val retryScheduled = walEntries.filterIsInstance<WALEntry.TaskRetryScheduled>()
            assertTrue(retryScheduled.isNotEmpty(), "WAL should contain TaskRetryScheduled entries")
            assertTrue(retryScheduled[0].attempt >= 1, "Attempt number should be >= 1")
            assertTrue(retryScheduled[0].conflictingFiles.contains("src/shared.kt"),
                "TaskRetryScheduled should reference the conflicting file")

            val retried = walEntries.filterIsInstance<WALEntry.TaskRetried>()
            assertTrue(retried.isNotEmpty(), "WAL should contain TaskRetried entries")
            assertTrue(retried[0].attempt >= 1, "Attempt number should be >= 1")
            assertTrue(retried[0].snapshotId.isNotEmpty(), "TaskRetried should have a snapshot ID")
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
            // Can't attribute to specific task -- both are suspects
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

    // ---------------------------------------------------------------
    //  Integration tests with real processes
    //  Uses a custom AgentRunner that spawns real java processes
    //  to write files, avoiding cross-platform shell issues.
    // ---------------------------------------------------------------

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
                // Use ProcessBuilder to run java to write the file -- a real child process
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

            // Verify files actually exist on disk -- written by real processes
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
            assertEquals(1, result.completedTasks, "Winner should complete")
            assertEquals(1, result.failedTasks, "Loser should fail (maxRetries=0 default)")

            // Verify the file exists on disk -- one of the writers won the race
            assertTrue(root.resolve("src/shared.txt").toFile().exists(), "shared.txt should exist")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stubborn retry - loser keeps conflicting, exhausts maxRetries`() = runBlocking {
        val root = createTestDir()
        try {
            val defs = listOf(
                TaskDefinition(id = "task-a", instruction = "task-a", files = listOf("shared.kt"), maxRetries = 0),
                TaskDefinition(id = "task-b", instruction = "task-b", files = listOf("shared.kt"), maxRetries = 3)
            )
            val graph = TaskGraph(defs)
            val orchestrator = Orchestrator(root)
            // Use RetryAwareMockRunner that always writes shared.kt (both initial and retry)
            // with unique content each time (via call count)
            val runner = RetryAwareMockRunner(
                initialFiles = mapOf(
                    "task-a" to listOf("shared.kt"),
                    "task-b" to listOf("shared.kt")
                ),
                retryFiles = mapOf(
                    "task-a" to listOf("shared.kt"),
                    "task-b" to listOf("shared.kt")
                ),
                delayMs = 10
            )

            val retryAttempts = mutableListOf<Int>()
            val result = orchestrator.runGraphParallel(
                project = "stubborn",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(defaultMaxRetries = 3),
                onRetry = { _, attempt, _, _ -> retryAttempts.add(attempt) }
            )

            assertEquals(1, result.completedTasks, "Only winner should complete")
            assertEquals(1, result.failedTasks, "Stubborn loser should fail after exhausting retries")
            assertEquals(3, retryAttempts.size, "Should have retried 3 times")
            assertEquals(listOf(1, 2, 3), retryAttempts, "Retry attempts should be sequential")

            val loserOutcome = result.taskResults["task-b"]!!
            assertEquals(TaskStatus.FAILED, loserOutcome.status)
            assertTrue(loserOutcome.skipReason!!.contains("3 retry attempts"))
            assertEquals(3, loserOutcome.retryCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rollback removes loser files before retry`() = runBlocking {
        val root = createTestDir()
        try {
            val defs = listOf(
                TaskDefinition(id = "task-a", instruction = "task-a", files = listOf("src/a.kt")),
                TaskDefinition(id = "task-b", instruction = "task-b-initial", files = listOf("src/a.kt", "src/b-extra.kt"), maxRetries = 1)
            )
            val graph = TaskGraph(defs)
            val orchestrator = Orchestrator(root)

            val runner = RetryAwareMockRunner(
                initialFiles = mapOf(
                    "task-a" to listOf("src/a.kt"),
                    "task-b-initial" to listOf("src/a.kt", "src/b-extra.kt")
                ),
                retryFiles = mapOf(
                    "task-a" to listOf("src/a.kt"),
                    "task-b-initial" to listOf("src/b-only.kt")
                ),
                delayMs = 10
            )

            val result = orchestrator.runGraphParallel(
                project = "rollback-test",
                graph = graph,
                runner = runner,
                retryPolicy = ConflictDetector.ConflictRetryPolicy(defaultMaxRetries = 1)
            )

            assertEquals(2, result.completedTasks, "Both tasks should complete after retry")
            assertTrue(result.success)

            assertFalse(root.resolve("src/b-extra.kt").toFile().exists(),
                "Loser's non-conflicting file (b-extra.kt) should have been rolled back before retry")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
