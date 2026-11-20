package com.example

import com.example.data.remote.GitHubUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUrlParserTest {

  @Test
  fun parseHttpsUrl() {
    val result = GitHubUrlParser.parse("https://github.com/octocat/Hello-World")
    assertEquals("octocat", result?.owner)
    assertEquals("Hello-World", result?.repo)
    assertNull(result?.branchFromUrl)
    assertNull(result?.pathFromUrl)
  }

  @Test
  fun parseHttpsGitUrl() {
    val result = GitHubUrlParser.parse("https://github.com/octocat/Hello-World.git/")
    assertEquals("octocat", result?.owner)
    assertEquals("Hello-World", result?.repo)
  }

  @Test
  fun parseTreeUrlWithBranchAndPath() {
    val result = GitHubUrlParser.parse("https://github.com/octocat/Hello-World/tree/main/src/app")
    assertEquals("octocat", result?.owner)
    assertEquals("Hello-World", result?.repo)
    assertEquals("main", result?.branchFromUrl)
    assertEquals("src/app", result?.pathFromUrl)
  }

  @Test
  fun parseSshUrl() {
    val result = GitHubUrlParser.parse("git@github.com:octocat/Hello-World.git")
    assertEquals("octocat", result?.owner)
    assertEquals("Hello-World", result?.repo)
  }

  @Test
  fun parseShorthand() {
    val result = GitHubUrlParser.parse("octocat/Hello-World")
    assertEquals("octocat", result?.owner)
    assertEquals("Hello-World", result?.repo)
  }

  @Test
  fun rejectInvalidInput() {
    assertNull(GitHubUrlParser.parse("https://gitlab.com/user/repo"))
    assertNull(GitHubUrlParser.parse("just-one-word"))
    assertNull(GitHubUrlParser.parse(""))
  }
}
