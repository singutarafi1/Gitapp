package com.example.ui

object TerminalCommandParser {

  /**
   * Tokenizes a shell-like command line while respecting single quotes, double quotes,
   * and backslash escapes inside double quotes or unquoted text.
   */
  fun tokenize(input: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaping = false
    var hasToken = false

    for (ch in input.trim()) {
      when {
        escaping -> {
          current.append(ch)
          escaping = false
          hasToken = true
        }
        ch == '\\' && !inSingleQuote -> {
          escaping = true
          hasToken = true
        }
        ch == '\'' && !inDoubleQuote -> {
          inSingleQuote = !inSingleQuote
          hasToken = true
        }
        ch == '"' && !inSingleQuote -> {
          inDoubleQuote = !inDoubleQuote
          hasToken = true
        }
        ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
          if (hasToken) {
            tokens += current.toString()
            current.clear()
            hasToken = false
          }
        }
        else -> {
          current.append(ch)
          hasToken = true
        }
      }
    }
    if (escaping) {
      current.append('\\')
    }
    if (hasToken) {
      tokens += current.toString()
    }
    return tokens
  }

  /**
   * Resolves a user-supplied path relative to [currentDir] inside a GitHub repository.
   * Empty string represents the repository root.
   */
  fun resolveRepoPath(currentDir: String, rawArg: String?): String {
    val trimmed = rawArg?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == ".") return currentDir.trim('/')
    if (trimmed == "/" || trimmed == "~") return ""

    val isAbsolute = trimmed.startsWith("/") || trimmed.startsWith("~/")
    val normalizedInput =
      when {
        trimmed.startsWith("~/") -> trimmed.removePrefix("~/")
        trimmed.startsWith("/") -> trimmed.removePrefix("/")
        else -> trimmed
      }

    val stack = ArrayDeque<String>()
    if (!isAbsolute && currentDir.isNotBlank()) {
      currentDir.trim('/').split('/').filter { it.isNotEmpty() }.forEach { stack.addLast(it) }
    }

    for (segment in normalizedInput.split('/')) {
      when (segment) {
        "", "." -> Unit
        ".." -> if (stack.isNotEmpty()) stack.removeLast()
        else -> stack.addLast(segment)
      }
    }
    return stack.joinToString("/")
  }

  /**
   * Parses `echo <text> > <file>` redirection syntax if present.
   * Returns (content, targetPath) or null when not an echo redirection.
   */
  fun parseEchoRedirect(rawCommand: String): Pair<String, String>? {
    val trimmed = rawCommand.trim()
    if (!trimmed.startsWith("echo ", ignoreCase = true)) return null
    val tokens = tokenize(trimmed)
    val redirectIdx = tokens.indexOfLast { it == ">" }
    if (redirectIdx <= 0 || redirectIdx >= tokens.lastIndex) return null
    val content = tokens.subList(1, redirectIdx).joinToString(" ")
    val target = tokens.subList(redirectIdx + 1, tokens.size).joinToString(" ").trim()
    if (target.isEmpty()) return null
    return content to target
  }
}
