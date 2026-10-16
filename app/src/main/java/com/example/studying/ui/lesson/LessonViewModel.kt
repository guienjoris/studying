package com.example.studying.ui.lesson

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studying.data.model.AiProvider
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.data.model.Lesson
import com.example.studying.data.repository.AiStudyRepository
import com.example.studying.data.repository.UserPreferencesRepository
import com.example.studying.data.repository.UserStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface LessonUiState {
    data object Loading : LessonUiState
    data class Success(
        val lesson: Lesson,
        val isDemoMode: Boolean,
        val provider: AiProvider
    ) : LessonUiState
    data class Error(val message: String) : LessonUiState
}

class LessonViewModel(application: Application) : AndroidViewModel(application) {

    private val userPreferencesRepo = UserPreferencesRepository(application)
    private val statsRepo = UserStatsRepository(application)
    private val aiStudyRepo = AiStudyRepository()

    private val _uiState = MutableStateFlow<LessonUiState>(LessonUiState.Loading)
    val uiState: StateFlow<LessonUiState> = _uiState.asStateFlow()

    fun loadLesson(
        theme: String,
        level: DifficultyLevel,
        variantSeed: Int = Random.nextInt(1, 10000)
    ) {
        viewModelScope.launch {
            _uiState.value = LessonUiState.Loading

            val provider = userPreferencesRepo.selectedProviderFlow.first()
            val apiKey = userPreferencesRepo.apiKeyFlow.first()
            val isApiKeyValidated = userPreferencesRepo.isApiKeyValidatedFlow.first()
            val isDemo = apiKey.isBlank() || !isApiKeyValidated

            val keyToUse = if (isDemo) "" else apiKey

            aiStudyRepo.fetchLesson(
                provider = provider,
                apiKey = keyToUse,
                theme = theme,
                level = level,
                variantSeed = variantSeed
            )
                .onSuccess { lesson ->
                    _uiState.value = LessonUiState.Success(
                        lesson = lesson,
                        isDemoMode = isDemo,
                        provider = provider
                    )
                    statsRepo.recordLessonRead(theme)
                }
                .onFailure { error ->
                    _uiState.value = LessonUiState.Error(error.message ?: "Erreur de chargement de la leçon")
                }
        }
    }

    fun regenerateLesson(theme: String, level: DifficultyLevel) {
        val freshSeed = Random.nextInt(1, 10000)
        loadLesson(theme, level, variantSeed = freshSeed)
    }
}
