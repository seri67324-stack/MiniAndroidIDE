package com.miniide.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndThemesTest {

    @Test
    fun testAllBuiltInThemesJsonRoundtripAndValidation() {
        assertEquals(7, SettingsAndThemes.builtInThemes.size)
        for (theme in SettingsAndThemes.builtInThemes) {
            val json = SettingsAndThemes.themeToJson(theme)
            val parsed = SettingsAndThemes.themeFromJson(json)
            assertTrue("Theme ${theme.id} must parse cleanly", parsed.isSuccess)
            assertEquals(theme, parsed.getOrThrow())
        }

        // Geçersiz HEX renk doğrulaması başarısız olmalı
        val brokenJson = SettingsAndThemes.themeToJson(SettingsAndThemes.builtInThemes.first())
            .replace("#1E1E1E", "NOT_A_COLOR")
        val brokenRes = SettingsAndThemes.themeFromJson(brokenJson)
        assertTrue(brokenRes.isFailure)
    }

    @Test
    fun testSettingsSerializeAndDeserialize() {
        val original = SettingsAndThemes.IdeSettings(
            themeId = "dracula",
            fontFamily = "Fira Code",
            fontSizeSp = 15.0f,
            enableLigatures = true,
            tabSize = 2,
            useSpaces = false,
            wordWrap = true,
            autoSaveDelayMs = 2500L,
            showInvisibleChars = true,
            showMinimap = true,
            zenMode = true,
            showHiddenFiles = true,
            excludedDirs = "build,gen,custom_out",
            enableIncrementalBuild = true,
            enableReleaseSigning = true,
            enableMavenDependencies = true,
            onboardingCompleted = true
        )

        val raw = SettingsAndThemes.serializeSettings(original)
        val restored = SettingsAndThemes.deserializeSettings(raw)
        assertEquals(original, restored)
    }
}
