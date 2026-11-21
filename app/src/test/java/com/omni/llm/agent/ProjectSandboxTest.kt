package com.omni.llm.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The model supplies the paths, so the model can try to escape. These tests are the boundary.
 * A failure here is a security bug, not a style nit.
 */
class ProjectSandboxTest {

    private lateinit var base: File
    private lateinit var root: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("omni-sandbox").toFile()
        root = File(base, "proj").apply { mkdirs() }
    }

    // ---------------------------------------------------------------- containment

    @Test fun `normal paths resolve`() {
        assertTrue(ProjectSandbox.resolve(root, "index.html") != null)
        assertTrue(ProjectSandbox.resolve(root, "src/js/app.js") != null)
    }

    @Test fun `dotdot is refused`() {
        assertNull(ProjectSandbox.resolve(root, "../evil.txt"))
        assertNull(ProjectSandbox.resolve(root, "a/../../evil.txt"))
        assertNull(ProjectSandbox.resolve(root, "..\\evil"))
    }

    @Test fun `absolute paths are refused`() {
        assertNull(ProjectSandbox.resolve(root, "/data/data/com.omni.llm/evil"))
        assertNull(ProjectSandbox.resolve(root, "\\windows\\evil"))
    }

    @Test fun `blank and nul paths are refused`() {
        assertNull(ProjectSandbox.resolve(root, ""))
        assertNull(ProjectSandbox.resolve(root, "   "))
        assertNull(ProjectSandbox.resolve(root, "a\u0000b"))
    }

    @Test fun `a symlinked subdirectory cannot be used to climb out`() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        val linked = runCatching {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        }.isSuccess
        if (!linked) return // filesystem without symlink support: nothing to test
        val resolved = ProjectSandbox.resolve(root, "link/secret.txt")
        assertTrue(resolved == null || !resolved.path.contains("outside"))
    }

    // ---------------------------------------------------------------- writing

    @Test fun `write puts the content on disk and creates parent dirs`() {
        assertTrue(ProjectSandbox.write(root, "index.html", "<h1>hi</h1>", 100_000).ok)
        assertEquals("<h1>hi</h1>", File(root, "index.html").readText())
        assertTrue(ProjectSandbox.write(root, "src/js/app.js", "x=1", 100_000).ok)
        assertTrue(File(root, "src/js/app.js").exists())
    }

    @Test fun `empty content is refused so a truncated stream never becomes a real file`() {
        val result = ProjectSandbox.write(root, "empty.txt", "", 100_000)
        assertFalse(result.ok)
        assertFalse(File(root, "empty.txt").exists())
        assertTrue(result.message.startsWith("ERROR"))
    }

    @Test fun `oversized content is refused with an actionable message`() {
        val result = ProjectSandbox.write(root, "big.txt", "x".repeat(200), 100)
        assertFalse(result.ok)
        assertTrue(result.message.contains("limit"))
    }

    @Test fun `a traversal write is refused and leaves no file behind`() {
        assertFalse(ProjectSandbox.write(root, "../pwn.txt", "owned", 100_000).ok)
        assertFalse(File(base, "pwn.txt").exists())
    }

    // ---------------------------------------------------------------- reading

    @Test fun `read returns content, and errors on a missing file`() {
        File(root, "a.txt").writeText("hello")
        assertEquals("hello", ProjectSandbox.read(root, "a.txt", 1000).message)
        assertTrue(ProjectSandbox.read(root, "nope.txt", 1000).message.startsWith("ERROR"))
    }

    @Test fun `read truncates instead of blowing the context window`() {
        File(root, "big.txt").writeText("y".repeat(500))
        val out = ProjectSandbox.read(root, "big.txt", 50).message
        assertTrue(out.contains("truncated"))
        assertTrue(out.length < 500)
    }

    @Test fun `read cannot escape`() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        assertTrue(ProjectSandbox.read(root, "../outside/secret.txt", 100).message.startsWith("ERROR"))
    }

    // ---------------------------------------------------------------- deleting

    @Test fun `delete removes files but refuses to leave the sandbox`() {
        File(root, "gone.txt").writeText("x")
        assertTrue(ProjectSandbox.delete(root, "gone.txt").ok)
        assertFalse(File(root, "gone.txt").exists())

        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        assertFalse(ProjectSandbox.delete(root, "../outside/secret.txt").ok)
        assertTrue(File(outside, "secret.txt").exists())
    }

    @Test fun `tree lists relative paths only`() {
        ProjectSandbox.write(root, "src/js/app.js", "x=1", 100_000)
        val tree = ProjectSandbox.tree(root, 50)
        assertTrue(tree.contains("src/js/app.js"))
        assertFalse(tree.contains(root.path))
    }
}
