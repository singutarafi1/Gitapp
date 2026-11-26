package com.example.ui

import com.example.data.local.SavedToken
import com.example.data.local.maskToken
import com.example.data.model.EntryType
import com.example.data.model.RepoEntry
import com.example.data.model.RepoSession
import com.example.data.remote.GitHubRepository

enum class TerminalLineKind {
  COMMAND,
  OUTPUT,
  DIRECTORY,
  SUCCESS,
  WARNING,
  ERROR,
  MUTED,
}

data class TerminalLine(
  val id: Long,
  val text: String,
  val kind: TerminalLineKind = TerminalLineKind.OUTPUT,
)

data class TerminalExecutionResult(
  val lines: List<Pair<String, TerminalLineKind>> = emptyList(),
  val newDir: String? = null,
  val updatedSession: RepoSession? = null,
  val didMutateRepo: Boolean = false,
  val openEditorEntry: RepoEntry? = null,
  val clearScreen: Boolean = false,
  val exitTerminal: Boolean = false,
  val clipboardCopyText: String? = null,
  val launchTermuxCommand: String? = null,
  val isError: Boolean = false,
)

data class TabCompletionResult(
  val updatedInput: String,
  val candidateLines: List<Pair<String, TerminalLineKind>> = emptyList(),
)

class RepoTerminalExecutor(private val repository: GitHubRepository) {

  suspend fun execute(
    rawCommand: String,
    session: RepoSession,
    currentDir: String,
    activeToken: SavedToken?,
    commandHistory: List<String> = emptyList(),
  ): TerminalExecutionResult {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return TerminalExecutionResult()

    val parts = TerminalCommandParser.splitChainedCommands(trimmed)
    if (parts.size <= 1) {
      return executeSingle(trimmed, session, currentDir, activeToken, commandHistory)
    }

    val allLines = mutableListOf<Pair<String, TerminalLineKind>>()
    var activeDir = currentDir
    var activeSession = session
    var mutated = false
    var clear = false
    var editorEntry: RepoEntry? = null
    var copyText: String? = null
    var termuxCmd: String? = null
    var lastFailed = false

    for (part in parts) {
      if (part.stopOnFailure && lastFailed) break
      val step =
        executeSingle(part.command, activeSession, activeDir, activeToken, commandHistory)
      if (step.clearScreen) {
        allLines.clear()
        clear = true
      }
      allLines += step.lines
      step.newDir?.let { activeDir = it }
      step.updatedSession?.let { activeSession = it }
      if (step.didMutateRepo) mutated = true
      if (step.openEditorEntry != null) editorEntry = step.openEditorEntry
      if (step.clipboardCopyText != null) copyText = step.clipboardCopyText
      if (step.launchTermuxCommand != null) termuxCmd = step.launchTermuxCommand
      lastFailed = step.isError
      if (step.exitTerminal) {
        return TerminalExecutionResult(
          lines = allLines,
          newDir = activeDir,
          updatedSession = activeSession,
          didMutateRepo = mutated,
          exitTerminal = true,
        )
      }
    }

    return TerminalExecutionResult(
      lines = allLines,
      newDir = activeDir,
      updatedSession = activeSession,
      didMutateRepo = mutated,
      openEditorEntry = editorEntry,
      clearScreen = clear,
      clipboardCopyText = copyText,
      launchTermuxCommand = termuxCmd,
      isError = lastFailed,
    )
  }

  private suspend fun executeSingle(
    trimmed: String,
    session: RepoSession,
    currentDir: String,
    activeToken: SavedToken?,
    commandHistory: List<String>,
  ): TerminalExecutionResult {
    val echoRedirect = TerminalCommandParser.parseEchoRedirect(trimmed)
    if (echoRedirect != null) {
      return writeTextFile(
        session = session,
        currentDir = currentDir,
        rawTarget = echoRedirect.targetPath,
        content = echoRedirect.content,
        append = echoRedirect.append,
      )
    }

    val tokens = TerminalCommandParser.tokenize(trimmed)
    if (tokens.isEmpty()) return TerminalExecutionResult()

    val head = tokens.first().lowercase()
    val args = tokens.drop(1)

    if (head == "git") {
      if (args.isEmpty()) {
        return TerminalExecutionResult(
          lines =
            listOf(
              "Usage: git <status|log|show|diff|pull|branch|checkout|switch|clone>" to
                TerminalLineKind.MUTED
            )
        )
      }
      return executeSubcommand(
        cmd = args.first().lowercase(),
        args = args.drop(1),
        session = session,
        currentDir = currentDir,
        activeToken = activeToken,
        commandHistory = commandHistory,
        fromGitPrefix = true,
      )
    }

    return executeSubcommand(
      cmd = head,
      args = args,
      session = session,
      currentDir = currentDir,
      activeToken = activeToken,
      commandHistory = commandHistory,
      fromGitPrefix = false,
    )
  }

  private suspend fun executeSubcommand(
    cmd: String,
    args: List<String>,
    session: RepoSession,
    currentDir: String,
    activeToken: SavedToken?,
    commandHistory: List<String>,
    fromGitPrefix: Boolean,
  ): TerminalExecutionResult {
    return when (cmd) {
      "help", "?" -> helpResult()
      "clear", "cls" -> TerminalExecutionResult(clearScreen = true)
      "exit", "quit" -> TerminalExecutionResult(exitTerminal = true)
      "pwd" -> {
        val display = if (currentDir.isEmpty()) "~" else "~/$currentDir"
        TerminalExecutionResult(
          lines = listOf("${session.fullName} (${session.branch}): $display" to TerminalLineKind.OUTPUT)
        )
      }
      "whoami" -> {
        val identity =
          when {
            activeToken != null && activeToken.username.isNotBlank() ->
              "@${activeToken.username} (${activeToken.label}) • ${maskToken(activeToken.token)}"
            activeToken != null -> "${activeToken.label} • ${maskToken(activeToken.token)}"
            else -> maskToken(session.token)
          }
        TerminalExecutionResult(lines = listOf(identity to TerminalLineKind.OUTPUT))
      }
      "history" -> {
        if (commandHistory.isEmpty()) {
          TerminalExecutionResult(lines = listOf("(history empty)" to TerminalLineKind.MUTED))
        } else {
          val lines =
            commandHistory.mapIndexed { idx, item ->
              "${(idx + 1).toString().padStart(4)}  $item" to TerminalLineKind.OUTPUT
            }
          TerminalExecutionResult(lines = lines)
        }
      }
      "status", "info", "repo" -> statusResult(session, currentDir)
      "pull", "fetch", "refresh" -> pullCommand(session, currentDir)
      "ls", "ll", "la", "dir" -> {
        val longFormat = cmd == "ll" || args.any { it.startsWith("-") && ('l' in it || 'a' in it) }
        val pathArg = args.firstOrNull { !it.startsWith("-") }
        listEntriesCommand(session, currentDir, pathArg, longFormat)
      }
      "cd" -> changeDirectoryCommand(session, currentDir, args.firstOrNull())
      "tree" -> treeCommand(session, currentDir, args.firstOrNull())
      "find", "search" -> findCommand(session, currentDir, args.joinToString(" ").trim())
      "grep" -> grepCommand(session, currentDir, args)
      "cat" -> {
        val showLineNumbers = args.any { it == "-n" }
        val fileArgs = args.filter { !it.startsWith("-") }
        readFileCommand(session, currentDir, fileArgs, headLimit = null, tailLimit = null, showLineNumbers = showLineNumbers)
      }
      "head" -> {
        val (count, fileArgs) = parseLineLimitArgs(args, defaultCount = 20)
        readFileCommand(session, currentDir, fileArgs, headLimit = count, tailLimit = null, showLineNumbers = false)
      }
      "tail" -> {
        val (count, fileArgs) = parseLineLimitArgs(args, defaultCount = 20)
        readFileCommand(session, currentDir, fileArgs, headLimit = null, tailLimit = count, showLineNumbers = false)
      }
      "wc" -> wcCommand(session, currentDir, args.filter { !it.startsWith("-") })
      "edit", "nano", "vim" -> openEditorCommand(session, currentDir, args.firstOrNull())
      "touch" -> {
        val target = args.firstOrNull()
          ?: return errorResult("Usage: touch <file> [initial text...]")
        val content = args.drop(1).joinToString(" ")
        writeTextFile(session, currentDir, target, content, append = false)
      }
      "write" -> {
        val target = args.firstOrNull()
          ?: return errorResult("Usage: write <file> <content...>")
        val content = args.drop(1).joinToString(" ")
        writeTextFile(session, currentDir, target, content, append = false)
      }
      "echo" -> TerminalExecutionResult(
        lines = listOf(args.joinToString(" ") to TerminalLineKind.OUTPUT)
      )
      "mkdir" -> mkdirCommand(session, currentDir, args.firstOrNull())
      "cp" -> cpCommand(session, currentDir, args)
      "mv", "rename" -> mvCommand(session, currentDir, args)
      "rm", "del" -> rmCommand(session, currentDir, args)
      "log", "commits" -> logCommand(session, currentDir, args)
      "show", "diff" -> showCommitCommand(session, args.firstOrNull())
      "branch" -> branchCommand(session, args)
      "checkout", "switch" -> checkoutCommand(session, args)
      "visibility" -> visibilityCommand(session, args.firstOrNull())
      "clone" -> cloneCommand(session)
      "termux" -> termuxCommand(session)
      else -> {
        val label = if (fromGitPrefix) "git $cmd" else cmd
        TerminalExecutionResult(
          lines =
            listOf(
              "bash: $label: command not found" to TerminalLineKind.ERROR,
              "Type 'help' for built-in Repo Terminal commands." to TerminalLineKind.MUTED,
            ),
          isError = true,
        )
      }
    }
  }

  suspend fun tabComplete(
    rawInput: String,
    session: RepoSession,
    currentDir: String,
  ): TabCompletionResult {
    val trailingSpace = rawInput.endsWith(" ")
    val activeChainPart = rawInput.substringAfterLast("&&").substringAfterLast(";")
    val prefixBeforeChain = rawInput.dropLast(activeChainPart.length)
    val trimmedPart = activeChainPart.trimStart()
    val leadingSpaces = activeChainPart.take(activeChainPart.length - trimmedPart.length)
    val tokens = TerminalCommandParser.tokenize(trimmedPart)

    // 1. Completing the command name itself
    if (tokens.isEmpty() || (tokens.size == 1 && !trailingSpace)) {
      val word = tokens.firstOrNull().orEmpty()
      val matches = ALL_COMMANDS.filter { it.startsWith(word, ignoreCase = true) }.sorted()
      if (matches.isEmpty()) return TabCompletionResult(rawInput)
      val completed =
        if (matches.size == 1) "${matches.first()} "
        else TerminalCommandParser.longestCommonPrefix(matches)
      val newInput =
        if (completed.length > word.length) prefixBeforeChain + leadingSpaces + completed
        else rawInput
      val hintLines =
        if (matches.size > 1) listOf(matches.joinToString("  ") to TerminalLineKind.MUTED)
        else emptyList()
      return TabCompletionResult(newInput, hintLines)
    }

    val head = tokens.first().lowercase()
    val argWord = if (trailingSpace) "" else tokens.last()
    val beforeArg =
      if (trailingSpace) activeChainPart
      else activeChainPart.dropLast(argWord.length)

    // 2. `git <subcommand>` completion
    if (head == "git" && (tokens.size == 1 || (tokens.size == 2 && !trailingSpace))) {
      val matches = GIT_SUBCOMMANDS.filter { it.startsWith(argWord, ignoreCase = true) }.sorted()
      if (matches.isEmpty()) return TabCompletionResult(rawInput)
      val completed =
        if (matches.size == 1) "${matches.first()} "
        else TerminalCommandParser.longestCommonPrefix(matches)
      val newInput =
        if (completed.length > argWord.length) prefixBeforeChain + beforeArg + completed
        else rawInput
      val hintLines =
        if (matches.size > 1) listOf(matches.joinToString("  ") to TerminalLineKind.MUTED)
        else emptyList()
      return TabCompletionResult(newInput, hintLines)
    }

    // 3. Branch name completion for `git checkout` / `git switch`
    if (head == "git" && tokens.getOrNull(1)?.lowercase() in setOf("checkout", "switch")) {
      val matches = session.branches.filter { it.startsWith(argWord, ignoreCase = true) }.sorted()
      if (matches.isEmpty()) return TabCompletionResult(rawInput)
      val completed =
        if (matches.size == 1) matches.first()
        else TerminalCommandParser.longestCommonPrefix(matches)
      val newInput =
        if (completed.length > argWord.length) prefixBeforeChain + beforeArg + completed
        else rawInput
      val hintLines =
        if (matches.size > 1) listOf(matches.joinToString("  ") to TerminalLineKind.MUTED)
        else emptyList()
      return TabCompletionResult(newInput, hintLines)
    }

    // 4. File / folder path completion inside current or parent repo directory
    val slashIdx = argWord.lastIndexOf('/')
    val dirPrefixArg = if (slashIdx >= 0) argWord.substring(0, slashIdx + 1) else ""
    val namePrefix = if (slashIdx >= 0) argWord.substring(slashIdx + 1) else argWord
    val lookupDir =
      if (dirPrefixArg.isEmpty()) currentDir
      else TerminalCommandParser.resolveRepoPath(currentDir, dirPrefixArg.trimEnd('/').ifEmpty { "/" })

    val entries = runCatching { repository.listDirectory(session, lookupDir) }.getOrDefault(emptyList())
    val matchingEntries =
      entries.filter { it.name.startsWith(namePrefix, ignoreCase = true) }.sortedBy { it.name }
    if (matchingEntries.isEmpty()) return TabCompletionResult(rawInput)

    val candidateStrings =
      matchingEntries.map { entry ->
        if (entry.type == EntryType.DIR) "$dirPrefixArg${entry.name}/" else "$dirPrefixArg${entry.name}"
      }
    val completed =
      if (candidateStrings.size == 1) candidateStrings.first()
      else TerminalCommandParser.longestCommonPrefix(candidateStrings)
    val newInput =
      if (completed.length > argWord.length) prefixBeforeChain + beforeArg + completed
      else rawInput
    val hintLines =
      if (candidateStrings.size > 1) {
        val display =
          matchingEntries.joinToString("  ") { e ->
            if (e.type == EntryType.DIR) "${e.name}/" else e.name
          }
        listOf(display to TerminalLineKind.DIRECTORY)
      } else {
        emptyList()
      }
    return TabCompletionResult(newInput, hintLines)
  }

  private fun helpResult(): TerminalExecutionResult {
    return TerminalExecutionResult(
      lines =
        listOf(
          "=== GitFiles Terminal (Termux-style Git Shell) ===" to TerminalLineKind.DIRECTORY,
          "Navigation & Inspection:" to TerminalLineKind.SUCCESS,
          "  ls [-l] [path]            List files & folders (ll for details)" to TerminalLineKind.OUTPUT,
          "  cd <path|..|~|/>          Change directory (supports && / ; chaining)" to TerminalLineKind.OUTPUT,
          "  pwd | tree [path]         Print path or recursive repo tree" to TerminalLineKind.OUTPUT,
          "  find <query>              Search paths in current folder" to TerminalLineKind.OUTPUT,
          "  grep [-i] <text> [path]   Search text inside files" to TerminalLineKind.OUTPUT,
          "  cat [-n] / head / tail    Read file (use -n N for line count)" to TerminalLineKind.OUTPUT,
          "  wc <file> | edit <file>   Line/byte count or open in Editor" to TerminalLineKind.OUTPUT,
          "Commit Actions (direct to GitHub):" to TerminalLineKind.SUCCESS,
          "  touch <file> [text]       Create/overwrite file & commit" to TerminalLineKind.OUTPUT,
          "  echo \"hi\" >|>> <file>     Write (>) or append (>>) & commit" to TerminalLineKind.OUTPUT,
          "  mkdir <folder>            Create folder (.gitkeep) & commit" to TerminalLineKind.OUTPUT,
          "  cp <src> <dst>            Copy file cleanly via Git tree" to TerminalLineKind.OUTPUT,
          "  mv <old> <new>            Rename or move file/folder" to TerminalLineKind.OUTPUT,
          "  rm <file> | rm -rf <dir>  Delete file or folder" to TerminalLineKind.OUTPUT,
          "Git, History & Termux:" to TerminalLineKind.SUCCESS,
          "  git status | git pull     Repo info or refresh branch" to TerminalLineKind.OUTPUT,
          "  git log [n] | git show    Commit history or commit diff/patch" to TerminalLineKind.OUTPUT,
          "  git branch [name]         List or create branch" to TerminalLineKind.OUTPUT,
          "  git checkout [-b] <name>  Switch or create+switch branch" to TerminalLineKind.OUTPUT,
          "  visibility public|private Change repo visibility" to TerminalLineKind.OUTPUT,
          "  git clone | termux        Copy clone cmd or open Termux app" to TerminalLineKind.OUTPUT,
          "  history | whoami | clear  Command history, token info, clear" to TerminalLineKind.OUTPUT,
        )
    )
  }

  private suspend fun statusResult(session: RepoSession, currentDir: String): TerminalExecutionResult {
    val entries = repository.listDirectory(session, currentDir)
    val dirs = entries.count { it.type == EntryType.DIR }
    val files = entries.count { it.type == EntryType.FILE }
    val pathText = if (currentDir.isEmpty()) "~" else "~/$currentDir"
    return TerminalExecutionResult(
      lines =
        listOf(
          "Repository : ${session.fullName} (${if (session.isPrivate) "Private" else "Public"})" to TerminalLineKind.OUTPUT,
          "Branch     : ${session.branch} (default: ${session.defaultBranch})" to TerminalLineKind.OUTPUT,
          "Directory  : $pathText ($dirs folders, $files files)" to TerminalLineKind.OUTPUT,
          "Commits    : ${if (session.isEmptyRepo) "empty repository" else "ready"}" to TerminalLineKind.MUTED,
        )
    )
  }

  private suspend fun pullCommand(session: RepoSession, currentDir: String): TerminalExecutionResult {
    val refreshed = repository.refreshBranches(session)
    val entries = repository.listDirectory(refreshed, currentDir)
    return TerminalExecutionResult(
      lines =
        listOf(
          "Synced ${refreshed.fullName} [${refreshed.branch}] (${entries.size} items in current dir)" to
            TerminalLineKind.SUCCESS
        ),
      updatedSession = refreshed,
      didMutateRepo = true,
    )
  }

  private suspend fun listEntriesCommand(
    session: RepoSession,
    currentDir: String,
    pathArg: String?,
    longFormat: Boolean,
  ): TerminalExecutionResult {
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, pathArg)
    val entries = repository.listDirectory(session, targetPath)
    if (entries.isEmpty()) {
      val display = if (targetPath.isEmpty()) "~" else "~/$targetPath"
      return TerminalExecutionResult(
        lines = listOf("(empty or not found: $display)" to TerminalLineKind.MUTED)
      )
    }
    val lines =
      entries.map { entry ->
        if (entry.type == EntryType.DIR) {
          if (longFormat) {
            "drwxr-xr-x         -  ${entry.name}/" to TerminalLineKind.DIRECTORY
          } else {
            "DIR   ${entry.name}/" to TerminalLineKind.DIRECTORY
          }
        } else {
          val sizeLabel = formatBytes(entry.size).padStart(9)
          if (longFormat) {
            "-rw-r--r-- $sizeLabel  ${entry.name}" to TerminalLineKind.OUTPUT
          } else {
            "FILE  $sizeLabel  ${entry.name}" to TerminalLineKind.OUTPUT
          }
        }
      }
    return TerminalExecutionResult(lines = lines)
  }

  private suspend fun changeDirectoryCommand(
    session: RepoSession,
    currentDir: String,
    pathArg: String?,
  ): TerminalExecutionResult {
    if (pathArg.isNullOrBlank() || pathArg.trim() == "/" || pathArg.trim() == "~") {
      return TerminalExecutionResult(
        lines = listOf("~" to TerminalLineKind.DIRECTORY),
        newDir = "",
      )
    }
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, pathArg)
    if (targetPath.isEmpty()) {
      return TerminalExecutionResult(
        lines = listOf("~" to TerminalLineKind.DIRECTORY),
        newDir = "",
      )
    }
    val existing = repository.findEntryAtPath(session, targetPath)
      ?: return errorResult("cd: no such folder: $targetPath")
    if (existing.type != EntryType.DIR) {
      return errorResult("cd: not a directory: $targetPath")
    }
    return TerminalExecutionResult(
      lines = listOf("~/$targetPath" to TerminalLineKind.DIRECTORY),
      newDir = targetPath,
    )
  }

  private suspend fun treeCommand(
    session: RepoSession,
    currentDir: String,
    pathArg: String?,
  ): TerminalExecutionResult {
    val targetDir = TerminalCommandParser.resolveRepoPath(currentDir, pathArg)
    val (allItems, truncated) = repository.listRecursiveTree(session)
    val prefix = if (targetDir.isEmpty()) "" else "$targetDir/"
    val matching =
      allItems.filter { item ->
        if (targetDir.isEmpty()) true else item.path.startsWith(prefix)
      }
    if (matching.isEmpty()) {
      return TerminalExecutionResult(lines = listOf("(no entries)" to TerminalLineKind.MUTED))
    }
    val limit = 120
    val lines = mutableListOf<Pair<String, TerminalLineKind>>()
    lines += ((if (targetDir.isEmpty()) "~" else "~/$targetDir") to TerminalLineKind.DIRECTORY)
    matching.take(limit).forEach { item ->
      val rel = if (prefix.isEmpty()) item.path else item.path.removePrefix(prefix)
      val kind = if (item.type == "tree") TerminalLineKind.DIRECTORY else TerminalLineKind.OUTPUT
      val suffix = if (item.type == "tree") "/" else " (${formatBytes(item.size)})"
      lines += ("  $rel$suffix" to kind)
    }
    if (matching.size > limit) {
      lines += ("... (${matching.size - limit} more entries omitted; use 'find <query>')" to TerminalLineKind.MUTED)
    }
    if (truncated) {
      lines += ("Warning: GitHub tree response was truncated due to repo size." to TerminalLineKind.WARNING)
    }
    return TerminalExecutionResult(lines = lines)
  }

  private suspend fun findCommand(
    session: RepoSession,
    currentDir: String,
    query: String,
  ): TerminalExecutionResult {
    if (query.isEmpty()) return errorResult("Usage: find <keyword>")
    val (allItems, _) = repository.listRecursiveTree(session)
    val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
    val matches =
      allItems.filter { item ->
        val inScope = if (prefix.isEmpty()) true else item.path.startsWith(prefix)
        inScope && item.path.contains(query, ignoreCase = true)
      }
    if (matches.isEmpty()) {
      return TerminalExecutionResult(lines = listOf("No matches for '$query'" to TerminalLineKind.MUTED))
    }
    val lines =
      matches.take(80).map { item ->
        val kind = if (item.type == "tree") TerminalLineKind.DIRECTORY else TerminalLineKind.OUTPUT
        val suffix = if (item.type == "tree") "/" else " (${formatBytes(item.size)})"
        "${item.path}$suffix" to kind
      }.toMutableList()
    if (matches.size > 80) {
      lines += ("... ${matches.size - 80} more matches" to TerminalLineKind.MUTED)
    }
    return TerminalExecutionResult(lines = lines)
  }

  private suspend fun grepCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
  ): TerminalExecutionResult {
    val ignoreCase = args.any { it == "-i" || it == "-in" || it == "-ni" }
    val positional = args.filter { !it.startsWith("-") }
    val query = positional.firstOrNull()
      ?: return errorResult("Usage: grep [-i] <pattern> [file_or_folder]")
    val targetArg = positional.getOrNull(1)
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, targetArg)

    val filesToSearch: List<RepoEntry> =
      if (targetArg != null && targetPath.isNotEmpty()) {
        val entry = repository.findEntryAtPath(session, targetPath)
          ?: return errorResult("grep: not found: $targetPath")
        if (entry.type == EntryType.FILE) {
          listOf(entry)
        } else {
          repository.listDirectory(session, targetPath).filter { it.type == EntryType.FILE }.take(20)
        }
      } else {
        repository.listDirectory(session, currentDir).filter { it.type == EntryType.FILE }.take(20)
      }

    if (filesToSearch.isEmpty()) {
      return TerminalExecutionResult(lines = listOf("(no files in scope)" to TerminalLineKind.MUTED))
    }

    val hits = mutableListOf<Pair<String, TerminalLineKind>>()
    for (file in filesToSearch) {
      val doc = runCatching { repository.getFileDocument(session, file) }.getOrNull() ?: continue
      if (doc.isBinary || doc.isTooLargeForEditor) continue
      val text = doc.textContent ?: continue
      text.lines().forEachIndexed { idx, lineText ->
        if (lineText.contains(query, ignoreCase = ignoreCase)) {
          hits += ("${file.name}:${idx + 1}: ${lineText.trim()}" to TerminalLineKind.OUTPUT)
          if (hits.size >= 80) return TerminalExecutionResult(lines = hits)
        }
      }
    }
    return if (hits.isEmpty()) {
      TerminalExecutionResult(lines = listOf("No matches for '$query'" to TerminalLineKind.MUTED))
    } else {
      TerminalExecutionResult(lines = hits)
    }
  }

  private fun parseLineLimitArgs(args: List<String>, defaultCount: Int): Pair<Int, List<String>> {
    var count = defaultCount
    val fileArgs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
      val token = args[i]
      when {
        token == "-n" && i + 1 < args.size -> {
          count = args[i + 1].toIntOrNull()?.coerceIn(1, 300) ?: defaultCount
          i += 2
        }
        token.startsWith("-") && token.drop(1).toIntOrNull() != null -> {
          count = token.drop(1).toInt().coerceIn(1, 300)
          i++
        }
        token.startsWith("-n") && token.drop(2).toIntOrNull() != null -> {
          count = token.drop(2).toInt().coerceIn(1, 300)
          i++
        }
        token.startsWith("-") -> i++
        else -> {
          fileArgs += token
          i++
        }
      }
    }
    return count to fileArgs
  }

  private suspend fun readFileCommand(
    session: RepoSession,
    currentDir: String,
    fileArgs: List<String>,
    headLimit: Int?,
    tailLimit: Int?,
    showLineNumbers: Boolean,
  ): TerminalExecutionResult {
    val targetArg = fileArgs.firstOrNull() ?: return errorResult("Specify a file path")
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, targetArg)
    if (targetPath.isEmpty()) return errorResult("Specify a file path")
    val entry = repository.findEntryAtPath(session, targetPath)
      ?: return errorResult("File not found: $targetPath")
    if (entry.type == EntryType.DIR) {
      return errorResult("'$targetPath' is a directory (use 'ls $targetArg')")
    }
    val doc = repository.getFileDocument(session, entry)
    if (doc.isBinary) {
      return TerminalExecutionResult(
        lines = listOf("[Binary file: ${entry.name} (${formatBytes(entry.size)})]" to TerminalLineKind.WARNING)
      )
    }
    if (doc.isTooLargeForEditor) {
      return TerminalExecutionResult(
        lines = listOf("[File too large to print in terminal: ${formatBytes(entry.size)}]" to TerminalLineKind.WARNING)
      )
    }
    val allLines = doc.textContent.orEmpty().lines()
    val startIndex =
      when {
        tailLimit != null -> (allLines.size - tailLimit).coerceAtLeast(0)
        else -> 0
      }
    val shown =
      when {
        headLimit != null -> allLines.take(headLimit)
        tailLimit != null -> allLines.drop(startIndex)
        else -> allLines.take(300)
      }
    val resultLines =
      shown.mapIndexed { idx, line ->
        val displayLine =
          if (showLineNumbers) "${(startIndex + idx + 1).toString().padStart(4)}  $line" else line
        displayLine to TerminalLineKind.OUTPUT
      }.toMutableList()
    if (tailLimit == null && allLines.size > shown.size) {
      resultLines += ("... (${allLines.size - shown.size} more lines; use 'tail $targetArg' or 'edit $targetArg')" to TerminalLineKind.MUTED)
    }
    return TerminalExecutionResult(lines = resultLines)
  }

  private suspend fun wcCommand(
    session: RepoSession,
    currentDir: String,
    fileArgs: List<String>,
  ): TerminalExecutionResult {
    val targetArg = fileArgs.firstOrNull() ?: return errorResult("Usage: wc <file>")
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, targetArg)
    val entry = repository.findEntryAtPath(session, targetPath)
      ?: return errorResult("wc: not found: $targetPath")
    if (entry.type == EntryType.DIR) return errorResult("wc: '$targetPath' is a directory")
    val doc = repository.getFileDocument(session, entry)
    if (doc.isBinary) {
      return TerminalExecutionResult(
        lines = listOf("${entry.size} bytes (binary)  $targetPath" to TerminalLineKind.OUTPUT)
      )
    }
    val text = doc.textContent.orEmpty()
    val lineCount = if (text.isEmpty()) 0 else text.lines().size
    val wordCount = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    val byteCount = text.encodeToByteArray().size
    return TerminalExecutionResult(
      lines = listOf("$lineCount lines  $wordCount words  $byteCount bytes  $targetPath" to TerminalLineKind.OUTPUT)
    )
  }

  private suspend fun openEditorCommand(
    session: RepoSession,
    currentDir: String,
    targetArg: String?,
  ): TerminalExecutionResult {
    if (targetArg.isNullOrBlank()) return errorResult("Usage: edit <file>")
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, targetArg)
    val entry = repository.findEntryAtPath(session, targetPath)
      ?: return errorResult("File not found: $targetPath")
    if (entry.type == EntryType.DIR) {
      return errorResult("'$targetPath' is a directory")
    }
    return TerminalExecutionResult(
      lines = listOf("Opening ${entry.path} in editor…" to TerminalLineKind.MUTED),
      openEditorEntry = entry,
    )
  }

  private suspend fun writeTextFile(
    session: RepoSession,
    currentDir: String,
    rawTarget: String,
    content: String,
    append: Boolean,
  ): TerminalExecutionResult {
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, rawTarget)
    if (targetPath.isEmpty()) return errorResult("Specify a valid file path")
    val existing = repository.findEntryAtPath(session, targetPath)
    if (existing?.type == EntryType.DIR) {
      return errorResult("Cannot overwrite directory: $targetPath")
    }
    val finalContent =
      if (append && existing != null) {
        val doc = repository.getFileDocument(session, existing)
        if (doc.isBinary) return errorResult("Cannot append text to binary file: $targetPath")
        val prev = doc.textContent.orEmpty()
        if (prev.isEmpty() || prev.endsWith("\n")) "$prev$content" else "$prev\n$content"
      } else {
        content
      }
    val updatedSession =
      repository.saveTextFile(
        session = session,
        path = targetPath,
        existingSha = existing?.sha,
        content = finalContent,
        commitMessage =
          when {
            existing == null -> "Create $targetPath"
            append -> "Append to $targetPath"
            else -> "Update $targetPath"
          },
      )
    val actionVerb =
      when {
        existing == null -> "Created"
        append -> "Appended"
        else -> "Updated"
      }
    return TerminalExecutionResult(
      lines =
        listOf(
          "$actionVerb $targetPath (${formatBytes(finalContent.encodeToByteArray().size.toLong())})" to
            TerminalLineKind.SUCCESS
        ),
      updatedSession = updatedSession,
      didMutateRepo = true,
    )
  }

  private suspend fun mkdirCommand(
    session: RepoSession,
    currentDir: String,
    folderArg: String?,
  ): TerminalExecutionResult {
    val cleanArg = folderArg?.trim().orEmpty()
    if (cleanArg.isEmpty()) return errorResult("Usage: mkdir <folder>")
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, cleanArg)
    if (targetPath.isEmpty()) return errorResult("Invalid folder path")
    val parent = targetPath.substringBeforeLast('/', "")
    val folderName = targetPath.substringAfterLast('/')
    val updated =
      repository.createFolder(
        session = session,
        parentPath = parent,
        folderName = folderName,
        commitMessage = "Create folder $targetPath",
      )
    return TerminalExecutionResult(
      lines = listOf("Created folder $targetPath/" to TerminalLineKind.SUCCESS),
      updatedSession = updated,
      didMutateRepo = true,
    )
  }

  private suspend fun cpCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
  ): TerminalExecutionResult {
    if (args.size < 2) return errorResult("Usage: cp <source-file> <destination-file>")
    val srcPath = TerminalCommandParser.resolveRepoPath(currentDir, args[0])
    val dstPath = TerminalCommandParser.resolveRepoPath(currentDir, args[1])
    if (srcPath.isEmpty() || dstPath.isEmpty()) {
      return errorResult("Specify valid source and destination paths")
    }
    val updated =
      repository.copyFile(
        session = session,
        sourcePath = srcPath,
        destPath = dstPath,
        commitMessage = "Copy $srcPath to $dstPath",
      )
    return TerminalExecutionResult(
      lines = listOf("Copied $srcPath -> $dstPath" to TerminalLineKind.SUCCESS),
      updatedSession = updated,
      didMutateRepo = true,
    )
  }

  private suspend fun mvCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
  ): TerminalExecutionResult {
    if (args.size < 2) return errorResult("Usage: mv <source> <destination>")
    val srcPath = TerminalCommandParser.resolveRepoPath(currentDir, args[0])
    val dstPath = TerminalCommandParser.resolveRepoPath(currentDir, args[1])
    if (srcPath.isEmpty() || dstPath.isEmpty()) return errorResult("Specify valid source and destination paths")
    val entry = repository.findEntryAtPath(session, srcPath)
      ?: return errorResult("mv: not found: $srcPath")
    val updated =
      repository.renameEntry(
        session = session,
        oldPath = srcPath,
        newPath = dstPath,
        isDirectory = entry.type == EntryType.DIR,
        commitMessage = "Move $srcPath to $dstPath",
      )
    return TerminalExecutionResult(
      lines = listOf("Moved $srcPath -> $dstPath" to TerminalLineKind.SUCCESS),
      updatedSession = updated,
      didMutateRepo = true,
    )
  }

  private suspend fun rmCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
  ): TerminalExecutionResult {
    val forceRecursive = args.any { it == "-r" || it == "-rf" || it == "-fr" || it == "--recursive" }
    val targetArg = args.firstOrNull { !it.startsWith("-") }
      ?: return errorResult("Usage: rm <file>  or  rm -rf <folder>")
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, targetArg)
    if (targetPath.isEmpty()) return errorResult("Cannot remove repository root")
    val entry = repository.findEntryAtPath(session, targetPath)
      ?: return errorResult("rm: not found: $targetPath")
    if (entry.type == EntryType.DIR && !forceRecursive) {
      return errorResult("rm: '$targetPath' is a folder. Run 'rm -rf $targetArg' to delete folder.")
    }
    val updated =
      repository.deleteEntry(
        session = session,
        entry = entry,
        commitMessage = "Delete $targetPath",
      )
    val clearedCurrent = currentDir == targetPath || currentDir.startsWith("$targetPath/")
    return TerminalExecutionResult(
      lines = listOf("Deleted $targetPath" to TerminalLineKind.SUCCESS),
      newDir = if (clearedCurrent) "" else null,
      updatedSession = updated,
      didMutateRepo = true,
    )
  }

  private suspend fun logCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
  ): TerminalExecutionResult {
    val limit = args.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()?.coerceIn(1, 30) ?: 10
    val commits = repository.listCommits(session, pathFilter = currentDir.takeIf { it.isNotEmpty() }, limit = limit)
    if (commits.isEmpty()) {
      return TerminalExecutionResult(lines = listOf("No commits found." to TerminalLineKind.MUTED))
    }
    val header =
      if (currentDir.isEmpty()) "Recent commits on ${session.branch}:"
      else "Recent commits in ~/$currentDir (${session.branch}):"
    val lines = mutableListOf(header to TerminalLineKind.MUTED)
    commits.forEach { commit ->
      val dateShort = commit.dateIso.take(10)
      lines += ("${commit.shortSha}  ${commit.message}" to TerminalLineKind.OUTPUT)
      if (commit.authorName.isNotEmpty() || dateShort.isNotEmpty()) {
        lines += ("         by ${commit.authorName} • $dateShort" to TerminalLineKind.MUTED)
      }
    }
    return TerminalExecutionResult(lines = lines)
  }

  private suspend fun showCommitCommand(
    session: RepoSession,
    refArg: String?,
  ): TerminalExecutionResult {
    val detail = repository.getCommitDetail(session, ref = refArg ?: session.branch)
    val lines = mutableListOf<Pair<String, TerminalLineKind>>()
    lines += ("commit ${detail.sha}" to TerminalLineKind.WARNING)
    if (detail.authorName.isNotEmpty() || detail.dateIso.isNotEmpty()) {
      lines += ("Author: ${detail.authorName} (${detail.dateIso.take(10)})" to TerminalLineKind.MUTED)
    }
    lines += ("  ${detail.message.lineSequence().firstOrNull().orEmpty()}" to TerminalLineKind.OUTPUT)
    if (detail.files.isEmpty()) {
      lines += ("(no changed files)" to TerminalLineKind.MUTED)
    } else {
      lines += ("Files changed (${detail.files.size}):" to TerminalLineKind.DIRECTORY)
      detail.files.take(20).forEach { file ->
        lines += ("  [${file.status}] ${file.filename} (+${file.additions} -${file.deletions})" to TerminalLineKind.OUTPUT)
        val patchLines = file.patch?.lines()?.take(18).orEmpty()
        patchLines.forEach { pLine ->
          val kind =
            when {
              pLine.startsWith("+") && !pLine.startsWith("+++") -> TerminalLineKind.SUCCESS
              pLine.startsWith("-") && !pLine.startsWith("---") -> TerminalLineKind.ERROR
              pLine.startsWith("@@") -> TerminalLineKind.DIRECTORY
              else -> TerminalLineKind.MUTED
            }
          lines += ("    $pLine" to kind)
        }
      }
      if (detail.files.size > 20) {
        lines += ("... (${detail.files.size - 20} more files)" to TerminalLineKind.MUTED)
      }
    }
    return TerminalExecutionResult(lines = lines)
  }

  private suspend fun branchCommand(
    session: RepoSession,
    args: List<String>,
  ): TerminalExecutionResult {
    val cleanArgs = args.filter { it != "-a" && it != "--list" }
    if (cleanArgs.isEmpty()) {
      val refreshed = repository.refreshBranches(session)
      val lines =
        refreshed.branches.map { name ->
          if (name == refreshed.branch) "* $name" to TerminalLineKind.SUCCESS
          else "  $name" to TerminalLineKind.OUTPUT
        }
      return TerminalExecutionResult(lines = lines, updatedSession = refreshed)
    }
    val newBranch = cleanArgs.first()
    val updated = repository.createBranch(session, newBranch)
    return TerminalExecutionResult(
      lines = listOf("Created and switched to branch '${updated.branch}'" to TerminalLineKind.SUCCESS),
      updatedSession = updated,
      didMutateRepo = true,
    )
  }

  private suspend fun checkoutCommand(
    session: RepoSession,
    args: List<String>,
  ): TerminalExecutionResult {
    val createNew = args.contains("-b") || args.contains("-c")
    val targetBranch = args.firstOrNull { !it.startsWith("-") }
      ?: return errorResult("Usage: git checkout [-b] <branch>")
    if (createNew) {
      val updated = repository.createBranch(session, targetBranch)
      return TerminalExecutionResult(
        lines = listOf("Switched to a new branch '${updated.branch}'" to TerminalLineKind.SUCCESS),
        updatedSession = updated,
        didMutateRepo = true,
      )
    }
    val refreshed = repository.refreshBranches(session)
    if (targetBranch !in refreshed.branches) {
      return errorResult("Branch '$targetBranch' not found. Use 'git checkout -b $targetBranch' to create it.")
    }
    return TerminalExecutionResult(
      lines = listOf("Switched to branch '$targetBranch'" to TerminalLineKind.SUCCESS),
      updatedSession = refreshed.copy(branch = targetBranch),
      didMutateRepo = true,
    )
  }

  private suspend fun visibilityCommand(
    session: RepoSession,
    arg: String?,
  ): TerminalExecutionResult {
    val makePrivate =
      when (arg?.lowercase()) {
        "private", "priv" -> true
        "public", "pub" -> false
        else -> return errorResult("Usage: visibility <public|private>")
      }
    val updatedSummary = repository.updateRepositoryVisibility(session.token, session.fullName, makePrivate)
    val label = if (updatedSummary.isPrivate) "Private" else "Public"
    return TerminalExecutionResult(
      lines = listOf("${session.fullName} is now $label" to TerminalLineKind.SUCCESS),
      updatedSession = session.copy(isPrivate = updatedSummary.isPrivate),
      didMutateRepo = true,
    )
  }

  private fun cloneCommand(session: RepoSession): TerminalExecutionResult {
    val cloneCmd = "git clone -b ${session.branch} https://github.com/${session.fullName}.git"
    return TerminalExecutionResult(
      lines =
        listOf(
          cloneCmd to TerminalLineKind.DIRECTORY,
          "Copied clone command to clipboard (ready for Termux)." to TerminalLineKind.SUCCESS,
        ),
      clipboardCopyText = cloneCmd,
    )
  }

  private fun termuxCommand(session: RepoSession): TerminalExecutionResult {
    val cmd = "pkg install -y git && git clone -b ${session.branch} https://github.com/${session.fullName}.git"
    return TerminalExecutionResult(
      lines =
        listOf(
          cmd to TerminalLineKind.DIRECTORY,
          "Copied Termux command & launching Termux app if installed…" to TerminalLineKind.SUCCESS,
        ),
      clipboardCopyText = cmd,
      launchTermuxCommand = cmd,
    )
  }

  private fun errorResult(message: String): TerminalExecutionResult =
    TerminalExecutionResult(
      lines = listOf(message to TerminalLineKind.ERROR),
      isError = true,
    )

  private companion object {
    val ALL_COMMANDS =
      listOf(
        "ls", "ll", "cd", "pwd", "tree", "find", "grep",
        "cat", "head", "tail", "wc", "edit",
        "touch", "write", "echo", "mkdir", "cp", "mv", "rm",
        "git", "history", "whoami", "status", "refresh",
        "visibility", "clone", "termux", "clear", "help", "exit",
      )
    val GIT_SUBCOMMANDS =
      listOf("status", "log", "show", "diff", "pull", "branch", "checkout", "switch", "clone")
  }
}
