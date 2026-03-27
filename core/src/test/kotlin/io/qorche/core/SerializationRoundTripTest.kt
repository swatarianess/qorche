package io.qorche.core

import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationRoundTripTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `RunResult round-trips through JSON`() {
        val original = Orchestrator.RunResult(
            agentResult = AgentResult(exitCode = 0, filesModified = listOf("src/main.kt")),
            diff = SnapshotDiff(
                added = setOf("src/new.kt"),
                modified = setOf("src/main.kt"),
                deleted = emptySet(),
                beforeId = "snap-before",
                afterId = "snap-after"
            ),
            beforeSnapshot = Snapshot(
                id = "snap-before",
                timestamp = Clock.System.now(),
                fileHashes = mapOf("src/main.kt" to "abc123"),
                description = "before test"
            ),
            afterSnapshot = Snapshot(
                id = "snap-after",
                timestamp = Clock.System.now(),
                fileHashes = mapOf("src/main.kt" to "def456", "src/new.kt" to "ghi789"),
                description = "after test"
            )
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Orchestrator.RunResult>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `TaskOutcome round-trips through JSON`() {
        val original = Orchestrator.TaskOutcome(
            taskId = "my-task",
            status = TaskStatus.COMPLETED,
            skipReason = null,
            retryCount = 2
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Orchestrator.TaskOutcome>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `TaskOutcome with null runResult round-trips`() {
        val original = Orchestrator.TaskOutcome(
            taskId = "skipped-task",
            status = TaskStatus.SKIPPED,
            skipReason = "Dependency 'x' failed",
            retryCount = 0
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Orchestrator.TaskOutcome>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `GraphResult round-trips through JSON`() {
        val original = Orchestrator.GraphResult(
            project = "test-project",
            taskResults = mapOf(
                "task-a" to Orchestrator.TaskOutcome("task-a", TaskStatus.COMPLETED, retryCount = 0),
                "task-b" to Orchestrator.TaskOutcome("task-b", TaskStatus.FAILED, skipReason = "exit 1")
            ),
            totalTasks = 2,
            completedTasks = 1,
            failedTasks = 1,
            skippedTasks = 0,
            retriedTasks = 0,
            conflicts = listOf(
                ConflictDetector.TaskConflict("task-a", "task-b", setOf("shared.kt"))
            ),
            scopeViolations = listOf(
                ConflictDetector.ScopeViolation(
                    undeclaredFiles = setOf("rogue.kt"),
                    suspectTaskIds = listOf("task-a", "task-b"),
                    declaredScopes = mapOf("task-a" to listOf("src/"))
                )
            )
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Orchestrator.GraphResult>(serialized)
        assertEquals(original, deserialized)
        assertEquals(true, deserialized.hasConflicts)
        assertEquals(true, deserialized.hasScopeViolations)
        assertEquals(false, deserialized.success)
    }

    @Test
    fun `TaskConflict round-trips through JSON`() {
        val original = ConflictDetector.TaskConflict(
            taskA = "agent-1",
            taskB = "agent-2",
            conflictingFiles = setOf("src/auth.kt", "src/login.kt")
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConflictDetector.TaskConflict>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `ConflictResolution round-trips through JSON`() {
        val original = ConflictDetector.ConflictResolution(
            winners = setOf("agent-1"),
            losers = setOf("agent-2"),
            conflicts = listOf(
                ConflictDetector.TaskConflict("agent-1", "agent-2", setOf("shared.kt"))
            )
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConflictDetector.ConflictResolution>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `ConflictReport round-trips through JSON`() {
        val original = ConflictDetector.ConflictReport(
            conflicts = setOf("shared.kt"),
            agentAOnly = setOf("a-only.kt"),
            agentBOnly = setOf("b-only.kt")
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConflictDetector.ConflictReport>(serialized)
        assertEquals(original, deserialized)
        assertEquals(true, deserialized.hasConflicts)
    }

    @Test
    fun `ConflictRetryPolicy round-trips through JSON`() {
        val original = ConflictDetector.ConflictRetryPolicy(
            defaultMaxRetries = 3,
            enabled = false
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConflictDetector.ConflictRetryPolicy>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `ScopeViolation round-trips through JSON`() {
        val original = ConflictDetector.ScopeViolation(
            undeclaredFiles = setOf("rogue.kt", "unexpected.kt"),
            suspectTaskIds = listOf("task-1", "task-2"),
            declaredScopes = mapOf(
                "task-1" to listOf("src/auth/"),
                "task-2" to listOf("src/api/")
            )
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ConflictDetector.ScopeViolation>(serialized)
        assertEquals(original, deserialized)
    }
}
