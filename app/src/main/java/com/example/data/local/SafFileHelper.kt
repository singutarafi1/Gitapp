package com.example.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalPickedFile(
  val name: String,
  val uri: Uri,
  val size: Long,
)

data class LocalFolderFile(
  val relativePath: String,
  val uri: Uri,
  val size: Long,
)

data class LocalFolderSelection(
  val folderName: String,
  val files: List<LocalFolderFile>,
)

class SafFileHelper(private val context: Context) {

  suspend fun readPickedFile(uri: Uri): LocalPickedFile =
    withContext(Dispatchers.IO) {
      var name = "file"
      var size = 0L
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameIdx >= 0) {
            name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: name
          }
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            size = cursor.getLong(sizeIdx)
          }
        }
      }
      LocalPickedFile(name = name, uri = uri, size = size)
    }

  suspend fun readBytes(uri: Uri): ByteArray =
    withContext(Dispatchers.IO) {
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Unable to read selected file")
    }

  suspend fun collectFolderFiles(treeUri: Uri): LocalFolderSelection =
    withContext(Dispatchers.IO) {
      val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
      val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
      val folderName = queryDisplayName(rootDocUri) ?: "folder"
      val files = mutableListOf<LocalFolderFile>()
      traverseDirectory(treeUri, rootDocId, "", files)
      LocalFolderSelection(folderName = folderName, files = files)
    }

  private fun traverseDirectory(
    treeUri: Uri,
    parentDocId: String,
    prefix: String,
    out: MutableList<LocalFolderFile>,
  ) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
      )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

      while (cursor.moveToNext()) {
        val docId = cursor.getString(idIdx) ?: continue
        val name = cursor.getString(nameIdx) ?: continue
        val mimeType = cursor.getString(mimeIdx) ?: ""
        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L

        // Skip internal .git metadata directory if user picks a local git clone
        if (name == ".git" && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
          continue
        }

        val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
          traverseDirectory(treeUri, docId, relPath, out)
        } else {
          val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          out.add(LocalFolderFile(relativePath = relPath, uri = fileUri, size = size))
        }
      }
    }
  }

  private fun queryDisplayName(docUri: Uri): String? {
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    return context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (idx >= 0) cursor.getString(idx) else null
      } else {
        null
      }
    }
  }

  suspend fun copyStreamToUri(
    inputStream: InputStream,
    destUri: Uri,
    onBytesCopied: (Long) -> Unit = {},
  ) =
    withContext(Dispatchers.IO) {
      val output =
        context.contentResolver.openOutputStream(destUri, "wt")
          ?: context.contentResolver.openOutputStream(destUri)
          ?: throw IllegalStateException("Unable to open destination file for writing")
      output.use { out -> copyWithProgress(inputStream, out, onBytesCopied) }
    }

  suspend fun writeStreamToTreeRelativePath(
    rootTreeUri: Uri,
    relativePath: String,
    inputStream: InputStream,
    onBytesCopied: (Long) -> Unit = {},
  ) =
    withContext(Dispatchers.IO) {
      val segments = relativePath.split("/").filter { it.isNotBlank() }
      if (segments.isEmpty()) return@withContext

      var currentParentDocId = DocumentsContract.getTreeDocumentId(rootTreeUri)
      for (dirName in segments.dropLast(1)) {
        currentParentDocId = findOrCreateChildDirectory(rootTreeUri, currentParentDocId, dirName)
      }

      val fileName = segments.last()
      val existingFileDocId = findChildDocumentId(rootTreeUri, currentParentDocId, fileName)
      val targetFileUri =
        if (existingFileDocId != null) {
          DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, existingFileDocId)
        } else {
          val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, currentParentDocId)
          val ext = fileName.substringAfterLast('.', "")
          val mime =
            if (ext.isNotEmpty()) {
              MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                ?: "application/octet-stream"
            } else {
              "application/octet-stream"
            }
          DocumentsContract.createDocument(context.contentResolver, parentUri, mime, fileName)
            ?: throw IllegalStateException("Failed to create $relativePath")
        }

      val output =
        context.contentResolver.openOutputStream(targetFileUri, "wt")
          ?: context.contentResolver.openOutputStream(targetFileUri)
          ?: throw IllegalStateException("Unable to write $relativePath")
      output.use { out -> copyWithProgress(inputStream, out, onBytesCopied) }
    }

  private fun copyWithProgress(
    input: InputStream,
    output: java.io.OutputStream,
    onBytesCopied: (Long) -> Unit,
  ) {
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (read > 0) {
        output.write(buffer, 0, read)
        onBytesCopied(read.toLong())
      }
    }
  }

  private fun findOrCreateChildDirectory(
    treeUri: Uri,
    parentDocId: String,
    dirName: String,
  ): String {
    val existing = findChildDocumentId(treeUri, parentDocId, dirName)
    if (existing != null) return existing

    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    val createdUri =
      DocumentsContract.createDocument(
        context.contentResolver,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        dirName,
      ) ?: throw IllegalStateException("Failed to create folder $dirName")
    return DocumentsContract.getDocumentId(createdUri)
  }

  private fun findChildDocumentId(
    treeUri: Uri,
    parentDocId: String,
    targetName: String,
  ): String? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      while (cursor.moveToNext()) {
        val name = cursor.getString(nameIdx)
        if (name == targetName) {
          return cursor.getString(idIdx)
        }
      }
    }
    return null
  }
}
