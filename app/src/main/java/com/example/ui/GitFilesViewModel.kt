package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalFolderSelection
import com.example.data.local.SafFileHelper
import com.example.data.local.SettingsDataStore
import com.example.data.model.EntryType
import com.example.data.model.FileDocument
import com.example.data.model.RepoEntry
import com.example.data.model.RepoSession
import com.example.data.model.RepoSummary
import com.example.data.model.TransferProgress
import com.example.data.model.TransferProgressUpdate
import com.example.data.remote.GitHubRepository
import com.example.data.remote.GitHubUrlParser
import com.example.sync.TransferForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
  CONNECT,
  BROWSER,
  EDITOR,
}

data class PendingFolderAction(
  val selection: LocalFolderSelection,
  val initialTargetFolderName: String,
  val fixedTargetFolderPath: String?,
  val replaceExisting: Boolean,
  val folderAlreadyExists: Boolean,
)

data class GitFilesUiState(
  val screen: AppScreen = AppScreen.CONNECT,
  val tokenInput: String = "",
  val repoUrlInput: String = "",
  val branchInput: String = "",
  val isConnecting: Boolean = false,
  val connectionError: String? = null,
  val session: RepoSession? = null,
  val currentPath: String = "",
  val entries: List<RepoEntry> = emptyList(),
  val isLoadingEntries: Boolean = false,
  val browserError: String? = null,
  val transferProgress: TransferProgress? = null,
  val statusMessage: String? = null,
  val openedDocument: FileDocument? = null,
  val isLoadingDocument: Boolean = false,
  val editorText: String = "",
  val isSavingDocument: Boolean = false,
  val pendingReplaceFileEntry: RepoEntry? = null,
  val pendingReplaceFolderEntry: RepoEntry? = null,
  val pendingDownloadFileEntry: RepoEntry? = null,
  val pendingDownloadFolderEntry: RepoEntry? = null,
  val pendingFolderAction: PendingFolderAction? = null,
  val isLoadingRepos: Boolean = false,
  val showRepoPicker: Boolean = false,
  val accessibleRepos: List<RepoSummary> = emptyList(),
  val canRetryTransfer: Boolean = false,
  val isManagingRepo: Boolean = false,
  val showCreateRepoDialog: Boolean = false,
  val repoVisibilityTarget: RepoSummary? = null,
  val repoDeleteTarget: RepoSummary? = null,
)

class GitFilesViewModel(application: Application) : AndroidViewModel(application) {

  private val safHelper = SafFileHelper(application.applicationContext)
  private val repository = GitHubRepository(safHelper)
  private val settingsStore = SettingsDataStore(application.applicationContext)

  private val appContext = application.applicationContext
  private val _uiState = MutableStateFlow(GitFilesUiState())
  val uiState: StateFlow<GitFilesUiState> = _uiState.asStateFlow()

  private var activeTransferJob: Job? = null
  private var lastFailedTransferRetry: (() -> Unit)? = null

  fun cancelTransfer() {
    activeTransferJob?.cancel()
    activeTransferJob = null
    lastFailedTransferRetry = null
    TransferForegroundService.stop(appContext)
    _uiState.update {
      it.copy(
        transferProgress = null,
        canRetryTransfer = false,
        statusMessage = "Cancelled",
      )
    }
  }

  fun retryFailedTransfer() {
    val action = lastFailedTransferRetry ?: return
    lastFailedTransferRetry = null
    _uiState.update { it.copy(canRetryTransfer = false, browserError = null) }
    action()
  }

  private fun CoroutineScope.startElapsedTicker(startedAtMs: Long): Job =
    launch {
      while (isActive) {
        delay(1000L)
        val elapsed = System.currentTimeMillis() - startedAtMs
        _uiState.update { state ->
          val current = state.transferProgress ?: return@update state
          state.copy(transferProgress = current.copy(elapsedMs = elapsed))
        }
      }
    }

  private fun publishTransferUpdate(
    title: String,
    startedAtMs: Long,
    update: TransferProgressUpdate,
    updateNotification: Boolean,
  ) {
    val elapsedMs = System.currentTimeMillis() - startedAtMs
    if (updateNotification) {
      val sizePart =
        when {
          update.totalBytes > 0L ->
            "${formatBytes(update.transferredBytes)}/${formatBytes(update.totalBytes)}"
          update.transferredBytes > 0L -> formatBytes(update.transferredBytes)
          else -> ""
        }
      val speedPart = formatSpeed(update.bytesPerSecond)
      val etaPart = update.etaSeconds?.let { "~${formatDuration(it)}" }.orEmpty()
      val stats = listOf(sizePart, speedPart, etaPart).filter { it.isNotEmpty() }.joinToString(" • ")
      TransferForegroundService.updateProgress(
        context = appContext,
        title = title,
        detail = update.currentName,
        completed = update.completed,
        total = update.total,
        stats = stats,
      )
    }
    _uiState.update {
      it.copy(
        transferProgress =
          TransferProgress(
            title = title,
            currentItem = update.currentName,
            completed = update.completed,
            total = update.total,
            transferredBytes = update.transferredBytes,
            totalBytes = update.totalBytes,
            bytesPerSecond = update.bytesPerSecond,
            elapsedMs = elapsedMs,
            etaSeconds = update.etaSeconds,
          )
      )
    }
  }

  init {
    viewModelScope.launch {
      TransferForegroundService.cancelRequests.collect {
        cancelTransfer()
      }
    }
    viewModelScope.launch {
      val saved = settingsStore.savedConnection.first()
      if (saved.token.isNotBlank() || saved.repoUrl.isNotBlank()) {
        _uiState.update {
          it.copy(
            tokenInput = saved.token,
            repoUrlInput = saved.repoUrl,
            branchInput = saved.branch,
          )
        }
        if (saved.token.isNotBlank() && saved.repoUrl.isNotBlank()) {
          connect()
        }
      }
    }
  }

  fun onTokenChanged(value: String) {
    _uiState.update { it.copy(tokenInput = value, connectionError = null) }
  }

  fun onRepoUrlChanged(value: String) {
    _uiState.update { it.copy(repoUrlInput = value, connectionError = null) }
  }

  fun onBranchInputChanged(value: String) {
    _uiState.update { it.copy(branchInput = value, connectionError = null) }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  fun clearBrowserError() {
    lastFailedTransferRetry = null
    _uiState.update { it.copy(browserError = null, canRetryTransfer = false) }
  }

  fun openConnectionSettings() {
    _uiState.update { it.copy(screen = AppScreen.CONNECT, connectionError = null) }
  }

  fun returnToBrowserFromSettings() {
    if (_uiState.value.session != null) {
      _uiState.update { it.copy(screen = AppScreen.BROWSER, connectionError = null) }
    }
  }

  fun disconnect() {
    viewModelScope.launch {
      lastFailedTransferRetry = null
      settingsStore.clearConnection()
      _uiState.value = GitFilesUiState()
    }
  }

  fun openRepoPicker() {
    val token = _uiState.value.tokenInput.trim()
    if (token.isEmpty()) {
      _uiState.update { it.copy(connectionError = "Enter a GitHub Personal Access Token") }
      return
    }
    viewModelScope.launch {
      _uiState.update {
        it.copy(isLoadingRepos = true, showRepoPicker = true, connectionError = null)
      }
      try {
        val repos = repository.listAccessibleRepos(token)
        _uiState.update {
          it.copy(isLoadingRepos = false, accessibleRepos = repos)
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isLoadingRepos = false,
            showRepoPicker = false,
            connectionError = e.message ?: "Failed to load repositories",
          )
        }
      }
    }
  }

  fun dismissRepoPicker() {
    _uiState.update { it.copy(showRepoPicker = false) }
  }

  fun selectRepoFromPicker(repo: RepoSummary) {
    _uiState.update {
      it.copy(
        showRepoPicker = false,
        repoUrlInput = repo.htmlUrl,
        branchInput = repo.defaultBranch,
        connectionError = null,
      )
    }
  }

  private fun setScreenError(message: String) {
    _uiState.update {
      if (it.screen == AppScreen.CONNECT) {
        it.copy(connectionError = message, isManagingRepo = false)
      } else {
        it.copy(browserError = message, isManagingRepo = false)
      }
    }
  }

  private fun activeTokenOrNotify(): String? {
    val state = _uiState.value
    val token = state.tokenInput.trim().ifEmpty { state.session?.token.orEmpty() }
    if (token.isEmpty()) {
      setScreenError("Enter a GitHub Personal Access Token")
      return null
    }
    return token
  }

  fun openCreateRepoDialog() {
    if (activeTokenOrNotify() == null) return
    _uiState.update {
      it.copy(
        showCreateRepoDialog = true,
        connectionError = null,
        browserError = null,
      )
    }
  }

  fun dismissCreateRepoDialog() {
    _uiState.update { it.copy(showCreateRepoDialog = false) }
  }

  fun createRepository(
    name: String,
    description: String,
    isPrivate: Boolean,
    autoInit: Boolean,
  ) {
    val token = activeTokenOrNotify() ?: return
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          showCreateRepoDialog = false,
          isManagingRepo = true,
          connectionError = null,
          browserError = null,
        )
      }
      try {
        val created = repository.createRepository(token, cleanName, description, isPrivate, autoInit)
        _uiState.update { state ->
          val updatedList =
            (listOf(created) + state.accessibleRepos).distinctBy { it.fullName.lowercase() }
          state.copy(
            accessibleRepos = updatedList,
            showRepoPicker = false,
            repoUrlInput = created.htmlUrl,
            branchInput = created.defaultBranch,
            isManagingRepo = false,
            statusMessage = "Created ${created.fullName}",
          )
        }
        connect()
      } catch (e: Exception) {
        setScreenError(e.message ?: "Failed to create repository")
      }
    }
  }

  fun requestToggleRepoVisibility(repo: RepoSummary) {
    _uiState.update { it.copy(repoVisibilityTarget = repo) }
  }

  fun requestToggleCurrentRepoVisibility() {
    val session = _uiState.value.session ?: return
    requestToggleRepoVisibility(
      RepoSummary(
        fullName = session.fullName,
        htmlUrl = "https://github.com/${session.fullName}",
        defaultBranch = session.defaultBranch,
        isPrivate = session.isPrivate,
      )
    )
  }

  fun dismissToggleRepoVisibility() {
    _uiState.update { it.copy(repoVisibilityTarget = null) }
  }

  fun confirmToggleRepoVisibility() {
    val state = _uiState.value
    val target = state.repoVisibilityTarget ?: return
    val token = activeTokenOrNotify() ?: return
    val makePrivate = !target.isPrivate

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          repoVisibilityTarget = null,
          isManagingRepo = true,
          connectionError = null,
          browserError = null,
        )
      }
      try {
        val updated = repository.updateRepositoryVisibility(token, target.fullName, makePrivate)
        _uiState.update { current ->
          val updatedList =
            current.accessibleRepos.map { item ->
              if (item.fullName.equals(updated.fullName, ignoreCase = true)) updated else item
            }
          val updatedSession =
            current.session?.let { s ->
              if (s.fullName.equals(updated.fullName, ignoreCase = true)) {
                s.copy(isPrivate = updated.isPrivate)
              } else {
                s
              }
            }
          current.copy(
            accessibleRepos = updatedList,
            session = updatedSession,
            isManagingRepo = false,
            statusMessage =
              "${updated.fullName} is now ${if (updated.isPrivate) "Private" else "Public"}",
          )
        }
      } catch (e: Exception) {
        setScreenError(e.message ?: "Failed to update repository visibility")
      }
    }
  }

  fun requestDeleteRepo(repo: RepoSummary) {
    _uiState.update { it.copy(repoDeleteTarget = repo) }
  }

  fun requestDeleteCurrentRepo() {
    val session = _uiState.value.session ?: return
    requestDeleteRepo(
      RepoSummary(
        fullName = session.fullName,
        htmlUrl = "https://github.com/${session.fullName}",
        defaultBranch = session.defaultBranch,
        isPrivate = session.isPrivate,
      )
    )
  }

  fun dismissDeleteRepo() {
    _uiState.update { it.copy(repoDeleteTarget = null) }
  }

  fun confirmDeleteRepo() {
    val state = _uiState.value
    val target = state.repoDeleteTarget ?: return
    val token = activeTokenOrNotify() ?: return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          repoDeleteTarget = null,
          isManagingRepo = true,
          connectionError = null,
          browserError = null,
        )
      }
      try {
        repository.deleteRepository(token, target.fullName)
        val currentSession = _uiState.value.session
        val deletedActiveSession =
          currentSession?.fullName?.equals(target.fullName, ignoreCase = true) == true
        val parsedInputFullName =
          GitHubUrlParser.parse(_uiState.value.repoUrlInput)?.let { "${it.owner}/${it.repo}" }
        val inputMatchesDeleted =
          parsedInputFullName?.equals(target.fullName, ignoreCase = true) == true

        if (deletedActiveSession) {
          activeTransferJob?.cancel()
          activeTransferJob = null
          lastFailedTransferRetry = null
          TransferForegroundService.stop(appContext)
          settingsStore.clearRepoSelection()
        } else if (inputMatchesDeleted) {
          settingsStore.clearRepoSelection()
        }

        _uiState.update { current ->
          val remainingRepos =
            current.accessibleRepos.filterNot {
              it.fullName.equals(target.fullName, ignoreCase = true)
            }
          if (deletedActiveSession) {
            current.copy(
              screen = AppScreen.CONNECT,
              session = null,
              repoUrlInput = "",
              branchInput = "",
              currentPath = "",
              entries = emptyList(),
              openedDocument = null,
              editorText = "",
              transferProgress = null,
              canRetryTransfer = false,
              accessibleRepos = remainingRepos,
              isManagingRepo = false,
              connectionError = null,
              browserError = null,
              statusMessage = "Deleted ${target.fullName}",
            )
          } else {
            current.copy(
              accessibleRepos = remainingRepos,
              repoUrlInput = if (inputMatchesDeleted) "" else current.repoUrlInput,
              branchInput = if (inputMatchesDeleted) "" else current.branchInput,
              isManagingRepo = false,
              statusMessage = "Deleted ${target.fullName}",
            )
          }
        }
      } catch (e: Exception) {
        setScreenError(e.message ?: "Failed to delete repository")
      }
    }
  }

  fun connect() {
    val state = _uiState.value
    val token = state.tokenInput.trim()
    val repoUrl = state.repoUrlInput.trim()
    val branch = state.branchInput.trim().takeIf { it.isNotEmpty() }

    if (token.isEmpty()) {
      _uiState.update { it.copy(connectionError = "Enter a GitHub Personal Access Token") }
      return
    }
    if (repoUrl.isEmpty()) {
      _uiState.update { it.copy(connectionError = "Enter a GitHub repository URL or owner/repo") }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isConnecting = true, connectionError = null) }
      try {
        val (session, initialPath) = repository.connect(token, repoUrl, branch)
        settingsStore.saveConnection(token, repoUrl, session.branch)
        _uiState.update {
          it.copy(
            isConnecting = false,
            session = session,
            branchInput = session.branch,
            currentPath = initialPath,
            screen = AppScreen.BROWSER,
            browserError = null,
          )
        }
        loadDirectory(initialPath)
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isConnecting = false,
            connectionError = e.message ?: "Connection failed",
          )
        }
      }
    }
  }

  fun switchBranch(branchName: String) {
    val session = _uiState.value.session ?: return
    if (branchName == session.branch) return
    val updated = session.copy(branch = branchName)
    lastFailedTransferRetry = null
    viewModelScope.launch {
      settingsStore.updateBranch(branchName)
      _uiState.update {
        it.copy(
          session = updated,
          branchInput = branchName,
          currentPath = "",
          canRetryTransfer = false,
        )
      }
      loadDirectory("")
    }
  }

  fun refreshCurrentDirectory() {
    loadDirectory(_uiState.value.currentPath)
  }

  fun navigateToPath(path: String) {
    loadDirectory(path.trim('/'))
  }

  fun navigateUp(): Boolean {
    val current = _uiState.value.currentPath
    if (current.isEmpty()) return false
    val parent = current.substringBeforeLast('/', "")
    loadDirectory(parent)
    return true
  }

  private fun loadDirectory(path: String) {
    val session = _uiState.value.session ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          currentPath = path,
          isLoadingEntries = true,
          browserError = null,
        )
      }
      try {
        val entries = repository.listDirectory(session, path)
        _uiState.update {
          it.copy(
            entries = entries,
            isLoadingEntries = false,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isLoadingEntries = false,
            browserError = e.message ?: "Failed to load directory",
          )
        }
      }
    }
  }

  // --- File Viewer & Editor ---

  fun openFile(entry: RepoEntry) {
    val session = _uiState.value.session ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          screen = AppScreen.EDITOR,
          isLoadingDocument = true,
          openedDocument = null,
          editorText = "",
          browserError = null,
        )
      }
      try {
        val doc = repository.getFileDocument(session, entry)
        _uiState.update {
          it.copy(
            isLoadingDocument = false,
            openedDocument = doc,
            editorText = doc.textContent.orEmpty(),
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isLoadingDocument = false,
            screen = AppScreen.BROWSER,
            browserError = e.message ?: "Failed to open file",
          )
        }
      }
    }
  }

  fun onEditorTextChanged(text: String) {
    _uiState.update { it.copy(editorText = text) }
  }

  fun closeEditor() {
    _uiState.update {
      it.copy(
        screen = AppScreen.BROWSER,
        openedDocument = null,
        editorText = "",
      )
    }
  }

  fun saveOpenedFile(commitMessage: String) {
    val state = _uiState.value
    val session = state.session ?: return
    val doc = state.openedDocument ?: return

    viewModelScope.launch {
      _uiState.update { it.copy(isSavingDocument = true, browserError = null) }
      try {
        val updatedSession =
          repository.saveTextFile(
            session = session,
            path = doc.path,
            existingSha = doc.sha,
            content = state.editorText,
            commitMessage = commitMessage,
          )
        // Reload file metadata so SHA stays current
        val refreshedEntries = repository.listDirectory(updatedSession, state.currentPath)
        val refreshedEntry = refreshedEntries.firstOrNull { it.path == doc.path }
        val refreshedDoc =
          if (refreshedEntry != null) {
            repository.getFileDocument(updatedSession, refreshedEntry)
          } else {
            doc.copy(textContent = state.editorText)
          }
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = refreshedEntries,
            openedDocument = refreshedDoc,
            editorText = refreshedDoc.textContent ?: state.editorText,
            isSavingDocument = false,
            statusMessage = "Committed ${doc.name}",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isSavingDocument = false,
            browserError = e.message ?: "Failed to commit changes",
          )
        }
      }
    }
  }

  // --- Create New File & Folder ---

  fun createNewFile(fileName: String, initialContent: String, commitMessage: String) {
    val state = _uiState.value
    val session = state.session ?: return
    val cleanName = fileName.trim().trim('/')
    if (cleanName.isEmpty()) return
    val targetPath = if (state.currentPath.isEmpty()) cleanName else "${state.currentPath}/$cleanName"

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Creating file", cleanName, 0, 1),
          browserError = null,
        )
      }
      try {
        val existing = state.entries.firstOrNull { it.path == targetPath && it.type == EntryType.FILE }
        val updatedSession =
          repository.saveTextFile(
            session = session,
            path = targetPath,
            existingSha = existing?.sha,
            content = initialContent,
            commitMessage = commitMessage.ifBlank { "Create $cleanName" },
          )
        val entries = repository.listDirectory(updatedSession, state.currentPath)
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = entries,
            transferProgress = null,
            statusMessage = "Created $cleanName",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Failed to create file",
          )
        }
      }
    }
  }

  fun createNewFolder(folderName: String, commitMessage: String) {
    val state = _uiState.value
    val session = state.session ?: return
    val cleanName = folderName.trim().trim('/')
    if (cleanName.isEmpty()) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Creating folder", cleanName, 0, 1),
          browserError = null,
        )
      }
      try {
        val updatedSession =
          repository.createFolder(
            session = session,
            parentPath = state.currentPath,
            folderName = cleanName,
            commitMessage = commitMessage,
          )
        val entries = repository.listDirectory(updatedSession, state.currentPath)
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = entries,
            transferProgress = null,
            statusMessage = "Created folder $cleanName",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Failed to create folder",
          )
        }
      }
    }
  }

  // --- Upload Files ---

  fun uploadPickedFiles(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val state = _uiState.value
    val session = state.session ?: return

    lastFailedTransferRetry = null
    activeTransferJob?.cancel()
    activeTransferJob =
      viewModelScope.launch {
        val title = "Uploading files"
        val startedAtMs = System.currentTimeMillis()
        TransferForegroundService.start(appContext, title, uris.size)
        _uiState.update {
          it.copy(
            transferProgress = TransferProgress(title, "", 0, uris.size),
            browserError = null,
            canRetryTransfer = false,
          )
        }
        val tickerJob = startElapsedTicker(startedAtMs)
        try {
          val picked = uris.map { safHelper.readPickedFile(it) }
          val updatedSession =
            repository.uploadFiles(
              session = session,
              targetDirPath = state.currentPath,
              pickedFiles = picked,
              existingEntriesInDir = state.entries,
              commitMessage =
                if (picked.size == 1) "Upload ${picked.first().name}" else "Upload ${picked.size} files",
            ) { update ->
              publishTransferUpdate(
                title = title,
                startedAtMs = startedAtMs,
                update = update,
                updateNotification = true,
              )
            }
          val entries = repository.listDirectory(updatedSession, state.currentPath)
          _uiState.update {
            it.copy(
              session = updatedSession,
              entries = entries,
              transferProgress = null,
              canRetryTransfer = false,
              statusMessage =
                if (picked.size == 1) "Uploaded ${picked.first().name}" else "Uploaded ${picked.size} files",
            )
          }
        } catch (e: CancellationException) {
          _uiState.update { it.copy(transferProgress = null) }
          throw e
        } catch (e: Exception) {
          lastFailedTransferRetry = { uploadPickedFiles(uris) }
          _uiState.update {
            it.copy(
              transferProgress = null,
              canRetryTransfer = true,
              browserError = e.message ?: "File upload failed",
            )
          }
        } finally {
          tickerJob.cancel()
          TransferForegroundService.stop(appContext)
        }
      }
  }

  // --- Replace File ---

  fun prepareReplaceFile(entry: RepoEntry) {
    _uiState.update { it.copy(pendingReplaceFileEntry = entry) }
  }

  fun onReplaceFilePicked(uri: Uri?) {
    val target = _uiState.value.pendingReplaceFileEntry
    _uiState.update { it.copy(pendingReplaceFileEntry = null) }
    if (uri == null || target == null) return

    val state = _uiState.value
    val session = state.session ?: return
    viewModelScope.launch {
      val title = "Replacing file"
      val startedAtMs = System.currentTimeMillis()
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress(title, target.name, 0, 1),
          browserError = null,
        )
      }
      val tickerJob = startElapsedTicker(startedAtMs)
      try {
        val updatedSession =
          repository.replaceFile(
            session = session,
            targetPath = target.path,
            existingSha = target.sha,
            localUri = uri,
            commitMessage = "Replace ${target.name}",
          ) { update ->
            publishTransferUpdate(
              title = title,
              startedAtMs = startedAtMs,
              update = update,
              updateNotification = false,
            )
          }
        val entries = repository.listDirectory(updatedSession, state.currentPath)
        val updatedEntry = entries.firstOrNull { it.path == target.path }
        val refreshedDoc =
          if (state.screen == AppScreen.EDITOR && updatedEntry != null) {
            repository.getFileDocument(updatedSession, updatedEntry)
          } else {
            state.openedDocument
          }
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = entries,
            openedDocument = refreshedDoc,
            editorText = refreshedDoc?.textContent ?: it.editorText,
            transferProgress = null,
            statusMessage = "Replaced ${target.name}",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Failed to replace file",
          )
        }
      } finally {
        tickerJob.cancel()
      }
    }
  }

  // --- Upload or Replace Folder ---

  fun prepareReplaceFolder(entry: RepoEntry) {
    _uiState.update { it.copy(pendingReplaceFolderEntry = entry) }
  }

  fun onLocalFolderPicked(treeUri: Uri?) {
    val replaceTarget = _uiState.value.pendingReplaceFolderEntry
    _uiState.update { it.copy(pendingReplaceFolderEntry = null) }
    if (treeUri == null) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Scanning folder", "", 0, 1),
          browserError = null,
        )
      }
      try {
        val selection = safHelper.collectFolderFiles(treeUri)
        if (selection.files.isEmpty()) {
          _uiState.update {
            it.copy(
              transferProgress = null,
              browserError = "Selected folder '${selection.folderName}' is empty",
            )
          }
          return@launch
        }

        val state = _uiState.value
        val existingMatch =
          state.entries.any {
            it.type == EntryType.DIR && it.name.equals(selection.folderName, ignoreCase = false)
          }

        _uiState.update {
          it.copy(
            transferProgress = null,
            pendingFolderAction =
              PendingFolderAction(
                selection = selection,
                initialTargetFolderName = replaceTarget?.name ?: selection.folderName,
                fixedTargetFolderPath = replaceTarget?.path,
                replaceExisting = replaceTarget != null || existingMatch,
                folderAlreadyExists = replaceTarget != null || existingMatch,
              ),
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Unable to read local folder",
          )
        }
      }
    }
  }

  fun dismissPendingFolderAction() {
    _uiState.update { it.copy(pendingFolderAction = null) }
  }

  fun executeFolderAction(
    targetFolderName: String,
    replaceExisting: Boolean,
    commitMessage: String,
  ) {
    val state = _uiState.value
    val pending = state.pendingFolderAction ?: return
    _uiState.update { it.copy(pendingFolderAction = null) }

    val targetPath =
      pending.fixedTargetFolderPath
        ?: run {
          val cleanName = targetFolderName.trim().trim('/')
          when {
            cleanName.isEmpty() -> state.currentPath
            state.currentPath.isEmpty() -> cleanName
            else -> "${state.currentPath}/$cleanName"
          }
        }

    runFolderTransfer(
      selection = pending.selection,
      targetPath = targetPath,
      listRefreshPath = state.currentPath,
      replaceExisting = replaceExisting,
      commitMessage = commitMessage,
    )
  }

  private fun runFolderTransfer(
    selection: LocalFolderSelection,
    targetPath: String,
    listRefreshPath: String,
    replaceExisting: Boolean,
    commitMessage: String,
  ) {
    val session = _uiState.value.session ?: return
    val title = if (replaceExisting) "Replacing folder" else "Uploading folder"
    val totalFiles = selection.files.size
    val totalBytes = selection.files.sumOf { it.size.coerceAtLeast(0L) }
    lastFailedTransferRetry = null
    activeTransferJob?.cancel()
    activeTransferJob =
      viewModelScope.launch {
        val startedAtMs = System.currentTimeMillis()
        TransferForegroundService.start(appContext, title, totalFiles)
        _uiState.update {
          it.copy(
            transferProgress =
              TransferProgress(
                title = title,
                currentItem = "",
                completed = 0,
                total = totalFiles,
                totalBytes = totalBytes,
              ),
            browserError = null,
            canRetryTransfer = false,
          )
        }
        val tickerJob = startElapsedTicker(startedAtMs)
        try {
          val updatedSession =
            repository.uploadOrReplaceFolder(
              session = session,
              targetFolderPath = targetPath,
              replaceExistingFolder = replaceExisting,
              localFiles = selection.files,
              commitMessage = commitMessage,
            ) { update ->
              publishTransferUpdate(
                title = title,
                startedAtMs = startedAtMs,
                update = update,
                updateNotification = true,
              )
            }
          val entries = repository.listDirectory(updatedSession, listRefreshPath)
          _uiState.update {
            it.copy(
              session = updatedSession,
              entries = entries,
              transferProgress = null,
              canRetryTransfer = false,
              statusMessage =
                if (replaceExisting) "Replaced folder ${targetPath.substringAfterLast('/').ifEmpty { "root" }}"
                else "Uploaded folder ${targetPath.substringAfterLast('/').ifEmpty { "root" }}",
            )
          }
        } catch (e: CancellationException) {
          _uiState.update { it.copy(transferProgress = null) }
          throw e
        } catch (e: Exception) {
          lastFailedTransferRetry = {
            runFolderTransfer(
              selection = selection,
              targetPath = targetPath,
              listRefreshPath = listRefreshPath,
              replaceExisting = replaceExisting,
              commitMessage = commitMessage,
            )
          }
          _uiState.update {
            it.copy(
              transferProgress = null,
              canRetryTransfer = true,
              browserError = e.message ?: "Folder operation failed",
            )
          }
        } finally {
          tickerJob.cancel()
          TransferForegroundService.stop(appContext)
        }
      }
  }

  // --- Download File & Folder ---

  fun prepareDownloadFile(entry: RepoEntry) {
    _uiState.update { it.copy(pendingDownloadFileEntry = entry) }
  }

  fun onDownloadFileDestinationPicked(destUri: Uri?) {
    val entry = _uiState.value.pendingDownloadFileEntry
    _uiState.update { it.copy(pendingDownloadFileEntry = null) }
    if (destUri == null || entry == null) return

    val session = _uiState.value.session ?: return
    viewModelScope.launch {
      val title = "Downloading"
      val startedAtMs = System.currentTimeMillis()
      _uiState.update {
        it.copy(
          transferProgress =
            TransferProgress(
              title = title,
              currentItem = entry.name,
              completed = 0,
              total = 1,
              totalBytes = entry.size,
            ),
          browserError = null,
        )
      }
      val tickerJob = startElapsedTicker(startedAtMs)
      try {
        repository.downloadFile(session, entry, destUri) { update ->
          publishTransferUpdate(
            title = title,
            startedAtMs = startedAtMs,
            update = update,
            updateNotification = false,
          )
        }
        _uiState.update {
          it.copy(
            transferProgress = null,
            statusMessage = "Downloaded ${entry.name}",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Download failed",
          )
        }
      } finally {
        tickerJob.cancel()
      }
    }
  }

  fun prepareDownloadFolder(entry: RepoEntry) {
    _uiState.update { it.copy(pendingDownloadFolderEntry = entry) }
  }

  fun onDownloadFolderDestinationPicked(destTreeUri: Uri?) {
    val folderEntry = _uiState.value.pendingDownloadFolderEntry
    _uiState.update { it.copy(pendingDownloadFolderEntry = null) }
    if (destTreeUri == null || folderEntry == null) return

    val session = _uiState.value.session ?: return
    val title = "Downloading folder"
    activeTransferJob?.cancel()
    activeTransferJob =
      viewModelScope.launch {
        val startedAtMs = System.currentTimeMillis()
        TransferForegroundService.start(appContext, title, 0)
        _uiState.update {
          it.copy(
            transferProgress = TransferProgress(title, folderEntry.name, 0, 1),
            browserError = null,
          )
        }
        val tickerJob = startElapsedTicker(startedAtMs)
        try {
          repository.downloadFolder(session, folderEntry, destTreeUri) { update ->
            publishTransferUpdate(
              title = title,
              startedAtMs = startedAtMs,
              update = update,
              updateNotification = true,
            )
          }
          _uiState.update {
            it.copy(
              transferProgress = null,
              statusMessage = "Downloaded folder ${folderEntry.name}",
            )
          }
        } catch (e: CancellationException) {
          _uiState.update { it.copy(transferProgress = null) }
          throw e
        } catch (e: Exception) {
          _uiState.update {
            it.copy(
              transferProgress = null,
              browserError = e.message ?: "Folder download failed",
            )
          }
        } finally {
          tickerJob.cancel()
          TransferForegroundService.stop(appContext)
        }
      }
  }

  // --- Rename & Delete ---

  fun renameEntry(entry: RepoEntry, newName: String, commitMessage: String) {
    val state = _uiState.value
    val session = state.session ?: return
    val cleanNewName = newName.trim().trim('/')
    if (cleanNewName.isEmpty() || cleanNewName == entry.name) return

    val parent = entry.path.substringBeforeLast('/', "")
    val newPath = if (parent.isEmpty()) cleanNewName else "$parent/$cleanNewName"

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Renaming", entry.name, 0, 1),
          browserError = null,
        )
      }
      try {
        val updatedSession =
          repository.renameEntry(
            session = session,
            oldPath = entry.path,
            newPath = newPath,
            isDirectory = entry.type == EntryType.DIR,
            commitMessage = commitMessage,
          )
        val entries = repository.listDirectory(updatedSession, state.currentPath)
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = entries,
            transferProgress = null,
            statusMessage = "Renamed to $cleanNewName",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Rename failed",
          )
        }
      }
    }
  }

  fun deleteEntry(entry: RepoEntry, commitMessage: String) {
    val state = _uiState.value
    val session = state.session ?: return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Deleting", entry.name, 0, 1),
          browserError = null,
        )
      }
      try {
        val updatedSession = repository.deleteEntry(session, entry, commitMessage)
        val entries = repository.listDirectory(updatedSession, state.currentPath)
        val closeEditorIfDeleted = state.openedDocument?.path == entry.path
        _uiState.update {
          it.copy(
            session = updatedSession,
            entries = entries,
            screen = if (closeEditorIfDeleted) AppScreen.BROWSER else it.screen,
            openedDocument = if (closeEditorIfDeleted) null else it.openedDocument,
            transferProgress = null,
            statusMessage = "Deleted ${entry.name}",
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            transferProgress = null,
            browserError = e.message ?: "Delete failed",
          )
        }
      }
    }
  }
}
