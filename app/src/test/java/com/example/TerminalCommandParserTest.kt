package com.example

import com.example.ui.ChainedCommandPart
import com.example.ui.EchoRedirectCommand
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
    assertEquals("", TerminalCommandParser.resolveRepoPath("app/src", "~"))
    assertEquals("docs/guide.md", TerminalCommandParser.resolveRepoPath("app/src", "/docs/guide.md"))
    assertEquals("app/src", TerminalCommandParser.resolveRepoPath("app/src", "."))
  }

  @Test
  fun parseEchoRedirect_parsesOverwriteAndAppend() {
    assertEquals(
      EchoRedirectCommand("hello repo", "notes/readme.txt", append = false),
      TerminalCommandParser.parseEchoRedirect("""echo "hello repo" > notes/readme.txt"""),
    )
    assertEquals(
      EchoRedirectCommand("next line", "notes/readme.txt", append = true),
      TerminalCommandParser.parseEchoRedirect("""echo "next line" >> notes/readme.txt"""),
    )
    assertNull(TerminalCommandParser.parseEchoRedirect("echo hello only"))
  }

  @Test
  fun splitChainedCommands_splitsOutsideQuotesOnly() {
    val parts = TerminalCommandParser.splitChainedCommands("""cd Da && echo "a && b; c" > note.txt; ls -la""")
    assertEquals(
      listOf(
        ChainedCommandPart("cd Da", stopOnFailure = false),
        ChainedCommandPart("""echo "a && b; c" > note.txt""", stopOnFailure = true),
        ChainedCommandPart("ls -la", stopOnFailure = false),
      ),
      parts,
    )
  }

  @Test
  fun longestCommonPrefix_computesSharedPrefix() {
    assertEquals("git ", TerminalCommandParser.longestCommonPrefix(listOf("git log", "git show", "git status")))
    assertEquals("", TerminalCommandParser.longestCommonPrefix(listOf("cat", "ls")))
  }
}
