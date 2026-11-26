package com.example.ui

data class EchoRedirectCommand(
  val content: String,
  val targetPath: String,
  val append: Boolean,
)

data class ChainedCommandPart(
  val command: String,
  val stopOnFailure: Boolean,
)

object TerminalCommandParser {

  /**
   * Splits a command line into sequential parts separated by unquoted `&&` or `;`.
   */
  fun splitChainedCommands(input: String): List<ChainedCommandPart> {
    val parts = mutableListOf<ChainedCommandPart>()
    val current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaping = false
    var pendingStopOnFailure = false
    var i = 0

    while (i < input.length) {
      val ch = input[i]
      when {
        escaping -> {
          current.append(ch)
          escaping = false
          i++
        }
        ch == '\\' && !inSingleQuote -> {
          current.append(ch)
          escaping = true
          i++
        }
        ch == '\'' && !inDoubleQuote -> {
          inSingleQuote = !inSingleQuote
          current.append(ch)
          i++
        }
        ch == '"' && !inSingleQuote -> {
          inDoubleQuote = !inDoubleQuote
          current.append(ch)
          i++
        }
        !inSingleQuote && !inDoubleQuote && ch == ';' -> {
          val cmd = current.toString().trim()
          if (cmd.isNotEmpty()) {
            parts += ChainedCommandPart(cmd, pendingStopOnFailure)
          }
          current.clear()
          pendingStopOnFailure = false
          i++
        }
        !inSingleQuote && !inDoubleQuote && ch == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
          val cmd = current.toString().trim()
          if (cmd.isNotEmpty()) {
            parts += ChainedCommandPart(cmd, pendingStopOnFailure)
          }
          current.clear()
          pendingStopOnFailure = true
          i += 2
        }
        else -> {
          current.append(ch)
          i++
        }
      }
    }

    val tail = current.toString().trim()
    if (tail.isNotEmpty()) {
      parts += ChainedCommandPart(tail, pendingStopOnFailure)
    }
    return parts
  }

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
   * Parses `echo <text> > <file>` or `echo <text> >> <file>` redirection syntax if present.
   */
  fun parseEchoRedirect(rawCommand: String): EchoRedirectCommand? {
    val trimmed = rawCommand.trim()
    if (!trimmed.startsWith("echo ", ignoreCase = true)) return null
    val tokens = tokenize(trimmed)
    val redirectIdx = tokens.indexOfLast { it == ">" || it == ">>" }
    if (redirectIdx <= 0 || redirectIdx >= tokens.lastIndex) return null
    val append = tokens[redirectIdx] == ">>"
    val content = tokens.subList(1, redirectIdx).joinToString(" ")
    val target = tokens.subList(redirectIdx + 1, tokens.size).joinToString(" ").trim()
    if (target.isEmpty()) return null
    return EchoRedirectCommand(content = content, targetPath = target, append = append)
  }

  fun longestCommonPrefix(values: List<String>): String {
    if (values.isEmpty()) return ""
    var prefix = values.first()
    for (i in 1 until values.size) {
      val next = values[i]
      val limit = minOf(prefix.length, next.length)
      var matchLen = 0
      while (matchLen < limit && prefix[matchLen] == next[matchLen]) {
        matchLen++
      }
      prefix = prefix.substring(0, matchLen)
      if (prefix.isEmpty()) break
    }
    return prefix
  }
}
