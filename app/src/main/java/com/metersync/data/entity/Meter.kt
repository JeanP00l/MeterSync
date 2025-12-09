package com.metersync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meters")
data class Meter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val addressId: Long,
    val apartment: String,
    val meterNumber: String,
    val loadedAt: Long = System.currentTimeMillis(),
    val status: MeterStatus = MeterStatus.NOT_CHECKED,
    val isPhotographed: Boolean = false
)

enum class MeterStatus {
    NOT_CHECKED,        // Не проверен (красный значок)
    CHECKED_NOT_LOADED, // Проверен, не загружен (зеленый значок)
    LOADED              // Загружен (зеленый значок облачка)
}


