package com.example.studying.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AiProvider(
    val displayName: String,
    val apiKeyPortalUrl: String,
    val defaultModel: String,
    val isFreeWithoutCc: Boolean,
    val guideInstructions: String
) {
    GEMINI(
        displayName = "Google Gemini",
        apiKeyPortalUrl = "https://aistudio.google.com/app/apikey",
        defaultModel = "gemini-2.0-flash",
        isFreeWithoutCc = true,
        guideInstructions = "1. Cliquez sur le bouton ci-dessous pour ouvrir Google AI Studio.\n" +
                "2. Connectez-vous avec votre compte Google.\n" +
                "3. Cliquez sur 'Create API Key' puis copiez la clé générée."
    ),
    OPEN_AI(
        displayName = "OpenAI (ChatGPT)",
        apiKeyPortalUrl = "https://platform.openai.com/api-keys",
        defaultModel = "gpt-4o-mini",
        isFreeWithoutCc = false,
        guideInstructions = "1. Connectez-vous à votre compte OpenAI Platform.\n" +
                "2. Rendez-vous dans 'API Keys' -> 'Create new secret key'.\n" +
                "3. Copiez la clé secrète générée."
    ),
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        apiKeyPortalUrl = "https://console.anthropic.com/settings/keys",
        defaultModel = "claude-3-5-haiku-20241022",
        isFreeWithoutCc = false,
        guideInstructions = "1. Rendez-vous sur la Console Anthropic.\n" +
                "2. Accédez à 'API Keys' -> 'Create Key'.\n" +
                "3. Copiez la clé obtenue."
    );

    companion object {
        fun fromName(name: String?): AiProvider {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GEMINI
        }
    }
}
