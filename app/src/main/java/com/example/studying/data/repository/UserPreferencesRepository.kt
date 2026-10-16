package com.example.studying.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.studying.data.model.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val SELECTED_AI_PROVIDER = stringPreferencesKey("selected_ai_provider")
        val API_KEY = stringPreferencesKey("api_key")
        val IS_API_KEY_VALIDATED = booleanPreferencesKey("is_api_key_validated")
        val DAILY_REMINDER_ENABLED = booleanPreferencesKey("daily_reminder_enabled")
    }

    val selectedProviderFlow: Flow<AiProvider> = context.dataStore.data.map { preferences ->
        val providerName = preferences[PreferencesKeys.SELECTED_AI_PROVIDER]
        AiProvider.fromName(providerName)
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.API_KEY] ?: ""
    }

    val isApiKeyValidatedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.IS_API_KEY_VALIDATED] ?: false
    }

    val dailyReminderEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DAILY_REMINDER_ENABLED] ?: true
    }

    suspend fun saveAiProvider(provider: AiProvider) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_AI_PROVIDER] = provider.name
            preferences[PreferencesKeys.IS_API_KEY_VALIDATED] = false // Provider changed -> needs revalidation
        }
    }

    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_KEY] = apiKey.trim()
            preferences[PreferencesKeys.IS_API_KEY_VALIDATED] = false // Key changed -> needs revalidation
        }
    }

    suspend fun setApiKeyValidated(isValidated: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_API_KEY_VALIDATED] = isValidated
        }
    }

    suspend fun setDailyReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_REMINDER_ENABLED] = enabled
        }
    }
}
