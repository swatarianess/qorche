package io.qorche.core

/** Exception thrown when a cycle is detected in the task dependency graph. */
class CycleDetectedException(val cycle: List<String>) :
    IllegalArgumentException("Cycle detected in task graph: ${cycle.joinToString(" -> ")}")

/** Directed acyclic graph of tasks with dependency-aware scheduling. */
class TaskGraph(definitions: List<TaskDefinition>) {

    private val nodes: Map<String, TaskNode> =
        definitions.associate { it.id to TaskNode(it) }

    private val adjacency: Map<String, List<String>> =
        definitions.associate { it.id to it.dependsOn }

    init {
        for (def in definitions) {
            for (dep in def.dependsOn) {
                require(dep in nodes) { "Task '${def.id}' depends on unknown task '$dep'" }
            }
        }
        detectCycles()
    }

    /** Returns task IDs in topological order (dependencies before dependents). */
    fun topologicalSort(): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            for (dep in adjacency[id].orEmpty()) {
                visit(dep)
            }
            result.add(id)
        }

        for (id in nodes.keys.sorted()) {
            visit(id)
        }
        return result
    }

    /** Returns pending tasks whose dependencies have all completed. */
    fun readyTasks(): List<TaskNode> =
        nodes.values.filter { node ->
            node.status == TaskStatus.PENDING &&
                adjacency[node.definition.id].orEmpty().all { depId ->
                    nodes[depId]?.status == TaskStatus.COMPLETED
                }
        }

    /** Groups tasks into waves that can execute concurrently within each wave. */
    fun parallelGroups(): List<List<String>> {
        val remaining = nodes.keys.toMutableSet()
        val completed = mutableSetOf<String>()
        val groups = mutableListOf<List<String>>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                adjacency[id].orEmpty().all { it in completed }
            }
            if (ready.isEmpty()) break
            groups.add(ready)
            remaining.removeAll(ready.toSet())
            completed.addAll(ready)
        }
        return groups
    }

    /** Returns the task node with the given [id], or null if not found. */
    operator fun get(id: String): TaskNode? = nodes[id]

    /** Returns all task nodes in the graph. */
    fun allNodes(): Collection<TaskNode> = nodes.values

    private fun detectCycles() {
        val white = nodes.keys.toMutableSet() // unvisited
        val gray = mutableSetOf<String>()      // in progress
        val black = mutableSetOf<String>()     // finished

        fun dfs(id: String, path: MutableList<String>) {
            white.remove(id)
            gray.add(id)
            path.add(id)

            for (dep in adjacency[id].orEmpty()) {
                if (dep in gray) {
                    val cycleStart = path.indexOf(dep)
                    throw CycleDetectedException(path.subList(cycleStart, path.size) + dep)
                }
                if (dep in white) {
                    dfs(dep, path)
                }
            }

            path.removeAt(path.lastIndex)
            gray.remove(id)
            black.add(id)
        }

        for (id in nodes.keys.sorted()) {
            if (id in white) {
                dfs(id, mutableListOf())
            }
        }
    }
}
