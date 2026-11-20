package com.example.data.remote

import com.example.data.model.RepoCoordinates

object GitHubUrlParser {
  private val sshRegex = Regex("""^git@github\.com:([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)

  fun parse(rawInput: String): RepoCoordinates? {
    val trimmed = rawInput.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null

    sshRegex.matchEntire(trimmed)?.let { match ->
      val owner = match.groupValues[1].trim()
      val repo = match.groupValues[2].removeSuffix(".git").trim()
      if (owner.isNotEmpty() && repo.isNotEmpty()) {
        return RepoCoordinates(owner = owner, repo = repo)
      }
      return null
    }

    val cleaned =
      trimmed
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")

    val segments =
      if (cleaned.startsWith("github.com/", ignoreCase = true)) {
        cleaned.substringAfter("github.com/").split("/").filter { it.isNotBlank() }
      } else if (!cleaned.contains("://") && !cleaned.contains(".")) {
        cleaned.split("/").filter { it.isNotBlank() }
      } else {
        return null
      }

    if (segments.size < 2) return null

    val owner = segments[0].trim()
    val repo = segments[1].removeSuffix(".git").trim()
    if (owner.isEmpty() || repo.isEmpty()) return null

    var branch: String? = null
    var initialPath: String? = null
    if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) {
      branch = segments[3]
      if (segments.size > 4) {
        initialPath = segments.drop(4).joinToString("/")
      }
    }

    return RepoCoordinates(
      owner = owner,
      repo = repo,
      branchFromUrl = branch,
      pathFromUrl = initialPath,
    )
  }
}
