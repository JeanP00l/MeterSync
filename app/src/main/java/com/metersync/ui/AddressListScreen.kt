package com.metersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metersync.data.entity.Address
import com.metersync.data.entity.Meter
import com.metersync.data.entity.MeterStatus
import com.metersync.viewmodel.MeterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SearchResult {
    data class Address(val address: com.metersync.data.entity.Address) : SearchResult()
    data class Meter(val address: com.metersync.data.entity.Address, val meter: com.metersync.data.entity.Meter) : SearchResult()
}

data class AddressStats(
    val checkedCount: Int,
    val totalCount: Int,
    val percentage: Int
)

@Composable
fun AddressListScreen(onOpenAddress: (Long) -> Unit, vm: MeterViewModel = viewModel()) {
    val addresses = vm.addresses.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showBulkLoadDialog by remember { mutableStateOf(false) }
    val loading by vm.loading.collectAsState()
    val bulkLoading by vm.bulkLoading.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Список адресов",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Поле поиска
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Поиск по адресу или номеру счетчика") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
        
        // Кнопка загрузки всех счетчиков
        Button(
            onClick = { showBulkLoadDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = !bulkLoading && addresses.value.isNotEmpty()
        ) {
            Text("Загрузить все счетчики")
        }
        
        // Прогресс бар загрузки
        if (bulkLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Загрузка всех счетчиков...")
            }
        }
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                // Показываем результаты поиска - все адреса, но SearchResultItem покажет только те, где есть совпадения
                items(addresses.value) { address ->
                    SearchResultItem(
                        address = address,
                        searchQuery = searchQuery,
                        onOpenAddress = onOpenAddress,
                        vm = vm
                    )
                }
            } else {
                // Показываем обычный список адресов
                items(addresses.value) { address ->
                    AddressItem(
                        address = address.fullAddress,
                        addressId = address.id,
                        onClick = { onOpenAddress(address.id) },
                        vm = vm
                    )
                }
            }
        }
        
        // Диалог подтверждения загрузки всех счетчиков
        if (showBulkLoadDialog) {
            AlertDialog(
                onDismissRequest = { showBulkLoadDialog = false },
                title = { Text("Подтверждение") },
                text = { Text("Загрузка всех счетчиков может занять долгое время. Продолжить?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showBulkLoadDialog = false
                            vm.loadAllMeters()
                        }
                    ) {
                        Text("Да")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showBulkLoadDialog = false }
                    ) {
                        Text("Нет")
                    }
                }
            )
        }
    }
}

@Composable
fun AddressItem(address: String, addressId: Long, onClick: () -> Unit, vm: MeterViewModel) {
    val meters = vm.metersByAddress(addressId).collectAsState(initial = emptyList())
    var addressStats by remember { mutableStateOf(AddressStats(0, 0, 0)) }
    
    // Загружаем статистику адреса
    LaunchedEffect(meters.value.size) {
        if (meters.value.isNotEmpty()) {
            val checkedCount = withContext(Dispatchers.IO) { vm.getCheckedMetersCountByAddress(addressId) }
            val totalCount = withContext(Dispatchers.IO) { vm.getTotalMetersCountByAddress(addressId) }
            val percentage = if (totalCount > 0) {
                ((checkedCount.toFloat() / totalCount.toFloat()) * 100).toInt()
            } else 0
            
            addressStats = AddressStats(checkedCount, totalCount, percentage)
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = address,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Счетчиков: ${meters.value.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Статистика прогресса
            if (addressStats.totalCount > 0) {
                Text(
                    text = "${addressStats.checkedCount}/${addressStats.totalCount} (${addressStats.percentage}%)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    
    // Разделитель между элементами
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}


@Composable
fun SearchResultItem(address: Address, searchQuery: String, onOpenAddress: (Long) -> Unit, vm: MeterViewModel) {
    val meters = vm.metersByAddress(address.id).collectAsState(initial = emptyList())
    
    // Проверяем, есть ли совпадения в адресе или счетчиках
    val addressMatches = address.fullAddress.contains(searchQuery, ignoreCase = true)
    val matchingMeters = meters.value.filter { meter ->
        meter.apartment.contains(searchQuery, ignoreCase = true) ||
        meter.meterNumber.contains(searchQuery, ignoreCase = true)
    }
    
    // Показываем элемент только если есть совпадения
    if (addressMatches || matchingMeters.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAddress(address.id) },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                if (addressMatches) {
                    Text(
                        text = "📍 ${address.fullAddress}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Адрес",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                matchingMeters.forEach { meter ->
                    // Определяем цвет номера счетчика в зависимости от статуса
                    val meterColor = when (meter.status) {
                        MeterStatus.LOADED -> Color(0xFF2E7D32) // Темно-зеленый для загруженных
                        MeterStatus.CHECKED_NOT_LOADED -> Color(0xFF388E3C) // Зеленый для проверенных, но не загруженных
                        MeterStatus.NOT_CHECKED -> Color(0xFFD32F2F) // Красный для не проверенных
                    }
                    
                    Text(
                        text = "🔍 ${meter.apartment}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = if (addressMatches) 8.dp else 0.dp)
                    )
                    Text(
                        text = "№${meter.meterNumber}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = meterColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!addressMatches) {
                        Text(
                            text = "Адрес: ${address.fullAddress}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        
        // Разделитель между элементами
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}


