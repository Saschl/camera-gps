package com.sasch.cameragps.sharednew.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.logging.LogDao
import com.sasch.cameragps.sharednew.database.logging.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [LogEntry::class, CameraDevice::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, LogDatabase.DeleteOldColumn::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5)
    ]
)
@ConstructedBy(LogDatabaseConstructor::class)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun cameraDeviceDao(): CameraDeviceDAO

    /*companion object {
        @Volatile
        private var INSTANCE: LogDatabase? = null

        fun getDatabase(context: Context): LogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    "log_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }*/


    @DeleteColumn(
        tableName = "camera_devices",
        columnName = "transmitTimezoneAndDst"
    )
    class DeleteOldColumn : AutoMigrationSpec

    companion object {
        private var instance: LogDatabase? = null

        fun getRoomDatabase(
            builder: Builder<LogDatabase>
        ): LogDatabase {
            return instance ?: builder
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
                .also { instance = it }
        }
    }
}

expect object LogDatabaseConstructor : RoomDatabaseConstructor<LogDatabase> {
    override fun initialize(): LogDatabase
}