package com.example.studying.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studying.data.model.UserStats
import com.example.studying.data.repository.UserStatsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val statsRepository = UserStatsRepository(application)

    val userStats: StateFlow<UserStats> = statsRepository.userStatsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserStats()
    )

    fun deleteTheme(theme: String) {
        viewModelScope.launch {
            statsRepository.deleteThemeStat(theme)
        }
    }
}
