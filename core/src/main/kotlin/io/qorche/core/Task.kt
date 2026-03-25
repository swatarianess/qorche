package io.qorche.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskType {
    @SerialName("explore") EXPLORE,
    @SerialName("implement") IMPLEMENT,
    @SerialName("verify") VERIFY,
    @SerialName("test") TEST
}

@Serializable
enum class TaskStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED
}

@Serializable
data class TaskDefinition(
    val id: String,
    val instruction: String,
    val type: TaskType = TaskType.IMPLEMENT,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    @SerialName("max_retries")
    val maxRetries: Int = 1
)

@Serializable
data class TaskProject(
    val project: String,
    val tasks: List<TaskDefinition>
)

data class TaskNode(
    val definition: TaskDefinition,
    var status: TaskStatus = TaskStatus.PENDING,
    var beforeSnapshotId: String? = null,
    var afterSnapshotId: String? = null,
    var result: AgentResult? = null,
    var retryCount: Int = 0
)
