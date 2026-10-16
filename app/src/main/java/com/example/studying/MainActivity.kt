package com.example.studying

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.ui.home.HomeScreen
import com.example.studying.ui.home.HomeViewModel
import com.example.studying.ui.lesson.LessonScreen
import com.example.studying.ui.lesson.LessonViewModel
import com.example.studying.ui.quiz.QuizScreen
import com.example.studying.ui.quiz.QuizViewModel
import com.example.studying.ui.settings.SettingsScreen
import com.example.studying.ui.settings.SettingsViewModel
import com.example.studying.ui.stats.StatsScreen
import com.example.studying.ui.stats.StatsViewModel
import com.example.studying.ui.theme.StudyingTheme
import com.example.studying.worker.DailyReminderWorker

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            DailyReminderWorker.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        DailyReminderWorker.schedule(this)

        setContent {
            StudyingTheme {
                MainApp()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    data object Home : BottomNavItem("home", "Thèmes", Icons.Default.Code)
    data object Stats : BottomNavItem("stats", "Statistiques", Icons.Default.Analytics)
    data object Settings : BottomNavItem("settings", "Paramètres", Icons.Default.Settings)
}

private fun encodeRouteParam(param: String): String {
    return Base64.encodeToString(
        param.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

private fun decodeRouteParam(encoded: String): String {
    return runCatching {
        String(
            Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            ),
            Charsets.UTF_8
        )
    }.getOrDefault(encoded)
}

@Composable
private fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Stats,
        BottomNavItem.Settings
    )

    // Hide bottom bar on Lesson and Quiz screens
    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Home.route) {
                val homeViewModel: HomeViewModel = viewModel()
                HomeScreen(
                    viewModel = homeViewModel,
                    onNavigateToLesson = { theme, level ->
                        navController.navigate("lesson/${encodeRouteParam(theme)}/${level.name}")
                    },
                    onNavigateToQuiz = { theme, level ->
                        navController.navigate("quiz/${encodeRouteParam(theme)}/${level.name}")
                    },
                    onNavigateToSettings = {
                        navController.navigate(BottomNavItem.Settings.route)
                    }
                )
            }

            composable(BottomNavItem.Stats.route) {
                val statsViewModel: StatsViewModel = viewModel()
                StatsScreen(viewModel = statsViewModel)
            }

            composable(BottomNavItem.Settings.route) {
                val settingsViewModel: SettingsViewModel = viewModel()
                SettingsScreen(viewModel = settingsViewModel)
            }

            composable(
                route = "lesson/{theme}/{level}",
                arguments = listOf(
                    navArgument("theme") { type = NavType.StringType },
                    navArgument("level") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encodedTheme = backStackEntry.arguments?.getString("theme") ?: ""
                val theme = decodeRouteParam(encodedTheme)
                val levelName = backStackEntry.arguments?.getString("level") ?: DifficultyLevel.DEBUTANT.name
                val level = DifficultyLevel.fromName(levelName)

                val lessonViewModel: LessonViewModel = viewModel()
                LessonScreen(
                    theme = theme,
                    level = level,
                    viewModel = lessonViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToQuiz = { targetTheme, targetLevel ->
                        navController.navigate("quiz/${encodeRouteParam(targetTheme)}/${targetLevel.name}") {
                            popUpTo("lesson/{theme}/{level}") { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = "quiz/{theme}/{level}",
                arguments = listOf(
                    navArgument("theme") { type = NavType.StringType },
                    navArgument("level") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encodedTheme = backStackEntry.arguments?.getString("theme") ?: ""
                val theme = decodeRouteParam(encodedTheme)
                val levelName = backStackEntry.arguments?.getString("level") ?: DifficultyLevel.DEBUTANT.name
                val level = DifficultyLevel.fromName(levelName)

                val quizViewModel: QuizViewModel = viewModel()
                QuizScreen(
                    theme = theme,
                    level = level,
                    viewModel = quizViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToStats = {
                        navController.navigate(BottomNavItem.Stats.route) {
                            popUpTo(BottomNavItem.Home.route)
                        }
                    }
                )
            }
        }
    }
}
