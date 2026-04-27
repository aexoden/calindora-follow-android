package com.calindora.follow

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LocationReportEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
  abstract fun locationReportDao(): LocationReportDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase {
      return INSTANCE
          ?: synchronized(this) {
            val instance =
                Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "location_reports_database",
                    )
                    // The v1 schema was never exported, so a Migration(1, 2) can't be written
                    // reliably. Allow destructive migration only from v1. From v2 onward, every
                    // schema bump must ship an explicit Migration.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
            INSTANCE = instance
            instance
          }
    }
  }
}
