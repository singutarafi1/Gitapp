package com.example.data.remote

import com.example.data.model.CreateBlobRequest
import com.example.data.model.CreateBlobResponse
import com.example.data.model.CreateCommitRequest
import com.example.data.model.CreateCommitResponse
import com.example.data.model.CreateRefRequest
import com.example.data.model.CreateRepoRequest
import com.example.data.model.CreateTreeRequest
import com.example.data.model.CreateTreeResponse
import com.example.data.model.DeleteContentRequest
import com.example.data.model.GitCommitDto
import com.example.data.model.GitHubBranchDto
import com.example.data.model.GitHubContentDto
import com.example.data.model.GitHubRepoDto
import com.example.data.model.GitHubUserDto
import com.example.data.model.GitRefDto
import com.example.data.model.GitTreeResponseDto
import com.example.data.model.PutContentRequest
import com.example.data.model.PutContentResponse
import com.example.data.model.RepoCommitListItemDto
import com.example.data.model.UpdateRefRequest
import com.example.data.model.UpdateRepoVisibilityRequest
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GitHubApiService {

  @GET("user")
  suspend fun getAuthenticatedUser(
    @Header("Authorization") auth: String,
  ): Response<GitHubUserDto>

  @GET("user/repos")
  suspend fun listUserRepos(
    @Header("Authorization") auth: String,
    @Query("sort") sort: String = "updated",
    @Query("per_page") perPage: Int = 100,
    @Query("page") page: Int = 1,
    @Query("affiliation") affiliation: String = "owner,collaborator,organization_member",
    @Query("visibility") visibility: String = "all",
  ): List<GitHubRepoDto>

  @POST("user/repos")
  suspend fun createUserRepo(
    @Header("Authorization") auth: String,
    @Body body: CreateRepoRequest,
  ): GitHubRepoDto

  @GET("repos/{owner}/{repo}")
  suspend fun getRepository(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
  ): GitHubRepoDto

  @PATCH("repos/{owner}/{repo}")
  suspend fun updateRepositoryVisibility(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Body body: UpdateRepoVisibilityRequest,
  ): GitHubRepoDto

  @DELETE("repos/{owner}/{repo}")
  suspend fun deleteRepository(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
  ): Response<Unit>

  @GET("repos/{owner}/{repo}/branches")
  suspend fun listBranches(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Query("per_page") perPage: Int = 100,
  ): List<GitHubBranchDto>

  @GET("repos/{owner}/{repo}/contents")
  suspend fun listRootContents(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Query("ref") ref: String,
  ): Response<List<GitHubContentDto>>

  @GET("repos/{owner}/{repo}/contents/{path}")
  suspend fun listPathContents(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("path", encoded = true) encodedPath: String,
    @Query("ref") ref: String,
  ): Response<List<GitHubContentDto>>

  @GET("repos/{owner}/{repo}/contents/{path}")
  suspend fun getFileContent(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("path", encoded = true) encodedPath: String,
    @Query("ref") ref: String,
  ): GitHubContentDto

  @Streaming
  @GET("repos/{owner}/{repo}/git/blobs/{sha}")
  suspend fun downloadRawBlob(
    @Header("Authorization") auth: String,
    @Header("Accept") accept: String = "application/vnd.github.raw+json",
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("sha") sha: String,
  ): Response<ResponseBody>

  @PUT("repos/{owner}/{repo}/contents/{path}")
  suspend fun putFileContent(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("path", encoded = true) encodedPath: String,
    @Body body: PutContentRequest,
  ): PutContentResponse

  @HTTP(method = "DELETE", path = "repos/{owner}/{repo}/contents/{path}", hasBody = true)
  suspend fun deleteFileContent(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("path", encoded = true) encodedPath: String,
    @Body body: DeleteContentRequest,
  )

  @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
  suspend fun getBranchRef(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("branch", encoded = true) encodedBranch: String,
  ): GitRefDto

  @GET("repos/{owner}/{repo}/git/commits/{sha}")
  suspend fun getCommit(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("sha") sha: String,
  ): GitCommitDto

  @GET("repos/{owner}/{repo}/git/trees/{sha}")
  suspend fun getTreeRecursive(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("sha") sha: String,
    @Query("recursive") recursive: Int = 1,
  ): GitTreeResponseDto

  @POST("repos/{owner}/{repo}/git/blobs")
  suspend fun createBlob(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Body body: RequestBody,
  ): CreateBlobResponse

  @POST("repos/{owner}/{repo}/git/trees")
  suspend fun createTree(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Body body: CreateTreeRequest,
  ): CreateTreeResponse

  @POST("repos/{owner}/{repo}/git/commits")
  suspend fun createCommit(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Body body: CreateCommitRequest,
  ): CreateCommitResponse

  @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
  suspend fun updateBranchRef(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Path("branch", encoded = true) encodedBranch: String,
    @Body body: UpdateRefRequest,
  ): GitRefDto

  @POST("repos/{owner}/{repo}/git/refs")
  suspend fun createBranchRef(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Body body: CreateRefRequest,
  ): GitRefDto

  @GET("repos/{owner}/{repo}/commits")
  suspend fun listCommits(
    @Header("Authorization") auth: String,
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Query("sha") sha: String,
    @Query("path") path: String? = null,
    @Query("per_page") perPage: Int = 15,
  ): List<RepoCommitListItemDto>
}
