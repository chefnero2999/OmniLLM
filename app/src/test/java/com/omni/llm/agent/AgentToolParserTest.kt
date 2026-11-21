package com.omni.llm.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a reply shape actually produced by the free keyless models (GPT-OSS 20B,
 * Llama-3.3-70B, DeepSeek-V4-Flash) while the agent was being built. They are not hypothetical.
 */
class AgentToolParserTest {

    @Test
    fun `clean tool fence is parsed`() {
        val act = AgentToolParser.parse(
            """```tool
            |{"tool":"write_file","path":"index.html","content":"<h1>hi</h1>"}
            |```""".trimMargin()
        )
        assertEquals("write_file", act?.tool)
        assertEquals("index.html", act?.path)
        assertEquals("<h1>hi</h1>", act?.content)
    }

    @Test
    fun `json fence is parsed`() {
        val act = AgentToolParser.parse("```json\n{\"tool\":\"finish\",\"summary\":\"done\"}\n```")
        assertEquals("finish", act?.tool)
        assertEquals("done", act?.summary)
    }

    @Test
    fun `json wrapped in prose with no fence is still parsed`() {
        val act = AgentToolParser.parse(
            "Sure! I'll create the stylesheet now.\n" +
                "{\"tool\":\"write_file\",\"path\":\"a.css\",\"content\":\"body{margin:0}\"}\n" +
                "Let me know if you want changes!"
        )
        assertEquals("write_file", act?.tool)
        assertEquals("a.css", act?.path)
    }

    @Test
    fun `braces inside content do not end the object early`() {
        val act = AgentToolParser.parse(
            """```tool
            |{"tool":"write_file","path":"app.js","content":"function f(){ if(x){return 1} else {return 2} }"}
            |```""".trimMargin()
        )
        assertTrue(act?.content?.contains("else {return 2}") == true)
    }

    @Test
    fun `escaped quotes and backslashes survive`() {
        val act = AgentToolParser.parse(
            """```tool
            |{"tool":"write_file","path":"x.txt","content":"say \"hi\" and C:\\tmp"}
            |```""".trimMargin()
        )
        assertTrue(act?.content?.contains("\"hi\"") == true)
        assertTrue(act?.content?.contains("\\") == true)
    }

    @Test
    fun `multiline html content is preserved`() {
        val act = AgentToolParser.parse(
            """```tool
            |{"tool":"write_file","path":"index.html","content":"<!doctype html>\n<html>\n<body>ok</body>\n</html>"}
            |```""".trimMargin()
        )
        assertTrue((act?.content?.lines()?.size ?: 0) >= 3)
    }

    @Test
    fun `tool synonyms are normalised`() {
        assertEquals("write_file", AgentToolParser.parse("{\"tool\":\"writefile\",\"path\":\"i.html\",\"content\":\"x\"}")?.tool)
        assertEquals("write_file", AgentToolParser.parse("{\"tool\":\"create_file\",\"path\":\"i.html\",\"content\":\"x\"}")?.tool)
        assertEquals("finish", AgentToolParser.parse("{\"tool\":\"done\",\"summary\":\"s\"}")?.tool)
        assertEquals("list_files", AgentToolParser.parse("{\"tool\":\"ls\"}")?.tool)
    }

    @Test
    fun `alternate content keys are accepted`() {
        assertEquals(
            "fun main(){}",
            AgentToolParser.parse("{\"tool\":\"write_file\",\"path\":\"a.kt\",\"code\":\"fun main(){}\"}")?.content,
        )
        assertEquals(
            "<p>x</p>",
            AgentToolParser.parse("{\"tool\":\"write_file\",\"path\":\"a.html\",\"text\":\"<p>x</p>\"}")?.content,
        )
    }

    @Test
    fun `prose with no call returns null so the loop can nudge instead of dying`() {
        assertNull(AgentToolParser.parse("I have finished the website. It looks great!"))
    }

    @Test
    fun `empty and truncated replies return null instead of throwing`() {
        assertNull(AgentToolParser.parse(""))
        assertNull(
            AgentToolParser.parse("```tool\n{\"tool\":\"write_file\",\"path\":\"a\",\"content\":\"unterminated"),
        )
        assertNull(AgentToolParser.parse("{}"))
        assertNull(AgentToolParser.parse("```\nnot json at all\n```"))
    }

    @Test
    fun `a traversal path parses - rejection is the sandbox's job`() {
        val act = AgentToolParser.parse(
            "{\"tool\":\"write_file\",\"path\":\"../../etc/passwd\",\"content\":\"x\"}"
        )
        assertEquals("../../etc/passwd", act?.path)
    }
}
