package com.metersync.web

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.metersync.utils.Logger

/**
 * Класс для управления точкой восстановления WebView
 * Создает "снимок" чистого состояния WebView при первом запуске
 * и восстанавливает его при очистке кэша
 */
class WebViewRestorePoint(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("webview_restore", Context.MODE_PRIVATE)
    private val RESTORE_POINT_KEY = "restore_point_created"
    private val RESTORE_POINT_VERSION = "restore_point_version"
    private val CURRENT_VERSION = 1
    
    /**
     * Проверяет, создана ли точка восстановления
     */
    fun isRestorePointCreated(): Boolean {
        val created = prefs.getBoolean(RESTORE_POINT_KEY, false)
        val version = prefs.getInt(RESTORE_POINT_VERSION, 0)
        
        Logger.logWebView("Restore point check: created=$created, version=$version, current=$CURRENT_VERSION")
        
        return created && version == CURRENT_VERSION
    }
    
    /**
     * Создает точку восстановления WebView
     * Вызывается при первом запуске приложения
     */
    fun createRestorePoint() {
        try {
            Logger.logWebView("Creating WebView restore point")
            
            // Очищаем все данные WebView для создания чистой точки
            clearAllWebViewData()
            
            // Отмечаем, что точка восстановления создана
            prefs.edit()
                .putBoolean(RESTORE_POINT_KEY, true)
                .putInt(RESTORE_POINT_VERSION, CURRENT_VERSION)
                .apply()
            
            Logger.logWebView("WebView restore point created successfully")
            
        } catch (e: Exception) {
            Logger.logError("Error creating WebView restore point", e)
        }
    }
    
    /**
     * Восстанавливает WebView к точке восстановления
     * Вызывается при очистке кэша
     */
    fun restoreToPoint() {
        try {
            Logger.logWebView("Restoring WebView to restore point")
            
            // Очищаем все данные WebView
            clearAllWebViewData()
            
            Logger.logWebView("WebView restored to clean state")
            
        } catch (e: Exception) {
            Logger.logError("Error restoring WebView to restore point", e)
        }
    }
    
    /**
     * Полная очистка всех данных WebView
     */
    private fun clearAllWebViewData() {
        try {
            Logger.logWebView("Performing complete WebView data cleanup")
            
            // Очистка кэша WebView
            WebViewDatabase.getInstance(context).clearFormData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            
            // Очистка куки - более агрессивная
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true) // Включаем куки для очистки
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            // Дополнительная очистка через CookieManager
            try {
                // Очищаем куки для всех доменов
                cookieManager.removeSessionCookies(null)
                cookieManager.flush()
            } catch (e: Exception) {
                Logger.logError("Error clearing session cookies", e)
            }
            
            // Очистка кэша приложения
            try {
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                    Logger.logWebView("App cache directory deleted")
                }
            } catch (e: Exception) {
                Logger.logError("Error clearing app cache", e)
            }
            
            // Очистка кэша WebView через файловую систему
            try {
                val webViewCacheDir = context.getDir("webview", Context.MODE_PRIVATE)
                if (webViewCacheDir.exists()) {
                    webViewCacheDir.deleteRecursively()
                    Logger.logWebView("WebView cache directory deleted")
                }
            } catch (e: Exception) {
                Logger.logError("Error clearing WebView cache directory", e)
            }
            
            Logger.logWebView("Complete WebView data cleanup finished")
            
        } catch (e: Exception) {
            Logger.logError("Error in complete WebView cleanup", e)
        }
    }
    
    /**
     * Сбрасывает точку восстановления (при переустановке приложения)
     */
    fun resetRestorePoint() {
        try {
            Logger.logWebView("Resetting WebView restore point")
            
            prefs.edit()
                .remove(RESTORE_POINT_KEY)
                .remove(RESTORE_POINT_VERSION)
                .apply()
            
            Logger.logWebView("WebView restore point reset")
            
        } catch (e: Exception) {
            Logger.logError("Error resetting WebView restore point", e)
        }
    }
}
