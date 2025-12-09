package com.metersync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metersync.ui.getColorScheme
import com.metersync.ui.ThemeManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.metersync.ui.AddressDetailScreen
import com.metersync.ui.AddressListScreen
import com.metersync.ui.LoginScreen
import com.metersync.ui.MainScreen
import com.metersync.utils.Logger
import com.metersync.permissions.PermissionManager
import com.metersync.camera.CameraManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.net.Uri
import android.app.Activity

class MainActivity : ComponentActivity() {
    
    // Регистрируем камеру на уровне Activity
    val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Logger.log("Camera photo taken successfully", "CAMERA")
            // Обрабатываем результат камеры
            currentTempUri?.let { uri ->
                currentMeterInfo?.let { meterInfo ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val watermarkedUri = cameraManager.addWatermarkToImage(uri, meterInfo)
                            if (watermarkedUri != null) {
                                Logger.log("Photo saved with watermark: $meterInfo", "CAMERA")
                            } else {
                                Logger.log("Failed to save photo with watermark", "CAMERA")
                            }
                        } catch (e: Exception) {
                            Logger.logError("Error processing camera result", e)
                        }
                    }
                }
            }
        } else {
            Logger.log("Camera photo cancelled", "CAMERA")
            // Очищаем переменные при отмене
            currentTempUri = null
            currentMeterInfo = null
        }
    }
    
    // Менеджер разрешений
    private lateinit var permissionManager: PermissionManager
    
    // Менеджер камеры
    private lateinit var cameraManager: CameraManager
    
    // Переменные для обработки результата камеры
    private var currentTempUri: Uri? = null
    private var currentMeterInfo: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Logger.init(this)
            Logger.log("MainActivity onCreate", "INIT")
            
            // Инициализируем менеджер разрешений
            permissionManager = PermissionManager(this)
            permissionManager.onPermissionsGranted = {
                Logger.log("All permissions granted", "PERMISSIONS")
                setupUI()
            }
            permissionManager.onPermissionsDenied = {
                Logger.log("Some permissions denied", "PERMISSIONS")
                setupUI() // Все равно показываем UI, но камера может не работать
            }
            
            // Инициализируем менеджер камеры
            cameraManager = CameraManager(this)
            
            // Запрашиваем разрешения
            permissionManager.checkAndRequestPermissions()
            
        } catch (e: Exception) {
            Logger.logError("Critical error in MainActivity onCreate", e)
        }
    }
    
    private fun setupUI() {
        setContent {
            Logger.logUI("MainActivity setContent")
            
            // Управление темой - используем состояние для автоматического обновления
            val context = LocalContext.current
            var isDarkTheme by remember { mutableStateOf(ThemeManager.isDarkTheme(context)) }
            
            // Периодически проверяем изменения темы для синхронизации
            LaunchedEffect(Unit) {
                while (true) {
                    delay(100) // Проверяем каждые 100ms
                    val currentTheme = ThemeManager.isDarkTheme(context)
                    if (currentTheme != isDarkTheme) {
                        isDarkTheme = currentTheme
                    }
                }
            }
            
            val colorScheme = getColorScheme(isDarkTheme)
            
            // Используем ключ для перекомпозиции при изменении темы
            androidx.compose.runtime.key(isDarkTheme) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    content = {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val navController = rememberNavController()
                            Logger.logUI("Navigation controller created")

                            MainScreen(
                                navController = navController, 
                                cameraLauncher = cameraLauncher,
                                onCameraDataReady = { uri, meterInfo ->
                                    currentTempUri = uri
                                    currentMeterInfo = meterInfo
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}


