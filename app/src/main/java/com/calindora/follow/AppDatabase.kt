package com.calindora.follow

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LocationReportEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
  abstract fun locationReportDao(): LocationReportDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE location_reports ADD COLUMN permanentFailureCode INTEGER NOT NULL DEFAULT 0"
            )

            // Attempt to set the failure code for existing permanently failed reports.
            db.execSQL(
                "UPDATE location_reports SET permanentFailureCode = 401 " +
                    "WHERE permanentlyFailed = true AND permanentFailureReason LIKE '%401:%'"
            )
          }
        }

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
                    .addMigrations(MIGRATION_2_3)
                    .build()
            INSTANCE = instance
            instance
          }
    }
  }
}
