package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [ScannedDocumentEntity::class, ScannedPageEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class ClearScanDatabase : RoomDatabase() {
  abstract fun scanDao(): ScanDao

  companion object {
    @Volatile private var INSTANCE: ClearScanDatabase? = null

    fun getInstance(context: Context): ClearScanDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          ClearScanDatabase::class.java,
          "clearscan_db",
        ).build().also { INSTANCE = it }
      }
    }
  }
}
