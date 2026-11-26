package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.ConnectScreen
import com.example.ui.FileEditorScreen
import com.example.ui.GitFilesViewModel
import com.example.ui.RepoBrowserScreen
import com.example.ui.RepoTerminalScreen
import com.example.ui.theme.GitFilesTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      GitFilesTheme {
        GitFilesApp()
      }
    }
  }
}

@Composable
fun GitFilesApp(viewModel: GitFilesViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var askedNotificationPermission by rememberSaveable { mutableStateOf(false) }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

  LaunchedEffect(uiState.screen) {
    if (
      uiState.screen == AppScreen.BROWSER &&
        !askedNotificationPermission &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        askedNotificationPermission = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  val uploadFilesLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) {
        viewModel.uploadPickedFiles(uris)
      }
    }

  val replaceFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      viewModel.onReplaceFilePicked(uri)
    }

  val folderUploadOrReplaceLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
      viewModel.onLocalFolderPicked(treeUri)
    }

  val downloadFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      viewModel.onDownloadFileDestinationPicked(uri)
    }

  val downloadFolderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
      viewModel.onDownloadFolderDestinationPicked(treeUri)
    }

  when (uiState.screen) {
    AppScreen.CONNECT -> {
      ConnectScreen(
        uiState = uiState,
        onTokenChange = viewModel::onTokenChanged,
        onRepoUrlChange = viewModel::onRepoUrlChanged,
        onBranchChange = viewModel::onBranchInputChanged,
        onUploadBatchSizeChange = viewModel::onUploadBatchSizeChanged,
        onSelectUploadBatchSizePreset = viewModel::selectUploadBatchSizePreset,
        onConnect = viewModel::connect,
        onOpenRepoPicker = viewModel::openRepoPicker,
        onDismissRepoPicker = viewModel::dismissRepoPicker,
        onSelectRepoFromPicker = viewModel::selectRepoFromPicker,
        onOpenCreateRepo = viewModel::openCreateRepoDialog,
        onDismissCreateRepo = viewModel::dismissCreateRepoDialog,
        onCreateRepo = viewModel::createRepository,
        onRequestToggleRepoVisibility = viewModel::requestToggleRepoVisibility,
        onDismissToggleRepoVisibility = viewModel::dismissToggleRepoVisibility,
        onConfirmToggleRepoVisibility = viewModel::confirmToggleRepoVisibility,
        onRequestDeleteRepo = viewModel::requestDeleteRepo,
        onDismissDeleteRepo = viewModel::dismissDeleteRepo,
        onConfirmDeleteRepo = viewModel::confirmDeleteRepo,
        onOpenSavedTokens = viewModel::openSavedTokensDialog,
        onDismissSavedTokens = viewModel::dismissSavedTokensDialog,
        onOpenAddToken = viewModel::openAddTokenDialog,
        onDismissAddToken = viewModel::dismissAddTokenDialog,
        onSaveNewToken = viewModel::saveNewToken,
        onSelectSavedToken = viewModel::selectSavedToken,
        onDeleteSavedToken = viewModel::deleteSavedToken,
        onClearStatus = viewModel::clearStatusMessage,
        onBackToBrowser = viewModel::returnToBrowserFromSettings,
        onDisconnect = viewModel::disconnect,
      )
    }

    AppScreen.BROWSER -> {
      RepoBrowserScreen(
        uiState = uiState,
        onRefresh = viewModel::refreshCurrentDirectory,
        onOpenSettings = viewModel::openConnectionSettings,
        onSwitchBranch = viewModel::switchBranch,
        onNavigateToPath = viewModel::navigateToPath,
        onNavigateUp = viewModel::navigateUp,
        onOpenFile = viewModel::openFile,
        onUploadFilesClick = { uploadFilesLauncher.launch(arrayOf("*/*")) },
        onUploadFolderClick = { folderUploadOrReplaceLauncher.launch(null) },
        onCreateNewFile = viewModel::createNewFile,
        onCreateNewFolder = viewModel::createNewFolder,
        onReplaceFileClick = { entry ->
          viewModel.prepareReplaceFile(entry)
          replaceFileLauncher.launch(arrayOf("*/*"))
        },
        onReplaceFolderClick = { entry ->
          viewModel.prepareReplaceFolder(entry)
          folderUploadOrReplaceLauncher.launch(null)
        },
        onDownloadFileClick = { entry ->
          viewModel.prepareDownloadFile(entry)
          downloadFileLauncher.launch(entry.name)
        },
        onDownloadFolderClick = { entry ->
          viewModel.prepareDownloadFolder(entry)
          downloadFolderLauncher.launch(null)
        },
        onRenameEntry = viewModel::renameEntry,
        onDeleteEntry = viewModel::deleteEntry,
        onExecuteFolderAction = viewModel::executeFolderAction,
        onDismissPendingFolderAction = viewModel::dismissPendingFolderAction,
        onCancelTransfer = viewModel::cancelTransfer,
        onRetryTransfer = viewModel::retryFailedTransfer,
        onOpenCreateRepo = viewModel::openCreateRepoDialog,
        onDismissCreateRepo = viewModel::dismissCreateRepoDialog,
        onCreateRepo = viewModel::createRepository,
        onRequestToggleCurrentRepoVisibility = viewModel::requestToggleCurrentRepoVisibility,
        onDismissToggleRepoVisibility = viewModel::dismissToggleRepoVisibility,
        onConfirmToggleRepoVisibility = viewModel::confirmToggleRepoVisibility,
        onRequestDeleteCurrentRepo = viewModel::requestDeleteCurrentRepo,
        onDismissDeleteRepo = viewModel::dismissDeleteRepo,
        onConfirmDeleteRepo = viewModel::confirmDeleteRepo,
        onOpenSavedTokens = viewModel::openSavedTokensDialog,
        onDismissSavedTokens = viewModel::dismissSavedTokensDialog,
        onOpenAddToken = viewModel::openAddTokenDialog,
        onDismissAddToken = viewModel::dismissAddTokenDialog,
        onSaveNewToken = viewModel::saveNewToken,
        onSelectSavedToken = viewModel::selectSavedToken,
        onDeleteSavedToken = viewModel::deleteSavedToken,
        onOpenTerminal = viewModel::openTerminal,
        onClearStatus = viewModel::clearStatusMessage,
        onClearError = viewModel::clearBrowserError,
      )
    }

    AppScreen.EDITOR -> {
      FileEditorScreen(
        uiState = uiState,
        onTextChange = viewModel::onEditorTextChanged,
        onSaveCommit = viewModel::saveOpenedFile,
        onDownload = { entry ->
          viewModel.prepareDownloadFile(entry)
          downloadFileLauncher.launch(entry.name)
        },
        onReplace = { entry ->
          viewModel.prepareReplaceFile(entry)
          replaceFileLauncher.launch(arrayOf("*/*"))
        },
        onDelete = viewModel::deleteEntry,
        onBack = viewModel::closeEditor,
        onClearStatus = viewModel::clearStatusMessage,
      )
    }

    AppScreen.TERMINAL -> {
      RepoTerminalScreen(
        uiState = uiState,
        onInputChange = viewModel::onTerminalInputChanged,
        onRunCommand = viewModel::runTerminalCommand,
        onTabComplete = viewModel::tabCompleteTerminalInput,
        onInterruptOrClear = viewModel::interruptOrClearTerminalLine,
        onClearOutput = viewModel::clearTerminalOutput,
        onHistoryUp = { viewModel.navigateTerminalHistory(-1) },
        onHistoryDown = { viewModel.navigateTerminalHistory(1) },
        onClearClipboardEvent = viewModel::clearTerminalClipboardEvent,
        onClearLaunchTermuxEvent = viewModel::clearTerminalLaunchEvent,
        onClearStatus = viewModel::clearStatusMessage,
        onBack = viewModel::closeTerminal,
      )
    }
  }
}
