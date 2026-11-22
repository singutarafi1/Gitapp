package com.example

import com.example.ui.TerminalCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalCommandParserTest {

  @Test
  fun tokenize_handlesQuotesAndSpaces() {
    val tokens = TerminalCommandParser.tokenize("""touch "hello world.txt" 'line 1'""")
    assertEquals(listOf("touch", "hello world.txt", "line 1"), tokens)
  }

  @Test
  fun resolveRepoPath_handlesRelativeParentAndRoot() {
    assertEquals("app/src/main", TerminalCommandParser.resolveRepoPath("app/src", "main"))
    assertEquals("app", TerminalCommandParser.resolveRepoPath("app/src", ".."))
    assertEquals("app/res", TerminalCommandParser.resolveRepoPath("app/src", "../res"))
    assertEquals("", TerminalCommandParser.resolveRepoPath("app/src", "/"))
    assertEquals("docs/guide.md", TerminalCommandParser.resolveRepoPath("app/src", "/docs/guide.md"))
    assertEquals("app/src", TerminalCommandParser.resolveRepoPath("app/src", "."))
  }

  @Test
  fun parseEchoRedirect_parsesContentAndTarget() {
    val parsed = TerminalCommandParser.parseEchoRedirect("""echo "hello repo" > notes/readme.txt""")
    assertEquals("hello repo" to "notes/readme.txt", parsed)
    assertNull(TerminalCommandParser.parseEchoRedirect("echo hello only"))
  }
}
