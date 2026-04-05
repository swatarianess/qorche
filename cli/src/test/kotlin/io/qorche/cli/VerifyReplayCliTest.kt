package io.qorche.cli

import io.qorche.core.WALEntry
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI-level smoke tests for the verify and replay features.
 *
 * Uses [QorcheTestHarness] DSL to cut boilerplate.
 */
@Tag("smoke")
class VerifyReplayCliTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val echoOk = if (isWindows) "cmd /c echo ok" else "echo ok"
    private val exitFail = if (isWindows) "cmd /c exit 1" else "false"

    // --- Verify pipeline via run ---

    @Test
    fun `run with verify section produces verify results in JSON`() = qorcheTest {
        yaml("""
            project: verify-json-test
            verify:
              command: "$echoOk"
              timeout_seconds: 30
            tasks:
              - id: task1
                instruction: do thing
                files: [src/output.kt]
        """)
        writes("do thing" to listOf("src/output.kt"))

        runJson { obj ->
            assertTrue(obj["success"]!!.jsonPrimitive.boolean)
            val verifyResults = obj["verifyResults"]!!.jsonArray
            assertEquals(1, verifyResults.size)
            assertTrue(verifyResults[0].jsonObject["success"]!!.jsonPrimitive.boolean)
            assertEquals(0, verifyResults[0].jsonObject["exit_code"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `run with failing verify and fail policy reports failure in JSON`() = qorcheTest {
        yaml("""
            project: verify-fail-test
            verify:
              command: "$exitFail"
              on_failure: fail
            tasks:
              - id: task1
                instruction: do thing
                files: [src/output.kt]
        """)
        writes("do thing" to listOf("src/output.kt"))

        runJson { obj ->
            assertFalse(obj["success"]!!.jsonPrimitive.boolean)
            val verifyResults = obj["verifyResults"]!!.jsonArray
            assertEquals(1, verifyResults.size)
            assertFalse(verifyResults[0].jsonObject["success"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `run with failing verify and warn policy still completes tasks`() = qorcheTest {
        yaml("""
            project: verify-warn-test
            verify:
              command: "$exitFail"
              on_failure: warn
            tasks:
              - id: task1
                instruction: first
                files: [src/a.kt]
              - id: task2
                instruction: second
                depends_on: [task1]
                files: [src/b.kt]
        """)
        writes(
            "first" to listOf("src/a.kt"),
            "second" to listOf("src/b.kt")
        )

        run { result ->
            assertEquals(2, result.completedTasks)
            assertTrue(result.hasVerifyFailure)
        }
    }

    @Test
    fun `run without verify section produces empty verify results`() = qorcheTest {
        fixture("parallel-no-conflict.yaml")
        writes(
            "task a" to listOf("src/a.kt"),
            "task b" to listOf("src/b.kt"),
            "task c" to listOf("src/c.kt")
        )

        run { result ->
            assertTrue(result.success)
            assertTrue(result.verifyResults.isEmpty())
        }
    }

    // --- Replay / WAL assertions ---

    @Test
    fun `WAL contains verify entries after run with verify`() = qorcheTest {
        yaml("""
            project: wal-verify-test
            verify:
              command: "$echoOk"
            tasks:
              - id: task1
                instruction: do thing
                files: [src/output.kt]
        """)
        writes("do thing" to listOf("src/output.kt"))

        run { result ->
            assertTrue(result.success)
        }

        // Read WAL directly from the orchestrator
        val orch = orchestrator()
        val entries = orch.walEntries()
        val verifyEntries = entries.filterIsInstance<WALEntry.VerifyCompleted>()
        assertEquals(1, verifyEntries.size)
        assertTrue(verifyEntries[0].success)
        assertEquals(echoOk, verifyEntries[0].command)
    }

    @Test
    fun `WAL captures task lifecycle for replay`() = qorcheTest {
        fixture("diamond-dag.yaml")
        writes(
            "explore" to listOf("src/explore.kt"),
            "backend" to listOf("src/backend.kt"),
            "frontend" to listOf("src/frontend.kt"),
            "integrate" to listOf("src/integrate.kt")
        )

        run { result ->
            assertTrue(result.success)
            assertEquals(4, result.completedTasks)
        }

        val orch = orchestrator()
        val entries = orch.walEntries()

        val starts = entries.filterIsInstance<WALEntry.TaskStarted>().map { it.taskId }
        val completes = entries.filterIsInstance<WALEntry.TaskCompleted>().map { it.taskId }

        assertEquals(4, starts.size)
        assertEquals(4, completes.size)
        // explore must start first (no deps), integrate must start last (depends on both)
        assertEquals("explore", starts.first())
        assertEquals("integrate", starts.last())
    }

    // --- DSL convenience tests ---

    @Test
    fun `plan via DSL produces valid output`() = qorcheTest {
        fixture("diamond-dag.yaml")

        plan { obj ->
            assertEquals(4, obj["tasks"]!!.jsonPrimitive.int)
            assertEquals(3, obj["groups"]!!.jsonArray.size)
        }
    }

    @Test
    fun `task failure skips dependents`() = qorcheTest {
        yaml("""
            project: fail-test
            tasks:
              - id: task1
                instruction: will fail
              - id: task2
                instruction: depends on failure
                depends_on: [task1]
        """)
        fails("will fail")

        run { result ->
            assertEquals(1, result.failedTasks)
            assertEquals(1, result.skippedTasks)
            assertFalse(result.success)
        }
    }
}
