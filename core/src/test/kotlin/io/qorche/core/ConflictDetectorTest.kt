package io.qorche.core

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictDetectorTest {

    @Test
    fun `detectGroupConflicts finds overlapping files`() {
        val changes = mapOf(
            "task-a" to setOf("src/main.kt", "src/shared.kt"),
            "task-b" to setOf("src/shared.kt", "src/other.kt")
        )

        val conflicts = ConflictDetector.detectGroupConflicts(changes)

        assertEquals(1, conflicts.size)
        assertEquals(setOf("src/shared.kt"), conflicts[0].conflictingFiles)
    }

    @Test
    fun `detectGroupConflicts returns empty for disjoint changes`() {
        val changes = mapOf(
            "task-a" to setOf("src/a.kt"),
            "task-b" to setOf("src/b.kt"),
            "task-c" to setOf("src/c.kt")
        )

        val conflicts = ConflictDetector.detectGroupConflicts(changes)

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `detectGroupConflicts handles empty change sets`() {
        val changes = mapOf(
            "task-a" to emptySet<String>(),
            "task-b" to setOf("src/b.kt")
        )

        val conflicts = ConflictDetector.detectGroupConflicts(changes)

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `detectGroupConflicts finds multiple pairwise conflicts`() {
        val changes = mapOf(
            "task-a" to setOf("shared.kt"),
            "task-b" to setOf("shared.kt"),
            "task-c" to setOf("shared.kt")
        )

        val conflicts = ConflictDetector.detectGroupConflicts(changes)

        assertEquals(3, conflicts.size)
    }

    @Test
    fun `detectGroupConflicts with single task returns no conflicts`() {
        val changes = mapOf("task-a" to setOf("src/a.kt", "src/b.kt"))

        val conflicts = ConflictDetector.detectGroupConflicts(changes)

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `resolveConflicts earlier task in group order wins`() {
        val conflicts = listOf(
            ConflictDetector.TaskConflict("task-b", "task-a", setOf("shared.kt"))
        )
        val groupOrder = listOf("task-a", "task-b", "task-c")

        val resolution = ConflictDetector.resolveConflicts(conflicts, groupOrder)

        assertTrue("task-a" in resolution.winners)
        assertTrue("task-b" in resolution.losers)
    }

    @Test
    fun `resolveConflicts loser in any conflict must retry`() {
        val conflicts = listOf(
            ConflictDetector.TaskConflict("task-a", "task-b", setOf("file1.kt")),
            ConflictDetector.TaskConflict("task-b", "task-c", setOf("file2.kt"))
        )
        val groupOrder = listOf("task-a", "task-b", "task-c")

        val resolution = ConflictDetector.resolveConflicts(conflicts, groupOrder)

        // task-b loses to task-a, task-c loses to task-b
        assertTrue("task-b" in resolution.losers)
        assertTrue("task-c" in resolution.losers)
        // task-b won against task-c but lost against task-a, so it's still a loser
        assertFalse("task-b" in resolution.winners)
    }

    @Test
    fun `resolveConflicts with empty conflicts`() {
        val resolution = ConflictDetector.resolveConflicts(emptyList(), listOf("task-a"))

        assertTrue(resolution.winners.isEmpty())
        assertTrue(resolution.losers.isEmpty())
    }

    @Test
    fun `detectScopeViolations finds undeclared changes`() {
        val allChanged = setOf("src/a.kt", "src/b.kt", "config/secret.yaml")
        val taskScopes = mapOf(
            "task-a" to listOf("src/a.kt"),
            "task-b" to listOf("src/b.kt")
        )
        val changesByTask = mapOf(
            "task-a" to setOf("src/a.kt"),
            "task-b" to setOf("src/b.kt")
        )

        val violations = ConflictDetector.detectScopeViolations(allChanged, taskScopes, changesByTask)

        assertEquals(1, violations.size)
        assertEquals(setOf("config/secret.yaml"), violations[0].undeclaredFiles)
    }

    @Test
    fun `detectScopeViolations returns empty when all changes accounted for`() {
        val allChanged = setOf("src/a.kt", "src/b.kt")
        val taskScopes = mapOf(
            "task-a" to listOf("src/a.kt"),
            "task-b" to listOf("src/b.kt")
        )
        val changesByTask = mapOf(
            "task-a" to setOf("src/a.kt"),
            "task-b" to setOf("src/b.kt")
        )

        val violations = ConflictDetector.detectScopeViolations(allChanged, taskScopes, changesByTask)

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `detectScopeViolations suspects all tasks in group`() {
        val allChanged = setOf("src/a.kt", "mystery.txt")
        val taskScopes = mapOf(
            "task-a" to listOf("src"),
            "task-b" to listOf("docs")
        )
        val changesByTask = mapOf(
            "task-a" to setOf("src/a.kt")
        )

        val violations = ConflictDetector.detectScopeViolations(allChanged, taskScopes, changesByTask)

        assertEquals(1, violations.size)
        assertTrue("task-a" in violations[0].suspectTaskIds)
    }

    @Test
    fun `detectConflicts between two snapshots`() {
        val now = Clock.System.now()
        val base = Snapshot(
            id = "base",
            timestamp = now,
            description = "base",
            fileHashes = mapOf("a.kt" to "h1", "b.kt" to "h2", "c.kt" to "h3")
        )
        val agentA = Snapshot(
            id = "a",
            timestamp = now,
            description = "agent-a",
            parentId = "base",
            fileHashes = mapOf("a.kt" to "h1-changed", "b.kt" to "h2", "c.kt" to "h3")
        )
        val agentB = Snapshot(
            id = "b",
            timestamp = now,
            description = "agent-b",
            parentId = "base",
            fileHashes = mapOf("a.kt" to "h1", "b.kt" to "h2-changed", "c.kt" to "h3")
        )

        val report = ConflictDetector.detectConflicts(base, agentA, agentB)

        assertFalse(report.hasConflicts)
        assertEquals(setOf("a.kt"), report.agentAOnly)
        assertEquals(setOf("b.kt"), report.agentBOnly)
    }

    @Test
    fun `detectConflicts finds write-write conflict`() {
        val now = Clock.System.now()
        val base = Snapshot(
            id = "base",
            timestamp = now,
            description = "base",
            fileHashes = mapOf("shared.kt" to "original")
        )
        val agentA = Snapshot(
            id = "a",
            timestamp = now,
            description = "agent-a",
            parentId = "base",
            fileHashes = mapOf("shared.kt" to "version-a")
        )
        val agentB = Snapshot(
            id = "b",
            timestamp = now,
            description = "agent-b",
            parentId = "base",
            fileHashes = mapOf("shared.kt" to "version-b")
        )

        val report = ConflictDetector.detectConflicts(base, agentA, agentB)

        assertTrue(report.hasConflicts)
        assertEquals(setOf("shared.kt"), report.conflicts)
    }
}
