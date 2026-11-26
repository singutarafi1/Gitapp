package com.example.data.remote

import android.net.Uri
import com.example.data.local.LocalFolderFile
import com.example.data.local.LocalPickedFile
import com.example.data.local.SafFileHelper
import com.example.data.model.CreateCommitRequest
import com.example.data.model.CreateRefRequest
import com.example.data.model.CreateRepoRequest
import com.example.data.model.CreateTreeEntryRequest
import com.example.data.model.CreateTreeRequest
import com.example.data.model.DeleteContentRequest
import com.example.data.model.DeleteTreeEntryRequest
import com.example.data.model.DeleteTreeRequest
import com.example.data.model.EntryType
import com.example.data.model.FileDocument
import com.example.data.model.GitHubErrorDto
import com.example.data.model.GitTreeEntryDto
import com.example.data.model.PutContentRequest
import com.example.data.model.RepoCommitDetail
import com.example.data.model.RepoCommitSummary
import com.example.data.model.RepoEntry
import com.example.data.model.RepoListResult
import com.example.data.model.RepoSession
import com.example.data.model.RepoSummary
import com.example.data.model.RepoTreeItem
import com.example.data.model.TransferProgressUpdate
import com.example.data.model.UpdateRefRequest
import com.example.data.model.UpdateRepoVisibilityRequest
import com.squareup.moshi.Moshi
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class GitHubRepository(private val safHelper: SafFileHelper) {

  private val moshi: Moshi = Moshi.Builder().build()
  private val errorAdapter = moshi.adapter(GitHubErrorDto::class.java)
  private val deleteTreeAdapter = moshi.adapter(DeleteTreeRequest::class.java).serializeNulls()

  private val api: GitHubApiService by lazy {
    val headerInterceptor = Interceptor { chain ->
      val original = chain.request()
      val builder = original.newBuilder()
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "GitFiles-Android")
      if (original.header("Accept") == null) {
        builder.header("Accept", "application/vnd.github+json")
      }
      chain.proceed(builder.build())
    }

    val client =
      OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
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

  private fun extractApiMessage(e: HttpException): String? {
    val rawBody = e.response()?.errorBody()?.string() ?: return null
    return runCatching { errorAdapter.fromJson(rawBody)?.message }.getOrNull()
  }

  private fun parseHttpError(e: HttpException, apiMessage: String? = extractApiMessage(e)): String {
    val code = e.code()
    val acceptedPerms = e.response()?.headers()?.get("X-Accepted-GitHub-Permissions").orEmpty()
    return when (code) {
      401 ->
        if (!apiMessage.isNullOrBlank()) "Authentication failed (401): $apiMessage"
        else "Authentication failed (401): Invalid or expired GitHub token."
      403 -> {
        val msg = apiMessage.orEmpty()
        val permHint = if (acceptedPerms.isNotBlank()) " [Required: $acceptedPerms]" else ""
        when {
          acceptedPerms.contains("administration", ignoreCase = true) ||
            msg.contains("administration", ignoreCase = true) ||
            msg.contains("delete_repo", ignoreCase = true) ->
            "Forbidden (403): Token needs 'Administration: Read and write' (Fine-grained PAT) or 'repo' / 'delete_repo' scope (Classic PAT) to create, modify, or delete repositories.$permHint"
          acceptedPerms.contains("workflows", ignoreCase = true) ||
            msg.contains("workflow", ignoreCase = true) ->
            "Forbidden (403): Token needs 'Workflows: Read and write' (Fine-grained PAT) or 'workflow' scope (Classic PAT) to upload .github/workflows files.$permHint"
          acceptedPerms.contains("contents", ignoreCase = true) ->
            "Forbidden (403): Token needs 'Contents: Read and write' (and 'Workflows: Read and write' if uploading .github/workflows), with Repository access set to 'All repositories' or this repo selected.$permHint"
          msg.contains("Resource not accessible by personal access token", ignoreCase = true) ->
            "Forbidden (403): Resource not accessible by personal access token.$permHint Check Fine-grained PAT: set Repository access to 'All repositories' (or select this repo) and enable 'Contents: Read and write', 'Workflows: Read and write' (for .github/workflows), and 'Administration: Read and write'."
          msg.isNotEmpty() -> "Forbidden (403): $msg$permHint"
          else -> "Forbidden (403): Check token permissions.$permHint"
        }
      }
      404 -> apiMessage?.let { "Not found (404): $it" } ?: "Repository, branch, or path not found (404)."
      409 -> apiMessage?.let { "Conflict (409): $it" } ?: "Conflict (409): Repository state changed, refresh and retry."
      422 -> apiMessage?.let { "Validation failed (422): $it" } ?: "Invalid request (422)."
      429 -> apiMessage?.let { "Rate limited (429): $it" } ?: "GitHub API rate limit exceeded (429)."
      else -> apiMessage?.let { "GitHub API error ($code): $it" } ?: "GitHub API error ($code)"
    }
  }

  private suspend fun <T> safeApiCall(
    retryTransientAuth: Boolean = true,
    block: suspend () -> T,
  ): T {
    val maxAttempts = 4
    var attempt = 0
    var authRetries = 0
    while (true) {
      try {
        return block()
      } catch (e: HttpException) {
        val code = e.code()
        val apiMessage = extractApiMessage(e)
        val isRateLimit =
          code == 429 ||
            (code == 403 && apiMessage?.contains("rate limit", ignoreCase = true) == true)
        val isTransientServerError = code in setOf(500, 502, 503, 504)
        val isTransientAuthError = retryTransientAuth && code == 401 && authRetries < 2

        if (isRateLimit && attempt < maxAttempts - 1) {
          val retryAfterSec =
            e.response()?.headers()?.get("Retry-After")?.toLongOrNull()?.coerceIn(1L, 60L)
              ?: ((attempt + 1) * 5L)
          delay(retryAfterSec * 1000L)
          attempt++
          continue
        }
        if (isTransientServerError && attempt < maxAttempts - 1) {
          delay(((attempt + 1) * 2L) * 1000L)
          attempt++
          continue
        }
        if (isTransientAuthError) {
          authRetries++
          delay(1500L * authRetries)
          continue
        }
        throw IllegalStateException(parseHttpError(e, apiMessage), e)
      } catch (e: IOException) {
        if (attempt < maxAttempts - 1) {
          delay(((attempt + 1) * 2L) * 1000L)
          attempt++
          continue
        }
        throw e
      }
    }
  }

  suspend fun fetchTokenOwnerLogin(token: String): String? =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) return@withContext null
      val auth = "Bearer $cleanToken"
      val resp = runCatching { api.getAuthenticatedUser(auth) }.getOrNull() ?: return@withContext null
      if (resp.code() == 401) {
        throw IllegalStateException("Authentication failed (401): Invalid or expired GitHub token.")
      }
      if (resp.isSuccessful) resp.body()?.login else null
    }

  suspend fun listAccessibleRepos(token: String): RepoListResult =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")
      val auth = "Bearer $cleanToken"

      val allDtos = mutableListOf<com.example.data.model.GitHubRepoDto>()
      for (page in 1..5) {
        val batch = safeApiCall(retryTransientAuth = false) { api.listUserRepos(auth, page = page) }
        allDtos.addAll(batch)
        if (batch.size < 100) break
      }

      val summaries =
        allDtos
          .distinctBy { it.fullName.lowercase() }
          .map {
            RepoSummary(
              fullName = it.fullName,
              htmlUrl = it.htmlUrl.ifEmpty { "https://github.com/${it.fullName}" },
              defaultBranch = it.defaultBranch?.ifEmpty { "main" } ?: "main",
              isPrivate = it.private,
            )
          }

      val hasPrivate = summaries.any { it.isPrivate }
      val notice =
        if (!hasPrivate) {
          val userResp = runCatching { api.getAuthenticatedUser(auth) }.getOrNull()
          val scopesHeader = userResp?.headers()?.get("X-OAuth-Scopes")
          if (scopesHeader != null) {
            val scopes = scopesHeader.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if ("repo" !in scopes) {
              "Classic token is missing 'repo' scope — Private repositories are hidden."
            } else {
              null
            }
          } else {
            "No private repositories shown for this Fine-grained token. If you have private repos, check GitHub Token settings -> Repository access = 'All repositories' (or select your private repos)."
          }
        } else {
          null
        }

      RepoListResult(repos = summaries, notice = notice)
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
      val repoDefaultBranch = repoDto.defaultBranch?.ifEmpty { "main" } ?: "main"

      val requestedBranch =
        preferredBranch?.trim()?.takeIf { it.isNotEmpty() }
          ?: coords.branchFromUrl?.takeIf { it.isNotEmpty() }

      val activeBranch =
        when {
          isEmptyRepo -> requestedBranch ?: repoDefaultBranch
          requestedBranch != null && branchNames.contains(requestedBranch) -> requestedBranch
          requestedBranch != null ->
            throw IllegalStateException("Branch '$requestedBranch' was not found in ${repoDto.fullName}")
          branchNames.contains(repoDefaultBranch) -> repoDefaultBranch
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
          defaultBranch = repoDefaultBranch,
          branches = branchNames.ifEmpty { listOf(activeBranch) },
          isEmptyRepo = isEmptyRepo,
          isPrivate = repoDto.private,
        )
      val initialPath = coords.pathFromUrl.orEmpty()
      session to initialPath
    }

  private fun parseOwnerRepo(fullName: String): Pair<String, String> {
    val parts = fullName.trim().trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size == 2) return parts[0] to parts[1]
    val coords =
      GitHubUrlParser.parse(fullName)
        ?: throw IllegalArgumentException("Invalid repository name: $fullName")
    return coords.owner to coords.repo
  }

  suspend fun createRepository(
    token: String,
    name: String,
    description: String,
    isPrivate: Boolean,
    autoInit: Boolean,
  ): RepoSummary =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")
      val cleanName = name.trim()
      if (cleanName.isEmpty()) throw IllegalArgumentException("Repository name is required")
      val auth = "Bearer $cleanToken"
      val dto =
        safeApiCall {
          api.createUserRepo(
            auth = auth,
            body =
              CreateRepoRequest(
                name = cleanName,
                description = description.trim().takeIf { it.isNotEmpty() },
                private = isPrivate,
                autoInit = autoInit,
              ),
          )
        }
      RepoSummary(
        fullName = dto.fullName,
        htmlUrl = dto.htmlUrl.ifEmpty { "https://github.com/${dto.fullName}" },
        defaultBranch = dto.defaultBranch?.ifEmpty { "main" } ?: "main",
        isPrivate = dto.private,
      )
    }

  suspend fun updateRepositoryVisibility(
    token: String,
    fullName: String,
    makePrivate: Boolean,
  ): RepoSummary =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")
      val (owner, repo) = parseOwnerRepo(fullName)
      val auth = "Bearer $cleanToken"
      val dto =
        safeApiCall {
          api.updateRepositoryVisibility(
            auth = auth,
            owner = owner,
            repo = repo,
            body = UpdateRepoVisibilityRequest(private = makePrivate),
          )
        }
      RepoSummary(
        fullName = dto.fullName,
        htmlUrl = dto.htmlUrl.ifEmpty { "https://github.com/${dto.fullName}" },
        defaultBranch = dto.defaultBranch?.ifEmpty { "main" } ?: "main",
        isPrivate = dto.private,
      )
    }

  suspend fun deleteRepository(
    token: String,
    fullName: String,
  ) =
    withContext(Dispatchers.IO) {
      val cleanToken = normalizeToken(token)
      if (cleanToken.isEmpty()) throw IllegalArgumentException("GitHub token is required")
      val (owner, repo) = parseOwnerRepo(fullName)
      val auth = "Bearer $cleanToken"
      safeApiCall {
        val resp = api.deleteRepository(auth = auth, owner = owner, repo = repo)
        if (!resp.isSuccessful) {
          val ex = HttpException(resp)
          if (resp.code() == 403) {
            val apiMsg = extractApiMessage(ex)
            if (apiMsg?.contains("rate limit", ignoreCase = true) != true) {
              throw IllegalStateException(
                "Forbidden (403): Deleting a repository requires 'Administration: Read and write' (Fine-grained PAT) or 'delete_repo' scope (Classic PAT).",
                ex,
              )
            }
          }
          throw ex
        }
      }
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

  suspend fun findEntryAtPath(session: RepoSession, fullPath: String): RepoEntry? =
    withContext(Dispatchers.IO) {
      val clean = fullPath.trim('/')
      if (clean.isEmpty()) return@withContext null
      val parent = clean.substringBeforeLast('/', "")
      val targetName = clean.substringAfterLast('/')
      listDirectory(session, parent).firstOrNull { it.name == targetName }
    }

  suspend fun listCommits(
    session: RepoSession,
    pathFilter: String? = null,
    limit: Int = 12,
  ): List<RepoCommitSummary> =
    withContext(Dispatchers.IO) {
      if (session.isEmptyRepo) return@withContext emptyList()
      val auth = authHeader(session.token)
      val cleanPath = pathFilter?.trim('/')?.takeIf { it.isNotEmpty() }
      val dtos =
        safeApiCall {
          api.listCommits(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            sha = session.branch,
            path = cleanPath,
            perPage = limit.coerceIn(1, 50),
          )
        }
      dtos.map { dto ->
        RepoCommitSummary(
          sha = dto.sha,
          shortSha = dto.sha.take(7),
          message = dto.commit.message.lineSequence().firstOrNull().orEmpty(),
          authorName = dto.commit.author?.name.orEmpty(),
          dateIso = dto.commit.author?.date.orEmpty(),
        )
      }
    }

  suspend fun getCommitDetail(
    session: RepoSession,
    ref: String = session.branch,
  ): RepoCommitDetail =
    withContext(Dispatchers.IO) {
      if (session.isEmptyRepo) {
        throw IllegalStateException("Repository has no commits yet.")
      }
      val auth = authHeader(session.token)
      val targetRef = ref.trim().ifEmpty { session.branch }
      val dto =
        safeApiCall {
          api.getCommitDetail(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            encodedRef = encodeGitHubPath(targetRef),
          )
        }
      RepoCommitDetail(
        sha = dto.sha,
        shortSha = dto.sha.take(7),
        message = dto.commit.message,
        authorName = dto.commit.author?.name.orEmpty(),
        dateIso = dto.commit.author?.date.orEmpty(),
        files = dto.files,
      )
    }

  suspend fun copyFile(
    session: RepoSession,
    sourcePath: String,
    destPath: String,
    commitMessage: String,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanSrc = sourcePath.trim('/')
      val cleanDst = destPath.trim('/')
      if (cleanSrc.isEmpty() || cleanDst.isEmpty()) {
        throw IllegalArgumentException("Source and destination paths are required")
      }
      if (cleanSrc == cleanDst) return@withContext session
      val srcEntry = findEntryAtPath(session, cleanSrc)
        ?: throw IllegalStateException("cp: not found: $cleanSrc")
      if (srcEntry.type != EntryType.FILE) {
        throw IllegalArgumentException("cp: '$cleanSrc' is a directory (only files supported)")
      }
      val auth = authHeader(session.token)
      val snapshot = fetchCurrentTree(auth, session)
      commitTreeAndMoveRef(
        auth = auth,
        session = session,
        parentCommitSha = snapshot.headCommitSha,
        baseTreeSha = snapshot.baseTreeSha,
        entries =
          listOf(
            CreateTreeEntryRequest(
              path = cleanDst,
              mode = "100644",
              type = "blob",
              sha = srcEntry.sha,
            )
          ),
        message = commitMessage.ifBlank { "Copy $cleanSrc to $cleanDst" },
      )
      refreshBranches(session)
    }

  suspend fun listRecursiveTree(session: RepoSession): Pair<List<RepoTreeItem>, Boolean> =
    withContext(Dispatchers.IO) {
      if (session.isEmptyRepo) return@withContext emptyList<RepoTreeItem>() to false
      val auth = authHeader(session.token)
      val snapshot = fetchCurrentTree(auth, session)
      val items =
        snapshot.entries.map {
          RepoTreeItem(
            path = it.path,
            type = it.type,
            size = it.size ?: 0L,
          )
        }
      items to snapshot.truncated
    }

  suspend fun createBranch(session: RepoSession, newBranchName: String): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanName = newBranchName.trim().removePrefix("refs/heads/")
      if (cleanName.isEmpty()) throw IllegalArgumentException("Branch name cannot be empty")
      if (session.isEmptyRepo) {
        throw IllegalStateException("Repository has no commits yet. Create a file first before creating a branch.")
      }
      val auth = authHeader(session.token)
      val ref =
        safeApiCall {
          api.getBranchRef(auth, session.owner, session.repo, encodeGitHubPath(session.branch))
        }
      safeApiCall {
        api.createBranchRef(
          auth = auth,
          owner = session.owner,
          repo = session.repo,
          body = CreateRefRequest(ref = "refs/heads/$cleanName", sha = ref.refObject.sha),
        )
      }
      refreshBranches(session).copy(branch = cleanName)
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
    onProgress: (TransferProgressUpdate) -> Unit = {},
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val bytes = safHelper.readBytes(localUri)
      if (bytes.size > MAX_SINGLE_BLOB_BYTES) {
        throw IllegalArgumentException("File exceeds 75 MB upload limit")
      }
      val tracker = TransferMetricsTracker(bytes.size.toLong())
      val fileName = targetPath.substringAfterLast('/')
      onProgress(tracker.snapshot(0, 1, fileName))
      val auth = authHeader(session.token)
      val message = commitMessage.ifBlank { "Replace $fileName" }

      if (!session.isEmptyRepo && bytes.size > LARGE_SINGLE_FILE_THRESHOLD_BYTES) {
        uploadSingleFileViaGitDataApi(
          auth = auth,
          session = session,
          fullPath = targetPath,
          bytes = bytes,
          commitMessage = message,
          tracker = tracker,
          onProgress = onProgress,
        )
        onProgress(tracker.snapshot(1, 1, fileName))
        return@withContext session
      }

      val base64 = bytes.toByteString().base64()
      safeApiCall {
        api.putFileContent(
          auth = auth,
          owner = session.owner,
          repo = session.repo,
          encodedPath = encodeGitHubPath(targetPath),
          body =
            PutContentRequest(
              message = message,
              content = base64,
              branch = session.branch,
              sha = existingSha,
            ),
        )
      }
      tracker.addProcessedBytes(bytes.size.toLong())
      tracker.recordNetworkBytes(bytes.size.toLong())
      onProgress(tracker.snapshot(1, 1, fileName))
      session
    }

  suspend fun uploadFiles(
    session: RepoSession,
    targetDirPath: String,
    pickedFiles: List<LocalPickedFile>,
    existingEntriesInDir: List<RepoEntry>,
    commitMessage: String,
    commitBatchSize: Int = UPLOAD_COMMIT_BATCH_SIZE,
    onProgress: (TransferProgressUpdate) -> Unit,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      if (pickedFiles.isEmpty()) return@withContext session
      val auth = authHeader(session.token)
      val existingByName = existingEntriesInDir.filter { it.type == EntryType.FILE }.associateBy { it.name }
      val totalBytes = pickedFiles.sumOf { it.size.coerceAtLeast(0L) }
      val tracker = TransferMetricsTracker(totalBytes)

      if (pickedFiles.size == 1) {
        val file = pickedFiles.first()
        val bytes = safHelper.readBytes(file.uri)
        if (bytes.size > MAX_SINGLE_BLOB_BYTES) {
          throw IllegalArgumentException("'${file.name}' exceeds 75 MB upload limit")
        }
        val fileTracker = TransferMetricsTracker(bytes.size.toLong())
        onProgress(fileTracker.snapshot(0, 1, file.name))
        val fullPath = joinPath(targetDirPath, file.name)
        val message = commitMessage.ifBlank { "Upload ${file.name}" }

        if (!session.isEmptyRepo && bytes.size > LARGE_SINGLE_FILE_THRESHOLD_BYTES) {
          uploadSingleFileViaGitDataApi(
            auth = auth,
            session = session,
            fullPath = fullPath,
            bytes = bytes,
            commitMessage = message,
            tracker = fileTracker,
            onProgress = onProgress,
          )
          onProgress(fileTracker.snapshot(1, 1, file.name))
          return@withContext session
        }

        val existingSha = existingByName[file.name]?.sha
        safeApiCall {
          api.putFileContent(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            encodedPath = encodeGitHubPath(fullPath),
            body =
              PutContentRequest(
                message = message,
                content = bytes.toByteString().base64(),
                branch = session.branch,
                sha = existingSha,
              ),
          )
        }
        fileTracker.addProcessedBytes(bytes.size.toLong())
        fileTracker.recordNetworkBytes(bytes.size.toLong())
        onProgress(fileTracker.snapshot(1, 1, file.name))
        return@withContext if (session.isEmptyRepo) refreshBranches(session) else session
      }

      var activeSession = session
      var remainingFiles = pickedFiles
      if (activeSession.isEmptyRepo) {
        val firstFile = pickedFiles.first()
        onProgress(tracker.snapshot(0, pickedFiles.size, firstFile.name))
        val firstBytes = safHelper.readBytes(firstFile.uri)
        safeApiCall {
          api.putFileContent(
            auth = auth,
            owner = activeSession.owner,
            repo = activeSession.repo,
            encodedPath = encodeGitHubPath(joinPath(targetDirPath, firstFile.name)),
            body =
              PutContentRequest(
                message = commitMessage.ifBlank { "Initial commit" },
                content = firstBytes.toByteString().base64(),
                branch = activeSession.branch,
              ),
          )
        }
        tracker.addProcessedBytes(firstBytes.size.toLong())
        tracker.recordNetworkBytes(firstBytes.size.toLong())
        activeSession = refreshBranches(activeSession)
        remainingFiles = pickedFiles.drop(1)
        onProgress(tracker.snapshot(1, pickedFiles.size, firstFile.name))
        if (remainingFiles.isEmpty()) {
          return@withContext activeSession
        }
      } else {
        onProgress(tracker.snapshot(0, pickedFiles.size, pickedFiles.first().name))
      }

      val snapshot = fetchCurrentTree(auth, activeSession)
      var currentHeadCommitSha = snapshot.headCommitSha
      var currentBaseTreeSha = snapshot.baseTreeSha
      val currentPathToSha =
        snapshot.entries
          .asSequence()
          .filter { it.type == "blob" }
          .associate { it.path to it.sha }
          .toMutableMap()
      val currentBlobShas = currentPathToSha.values.toMutableSet()

      val safeBatchSize = commitBatchSize.coerceIn(1, 200)
      val baseOffset = pickedFiles.size - remainingFiles.size
      val batches = remainingFiles.chunked(safeBatchSize)
      val baseMessage = commitMessage.ifBlank { "Upload ${pickedFiles.size} files" }

      batches.forEachIndexed { batchIndex, batch ->
        val offset = baseOffset + batchIndex * safeBatchSize
        val builtItems =
          buildTreeEntriesForFiles(
            auth = auth,
            session = activeSession,
            files = batch.map { joinPath(targetDirPath, it.name) to it.uri },
            existingPathToSha = currentPathToSha.toMap(),
            existingRepoBlobShas = currentBlobShas.toSet(),
            totalCount = pickedFiles.size,
            progressOffset = offset,
            tracker = tracker,
            onProgress = onProgress,
          )

        val changedEntries =
          builtItems.filter { !it.alreadyOnBranchAtSamePath }.map { it.treeEntry }
        if (changedEntries.isNotEmpty()) {
          val batchMessage =
            if (batches.size == 1) baseMessage else "$baseMessage (${batchIndex + 1}/${batches.size})"
          val statusLabel =
            if (batches.size == 1) "Committing ${pickedFiles.size} files…"
            else "Committing batch ${batchIndex + 1}/${batches.size}…"
          onProgress(tracker.snapshot(offset + batch.size, pickedFiles.size, statusLabel))
          val (newCommitSha, newTreeSha) =
            commitTreeAndMoveRef(
              auth = auth,
              session = activeSession,
              parentCommitSha = currentHeadCommitSha,
              baseTreeSha = currentBaseTreeSha,
              entries = changedEntries,
              message = batchMessage,
            )
          currentHeadCommitSha = newCommitSha
          currentBaseTreeSha = newTreeSha
          for (item in builtItems) {
            currentPathToSha[item.treeEntry.path] = item.gitSha
            currentBlobShas.add(item.gitSha)
          }
        }
      }
      activeSession
    }

  suspend fun uploadOrReplaceFolder(
    session: RepoSession,
    targetFolderPath: String,
    replaceExistingFolder: Boolean,
    localFiles: List<LocalFolderFile>,
    commitMessage: String,
    commitBatchSize: Int = UPLOAD_COMMIT_BATCH_SIZE,
    onProgress: (TransferProgressUpdate) -> Unit,
  ): RepoSession =
    withContext(Dispatchers.IO) {
      val cleanTarget = targetFolderPath.trim('/')
      if (localFiles.isEmpty()) {
        throw IllegalArgumentException("Selected folder contains no files")
      }

      val auth = authHeader(session.token)
      val totalBytes = localFiles.sumOf { it.size.coerceAtLeast(0L) }
      val tracker = TransferMetricsTracker(totalBytes)
      var activeSession = session
      var remainingFiles = localFiles

      if (activeSession.isEmptyRepo) {
        val firstFile = localFiles.first()
        onProgress(tracker.snapshot(0, localFiles.size, firstFile.relativePath))
        val firstBytes = safHelper.readBytes(firstFile.uri)
        safeApiCall {
          api.putFileContent(
            auth = auth,
            owner = activeSession.owner,
            repo = activeSession.repo,
            encodedPath = encodeGitHubPath(joinPath(cleanTarget, firstFile.relativePath)),
            body =
              PutContentRequest(
                message = commitMessage.ifBlank { "Initial commit" },
                content = firstBytes.toByteString().base64(),
                branch = activeSession.branch,
              ),
          )
        }
        tracker.addProcessedBytes(firstBytes.size.toLong())
        tracker.recordNetworkBytes(firstBytes.size.toLong())
        activeSession = refreshBranches(activeSession)
        remainingFiles = localFiles.drop(1)
        onProgress(tracker.snapshot(1, localFiles.size, firstFile.relativePath))
        if (remainingFiles.isEmpty()) {
          return@withContext activeSession
        }
      } else {
        onProgress(tracker.snapshot(0, localFiles.size, localFiles.first().relativePath))
      }

      val snapshot = fetchCurrentTree(auth, activeSession)
      if (replaceExistingFolder && snapshot.truncated) {
        throw IllegalStateException("Repository tree is too large for full recursive replacement")
      }

      val allTargetPaths = localFiles.map { joinPath(cleanTarget, it.relativePath) }.toSet()
      var currentHeadCommitSha = snapshot.headCommitSha
      var currentBaseTreeSha = snapshot.baseTreeSha
      val currentPathToSha =
        snapshot.entries
          .asSequence()
          .filter { it.type == "blob" }
          .associate { it.path to it.sha }
          .toMutableMap()
      val currentBlobShas = currentPathToSha.values.toMutableSet()

      val safeBatchSize = commitBatchSize.coerceIn(1, 200)
      val baseOffset = localFiles.size - remainingFiles.size
      val batches = remainingFiles.chunked(safeBatchSize)
      val baseMessage =
        commitMessage.ifBlank {
          if (replaceExistingFolder) "Replace folder ${cleanTarget.ifEmpty { "root" }}"
          else "Upload folder ${cleanTarget.ifEmpty { "root" }}"
        }

      batches.forEachIndexed { batchIndex, batch ->
        val offset = baseOffset + batchIndex * safeBatchSize
        val builtItems =
          buildTreeEntriesForFiles(
            auth = auth,
            session = activeSession,
            files = batch.map { joinPath(cleanTarget, it.relativePath) to it.uri },
            existingPathToSha = currentPathToSha.toMap(),
            existingRepoBlobShas = currentBlobShas.toSet(),
            totalCount = localFiles.size,
            progressOffset = offset,
            tracker = tracker,
            onProgress = onProgress,
          )

        val changedEntries =
          builtItems.filter { !it.alreadyOnBranchAtSamePath }.map { it.treeEntry }
        val isFinalBatch = batchIndex == batches.lastIndex
        val batchMessage =
          if (batches.size == 1) baseMessage else "$baseMessage (${batchIndex + 1}/${batches.size})"

        if (!isFinalBatch) {
          if (changedEntries.isNotEmpty()) {
            onProgress(
              tracker.snapshot(
                offset + batch.size,
                localFiles.size,
                "Committing batch ${batchIndex + 1}/${batches.size}…",
              )
            )
            val (newCommitSha, newTreeSha) =
              commitTreeAndMoveRef(
                auth = auth,
                session = activeSession,
                parentCommitSha = currentHeadCommitSha,
                baseTreeSha = currentBaseTreeSha,
                entries = changedEntries,
                message = batchMessage,
              )
            currentHeadCommitSha = newCommitSha
            currentBaseTreeSha = newTreeSha
            for (item in builtItems) {
              currentPathToSha[item.treeEntry.path] = item.gitSha
              currentBlobShas.add(item.gitSha)
            }
          }
        } else {
          val prefix = if (cleanTarget.isEmpty()) "" else "$cleanTarget/"
          val hasStaleFiles =
            replaceExistingFolder &&
              currentPathToSha.keys.any { path ->
                val isInside =
                  if (cleanTarget.isEmpty()) true
                  else (path == cleanTarget || path.startsWith(prefix))
                isInside && path !in allTargetPaths
              }

          if (hasStaleFiles) {
            onProgress(
              tracker.snapshot(
                localFiles.size,
                localFiles.size,
                if (batches.size == 1) "Committing ${localFiles.size} files…"
                else "Finalizing folder (${batchIndex + 1}/${batches.size})…",
              )
            )
            val latestSnapshot =
              if (batches.size > 1) fetchCurrentTree(auth, activeSession) else snapshot
            val finalChangedPaths = changedEntries.map { it.path }.toSet()
            val kept =
              latestSnapshot.entries
                .filter { entry ->
                  if (entry.type == "tree") return@filter false
                  if (entry.path in finalChangedPaths) return@filter false
                  if (cleanTarget.isEmpty()) {
                    entry.path in allTargetPaths
                  } else {
                    val isInside = entry.path == cleanTarget || entry.path.startsWith(prefix)
                    !isInside || entry.path in allTargetPaths
                  }
                }
                .map { CreateTreeEntryRequest(it.path, it.mode, it.type, it.sha) }

            commitTreeAndMoveRef(
              auth = auth,
              session = activeSession,
              parentCommitSha = latestSnapshot.headCommitSha,
              baseTreeSha = null,
              entries = kept + changedEntries,
              message = batchMessage,
            )
          } else if (changedEntries.isNotEmpty()) {
            onProgress(
              tracker.snapshot(
                localFiles.size,
                localFiles.size,
                if (batches.size == 1) "Committing ${localFiles.size} files…"
                else "Committing batch ${batchIndex + 1}/${batches.size}…",
              )
            )
            commitTreeAndMoveRef(
              auth = auth,
              session = activeSession,
              parentCommitSha = currentHeadCommitSha,
              baseTreeSha = currentBaseTreeSha,
              entries = changedEntries,
              message = batchMessage,
            )
          }
        }
      }
      activeSession
    }

  private data class BuiltTreeItem(
    val treeEntry: CreateTreeEntryRequest,
    val gitSha: String,
    val alreadyOnBranchAtSamePath: Boolean,
  )

  private suspend fun buildTreeEntriesForFiles(
    auth: String,
    session: RepoSession,
    files: List<Pair<String, Uri>>,
    existingPathToSha: Map<String, String>,
    existingRepoBlobShas: Set<String>,
    totalCount: Int,
    progressOffset: Int,
    tracker: TransferMetricsTracker,
    onProgress: (TransferProgressUpdate) -> Unit,
  ): List<BuiltTreeItem> = coroutineScope {
    val fileReadSemaphore = Semaphore(6)
    val blobUploadSemaphore = Semaphore(4)
    val completedCount = AtomicInteger(progressOffset)
    val inlineBytesUsed = AtomicLong(0L)
    val maxInlineFileBytes = 64_000
    val maxInlineTotalBytes = 2_500_000L
    val inFlightBlobs = ConcurrentHashMap<String, CompletableDeferred<String>>()
    val lastStreamEmitAtMs = AtomicLong(0L)

    files
      .map { (remotePath, uri) ->
        async(Dispatchers.IO) {
          var fileSizeBytes = 0L
          var streamedOwnBlob = false
          val item =
            fileReadSemaphore.withPermit {
              val bytes = safHelper.readBytes(uri)
              fileSizeBytes = bytes.size.toLong()
              if (bytes.size > MAX_SINGLE_BLOB_BYTES) {
                throw IllegalArgumentException("'$remotePath' exceeds 75 MB upload limit")
              }

              val gitSha = computeGitBlobSha1(bytes)
              if (existingPathToSha[remotePath] == gitSha) {
                return@withPermit BuiltTreeItem(
                  treeEntry =
                    CreateTreeEntryRequest(
                      path = remotePath,
                      mode = "100644",
                      type = "blob",
                      sha = gitSha,
                    ),
                  gitSha = gitSha,
                  alreadyOnBranchAtSamePath = true,
                )
              }

              if (gitSha in existingRepoBlobShas) {
                return@withPermit BuiltTreeItem(
                  treeEntry =
                    CreateTreeEntryRequest(
                      path = remotePath,
                      mode = "100644",
                      type = "blob",
                      sha = gitSha,
                    ),
                  gitSha = gitSha,
                  alreadyOnBranchAtSamePath = false,
                )
              }

              val displayName = remotePath.substringAfterLast('/')
              val inlineText =
                if (
                  bytes.size <= maxInlineFileBytes &&
                    !isKnownBinaryExtension(displayName)
                ) {
                  val decoded = tryDecodeUtf8(bytes)
                  if (decoded != null) {
                    val prev = inlineBytesUsed.get()
                    if (prev + bytes.size <= maxInlineTotalBytes &&
                      inlineBytesUsed.compareAndSet(prev, prev + bytes.size)
                    ) {
                      decoded
                    } else {
                      null
                    }
                  } else {
                    null
                  }
                } else {
                  null
                }

              if (inlineText != null) {
                tracker.recordNetworkBytes(fileSizeBytes)
                return@withPermit BuiltTreeItem(
                  treeEntry =
                    CreateTreeEntryRequest(
                      path = remotePath,
                      mode = "100644",
                      type = "blob",
                      content = inlineText,
                    ),
                  gitSha = gitSha,
                  alreadyOnBranchAtSamePath = false,
                )
              }

              val newDeferred = CompletableDeferred<String>()
              val existingDeferred = inFlightBlobs.putIfAbsent(gitSha, newDeferred)
              val uploadedSha =
                if (existingDeferred != null) {
                  existingDeferred.await()
                } else {
                  try {
                    streamedOwnBlob = true
                    val sha =
                      blobUploadSemaphore.withPermit {
                        createBlob(auth, session, bytes) { delta ->
                          tracker.addProcessedBytes(delta)
                          if (delta > 0L) tracker.recordNetworkBytes(delta)
                          val now = System.currentTimeMillis()
                          val prevEmit = lastStreamEmitAtMs.get()
                          if (now - prevEmit >= 300L && lastStreamEmitAtMs.compareAndSet(prevEmit, now)) {
                            onProgress(tracker.snapshot(completedCount.get(), totalCount, remotePath))
                          }
                        }
                      }
                    newDeferred.complete(sha)
                    sha
                  } catch (t: Throwable) {
                    inFlightBlobs.remove(gitSha, newDeferred)
                    newDeferred.completeExceptionally(t)
                    throw t
                  }
                }

              BuiltTreeItem(
                treeEntry =
                  CreateTreeEntryRequest(
                    path = remotePath,
                    mode = "100644",
                    type = "blob",
                    sha = uploadedSha,
                  ),
                gitSha = gitSha,
                alreadyOnBranchAtSamePath = false,
              )
            }

          if (!streamedOwnBlob) {
            tracker.addProcessedBytes(fileSizeBytes)
          }
          val done = completedCount.incrementAndGet()
          onProgress(tracker.snapshot(done, totalCount, remotePath))
          item
        }
      }
      .awaitAll()
  }

  private fun computeGitBlobSha1(bytes: ByteArray): String {
    val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(header)
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
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
        val message = commitMessage.ifBlank { "Delete ${entry.name}" }
        deleteSingleFileWithRefresh(
          auth = auth,
          session = session,
          path = entry.path,
          knownSha = entry.sha,
          message = message,
        )
        return@withContext refreshBranches(session)
      }

      val cleanFolder = entry.path.trim('/')
      if (cleanFolder.isEmpty()) {
        throw IllegalArgumentException("Cannot delete repository root")
      }
      val message = commitMessage.ifBlank { "Delete folder ${entry.name}" }
      val prefix = "$cleanFolder/"
      val snapshot = fetchCurrentTree(auth, session)

      val targetItems: List<DeleteTreeEntryRequest>
      val hasOtherFiles: Boolean

      if (!snapshot.truncated) {
        val nonTreeEntries = snapshot.entries.filter { it.type != "tree" }
        val matched =
          nonTreeEntries.filter { it.path == cleanFolder || it.path.startsWith(prefix) }
        targetItems =
          matched.map {
            DeleteTreeEntryRequest(
              path = it.path,
              mode = it.mode,
              type = it.type,
              sha = null,
            )
          }
        hasOtherFiles =
          nonTreeEntries.any { it.path != cleanFolder && !it.path.startsWith(prefix) }
      } else {
        val collectedFiles = collectFilesUnderFolderViaContentsApi(session, cleanFolder)
        targetItems =
          collectedFiles.map { fileEntry ->
            DeleteTreeEntryRequest(
              path = fileEntry.path,
              mode = "100644",
              type = "blob",
              sha = null,
            )
          }
        hasOtherFiles = hasFilesOutsideFolder(session, cleanFolder)
      }

      if (targetItems.isEmpty()) {
        return@withContext refreshBranches(session)
      }

      if (!hasOtherFiles) {
        // Removing all files from branch: delete sequentially via Contents API so branch clears cleanly
        for (item in targetItems) {
          val sha =
            snapshot.entries.firstOrNull { it.path == item.path }?.sha.orEmpty()
          deleteSingleFileWithRefresh(
            auth = auth,
            session = session,
            path = item.path,
            knownSha = sha,
            message = message,
          )
        }
        return@withContext refreshBranches(session)
      }

      // Delete folder files atomically using base_tree + sha=null so unrelated files
      // (including .github/workflows) are never re-submitted or touched.
      var currentParentCommitSha = snapshot.headCommitSha
      var currentBaseTreeSha = snapshot.baseTreeSha
      val chunks = targetItems.chunked(DELETE_TREE_BATCH_SIZE)
      chunks.forEachIndexed { index, chunk ->
        val chunkMessage =
          if (chunks.size == 1) message else "$message (${index + 1}/${chunks.size})"
        val json = deleteTreeAdapter.toJson(DeleteTreeRequest(baseTree = currentBaseTreeSha, tree = chunk))
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val createdTree =
          safeApiCall {
            api.createTreeRaw(auth, session.owner, session.repo, body)
          }
        val createdCommit =
          safeApiCall {
            api.createCommit(
              auth,
              session.owner,
              session.repo,
              CreateCommitRequest(
                message = chunkMessage,
                tree = createdTree.sha,
                parents = listOf(currentParentCommitSha),
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
        currentParentCommitSha = createdCommit.sha
        currentBaseTreeSha = createdTree.sha
      }
      refreshBranches(session)
    }

  private suspend fun deleteSingleFileWithRefresh(
    auth: String,
    session: RepoSession,
    path: String,
    knownSha: String,
    message: String,
  ) {
    val cleanPath = path.trim('/')
    val encodedPath = encodeGitHubPath(cleanPath)
    var activeSha = knownSha.trim()
    if (activeSha.isEmpty()) {
      activeSha =
        safeApiCall {
          api.getFileContent(auth, session.owner, session.repo, encodedPath, session.branch).sha
        }
    }

    try {
      safeApiCall {
        val resp =
          api.deleteFileContent(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            encodedPath = encodedPath,
            body =
              DeleteContentRequest(
                message = message,
                sha = activeSha,
                branch = session.branch,
              ),
          )
        resp.body()?.use { }
        if (!resp.isSuccessful) {
          throw HttpException(resp)
        }
      }
    } catch (e: IllegalStateException) {
      val httpCode = (e.cause as? HttpException)?.code()
      if (httpCode != 409 && httpCode != 422) {
        throw e
      }
      val latestSha =
        safeApiCall {
          api.getFileContent(auth, session.owner, session.repo, encodedPath, session.branch).sha
        }
      if (latestSha == activeSha || latestSha.isBlank()) {
        throw e
      }
      safeApiCall {
        val retryResp =
          api.deleteFileContent(
            auth = auth,
            owner = session.owner,
            repo = session.repo,
            encodedPath = encodedPath,
            body =
              DeleteContentRequest(
                message = message,
                sha = latestSha,
                branch = session.branch,
              ),
          )
        retryResp.body()?.use { }
        if (!retryResp.isSuccessful) {
          throw HttpException(retryResp)
        }
      }
    }
  }

  private suspend fun collectFilesUnderFolderViaContentsApi(
    session: RepoSession,
    folderPath: String,
  ): List<RepoEntry> {
    val result = mutableListOf<RepoEntry>()
    val queue = ArrayDeque<String>()
    queue.addLast(folderPath.trim('/'))
    while (queue.isNotEmpty()) {
      val dir = queue.removeFirst()
      val entries = listDirectory(session, dir)
      for (item in entries) {
        if (item.type == EntryType.DIR) {
          queue.addLast(item.path)
        } else {
          result += item
        }
      }
    }
    return result
  }

  private suspend fun hasFilesOutsideFolder(
    session: RepoSession,
    folderPath: String,
  ): Boolean {
    val segments = folderPath.trim('/').split('/').filter { it.isNotEmpty() }
    var currentParent = ""
    for (segment in segments) {
      val siblings = listDirectory(session, currentParent)
      if (siblings.any { it.name != segment }) {
        return true
      }
      currentParent = if (currentParent.isEmpty()) segment else "$currentParent/$segment"
    }
    return false
  }

  suspend fun downloadFile(
    session: RepoSession,
    entry: RepoEntry,
    destUri: Uri,
    onProgress: (TransferProgressUpdate) -> Unit = {},
  ) =
    withContext(Dispatchers.IO) {
      val auth = authHeader(session.token)
      val tracker = TransferMetricsTracker(entry.size.coerceAtLeast(0L))
      onProgress(tracker.snapshot(0, 1, entry.name))
      safeApiCall {
        val resp = api.downloadRawBlob(auth, owner = session.owner, repo = session.repo, sha = entry.sha)
        if (!resp.isSuccessful) throw HttpException(resp)
        val body = resp.body() ?: throw IllegalStateException("Empty download response")
        var copiedThisAttempt = 0L
        var lastEmitAtMs = 0L
        try {
          body.byteStream().use { input ->
            safHelper.copyStreamToUri(input, destUri) { chunk ->
              copiedThisAttempt += chunk
              tracker.addProcessedBytes(chunk)
              tracker.recordNetworkBytes(chunk)
              val now = System.currentTimeMillis()
              if (now - lastEmitAtMs >= 300L) {
                lastEmitAtMs = now
                onProgress(tracker.snapshot(0, 1, entry.name))
              }
            }
          }
        } catch (t: Throwable) {
          if (copiedThisAttempt > 0L) tracker.addProcessedBytes(-copiedThisAttempt)
          throw t
        }
      }
      onProgress(tracker.snapshot(1, 1, entry.name))
    }

  suspend fun downloadFolder(
    session: RepoSession,
    folderEntry: RepoEntry,
    destTreeUri: Uri,
    onProgress: (TransferProgressUpdate) -> Unit,
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

      val totalBytes = blobs.sumOf { (it.size ?: 0L).coerceAtLeast(0L) }
      val tracker = TransferMetricsTracker(totalBytes)
      var lastEmitAtMs = 0L

      blobs.forEachIndexed { index, blob ->
        val relPath = joinPath(folderEntry.name, blob.path.removePrefix(prefix))
        onProgress(tracker.snapshot(index, blobs.size, relPath))
        safeApiCall {
          val resp = api.downloadRawBlob(auth, owner = session.owner, repo = session.repo, sha = blob.sha)
          if (!resp.isSuccessful) throw HttpException(resp)
          val body = resp.body() ?: throw IllegalStateException("Empty blob response")
          var copiedThisAttempt = 0L
          try {
            body.byteStream().use { input ->
              safHelper.writeStreamToTreeRelativePath(destTreeUri, relPath, input) { chunk ->
                copiedThisAttempt += chunk
                tracker.addProcessedBytes(chunk)
                tracker.recordNetworkBytes(chunk)
                val now = System.currentTimeMillis()
                if (now - lastEmitAtMs >= 300L) {
                  lastEmitAtMs = now
                  onProgress(tracker.snapshot(index, blobs.size, relPath))
                }
              }
            }
          } catch (t: Throwable) {
            if (copiedThisAttempt > 0L) tracker.addProcessedBytes(-copiedThisAttempt)
            throw t
          }
        }
        onProgress(tracker.snapshot(index + 1, blobs.size, relPath))
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

  private suspend fun uploadSingleFileViaGitDataApi(
    auth: String,
    session: RepoSession,
    fullPath: String,
    bytes: ByteArray,
    commitMessage: String,
    tracker: TransferMetricsTracker,
    onProgress: (TransferProgressUpdate) -> Unit,
  ) {
    val displayName = fullPath.substringAfterLast('/')
    val ref =
      safeApiCall {
        api.getBranchRef(auth, session.owner, session.repo, encodeGitHubPath(session.branch))
      }
    val headSha = ref.refObject.sha
    val commit = safeApiCall { api.getCommit(auth, session.owner, session.repo, headSha) }

    var lastEmitAtMs = 0L
    val blobSha =
      createBlob(auth, session, bytes) { delta ->
        tracker.addProcessedBytes(delta)
        if (delta > 0L) tracker.recordNetworkBytes(delta)
        val now = System.currentTimeMillis()
        if (now - lastEmitAtMs >= 250L) {
          lastEmitAtMs = now
          onProgress(tracker.snapshot(0, 1, displayName))
        }
      }

    onProgress(tracker.snapshot(1, 1, "Committing $displayName…"))
    commitTreeAndMoveRef(
      auth = auth,
      session = session,
      parentCommitSha = headSha,
      baseTreeSha = commit.tree.sha,
      entries =
        listOf(
          CreateTreeEntryRequest(
            path = fullPath,
            mode = "100644",
            type = "blob",
            sha = blobSha,
          )
        ),
      message = commitMessage,
    )
  }

  private suspend fun createBlob(
    auth: String,
    session: RepoSession,
    bytes: ByteArray,
    onBytesDelta: (Long) -> Unit = {},
  ): String {
    val body = StreamingBlobRequestBody(bytes, onBytesDelta)
    return safeApiCall {
      try {
        api.createBlob(auth, session.owner, session.repo, body).sha
      } catch (t: Throwable) {
        body.rollbackReportedBytes()
        throw t
      }
    }
  }

  private suspend fun commitTreeAndMoveRef(
    auth: String,
    session: RepoSession,
    parentCommitSha: String,
    baseTreeSha: String?,
    entries: List<CreateTreeEntryRequest>,
    message: String,
  ): Pair<String, String> {
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
    return createdCommit.sha to createdTree.sha
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

  private class TransferMetricsTracker(val totalBytes: Long) {
    private val processedBytes = AtomicLong(0L)
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var windowBytes = 0L

    fun addProcessedBytes(bytes: Long) {
      if (bytes != 0L) {
        processedBytes.updateAndGet { current -> (current + bytes).coerceAtLeast(0L) }
      }
    }

    fun recordNetworkBytes(bytes: Long) {
      if (bytes <= 0L) return
      val now = System.currentTimeMillis()
      synchronized(samples) {
        samples.addLast(now to bytes)
        windowBytes += bytes
        pruneLocked(now)
      }
    }

    private fun pruneLocked(now: Long) {
      val cutoff = now - 5_000L
      while (samples.size > 1 && samples.first().first < cutoff) {
        windowBytes -= samples.removeFirst().second
      }
      if (samples.isEmpty()) windowBytes = 0L
    }

    fun snapshot(completed: Int, total: Int, currentName: String): TransferProgressUpdate {
      val now = System.currentTimeMillis()
      val speed =
        synchronized(samples) {
          pruneLocked(now)
          if (windowBytes <= 0L || samples.isEmpty() || now - samples.last().first > 8_000L) {
            0L
          } else {
            val spanMs = (now - samples.first().first).coerceAtLeast(350L)
            (windowBytes * 1000L) / spanMs
          }
        }
      val effectiveTotal = totalBytes.coerceAtLeast(processedBytes.get())
      val transferred = processedBytes.get().coerceAtMost(effectiveTotal)
      val eta =
        if (speed > 0L && effectiveTotal > transferred) {
          ((effectiveTotal - transferred) / speed).coerceAtLeast(1L)
        } else {
          null
        }
      return TransferProgressUpdate(
        completed = completed,
        total = total,
        currentName = currentName,
        transferredBytes = transferred,
        totalBytes = effectiveTotal,
        bytesPerSecond = speed,
        etaSeconds = eta,
      )
    }
  }

  private companion object {
    const val UPLOAD_COMMIT_BATCH_SIZE = 50
    const val DELETE_TREE_BATCH_SIZE = 500
    const val LARGE_SINGLE_FILE_THRESHOLD_BYTES = 1_000_000
    const val MAX_SINGLE_BLOB_BYTES = 75_000_000
  }
}
