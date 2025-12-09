package com.metersync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metersync.utils.Logger
import com.metersync.viewmodel.MeterViewModel

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, vm: MeterViewModel = viewModel()) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var enableLogging by remember { mutableStateOf(Logger.isLoggingEnabled()) }
    
    // Статистика счетчиков
    var totalMeters by remember { mutableStateOf(0) }
    var notCheckedMeters by remember { mutableStateOf(0) }
    var checkedNotLoadedMeters by remember { mutableStateOf(0) }
    var loadedMeters by remember { mutableStateOf(0) }

    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val addresses = vm.addresses.collectAsState(initial = emptyList())
    val isLoggingOut by vm.isLoggingOut.collectAsState()

    // Загружаем статистику счетчиков
    LaunchedEffect(addresses.value.size) {
        if (addresses.value.isNotEmpty()) {
            totalMeters = vm.getTotalMetersCount()
            notCheckedMeters = vm.getNotCheckedCount()
            checkedNotLoadedMeters = vm.getCheckedNotLoadedCount()
            loadedMeters = vm.getLoadedCount()
        }
    }
    
    // Обновляем состояние логирования при изменении чекбокса
    LaunchedEffect(enableLogging) {
        Logger.setLoggingEnabled(enableLogging)
    }

    Logger.logUI("LoginScreen composed")

    Column(
        modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Чекбокс для управления логированием
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enableLogging,
                onCheckedChange = { enableLogging = it }
            )
            Text(
                text = "Записывать логи",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 14.sp
            )
        }
        
        OutlinedTextField(
            value = login, 
            onValueChange = { 
                login = it
                Logger.logUI("Login field changed: ${it.take(3)}***")
            }, 
            label = { Text("Логин") },
            enabled = !loading && !isLoggingOut
        )
        OutlinedTextField(
            value = password, 
            onValueChange = { 
                password = it
                Logger.logUI("Password field changed")
            }, 
            label = { Text("Пароль") },
            enabled = !loading && !isLoggingOut
        )
        
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                Text("Загрузка...", modifier = Modifier.padding(top = 8.dp))
                Logger.logUI("Showing loading indicator")
            } else if (isLoggingOut) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                Text("Выход из системы...", modifier = Modifier.padding(top = 8.dp))
                Logger.logUI("Showing logout indicator")
            } else {
                Button(
                    onClick = {
                        Logger.logUI("Login button clicked")
                        if (login.isNotBlank() && password.isNotBlank()) {
                            Logger.logUI("Starting login process")
                            vm.saveCredentials(login, password)
                            vm.loadAddressesOnceIfNeeded {
                                Logger.logUI("Login process completed, error: $error")
                                // Only navigate on success (no error)
                                if (error == null) {
                                    Logger.logUI("Login successful")
                                    onLoginSuccess()
                                } else {
                                    Logger.logUI("Login failed, staying on login screen")
                                }
                            }
                        } else {
                            Logger.logUI("Login button clicked but fields are empty")
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                    enabled = login.isNotBlank() && password.isNotBlank() && !isLoggingOut
                ) {
                    Text("Вход")
                }
                
                Button(
                    onClick = {
                        Logger.logUI("Clear cache button clicked")
                        showClearCacheDialog = true
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = !loading && !isLoggingOut
                ) {
                    Text("Удалить кэш")
                }
            }

            error?.let {
                Logger.logUI("Showing error: $it")
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = Color.Red
                )
            }
            
            // Отображение количества загруженных адресов
            Text(
                text = "Загружено адресов: ${addresses.value.size}",
                modifier = Modifier.padding(top = 16.dp),
                fontSize = 14.sp,
                color = Color.Gray
            )
            
            // Статистика счетчиков
            if (addresses.value.isNotEmpty() && totalMeters > 0) {
                Text(
                    text = "Всего счетчиков: $totalMeters",
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                
                Text(
                    text = "🔴 Не проверены: $notCheckedMeters",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = Color.Red
                )
                
                Text(
                    text = "🟢 Проверены, не загружены: $checkedNotLoadedMeters",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = Color(0xFF95CAB4)
                )
                
                Text(
                    text = "☁️ Загружены: $loadedMeters",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = Color(0xFF95CAB4)
                )
            }
        }

        // Диалог подтверждения очистки кэша
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("Подтверждение") },
                text = { Text("При нажатии на \"Очистить\" будут удалены все данные приложения, включая адреса и счетчики. Это действие нельзя отменить.") },
                confirmButton = {
                    Button(
                        onClick = {
                            Logger.logUI("Cache clear confirmed")
                            vm.clearCache()
                            showClearCacheDialog = false
                        }
                    ) {
                        Text("Очистить")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearCacheDialog = false }
                    ) {
                        Text("Нет")
                    }
                }
            )
        }
}


