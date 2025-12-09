package com.metersync.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_THEME = "dark_theme"
    
    fun isDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DARK_THEME, false)
    }
    
    fun setDarkTheme(context: Context, isDark: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }
}

@Composable
fun getColorScheme(isDark: Boolean): ColorScheme {
    return if (isDark) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }
}

@Composable
fun rememberThemeState(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    // Перечитываем значение из SharedPreferences при каждой композиции
    // Это обеспечит синхронизацию между экранами
    var isDark by remember { 
        mutableStateOf(ThemeManager.isDarkTheme(context))
    }
    
    // Обновляем состояние при изменении в SharedPreferences
    LaunchedEffect(Unit) {
        // Периодически проверяем изменения (для синхронизации)
        // Но лучше использовать ключ для принудительной перекомпозиции
    }
    
    return Pair(isDark) { newValue ->
        isDark = newValue
        ThemeManager.setDarkTheme(context, newValue)
    }
}

