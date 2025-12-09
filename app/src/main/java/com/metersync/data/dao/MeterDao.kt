package com.metersync.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.metersync.data.entity.Meter
import kotlinx.coroutines.flow.Flow

import com.metersync.data.entity.MeterStatus

@Dao
interface MeterDao {
    @Query("SELECT * FROM meters WHERE addressId = :addressId ORDER BY CAST(SUBSTR(apartment, INSTR(apartment, ', ') + 2) AS INTEGER)")
    fun getByAddress(addressId: Long): Flow<List<Meter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(meters: List<Meter>)

    @Query("DELETE FROM meters WHERE addressId = :addressId")
    suspend fun deleteByAddress(addressId: Long)

    @Query("DELETE FROM meters")
    suspend fun deleteAll()
    
    // Статистика по состояниям счетчиков
    @Query("SELECT COUNT(*) FROM meters")
    suspend fun getTotalMetersCount(): Int
    
    @Query("SELECT COUNT(*) FROM meters WHERE status = 'NOT_CHECKED'")
    suspend fun getNotCheckedCount(): Int
    
    @Query("SELECT COUNT(*) FROM meters WHERE status = 'CHECKED_NOT_LOADED'")
    suspend fun getCheckedNotLoadedCount(): Int
    
    @Query("SELECT COUNT(*) FROM meters WHERE status = 'LOADED'")
    suspend fun getLoadedCount(): Int
    
    // Статистика по конкретному адресу
    @Query("SELECT COUNT(*) FROM meters WHERE addressId = :addressId")
    suspend fun getTotalMetersCountByAddress(addressId: Long): Int
    
    @Query("SELECT COUNT(*) FROM meters WHERE addressId = :addressId AND (status = 'CHECKED_NOT_LOADED' OR status = 'LOADED')")
    suspend fun getCheckedMetersCountByAddress(addressId: Long): Int
    
    // Обновление статуса счетчика
    @Query("UPDATE meters SET status = :status WHERE id = :meterId")
    suspend fun updateMeterStatus(meterId: Long, status: MeterStatus)
    
    // Обновление флага фотографирования счетчика
    @Query("UPDATE meters SET isPhotographed = :isPhotographed WHERE id = :meterId")
    suspend fun updateMeterPhotographed(meterId: Long, isPhotographed: Boolean)
}


