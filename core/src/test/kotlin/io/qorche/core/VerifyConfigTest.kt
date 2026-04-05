package io.qorche.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyConfigTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `VerifyConfig defaults`() {
        val config = VerifyConfig(command = "npm test")
        assertEquals("npm test", config.command)
        assertEquals(300, config.timeoutSeconds)
        assertEquals(VerifyFailurePolicy.FAIL, config.onFailure)
    }

    @Test
    fun `VerifyConfig serialization round-trip`() {
        val config = VerifyConfig(
            command = "./gradlew test",
            timeoutSeconds = 600,
            onFailure = VerifyFailurePolicy.WARN
        )
        val encoded = json.encodeToString(VerifyConfig.serializer(), config)
        val decoded = json.decodeFromString(VerifyConfig.serializer(), encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `VerifyResult serialization round-trip`() {
        val result = VerifyResult(
            success = false,
            exitCode = 1,
            output = "test failed",
            elapsedMs = 5000,
            groupIndex = 2
        )
        val encoded = json.encodeToString(VerifyResult.serializer(), result)
        val decoded = json.decodeFromString(VerifyResult.serializer(), encoded)
        assertEquals(result, decoded)
    }

    @Test
    fun `GraphResult success is false when verify fails`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 1,
            completedTasks = 1,
            failedTasks = 0,
            skippedTasks = 0,
            verifyResults = listOf(
                VerifyResult(success = false, exitCode = 1, groupIndex = 0)
            )
        )
        assertFalse(result.success)
        assertTrue(result.hasVerifyFailure)
    }

    @Test
    fun `GraphResult success is true when verify passes`() {
        val result = Orchestrator.GraphResult(
            project = "test",
            taskResults = emptyMap(),
            totalTasks = 1,
            completedTasks = 1,
            failedTasks = 0,
            skippedTasks = 0,
            verifyResults = listOf(
                VerifyResult(success = true, exitCode = 0, groupIndex = 0)
            )
        )
        assertTrue(result.success)
        assertFalse(result.hasVerifyFailure)
    }

    @Test
    fun `TaskProject with verify config parses from YAML`() {
        val yaml = """
            project: test
            verify:
              command: npm test
              timeout_seconds: 120
              on_failure: warn
            tasks:
              - id: task1
                instruction: do something
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        val verify = project.verify
        assertEquals("npm test", verify?.command)
        assertEquals(120, verify?.timeoutSeconds)
        assertEquals(VerifyFailurePolicy.WARN, verify?.onFailure)
    }

    @Test
    fun `TaskProject without verify config parses correctly`() {
        val yaml = """
            project: test
            tasks:
              - id: task1
                instruction: do something
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        assertEquals(null, project.verify)
    }

    @Test
    fun `WAL VerifyCompleted serialization round-trip`() {
        val entry: WALEntry = WALEntry.VerifyCompleted(
            taskId = "verify-group-0",
            success = true,
            exitCode = 0,
            command = "npm test",
            groupIndex = 0
        )
        val encoded = json.encodeToString(WALEntry.serializer(), entry)
        val decoded = json.decodeFromString<WALEntry>(encoded)
        assertTrue(decoded is WALEntry.VerifyCompleted)
        assertEquals(0, (decoded as WALEntry.VerifyCompleted).exitCode)
        assertTrue(decoded.success)
    }
}
