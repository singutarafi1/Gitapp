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
import com.example.data.remote.GitHubRepository
import com.example.sync.TransferForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
        TransferForegroundService.start(appContext, title, uris.size)
        _uiState.update {
          it.copy(
            transferProgress = TransferProgress(title, "", 0, uris.size),
            browserError = null,
            canRetryTransfer = false,
          )
        }
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
            ) { completed, total, currentName ->
              TransferForegroundService.updateProgress(appContext, title, currentName, completed, total)
              _uiState.update {
                it.copy(
                  transferProgress = TransferProgress(title, currentName, completed, total)
                )
              }
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
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Replacing file", target.name, 0, 1),
          browserError = null,
        )
      }
      try {
        val updatedSession =
          repository.replaceFile(
            session = session,
            targetPath = target.path,
            existingSha = target.sha,
            localUri = uri,
            commitMessage = "Replace ${target.name}",
          )
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
    lastFailedTransferRetry = null
    activeTransferJob?.cancel()
    activeTransferJob =
      viewModelScope.launch {
        TransferForegroundService.start(appContext, title, totalFiles)
        _uiState.update {
          it.copy(
            transferProgress = TransferProgress(title, "", 0, totalFiles),
            browserError = null,
            canRetryTransfer = false,
          )
        }
        try {
          val updatedSession =
            repository.uploadOrReplaceFolder(
              session = session,
              targetFolderPath = targetPath,
              replaceExistingFolder = replaceExisting,
              localFiles = selection.files,
              commitMessage = commitMessage,
            ) { completed, total, currentName ->
              TransferForegroundService.updateProgress(appContext, title, currentName, completed, total)
              _uiState.update {
                it.copy(transferProgress = TransferProgress(title, currentName, completed, total))
              }
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
      _uiState.update {
        it.copy(
          transferProgress = TransferProgress("Downloading", entry.name, 0, 1),
          browserError = null,
        )
      }
      try {
        repository.downloadFile(session, entry, destUri)
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
        TransferForegroundService.start(appContext, title, 0)
        _uiState.update {
          it.copy(
            transferProgress = TransferProgress(title, folderEntry.name, 0, 1),
            browserError = null,
          )
        }
        try {
          repository.downloadFolder(session, folderEntry, destTreeUri) { completed, total, currentName ->
            TransferForegroundService.updateProgress(appContext, title, currentName, completed, total)
            _uiState.update {
              it.copy(
                transferProgress = TransferProgress(title, currentName, completed, total)
              )
            }
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
