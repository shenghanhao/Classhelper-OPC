package com.classmemory.app.i18n

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Lightweight, app-local language preference shared by UI and core messages. */
object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val ENGLISH_KEY = "english"

    private var english by mutableStateOf(true)

    val isEnglish: Boolean
        get() = english

    fun initialize(context: Context) {
        initialize {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }

    internal fun initialize(preferences: () -> SharedPreferences) {
        english = runCatching {
            preferences().getBoolean(ENGLISH_KEY, true)
        }.getOrDefault(true)
        applyDefaultLocale()
    }

    fun selectEnglish(context: Context, selected: Boolean) {
        selectEnglish(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
            selected,
        )
    }

    internal fun selectEnglish(preferences: SharedPreferences, selected: Boolean) {
        preferences
            .edit()
            .putBoolean(ENGLISH_KEY, selected)
            .apply()
        english = selected
        applyDefaultLocale()
    }

    private fun applyDefaultLocale() {
        Locale.setDefault(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
    }
}

fun tr(chinese: String, english: String): String = selectAppLanguageText(chinese, english, AppLanguage.isEnglish)

internal fun selectAppLanguageText(chinese: String, english: String, englishEnabled: Boolean): String =
    if (englishEnabled) english else chinese
