package com.example.studying.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.studying.data.model.AiProvider
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.data.model.ThemeItem

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToLesson: (String, DifficultyLevel) -> Unit,
    onNavigateToQuiz: (String, DifficultyLevel) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val stats by viewModel.userStatsFlow.collectAsState()
    val provider by viewModel.selectedProviderFlow.collectAsState()
    val apiKey by viewModel.apiKeyFlow.collectAsState()
    val isApiKeyValidated by viewModel.isApiKeyValidatedFlow.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val themes by viewModel.filteredThemes.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // Top Header Banner
        item {
            HeaderBanner(streakDays = stats.currentStreakDays)
        }

        // Mode Status Banner (Demo Mode vs AI Active)
        item {
            PaddingBox {
                ModeStatusCard(
                    provider = provider,
                    isConfigured = apiKey.isNotBlank() && isApiKeyValidated,
                    onNavigateToSettings = onNavigateToSettings
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Search Field
        item {
            PaddingBox {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Rechercher ou saisir un thème (ex: NextJS, Rust...)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Level Selector (Débutant, Intermédiaire, Avancé)
        item {
            PaddingBox {
                Column {
                    Text(
                        text = "Niveau de Révision",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(DifficultyLevel.entries) { level ->
                            FilterChip(
                                selected = (selectedLevel == level),
                                onClick = { viewModel.onLevelSelected(level) },
                                label = {
                                    Text(
                                        text = level.label,
                                        fontWeight = if (selectedLevel == level) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Category & Themes Section
        item {
            PaddingBox {
                Text(
                    text = "Thèmes & Technologies",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(viewModel.categories) { category ->
                    FilterChip(
                        selected = (selectedCategory == category),
                        onClick = { viewModel.onCategorySelected(category) },
                        label = { Text(category) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Custom Theme Action if user typed a search query not matching exact themes
        if (searchQuery.isNotBlank() && themes.none { it.title.equals(searchQuery, ignoreCase = true) }) {
            item {
                PaddingBox {
                    CustomThemeCard(
                        customThemeName = searchQuery,
                        level = selectedLevel,
                        onNavigateToLesson = onNavigateToLesson,
                        onNavigateToQuiz = onNavigateToQuiz
                    )
                }
            }
        }

        // Theme Cards List
        items(themes, key = { it.id }) { theme ->
            PaddingBox {
                ThemeCard(
                    theme = theme,
                    level = selectedLevel,
                    onNavigateToLesson = onNavigateToLesson,
                    onNavigateToQuiz = onNavigateToQuiz
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeStatusCard(
    provider: AiProvider,
    isConfigured: Boolean,
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToSettings() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured)
                Color(0xFFE8F5E9)
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConfigured) Icons.Default.AutoAwesome else Icons.Default.Key,
                contentDescription = null,
                tint = if (isConfigured) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConfigured) "✨ Mode IA Actif (${provider.displayName})" else "💡 Mode Démo (Clé non testée)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isConfigured) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isConfigured)
                        "Les leçons et quiz sont générés en direct par l'IA."
                    else
                        "L'app utilise les leçons de démo. Cliquez ici pour valider votre clé ${provider.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConfigured) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Paramètres",
                tint = if (isConfigured) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HeaderBanner(streakDays: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Révisions Dev IA 🚀",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Boostez vos compétences techniques au quotidien",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.2f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = "Streak",
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$streakDays j",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomThemeCard(
    customThemeName: String,
    level: DifficultyLevel,
    onNavigateToLesson: (String, DifficultyLevel) -> Unit,
    onNavigateToQuiz: (String, DifficultyLevel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Thème Sur Mesure : \"$customThemeName\"",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "L'IA va générer une leçon ou un quiz spécifique sur $customThemeName au niveau ${level.label}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onNavigateToLesson(customThemeName, level) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Générer Leçon")
                }
                OutlinedButton(
                    onClick = { onNavigateToQuiz(customThemeName, level) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lancer Quiz")
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ThemeItem,
    level: DifficultyLevel,
    onNavigateToLesson: (String, DifficultyLevel) -> Unit,
    onNavigateToQuiz: (String, DifficultyLevel) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = theme.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = theme.category,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = theme.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onNavigateToLesson(theme.title, level) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Leçon 📖")
                }
                OutlinedButton(
                    onClick = { onNavigateToQuiz(theme.title, level) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Quiz ⚡")
                }
            }
        }
    }
}

@Composable
private fun PaddingBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        content()
    }
}
