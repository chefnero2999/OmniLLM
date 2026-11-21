package com.omni.llm.ui

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Exports a generated project as a zip through the system share sheet.
 *
 * Used from two places — the preview footer and the full file browser — because "give me my files"
 * should not depend on which screen the user happens to be on. No storage permission is needed: the
 * zip goes to the app cache and is handed out as a content:// URI via FileProvider.
 */
object ProjectExport {

    fun share(context: Context, dir: File) {
        runCatching {
            val zip = File(context.cacheDir, "${dir.name}.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
                dir.walkTopDown()
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { f ->
                        out.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).path))
                        f.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.projects", zip,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export project"))
        }
    }
}
