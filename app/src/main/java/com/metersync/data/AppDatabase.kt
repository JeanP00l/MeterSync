package com.metersync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.metersync.data.dao.AddressDao
import com.metersync.data.dao.MeterDao
import com.metersync.data.entity.Address
import com.metersync.data.entity.Meter

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.metersync.data.entity.MeterStatus

@Database(entities = [Address::class, Meter::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun addressDao(): AddressDao
    abstract fun meterDao(): MeterDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Добавляем колонку status в таблицу meters
                database.execSQL("ALTER TABLE meters ADD COLUMN status TEXT NOT NULL DEFAULT 'NOT_CHECKED'")
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Добавляем колонку isPhotographed в таблицу meters
                database.execSQL("ALTER TABLE meters ADD COLUMN isPhotographed INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "metersync.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }
}


