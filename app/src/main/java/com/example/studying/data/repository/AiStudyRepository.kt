package com.example.studying.data.repository

import com.example.studying.data.model.AiProvider
import com.example.studying.data.model.DifficultyLevel
import com.example.studying.data.model.Lesson
import com.example.studying.data.model.LessonSection
import com.example.studying.data.model.Question
import com.example.studying.data.model.Quiz
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

class AiStudyRepository {

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 90_000L
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun validateApiKey(provider: AiProvider, apiKey: String): Result<Unit> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("La clé API ne peut pas être vide."))
        }

        return runCatching {
            val prompt = "Réponds au format JSON strict : {\"status\": \"ok\"}"
            callAiApi(provider, apiKey, prompt)
            Unit
        }
    }

    suspend fun fetchLesson(
        provider: AiProvider,
        apiKey: String,
        theme: String,
        level: DifficultyLevel,
        variantSeed: Int = Random.nextInt(1, 10000)
    ): Result<Lesson> {
        if (apiKey.isBlank()) {
            return Result.success(getDemoLesson(theme, level, variantSeed))
        }

        return runCatching {
            val prompt = buildLessonPrompt(theme, level, variantSeed)
            val jsonResponseText = callAiApi(provider, apiKey, prompt)
            parseLessonJson(jsonResponseText, theme, level)
        }
    }

    suspend fun fetchQuiz(
        provider: AiProvider,
        apiKey: String,
        theme: String,
        level: DifficultyLevel
    ): Result<Quiz> {
        if (apiKey.isBlank()) {
            return Result.success(getDemoQuiz(theme, level))
        }

        return runCatching {
            val prompt = buildQuizPrompt(theme, level)
            val jsonResponseText = callAiApi(provider, apiKey, prompt)
            parseQuizJson(jsonResponseText, theme, level)
        }
    }

    private suspend fun callAiApi(provider: AiProvider, apiKey: String, prompt: String): String {
        return when (provider) {
            AiProvider.GEMINI -> callGemini(apiKey, prompt)
            AiProvider.OPEN_AI -> callOpenAi(apiKey, prompt)
            AiProvider.ANTHROPIC -> callAnthropic(apiKey, prompt)
        }
    }

    private suspend fun callGemini(apiKey: String, prompt: String): String {
        val geminiModels = listOf("gemini-3.8-flash","gemini-3.6-flash","gemini-3.5-flash")
        var lastError = "Impossible de joindre Gemini"

        for (model in geminiModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val body = """
                {
                  "contents": [{ "parts": [{ "text": ${jsonParser.encodeToString(prompt)} }] }],
                  "generationConfig": { 
                    "responseMimeType": "application/json",
                    "temperature": 0.95
                  }
                }
            """.trimIndent()

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val responseText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val root = jsonParser.parseToJsonElement(responseText).jsonObject
                val candidates = root["candidates"]?.jsonArray ?: continue
                val content = candidates.getOrNull(0)?.jsonObject?.get("content")?.jsonObject
                val parts = content?.get("parts")?.jsonArray
                val text = parts?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) {
                    return text
                }
            } else {
                lastError = parseErrorMessage(responseText)
                if (response.status.value == 400 && lastError.contains("API key", ignoreCase = true)) {
                    throw IllegalArgumentException("Erreur Gemini (400) : Clé API invalide. Vérifiez votre clé Google AI Studio.")
                }
            }
        }

        throw IllegalArgumentException("Erreur Gemini : $lastError")
    }

    private suspend fun callOpenAi(apiKey: String, prompt: String): String {
        val openAiModels = listOf("gpt-4o-mini", "gpt-3.5-turbo")
        var lastError = "Impossible de joindre OpenAI"

        for (model in openAiModels) {
            val url = "https://api.openai.com/v1/chat/completions"
            val body = """
                {
                  "model": "$model",
                  "temperature": 0.95,
                  "response_format": { "type": "json_object" },
                  "messages": [
                    { "role": "system", "content": "Tu es un tuteur expert en informatique et programmation. Réponds toujours au format JSON strict." },
                    { "role": "user", "content": ${jsonParser.encodeToString(prompt)} }
                  ]
                }
            """.trimIndent()

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(body)
            }

            val responseText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val root = jsonParser.parseToJsonElement(responseText).jsonObject
                val choices = root["choices"]?.jsonArray ?: continue
                val message = choices.getOrNull(0)?.jsonObject?.get("message")?.jsonObject
                val text = message?.get("content")?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) {
                    return text
                }
            } else {
                lastError = parseErrorMessage(responseText)
                if (response.status.value == 401 || (response.status.value == 400 && lastError.contains("key", ignoreCase = true))) {
                    throw IllegalArgumentException("Erreur OpenAI (${response.status.value}) : Clé API invalide.")
                }
            }
        }

        throw IllegalArgumentException("Erreur OpenAI : $lastError")
    }

    private suspend fun callAnthropic(apiKey: String, prompt: String): String {
        val anthropicModels = listOf("claude-3-5-haiku-20241022", "claude-3-haiku-20240307")
        var lastError = "Impossible de joindre Anthropic"

        for (model in anthropicModels) {
            val url = "https://api.anthropic.com/v1/messages"
            val body = """
                {
                  "model": "$model",
                  "temperature": 0.95,
                  "max_tokens": 2048,
                  "messages": [
                    { "role": "user", "content": ${jsonParser.encodeToString(prompt + "\n\nRends uniquement le JSON sans markdown ni balises triple backticks.")} }
                  ]
                }
            """.trimIndent()

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(body)
            }

            val responseText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val root = jsonParser.parseToJsonElement(responseText).jsonObject
                val content = root["content"]?.jsonArray ?: continue
                val text = content.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) {
                    return text
                }
            } else {
                lastError = parseErrorMessage(responseText)
                if (response.status.value == 401 || response.status.value == 403) {
                    throw IllegalArgumentException("Erreur Claude (${response.status.value}) : Clé API invalide.")
                }
            }
        }

        throw IllegalArgumentException("Erreur Claude : $lastError")
    }

    private fun parseErrorMessage(rawJson: String): String {
        return runCatching {
            val root = jsonParser.parseToJsonElement(rawJson).jsonObject
            val errorObj = root["error"]?.jsonObject
            errorObj?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
                ?: rawJson
        }.getOrDefault(rawJson)
    }

    private fun buildLessonPrompt(theme: String, level: DifficultyLevel, variantSeed: Int): String {
        val angles = listOf(
            "Focalise la leçon sur les cas d'usage réels, l'architecture et les exemples pratiques de production.",
            "Focalise la leçon sur les performances bas niveau, l'optimisation mémoire et la résolution des pièges fréquents.",
            "Focalise la leçon sur le code idiomatique moderne, la lisibilité et les meilleures conventions de l'écosystème."
        )
        val selectedAngle = angles[variantSeed % angles.size]

        return """
            Génère une NOUVELLE leçon de révision sur-mesure et inédite pour développeurs sur le thème "$theme" au niveau "${level.label}".
            
            ANGLE PÉDAGOGIQUE POUR CETTE GÉNÉRATION : $selectedAngle (Génération #$variantSeed).

            EXIGENCES OBLIGATOIRES:
            1. Propose du contenu NOUVEAU et INÉDIT spécifique à "$theme" et "${level.label}".
            2. Chaque section DOIT OBLIGATOIREMENT contenir un exemple de code concret, fonctionnel et explicatif dans la propriété "codeSnippet".
            3. Le code dans "codeSnippet" doit être du vrai code source propre (sans balises markdown triple backticks).
            4. TRES IMPORTANT: Protège et échappe tous les caractères spéciaux (notamment les guillemets et les retours à la ligne \n) pour que le JSON généré soit 100% parsable. N'inclus aucune virgule traînante.

            Exigences spécifiques selon le niveau :
            - Débutant : Expliquer la syntaxe de base, le rôle principal de $theme et des exemples simples.
            - Intermédiaire : Patterns d'utilisation courants, fonctionnement sous le capot, pièges classiques et code idiomatique.
            - Avancé : Architecture, gestion mémoire/concurrence, optimisations de performances et cas complexes.

            La réponse DOIT être un objet JSON valide avec la structure exacte suivante :
            {
              "title": "Leçon : $theme (${level.label})",
              "summary": "Résumé global en 2-3 phrases",
              "keyPoints": ["Point clé 1", "Point clé 2", "Point clé 3", "Point clé 4"],
              "sections": [
                {
                  "title": "Page 1 : Introduction & Fondamentaux de $theme",
                  "content": "Explication détaillée...",
                  "codeSnippet": "// Exemple de code pour la page 1"
                },
                {
                  "title": "Page 2 : Mécanismes & Implémentation Pratique",
                  "content": "Explication approfondie...",
                  "codeSnippet": "// Exemple de code pratique"
                },
                {
                  "title": "Page 3 : Pièges & Optimisations",
                  "content": "Conseils d'experts...",
                  "codeSnippet": "// Code recommandé"
                }
              ]
            }
        """.trimIndent()
    }

    private fun buildQuizPrompt(theme: String, level: DifficultyLevel): String {
        return """
            Génère un quiz interactif de 5 questions à choix multiples pour développeurs sur le thème "$theme" au niveau "${level.label}".
            
            Les questions doivent être spécifiquement adaptées au niveau "${level.label}" :
            - Débutant : Questions sur la syntaxe et les définitions.
            - Intermédiaire : Questions sur les résultats de code et les bugs fréquents.
            - Avancé : Questions sur les performances, les effets de bord et la conception système.

            La réponse DOIT être un objet JSON valide avec la structure exacte suivante :
            {
              "title": "Quiz : $theme (${level.label})",
              "questions": [
                {
                  "id": 1,
                  "prompt": "Question technique précise ?",
                  "options": ["Option A", "Option B", "Option C", "Option D"],
                  "correctAnswerIndex": 0,
                  "explanation": "Explication pédagogique complète."
                }
              ]
            }
        """.trimIndent()
    }

    private fun parseLessonJson(rawJson: String, theme: String, level: DifficultyLevel): Lesson {
        val cleanJson = cleanJsonString(rawJson)
        val lesson = jsonParser.decodeFromString<Lesson>(cleanJson)
        
        val rawSections = if (lesson.sections.isEmpty()) {
            listOf(
                LessonSection(
                    title = "Contenu de la leçon",
                    content = lesson.content.ifBlank { "Notions clés pour $theme." }
                )
            )
        } else {
            lesson.sections
        }

        val processedSections = rawSections.map { section ->
            var snippet = section.codeSnippet?.trim() ?: ""
            var content = section.content

            if (snippet.isBlank() && content.contains("```")) {
                val extracted = extractCodeBlockFromText(content)
                if (extracted.isNotBlank()) {
                    snippet = extracted
                    content = removeCodeBlocksFromText(content)
                }
            }

            snippet = cleanCodeSnippet(snippet)

            section.copy(
                content = content.trim(),
                codeSnippet = snippet.ifBlank { null }
            )
        }

        return lesson.copy(theme = theme, level = level.label, sections = processedSections)
    }

    private fun parseQuizJson(rawJson: String, theme: String, level: DifficultyLevel): Quiz {
        val cleanJson = cleanJsonString(rawJson)
        return jsonParser.decodeFromString<Quiz>(cleanJson).copy(theme = theme, level = level.label)
    }

    private fun cleanJsonString(input: String): String {
        return input.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            // Clean control characters that sometimes break JSON parsers if LLMs output raw line breaks instead of \n
            .replace(Regex("(?<!\\\\)\\\\(?![\"\\\\/bfnrtu])"), "\\\\\\\\")
    }

    private fun extractCodeBlockFromText(text: String): String {
        val regex = Regex("```(?:[a-zA-Z0-9#+-]+)?\\s*([\\s\\S]*?)```")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun removeCodeBlocksFromText(text: String): String {
        val regex = Regex("```(?:[a-zA-Z0-9#+-]+)?\\s*[\\s\\S]*?```")
        return text.replace(regex, "").trim()
    }

    private fun cleanCodeSnippet(snippet: String): String {
        return snippet
            .removePrefix("```kotlin")
            .removePrefix("```java")
            .removePrefix("```python")
            .removePrefix("```javascript")
            .removePrefix("```typescript")
            .removePrefix("```rust")
            .removePrefix("```cpp")
            .removePrefix("```sql")
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun getDemoLesson(theme: String, level: DifficultyLevel, seed: Int = 1): Lesson {
        val baseLesson = getBaseDemoLesson(theme, level)
        val variantIndex = (seed % 3).coerceAtLeast(0)

        if (variantIndex == 0) return baseLesson

        val variantSuffix = if (variantIndex == 1) "(Variante B - Cas Pratiques)" else "(Variante C - Deep Dive)"
        val updatedSections = baseLesson.sections.mapIndexed { _, sec ->
            sec.copy(
                title = "${sec.title} - $variantSuffix",
                content = "${sec.content}\n\n*Note de révision $variantSuffix : Analysez attentivement l'extrait de code ci-dessous pour identifier les schémas d'exécution.*"
            )
        }

        return baseLesson.copy(
            title = "${baseLesson.title} $variantSuffix",
            sections = updatedSections
        )
    }

    private fun getBaseDemoLesson(theme: String, level: DifficultyLevel): Lesson {
        val themeKey = theme.lowercase()
        val levelText = level.label

        return when {
            themeKey.contains("sql") -> getSqlDemoLesson(theme, levelText, level)
            themeKey.contains("react") -> getReactDemoLesson(theme, levelText, level)
            themeKey.contains("next") -> getNextJsDemoLesson(theme, levelText, level)
            themeKey.contains("nest") -> getNestJsDemoLesson(theme, levelText, level)
            themeKey.contains("rust") -> getRustDemoLesson(theme, levelText, level)
            themeKey.contains("python") -> getPythonDemoLesson(theme, levelText, level)
            themeKey.contains("java") && !themeKey.contains("script") -> getJavaDemoLesson(theme, levelText, level)
            themeKey.contains("kotlin") -> getKotlinDemoLesson(theme, levelText, level)
            themeKey.contains("script") -> getTypeScriptDemoLesson(theme, levelText, level)
            themeKey.contains("node") -> getNodeJsDemoLesson(theme, levelText, level)
            themeKey.contains("c++") || themeKey.contains("cpp") -> getCppDemoLesson(theme, levelText, level)
            themeKey.contains("go") -> getGoDemoLesson(theme, levelText, level)
            themeKey.contains("swift") -> getSwiftDemoLesson(theme, levelText, level)
            themeKey.contains("docker") -> getDockerDemoLesson(theme, levelText, level)
            themeKey.contains("git") -> getGitDemoLesson(theme, levelText, level)
            themeKey.contains("arch") || themeKey.contains("pattern") -> getArchDemoLesson(theme, levelText, level)
            else -> getGenericDemoLesson(theme, levelText, level)
        }
    }

    private fun getSqlDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "SQL : Requetes de Base & Filtres ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la syntaxe SQL fondamentale : SELECT, WHERE, ORDER BY, INSERT, UPDATE et DELETE.",
                keyPoints = listOf(
                    "Interroger les données d'une table avec SELECT et WHERE.",
                    "Trier et limiter les résultats avec ORDER BY et LIMIT.",
                    "Insérer, modifier et supprimer des données (DML).",
                    "Comprendre le rôle des Clés Primaires (Primary Keys)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Interrogation de Données (SELECT)",
                        content = "La commande **SELECT** permet d'extraire des colonnes spécifiques d'une table. La clause **WHERE** filtre les lignes selon une condition.",
                        codeSnippet = """
                            SELECT nom, email, age
                            FROM utilisateurs
                            WHERE age >= 18 AND ville = 'Paris'
                            ORDER BY nom ASC
                            LIMIT 10;
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Modification de Données (INSERT, UPDATE, DELETE)",
                        content = "Utilisez **INSERT INTO** pour ajouter des lignes, **UPDATE** pour modifier des valeurs existantes, et **DELETE** pour supprimer.",
                        codeSnippet = """
                            -- Insertion
                            INSERT INTO utilisateurs (nom, email) VALUES ('Alice', 'alice@email.com');

                            -- Modification
                            UPDATE utilisateurs SET ville = 'Lyon' WHERE id = 1;

                            -- Suppression
                            DELETE FROM utilisateurs WHERE id = 1;
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Clé Primaire & Unicité",
                        content = "La **PRIMARY KEY** identifie de manière unique chaque enregistrement dans une table. Elle ne peut jamais être NULL ni dupliquée.",
                        codeSnippet = """
                            CREATE TABLE utilisateurs (
                                id SERIAL PRIMARY KEY,
                                nom VARCHAR(100) NOT NULL,
                                email VARCHAR(255) UNIQUE NOT NULL
                            );
                        """.trimIndent()
                    )
                )
            )

            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "SQL : Jointures, Regroupements & Agrégations ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez les jointures relationnelles (INNER/LEFT JOIN), les fonctions d'agrégation et le regroupement GROUP BY / HAVING.",
                keyPoints = listOf(
                    "Associer plusieurs tables relationnelles avec INNER JOIN et LEFT JOIN.",
                    "Calculer des statistiques avec COUNT, SUM, AVG, MIN, MAX.",
                    "Regrouper les données filtrées avec GROUP BY et HAVING.",
                    "Comprendre les Clés Étrangères (Foreign Keys) et l'intégrité référentielle."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Jointures Relationnelles (INNER & LEFT JOIN)",
                        content = "**INNER JOIN** ne retourne que les lignes ayant une correspondance dans les deux tables. **LEFT JOIN** conserve toutes les lignes de la table de gauche, même sans correspondance.",
                        codeSnippet = """
                            SELECT u.nom, c.id AS commande_id, c.montant_total
                            FROM utilisateurs u
                            LEFT JOIN commandes c ON u.id = c.utilisateur_id
                            WHERE c.montant_total > 100.0;
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Agrégations & GROUP BY / HAVING",
                        content = "**GROUP BY** rassemble les lignes ayant des valeurs identiques. **HAVING** permet de filtrer les groupes calculés (contrairement à WHERE qui filtre avant regroupement).",
                        codeSnippet = """
                            SELECT u.ville, COUNT(c.id) AS total_commandes, AVG(c.montant_total) AS panier_moyen
                            FROM utilisateurs u
                            JOIN commandes c ON u.id = c.utilisateur_id
                            GROUP BY u.ville
                            HAVING COUNT(c.id) >= 5
                            ORDER BY panier_moyen DESC;
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Clés Étrangères & Contraintes",
                        content = "Une **FOREIGN KEY** établit un lien de dépendance entre deux tables et garantit que vous ne pouvez pas créer de commande pour un utilisateur inexistant.",
                        codeSnippet = """
                            CREATE TABLE commandes (
                                id SERIAL PRIMARY KEY,
                                utilisateur_id INT REFERENCES utilisateurs(id) ON DELETE CASCADE,
                                montant_total DECIMAL(10, 2) NOT NULL
                            );
                        """.trimIndent()
                    )
                )
            )

            DifficultyLevel.AVANCE -> Lesson(
                title = "SQL : Indexation, Transactions ACID & Window Functions ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Passez au niveau expert SQL : optimisation par Index (B-Tree), plans d'exécution EXPLAIN, transactions ACID et Window Functions.",
                keyPoints = listOf(
                    "Analyser et optimiser les requêtes avec EXPLAIN ANALYZE.",
                    "Créer des Index adaptés (B-Tree, Partial, Composite).",
                    "Garantir la cohérence des données avec les Transactions ACID.",
                    "Calculer des classements et cumuls avec les Window Functions (OVER, PARTITION BY)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Indexation B-Tree & EXPLAIN ANALYZE",
                        content = "Les index évitent les parcours complets de table (Sequential Scans). Utilisez **EXPLAIN ANALYZE** pour visualiser le coût en E/S et l'utilisation de l'index par le Query Planner.",
                        codeSnippet = """
                            -- Analyse du plan d'exécution
                            EXPLAIN ANALYZE
                            SELECT * FROM utilisateurs WHERE email = 'dev@test.com';

                            -- Création d'un index composite
                            CREATE INDEX idx_users_ville_statut ON utilisateurs(ville, statut);
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Window Functions (OVER / PARTITION BY)",
                        content = "Les **Window Functions** effectuent des calculs sur un ensemble de lignes associées à la ligne courante sans regrouper les lignes en un seul résultat.",
                        codeSnippet = """
                            SELECT nom, departement, salaire,
                                   ROW_NUMBER() OVER(PARTITION BY departement ORDER BY salaire DESC) as rang_salaire,
                                   AVG(salaire) OVER(PARTITION BY departement) as moyenne_dept
                            FROM employes;
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Transactions ACID & Niveaux d'Isolement",
                        content = "Les transactions (`BEGIN`, `COMMIT`, `ROLLBACK`) garantissent qu'une série d'opérations s'exécute intégralement ou pas du tout (Atomicité).",
                        codeSnippet = """
                            BEGIN TRANSACTION;

                            UPDATE comptes SET solde = solde - 500 WHERE id = 10;
                            UPDATE comptes SET solde = solde + 500 WHERE id = 20;

                            -- En cas d'erreur: ROLLBACK;
                            COMMIT;
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getReactDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "React.js : Composants & useState ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Apprenez les bases de React, la création de composants fonctionnels et la gestion de l'état local avec useState.",
                keyPoints = listOf(
                    "Comprendre le fonctionnement de JSX et des composants fonctionnels.",
                    "Gérer l'état d'un composant avec le hook useState.",
                    "Passer des données aux composants enfants via les Props.",
                    "Réagir aux événements utilisateurs (onClick, onChange)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Composants Fonctionnels & JSX",
                        content = "En React, la UI est découpée en **composants fonctionnels**. Le **JSX** vous permet d'écrire une syntaxe semblable à HTML directement au sein de JavaScript.",
                        codeSnippet = """
                            export function Greeting({ name }: { name: string }) {
                              return <h2>Bonjour, {name} !</h2>;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Gestion d'État avec useState",
                        content = "Pour qu'un composant réagisse aux actions utilisateur et se mette à jour, on utilise le hook **useState**. Chaque modification d'état déclenche un re-render du composant.",
                        codeSnippet = """
                            import { useState } from 'react';

                            export function Compteur() {
                              const [count, setCount] = useState(0);
                              return (
                                <button onClick={() => setCount(count + 1)}>
                                  Cliqué {count} fois
                                </button>
                              );
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Règle d'or en React Débutant",
                        content = "Ne modifiez **jamais** directement la variable d'état ! Utilisez toujours la fonction de mise à jour dédiée (`setCount(...)`).",
                        codeSnippet = """
                            // ❌ Mauvais : count = count + 1
                            // ✅ Bon : setCount(prev => prev + 1)
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "React.js : useEffect, Custom Hooks & Performance ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez le cycle de vie avec useEffect, la création de Custom Hooks réutilisables et l'optimisation des re-renders.",
                keyPoints = listOf(
                    "Gérer les effets secondaires (API, timers) avec useEffect.",
                    "Comprendre le tableau de dépendances de useEffect.",
                    "Créer des Custom Hooks pour encapsuler la logique métier.",
                    "Mémoriser les calculs coûteux avec useMemo et useCallback."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Effets Secondaires avec useEffect",
                        content = "Le hook **useEffect** exécute du code après le rendu. Le tableau de dépendances détermine quand l'effet s'exécute à nouveau.",
                        codeSnippet = """
                            useEffect(() => {
                              const controller = new AbortController();
                              fetch('/api/user', { signal: controller.signal })
                                .then(res => res.json())
                                .then(setData);

                              return () => controller.abort(); // Cleanup
                            }, []);
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Créer un Custom Hook Réutilisable",
                        content = "Un Custom Hook est une fonction dont le nom commence par 'use'. Il vous permet de partager de la logique d'état entre plusieurs composants.",
                        codeSnippet = """
                            function useFetch<T>(url: string) {
                              const [data, setData] = useState<T | null>(null);
                              useEffect(() => {
                                fetch(url).then(r => r.json()).then(setData);
                              }, [url]);
                              return data;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Éviter les Re-renders Inutiles",
                        content = "Utilisez **useCallback** pour stabiliser les références de fonctions passées en props à des composants enfants mémorisés.",
                        codeSnippet = """
                            const handleClick = useCallback(() => {
                              console.log('Action stabilisée');
                            }, []);
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "React.js : Virtual DOM, Reconciliation & RSC ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Plongez dans l'algorithme de Reconciliation (Fiber), la gestion d'état globale complexe et les React Server Components (RSC).",
                keyPoints = listOf(
                    "Comprendre l'architecture React Fiber et la reconciliation.",
                    "Distinguer Server Components (RSC) et Client Components.",
                    "Gérer l'état complexe avec useReducer / Context ou Zustand.",
                    "Utiliser useTransition et useDeferredValue pour la réactivité."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. React Fiber & Diffing Algorithm",
                        content = "React Fiber est le moteur de réconciliation réécrit pour permettre le découpage des rendus en priorités. L'algorithme de diffing compare les arbres d'éléments virtuels O(n).",
                        codeSnippet = """
                            // Concurrent Feature : ne pas bloquer l'UI
                            const [isPending, startTransition] = useTransition();
                            startTransition(() => {
                              setFilterQuery(heavyQuery);
                            });
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Server vs Client Components",
                        content = "Les **Server Components** sont exécutés exclusivement sur le serveur. Ils réduisent la taille du bundle client et accèdent directement aux bases de données.",
                        codeSnippet = """
                            // Composant Serveur (par défaut dans Next.js App Router)
                            export async function UserList() {
                              const users = await db.users.findMany();
                              return <ul>{users.map(u => <li key={u.id}>{u.name}</li>)}</ul>;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Gestion d'État Avancée (useReducer)",
                        content = "Pour une logique d'état complexe avec plusieurs sous-valeurs, préférez **useReducer** au lieu de useState multiples.",
                        codeSnippet = """
                            const [state, dispatch] = useReducer(reducer, { count: 0 });
                            dispatch({ type: 'INCREMENT' });
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getRustDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Rust : Ownership, Borrowing & Mutabilité ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Comprenez la fonctionnalité phare de Rust : la gestion mémoire via l'Ownership sans Garbage Collector.",
                keyPoints = listOf(
                    "Chaque valeur dans Rust possède un unique propriétaire (Owner).",
                    "Les variables sont immuables par défaut (let mut pour modifier).",
                    "L'Ownership est transféré lors d'un Move.",
                    "L'emprunt (Borrowing) permet d'accéder aux données sans transfert."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Les Règles de l'Ownership",
                        content = "En Rust, quand le propriétaire d'une valeur sort du scope, la mémoire est automatiquement libérée (Drop).",
                        codeSnippet = """
                            fn main() {
                                let s1 = String::from("Rust");
                                let s2 = s1; // Ownership déplacé (Move) ! s1 n'est plus valide.
                                println!("{}", s2);
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Borrowing (Emprunt) & Références",
                        content = "Plutôt que d'abandonner l'ownership, vous pouvez prêter une valeur via une référence immuable (&) ou mutable (&mut).",
                        codeSnippet = """
                            fn calculer_longueur(s: &String) -> usize {
                                s.len() // Emprunt immuable
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Règle du Borrow Checker",
                        content = "Vous pouvez avoir soit **plusieurs références immuables (&)**, soit **une seule référence mutable (&mut)** à un instant T.",
                        codeSnippet = """
                            let mut txt = String::from("Hello");
                            let r1 = &mut txt;
                            r1.push_str(", World!");
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Rust : Pattern Matching, Structs & Traits ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la gestion robuste des erreurs avec Option/Result, la déclaration de structures et l'utilisation des Traits.",
                keyPoints = listOf(
                    "Manipuler Option<T> et Result<T, E> sans null/exceptions.",
                    "Déstructurer avec la puissance du match.",
                    "Définir des structures et implémenter des méthodes (impl).",
                    "Définir des comportements partagés avec les Traits."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Gestion d'Erreurs : Option & Result",
                        content = "Rust n'a pas de valeur 'null' ni d'exceptions. Tout résultat optionnel utilise `Option<T>` et tout échec potentiel utilise `Result<T, E>`.",
                        codeSnippet = """
                            fn diviser(a: f64, b: f64) -> Result<f64, String> {
                                if b == 0.0 {
                                    Err(String::from("Division par zéro"))
                                } else {
                                    Ok(a / b)
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Pattern Matching Explicite",
                        content = "L'instruction `match` est exhaustive : vous devez traiter tous les cas possibles.",
                        codeSnippet = """
                            match diviser(10.0, 2.0) {
                                Ok(res) => println!("Résultat: {}", res),
                                Err(e) => println!("Erreur: {}", e),
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Polymorphisme via les Traits",
                        content = "Les **Traits** définissent une interface commune. Ils remplacent l'héritage de classes classique.",
                        codeSnippet = """
                            trait Affichable { fn afficher(&self); }
                            impl Affichable for String {
                                fn afficher(&self) { println!("{}", self); }
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Rust : Lifetimes, Smart Pointers & Concurrence ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Abordez les annotations de Lifetimes ('a), les pointeurs intelligents (Box, Rc, Arc) et la concurrence sécurisée (Fearless Concurrency).",
                keyPoints = listOf(
                    "Annoter explicitement les Lifetimes ('a) pour le compilateur.",
                    "Allouer sur le Heap avec Box<T>.",
                    "Partager des références sur plusieurs threads avec Arc<Mutex<T>>.",
                    "Utiliser le multithreading garanti sans Data Races au compile-time."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Les Annotations de Lifetimes ('a)",
                        content = "Le compilateur vérifie la durée de vie des références pour éviter les pointeurs pendantes (Dangling pointers).",
                        codeSnippet = """
                            fn plus_long<'a>(x: &'a str, y: &'a str) -> &'a str {
                                if x.len() > y.len() { x } else { y }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Smart Pointers (Arc & Mutex)",
                        content = "`Arc<T>` (Atomic Reference Counting) combiné à `Mutex<T>` permet de partager et muter des données entre plusieurs threads en toute sécurité.",
                        codeSnippet = """
                            use std::sync::{Arc, Mutex};
                            use std::thread;

                            let compteur = Arc::new(Mutex::new(0));
                            let c_clone = Arc::clone(&compteur);
                            thread::spawn(move || {
                                let mut num = c_clone.lock().unwrap();
                                *num += 1;
                            });
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Safe vs Unsafe Rust",
                        content = "Le bloc `unsafe` vous permet d'effectuer des opérations bas niveau (déréférencement de pointeurs bruts) tout en contenant le risque.",
                        codeSnippet = "// Maîtrise des concepts avancés Rust validée !"
                    )
                )
            )
        }
    }

    private fun getNextJsDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Next.js : App Router & Composants ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez l'architecture Next.js App Router, la création de routes par dossiers et les Server Components.",
                keyPoints = listOf(
                    "Comprendre l'organisation du dossier app/ dans Next.js.",
                    "Créer des routes avec page.tsx et des layouts partagés avec layout.tsx.",
                    "Utiliser le composant <Link> pour la navigation côté client.",
                    "Comprendre la différence entre Server et Client Components ('use client')."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Routage basé sur les dossiers",
                        content = "Dans Next.js App Router, chaque dossier dans `app/` crée un segment de route. Le fichier `page.tsx` rend la UI de la route.",
                        codeSnippet = """
                            // app/dashboard/page.tsx
                            import Link from 'next/link';

                            export default function DashboardPage() {
                              return (
                                <div>
                                  <h1>Tableau de Bord</h1>
                                  <Link href="/settings">Paramètres</Link>
                                </div>
                              );
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Server vs Client Components ('use client')",
                        content = "Par défaut, tous les composants de `app/` sont des **Server Components**. Pour utiliser du state ou des écouteurs d'événements, ajoutez `'use client'` au sommet du fichier.",
                        codeSnippet = """
                            'use client';
                            import { useState } from 'react';

                            export function BoutonLike() {
                              const [likes, setLikes] = useState(0);
                              return <button onClick={() => setLikes(likes + 1)}>👍 {likes}</button>;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Rendu & Performance Initiales",
                        content = "Les Server Components réduisent la taille du JavaScript envoyé au navigateur et améliorent le temps de chargement initial (FCP).",
                        codeSnippet = "// Le HTML est pré-rendu sur le serveur pour une vitesse optimale."
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Next.js : Server Actions, Dynamic Routes & Caching ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez les mutations de données avec Server Actions, les routes dynamiques [id] et la stratégie de cache Next.js.",
                keyPoints = listOf(
                    "Utiliser les Server Actions ('use server') pour muter les données.",
                    "Créer des routes dynamiques avec [id] et récupérer les paramètres.",
                    "Configurer le cache fetch et la revalidation (revalidate).",
                    "Gérer les états de chargement avec loading.tsx et error.tsx."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Server Actions ('use server')",
                        content = "Les **Server Actions** permettent d'exécuter des fonctions asynchrones directement sur le serveur sans écrire d'endpoint API REST séparé.",
                        codeSnippet = """
                            // app/actions.ts
                            'use server';
                            import { revalidatePath } from 'next/cache';

                            export async function ajouterArticle(formData: FormData) {
                              const titre = formData.get('titre');
                              await db.post.create({ data: { titre } });
                              revalidatePath('/blog');
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Routes Dynamiques [id]",
                        content = "Un dossier nommé `[id]` capture des paramètres d'URL dynamiques transmis à la prop `params` du composant.",
                        codeSnippet = """
                            // app/blog/[slug]/page.tsx
                            export default async function PostPage({ params }: { params: { slug: string } }) {
                              const post = await getPost(params.slug);
                              return <article><h1>{post.title}</h1></article>;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Stratégie de Cache Fetch",
                        content = "Next.js étend la fonction native `fetch` pour offrir une mise en cache automatique et une revalidation à la demande.",
                        codeSnippet = """
                            const res = await fetch('https://api.example.com/data', {
                              next: { revalidate: 3600 } // Revalide chaque heure
                            });
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Next.js : Parallel Routes, Middleware & ISR ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Explorez les fonctionnalités avancées Next.js : Middleware global, Parallel & Intercepting Routes, Edge Runtime et ISR.",
                keyPoints = listOf(
                    "Rédiger des Middlewares globaux (middleware.ts) pour la sécurité.",
                    "Construire des dashboards multi-panneaux avec les Parallel Routes (@slot).",
                    "Intercepter des routes pour des modales d'URL (@modal).",
                    "Optimiser l'ISR (Incremental Static Regeneration) à grande échelle."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Middleware Global (middleware.ts)",
                        content = "Le fichier `middleware.ts` s'exécute avant que la requête n'atteigne le routeur. Il permet de gérer l'authentification et les redirections.",
                        codeSnippet = """
                            import { NextResponse } from 'next/server';
                            import type { NextRequest } from 'next/server';

                            export function middleware(req: NextRequest) {
                              const token = req.cookies.get('token');
                              if (!token && req.nextUrl.pathname.startsWith('/dashboard')) {
                                return NextResponse.redirect(new URL('/login', req.url));
                              }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Parallel Routes (@analytics)",
                        content = "Les Parallel Routes permettent d'afficher simultanément plusieurs pages indépendantes dans un même layout grâce aux 'slots' (@dossier).",
                        codeSnippet = """
                            // app/dashboard/layout.tsx
                            export default function Layout({ children, analytics, team }) {
                              return (
                                <div>
                                  {children}
                                  <div className="flex">{analytics}{team}</div>
                                </div>
                              );
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Optimisation Edge Runtime",
                        content = "Exécutez vos fonctions légères au plus proche des utilisateurs mondiaux grâce à l'Edge Runtime Vercel/Next.js.",
                        codeSnippet = """
                            export const runtime = 'edge';
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getNestJsDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "NestJS : Contrôleurs, Services & Modules ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Apprenez les bases de NestJS : la structure modulaire, les contrôleurs HTTP et l'injection de services.",
                keyPoints = listOf(
                    "Comprendre le rôle de l'architecture découplée NestJS.",
                    "Déclarer des routes avec les contrôleurs (@Controller, @Get, @Post).",
                    "Encapsuler la logique métier dans des services (@Injectable).",
                    "Organiser l'application en modules (@Module)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Contrôleurs & Décorateurs HTTP",
                        content = "Les contrôleurs gèrent les requêtes HTTP entrantes et retournent une réponse au client.",
                        codeSnippet = """
                            import { Controller, Get, Param } from '@nestjs/common';

                            @Controller('produits')
                            export class ProduitsController {
                              @Get(':id')
                              getProduit(@Param('id') id: string) {
                                return { id, nom: 'Clavier mécanique' };
                              }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Services & Injection de Dépendances",
                        content = "Les services contiennent la logique métier de l'application. Le décorateur `@Injectable()` permet à NestJS d'injecter automatiquement le service dans le contrôleur.",
                        codeSnippet = """
                            @Injectable()
                            export class ProduitsService {
                              private produits = [{ id: '1', nom: 'Ecran 4K' }];
                              find() { return this.produits; }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Modules NestJS (@Module)",
                        content = "Chaque application NestJS possède au moins un module racine (AppModule) qui regroupe contrôleurs et providers.",
                        codeSnippet = """
                            @Module({
                              controllers: [ProduitsController],
                              providers: [ProduitsService],
                            })
                            export class ProduitsModule {}
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "NestJS : Validation DTO, Pipes & Exception Filters ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Validez les requêtes entrantes avec les DTOs, transformez les données via Pipes et gérez les erreurs de façon centralisée.",
                keyPoints = listOf(
                    "Créer des DTOs validés automatiquement avec class-validator.",
                    "Activer le ValidationPipe globalement.",
                    "Transformer les types d'arguments avec les Pipes (ParseIntPipe).",
                    "Centraliser la réponse d'erreur avec les Exception Filters."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Validation de Données avec DTO",
                        content = "Un DTO (Data Transfer Object) définit le schéma attendu dans le corps d'une requête HTTP.",
                        codeSnippet = """
                            import { IsString, IsEmail, MinLength } from 'class-validator';

                            export class CreateUserDto {
                              @IsString() nom: string;
                              @IsEmail() email: string;
                              @MinLength(8) motDePasse: string;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Transformation avec Pipes",
                        content = "Les **Pipes** valident ou transforment les données d'entrée (ex: convertir une chaîne d'URL en entier).",
                        codeSnippet = """
                            @Get(':id')
                            findOne(@Param('id', ParseIntPipe) id: number) {
                              return this.service.findById(id);
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Filtres d'Exceptions Centralisés",
                        content = "Les Exception Filters attrapent les erreurs non traitées pour renvoyer une réponse JSON normalisée au client.",
                        codeSnippet = """
                            throw new NotFoundException('Utilisateur introuvable');
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "NestJS : Guards, Interceptors, Microservices & gRPC ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Passez au niveau supérieur : Guards d'autorisation JWT, Intercepteurs RxJS, architecture Microservices et gRPC.",
                keyPoints = listOf(
                    "Protéger les endpoints avec les Guards (JWT / Roles).",
                    "Intercepter et modifier les requêtes/réponses avec RxJS.",
                    "Découpler les services via RabbitMQ ou Kafka.",
                    "Implémenter une communication ultra-rapide avec gRPC."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Guards d'Autorisation (CanActivate)",
                        content = "Les **Guards** déterminent si une requête donnée sera traitée ou refusée en fonction des rôles ou jetons JWT.",
                        codeSnippet = """
                            @Injectable()
                            export class RolesGuard implements CanActivate {
                              canActivate(context: ExecutionContext): boolean {
                                const request = context.switchToHttp().getRequest();
                                return request.user?.role === 'ADMIN';
                              }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Intercepteurs RxJS",
                        content = "Les intercepteurs entourent l'exécution de la méthode du contrôleur pour mesurer le temps de réponse ou ajouter du cache.",
                        codeSnippet = """
                            @Injectable()
                            export class LoggingInterceptor implements NestInterceptor {
                              intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
                                const now = Date.now();
                                return next.handle().pipe(tap(() => console.log(`Mis ${'$'}{Date.now() - now}ms`)));
                              }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Microservices & gRPC",
                        content = "NestJS prend en charge nativement le pattern microservices avec gRPC pour une communication binaire entre services.",
                        codeSnippet = "// Architecture microservices NestJS d'entreprise validée !"
                    )
                )
            )
        }
    }

    private fun getPythonDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Python : Syntaxe, Structures & Fonctions ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Apprenez les bases de Python : types de données, Listes, Dictionnaires, boucles et écriture de fonctions simples.",
                keyPoints = listOf(
                    "Déclarer des variables et utiliser le typage dynamique Python.",
                    "Manipuler les Listes et Dictionnaires.",
                    "Écrire des boucles for/while et conditions if/else.",
                    "Définir des fonctions réutilisables avec def."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Listes & Dictionnaires",
                        content = "Les listes ordered et les dictionnaires clé-valeur sont les structures fondamentales de Python.",
                        codeSnippet = """
                            utilisateurs = ["Alice", "Bob", "Charlie"]
                            score = {"Alice": 95, "Bob": 80}

                            print(f"{utilisateurs[0]} a un score de {score['Alice']}")
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Boucles & Fonctions",
                        content = "En Python, l'indentation définit les blocs de code. Les fonctions sont déclarées avec le mot-clé `def`.",
                        codeSnippet = """
                            def saluer_tout_le_monde(noms):
                                for nom in noms:
                                    print(f"Bonjour {nom} !")

                            saluer_tout_le_monde(utilisateurs)
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Manipulation de Fichiers",
                        content = "Utilisez l'instruction `with` pour lire et écrire dans des fichiers en toute sécurité.",
                        codeSnippet = """
                            with open("logs.txt", "w") as f:
                                f.write("Session démarrée\n")
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Python : Comprehensions, Decorators & Dataclasses ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Écrivez du code Python concis et idiomatique (Pythonic) : List Comprehensions, Décorateurs et Dataclasses.",
                keyPoints = listOf(
                    "Transformer des listes avec les List & Dict Comprehensions.",
                    "Créer des Décorateurs de fonctions (@decorator).",
                    "Définir des objets de données avec @dataclass.",
                    "Utiliser le Type Hinting pour la vérification statique."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. List & Dict Comprehensions",
                        content = "Les comprehensions offrent une syntaxe puissante et rapide pour transformer des listes.",
                        codeSnippet = """
                            nombres = [1, 2, 3, 4, 5, 6]
                            carres_pairs = [x**2 for x in nombres if x % 2 == 0]
                            # [4, 16, 36]
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Décorateurs (@decorator)",
                        content = "Un décorateur modifie ou enrichit le comportement d'une fonction sans en altérer le code source.",
                        codeSnippet = """
                            import time

                            def minuterie(func):
                                def wrapper(*args, **kwargs):
                                    t0 = time.time()
                                    res = func(*args, **kwargs)
                                    print(f"Exécuté en {time.time() - t0:.4f}s")
                                    return res
                                return wrapper

                            @minuterie
                            def traiter():
                                sum(range(1000000))
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Dataclasses (Python 3.7+)",
                        content = "Le décorateur `@dataclass` génère automatiquement `__init__`, `__repr__` et `__eq__`.",
                        codeSnippet = """
                            from dataclasses import dataclass

                            @dataclass
                            class Produit:
                                id: int
                                nom: str
                                prix: float
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Python : Asyncio, Metaclasses & GIL ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Comprenez les rouages internes de Python : l'Event Loop asyncio, les Métaclasses et l'impact du GIL sur le multithreading.",
                keyPoints = listOf(
                    "Exécuter des I/O concurrentes avec asyncio (async/await).",
                    "Comprendre le GIL (Global Interpreter Lock) et choisir multiprocessing vs threading.",
                    "Personnaliser la création de classes via les Métaclasses.",
                    "Profilage mémoire et optimisation des générateurs (yield)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Programmation Asynchrone avec asyncio",
                        content = "`asyncio` permet d'exécuter des milliers de requêtes I/O simultanées sur un seul thread.",
                        codeSnippet = """
                            import asyncio

                            async def fetch_api(url):
                                await asyncio.sleep(1) # Simulation I/O
                                return f"Données de {url}"

                            async def main():
                                res = await asyncio.gather(
                                    fetch_api("api_1"),
                                    fetch_api("api_2")
                                )
                                print(res)

                            asyncio.run(main())
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Le GIL & Multiprocessing",
                        content = "En CPython, le **GIL** empêche l'exécution parallèle de bytecode sur plusieurs cœurs CPU. Pour du calcul intensif, utilisez `multiprocessing` au lieu de `threading`.",
                        codeSnippet = """
                            from multiprocessing import Pool

                            def calcul_lourd(n):
                                return n * n

                            if __name__ == '__main__':
                                with Pool(4) as p:
                                    print(p.map(calcul_lourd, [1, 2, 3, 4]))
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Métaclasses Python",
                        content = "Les métaclasses sont la 'classe d'une classe'. Elles permettent d'intercepter la création des classes lors de l'importation.",
                        codeSnippet = "// Maîtrise avancée des internals Python validée !"
                    )
                )
            )
        }
    }

    private fun getJavaDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Java : POO, Syntaxe & Collections ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la syntaxe orientée objet de Java : classes, objets, méthodes, boucles et ArrayList.",
                keyPoints = listOf(
                    "Comprendre la structure d'un programme Java (public class, main).",
                    "Instancier des objets et encapsuler les variables (private/getters/setters).",
                    "Utiliser les collections de base (ArrayList, HashMap).",
                    "Gérer les exceptions simples avec try/catch."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Classes & Encapsulation",
                        content = "Tout code Java réside dans une classe. L'encapsulation protège les champs internes via des visibilités `private`.",
                        codeSnippet = """
                            public class Etudiant {
                                private String nom;
                                private double note;

                                public Etudiant(String nom, double note) {
                                    this.nom = nom;
                                    this.note = note;
                                }

                                public String getNom() { return nom; }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Collections (ArrayList & HashMap)",
                        content = "Les collections gèrent des ensembles dynamiques d'objets.",
                        codeSnippet = """
                            import java.util.ArrayList;
                            import java.util.List;

                            List<String> cours = new ArrayList<>();
                            cours.add("Java Basics");
                            cours.add("Spring Boot");

                            for (String c : cours) {
                                System.out.println("Cours: " + c);
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Gestion d'Exceptions (try/catch)",
                        content = "Interceptez les erreurs d'exécution pour éviter le plantage de l'application.",
                        codeSnippet = """
                            try {
                                int res = 10 / 0;
                            } catch (ArithmeticException e) {
                                System.err.println("Erreur: " + e.getMessage());
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Java : Streams API, Records & Optionals ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez Java moderne : programmation fonctionnelle avec Streams API, Records immuables et Optionals.",
                keyPoints = listOf(
                    "Filtrer et transformer des collections avec Streams (filter, map, collect).",
                    "Déclarer des classes de données concises avec Records (Java 14+).",
                    "Prévenir les NullPointerExceptions avec Optional.",
                    "Comprendre le principe d'injection de dépendances."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Streams API & Lambdas",
                        content = "La Streams API permet d'effectuer des opérations chaînées déclaratives sur les collections.",
                        codeSnippet = """
                            List<String> personnes = List.of("Alice", "Bob", "Alex", "Charlie");

                            List<String> resultat = personnes.stream()
                                .filter(p -> p.startsWith("A"))
                                .map(String::toUpperCase)
                                .toList();
                            // ["ALICE", "ALEX"]
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Records Java (Java 14+)",
                        content = "Un Record génère automatiquement un constructeur, des getters immuables, `equals` et `hashCode`.",
                        codeSnippet = """
                            public record Client(String id, String nom, String email) {
                                public Client {
                                    Objects.requireNonNull(email, "L'email est obligatoire");
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Éviter Null avec Optional<T>",
                        content = "`Optional` exprime clairement qu me valeur peut être absente sans risquer de NPE.",
                        codeSnippet = """
                            Optional<Client> client = repository.findById("42");
                            String nom = client.map(Client::nom).orElse("Anonyme");
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Java : Mémoire JVM, Virtual Threads & Concurrence ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la JVM : gestion de la mémoire Heap/Stack, Virtual Threads (Project Loom) et profilage de Garbage Collection.",
                keyPoints = listOf(
                    "Comprendre la gestion mémoire JVM (Heap, Stack, Metaspace, GC).",
                    "Utiliser les Virtual Threads (Project Loom, Java 21) pour une concurrence massive.",
                    "Programmer sans verrous avec AtomicReferences & ConcurrentHashMap.",
                    "Analyse de thread dumps et profilage JVM (JProfiler/VisualVM)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Virtual Threads (Java 21 / Project Loom)",
                        content = "Les Virtual Threads sont gérés par le runtime Java au lieu du système d'exploitation, permettant d'exécuter des millions de threads simultanés.",
                        codeSnippet = """
                            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                IntStream.range(0, 10_000).forEach(i -> {
                                    executor.submit(() -> {
                                        Thread.sleep(1000);
                                        return i;
                                    });
                                });
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Collections Concurrentes (ConcurrentHashMap)",
                        content = "Utilisez des collections thread-safe optimisées pour éviter les verrous globaux bloquants.",
                        codeSnippet = """
                            ConcurrentMap<String, Integer> map = new ConcurrentHashMap<>();
                            map.computeIfAbsent("cle", k -> 100);
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Memory Model & volatile",
                        content = "Le mot-clé `volatile` garantit la visibilité directe des écritures en mémoire centrale entre différents threads.",
                        codeSnippet = "// Optimisations JVM et concurrence de pointe validées !"
                    )
                )
            )
        }
    }

    private fun getKotlinDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Kotlin : Syntaxe, Null Safety & Data Classes ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la concision de Kotlin : variables val/var, la sécurité anti-null et les Data Classes.",
                keyPoints = listOf(
                    "Distinguer les variables immuables (val) et muables (var).",
                    "Éliminer les NullPointerExceptions grâce aux types optionnels (?).",
                    "Déclarer des classes de données en une seule ligne (data class).",
                    "Utiliser l'instruction expressive when."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Null Safety (Système Anti-Null)",
                        content = "En Kotlin, le compilateur interdit d'assigner `null` à un type standard. Pour autoriser la valeur nulle, ajoutez un `?`.",
                        codeSnippet = """
                            val nom: String = "Alice" // Impossible d'assigner null
                            val pseudo: String? = null // Ok !

                            val longueur = pseudo?.length ?: 0 // Operateur Elvis
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Data Classes",
                        content = "Une `data class` génère automatiquement `copy()`, `equals()`, `hashCode()` et `toString()`.",
                        codeSnippet = """
                            data class Utilisateur(val id: Int, val nom: String, val email: String)

                            val u1 = Utilisateur(1, "Bob", "bob@test.com")
                            val u2 = u1.copy(nom = "Robert")
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Expressions when",
                        content = "L'instruction `when` est une version suralimentée du switch qui retourne une valeur.",
                        codeSnippet = """
                            val message = when (statut) {
                                200 -> "Succès"
                                404 -> "Introuvable"
                                else -> "Erreur inconnue"
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Kotlin : Coroutines, Extension Functions & Flow ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la programmation asynchrone Kotlin avec les Coroutines, les Flow et les fonctions d'extension.",
                keyPoints = listOf(
                    "Exécuter des tâches asynchrones non-bloquantes avec Coroutines (suspend).",
                    "Basculer entre les threads via les Dispatchers (Main, IO, Default).",
                    "Créer des flux de données réactifs avec Flow et StateFlow.",
                    "Enrichir des classes existantes via les Extension Functions."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Coroutines & Dispatchers",
                        content = "Les coroutines sont des threads légers suspendables. `Dispatchers.IO` est réservé aux requêtes réseau et à la base de données.",
                        codeSnippet = """
                            suspend fun fetchUserData(): User = withContext(Dispatchers.IO) {
                                apiService.getUser()
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Fonctions d'Extension",
                        content = "Les fonctions d'extension permettent d'ajouter de nouvelles méthodes à une classe sans hériter de celle-ci.",
                        codeSnippet = """
                            fun String.estEmailValide(): Boolean {
                                return this.contains("@") && this.contains(".")
                            }

                            val valide = "user@test.com".estEmailValide()
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Flux Réactifs StateFlow",
                        content = "`StateFlow` est un conteneur d'état observable qui émet la dernière valeur enregistrée.",
                        codeSnippet = """
                            private val _count = MutableStateFlow(0)
                            val count: StateFlow<Int> = _count.asStateFlow()
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Kotlin : Generics Reified, Inline Functions & DSLs ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Devenez un expert Kotlin : type erasure contourné avec reified, fonctions inline et création de DSLs personnalisés.",
                keyPoints = listOf(
                    "Conserver les informations de types au runtime grâce à inline & reified.",
                    "Éviter les allocations de Lambdas avec inline functions.",
                    "Créer des DSLs fluides avec les Lambdas avec récepteur.",
                    "Utiliser la délégation de propriétés (by)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Inline Functions & reified Types",
                        content = "En Kotlin, le mot-clé `reified` combiné à `inline` permet d'accéder à la classe exacte d'un type générique T au runtime.",
                        codeSnippet = """
                            inline fun <reified T> Intent.hasExtra(key: String): Boolean {
                                return getParcelableExtra<T>(key) != null
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Créer son propre DSL Kotlin",
                        content = "Grâce aux Lambdas avec récepteur (`T.() -> Unit`), vous pouvez créer une syntaxe déclarative fluide.",
                        codeSnippet = """
                            fun html(init: HTML.() -> Unit): HTML {
                                val html = HTML()
                                html.init()
                                return html
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Délégation de Propriétés (by)",
                        content = "Déléguez la lecture et l'écriture d'une propriété à un objet réutilisable (ex: `by lazy`).",
                        codeSnippet = """
                            val baseDeDonnees: Database by lazy {
                                Database.connect()
                            }
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getTypeScriptDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "TypeScript : Typage Statique, Interfaces & Types ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Apprenez les bases de TypeScript : typage explicite, interfaces, types d'union et fonctions typées.",
                keyPoints = listOf(
                    "Annoter les types primitifs (string, number, boolean, Array).",
                    "Définir des structures d'objets avec interface et type.",
                    "Utiliser les Union Types (|) pour accepter plusieurs valeurs.",
                    "Activer le strictNullChecks pour sécuriser le code."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Annotations de Types & Interfaces",
                        content = "TypeScript ajoute un système de typage statique au-dessus de JavaScript pour détecter les erreurs à la compilation.",
                        codeSnippet = """
                            interface Article {
                              id: number;
                              titre: string;
                              prix: number;
                              enStock?: boolean; // Optionnel
                            }

                            const livre: Article = { id: 1, titre: "TypeScript Guide", prix: 29.99 };
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Union Types & Literals",
                        content = "Les types d'union permettent à une variable de contenir plusieurs types de valeurs spécifiques.",
                        codeSnippet = """
                            type StatutCommande = 'en_attente' | 'expedie' | 'livre';

                            function mettreAJourStatut(id: number, statut: StatutCommande) {
                              console.log(`Commande ${'$'}{id} est ${'$'}{statut}`);
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Typage de Fonctions",
                        content = "Spécifiez toujours les types de paramètres et le type de retour d'une fonction.",
                        codeSnippet = """
                            function additionner(a: number, b: number): number {
                              return a + b;
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "TypeScript : Generics, Type Guards & Narrowing ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Développez des fonctions génériques réutilisables <T>, affinez les types avec Type Guards et maîtrisez le Narrowing.",
                keyPoints = listOf(
                    "Créer des fonctions et classes génériques flexibles (<T>).",
                    "Affinage de types avec Type Guards personnalisés (is).",
                    "Utiliser typeof, instanceof et in pour le narrowing.",
                    "Manipuler les Utility Types de base (Partial, Pick, Omit)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Génériques (<T>)",
                        content = "Les génériques permettent d'écrire du code qui fonctionne avec une variété de types tout en maintenant la sécurité statique.",
                        codeSnippet = """
                            function inverserTableau<T>(items: T[]): T[] {
                              return items.reverse();
                            }

                            const nombres = inverserTableau([1, 2, 3]); // number[]
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Type Guards (Predicate 'is')",
                        content = "Les fonctions Type Guard confirment le type exact d'une variable au runtime.",
                        codeSnippet = """
                            interface Chien { aboyer(): void; }
                            interface Chat { miauler(): void; }

                            function estChien(pet: Chien | Chat): pet is Chien {
                              return (pet as Chien).aboyer !== undefined;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Utility Types (Partial & Pick)",
                        content = "Les utilitaires intégrés transforment des types existants.",
                        codeSnippet = """
                            interface User { id: string; name: string; email: string; }

                            type UserUpdate = Partial<User>; // Tous les champs deviennent optionnels
                            type UserHeader = Pick<User, 'id' | 'name'>; // Garde id et name
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "TypeScript : Mapped Types, Conditional Types & Infer ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Passez au niveau metaprogrammation TypeScript : Conditional Types, Mapped Types, mot-clé infer et Template Literal Types.",
                keyPoints = listOf(
                    "Déclarer des Conditional Types (T extends U ? X : Y).",
                    "Extraire des types dynamiquement avec le mot-clé infer.",
                    "Créer des Mapped Types complexes avec clés réorganisées.",
                    "Valider des formats de chaînes avec Template Literal Types."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Conditional Types & Mot-clé infer",
                        content = "Les types conditionnels sélectionnent un type en fonction d'un test de sous-typage. `infer` permet de capturer un type interne.",
                        codeSnippet = """
                            type ReturnTypeOfFunc<T> = T extends (...args: any[]) => infer R ? R : never;

                            type Resultat = ReturnTypeOfFunc<() => number>; // number
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Template Literal Types",
                        content = "Les types littéraux de modèle permettent de manipuler des chaînes au niveau du système de types.",
                        codeSnippet = """
                            type Event = 'click' | 'hover';
                            type HandlerName = `on${'$'}{Capitalize<Event>}`; // 'onClick' | 'onHover'
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Mapped Types Avancés",
                        content = "Transformez chaque propriété d'un type via des itérations sur clés `[K in keyof T]`.",
                        codeSnippet = """
                            type ReadonlyDeep<T> = {
                              readonly [P in keyof T]: ReadonlyDeep<T[P]>;
                            };
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getCppDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "C++ : Syntaxe, Pointeurs & Références ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la syntaxe C++ : variables, entrées/sorties std::cout, pointeurs (*) et références (&).",
                keyPoints = listOf(
                    "Afficher et lire des données avec std::cout et std::cin.",
                    "Comprendre la différence entre Pointeurs (*) et Références (&).",
                    "Définir des structures de données simples (struct).",
                    "Utiliser les en-têtes (#include) et espaces de noms (namespace std)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Entrées / Sorties (std::cout & std::cin)",
                        content = "En C++, le flux `std::cout` sert à écrire sur la sortie standard et `std::cin` à lire la saisie utilisateur.",
                        codeSnippet = """
                            #include <iostream>

                            int main() {
                                int age = 25;
                                std::cout << "Votre âge : " << age << std::endl;
                                return 0;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Pointeurs (*) vs Références (&)",
                        content = "Un **pointeur** stocke l'adresse mémoire d'une variable. Une **référence** est un alias immuable vers une variable existante.",
                        codeSnippet = """
                            int val = 42;
                            int* ptr = &val; // Pointeur stocke l'adresse
                            int& ref = val;  // Référence alias vers val

                            *ptr = 100; // Modifie val à 100 via le pointeur
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Structures (struct)",
                        content = "Les `struct` en C++ regroupent plusieurs variables sous un même nom d'accès.",
                        codeSnippet = """
                            struct Point {
                                int x;
                                int y;
                            };

                            Point p1{10, 20};
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "C++ : POO, RAII & Smart Pointers ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la POO en C++, le concept RAII et la gestion mémoire sécurisée via std::unique_ptr et std::shared_ptr.",
                keyPoints = listOf(
                    "Appliquer le principe RAII (Resource Acquisition Is Initialization).",
                    "Éviter les fuites mémoire avec std::unique_ptr et std::shared_ptr.",
                    "Utiliser les conteneurs de la STL (std::vector, std::unordered_map).",
                    "Déclarer des méthodes virtuelles pour le polymorphisme."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Principe RAII & Destructeurs",
                        content = "RAII garantit qu'une ressource (mémoire, fichier) acquise lors de la construction d'un objet est libérée automatiquement dans son destructeur (`~Classe`).",
                        codeSnippet = """
                            class FichierHandler {
                            public:
                                FichierHandler() { /* ouvrir fichier */ }
                                ~FichierHandler() { /* fermer fichier automatiquement */ }
                            };
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Smart Pointers (std::unique_ptr)",
                        content = "`std::unique_ptr` détient la propriété exclusive d'une ressource et la libère automatiquement dès qu'il sort du scope.",
                        codeSnippet = """
                            #include <memory>

                            void demo() {
                                auto ptr = std::make_unique<Point>(5, 10);
                                // Pas besoin de delete ! Libération automatique.
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Conteneurs STL (std::vector)",
                        content = "`std::vector` est un tableau dynamique sécurisé de la bibliothèque standard.",
                        codeSnippet = """
                            #include <vector>

                            std::vector<int> nombres = {1, 2, 3};
                            nombres.push_back(4);
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "C++ : Move Semantics, Templates & Concurrence ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Devenez expert C++ : Move Semantics (std::move), Métaprogrammation par Templates et Concurrence multi-thread.",
                keyPoints = listOf(
                    "Comprendre les références rvalue (&&) et le Move Constructor.",
                    "Écrire des fonctions génériques avec Templates & Concepts (C++20).",
                    "Gérer le multithreading avec std::thread, std::mutex et std::atomic.",
                    "Éviter les copies coûteuses grâce au Perfect Forwarding (std::forward)."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Move Semantics (std::move)",
                        content = "La Move Semantics transfère le contenu d'un objet temporaire sans effectuer de copie lourde en mémoire.",
                        codeSnippet = """
                            #include <utility>
                            #include <string>

                            std::string s1 = "Texte très long...";
                            std::string s2 = std::move(s1); // Transfert direct sans allocation
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Templates C++",
                        content = "Les templates permettent la métaprogrammation et la création de fonctions ou classes valables pour tout type.",
                        codeSnippet = """
                            template <typename T>
                            T maximum(T a, T b) {
                                return (a > b) ? a : b;
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Multithreading & std::mutex",
                        content = "Utilisez `std::lock_guard` pour verrouiller un mutex de manière sécurisée selon le principe RAII.",
                        codeSnippet = """
                            #include <mutex>
                            std::mutex mtx;

                            void traitement() {
                                std::lock_guard<std::mutex> lock(mtx);
                                // Section critique sécurisée
                            }
                        """.trimIndent()
                    )
                )
            )
        }
    }

    private fun getGoDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Go : Packages, Structs & Gestion d'Erreurs ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la simplicité et la clarté de Go (Golang) : packages, structures, slices et gestion d'erreurs explicite.",
                keyPoints = listOf(
                    "Déclarer des variables et comprendre le typage statique Go.",
                    "Manipuler les Slices et les Structs.",
                    "Traiter les erreurs de façon explicite (if err != nil).",
                    "Organiser son code en packages."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Variables & Slices",
                        content = "En Go, l'opérateur `:=` déclare et infère le type d'une variable localement. Un **slice** est une vue dynamique sur un tableau.",
                        codeSnippet = """
                            package main
                            import "fmt"

                            func main() {
                                nom := "Gopher"
                                langues := []string{"Go", "Python", "Rust"}
                                fmt.Println(nom, langues[0])
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Structs & Méthodes",
                        content = "Go n'a pas de classes. On définit des structures (`struct`) et on leur associe des méthodes avec un 'receiver'.",
                        codeSnippet = """
                            type Utilisateur struct {
                                ID   int
                                Nom  string
                            }

                            func (u Utilisateur) Afficher() {
                                fmt.Printf("User #%d: %s\n", u.ID, u.Nom)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Gestion d'Erreurs Explicite",
                        content = "En Go, les fonctions renvoient généralement le résultat et une valeur d'erreur en second retour.",
                        codeSnippet = """
                            val, err := diviser(10, 0)
                            if err != nil {
                                fmt.Println("Erreur:", err)
                                return
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Go : Goroutines, Channels & Interfaces ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la concurrence native de Go avec les Goroutines et Channels, ainsi que le polymorphisme par Interfaces implicites.",
                keyPoints = listOf(
                    "Lancer des Goroutines légères avec le mot-clé go.",
                    "Communiquer entre goroutines via les Channels (<-).",
                    "Synchroniser l'exécution avec sync.WaitGroup.",
                    "Implémenter des Interfaces implicites sans mot-clé implements."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Goroutines & sync.WaitGroup",
                        content = "Une Goroutine est un thread léger géré par le runtime Go. `sync.WaitGroup` attend la fin des goroutines.",
                        codeSnippet = """
                            var wg sync.WaitGroup

                            for i := 0; i < 3; i++ {
                                wg.Add(1)
                                go func(id int) {
                                    defer wg.Done()
                                    fmt.Println("Goroutine", id)
                                }(i)
                            }
                            wg.Wait()
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Channels de Communication",
                        content = "Les channels permettent aux goroutines de s'échanger des données en toute sécurité (CSP model).",
                        codeSnippet = """
                            ch := make(chan string)

                            go func() {
                                ch <- "Données prêtes !"
                            }()

                            msg := <-ch
                            fmt.Println(msg)
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Interfaces Implicites",
                        content = "Une structure satisfait une interface dès lors qu'elle possède toutes ses méthodes.",
                        codeSnippet = """
                            type Lecteur interface { Read() string }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Go : Concurrency Patterns, Context & Profiling ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Appliquez les patterns de concurrence avancés (Worker Pools), gérez l'annulation via context.Context et profilez avec pprof.",
                keyPoints = listOf(
                    "Concevoir des Worker Pools pour limiter la charge concurrente.",
                    "Gérer les timeouts et annulations via context.Context.",
                    "Éviter les Data Races détectées par go test -race.",
                    "Analyser la mémoire et le CPU avec pprof."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Concurrence avec context.Context",
                        content = "Le package `context` transmet les signaux d'annulation et les deadlines à travers tout l'arbre d'exécution.",
                        codeSnippet = """
                            ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
                            defer cancel()

                            req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Worker Pool Pattern",
                        content = "Un Worker Pool distribue un nombre N de tâches à un groupe fixe K de goroutines.",
                        codeSnippet = """
                            func worker(id int, jobs <-chan int, results chan<- int) {
                                for j := range jobs {
                                    results <- j * 2
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Profiling pprof",
                        content = "Utilisez `net/http/pprof` pour générer des flamme-graphes et identifier les hotspots de mémoire.",
                        codeSnippet = "// Maîtrise avancée des performances Go validée !"
                    )
                )
            )
        }
    }

    private fun getSwiftDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Swift : Syntaxe, Optionals & Structs ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Apprenez les bases de Swift pour iOS/macOS : déclarations let/var, gestion des Optionals et Structures.",
                keyPoints = listOf(
                    "Distinguer constantes (let) et variables (var).",
                    "Gérer la possibilité de valeur nulle avec les Optionals (?).",
                    "Déballer les optionals avec if let et guard let.",
                    "Préférer les Structs aux Classes pour la sécurité."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Déballage d'Optionals (if let / guard let)",
                        content = "Un **Optional** contient soit une valeur, soit `nil`. `guard let` permet un déballage précoce avec sortie de fonction.",
                        codeSnippet = """
                            var pseudo: String? = "Developer"

                            func saluer(nomOptional: String?) {
                                guard let nom = nomOptional else {
                                    print("Nom manquant")
                                    return
                                }
                                print("Bonjour \(nom)")
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Structs vs Classes",
                        content = "Les **Structs** sont des types valeur (copies lors du passage), tandis que les **Classes** sont des types référence.",
                        codeSnippet = """
                            struct Utilisateur {
                                var id: Int
                                var nom: String
                            }

                            var u1 = Utilisateur(id: 1, nom: "Alice")
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Enums Swift Expressifs",
                        content = "Les Enums en Swift peuvent contenir des valeurs associées (Associated Values).",
                        codeSnippet = """
                            enum Statut {
                                case succes(message: String)
                                case erreur(code: Int)
                            }
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Swift : Protocols, Closures & ARC ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Maîtrisez la programmation orientée Protocole (POP), les Closures et la gestion mémoire ARC sans Retain Cycles.",
                keyPoints = listOf(
                    "Appliquer la Protocol-Oriented Programming (POP).",
                    "Écrire des Closures concises.",
                    "Comprendre le fonctionnement d'ARC (Automatic Reference Counting).",
                    "Éviter les fuites de mémoire grâce à [weak self]."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Protocol-Oriented Programming (POP)",
                        content = "Les **Protocols** définissent un contrat. Les extensions de protocole permettent de fournir des implémentations par défaut.",
                        codeSnippet = """
                            protocol Dessinable {
                                fn dessiner()
                            }

                            extension Dessinable {
                                fn dessiner() { print("Dessin par défaut") }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Gestion Mémoire ARC & [weak self]",
                        content = "Pour éviter les cycles de référence forte (Retain Cycles) dans les Closures asynchrones, utilisez `[weak self]`.",
                        codeSnippet = """
                            networkManager.fetchData { [weak self] result in
                                guard let self = self else { return }
                                self.updateUI(with: result)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Property Wrappers (@State)",
                        content = "Les Property Wrappers encapsulent la logique d'accès et de modification d'une propriété.",
                        codeSnippet = """
                            @State private var estConnecte: Bool = false
                        """.trimIndent()
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Swift : Concurrency (Async/Await, Actors) & Performance ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Explorez Swift Concurrency moderne : async/await, TaskGroups, Actors pour l'isolation d'état et SwiftUI Architecture.",
                keyPoints = listOf(
                    "Gérer les tâches asynchrones réactives avec async/await.",
                    "Isoler l'état mutable partagé à l'aide des Actors.",
                    "Exécuter des calculs parallèles avec TaskGroup.",
                    "Optimiser la mémoire et le temps de rendu SwiftUI."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Actors & Thread Safety",
                        content = "Un **Actor** protège son état interne en s'assurant qu'une seule tâche à la fois peut y accéder de façon concurrente.",
                        codeSnippet = """
                            actor CompteurBanque {
                                private var solde: Double = 0.0

                                func mepDépôt(montant: Double) {
                                    solde += montant
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Swift Concurrency (async/await)",
                        content = "Swift 5.5+ intègre nativement `async/await` au niveau du langage pour éliminer la 'Pyramide de la mort' des callbacks.",
                        codeSnippet = """
                            func chargerProduits() async throws -> [Produit] {
                                let (data, _) = try await URLSession.shared.data(from: url)
                                return try JSONDecoder().decode([Produit].self, from: data)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Execution Parallèle avec TaskGroup",
                        content = "Exécutez dynamiquement un nombre variable de sous-tâches asynchrones concurrentes.",
                        codeSnippet = "// Maîtrise avancée de Swift Concurrency validée !"
                    )
                )
            )
        }
    }

    private fun getNodeJsDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Node.js : Runtime, Modules & NPM ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez Node.js : modules ES/CommonJS et paquets NPM.",
                keyPoints = listOf("Comprendre V8.", "Distinguer require et import."),
                sections = listOf(
                    LessonSection(
                        title = "1. Modules ES",
                        content = "Node.js gère les ES Modules (`import`/`export`).",
                        codeSnippet = "import fs from 'node:fs/promises';"
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Node.js : Event Loop & Express ($levelText)",
                theme = theme,
                level = levelText,
                summary = "API REST Express.js et Event Loop.",
                keyPoints = listOf("Event Loop phases.", "Middlewares Express."),
                sections = listOf(
                    LessonSection(
                        title = "1. Express REST API",
                        content = "Express est le framework standard.",
                        codeSnippet = "import express from 'express';"
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Node.js : Streams & Cluster ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Streams, Worker Threads et Cluster.",
                keyPoints = listOf("Streams I/O.", "Worker Threads."),
                sections = listOf(
                    LessonSection(
                        title = "1. Worker Threads",
                        content = "Exécutez du JS sur des threads séparés.",
                        codeSnippet = "import { Worker } from 'node:worker_threads';"
                    )
                )
            )
        }
    }

    private fun getDockerDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Docker : Containers & Dockerfile ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Conteneurs Docker et syntaxe Dockerfile.",
                keyPoints = listOf("Isolation par conteneur.", "Bases du Dockerfile."),
                sections = listOf(
                    LessonSection(
                        title = "1. Dockerfile",
                        content = "Exemple de Dockerfile Node.js.",
                        codeSnippet = "FROM node:20-alpine\nWORKDIR /app"
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Docker : Multi-Stage & Compose ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Multi-Stage builds et Docker Compose.",
                keyPoints = listOf("Multi-stage builds.", "docker-compose.yml."),
                sections = listOf(
                    LessonSection(
                        title = "1. Docker Compose",
                        content = "Orchestrer votre stack.",
                        codeSnippet = "version: '3.8'"
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Docker : Sécurité & Healthchecks ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Conteneurs non-root et Healthchecks.",
                keyPoints = listOf("Utilisateur non-root.", "Healthchecks."),
                sections = listOf(
                    LessonSection(
                        title = "1. Non-root user",
                        content = "Sécurisez l'exécution du conteneur.",
                        codeSnippet = "USER appuser"
                    )
                )
            )
        }
    }

    private fun getGitDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Git : Commits & Branches ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Bases de la gestion de version Git.",
                keyPoints = listOf("git add & commit.", "git branch."),
                sections = listOf(
                    LessonSection(
                        title = "1. Commit cycle",
                        content = "Indexation et commit.",
                        codeSnippet = "git add . && git commit -m 'feat'"
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Git : Rebase & Stash ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Merge vs Rebase et Stashing.",
                keyPoints = listOf("git rebase.", "git stash."),
                sections = listOf(
                    LessonSection(
                        title = "1. Rebase",
                        content = "Historique linéaire.",
                        codeSnippet = "git rebase main"
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Git : Reflog & Bisect ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Rebase interactif, Reflog et Bisect.",
                keyPoints = listOf("git reflog.", "git bisect."),
                sections = listOf(
                    LessonSection(
                        title = "1. Reflog",
                        content = "Restaurez n'importe quel commit.",
                        codeSnippet = "git reflog"
                    )
                )
            )
        }
    }

    private fun getArchDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Architecture : DRY & KISS ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Bases de la conception logicielle.",
                keyPoints = listOf("Don't Repeat Yourself.", "Keep It Simple."),
                sections = listOf(
                    LessonSection(
                        title = "1. DRY",
                        content = "Éviter la duplication.",
                        codeSnippet = "fun calcul() = 42"
                    )
                )
            )
            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Architecture : SOLID & Repository ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Principes SOLID et Repository Pattern.",
                keyPoints = listOf("SOLID principles.", "Repository Pattern."),
                sections = listOf(
                    LessonSection(
                        title = "1. SOLID",
                        content = "Inversion de dépendance.",
                        codeSnippet = "interface Repo { fun get() }"
                    )
                )
            )
            DifficultyLevel.AVANCE -> Lesson(
                title = "Architecture : Clean Arch & CQRS ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Clean Architecture et CQRS.",
                keyPoints = listOf("Domain Driven Design.", "CQRS."),
                sections = listOf(
                    LessonSection(
                        title = "1. Clean Arch",
                        content = "Indépendance du domaine.",
                        codeSnippet = "class DomainUseCase"
                    )
                )
            )
        }
    }

    private fun getGenericDemoLesson(theme: String, levelText: String, level: DifficultyLevel): Lesson {
        return when (level) {
            DifficultyLevel.DEBUTANT -> Lesson(
                title = "Les Fondamentaux de $theme ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Découvrez la syntaxe élémentaire, le rôle et les premiers concepts pour aborder sereinement $theme.",
                keyPoints = listOf(
                    "Comprendre le rôle principal et la philosophie de $theme.",
                    "Manipuler les structures et fonctions élémentaires.",
                    "Écrire vos premiers scripts et instructions conditionnelles.",
                    "Comprendre l'organisation du code pour $theme."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Introduction & Syntaxe Débutant",
                        content = "Au niveau **Débutant**, $theme repose sur des règles de clarté et de lisibilité. L'objectif est d'installer l'environnement et de maîtriser les variables basiques.",
                        codeSnippet = """
                            // Exemple de démarrage pour $theme ($levelText)
                            fun init$theme() {
                                val sujet = "$theme"
                                println("Apprentissage de " + sujet)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Traitement & Fonctions Simples",
                        content = "Les fonctions encapsulent la logique de votre programme. Découpez les problèmes complexes en plusieurs fonctions courtes.",
                        codeSnippet = """
                            fun traiter$theme(valeur: Int): Int {
                                if (valeur <= 0) return 0
                                return valeur * 2
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Bonnes Pratiques Débutant",
                        content = "1. Nommez vos variables de façon claire.\n2. Évitez les fonctions trop longues.\n3. Testez votre code au fur et à mesure.",
                        codeSnippet = "// Fondamentaux de $theme assimilés avec succès !"
                    )
                )
            )

            DifficultyLevel.INTERMEDIAIRE -> Lesson(
                title = "Patterns & Pratiques Idiomatiques sur $theme ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Approfondissez l'asynchronisme, la gestion des erreurs, la modularité et les pièges classiques sur $theme.",
                keyPoints = listOf(
                    "Gérer les erreurs et les exceptions de manière robuste.",
                    "Appliquer la modularité et la séparation des responsabilités.",
                    "Éviter les effets de bord et les fuites mémoire.",
                    "Rédiger un code idiomatique respectant les conventions de l'écosystème $theme."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Asynchronisme & Traitement d'Erreurs",
                        content = "Au niveau **Intermédiaire** sur $theme, la gestion rigoureuse des erreurs et le découplage des composants garantissent la stabilité de l'application.",
                        codeSnippet = """
                            // Traitement asynchrone sécurisé
                            suspend fun charger$theme(): Result<String> {
                                return runCatching {
                                    api.fetchData("$theme")
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Immuabilité & Anti-patterns",
                        content = "Préférez l'immuabilité des données. La mutation d'état global est l'anti-pattern causant le plus de bogues intermittents.",
                        codeSnippet = """
                            // Renvoyer une nouvelle copie au lieu de muter
                            fun mettreAJour$theme(ancienState: State, modif: String): State {
                                return ancienState.copy(valeur = modif)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Modularité",
                        content = "Isolez les services métier dans des packages distincts pour pouvoir écrire des tests automatisés.",
                        codeSnippet = "// Architecture intermédiaire $theme validée !"
                    )
                )
            )

            DifficultyLevel.AVANCE -> Lesson(
                title = "Architecture, Optimisation & Internals sur $theme ($levelText)",
                theme = theme,
                level = levelText,
                summary = "Passez au niveau expert : profilage des performances, gestion mémoire/concurrence bas niveau et Clean Architecture pour $theme.",
                keyPoints = listOf(
                    "Optimiser l'empreinte mémoire et le temps d'exécution.",
                    "Concevoir des architectures scalables et maintenables à long terme.",
                    "Gérer la concurrence avancée et les verrous.",
                    "Profilage et métriques de production pour $theme."
                ),
                sections = listOf(
                    LessonSection(
                        title = "1. Profilage & Performances ($theme)",
                        content = "Au niveau **Avancé**, vous devez mesurer avant d'optimiser. Identifiez les vrais goulots d'étranglement mémoire et CPU.",
                        codeSnippet = """
                            // Minimiser les allocations temporaires
                            inline fun <T> optimiser$theme(list: List<T>, block: (T) -> Unit) {
                                for (i in list.indices) {
                                    block(list[i])
                                }
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "2. Architecture Scalable",
                        content = "Appliquez l'inversion de dépendance pour préserver le domaine métier de toute bibliothèque tierce.",
                        codeSnippet = """
                            interface RepositorySpec {
                                fun save(data: Any)
                            }
                        """.trimIndent()
                    ),
                    LessonSection(
                        title = "3. Concurrence Avancée",
                        content = "Évitez les verrous bloquants et préférez les primitives non-bloquantes ou la communication par messages.",
                        codeSnippet = "// Niveau expert sur $theme maîtrisé !"
                    )
                )
            )
        }
    }

    private fun getDemoQuiz(theme: String, level: DifficultyLevel): Quiz {
        val levelText = level.label
        return Quiz(
            title = "Quiz : $theme ($levelText)",
            theme = theme,
            level = levelText,
            questions = listOf(
                Question(
                    id = 1,
                    prompt = "Quel est l'un des principes fondamentaux associés à $theme en niveau $levelText ?",
                    options = listOf(
                        "Favoriser la modularité et la séparation des responsabilités",
                        "Tout regrouper dans un seul fichier monolithique",
                        "Désactiver la gestion d'erreurs",
                        "Éviter les tests automatisés"
                    ),
                    correctAnswerIndex = 0,
                    explanation = "La modularité et la séparation des responsabilités rendent le code plus lisible, maintenable et testable."
                ),
                Question(
                    id = 2,
                    prompt = "En $theme ($levelText), quelle est la recommandation pour traiter les erreurs ?",
                    options = listOf(
                        "Ignorer silencieusement les exceptions",
                        "Traiter explicitement les erreurs ou utiliser des types de retour sécurisés",
                        "Quitter immédiatement le programme",
                        "Utiliser des variables globales masquées"
                    ),
                    correctAnswerIndex = 1,
                    explanation = "Un traitement explicite des erreurs évite le plantage inattendu de l'application."
                ),
                Question(
                    id = 3,
                    prompt = "Pourquoi privilégier l'immuabilité en $theme ?",
                    options = listOf(
                        "Pour prévenir les effets de bord inattendus et sécuriser le multi-threading",
                        "Parce que les variables sont interdites",
                        "Pour ralentir le programme",
                        "Cela réduit la taille du fichier binaire"
                    ),
                    correctAnswerIndex = 0,
                    explanation = "L'immuabilité garantit qu'une donnée ne sera pas altérée par inadvertance depuis une autre partie du programme."
                ),
                Question(
                    id = 4,
                    prompt = "Au niveau $levelText en $theme, quelle est la règle d'or concernant l'optimisation ?",
                    options = listOf(
                        "Profiler et mesurer d'abord pour identifier les goulots d'étranglement",
                        "Optimiser chaque ligne avant même qu'elle ne fonctionne",
                        "Supprimer tous les logs et commentaires",
                        "Dupliquer la logique manuellement"
                    ),
                    correctAnswerIndex = 0,
                    explanation = "L'optimisation prématurée est la racine de nombreux bugs. Il faut toujours mesurer avant d'optimiser."
                )
            )
        )
    }
}
