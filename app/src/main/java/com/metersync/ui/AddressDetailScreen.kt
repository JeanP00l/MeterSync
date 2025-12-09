package com.metersync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metersync.data.entity.MeterStatus
import com.metersync.utils.Logger
import com.metersync.viewmodel.MeterViewModel
import com.metersync.camera.CameraManager
import com.metersync.camera.rememberCameraManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

enum class MeterFilter {
    ALL,           // Все счетчики
    CHECKED,       // Проверенные (CHECKED_NOT_LOADED + LOADED)
    NOT_CHECKED    // Не проверенные (NOT_CHECKED)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressDetailScreen(
    addressId: Long, 
    onBack: () -> Unit, 
    cameraLauncher: androidx.activity.result.ActivityResultLauncher<Intent>?,
    onCameraDataReady: (Uri, String) -> Unit,
    vm: MeterViewModel = viewModel()
) {
    val addresses = vm.addresses.collectAsState(initial = emptyList())
    val meters = vm.metersByAddress(addressId).collectAsState(initial = emptyList())
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val bulkLoading by vm.bulkLoading.collectAsState()
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(MeterFilter.ALL) }
    var expanded by remember { mutableStateOf(false) }
    
    // Камера - получаем launcher из MainActivity
    val cameraManager = rememberCameraManager()
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    // Функция проверки разрешений
    fun checkCameraPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val storagePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        return cameraPermission == PackageManager.PERMISSION_GRANTED && 
               storagePermission == PackageManager.PERMISSION_GRANTED
    }

    // Находим адрес по ID
    val currentAddress = addresses.value.find { it.id == addressId }

    // Фильтруем счетчики по поисковому запросу и статусу
    val filteredMeters = remember(meters.value, searchQuery, selectedFilter) {
        var filtered = meters.value
        
        // Фильтр по статусу
        filtered = when (selectedFilter) {
            MeterFilter.ALL -> filtered
            MeterFilter.CHECKED -> filtered.filter { meter ->
                meter.status == MeterStatus.CHECKED_NOT_LOADED || meter.status == MeterStatus.LOADED
            }
            MeterFilter.NOT_CHECKED -> filtered.filter { meter ->
                meter.status == MeterStatus.NOT_CHECKED
            }
        }
        
        // Фильтр по поисковому запросу
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { meter ->
                meter.apartment.contains(searchQuery, ignoreCase = true) ||
                meter.meterNumber.contains(searchQuery, ignoreCase = true)
            }
        }
        
        filtered
    }

    // Логирование для отладки
    Logger.logUI("AddressDetailScreen composed for addressId: $addressId, meters count: ${meters.value.size}")
    if (meters.value.isNotEmpty()) {
        Logger.logUI("First meter: ${meters.value.first().apartment} - ${meters.value.first().meterNumber}")
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack, enabled = !loading) { Text("Назад") }

        Button(
            onClick = {
                // Используем реальный адрес из списка адресов
                val addressText = currentAddress?.fullAddress ?: "Unknown"
                vm.refreshMetersForAddress(addressId, addressText)
            },
            modifier = Modifier.padding(top = 8.dp),
            enabled = !loading && !bulkLoading
        ) {
            Text("Загрузить счетчики")
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            Text("Загрузка счетчиков...", modifier = Modifier.padding(top = 8.dp))
            Text("Пожалуйста, подождите", 
                 modifier = Modifier.padding(top = 4.dp),
                 fontSize = 12.sp,
                 color = Color.Gray)
        }

        error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                color = Color.Red
            )
        }

            // Поле поиска и фильтр
            if (meters.value.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Поле поиска
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Поиск") },
                        modifier = Modifier.weight(1f),
                        enabled = !loading
                    )
                    
                    // Фильтр по статусу
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = when (selectedFilter) {
                                MeterFilter.ALL -> "Все"
                                MeterFilter.CHECKED -> "Проверены"
                                MeterFilter.NOT_CHECKED -> "Не проверены"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Фильтр") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .width(120.dp),
                            enabled = !loading
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Все") },
                                onClick = {
                                    selectedFilter = MeterFilter.ALL
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Проверены") },
                                onClick = {
                                    selectedFilter = MeterFilter.CHECKED
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Не проверены") },
                                onClick = {
                                    selectedFilter = MeterFilter.NOT_CHECKED
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                if (meters.value.isEmpty()) {
                    items(listOf("Данные не загружены")) { row ->
                        Text(
                            text = row,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                    }
                } else {
                    if (filteredMeters.isEmpty() && searchQuery.isNotBlank()) {
                        items(listOf("Счетчики не найдены")) { row ->
                            Text(
                                text = row,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                        }
                    } else {
                        items(filteredMeters) { meter ->
                            MeterItem(
                                apartment = meter.apartment,
                                meterNumber = meter.meterNumber,
                                status = meter.status,
                                onCameraClick = {
                                    if (checkCameraPermissions()) {
                                        // Создаем временный URI для изображения
                                        val tempUri = cameraManager.createTempImageUri()
                                        val meterInfo = "${meter.apartment}      №${meter.meterNumber}"
                                        
                                        // Передаем данные в MainActivity
                                        onCameraDataReady(tempUri, meterInfo)
                                        
                                        // Создаем Intent для камеры
                                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                            putExtra(MediaStore.EXTRA_OUTPUT, tempUri)
                                        }
                                        
                                        // Запускаем камеру через MainActivity
                                        cameraLauncher?.launch(cameraIntent)
                                    } else {
                                        // Показываем диалог о необходимости разрешений
                                        showPermissionDialog = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
    }
    
    // Диалог о разрешениях
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Необходимы разрешения") },
            text = { Text("Для фотографирования счетчиков необходимо разрешение на использование камеры и доступ к галерее. Пожалуйста, предоставьте разрешения в настройках приложения.") },
            confirmButton = {
                TextButton(
                    onClick = { showPermissionDialog = false }
                ) {
                    Text("Понятно")
                }
            }
        )
    }
}

@Composable
fun MeterItem(apartment: String, meterNumber: String, status: MeterStatus, onCameraClick: () -> Unit) {
    // Определяем цвет номера счетчика в зависимости от статуса
    val meterColor = when (status) {
        MeterStatus.LOADED -> Color(0xFF2E7D32) // Темно-зеленый для загруженных
        MeterStatus.CHECKED_NOT_LOADED -> Color(0xFF388E3C) // Зеленый для проверенных, но не загруженных
        MeterStatus.NOT_CHECKED -> Color(0xFFD32F2F) // Красный для не проверенных
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = apartment,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Gray
            )
            Text(
                text = "№$meterNumber",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = meterColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        // Кнопка камеры
        Button(
            onClick = onCameraClick,
            modifier = Modifier.size(40.dp)
        ) {
            Text(
                text = "📷",
                fontSize = 16.sp
            )
        }
    }

    // Разделитель между элементами
    HorizontalDivider(
        color = Color.Gray.copy(alpha = 0.3f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 8.dp)
    )
}


