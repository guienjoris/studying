package com.example.studying.ui.quiz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.data.model.Quiz
import com.example.studying.data.repository.AiStudyRepository
import com.example.studying.data.repository.UserPreferencesRepository
import com.example.studying.data.repository.UserStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface QuizUiState {
    data object Loading : QuizUiState
    data class Active(
        val quiz: Quiz,
        val currentQuestionIndex: Int,
        val selectedOptionIndex: Int?,
        val isAnswerSubmitted: Boolean,
        val score: Int
    ) : QuizUiState
    data class Completed(
        val theme: String,
        val score: Int,
        val totalQuestions: Int
    ) : QuizUiState
    data class Error(val message: String) : QuizUiState
}

class QuizViewModel(application: Application) : AndroidViewModel(application) {

    private val userPreferencesRepo = UserPreferencesRepository(application)
    private val statsRepo = UserStatsRepository(application)
    private val aiStudyRepo = AiStudyRepository()

    private val _uiState = MutableStateFlow<QuizUiState>(QuizUiState.Loading)
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    fun loadQuiz(theme: String, level: DifficultyLevel) {
        viewModelScope.launch {
            _uiState.value = QuizUiState.Loading

            val provider = userPreferencesRepo.selectedProviderFlow.first()
            val apiKey = userPreferencesRepo.apiKeyFlow.first()
            val isApiKeyValidated = userPreferencesRepo.isApiKeyValidatedFlow.first()
            
            val keyToUse = if (apiKey.isBlank() || !isApiKeyValidated) "" else apiKey

            aiStudyRepo.fetchQuiz(provider, keyToUse, theme, level)
                .onSuccess { quiz ->
                    _uiState.value = QuizUiState.Active(
                        quiz = quiz,
                        currentQuestionIndex = 0,
                        selectedOptionIndex = null,
                        isAnswerSubmitted = false,
                        score = 0
                    )
                }
                .onFailure { error ->
                    _uiState.value = QuizUiState.Error(error.message ?: "Erreur de chargement du quiz")
                }
        }
    }

    fun selectOption(optionIndex: Int) {
        val currentState = _uiState.value as? QuizUiState.Active ?: return
        if (currentState.isAnswerSubmitted) return

        _uiState.value = currentState.copy(selectedOptionIndex = optionIndex)
    }

    fun submitAnswer() {
        val currentState = _uiState.value as? QuizUiState.Active ?: return
        val selected = currentState.selectedOptionIndex ?: return
        if (currentState.isAnswerSubmitted) return

        val currentQuestion = currentState.quiz.questions[currentState.currentQuestionIndex]
        val isCorrect = (selected == currentQuestion.correctAnswerIndex)
        val newScore = if (isCorrect) currentState.score + 1 else currentState.score

        _uiState.value = currentState.copy(
            isAnswerSubmitted = true,
            score = newScore
        )
    }

    fun nextQuestion() {
        val currentState = _uiState.value as? QuizUiState.Active ?: return
        val nextIndex = currentState.currentQuestionIndex + 1

        if (nextIndex < currentState.quiz.questions.size) {
            _uiState.value = currentState.copy(
                currentQuestionIndex = nextIndex,
                selectedOptionIndex = null,
                isAnswerSubmitted = false
            )
        } else {
            // Quiz completed
            val finalScore = currentState.score
            val totalQuestions = currentState.quiz.questions.size
            val theme = currentState.quiz.theme

            viewModelScope.launch {
                statsRepo.recordQuizResult(theme, finalScore, totalQuestions)
                _uiState.value = QuizUiState.Completed(
                    theme = theme,
                    score = finalScore,
                    totalQuestions = totalQuestions
                )
            }
        }
    }
}
