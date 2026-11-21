package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

data class RepoCoordinates(
  val owner: String,
  val repo: String,
  val branchFromUrl: String? = null,
  val pathFromUrl: String? = null,
)

data class RepoSession(
  val token: String,
  val rawRepoInput: String,
  val owner: String,
  val repo: String,
  val fullName: String,
  val branch: String,
  val defaultBranch: String,
  val branches: List<String>,
  val isEmptyRepo: Boolean,
  val isPrivate: Boolean = false,
)

enum class EntryType {
  DIR,
  FILE,
}

data class RepoEntry(
  val name: String,
  val path: String,
  val sha: String,
  val size: Long,
  val type: EntryType,
  val downloadUrl: String? = null,
)

data class FileDocument(
  val name: String,
  val path: String,
  val sha: String,
  val size: Long,
  val textContent: String?,
  val isBinary: Boolean,
  val isTooLargeForEditor: Boolean,
)

data class TransferProgress(
  val title: String,
  val currentItem: String,
  val completed: Int,
  val total: Int,
  val transferredBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val bytesPerSecond: Long = 0L,
  val elapsedMs: Long = 0L,
  val etaSeconds: Long? = null,
)

data class TransferProgressUpdate(
  val completed: Int,
  val total: Int,
  val currentName: String,
  val transferredBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val bytesPerSecond: Long = 0L,
  val etaSeconds: Long? = null,
)

// --- GitHub REST API DTOs ---

@JsonClass(generateAdapter = true)
data class GitHubUserDto(
  val login: String,
)

@JsonClass(generateAdapter = true)
data class GitHubRepoDto(
  @Json(name = "full_name") val fullName: String,
  @Json(name = "default_branch") val defaultBranch: String,
  @Json(name = "html_url") val htmlUrl: String = "",
  val private: Boolean = false,
  val size: Long = 0L,
)

data class RepoSummary(
  val fullName: String,
  val htmlUrl: String,
  val defaultBranch: String,
  val isPrivate: Boolean,
)

@JsonClass(generateAdapter = true)
data class GitHubBranchDto(
  val name: String,
  val commit: BranchCommitRefDto,
)

@JsonClass(generateAdapter = true)
data class BranchCommitRefDto(
  val sha: String,
)

@JsonClass(generateAdapter = true)
data class GitHubContentDto(
  val name: String,
  val path: String,
  val sha: String,
  val size: Long = 0L,
  val type: String,
  val content: String? = null,
  val encoding: String? = null,
  @Json(name = "download_url") val downloadUrl: String? = null,
)

@JsonClass(generateAdapter = true)
data class PutContentRequest(
  val message: String,
  val content: String,
  val branch: String? = null,
  val sha: String? = null,
)

@JsonClass(generateAdapter = true)
data class PutContentResponse(
  val content: GitHubContentDto? = null,
)

@JsonClass(generateAdapter = true)
data class DeleteContentRequest(
  val message: String,
  val sha: String,
  val branch: String? = null,
)

@JsonClass(generateAdapter = true)
data class GitRefDto(
  val ref: String,
  @Json(name = "object") val refObject: GitObjectRefDto,
)

@JsonClass(generateAdapter = true)
data class GitObjectRefDto(
  val sha: String,
  val type: String,
)

@JsonClass(generateAdapter = true)
data class GitCommitDto(
  val sha: String,
  val tree: GitTreeRefDto,
)

@JsonClass(generateAdapter = true)
data class GitTreeRefDto(
  val sha: String,
)

@JsonClass(generateAdapter = true)
data class GitTreeResponseDto(
  val sha: String,
  val truncated: Boolean = false,
  val tree: List<GitTreeEntryDto>,
)

@JsonClass(generateAdapter = true)
data class GitTreeEntryDto(
  val path: String,
  val mode: String,
  val type: String,
  val sha: String,
  val size: Long? = null,
)

@JsonClass(generateAdapter = true)
data class CreateBlobRequest(
  val content: String,
  val encoding: String = "base64",
)

@JsonClass(generateAdapter = true)
data class CreateBlobResponse(
  val sha: String,
)

@JsonClass(generateAdapter = true)
data class CreateTreeRequest(
  @Json(name = "base_tree") val baseTree: String? = null,
  val tree: List<CreateTreeEntryRequest>,
)

@JsonClass(generateAdapter = true)
data class CreateTreeEntryRequest(
  val path: String,
  val mode: String,
  val type: String,
  val sha: String? = null,
  val content: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateTreeResponse(
  val sha: String,
)

@JsonClass(generateAdapter = true)
data class CreateCommitRequest(
  val message: String,
  val tree: String,
  val parents: List<String>,
)

@JsonClass(generateAdapter = true)
data class CreateCommitResponse(
  val sha: String,
)

@JsonClass(generateAdapter = true)
data class UpdateRefRequest(
  val sha: String,
  val force: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class GitHubErrorDto(
  val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateRepoRequest(
  val name: String,
  val description: String? = null,
  val private: Boolean = false,
  @Json(name = "auto_init") val autoInit: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class UpdateRepoVisibilityRequest(
  val private: Boolean,
)
