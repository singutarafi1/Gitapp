package com.example.data.remote

import android.net.Uri
import com.example.data.local.LocalFolderFile
import com.example.data.local.LocalPickedFile
import com.example.data.local.SafFileHelper
import com.example.data.model.CreateBlobRequest
import com.example.data.model.CreateCommitRequest
import com.example.data.model.CreateTreeEntryRequest
import com.example.data.model.CreateTreeRequest
import com.example.data.model.DeleteContentRequest
import com.example.data.model.EntryType
import com.example.data.model.FileDocument
import com.example.data.model.GitHubErrorDto
import com.example.data.model.GitTreeEntryDto
import com.example.data.model.PutContentRequest
import com.example.data.model.RepoEntry
import com.example.data.model.RepoSession
import com.example.data.model.RepoSummary
import com.example.data.model.UpdateRefRequest
import com.squareup.moshi.Moshi
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class GitHubRepository(private val safHelper: SafFileHelper) {

  private val moshi: Moshi = Moshi.Builder().build()
  private val errorAdapter = moshi.adapter(GitHubErrorDto::class.java)

  private val api: GitHubApiService by lazy {
    val headerInterceptor = Interceptor { chain ->
      val request =
        chain
          .request()
          .newBuilder()
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28")
          .header("User-Agent", "GitFiles-Android")
          .build()
      chain.proceed(request)
    }

    val client =
      OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    Retrofit.Builder()
      .baseUrl("https://api.github.com/")
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(GitHubApiService::class.java)
  }

  private fun normalizeToken(raw: String): String =
    raw.trim()
      .removePrefix("Bearer ")
      .removePrefix("bearer ")
      .removePrefix("token ")
      .removePrefix("Token ")
      .filter { !it.isWhitespace() }

  private fun authHeader(token: String): String = "Bearer ${normalizeToken(token)}"

  private fun encodeGitHubPath(path: String): String =
    path.split("/").filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

  private fun joinPath(parent: String, child: String): String {
    val cleanParent = parent.trim('/')
    val cleanChild = child.trim('/')
    return when {
      cleanParent.isEmpty() -> cleanChild
      cleanChild.isEmpty() -> cleanParent
      else -> "$cleanParent/$cleanChild"
    }
  }

  private fun parseHttpError(e: HttpException): String {
    val code = e.code()
    val rawBody = e.response()?.errorBody()?.string()
    val apiMessage =
      rawBody?.let {
        runCatching { errorAdapter.fromJson(it)?.message }.getOrNull()
      }
    return when (code) {
      401 -> "Authentication failed (401): Invalid or expired GitHub token."
      403 -> apiMessage?.let { "Forbidden (403): $it" } ?: "Forbidden (403): Check token permissions."
      404 -> apiMessage?.let { "Not found (404): $it" } ?: "Repository, branch, or path not found (404)."
      409 -> apiMessage?.let { "Conflict (409): $it" } ?: "Conflict (409): Repository state changed, refresh and retry."
      422 -> apiMessage?.let { "Validation failed (422): $it" } ?: "Invalid request (422)."
      else -> apiMessage?.let { "GitHub API error ($code): $it" } ?: "GitHub API error ($code)"
    }
  }

  private suspend fun <T> safeApiCall(block: suspend () -> T): T {
    return try {
      block()
    } catch (e: HttpException) {
      throw IllegalStateException(parseHttpError(e), e)
    }
  }

  suspend fun listAccessibleRepos(token: String): List<RepoSummary> =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")
      val auth = "Bearer $cleanToken"
      val repos = safeApiCall { api.listUserRepos(auth) }
      repos.map {
        RepoSummary(
          fullName = it.fullName,
          htmlUrl = it.htmlUrl.ifEmpty { "https://github.com/${it.fullName}" },
          defaultBranch = it.defaultBranch,
          isPrivate = it.private,
        )
      }
    }

  suspend fun connect(
    token: String,
    repoInput: String,
    preferredBranch: String? = null,
  ): Pair<RepoSession, String> =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")

      val coords =
        GitHubUrlParser.parse(repoInput)
          ?: throw IllegalArgumentException("Invalid GitHub repository URL or owner/repo")

      val auth = "Bearer $cleanToken"
      val repoDto =
        try {
          api.getRepository(auth, coords.owner, coords.repo)
        } catch (e: HttpException) {
          if (e.code() == 404) {
            throw IllegalStateException(diagnoseRepoNotFound(auth, coords.owner, coords.repo), e)
          }
          throw IllegalStateException(parseHttpError(e), e)
        }
      val branchDtos = safeApiCall { api.listBranches(auth, coords.owner, coords.repo) }
      val branchNames = branchDtos.map { it.name }
      val isEmptyRepo = branchNames.isEmpty()

      val requestedBranch =
        preferredBranch?.trim()?.takeIf { it.isNotEmpty() }
          ?: coords.branchFromUrl?.takeIf { it.isNotEmpty() }

      val activeBranch =
        when {
          isEmptyRepo -> requestedBranch ?: repoDto.defaultBranch
          requestedBranch != null && branchNames.contains(requestedBranch) -> requestedBranch
          requestedBranch != null ->
            throw IllegalStateException("Branch '$requestedBranch' was not found in ${repoDto.fullName}")
          branchNames.contains(repoDto.defaultBranch) -> repoDto.defaultBranch
          else -> branchNames.first()
        }

      val session =
        RepoSession(
          token = cleanToken,
          rawRepoInput = repoInput.trim(),
          owner = coords.owner,
          repo = coords.repo,
          fullName = repoDto.fullName,
          branch = activeBranch,
          defaultBranch = repoDto.defaultBranch,
          branches = branchNames.ifEmpty { listOf(activeBranch) },
          isEmptyRepo = isEmptyRepo,
        )
      val initialPath = coords.pathFromUrl.orEmpty()
      session to initialPath
    }

  suspend fun refreshBranches(session: RepoSession): RepoSession =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      val branches = safeApiCall { api.listBranches(auth, session.owner, session.repo) }.map { it.name }
      session.copy(
        branches = branches.ifEmpty { listOf(session.branch) },
        isEmptyRepo = branches.isEmpty(),
      )
    }

  suspend fun listDirectory(session: RepoSession, path: String): List<RepoEntry> =
    withContext(Dispatchers.IO) {
      if (session.isEmptyRepo) return@withContext emptyList()
      val auth = authHeader(session.token)
      val cleanPath = path.trim('/')

      val response =
        if (cleanPath.isEmpty()) {
          api.listRootContents(auth, session.owner, session.repo, session.branch)
        } else {
          api.listPathContents(auth, session.owner, session.repo, encodeGitHubPath(cleanPath), session.branch)
        }

      if (response.code() == 404) {
        return@withContext emptyList()
      }
      if (!response.isSuccessful) {
        throw HttpException(response).let { IllegalStateException(parseHttpError(it), it) }
      }

      val items = response.body().orEmpty()
      items
        .map { dto ->
          RepoEntry(
            name = dto.name,
            path = dto.path,
            sha = dto.sha,
            size = dto.size,
            type = if (dto.type == "dir") EntryType.DIR else EntryType.FILE,
            downloadUrl = dto.downloadUrl,
          )
        }
        .sortedWith(compareBy<RepoEntry> { it.type != EntryType.DIR }.thenBy { it.name.lowercase() })
    }

  suspend fun getFileDocument(session: RepoSession, entry: RepoEntry): FileDocument =
    withContext(Dispatchers.IO) {
      val maxEditorBytes = 1_500_000L
      if (entry.size > maxEditorBytes || isKnownBinaryExtension(entry.name)) {
        return@withContext FileDocument(
          name = entry.name,
          path = entry.path,
          sha = entry.sha,
          size = entry.size,
          textContent = null,
          isBinary = isKnownBinaryExtension(entry.name),
          isTooLargeForEditor = entry.size > maxEditorBytes,
        )
      }

      val auth = authHeader(session.token)
      val bytes =
        safeApiCall {
          val resp = api.downloadRawBlob(auth, owner = session.owner, repo = session.repo, sha = entry.sha)
          if (!resp.isSuccessful) throw HttpException(resp)
          resp.body()?.bytes() ?: ByteArray(0)
        }

      val decodedText = tryDecodeUtf8(bytes)
      FileDocument(
        name = entry.name,
        path = entry.path,
        sha = entry.sha,
        size = bytes.size.toLong(),
        textContent = decodedText,
        isBinary = decodedText == null,
        isTooLargeForEditor = false,
      )
    }

  suspend fun saveTextFile(
    session: RepoSession,
    path: String,
    existingSha: String?,
    content: String,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      val base64 = content.encodeToByteArray().toByteString().base64()
      safeApiCall {
        api.putFileContent(
          auth = auth,
          owner = session.owner,
          repo = session.repo,
          encodedPath = encodeGitHubPath(path),
          body =
            PutContentRequest(
              message = commitMessage.ifBlank { "Update ${path.substringAfterLast('/')}" },
              content = base64,
              branch = session.branch,
              sha = existingSha,
            ),
        )
      }
      if (session.isEmptyRepo) refreshBranches(session) else session
    }

  suspend fun createFolder(
    session: RepoSession,
    parentPath: String,
    folderName: String,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanFolder = folderName.trim().trim('/')
      if (cleanFolder.isEmpty()) throw IllegalArgumentException("Folder name cannot be empty")
      val gitkeepPath = joinPath(joinPath(parentPath, cleanFolder), ".gitkeep")
      saveTextFile(
        session = session,
        path = gitkeepPath,
        existingSha = null,
        content = "",
        commitMessage = commitMessage.ifBlank { "Create folder $cleanFolder" },
      )
    }

  suspend fun replaceFile(
    session: RepoSession,
    targetPath: String,
    existingSha: String,
    localUri: Uri,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val bytes = safHelper.readBytes(localUri)
      val base64 = bytes.toByteString().base64()
      val auth = authHeader(session.token)
      safeApiCall {
        api.putFileContent(
          auth = auth,
          owner = session.owner,
          repo = session.repo,
          encodedPath = encodeGitHubPath(targetPath),
          body =
            PutContentRequest(
              message = commitMessage.ifBlank { "Replace ${targetPath.substringAfterLast('/')}" },
              content = base64,
              branch = session.branch,
              sha = existingSha,
            ),
        )
      }
      session
    }

  suspend fun uploadFiles(
    session: RepoSession,
    targetDirPath: String,
    pickedFiles: List<LocalPickedFile>,
    existingEntriesInDir: List<RepoEntry>,
    commitMessage: String,
    onProgress: (completed: Int, total: Int, currentName: String) -> Unit,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      if (pickedFiles.isEmpty()) return@withContext session
      val auth = authHeader(session.token)
      val existingByName = existingEntriesInDir.filter { it.type == EntryType.FILE }.associateBy { it.name }

      if (session.isEmptyRepo || pickedFiles.size == 1) {
        pickedFiles.forEachIndexed { index, file ->
          onProgress(index, pickedFiles.size, file.name)
          val bytes = safHelper.readBytes(file.uri)
          val base64 = bytes.toByteString().base64()
          val fullPath = joinPath(targetDirPath, file.name)
          val existingSha = existingByName[file.name]?.sha
          safeApiCall {
            api.putFileContent(
              auth = auth,
              owner = session.owner,
              repo = session.repo,
              encodedPath = encodeGitHubPath(fullPath),
              body =
                PutContentRequest(
                  message = commitMessage.ifBlank { "Upload ${file.name}" },
                  content = base64,
                  branch = session.branch,
                  sha = existingSha,
                ),
            )
          }
          onProgress(index + 1, pickedFiles.size, file.name)
        }
        return@withContext if (session.isEmptyRepo) refreshBranches(session) else session
      }

      // Multi-file atomic commit via Git Data API
      val (headCommitSha, _, existingTreeEntries, truncated) = fetchCurrentTree(auth, session)
      val uploads = mutableListOf<Pair<String, String>>()
      pickedFiles.forEachIndexed { index, file ->
        onProgress(index, pickedFiles.size, file.name)
        val bytes = safHelper.readBytes(file.uri)
        val blobSha = createBlob(auth, session, bytes)
        uploads.add(joinPath(targetDirPath, file.name) to blobSha)
        onProgress(index + 1, pickedFiles.size, file.name)
      }

      val uploadPathSet = uploads.map { it.first }.toSet()
      val newTreeEntries =
        if (!truncated) {
          val kept =
            existingTreeEntries
              .filter { it.type != "tree" && it.path !in uploadPathSet }
              .map { CreateTreeEntryRequest(it.path, it.mode, it.type, it.sha) }
          kept + uploads.map { (path, sha) -> CreateTreeEntryRequest(path, "100644", "blob", sha) }
        } else {
          uploads.map { (path, sha) -> CreateTreeEntryRequest(path, "100644", "blob", sha) }
        }

      commitTreeAndMoveRef(
        auth = auth,
        session = session,
        parentCommitSha = headCommitSha,
        baseTreeSha = if (truncated) existingTreeEntries.firstOrNull()?.sha else null,
        entries = newTreeEntries,
        message = commitMessage.ifBlank { "Upload ${pickedFiles.size} files" },
      )
      session
    }

  suspend fun uploadOrReplaceFolder(
    session: RepoSession,
    targetFolderPath: String,
    replaceExistingFolder: Boolean,
    localFiles: List<LocalFolderFile>,
    commitMessage: String,
    onProgress: (completed: Int, total: Int, currentName: String) -> Unit,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanTarget = targetFolderPath.trim('/')
      if (localFiles.isEmpty()) {
        throw IllegalArgumentException("Selected folder contains no files")
      }

      val auth = authHeader(session.token)
      if (session.isEmptyRepo) {
        localFiles.forEachIndexed { index, file ->
          onProgress(index, localFiles.size, file.relativePath)
          val bytes = safHelper.readBytes(file.uri)
          val remotePath = joinPath(cleanTarget, file.relativePath)
          safeApiCall {
            api.putFileContent(
              auth = auth,
              owner = session.owner,
              repo = session.repo,
              encodedPath = encodeGitHubPath(remotePath),
              body =
                PutContentRequest(
                  message = commitMessage.ifBlank { "Upload folder ${cleanTarget.ifEmpty { "root" }}" },
                  content = bytes.toByteString().base64(),
                  branch = session.branch,
                ),
            )
          }
          onProgress(index + 1, localFiles.size, file.relativePath)
        }
        return@withContext refreshBranches(session)
      }

      val (headCommitSha, baseTreeSha, existingTreeEntries, truncated) = fetchCurrentTree(auth, session)
      if (replaceExistingFolder && truncated) {
        throw IllegalStateException("Repository tree is too large for full recursive replacement")
      }

      val uploadedEntries = mutableListOf<CreateTreeEntryRequest>()
      localFiles.forEachIndexed { index, file ->
        onProgress(index, localFiles.size, file.relativePath)
        val bytes = safHelper.readBytes(file.uri)
        val blobSha = createBlob(auth, session, bytes)
        val remotePath = joinPath(cleanTarget, file.relativePath)
        uploadedEntries.add(CreateTreeEntryRequest(remotePath, "100644", "blob", blobSha))
        onProgress(index + 1, localFiles.size, file.relativePath)
      }

      val uploadedPathSet = uploadedEntries.map { it.path }.toSet()
      val finalEntries =
        if (!truncated) {
          val prefix = if (cleanTarget.isEmpty()) "" else "$cleanTarget/"
          val kept =
            existingTreeEntries
              .filter { entry ->
                if (entry.type == "tree") return@filter false
                if (entry.path in uploadedPathSet) return@filter false
                if (replaceExistingFolder) {
                  if (cleanTarget.isEmpty()) {
                    false
                  } else {
                    entry.path != cleanTarget && !entry.path.startsWith(prefix)
                  }
                } else {
                  true
                }
              }
              .map { CreateTreeEntryRequest(it.path, it.mode, it.type, it.sha) }
          kept + uploadedEntries
        } else {
          uploadedEntries
        }

      commitTreeAndMoveRef(
        auth = auth,
        session = session,
        parentCommitSha = headCommitSha,
        baseTreeSha = if (truncated) baseTreeSha else null,
        entries = finalEntries,
        message =
          commitMessage.ifBlank {
            if (replaceExistingFolder) "Replace folder ${cleanTarget.ifEmpty { "root" }}"
            else "Upload folder ${cleanTarget.ifEmpty { "root" }}"
          },
      )
      session
    }

  suspend fun renameEntry(
    session: RepoSession,
    oldPath: String,
    newPath: String,
    isDirectory: Boolean,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanOld = oldPath.trim('/')
      val cleanNew = newPath.trim('/')
      if (cleanNew.isEmpty()) throw IllegalArgumentException("New name cannot be empty")
      if (cleanOld == cleanNew) return@withContext session

      val auth = authHeader(session.token)
      val (headCommitSha, _, existingTreeEntries, truncated) = fetchCurrentTree(auth, session)
      if (truncated) {
        throw IllegalStateException("Repository tree is too large for atomic rename")
      }

      val oldPrefix = "$cleanOld/"
      val newEntries =
        existingTreeEntries
          .filter { it.type != "tree" }
          .map { entry ->
            val updatedPath =
              if (isDirectory) {
                if (entry.path.startsWith(oldPrefix)) {
                  cleanNew + "/" + entry.path.removePrefix(oldPrefix)
                } else {
                  entry.path
                }
              } else {
                if (entry.path == cleanOld) cleanNew else entry.path
              }
            CreateTreeEntryRequest(updatedPath, entry.mode, entry.type, entry.sha)
          }

      commitTreeAndMoveRef(
        auth = auth,
        session = session,
        parentCommitSha = headCommitSha,
        baseTreeSha = null,
        entries = newEntries,
        message = commitMessage.ifBlank { "Rename $cleanOld to $cleanNew" },
      )
      session
    }

  suspend fun deleteEntry(
    session: RepoSession,
    entry: RepoEntry,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      if (entry.type == EntryType.FILE) {
        safeApiCall {
          api.deleteFileContent(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            encodedPath = encodeGitHubPath(entry.path),
            body =
              DeleteContentRequest(
                message = commitMessage.ifBlank { "Delete ${entry.name}" },
                sha = entry.sha,
                branch = session.branch,
              ),
          )
        }
        return@withContext refreshBranches(session)
      }

      // Directory delete via atomic tree commit (or sequential delete if it clears the entire repo)
      val (headCommitSha, _, existingTreeEntries, truncated) = fetchCurrentTree(auth, session)
      if (truncated) {
        throw IllegalStateException("Repository tree is too large for recursive folder delete")
      }

      val prefix = "${entry.path.trim('/')}/"
      val nonTreeEntries = existingTreeEntries.filter { it.type != "tree" }
      val targetEntries = nonTreeEntries.filter { it.path == entry.path || it.path.startsWith(prefix) }
      val keptEntries =
        nonTreeEntries
          .filter { it.path != entry.path && !it.path.startsWith(prefix) }
          .map { CreateTreeEntryRequest(it.path, it.mode, it.type, it.sha) }

      if (keptEntries.isEmpty()) {
        // Removing all files from branch: delete sequentially via Contents API so branch clears cleanly
        for (item in targetEntries) {
          safeApiCall {
            api.deleteFileContent(
              auth = auth,
              owner = session.owner,
              repo = session.repo,
              encodedPath = encodeGitHubPath(item.path),
              body =
                DeleteContentRequest(
                  message = commitMessage.ifBlank { "Delete folder ${entry.name}" },
                  sha = item.sha,
                  branch = session.branch,
                ),
            )
          }
        }
        return@withContext refreshBranches(session)
      }

      commitTreeAndMoveRef(
        auth = auth,
        session = session,
        parentCommitSha = headCommitSha,
        baseTreeSha = null,
        entries = keptEntries,
        message = commitMessage.ifBlank { "Delete folder ${entry.name}" },
      )
      session
    }

  suspend fun downloadFile(session: RepoSession, entry: RepoEntry, destUri: Uri) =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      safeApiCall {
        val resp = api.downloadRawBlob(auth, owner = session.owner, repo = session.repo, sha = entry.sha)
        if (!resp.isSuccessful) throw HttpException(resp)
        val body = resp.body() ?: throw IllegalStateException("Empty download response")
        body.byteStream().use { input -> safHelper.copyStreamToUri(input, destUri) }
      }
    }

  suspend fun downloadFolder(
    session: RepoSession,
    folderEntry: RepoEntry,
    destTreeUri: Uri,
    onProgress: (completed: Int, total: Int, currentName: String) -> Unit,
  ) =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      val (_, _, existingTreeEntries, truncated) = fetchCurrentTree(auth, session)
      if (truncated) {
        throw IllegalStateException("Repository tree is too large for folder download")
      }

      val cleanFolder = folderEntry.path.trim('/')
      val prefix = "$cleanFolder/"
      val blobs =
        existingTreeEntries.filter { it.type == "blob" && it.path.startsWith(prefix) }
      if (blobs.isEmpty()) {
        throw IllegalStateException("Folder '${folderEntry.name}' has no files")
      }

      blobs.forEachIndexed { index, blob ->
        val relPath = joinPath(folderEntry.name, blob.path.removePrefix(prefix))
        onProgress(index, blobs.size, relPath)
        safeApiCall {
          val resp = api.downloadRawBlob(auth, owner = session.owner, repo = session.repo, sha = blob.sha)
          if (!resp.isSuccessful) throw HttpException(resp)
          val body = resp.body() ?: throw IllegalStateException("Empty blob response")
          body.byteStream().use { input ->
            safHelper.writeStreamToTreeRelativePath(destTreeUri, relPath, input)
          }
        }
        onProgress(index + 1, blobs.size, relPath)
      }
    }

  private data class TreeSnapshot(
    val headCommitSha: String,
    val baseTreeSha: String,
    val entries: List<GitTreeEntryDto>,
    val truncated: Boolean,
  )

  private suspend fun fetchCurrentTree(auth: String, session: RepoSession): TreeSnapshot {
    val ref =
      safeApiCall {
        api.getBranchRef(auth, session.owner, session.repo, encodeGitHubPath(session.branch))
      }
    val headSha = ref.refObject.sha
    val commit = safeApiCall { api.getCommit(auth, session.owner, session.repo, headSha) }
    val tree = safeApiCall { api.getTreeRecursive(auth, session.owner, session.repo, commit.tree.sha) }
    return TreeSnapshot(
      headCommitSha = headSha,
      baseTreeSha = commit.tree.sha,
      entries = tree.tree,
      truncated = tree.truncated,
    )
  }

  private suspend fun createBlob(auth: String, session: RepoSession, bytes: ByteArray): String {
    val base64 = bytes.toByteString().base64()
    return safeApiCall {
      api.createBlob(auth, session.owner, session.repo, CreateBlobRequest(content = base64)).sha
    }
  }

  private suspend fun commitTreeAndMoveRef(
    auth: String,
    session: RepoSession,
    parentCommitSha: String,
    baseTreeSha: String?,
    entries: List<CreateTreeEntryRequest>,
    message: String,
  ) {
    val createdTree =
      safeApiCall {
        api.createTree(
          auth,
          session.owner,
          session.repo,
          CreateTreeRequest(baseTree = baseTreeSha, tree = entries),
        )
      }
    val createdCommit =
      safeApiCall {
        api.createCommit(
          auth,
          session.owner,
          session.repo,
          CreateCommitRequest(
            message = message,
            tree = createdTree.sha,
            parents = listOf(parentCommitSha),
          ),
        )
      }
    safeApiCall {
      api.updateBranchRef(
        auth,
        session.owner,
        session.repo,
        encodeGitHubPath(session.branch),
        UpdateRefRequest(sha = createdCommit.sha),
      )
    }
  }

  private fun isKnownBinaryExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in
      setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
        "pdf", "zip", "gz", "tar", "7z", "rar",
        "apk", "aab", "jar", "aar", "dex", "so", "exe", "dll",
        "ttf", "otf", "woff", "woff2",
        "mp3", "wav", "ogg", "m4a", "mp4", "mov", "webm",
        "keystore", "jks",
      )
  }

  private fun tryDecodeUtf8(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return try {
      val decoder =
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun diagnoseRepoNotFound(auth: String, owner: String, repo: String): String {
    return try {
      val userResp = api.getAuthenticatedUser(auth)
      if (userResp.code() == 401) {
        return "Authentication failed (401): Invalid or expired GitHub token."
      }
      if (!userResp.isSuccessful) {
        return "Not found (404): Repository '$owner/$repo' not found or token lacks permission."
      }
      val login = userResp.body()?.login ?: "user"
      val scopesHeader = userResp.headers()["X-OAuth-Scopes"]

      // Classic PAT: X-OAuth-Scopes header is present
      if (scopesHeader != null) {
        val scopes = scopesHeader.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if ("repo" !in scopes) {
          val scopeText = if (scopes.isEmpty()) "none" else scopes.joinToString(", ")
          return "Token (@$login) is missing 'repo' scope for private repositories (current scopes: $scopeText)."
        }
        return "Signed in as @$login, but '$owner/$repo' was not found or is inaccessible."
      }

      // Fine-grained PAT: verify whether the repository is included in Repository access
      val accessible = runCatching { api.listUserRepos(auth) }.getOrNull().orEmpty()
      val matched = accessible.any { it.fullName.equals("$owner/$repo", ignoreCase = true) }
      if (!matched) {
        return "Signed in as @$login, but this token cannot access '$owner/$repo'. In GitHub Fine-grained Token settings, set Repository access to 'All repositories' (or select '$repo') and enable Repository permissions -> Contents (Read and write)."
      }
      "Signed in as @$login, but repository '$owner/$repo' returned 404. Check Repository permissions -> Contents (Read and write)."
    } catch (_: Exception) {
      "Not found (404): Repository '$owner/$repo' not found or token lacks access."
    }
  }
}
