package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import io.qorche.core.ConflictDetector
import io.qorche.core.FileIndex
import io.qorche.core.Orchestrator
import io.qorche.core.SnapshotCreator
import io.qorche.core.TaskDefinition
import io.qorche.core.TaskGraph
import io.qorche.core.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Benchmarks measuring Qorche's real overhead and the net benefit of
 * MVCC-based parallel execution.
 *
 * Methodology:
 *
 * 1. MVCC OVERHEAD — How much does Qorche's snapshot/conflict detection cost?
 *    Measures snapshot (cold & warm) and conflict detection independently,
 *    expressed as absolute time and as % of a realistic step duration.
 *
 * 2. END-TO-END COMPARISON — Is parallel + MVCC overhead still faster than sequential raw?
 *    Compares:
 *      - Sequential raw:       steps run one after another, no snapshots
 *      - Parallel + MVCC:      steps run concurrently, with before/after snapshots + conflict detection
 *      - Net speedup:          (sequential raw) / (parallel + all MVCC overhead)
 *    This answers: "Even after paying for snapshots, do I come out ahead?"
 *
 * 3. SCALING — How do these numbers change with more files and more steps?
 */
@Tag("benchmark")
class BenchmarkTest {

    companion object {
        private val FILE_COUNTS = listOf(100, 1_000, 5_000, 10_000, 20_000)
        private val STEP_COUNTS = listOf(3, 5, 8, 12)
        private val LARGE_FILE_COUNTS = listOf(50_000, 100_000)

        // Simulated step durations — representative of real CI steps
        private const val STEP_DELAY_MS = 250L
    }

    private fun createTestRepo(root: Path, fileCount: Int) {
        val dirs = listOf("src", "test", "docs", "dist", "coverage", "config", "scripts", "assets")
        for (dir in dirs) {
            root.resolve(dir).createDirectories()
        }
        for (i in 1..fileCount) {
            val dir = dirs[i % dirs.size]
            root.resolve("$dir/file_$i.txt").writeText("content of file $i\n".repeat(10))
        }
    }

    /**
     * Create a test repo with realistic file sizes mimicking a real codebase.
     * Distribution: 40% small (100-500B), 30% medium (2-10KB), 20% large (20-80KB), 10% xlarge (100-300KB)
     */
    private fun createRealisticTestRepo(root: Path, fileCount: Int) {
        val dirs = listOf("src", "test", "docs", "lib", "config", "assets")
        for (dir in dirs) {
            root.resolve(dir).createDirectories()
        }
        val rng = java.util.Random(42) // deterministic for reproducibility
        for (i in 1..fileCount) {
            val dir = dirs[i % dirs.size]
            val bucket = rng.nextInt(100)
            val size = when {
                bucket < 40 -> 100 + rng.nextInt(400)       // small: 100-500B
                bucket < 70 -> 2_000 + rng.nextInt(8_000)   // medium: 2-10KB
                bucket < 90 -> 20_000 + rng.nextInt(60_000) // large: 20-80KB
                else -> 100_000 + rng.nextInt(200_000)      // xlarge: 100-300KB
            }
            val content = buildString {
                val line = "// line content for file $i with some realistic code padding\n"
                while (length < size) append(line)
            }
            root.resolve("$dir/file_$i.kt").writeText(content)
        }
    }

    private fun createPipelineRunners(stepCount: Int, delayMs: Long = STEP_DELAY_MS): List<Pair<String, MockAgentRunner>> {
        val steps = listOf(
            "lint" to listOf("src/lint-report.txt"),
            "test" to listOf("coverage/report.txt"),
            "build" to listOf("dist/bundle.txt"),
            "docs" to listOf("docs/generated.txt"),
            "security" to listOf("config/security-report.txt"),
            "format" to listOf("src/format-report.txt"),
            "typecheck" to listOf("dist/typecheck.txt"),
            "license" to listOf("docs/license-check.txt"),
            "bundle-analysis" to listOf("dist/bundle-stats.txt"),
            "e2e-prep" to listOf("test/e2e-setup.txt"),
            "asset-hash" to listOf("assets/manifest.txt"),
            "script-validate" to listOf("scripts/validate-report.txt"),
        )
        return steps.take(stepCount).map { (name, files) ->
            name to MockAgentRunner(filesToTouch = files, delayMs = delayMs)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  1. MVCC OVERHEAD — What does Qorche's safety cost?
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `mvcc overhead across file counts`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  MVCC OVERHEAD — cost of snapshots & conflict detection (step = ${STEP_DELAY_MS}ms)")
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %12s │ %12s".format(
            "Files", "Cold Snap", "Warm Snap", "Conflict", "Overhead/step", "% of step"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────────┼──────────────")

        for (fileCount in FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-overhead-$fileCount")
            try {
                createTestRepo(root, fileCount)
                val fileIndex = FileIndex()

                // Cold snapshot
                val coldStart = System.currentTimeMillis()
                val snap1 = SnapshotCreator.create(root, "cold", fileIndex = fileIndex)
                val coldMs = System.currentTimeMillis() - coldStart

                // Warm snapshot (what you'd pay on subsequent runs)
                val warmStart = System.currentTimeMillis()
                val snap2 = SnapshotCreator.create(root, "warm", fileIndex = fileIndex)
                val warmMs = System.currentTimeMillis() - warmStart

                // Conflict detection (comparing two snapshots)
                val conflictStart = System.currentTimeMillis()
                // Run 100 times to get measurable number, then average
                repeat(100) { SnapshotCreator.diff(snap1, snap2) }
                val conflictMs = (System.currentTimeMillis() - conflictStart) / 100.0

                // Per-step overhead = 2 warm snapshots (before + after) + 1 conflict check
                val overheadPerStep = warmMs * 2 + conflictMs
                val overheadPct = overheadPerStep / STEP_DELAY_MS * 100

                println("  %-12s │ %8dms │ %8dms │ %7.1fms │ %9.0fms │ %9.1f%%".format(
                    "%,d".format(fileCount), coldMs, warmMs, conflictMs, overheadPerStep, overheadPct
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Overhead/step = 2× warm snapshot (before + after) + conflict detection")
        println("  % of step     = overhead relative to a ${STEP_DELAY_MS}ms step (lower = better)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  2. END-TO-END — Sequential raw vs Parallel + MVCC
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `end-to-end sequential raw vs parallel with mvcc`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  END-TO-END — 5 steps × ${STEP_DELAY_MS}ms each: sequential (no snapshots) vs parallel (with MVCC)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %12s │ %10s │ %8s".format(
            "Files", "Seq (raw)", "Par+MVCC", "MVCC portion", "Time saved", "Speedup"))
        println("  ─────────────┼────────────┼────────────┼──────────────┼────────────┼──────────")

        for (fileCount in FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-e2e-$fileCount")
            try {
                createTestRepo(root, fileCount)

                // --- Sequential raw: no snapshots, just run steps one by one ---
                val seqPipeline = createPipelineRunners(5)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // --- Parallel + full MVCC: snapshots before, run all, snapshot after, detect conflicts ---
                val parPipeline = createPipelineRunners(5)
                val fileIndex = FileIndex()

                val parTotalStart = System.currentTimeMillis()

                // Before snapshot (warm — assume FileIndex populated from prior run)
                SnapshotCreator.create(root, "pre-warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                // Run all steps in parallel
                val mvccStart = System.currentTimeMillis()
                val stepResults = parPipeline.map { (name, runner) ->
                    async {
                        val files = mutableListOf<String>()
                        runner.run(name, root) {}.onEach { event ->
                            if (event is AgentEvent.FileModified) files.add(event.path)
                        }.collect()
                        name to files
                    }
                }.awaitAll()

                // After snapshot
                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)

                // Conflict detection
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parTotalStart
                val mvccOverhead = parTotalMs - (System.currentTimeMillis() - mvccStart - (System.currentTimeMillis() - parTotalStart - parTotalMs).coerceAtLeast(0))

                // Simpler: just measure the MVCC portion
                val parStepsOnlyMs = System.currentTimeMillis() - mvccStart
                val mvccPortionMs = parTotalMs - STEP_DELAY_MS.coerceAtMost(parTotalMs) // approximate

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"
                val mvccPortion = parTotalMs - STEP_DELAY_MS // rough: total - max(step delay)

                println("  %-12s │ %8dms │ %8dms │ %8dms │ %7dms │ %8s".format(
                    "%,d".format(fileCount), seqRawMs, parTotalMs,
                    mvccPortion.coerceAtLeast(0), timeSaved, speedup
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Seq (raw)     = steps run sequentially, zero overhead (baseline)")
        println("  Par+MVCC      = parallel steps + before/after snapshots + conflict detection")
        println("  MVCC portion  = approximate snapshot & conflict overhead within Par+MVCC")
        println("  Time saved    = Seq (raw) - Par+MVCC (positive = faster)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  3. SCALING — More steps = more benefit
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `scaling with step count`() = runBlocking {
        val fileCount = 5_000
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  SCALING — %,d files, ${STEP_DELAY_MS}ms/step, varying step count".format(fileCount))
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-8s │ %10s │ %10s │ %10s │ %8s │ %14s".format(
            "Steps", "Seq (raw)", "Par+MVCC", "Time saved", "Speedup", "MVCC amortised"))
        println("  ─────────┼────────────┼────────────┼────────────┼──────────┼────────────────")

        for (stepCount in STEP_COUNTS) {
            val root = Files.createTempDirectory("qorche-scale-$stepCount")
            try {
                createTestRepo(root, fileCount)

                // Sequential raw
                val seqPipeline = createPipelineRunners(stepCount)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // Parallel + MVCC
                val parPipeline = createPipelineRunners(stepCount)
                val fileIndex = FileIndex()
                val parStart = System.currentTimeMillis()

                SnapshotCreator.create(root, "warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                parPipeline.map { (name, runner) ->
                    async { runner.run(name, root) {}.collect() }
                }.awaitAll()

                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parStart

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"
                // MVCC cost amortised per step
                val mvccTotal = (parTotalMs - STEP_DELAY_MS).coerceAtLeast(0)
                val mvccPerStep = if (stepCount > 0) mvccTotal / stepCount else 0

                println("  %-8d │ %8dms │ %8dms │ %7dms │ %8s │ %10dms/step".format(
                    stepCount, seqRawMs, parTotalMs, timeSaved, speedup, mvccPerStep
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  MVCC amortised = (Par+MVCC - max step delay) / step count")
        println("  As steps increase, MVCC cost per step drops — overhead is mostly fixed")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  4. CONFLICT DETECTION — Correctness
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `conflict detection catches overlapping writes`() = runBlocking {
        val root = Files.createTempDirectory("qorche-bench-conflict")
        try {
            createTestRepo(root, 50)
            val fileIndex = FileIndex()

            val base = SnapshotCreator.create(root, "base", fileIndex = fileIndex)

            val agentA = MockAgentRunner(filesToTouch = listOf("src/file_1.txt"), delayMs = 50)
            agentA.run("agent A", root) {}.collect()
            val snapshotA = SnapshotCreator.create(root, "after agent A", fileIndex = fileIndex)

            root.resolve("src/file_1.txt").writeText("content of file 1\n".repeat(10))

            val agentB = MockAgentRunner(filesToTouch = listOf("src/file_1.txt", "test/file_2.txt"), delayMs = 50)
            agentB.run("agent B", root) {}.collect()
            val snapshotB = SnapshotCreator.create(root, "after agent B", fileIndex = fileIndex)

            val report = ConflictDetector.detectConflicts(base, snapshotA, snapshotB)

            println()
            println("═══════════════════════════════════════════════════════════════")
            println("  CONFLICT DETECTION — correctness verification")
            println("═══════════════════════════════════════════════════════════════")
            println("  Agent A modified:  [src/file_1.txt]")
            println("  Agent B modified:  [src/file_1.txt, test/file_2.txt]")
            println("  ───────────────────────────────────────────")
            println("  Conflicts found:   ${report.conflicts}")
            println("  Agent A only:      ${report.agentAOnly}")
            println("  Agent B only:      ${report.agentBOnly}")
            println("  Correct:           ${report.hasConflicts && "src/file_1.txt" in report.conflicts && "test/file_2.txt" !in report.conflicts}")
            println()

            assertTrue(report.hasConflicts, "Should detect conflict on src/file_1.txt")
            assertTrue("src/file_1.txt" in report.conflicts)
            assertFalse("test/file_2.txt" in report.conflicts)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  5. REALISTIC FILE SIZES — Does SHA-256 throughput matter?
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `realistic file sizes vs uniform small files`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  REALISTIC FILE SIZES — comparing uniform 150B files vs real codebase distribution")
        println("  Distribution: 40% small (100-500B), 30% medium (2-10KB), 20% large (20-80KB), 10% xlarge (100-300KB)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-8s │ %12s │ %12s │ %12s │ %12s │ %10s │ %10s".format(
            "Files", "Uniform Cold", "Uniform Warm", "Real Cold", "Real Warm", "Ratio Cold", "Ratio Warm"))
        println("  ─────────┼──────────────┼──────────────┼──────────────┼──────────────┼────────────┼────────────")

        for (fileCount in listOf(500, 1_000, 5_000)) {
            val uniformRoot = Files.createTempDirectory("qorche-bench-uniform-$fileCount")
            val realisticRoot = Files.createTempDirectory("qorche-bench-realistic-$fileCount")
            try {
                createTestRepo(uniformRoot, fileCount)
                createRealisticTestRepo(realisticRoot, fileCount)

                val uniformIndex = FileIndex()
                val realisticIndex = FileIndex()

                // Uniform — cold
                val uColdStart = System.currentTimeMillis()
                SnapshotCreator.create(uniformRoot, "cold", fileIndex = uniformIndex)
                val uColdMs = System.currentTimeMillis() - uColdStart

                // Uniform — warm
                val uWarmStart = System.currentTimeMillis()
                SnapshotCreator.create(uniformRoot, "warm", fileIndex = uniformIndex)
                val uWarmMs = System.currentTimeMillis() - uWarmStart

                // Realistic — cold
                val rColdStart = System.currentTimeMillis()
                SnapshotCreator.create(realisticRoot, "cold", fileIndex = realisticIndex)
                val rColdMs = System.currentTimeMillis() - rColdStart

                // Realistic — warm
                val rWarmStart = System.currentTimeMillis()
                SnapshotCreator.create(realisticRoot, "warm", fileIndex = realisticIndex)
                val rWarmMs = System.currentTimeMillis() - rWarmStart

                val ratioCold = if (uColdMs > 0) "%.1fx".format(rColdMs.toDouble() / uColdMs) else "N/A"
                val ratioWarm = if (uWarmMs > 0) "%.1fx".format(rWarmMs.toDouble() / uWarmMs) else "N/A"

                println("  %-8s │ %10dms │ %10dms │ %10dms │ %10dms │ %10s │ %10s".format(
                    "%,d".format(fileCount), uColdMs, uWarmMs, rColdMs, rWarmMs, ratioCold, ratioWarm
                ))
            } finally {
                uniformRoot.toFile().deleteRecursively()
                realisticRoot.toFile().deleteRecursively()
            }
        }
        println()
        println("  Ratio = realistic / uniform (>1x means realistic files are slower to hash)")
        println("  Cold  = first snapshot (no cache). Warm = second snapshot (mtime cache hit)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  6. DAG PROPAGATION — Is failure skip logic fast at scale?
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `dag propagation overhead at scale`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  DAG PROPAGATION — failure skip propagation time for deep/wide DAGs")
        println("  First task fails, all dependents skipped. Measures pure graph overhead.")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %12s │ %8s".format(
            "DAG shape", "Tasks", "Skipped", "Time", "Per task"))
        println("  ─────────────┼────────────┼────────────┼──────────────┼──────────")

        // Deep chain: task-1 → task-2 → ... → task-N
        for (depth in listOf(10, 50, 100, 500)) {
            val root = Files.createTempDirectory("qorche-dag-deep-$depth")
            try {
                root.resolve("src").createDirectories()
                root.resolve("src/main.kt").writeText("fun main() {}")

                val defs = (1..depth).map { i ->
                    TaskDefinition(
                        id = "task-$i",
                        instruction = "task-$i",
                        dependsOn = if (i == 1) emptyList() else listOf("task-${i - 1}"),
                        files = listOf("output/task-$i.txt")
                    )
                }
                val graph = TaskGraph(defs)
                val orchestrator = Orchestrator(root)
                // First task fails, rest should be skipped
                val runner = object : AgentRunner {
                    override fun run(instruction: String, workingDirectory: Path,
                                     onOutput: (String) -> Unit): Flow<AgentEvent> = flow {
                        emit(AgentEvent.Completed(exitCode = 1)) // fail immediately
                    }.flowOn(Dispatchers.IO)
                }

                val start = System.currentTimeMillis()
                val result = orchestrator.runGraphParallel("bench", graph, runner)
                val elapsed = System.currentTimeMillis() - start

                val perTask = if (depth > 0) "%.2fms".format(elapsed.toDouble() / depth) else "N/A"

                println("  %-12s │ %10d │ %10d │ %9dms │ %8s".format(
                    "chain($depth)", depth, result.skippedTasks, elapsed, perTask
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        // Wide fan-out: root → [N leaves]
        for (width in listOf(10, 50, 100, 500)) {
            val root = Files.createTempDirectory("qorche-dag-wide-$width")
            try {
                root.resolve("src").createDirectories()
                root.resolve("src/main.kt").writeText("fun main() {}")

                val defs = mutableListOf(
                    TaskDefinition(id = "root", instruction = "root", files = listOf("output/root.txt"))
                )
                for (i in 1..width) {
                    defs.add(TaskDefinition(
                        id = "leaf-$i", instruction = "leaf-$i",
                        dependsOn = listOf("root"),
                        files = listOf("output/leaf-$i.txt")
                    ))
                }
                val graph = TaskGraph(defs)
                val orchestrator = Orchestrator(root)
                val runner = object : AgentRunner {
                    override fun run(instruction: String, workingDirectory: Path,
                                     onOutput: (String) -> Unit): Flow<AgentEvent> = flow {
                        emit(AgentEvent.Completed(exitCode = 1))
                    }.flowOn(Dispatchers.IO)
                }

                val start = System.currentTimeMillis()
                val result = orchestrator.runGraphParallel("bench", graph, runner)
                val elapsed = System.currentTimeMillis() - start

                val totalTasks = width + 1
                val perTask = "%.2fms".format(elapsed.toDouble() / totalTasks)

                println("  %-12s │ %10d │ %10d │ %9dms │ %8s".format(
                    "fan($width)", totalTasks, result.skippedTasks, elapsed, perTask
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Time = wall-clock for entire graph execution (first task fails, rest skip)")
        println("  Per task = time / total tasks (pure graph traversal overhead per node)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  9. LARGE-SCALE (opt-in) — 50k and 100k files
    // ─────────────────────────────────────────────────────────────

    @Test
    @Tag("large-scale")
    fun `large-scale mvcc overhead`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  LARGE-SCALE: MVCC OVERHEAD (step = ${STEP_DELAY_MS}ms)")
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %12s │ %12s │ %10s".format(
            "Files", "Cold Snap", "Warm Snap", "Conflict", "Overhead/step", "% of step", "Setup"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────────┼──────────────┼────────────")

        for (fileCount in LARGE_FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-large-overhead-$fileCount")
            try {
                val setupStart = System.currentTimeMillis()
                createTestRepo(root, fileCount)
                val setupMs = System.currentTimeMillis() - setupStart

                val fileIndex = FileIndex()

                val coldStart = System.currentTimeMillis()
                val snap1 = SnapshotCreator.create(root, "cold", fileIndex = fileIndex)
                val coldMs = System.currentTimeMillis() - coldStart

                val warmStart = System.currentTimeMillis()
                val snap2 = SnapshotCreator.create(root, "warm", fileIndex = fileIndex)
                val warmMs = System.currentTimeMillis() - warmStart

                val conflictStart = System.currentTimeMillis()
                repeat(100) { SnapshotCreator.diff(snap1, snap2) }
                val conflictMs = (System.currentTimeMillis() - conflictStart) / 100.0

                val overheadPerStep = warmMs * 2 + conflictMs
                val overheadPct = overheadPerStep / STEP_DELAY_MS * 100

                println("  %-12s │ %8dms │ %8dms │ %7.1fms │ %9.0fms │ %9.1f%% │ %8dms".format(
                    "%,d".format(fileCount), coldMs, warmMs, conflictMs, overheadPerStep, overheadPct, setupMs
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  6. COLD START — First-run performance with no cache
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `cold start vs warm orchestrator execution`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  COLD START — first run (no FileIndex) vs warm run (cached)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-8s │ %12s │ %12s │ %10s │ %8s".format(
            "Files", "Cold (1st)", "Warm (2nd)", "Difference", "Speedup"))
        println("  ─────────┼──────────────┼──────────────┼────────────┼──────────")

        for (fileCount in listOf(100, 500, 1_000, 5_000)) {
            val root = Files.createTempDirectory("qorche-cold-$fileCount")
            try {
                createTestRepo(root, fileCount)

                // Cold: no .qorche directory, no FileIndex
                val coldOrch = Orchestrator(root)
                val coldRunner = PerTaskMockRunner(delayMs = 10)
                val coldDefs = listOf(
                    TaskDefinition(id = "cold-task", instruction = "cold-task",
                        type = TaskType.IMPLEMENT, files = listOf("output/cold-task.txt"))
                )
                val coldGraph = TaskGraph(coldDefs)

                val coldStart = System.currentTimeMillis()
                coldOrch.runGraphParallel("bench", coldGraph, coldRunner)
                val coldMs = System.currentTimeMillis() - coldStart

                // Warm: .qorche exists with FileIndex from cold run
                val warmOrch = Orchestrator(root)
                val warmRunner = PerTaskMockRunner(delayMs = 10)
                val warmDefs = listOf(
                    TaskDefinition(id = "warm-task", instruction = "warm-task",
                        type = TaskType.IMPLEMENT, files = listOf("output/warm-task.txt"))
                )
                val warmGraph = TaskGraph(warmDefs)

                val warmStart = System.currentTimeMillis()
                warmOrch.runGraphParallel("bench", warmGraph, warmRunner)
                val warmMs = System.currentTimeMillis() - warmStart

                val diff = coldMs - warmMs
                val speedup = if (warmMs > 0) "%.1fx".format(coldMs.toDouble() / warmMs) else "N/A"

                println("  %-8s │ %10dms │ %10dms │ %7dms │ %8s".format(
                    "%,d".format(fileCount), coldMs, warmMs, diff, speedup
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Cold = first run, no .qorche/ directory, no FileIndex cache")
        println("  Warm = second run, FileIndex loaded from previous run")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  8. PARALLEL EXECUTION (M3) — Sequential vs Parallel via Orchestrator
    // ─────────────────────────────────────────────────────────────

    /**
     * A mock runner that writes to specific files based on the instruction,
     * simulating heterogeneous workers touching different parts of the repo.
     */
    private class PerTaskMockRunner(
        private val delayMs: Long = STEP_DELAY_MS
    ) : AgentRunner {
        override fun run(
            instruction: String,
            workingDirectory: Path,
            onOutput: (String) -> Unit
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.Output("Starting: $instruction"))
            delay(delayMs)

            // Each task writes to its own unique file
            val file = workingDirectory.resolve("output/$instruction.txt")
            Files.createDirectories(file.parent)
            Files.writeString(file, "Result of $instruction\n")
            emit(AgentEvent.FileModified("output/$instruction.txt"))

            emit(AgentEvent.Completed(exitCode = 0))
        }.flowOn(Dispatchers.IO)
    }

    private fun buildParallelGraph(taskCount: Int): Pair<List<TaskDefinition>, TaskGraph> {
        // All tasks independent — maximum parallelism
        val defs = (1..taskCount).map { i ->
            TaskDefinition(
                id = "task-$i",
                instruction = "task-$i",
                type = TaskType.IMPLEMENT,
                files = listOf("output/task-$i.txt")
            )
        }
        return defs to TaskGraph(defs)
    }

    private fun buildDiamondGraph(parallelWidth: Int): Pair<List<TaskDefinition>, TaskGraph> {
        // explore → [parallel-1..N] → integrate
        val defs = mutableListOf(
            TaskDefinition(id = "explore", instruction = "explore", type = TaskType.EXPLORE,
                files = listOf("output/explore.txt"))
        )
        for (i in 1..parallelWidth) {
            defs.add(TaskDefinition(
                id = "parallel-$i", instruction = "parallel-$i",
                dependsOn = listOf("explore"),
                files = listOf("output/parallel-$i.txt")
            ))
        }
        defs.add(TaskDefinition(
            id = "integrate", instruction = "integrate",
            dependsOn = (1..parallelWidth).map { "parallel-$it" },
            files = listOf("output/integrate.txt")
        ))
        return defs to TaskGraph(defs)
    }

    @Test
    fun `parallel vs sequential orchestrator execution`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  PARALLEL EXECUTION (M3) — runGraph() vs runGraphParallel() via Orchestrator")
        println("  Each task = ${STEP_DELAY_MS}ms, all tasks independent (max parallelism)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-8s │ %10s │ %10s │ %10s │ %8s │ %8s".format(
            "Tasks", "Sequential", "Parallel", "Time saved", "Speedup", "Max ∥"))
        println("  ─────────┼────────────┼────────────┼────────────┼──────────┼──────────")

        for (taskCount in listOf(2, 4, 6, 8, 12)) {
            val seqRoot = Files.createTempDirectory("qorche-bench-seq-$taskCount")
            val parRoot = Files.createTempDirectory("qorche-bench-par-$taskCount")
            try {
                createTestRepo(seqRoot, 100)
                createTestRepo(parRoot, 100)

                val (seqDefs, seqGraph) = buildParallelGraph(taskCount)
                val (_, parGraph) = buildParallelGraph(taskCount)

                // Sequential
                val seqOrch = Orchestrator(seqRoot)
                val seqRunner = PerTaskMockRunner()
                val seqStart = System.currentTimeMillis()
                seqOrch.runGraph("bench", seqGraph, seqRunner)
                val seqMs = System.currentTimeMillis() - seqStart

                // Parallel
                val parOrch = Orchestrator(parRoot)
                val parRunner = PerTaskMockRunner()
                val parStart = System.currentTimeMillis()
                parOrch.runGraphParallel("bench", parGraph, parRunner)
                val parMs = System.currentTimeMillis() - parStart

                val saved = seqMs - parMs
                val speedup = if (parMs > 0) "%.1fx".format(seqMs.toDouble() / parMs) else "∞"

                println("  %-8d │ %8dms │ %8dms │ %7dms │ %8s │ %8d".format(
                    taskCount, seqMs, parMs, saved, speedup, taskCount
                ))
            } finally {
                seqRoot.toFile().deleteRecursively()
                parRoot.toFile().deleteRecursively()
            }
        }
        println()
        println("  Sequential = runGraph() (topological order, one at a time)")
        println("  Parallel   = runGraphParallel() (concurrent within groups + MVCC)")
        println("  Max ∥      = maximum tasks running concurrently")
        println()
    }

    @Test
    fun `diamond DAG parallel benchmark`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  DIAMOND DAG — explore → [N parallel] → integrate (${STEP_DELAY_MS}ms/task)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %8s │ %18s".format(
            "Par Width", "Sequential", "Parallel", "Time saved", "Speedup", "Expected seq ms"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────┼────────────────────")

        for (width in listOf(2, 4, 6, 8)) {
            val seqRoot = Files.createTempDirectory("qorche-bench-diamond-seq-$width")
            val parRoot = Files.createTempDirectory("qorche-bench-diamond-par-$width")
            try {
                createTestRepo(seqRoot, 100)
                createTestRepo(parRoot, 100)

                val (_, seqGraph) = buildDiamondGraph(width)
                val (_, parGraph) = buildDiamondGraph(width)

                val totalTasks = width + 2  // explore + N parallel + integrate
                val expectedSeqMs = totalTasks * STEP_DELAY_MS

                // Sequential
                val seqOrch = Orchestrator(seqRoot)
                val seqStart = System.currentTimeMillis()
                seqOrch.runGraph("bench", seqGraph, PerTaskMockRunner())
                val seqMs = System.currentTimeMillis() - seqStart

                // Parallel
                val parOrch = Orchestrator(parRoot)
                val parStart = System.currentTimeMillis()
                parOrch.runGraphParallel("bench", parGraph, PerTaskMockRunner())
                val parMs = System.currentTimeMillis() - parStart

                val saved = seqMs - parMs
                val speedup = if (parMs > 0) "%.1fx".format(seqMs.toDouble() / parMs) else "∞"

                println("  %-12d │ %8dms │ %8dms │ %7dms │ %8s │ %14dms".format(
                    width, seqMs, parMs, saved, speedup, expectedSeqMs
                ))
            } finally {
                seqRoot.toFile().deleteRecursively()
                parRoot.toFile().deleteRecursively()
            }
        }
        println()
        println("  Par Width     = number of tasks in the parallel middle layer")
        println("  Sequential    = all tasks one-by-one (explore, par-1, par-2, ..., integrate)")
        println("  Parallel      = explore → [all par in parallel] → integrate")
        println("  Expected seq  = total tasks × ${STEP_DELAY_MS}ms (theoretical minimum)")
        println()
    }

    @Test
    @Tag("large-scale")
    fun `large-scale end-to-end`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  LARGE-SCALE: END-TO-END — 5 steps × ${STEP_DELAY_MS}ms")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %8s │ %10s".format(
            "Files", "Seq (raw)", "Par+MVCC", "Time saved", "Speedup", "Setup"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────┼────────────")

        for (fileCount in LARGE_FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-large-e2e-$fileCount")
            try {
                val setupStart = System.currentTimeMillis()
                createTestRepo(root, fileCount)
                val setupMs = System.currentTimeMillis() - setupStart

                // Sequential raw
                val seqPipeline = createPipelineRunners(5)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // Parallel + MVCC
                val parPipeline = createPipelineRunners(5)
                val fileIndex = FileIndex()
                val parStart = System.currentTimeMillis()

                SnapshotCreator.create(root, "warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                parPipeline.map { (name, runner) ->
                    async { runner.run(name, root) {}.collect() }
                }.awaitAll()

                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parStart

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"

                println("  %-12s │ %8dms │ %8dms │ %7dms │ %8s │ %8dms".format(
                    "%,d".format(fileCount), seqRawMs, parTotalMs, timeSaved, speedup, setupMs
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
    }
}
