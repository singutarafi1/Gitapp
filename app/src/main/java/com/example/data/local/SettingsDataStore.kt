package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gitfiles_prefs")

@JsonClass(generateAdapter = true)
data class SavedToken(
  val id: String,
  val label: String,
  val token: String,
  val username: String = "",
)

data class SavedConnection(
  val token: String = "",
  val repoUrl: String = "",
  val branch: String = "",
  val savedTokens: List<SavedToken> = emptyList(),
  val uploadBatchSize: Int = SettingsDataStore.DEFAULT_UPLOAD_BATCH_SIZE,
)

fun maskToken(token: String): String {
  val clean = token.trim()
  if (clean.length <= 8) return "••••${clean.takeLast(4.coerceAtMost(clean.length))}"
  val prefix =
    when {
      clean.startsWith("github_pat_") -> "github_pat_"
      clean.startsWith("ghp_") -> "ghp_"
      clean.startsWith("gho_") -> "gho_"
      else -> clean.take(4)
    }
  return "$prefix••••${clean.takeLast(4)}"
}

class SettingsDataStore(private val context: Context) {
  private object Keys {
    val TOKEN = stringPreferencesKey("github_token")
    val REPO_URL = stringPreferencesKey("repo_url")
    val BRANCH = stringPreferencesKey("branch")
    val SAVED_TOKENS = stringPreferencesKey("saved_tokens_json")
    val UPLOAD_BATCH_SIZE = intPreferencesKey("upload_batch_size")
  }

  private val moshi = Moshi.Builder().build()
  private val tokenListType = Types.newParameterizedType(List::class.java, SavedToken::class.java)
  private val tokenListAdapter = moshi.adapter<List<SavedToken>>(tokenListType)

  private fun parseTokens(json: String?): List<SavedToken> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { tokenListAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
  }

  private fun encodeTokens(tokens: List<SavedToken>): String = tokenListAdapter.toJson(tokens)

  private fun upsertTokenInList(
    existing: List<SavedToken>,
    tokenValue: String,
    label: String? = null,
    username: String? = null,
  ): List<SavedToken> {
    val cleanToken = tokenValue.trim()
    if (cleanToken.isEmpty()) return existing
    val cleanLabel = label?.trim().orEmpty()
    val cleanUser = username?.trim().orEmpty()
    val index = existing.indexOfFirst { it.token == cleanToken }

    return if (index >= 0) {
      val current = existing[index]
      val resolvedUser = cleanUser.ifEmpty { current.username }
      val resolvedLabel =
        when {
          cleanLabel.isNotEmpty() -> cleanLabel
          current.label.isNotBlank() && !current.label.startsWith("Token ") -> current.label
          resolvedUser.isNotEmpty() -> "@$resolvedUser"
          else -> current.label.ifBlank { "Token ${index + 1}" }
        }
      val updated = current.copy(label = resolvedLabel, username = resolvedUser)
      existing.toMutableList().apply { set(index, updated) }
    } else {
      val fallbackLabel =
        when {
          cleanLabel.isNotEmpty() -> cleanLabel
          cleanUser.isNotEmpty() -> "@$cleanUser"
          else -> "Token ${existing.size + 1}"
        }
      existing +
        SavedToken(
          id = UUID.randomUUID().toString(),
          label = fallbackLabel,
          token = cleanToken,
          username = cleanUser,
        )
    }
  }

  val savedConnection: Flow<SavedConnection> =
    context.dataStore.data.map { prefs ->
      val activeToken = prefs[Keys.TOKEN].orEmpty().trim()
      val storedTokens = parseTokens(prefs[Keys.SAVED_TOKENS])
      val mergedTokens =
        if (activeToken.isNotEmpty() && storedTokens.none { it.token == activeToken }) {
          upsertTokenInList(storedTokens, activeToken)
        } else {
          storedTokens
        }
      val batchSize = sanitizeUploadBatchSize(prefs[Keys.UPLOAD_BATCH_SIZE] ?: DEFAULT_UPLOAD_BATCH_SIZE)
      SavedConnection(
        token = activeToken,
        repoUrl = prefs[Keys.REPO_URL].orEmpty(),
        branch = prefs[Keys.BRANCH].orEmpty(),
        savedTokens = mergedTokens,
        uploadBatchSize = batchSize,
      )
    }

  suspend fun saveConnection(
    token: String,
    repoUrl: String,
    branch: String,
    username: String = "",
  ): List<SavedToken> {
    var result: List<SavedToken> = emptyList()
    context.dataStore.edit { prefs ->
      val cleanToken = token.trim()
      val updated = upsertTokenInList(parseTokens(prefs[Keys.SAVED_TOKENS]), cleanToken, username = username)
      result = updated
      prefs[Keys.TOKEN] = cleanToken
      prefs[Keys.REPO_URL] = repoUrl.trim()
      prefs[Keys.BRANCH] = branch.trim()
      prefs[Keys.SAVED_TOKENS] = encodeTokens(updated)
    }
    return result
  }

  suspend fun addOrUpdateSavedToken(
    label: String,
    token: String,
    username: String = "",
    setAsActive: Boolean = true,
  ): List<SavedToken> {
    var result: List<SavedToken> = emptyList()
    context.dataStore.edit { prefs ->
      val cleanToken = token.trim()
      val updated =
        upsertTokenInList(
          existing = parseTokens(prefs[Keys.SAVED_TOKENS]),
          tokenValue = cleanToken,
          label = label,
          username = username,
        )
      result = updated
      prefs[Keys.SAVED_TOKENS] = encodeTokens(updated)
      if (setAsActive && cleanToken.isNotEmpty()) {
        prefs[Keys.TOKEN] = cleanToken
      }
    }
    return result
  }

  suspend fun selectActiveToken(token: String, clearRepo: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[Keys.TOKEN] = token.trim()
      if (clearRepo) {
        prefs.remove(Keys.REPO_URL)
        prefs.remove(Keys.BRANCH)
      }
    }
  }

  suspend fun removeSavedToken(id: String): List<SavedToken> {
    var result: List<SavedToken> = emptyList()
    context.dataStore.edit { prefs ->
      val current = parseTokens(prefs[Keys.SAVED_TOKENS])
      val removed = current.firstOrNull { it.id == id }
      val remaining = current.filterNot { it.id == id }
      result = remaining
      prefs[Keys.SAVED_TOKENS] = encodeTokens(remaining)
      if (removed != null && prefs[Keys.TOKEN] == removed.token) {
        prefs.remove(Keys.TOKEN)
        prefs.remove(Keys.REPO_URL)
        prefs.remove(Keys.BRANCH)
      }
    }
    return result
  }

  suspend fun updateBranch(branch: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.BRANCH] = branch.trim()
    }
  }

  suspend fun clearRepoSelection() {
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.REPO_URL)
      prefs.remove(Keys.BRANCH)
    }
  }

  suspend fun saveUploadBatchSize(batchSize: Int): Int {
    val clamped = sanitizeUploadBatchSize(batchSize)
    context.dataStore.edit { prefs ->
      prefs[Keys.UPLOAD_BATCH_SIZE] = clamped
    }
    return clamped
  }

  suspend fun clearConnection() {
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.TOKEN)
      prefs.remove(Keys.REPO_URL)
      prefs.remove(Keys.BRANCH)
    }
  }

  companion object {
    const val DEFAULT_UPLOAD_BATCH_SIZE = 50
    const val MIN_UPLOAD_BATCH_SIZE = 1
    const val MAX_UPLOAD_BATCH_SIZE = 200

    fun sanitizeUploadBatchSize(raw: Int): Int =
      raw.coerceIn(MIN_UPLOAD_BATCH_SIZE, MAX_UPLOAD_BATCH_SIZE)
  }
}
