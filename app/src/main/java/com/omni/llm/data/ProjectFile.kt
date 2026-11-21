package com.omni.llm.data

/**
 * One file in a project folder.
 *
 * Lives in the data layer, not next to the screen that renders it: the agent view model needs to
 * describe a file without importing a Compose file. It is also the shape the file browser and any
 * future export/debug surface share.
 */
data class ProjectFile(
    val path: String,
    val sizeBytes: Long,
    val lines: Int,
    val isBinary: Boolean,
) {
    val sizeLabel: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
}
