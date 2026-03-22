package io.qorche.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskYamlParserTest {

    @Test
    fun `parse simple task definition`() {
        val yaml = """
            project: test-project
            tasks:
              - id: task-1
                instruction: "Do something"
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        assertEquals("test-project", project.project)
        assertEquals(1, project.tasks.size)
        assertEquals("task-1", project.tasks[0].id)
        assertEquals("Do something", project.tasks[0].instruction)
        assertEquals(TaskType.IMPLEMENT, project.tasks[0].type)
    }

    @Test
    fun `parse task with dependencies and files`() {
        val yaml = """
            project: auth-refactor
            tasks:
              - id: explore
                instruction: "Map the auth module"
                type: explore
              - id: backend
                instruction: "Implement JWT endpoint"
                depends_on: [explore]
                files: [src/auth/login.ts, src/auth/types.ts]
              - id: frontend
                instruction: "Build login form"
                depends_on: [explore]
                files: [src/ui/LoginForm.tsx]
              - id: tests
                instruction: "Write integration tests"
                depends_on: [backend, frontend]
              - id: verify
                instruction: "Run full test suite"
                depends_on: [tests]
                type: verify
        """.trimIndent()

        val project = TaskYamlParser.parse(yaml)
        assertEquals("auth-refactor", project.project)
        assertEquals(5, project.tasks.size)

        val explore = project.tasks[0]
        assertEquals(TaskType.EXPLORE, explore.type)
        assertTrue(explore.dependsOn.isEmpty())

        val backend = project.tasks[1]
        assertEquals(listOf("explore"), backend.dependsOn)
        assertEquals(listOf("src/auth/login.ts", "src/auth/types.ts"), backend.files)

        val tests = project.tasks[3]
        assertEquals(listOf("backend", "frontend"), tests.dependsOn)

        val verify = project.tasks[4]
        assertEquals(TaskType.VERIFY, verify.type)
    }

    @Test
    fun `parseToGraph builds valid graph`() {
        val yaml = """
            project: test
            tasks:
              - id: a
                instruction: "first"
              - id: b
                instruction: "second"
                depends_on: [a]
        """.trimIndent()

        val graph = TaskYamlParser.parseToGraph(yaml)
        val order = graph.topologicalSort()
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun `parseToGraph detects cycles`() {
        val yaml = """
            project: test
            tasks:
              - id: a
                instruction: "first"
                depends_on: [b]
              - id: b
                instruction: "second"
                depends_on: [a]
        """.trimIndent()

        assertFailsWith<CycleDetectedException> {
            TaskYamlParser.parseToGraph(yaml)
        }
    }

    @Test
    fun `parse rejects empty content`() {
        assertFailsWith<IllegalArgumentException> {
            TaskYamlParser.parse("")
        }
    }

    @Test
    fun `parse rejects blank content`() {
        assertFailsWith<IllegalArgumentException> {
            TaskYamlParser.parse("   \n  ")
        }
    }

    @Test
    fun `parse handles malformed yaml`() {
        val yaml = """
            this is not: valid: yaml: [[[
        """.trimIndent()

        assertFailsWith<TaskParseException> {
            TaskYamlParser.parse(yaml)
        }
    }

    @Test
    fun `parseToGraph rejects empty task list`() {
        val yaml = """
            project: test
            tasks: []
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            TaskYamlParser.parseToGraph(yaml)
        }
    }

    @Test
    fun `parseToGraph detects unknown dependencies`() {
        val yaml = """
            project: test
            tasks:
              - id: a
                instruction: "first"
                depends_on: [nonexistent]
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            TaskYamlParser.parseToGraph(yaml)
        }
    }

    @Test
    fun `parallel groups identified correctly`() {
        val yaml = """
            project: test
            tasks:
              - id: explore
                instruction: "explore"
              - id: backend
                instruction: "backend"
                depends_on: [explore]
              - id: frontend
                instruction: "frontend"
                depends_on: [explore]
              - id: integrate
                instruction: "integrate"
                depends_on: [backend, frontend]
        """.trimIndent()

        val graph = TaskYamlParser.parseToGraph(yaml)
        val groups = graph.parallelGroups()

        assertEquals(3, groups.size)
        assertEquals(listOf("explore"), groups[0])
        assertTrue(groups[1].containsAll(listOf("backend", "frontend")))
        assertEquals(2, groups[1].size)
        assertEquals(listOf("integrate"), groups[2])
    }
}
