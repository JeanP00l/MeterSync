package com.metersync.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Класс для проверки актуальной версии приложения на GitHub
 */
object VersionChecker {
    
    private const val GITHUB_API_URL = "https://api.github.com/repos/JeanP00l/MeterSync/releases/latest"
    private const val RELEASES_URL = "https://github.com/JeanP00l/MeterSync/releases"
    
    /**
     * Проверяет, есть ли интернет-соединение
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return connectivityManager?.let { cm ->
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }
    
    /**
     * Получает последнюю версию с GitHub
     * @return Версия в формате "v0.1.5" или null в случае ошибки
     */
    suspend fun getLatestVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MeterSync-Android/0.1.5")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name")
                    Logger.log("Latest version from GitHub: $tagName", "VERSION_CHECK")
                    tagName
                } else {
                    Logger.log("Failed to get version: HTTP $responseCode", "VERSION_CHECK")
                    null
                }
            } catch (e: Exception) {
                Logger.logError("Error checking version", e)
                null
            }
        }
    }
    
    /**
     * Сравнивает версии
     * @param currentVersion текущая версия (например "0.1.5")
     * @param latestVersion последняя версия (например "v0.1.6")
     * @return true если текущая версия устарела
     */
    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
        try {
            // Убираем префикс "v" если есть
            val cleanLatest = latestVersion.removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.removePrefix("v").removePrefix("V")
            
            // Сравниваем версии по частям (major.minor.patch)
            val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
            val latestParts = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
            
            // Дополняем до 3 частей если нужно
            while (currentParts.size < 3) currentParts.add(0)
            while (latestParts.size < 3) latestParts.add(0)
            
            // Сравниваем по порядку: major, minor, patch
            for (i in 0 until 3) {
                if (latestParts[i] > currentParts[i]) {
                    return true
                } else if (latestParts[i] < currentParts[i]) {
                    return false
                }
            }
            
            return false // Версии равны
        } catch (e: Exception) {
            Logger.logError("Error comparing versions", e)
            return false
        }
    }
    
    /**
     * Получает URL страницы релизов
     */
    fun getReleasesUrl(): String = RELEASES_URL
}

