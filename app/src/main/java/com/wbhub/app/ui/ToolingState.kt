package com.wbhub.app.ui

/** Everything the 工程 page renders; filled from the tooling database. */
data class ToolingState(
    val projects: List<ToolingProject> = emptyList(),
    val selected: ToolingProjectDetail? = null,
    val loading: Boolean = false,
    val error: String = "",
)

data class ToolingProject(
    val id: String,
    val name: String,
    val packageName: String,
    val versionName: String,
    val status: String,
    val updatedAt: Long,
)

data class ToolingArtifact(
    val kind: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val origin: String,
)

data class ToolingOperation(
    val tool: String,
    val status: String,
    val summary: String,
    val startedAt: Long,
    val outputRef: String?,
)

data class ToolingProjectDetail(
    val project: ToolingProject,
    val workspace: String,
    val artifacts: List<ToolingArtifact>,
    val operations: List<ToolingOperation>,
)
