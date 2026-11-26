package com.omni.llm.agent

import java.io.File

/**
 * The sandbox an agent project is allowed to touch.
 *
 * The model writes the paths, so the model can also write `../../databases/foo` or
 * `/data/data/com.omni.llm/shared_prefs/settings.xml`. Everything here exists to make that
 * impossible, and it is a separate object so it can be tested on a real JVM instead of trusted.
 *
 * Rules:
 *  - a path must be relative; absolute paths are refused
 *  - it must not contain a `..` segment
 *  - after canonicalisation it must still sit inside the project root (this is what defeats
 *    symlinks and `a/../../b` style tricks that survive a naive prefix check)
 *  - writes may not be empty, because an empty file is always a truncated stream, never intent
 */
object ProjectSandbox {

    data class Result(val ok: Boolean, val message: String) {
        fun asToolOutput(): String = message
    }

    /** @return the resolved file, or null when the path tries to leave the sandbox. */
    fun resolve(root: File, path: String): File? {
        val p = path.trim()
        if (p.isEmpty()) return null
        if (p.startsWith("/") || p.startsWith("\\")) return null
        if (p.contains('\u0000')) return null
        val segments = p.split('/', '\\')
        if (segments.any { it == ".." }) return null
        if (segments.any { it.length > 100 }) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(canonicalRoot, p).canonicalFile }.getOrNull() ?: return null
        // Belt and braces: canonical paths make this a real containment test, not a string prefix
        // match, so a symlinked subdirectory cannot be used to climb out.
        val rootPath = canonicalRoot.path
        val targetPath = target.path
        return if (targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            target
        } else {
            null
        }
    }

    fun write(root: File, path: String, content: String, maxBytes: Int): Result {
        val f = resolve(root, path)
            ?: return Result(false, "ERROR: bad path '$path'. Use a relative path inside the " +
                "project, like index.html or src/app.js. Absolute paths and '..' are refused.")
        if (content.isEmpty()) {
            return Result(false, "ERROR: content was empty, so '$path' was not written. A truncated " +
                "reply produces an empty file — send the complete file contents.")
        }
        if (content.length > maxBytes) {
            return Result(false, "ERROR: '$path' is ${content.length} chars, over the ${maxBytes}-char " +
                "limit. Write it in parts: write_file the first part, then write_file the same path " +
                "with the remainder appended is NOT possible, so split it into two files instead.")
        }
        f.parentFile?.mkdirs()
        f.writeText(content)
        return Result(true, "OK: wrote $path (${f.length()} bytes, ${content.lines().size} lines)")
    }

    fun read(root: File, path: String, maxChars: Int): Result {
        val f = resolve(root, path) ?: return Result(false, "ERROR: bad path '$path'.")
        if (!f.exists()) return Result(false, "ERROR: '$path' does not exist. Call list_files first.")
        if (f.isDirectory) return Result(false, "ERROR: '$path' is a directory, not a file.")
        val text = f.readText()
        return Result(
            true,
            if (text.length > maxChars) {
                text.take(maxChars) + "\n...(truncated at $maxChars chars; the file is ${text.length})"
            } else {
                text
            },
        )
    }

    fun delete(root: File, path: String): Result {
        val f = resolve(root, path) ?: return Result(false, "ERROR: bad path '$path'.")
        if (f == root.canonicalFile) return Result(false, "ERROR: refusing to delete the project root.")
        if (!f.exists()) return Result(false, "ERROR: '$path' does not exist.")
        return if (f.deleteRecursively()) Result(true, "OK: deleted $path")
        else Result(false, "ERROR: could not delete $path")
    }

    /** Every file in the project, relative path first, with sizes so a 0-byte file is obvious. */
    fun tree(root: File, limit: Int): String =
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .take(limit)
            .joinToString("\n") { f -> "${f.relativeTo(root).path.replace('\\', '/')} (${f.length()} B)" }
}
