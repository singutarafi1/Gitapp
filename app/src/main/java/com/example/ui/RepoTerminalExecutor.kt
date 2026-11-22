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
)

class RepoTerminalExecutor(private val repository: GitHubRepository) {

  suspend fun execute(
    rawCommand: String,
    session: RepoSession,
    currentDir: String,
    activeToken: SavedToken?,
  ): TerminalExecutionResult {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return TerminalExecutionResult()

    val echoRedirect = TerminalCommandParser.parseEchoRedirect(trimmed)
    if (echoRedirect != null) {
      val (content, rawTarget) = echoRedirect
      return writeTextFile(session, currentDir, rawTarget, content, commitMessage = null)
    }

    val tokens = TerminalCommandParser.tokenize(trimmed)
    if (tokens.isEmpty()) return TerminalExecutionResult()

    val head = tokens.first().lowercase()
    val args = tokens.drop(1)

    // Support `git <subcommand> ...` seamlessly alongside direct short commands.
    if (head == "git") {
      if (args.isEmpty()) {
        return TerminalExecutionResult(
          lines = listOf("Usage: git <status|log|branch|checkout|switch|clone>" to TerminalLineKind.MUTED)
        )
      }
      val sub = args.first().lowercase()
      val subArgs = args.drop(1)
      return executeSubcommand(sub, subArgs, session, currentDir, activeToken, fromGitPrefix = true)
    }

    return executeSubcommand(head, args, session, currentDir, activeToken, fromGitPrefix = false)
  }

  private suspend fun executeSubcommand(
    cmd: String,
    args: List<String>,
    session: RepoSession,
    currentDir: String,
    activeToken: SavedToken?,
    fromGitPrefix: Boolean,
  ): TerminalExecutionResult {
    return when (cmd) {
      "help", "?" -> helpResult()
      "clear", "cls" -> TerminalExecutionResult(clearScreen = true)
      "exit", "quit" -> TerminalExecutionResult(exitTerminal = true)
      "pwd" -> {
        val display = if (currentDir.isEmpty()) "/" else "/$currentDir"
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
      "status", "info", "repo" -> statusResult(session, currentDir)
      "ls", "ll", "dir" -> listEntriesCommand(session, currentDir, args.firstOrNull { !it.startsWith("-") })
      "cd" -> changeDirectoryCommand(session, currentDir, args.firstOrNull())
      "tree" -> treeCommand(session, currentDir, args.firstOrNull())
      "find", "search" -> findCommand(session, currentDir, args.joinToString(" ").trim())
      "cat", "head" -> readFileCommand(session, currentDir, args, headLimit = if (cmd == "head") 25 else null)
      "edit", "nano", "vim" -> openEditorCommand(session, currentDir, args.firstOrNull())
      "touch" -> {
        val target = args.firstOrNull()
          ?: return errorResult("Usage: touch <file> [initial text...]")
        val content = args.drop(1).joinToString(" ")
        writeTextFile(session, currentDir, target, content, commitMessage = null)
      }
      "write" -> {
        val target = args.firstOrNull()
          ?: return errorResult("Usage: write <file> <content...>")
        val content = args.drop(1).joinToString(" ")
        writeTextFile(session, currentDir, target, content, commitMessage = null)
      }
      "echo" -> TerminalExecutionResult(
        lines = listOf(args.joinToString(" ") to TerminalLineKind.OUTPUT)
      )
      "mkdir" -> mkdirCommand(session, currentDir, args.firstOrNull())
      "mv", "rename" -> mvCommand(session, currentDir, args)
      "rm", "del" -> rmCommand(session, currentDir, args)
      "log", "commits" -> logCommand(session, currentDir, args)
      "branch" -> branchCommand(session, args)
      "checkout", "switch" -> checkoutCommand(session, args)
      "visibility" -> visibilityCommand(session, args.firstOrNull())
      "clone" -> cloneCommand(session)
      else -> {
        val label = if (fromGitPrefix) "git $cmd" else cmd
        TerminalExecutionResult(
          lines =
            listOf(
              "Unknown command: '$label'" to TerminalLineKind.ERROR,
              "Type 'help' to see supported Repo Terminal commands." to TerminalLineKind.MUTED,
            )
        )
      }
    }
  }

  private fun helpResult(): TerminalExecutionResult {
    return TerminalExecutionResult(
      lines =
        listOf(
          "=== GitFiles Repo Terminal ===" to TerminalLineKind.DIRECTORY,
          "Navigation & Inspection:" to TerminalLineKind.SUCCESS,
          "  pwd                       Print repo, branch & current path" to TerminalLineKind.OUTPUT,
          "  ls [path]                 List files and folders" to TerminalLineKind.OUTPUT,
          "  cd <path|..|/>            Change current directory" to TerminalLineKind.OUTPUT,
          "  tree [path]               Show recursive file tree" to TerminalLineKind.OUTPUT,
          "  find <keyword>            Search paths inside current folder" to TerminalLineKind.OUTPUT,
          "  cat <file> / head <file>  Print file contents" to TerminalLineKind.OUTPUT,
          "  edit <file>               Open file in Editor" to TerminalLineKind.OUTPUT,
          "Commit Actions (direct to GitHub):" to TerminalLineKind.SUCCESS,
          "  touch <file> [text]       Create/overwrite file & commit" to TerminalLineKind.OUTPUT,
          "  echo \"hi\" > <file>        Write text to file & commit" to TerminalLineKind.OUTPUT,
          "  mkdir <folder>            Create folder (.gitkeep) & commit" to TerminalLineKind.OUTPUT,
          "  mv <old> <new>            Rename or move file/folder" to TerminalLineKind.OUTPUT,
          "  rm <file> | rm -rf <dir>  Delete file or folder" to TerminalLineKind.OUTPUT,
          "Git & Repo Controls:" to TerminalLineKind.SUCCESS,
          "  git status                Repo, branch, visibility & path" to TerminalLineKind.OUTPUT,
          "  git log [n]               Recent commits (filtered by folder)" to TerminalLineKind.OUTPUT,
          "  git branch [new-name]     List branches or create branch" to TerminalLineKind.OUTPUT,
          "  git checkout [-b] <name>  Switch or create+switch branch" to TerminalLineKind.OUTPUT,
          "  visibility public|private Change repo visibility" to TerminalLineKind.OUTPUT,
          "  git clone                 Print & copy clone command" to TerminalLineKind.OUTPUT,
          "  whoami | clear | exit     Token info, clear screen, or return" to TerminalLineKind.OUTPUT,
        )
    )
  }

  private suspend fun statusResult(session: RepoSession, currentDir: String): TerminalExecutionResult {
    val entries = repository.listDirectory(session, currentDir)
    val dirs = entries.count { it.type == EntryType.DIR }
    val files = entries.count { it.type == EntryType.FILE }
    val pathText = if (currentDir.isEmpty()) "/" else "/$currentDir"
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

  private suspend fun listEntriesCommand(
    session: RepoSession,
    currentDir: String,
    pathArg: String?,
  ): TerminalExecutionResult {
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, pathArg)
    val entries = repository.listDirectory(session, targetPath)
    if (entries.isEmpty()) {
      val display = if (targetPath.isEmpty()) "/" else "/$targetPath"
      return TerminalExecutionResult(
        lines = listOf("(empty or not found: $display)" to TerminalLineKind.MUTED)
      )
    }
    val lines =
      entries.map { entry ->
        if (entry.type == EntryType.DIR) {
          "DIR   ${entry.name}/" to TerminalLineKind.DIRECTORY
        } else {
          val sizeLabel = formatBytes(entry.size).padStart(8)
          "FILE  $sizeLabel  ${entry.name}" to TerminalLineKind.OUTPUT
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
        lines = listOf("/" to TerminalLineKind.DIRECTORY),
        newDir = "",
      )
    }
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, pathArg)
    if (targetPath.isEmpty()) {
      return TerminalExecutionResult(
        lines = listOf("/" to TerminalLineKind.DIRECTORY),
        newDir = "",
      )
    }
    val existing = repository.findEntryAtPath(session, targetPath)
    if (existing == null) {
      return errorResult("cd: no such folder: $targetPath")
    }
    if (existing.type != EntryType.DIR) {
      return errorResult("cd: not a directory: $targetPath")
    }
    return TerminalExecutionResult(
      lines = listOf("/$targetPath" to TerminalLineKind.DIRECTORY),
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
    lines += ((if (targetDir.isEmpty()) "/" else "/$targetDir") to TerminalLineKind.DIRECTORY)
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

  private suspend fun readFileCommand(
    session: RepoSession,
    currentDir: String,
    args: List<String>,
    headLimit: Int?,
  ): TerminalExecutionResult {
    val targetArg = args.firstOrNull() ?: return errorResult("Usage: cat <file>")
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
    val shown = if (headLimit != null) allLines.take(headLimit) else allLines.take(300)
    val resultLines = shown.map { it to TerminalLineKind.OUTPUT }.toMutableList()
    if (allLines.size > shown.size) {
      resultLines += ("... (${allLines.size - shown.size} more lines; use 'edit $targetArg' to view full file)" to TerminalLineKind.MUTED)
    }
    return TerminalExecutionResult(lines = resultLines)
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
    commitMessage: String?,
  ): TerminalExecutionResult {
    val targetPath = TerminalCommandParser.resolveRepoPath(currentDir, rawTarget)
    if (targetPath.isEmpty()) return errorResult("Specify a valid file path")
    val existing = repository.findEntryAtPath(session, targetPath)
    if (existing?.type == EntryType.DIR) {
      return errorResult("Cannot overwrite directory: $targetPath")
    }
    val updatedSession =
      repository.saveTextFile(
        session = session,
        path = targetPath,
        existingSha = existing?.sha,
        content = content,
        commitMessage = commitMessage ?: if (existing == null) "Create $targetPath" else "Update $targetPath",
      )
    val actionVerb = if (existing == null) "Created" else "Updated"
    return TerminalExecutionResult(
      lines = listOf("$actionVerb $targetPath (${formatBytes(content.encodeToByteArray().size.toLong())})" to TerminalLineKind.SUCCESS),
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
      else "Recent commits in /$currentDir (${session.branch}):"
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
          "Copied clone command to clipboard (ready for Termux or desktop shell)." to TerminalLineKind.SUCCESS,
        ),
      clipboardCopyText = cloneCmd,
    )
  }

  private fun errorResult(message: String): TerminalExecutionResult =
    TerminalExecutionResult(lines = listOf(message to TerminalLineKind.ERROR))
}
