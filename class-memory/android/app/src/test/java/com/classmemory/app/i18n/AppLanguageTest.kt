package com.classmemory.app.i18n

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    private val originalLanguage = AppLanguage.isEnglish
    private val originalLocale = Locale.getDefault()

    @After
    fun restoreProcessLanguage() {
        AppLanguage.selectEnglish(LanguagePreferences().preferences, originalLanguage)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun languageSelectionReturnsTheMatchingUiCopy() {
        assertEquals("Class details", selectAppLanguageText("课堂详情", "Class details", englishEnabled = true))
        assertEquals("课堂详情", selectAppLanguageText("课堂详情", "Class details", englishEnabled = false))
        assertEquals("Lecture", selectAppLanguageText("讲座", "Lecture", englishEnabled = true))
        assertEquals("讲座", selectAppLanguageText("讲座", "Lecture", englishEnabled = false))
    }

    @Test
    fun menuSelectionsSwitchBothWaysAndPersistChinese() {
        val saved = LanguagePreferences()
        AppLanguage.initialize { saved.preferences }
        assertTrue(AppLanguage.isEnglish)

        for (selected in listOf(false, true, false, true, false)) {
            AppLanguage.selectEnglish(saved.preferences, selected)
            assertEquals(selected, AppLanguage.isEnglish)
            assertEquals(selected, saved.values["english"])
            assertEquals(if (selected) "Class Memory" else "课堂记忆", tr("课堂记忆", "Class Memory"))
            assertEquals(if (selected) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE, Locale.getDefault())
        }

        // A new process starts in English, then restores the previously saved Chinese choice.
        val reopened = LanguagePreferences(saved.values.toMap())
        AppLanguage.initialize { LanguagePreferences().preferences }
        assertTrue(AppLanguage.isEnglish)
        AppLanguage.initialize { reopened.preferences }
        assertFalse(AppLanguage.isEnglish)
        assertEquals("课堂记忆", tr("课堂记忆", "Class Memory"))
        assertEquals(Locale.SIMPLIFIED_CHINESE, Locale.getDefault())
    }

    @Test
    fun savedEnglishIsRestoredAfterChineseWasActive() {
        val saved = LanguagePreferences(mapOf("english" to true))
        AppLanguage.selectEnglish(LanguagePreferences().preferences, false)
        AppLanguage.initialize { saved.preferences }
        assertTrue(AppLanguage.isEnglish)
        assertEquals(Locale.ENGLISH, Locale.getDefault())
    }
}

private class LanguagePreferences(initial: Map<String, Boolean> = emptyMap()) {
    val values = initial.toMutableMap()
    private val pending = mutableMapOf<String, Boolean>()

    private val editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "putBoolean" -> {
                pending[arguments!![0] as String] = arguments[1] as Boolean
                proxy
            }
            "apply" -> {
                values.putAll(pending)
                pending.clear()
                null
            }
            else -> error("Unexpected preferences edit operation: ${method.name}")
        }
    } as SharedPreferences.Editor

    val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getBoolean" -> values[arguments!![0] as String] ?: arguments[1] as Boolean
            "edit" -> editor
            else -> error("Unexpected preferences operation: ${method.name}")
        }
    } as SharedPreferences
}
