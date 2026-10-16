package com.example.studying.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class DifficultyLevel(val label: String, val description: String) {
    DEBUTANT("Débutant", "Fondamentaux et syntaxe de base"),
    INTERMEDIAIRE("Intermédiaire", "Cas d'usage, pièges et bonnes pratiques"),
    AVANCE("Avancé", "Concepts avancés, architecture et optimisation");

    companion object {
        fun fromName(name: String?): DifficultyLevel {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEBUTANT
        }
    }
}

@Serializable
data class ThemeItem(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val iconName: String
)

val DEFAULT_THEMES = listOf(
    // Écosystème JS & Web
    ThemeItem("js_ts", "JavaScript & TypeScript", "Écosystème Web", "Bases de la syntaxe, asynchronisme (Promises, async/await), typage fort avec TS.", "javascript"),
    ThemeItem("react", "React.js", "Écosystème Web", "Hooks, gestion d'état, Virtual DOM, re-renders et composants fonctionnels.", "react"),
    ThemeItem("nextjs", "Next.js", "Écosystème Web", "App Router, Server Components (RSC), SSR, SSG et optimisation SEO.", "nextjs"),
    ThemeItem("nodejs", "Node.js", "Écosystème Web", "Event Loop, I/O non-bloquant, modules, streams et HTTP server.", "nodejs"),
    ThemeItem("nestjs", "NestJS", "Écosystème Web", "Architecture modulaire, injection de dépendances, décorateurs et DTOs.", "nestjs"),

    // Langages Systems & Backend
    ThemeItem("python", "Python", "Langages & Backend", "Structures de données, décorateurs, typage, GIL et programmation orientée objet.", "python"),
    ThemeItem("java", "Java", "Langages & Backend", "JVM, gestion mémoire, Streams API, multi-threading et POO.", "java"),
    ThemeItem("kotlin", "Kotlin", "Langages & Backend", "Coroutines, Null Safety, extensions, Data Classes et Flow.", "kotlin"),
    ThemeItem("rust", "Rust", "Langages & Backend", "Ownership, Borrow Checker, Traits, gestion de la mémoire sans GC.", "rust"),
    ThemeItem("cpp", "C++", "Langages & Backend", "Pointeurs, RAII, templates, STL et gestion manuelle de la mémoire.", "cpp"),
    ThemeItem("golang", "Go", "Langages & Backend", "Goroutines, channels, interfaces implicites et garbage collection.", "go"),
    ThemeItem("swift", "Swift", "Langages & Backend", "ARC, Optionals, Protocols, Structs vs Classes et Concurrence.", "swift"),

    // DevOps & Data
    ThemeItem("sql", "SQL & Bases de données", "DevOps & Data", "Jointures, indexation, transactions ACID et optimisation de requêtes.", "sql"),
    ThemeItem("docker", "Docker & Containers", "DevOps & Data", "Dockerfile, images, volumes, réseaux et orchestration Docker Compose.", "docker"),
    ThemeItem("git", "Git & Versioning", "DevOps & Data", "Rebase vs Merge, Cherry-pick, Git Flow, Reflog et gestion des conflits.", "git"),
    ThemeItem("arch", "Design Patterns & Architecture", "DevOps & Data", "SOLID, Clean Architecture, Event-Driven et Design Patterns GoF.", "architecture")
)

@Serializable
data class LessonSection(
    val title: String,
    val content: String,
    val codeSnippet: String? = null
)

@Serializable
data class Lesson(
    val title: String,
    val theme: String,
    val level: String,
    val summary: String,
    val keyPoints: List<String>,
    val sections: List<LessonSection> = emptyList(),
    val content: String = ""
)

@Serializable
data class Question(
    val id: Int = 0,
    val prompt: String,
    val options: List<String>,
    val correctAnswerIndex: Int,
    val explanation: String
)

@Serializable
data class Quiz(
    val title: String,
    val theme: String,
    val level: String,
    val questions: List<Question>
)

@Serializable
data class QuizResult(
    val id: String,
    val theme: String,
    val level: String,
    val score: Int,
    val totalQuestions: Int,
    val timestamp: Long
)

@Serializable
data class ThemeStat(
    val theme: String,
    val quizzesTaken: Int = 0,
    val totalScore: Int = 0,
    val totalQuestions: Int = 0,
    val lessonsRead: Int = 0,
    val lastActivityTimestamp: Long = 0L
) {
    val successRatePercentage: Int
        get() = if (totalQuestions > 0) ((totalScore.toDouble() / totalQuestions) * 100).toInt() else 0
}

@Serializable
data class UserStats(
    val totalLessonsRead: Int = 0,
    val totalQuizzesTaken: Int = 0,
    val totalCorrectAnswers: Int = 0,
    val totalQuestionsAnswered: Int = 0,
    val currentStreakDays: Int = 0,
    val lastActivityDateMillis: Long = 0L,
    val themeStats: Map<String, ThemeStat> = emptyMap()
) {
    val globalSuccessRatePercentage: Int
        get() = if (totalQuestionsAnswered > 0) ((totalCorrectAnswers.toDouble() / totalQuestionsAnswered) * 100).toInt() else 0
}
