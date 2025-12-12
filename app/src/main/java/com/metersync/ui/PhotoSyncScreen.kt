package com.metersync.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.integration.android.IntentIntegrator
import com.metersync.sync.PhotoSyncManager
import com.metersync.sync.PhotoInfo
import com.metersync.sync.SyncSessionData
import com.metersync.utils.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val syncManager = remember { PhotoSyncManager(context) }
    
    var connectionMode by remember { mutableStateOf<ConnectionMode?>(null) } // null, QR_SCAN, MANUAL
    var tokenInput by remember { mutableStateOf("") }
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("8080") }
    var sessionData by remember { mutableStateOf<SyncSessionData?>(null) }
    var photosFound by remember { mutableStateOf(0) }
    var isScanningPhotos by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf(0 to 0) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var syncComplete by remember { mutableStateOf(false) }
    var originalOrientation by remember { mutableStateOf(-1) } // Сохраняем оригинальную ориентацию
    
    // Launcher для сканирования QR-кодов
    val qrScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Восстанавливаем ориентацию после сканирования (в любом случае - успех или отмена)
        if (activity != null && originalOrientation != -1) {
            try {
                activity.requestedOrientation = originalOrientation
            } catch (e: Exception) {
                // Игнорируем ошибки восстановления ориентации
            }
            originalOrientation = -1
        }
        
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // Пробуем распарсить результат через IntentIntegrator (для ZXing)
            val scanResult = IntentIntegrator.parseActivityResult(
                android.app.Activity.RESULT_OK,
                result.data
            )
            
            val qrContent = scanResult?.contents
                ?: result.data?.getStringExtra("SCAN_RESULT")
                ?: result.data?.getStringExtra(Intent.EXTRA_TEXT)
            
            qrContent?.let { content ->
                try {
                    // Парсим JSON из QR-кода
                    val parsedData = syncManager.parseSessionData(content)
                    
                    if (parsedData != null) {
                        sessionData = parsedData
                        connectionMode = null
                        
                        scope.launch {
                            try {
                                // Обновляем состояние в главном потоке
                                withContext(Dispatchers.Main) {
                                    isScanningPhotos = true
                                }
                                
                                val photos = syncManager.getPhotosWithEXIF()
                                
                                // Обновляем состояние в главном потоке
                                // Используем ensureActive() чтобы проверить, не была ли корутина отменена
                                withContext(Dispatchers.Main) {
                                    ensureActive()
                                    photosFound = photos.size
                                    isScanningPhotos = false
                                }
                            } catch (e: CancellationException) {
                                // Не обрабатываем отмену как ошибку - это нормально при завершении композиции
                                throw e // Пробрасываем дальше для правильной обработки отмены
                            } catch (e: Exception) {
                                Logger.logError("Error in photo scan coroutine", e)
                                
                                // Обновляем состояние только если корутина не была отменена
                                try {
                                    withContext(Dispatchers.Main) {
                                        isScanningPhotos = false
                                        syncError = "Ошибка поиска фото: ${e.message}"
                                    }
                                } catch (cancelException: CancellationException) {
                                    // Игнорируем отмену при обновлении состояния
                                }
                            }
                        }
                    } else {
                        syncError = "Не удалось распарсить данные из QR-кода. Убедитесь, что QR-код содержит правильный формат JSON."
                    }
                } catch (e: Exception) {
                    Logger.logError("Error parsing QR code data", e)
                    syncError = "Ошибка обработки QR-кода: ${e.message}"
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Синхронизация фото",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onBack) {
                Text("Назад")
            }
        }
        
        // Выбор способа подключения
        if (connectionMode == null && sessionData == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Выберите способ подключения:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Button(
                        onClick = {
                            if (activity != null) {
                                try {
                                    // Устанавливаем portrait ориентацию для Activity ПЕРЕД созданием integrator
                                    originalOrientation = activity.requestedOrientation
                                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    
                                    // Используем IntentIntegrator для открытия сканера QR-кодов
                                    val integrator = IntentIntegrator(activity)
                                    integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                                    integrator.setPrompt("Наведите камеру на QR-код")
                                    integrator.setCameraId(0)
                                    integrator.setBeepEnabled(false)
                                    integrator.setBarcodeImageEnabled(false)
                                    // Блокируем ориентацию в portrait, чтобы не нужно было поворачивать телефон
                                    integrator.setOrientationLocked(true)
                                    
                                    // Создаем Intent и устанавливаем ориентацию также через Intent extras
                                    val scanIntent = integrator.createScanIntent()
                                    // Убеждаемся, что ориентация передается через Intent
                                    scanIntent.putExtra("SCAN_ORIENTATION_LOCKED", true)
                                    
                                    // Добавляем небольшую задержку для применения ориентации перед запуском
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        qrScanLauncher.launch(scanIntent)
                                    }, 100)
                                } catch (e: Exception) {
                                    Logger.logError("Failed to launch QR scanner with IntentIntegrator", e)
                                    // Если IntentIntegrator не работает, пробуем открыть любое приложение для сканирования
                                    try {
                                        // Пробуем открыть Google Lens или другие приложения для сканирования
                                        val intent = Intent("com.google.zxing.client.android.SCAN").apply {
                                            putExtra("SCAN_MODE", "QR_CODE_MODE")
                                        }
                                        
                                        // Проверяем, есть ли приложение ZXing
                                        if (intent.resolveActivity(context.packageManager) != null) {
                                            qrScanLauncher.launch(intent)
                                        } else {
                                            // Если нет ZXing, пробуем открыть через Intent.ACTION_VIEW
                                            val alternativeIntent = Intent(Intent.ACTION_VIEW).apply {
                                                data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.zxing.client.android")
                                            }
                                            try {
                                                context.startActivity(alternativeIntent)
                                                syncError = "Установите приложение ZXing для сканирования QR-кодов или используйте ручной ввод."
                                            } catch (e2: Exception) {
                                                syncError = "Не найдено приложение для сканирования QR-кодов. Используйте ручной ввод."
                                            }
                                        }
                                    } catch (e2: Exception) {
                                        Logger.logError("Failed to open QR scanner alternative", e2)
                                        syncError = "Не удалось открыть сканер QR-кодов. Используйте ручной ввод."
                                    }
                                }
                            } else {
                                syncError = "Не удалось получить доступ к Activity. Используйте ручной ввод."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📷 Сканировать QR-код")
                    }
                    
                    OutlinedButton(
                        onClick = { connectionMode = ConnectionMode.MANUAL },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⌨️ Ввести токен вручную")
                    }
                }
            }
        }
        
        // Ручной ввод токена
        if (connectionMode == ConnectionMode.MANUAL) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Введите данные подключения:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Токен") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("IP адрес сервера") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("192.168.1.100") }
                    )
                    
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Порт") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { connectionMode = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Отмена")
                        }
                        
                        Button(
                            onClick = {
                                if (tokenInput.isNotBlank() && ipInput.isNotBlank() && portInput.isNotBlank()) {
                                    try {
                                        val port = portInput.toIntOrNull() ?: 8080
                                        val url = "http://$ipInput:$port/sync?token=$tokenInput"
                                        sessionData = SyncSessionData(
                                            token = tokenInput,
                                            url = url,
                                            localIP = ipInput,
                                            port = port
                                        )
                                        connectionMode = null
                                        scope.launch {
                                            isScanningPhotos = true
                                            val photos = syncManager.getPhotosWithEXIF()
                                            photosFound = photos.size
                                            isScanningPhotos = false
                                        }
                                    } catch (e: Exception) {
                                        Logger.logError("Failed to create session data", e)
                                        syncError = "Ошибка создания подключения: ${e.message}"
                                    }
                                } else {
                                    syncError = "Заполните все поля"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = tokenInput.isNotBlank() && ipInput.isNotBlank() && portInput.isNotBlank()
                        ) {
                            Text("Подключиться")
                        }
                    }
                }
            }
        }
        
        // Информация о подключении
        sessionData?.let { session ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "✅ Подключено",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "IP: ${session.localIP}:${session.port}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Токен: ${session.token.take(20)}...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // Поиск фото
            if (isScanningPhotos) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Поиск фото...")
                    }
                }
            } else if (photosFound > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Найдено фото: $photosFound",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        if (!isSyncing && !syncComplete) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isSyncing = true
                                        syncError = null
                                        try {
                                            startSync(session, syncManager) { current, total ->
                                                syncProgress = current to total
                                            }
                                            syncComplete = true
                                        } catch (e: Exception) {
                                            Logger.logError("Sync failed", e)
                                            syncError = "Ошибка синхронизации: ${e.message}"
                                        } finally {
                                            isSyncing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Начать синхронизацию")
                            }
                        }
                    }
                }
            } else if (photosFound == 0 && !isScanningPhotos) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Фото не найдены",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Убедитесь, что фото были сделаны через приложение MeterSync и содержат EXIF метаданные USER_COMMENT",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            // Прогресс синхронизации
            if (isSyncing) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Синхронизация...",
                            style = MaterialTheme.typography.titleMedium
                        )
                        LinearProgressIndicator(
                            progress = { 
                                if (syncProgress.second > 0) {
                                    syncProgress.first.toFloat() / syncProgress.second.toFloat()
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${syncProgress.first} / ${syncProgress.second}",
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            // Завершение синхронизации
            if (syncComplete) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✅ Синхронизация завершена!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "Загружено: ${syncProgress.first} фото",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
        
        // Ошибки
        syncError?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { syncError = null }) {
                        Text("✕", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

private enum class ConnectionMode {
    QR_SCAN,
    MANUAL
}

private suspend fun startSync(
    sessionData: SyncSessionData,
    syncManager: PhotoSyncManager,
    progressCallback: (Int, Int) -> Unit
) {
    // Получаем список фото
    val photos = syncManager.getPhotosWithEXIF()
    Logger.log("Starting sync with ${photos.size} photos", "SYNC")
    
    if (photos.isEmpty()) {
        throw Exception("Нет фото для синхронизации")
    }
    
    // Инициализируем синхронизацию с реальным количеством фото
    val initSuccess = syncManager.initSync(sessionData, photos.size)
    if (!initSuccess) {
        throw Exception("Не удалось инициализировать синхронизацию")
    }
    
    // Загружаем каждое фото
    var uploaded = 0
    for ((index, photo) in photos.withIndex()) {
        Logger.log("Uploading photo ${index + 1}/${photos.size}: ${photo.name}", "SYNC")
        val success = syncManager.uploadPhoto(sessionData, photo) { _, _ ->
            // Progress callback для отдельного фото не используется
        }
        if (success) {
            uploaded++
            Logger.log("Successfully uploaded: ${photo.name} ($uploaded/${photos.size})", "SYNC")
        } else {
            Logger.logError("Failed to upload: ${photo.name}", null)
        }
        progressCallback(uploaded, photos.size)
    }
    
    Logger.log("Sync completed: $uploaded/${photos.size} photos uploaded", "SYNC")
}

