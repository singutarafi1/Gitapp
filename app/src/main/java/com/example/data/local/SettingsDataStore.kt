package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gitfiles_prefs")

data class SavedConnection(
  val token: String = "",
  val repoUrl: String = "",
  val branch: String = "",
)

class SettingsDataStore(private val context: Context) {
  private object Keys {
    val TOKEN = stringPreferencesKey("github_token")
    val REPO_URL = stringPreferencesKey("repo_url")
    val BRANCH = stringPreferencesKey("branch")
  }

  val savedConnection: Flow<SavedConnection> =
    context.dataStore.data.map { prefs ->
      SavedConnection(
        token = prefs[Keys.TOKEN].orEmpty(),
        repoUrl = prefs[Keys.REPO_URL].orEmpty(),
        branch = prefs[Keys.BRANCH].orEmpty(),
      )
    }

  suspend fun saveConnection(token: String, repoUrl: String, branch: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.TOKEN] = token.trim()
      prefs[Keys.REPO_URL] = repoUrl.trim()
      prefs[Keys.BRANCH] = branch.trim()
    }
  }

  suspend fun updateBranch(branch: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.BRANCH] = branch.trim()
    }
  }

  suspend fun clearConnection() {
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.TOKEN)
      prefs.remove(Keys.REPO_URL)
      prefs.remove(Keys.BRANCH)
    }
  }
}
