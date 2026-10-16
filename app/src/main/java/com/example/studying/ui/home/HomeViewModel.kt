package com.example.studying.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studying.data.model.AiProvider
import com.example.studying.data.model.DEFAULT_THEMES
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.data.model.ThemeItem
import com.example.studying.data.repository.UserPreferencesRepository
import com.example.studying.data.repository.UserStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val statsRepository = UserStatsRepository(application)
    private val preferencesRepository = UserPreferencesRepository(application)

    val userStatsFlow = statsRepository.userStatsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = com.example.studying.data.model.UserStats()
    )

    val selectedProviderFlow = preferencesRepository.selectedProviderFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiProvider.GEMINI
    )

    val apiKeyFlow = preferencesRepository.apiKeyFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val isApiKeyValidatedFlow = preferencesRepository.isApiKeyValidatedFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("Tous")
    val selectedCategory = _selectedCategory.asStateFlow()

    private val _selectedLevel = MutableStateFlow(DifficultyLevel.DEBUTANT)
    val selectedLevel = _selectedLevel.asStateFlow()

    val categories = listOf("Tous", "Écosystème Web", "Langages & Backend", "DevOps & Data")

    val filteredThemes: StateFlow<List<ThemeItem>> = combine(
        _searchQuery,
        _selectedCategory
    ) { query, category ->
        DEFAULT_THEMES.filter { theme ->
            val matchesCategory = (category == "Tous" || theme.category == category)
            val matchesQuery = query.isBlank() ||
                    theme.title.contains(query, ignoreCase = true) ||
                    theme.description.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DEFAULT_THEMES
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String) {
        _selectedCategory.value = category
    }

    fun onLevelSelected(level: DifficultyLevel) {
        _selectedLevel.value = level
    }
}
