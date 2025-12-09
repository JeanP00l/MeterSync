package com.metersync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "addresses")
data class Address(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullAddress: String,
    val loadedAt: Long = System.currentTimeMillis()
)


