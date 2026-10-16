package com.example.studying.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studying.data.model.AiProvider
import com.example.studying.data.repository.AiStudyRepository
import com.example.studying.data.repository.UserPreferencesRepository
import com.example.studying.worker.DailyReminderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ApiKeyTestState {
    data object Idle : ApiKeyTestState
    data object Testing : ApiKeyTestState
    data class Success(val message: String) : ApiKeyTestState
    data class Error(val message: String) : ApiKeyTestState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = UserPreferencesRepository(application)
    private val aiStudyRepository = AiStudyRepository()

    val selectedProvider: StateFlow<AiProvider> = preferencesRepository.selectedProviderFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiProvider.GEMINI
    )

    val apiKey: StateFlow<String> = preferencesRepository.apiKeyFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val isApiKeyValidated: StateFlow<Boolean> = preferencesRepository.isApiKeyValidatedFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val dailyReminderEnabled: StateFlow<Boolean> = preferencesRepository.dailyReminderEnabledFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    private val _testState = MutableStateFlow<ApiKeyTestState>(ApiKeyTestState.Idle)
    val testState: StateFlow<ApiKeyTestState> = _testState.asStateFlow()

    fun onProviderSelected(provider: AiProvider) {
        viewModelScope.launch {
            preferencesRepository.saveAiProvider(provider)
            _testState.value = ApiKeyTestState.Idle
        }
    }

    fun onApiKeyChanged(newApiKey: String) {
        viewModelScope.launch {
            preferencesRepository.saveApiKey(newApiKey)
            _testState.value = ApiKeyTestState.Idle
        }
    }

    fun testApiKey() {
        viewModelScope.launch {
            _testState.value = ApiKeyTestState.Testing

            val provider = preferencesRepository.selectedProviderFlow.first()
            val key = preferencesRepository.apiKeyFlow.first()

            if (key.isBlank()) {
                _testState.value = ApiKeyTestState.Error("Veuillez d'abord coller votre clé API dans le champ ci-dessus.")
                return@launch
            }

            aiStudyRepository.validateApiKey(provider, key)
                .onSuccess {
                    preferencesRepository.setApiKeyValidated(true)
                    _testState.value = ApiKeyTestState.Success("🎉 Clé ${provider.displayName} valide ! L'IA est prête et opérationnelle.")
                }
                .onFailure { error ->
                    preferencesRepository.setApiKeyValidated(false)
                    _testState.value = ApiKeyTestState.Error("❌ Erreur de connexion (${provider.displayName}) : ${error.localizedMessage ?: "Clé invalide ou problème réseau"}")
                }
        }
    }

    fun onDailyReminderToggled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDailyReminderEnabled(enabled)
            if (enabled) {
                DailyReminderWorker.schedule(getApplication())
            } else {
                DailyReminderWorker.cancel(getApplication())
            }
        }
    }
}
