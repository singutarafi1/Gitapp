package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.ConnectScreen
import com.example.ui.FileEditorScreen
import com.example.ui.GitFilesViewModel
import com.example.ui.RepoBrowserScreen
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
        onConnect = viewModel::connect,
        onOpenRepoPicker = viewModel::openRepoPicker,
        onDismissRepoPicker = viewModel::dismissRepoPicker,
        onSelectRepoFromPicker = viewModel::selectRepoFromPicker,
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
  }
}
