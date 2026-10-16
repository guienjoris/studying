package com.example.studying.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.studying.data.model.ThemeStat
import com.example.studying.data.model.UserStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.statsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_stats")

class UserStatsRepository(private val context: Context) {

    private object PreferencesKeys {
        val STATS_JSON = stringPreferencesKey("stats_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val userStatsFlow: Flow<UserStats> = context.statsDataStore.data.map { preferences ->
        val rawJson = preferences[PreferencesKeys.STATS_JSON]
        if (rawJson.isNullOrBlank()) {
            UserStats()
        } else {
            runCatching { json.decodeFromString<UserStats>(rawJson) }.getOrDefault(UserStats())
        }
    }

    suspend fun recordLessonRead(theme: String) {
        val currentStats = userStatsFlow.first()
        val now = System.currentTimeMillis()
        val updatedStreak = calculateStreak(currentStats.lastActivityDateMillis, currentStats.currentStreakDays, now)

        val currentThemeStat = currentStats.themeStats[theme] ?: ThemeStat(theme = theme)
        val updatedThemeStat = currentThemeStat.copy(
            lessonsRead = currentThemeStat.lessonsRead + 1,
            lastActivityTimestamp = now
        )

        val updatedMap = currentStats.themeStats.toMutableMap().apply {
            put(theme, updatedThemeStat)
        }

        val updatedStats = currentStats.copy(
            totalLessonsRead = currentStats.totalLessonsRead + 1,
            currentStreakDays = updatedStreak,
            lastActivityDateMillis = now,
            themeStats = updatedMap
        )

        saveStats(updatedStats)
    }

    suspend fun recordQuizResult(theme: String, score: Int, totalQuestions: Int) {
        val currentStats = userStatsFlow.first()
        val now = System.currentTimeMillis()
        val updatedStreak = calculateStreak(currentStats.lastActivityDateMillis, currentStats.currentStreakDays, now)

        val currentThemeStat = currentStats.themeStats[theme] ?: ThemeStat(theme = theme)
        val updatedThemeStat = currentThemeStat.copy(
            quizzesTaken = currentThemeStat.quizzesTaken + 1,
            totalScore = currentThemeStat.totalScore + score,
            totalQuestions = currentThemeStat.totalQuestions + totalQuestions,
            lastActivityTimestamp = now
        )

        val updatedMap = currentStats.themeStats.toMutableMap().apply {
            put(theme, updatedThemeStat)
        }

        val updatedStats = currentStats.copy(
            totalQuizzesTaken = currentStats.totalQuizzesTaken + 1,
            totalCorrectAnswers = currentStats.totalCorrectAnswers + score,
            totalQuestionsAnswered = currentStats.totalQuestionsAnswered + totalQuestions,
            currentStreakDays = updatedStreak,
            lastActivityDateMillis = now,
            themeStats = updatedMap
        )

        saveStats(updatedStats)
    }

    suspend fun deleteThemeStat(theme: String) {
        val currentStats = userStatsFlow.first()
        val themeStatToRemove = currentStats.themeStats[theme] ?: return

        val updatedMap = currentStats.themeStats.toMutableMap().apply {
            remove(theme)
        }

        val updatedStats = currentStats.copy(
            totalLessonsRead = (currentStats.totalLessonsRead - themeStatToRemove.lessonsRead).coerceAtLeast(0),
            totalQuizzesTaken = (currentStats.totalQuizzesTaken - themeStatToRemove.quizzesTaken).coerceAtLeast(0),
            totalCorrectAnswers = (currentStats.totalCorrectAnswers - themeStatToRemove.totalScore).coerceAtLeast(0),
            totalQuestionsAnswered = (currentStats.totalQuestionsAnswered - themeStatToRemove.totalQuestions).coerceAtLeast(0),
            themeStats = updatedMap
        )

        saveStats(updatedStats)
    }

    suspend fun hasStudiedToday(): Boolean {
        val currentStats = userStatsFlow.first()
        if (currentStats.lastActivityDateMillis == 0L) return false

        val todayCal = Calendar.getInstance()
        val lastCal = Calendar.getInstance().apply { timeInMillis = currentStats.lastActivityDateMillis }

        return todayCal.get(Calendar.YEAR) == lastCal.get(Calendar.YEAR) &&
                todayCal.get(Calendar.DAY_OF_YEAR) == lastCal.get(Calendar.DAY_OF_YEAR)
    }

    private suspend fun saveStats(stats: UserStats) {
        context.statsDataStore.edit { preferences ->
            preferences[PreferencesKeys.STATS_JSON] = json.encodeToString(stats)
        }
    }

    private fun calculateStreak(lastActivityMillis: Long, currentStreak: Int, nowMillis: Long): Int {
        if (lastActivityMillis == 0L) return 1

        val lastCal = Calendar.getInstance().apply { timeInMillis = lastActivityMillis }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

        // If activity was today, streak remains unchanged
        if (lastCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            lastCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        ) {
            return if (currentStreak == 0) 1 else currentStreak
        }

        // Check if yesterday
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }

        return if (lastCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
            lastCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        ) {
            currentStreak + 1
        } else {
            1
        }
    }
}
