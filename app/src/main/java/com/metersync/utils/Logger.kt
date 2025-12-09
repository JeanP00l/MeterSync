package com.metersync.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val TAG = "MeterSync"
    private const val PREFS_NAME = "logger_prefs"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    
    private var logFile: File? = null
    private var prefs: SharedPreferences? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var isLoggingEnabled = false // По умолчанию логирование отключено

    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, "metersync.log")
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Загружаем сохраненное состояние логирования
            isLoggingEnabled = prefs?.getBoolean(KEY_LOGGING_ENABLED, false) ?: false
            
            log("Logger initialized, logging enabled: $isLoggingEnabled", "INIT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize logger", e)
        }
    }
    
    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
        // Сохраняем состояние в SharedPreferences
        prefs?.edit()?.putBoolean(KEY_LOGGING_ENABLED, enabled)?.apply()
        log("Logging ${if (enabled) "enabled" else "disabled"}", "INIT")
    }
    
    fun isLoggingEnabled(): Boolean {
        return isLoggingEnabled
    }

    fun log(message: String, category: String = "INFO") {
        // Если логирование отключено, ничего не делаем
        if (!isLoggingEnabled) {
            return
        }
        
        val timestamp = dateFormat.format(Date())
        val logMessage = "[$timestamp] [$category] $message"
        
        // Log to Android logcat
        when (category) {
            "ERROR" -> Log.e(TAG, logMessage)
            "WARN" -> Log.w(TAG, logMessage)
            "DEBUG" -> Log.d(TAG, logMessage)
            else -> Log.i(TAG, logMessage)
        }

        // Log to file
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logMessage)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (!isLoggingEnabled) return
        log("ERROR: $message", "ERROR")
        throwable?.let {
            log("Stack trace: ${it.stackTraceToString()}", "ERROR")
        }
    }

    fun logWebView(message: String) {
        if (!isLoggingEnabled) return
        log(message, "WEBVIEW")
    }

    fun logDatabase(message: String) {
        if (!isLoggingEnabled) return
        log(message, "DATABASE")
    }

    fun logUI(message: String) {
        if (!isLoggingEnabled) return
        log(message, "UI")
    }

    fun logNetwork(message: String) {
        if (!isLoggingEnabled) return
        log(message, "NETWORK")
    }

    fun clearLogs() {
        try {
            logFile?.delete()
            if (isLoggingEnabled) {
                log("Logs cleared", "INIT")
            }
        } catch (e: Exception) {
            if (isLoggingEnabled) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    fun getLogPath(): String? {
        return logFile?.absolutePath
    }
}
