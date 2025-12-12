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
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
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
import com.metersync.ui.UpdateDialog
import com.metersync.utils.Logger
import com.metersync.utils.VersionChecker
import com.metersync.permissions.PermissionManager
import com.metersync.camera.CameraManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.net.Uri
import android.app.Activity
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    
    // Регистрируем камеру на уровне Activity
    val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Logger.log("Camera photo taken successfully", "CAMERA")
            // Обрабатываем результат камеры
            currentTempUri?.let { uri ->
                currentTempFile?.let { tempFile ->
                    currentCounterAddress?.let { counterAddress ->
                        currentCounterNumber?.let { counterNumber ->
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val watermarkedUri = cameraManager.addWatermarkToImage(
                                        uri, 
                                        counterAddress, 
                                        counterNumber, 
                                        tempFile
                                    )
                                    if (watermarkedUri != null) {
                                        Logger.log("Photo saved with watermark and EXIF: address='$counterAddress', number='$counterNumber'", "CAMERA")
                                    } else {
                                        Logger.log("Failed to save photo with watermark", "CAMERA")
                                    }
                                } catch (e: Exception) {
                                    Logger.logError("Error processing camera result", e)
                                } finally {
                                    // Очищаем переменные после обработки
                                    currentTempUri = null
                                    currentTempFile = null
                                    currentCounterAddress = null
                                    currentCounterNumber = null
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Logger.log("Camera photo cancelled", "CAMERA")
            // Очищаем переменные при отмене
            currentTempUri = null
            currentTempFile = null
            currentCounterAddress = null
            currentCounterNumber = null
        }
    }
    
    // Менеджер разрешений
    private lateinit var permissionManager: PermissionManager
    
    // Менеджер камеры
    private lateinit var cameraManager: CameraManager
    
    // Переменные для обработки результата камеры
    private var currentTempUri: Uri? = null
    private var currentTempFile: java.io.File? = null
    private var currentCounterAddress: String? = null
    private var currentCounterNumber: String? = null
    
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
            val view = LocalView.current
            
            // Настраиваем системную панель статуса в зависимости от темы
            LaunchedEffect(isDarkTheme) {
                val window = (view.context as? Activity)?.window
                window?.let {
                    WindowCompat.setDecorFitsSystemWindows(it, false)
                    it.statusBarColor = android.graphics.Color.TRANSPARENT
                    // Устанавливаем цвет иконок статус-бара: светлые для темной темы, темные для светлой
                    val insetsController = WindowCompat.getInsetsController(it, view)
                    insetsController.isAppearanceLightStatusBars = !isDarkTheme
                }
            }
            
            // Проверка версии приложения
            var showUpdateDialog by remember { mutableStateOf(false) }
            var latestVersion by remember { mutableStateOf<String?>(null) }
            var currentVersion by remember { mutableStateOf("") }
            
            // Получаем текущую версию приложения
            LaunchedEffect(Unit) {
                try {
                    val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                    currentVersion = packageInfo.versionName ?: "0.0.0"
                    Logger.log("Current app version: $currentVersion", "VERSION_CHECK")
                } catch (e: Exception) {
                    Logger.logError("Error getting app version", e)
                    currentVersion = "0.0.0"
                }
            }
            
            // Проверяем версию при запуске, если есть интернет
            LaunchedEffect(Unit) {
                if (VersionChecker.isNetworkAvailable(context)) {
                    delay(1000) // Небольшая задержка, чтобы не мешать запуску приложения
                    try {
                        val latest = VersionChecker.getLatestVersion()
                        if (latest != null) {
                            latestVersion = latest
                            if (VersionChecker.isUpdateAvailable(currentVersion, latest)) {
                                Logger.log("Update available: $currentVersion -> $latest", "VERSION_CHECK")
                                showUpdateDialog = true
                            } else {
                                Logger.log("App is up to date: $currentVersion", "VERSION_CHECK")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.logError("Error checking for updates", e)
                    }
                } else {
                    Logger.log("No internet connection, skipping version check", "VERSION_CHECK")
                }
            }
            
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
                                onCameraDataReady = { uri, counterAddress, counterNumber, tempFile ->
                                    currentTempUri = uri
                                    currentTempFile = tempFile
                                    currentCounterAddress = counterAddress
                                    currentCounterNumber = counterNumber
                                }
                            )
                            
                            // Диалог обновления
                            if (showUpdateDialog && latestVersion != null) {
                                UpdateDialog(
                                    currentVersion = currentVersion,
                                    latestVersion = latestVersion!!,
                                    onDismiss = { showUpdateDialog = false }
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}


