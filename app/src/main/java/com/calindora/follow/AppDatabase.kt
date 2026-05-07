package com.calindora.follow

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LocationReportEntity::class], version = 6, exportSchema = true)
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

    private val MIGRATION_3_4 =
        object : Migration(3, 4) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_submittedAt_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`submittedAt`, `permanentlyFailed`, `timestamp`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`permanentlyFailed`, `timestamp`)"
            )
          }
        }

    private val MIGRATION_4_5 =
        object : Migration(4, 5) {
          override fun migrate(db: SupportSQLiteDatabase) {
            // The `body` column was a serialization cache of fields already stored as typed
            // columns. SQLite shipped with minSdk 33 (Android 13) is < 3.35 and does not support
            // `ALTER TABLE ... DROP COLUMN`, so we rebuild the table.
            db.execSQL(
                "CREATE TABLE `location_reports_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`latitude` REAL NOT NULL, " +
                    "`longitude` REAL NOT NULL, " +
                    "`altitude` REAL NOT NULL, " +
                    "`speed` REAL NOT NULL, " +
                    "`bearing` REAL NOT NULL, " +
                    "`accuracy` REAL NOT NULL, " +
                    "`signatureInput` TEXT NOT NULL, " +
                    "`permanentlyFailed` INTEGER NOT NULL, " +
                    "`permanentFailureCode` INTEGER NOT NULL, " +
                    "`permanentFailureReason` TEXT NOT NULL, " +
                    "`submissionAttempts` INTEGER NOT NULL, " +
                    "`submittedAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")"
            )
            db.execSQL(
                "INSERT INTO `location_reports_new` (" +
                    "`id`, `timestamp`, `latitude`, `longitude`, `altitude`, " +
                    "`speed`, `bearing`, `accuracy`, `signatureInput`, " +
                    "`permanentlyFailed`, `permanentFailureCode`, `permanentFailureReason`, " +
                    "`submissionAttempts`, `submittedAt`, `createdAt`" +
                    ") SELECT " +
                    "`id`, `timestamp`, `latitude`, `longitude`, `altitude`, " +
                    "`speed`, `bearing`, `accuracy`, `signatureInput`, " +
                    "`permanentlyFailed`, `permanentFailureCode`, `permanentFailureReason`, " +
                    "`submissionAttempts`, `submittedAt`, `createdAt` " +
                    "FROM `location_reports`"
            )
            db.execSQL("DROP TABLE `location_reports`")
            db.execSQL("ALTER TABLE `location_reports_new` RENAME TO `location_reports`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_submittedAt_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`submittedAt`, `permanentlyFailed`, `timestamp`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`permanentlyFailed`, `timestamp`)"
            )
          }
        }

    private val MIGRATION_5_6 =
        object : Migration(5, 6) {
          override fun migrate(db: SupportSQLiteDatabase) {
            // The `signatureInput` column was a serialization cache of fields already stored as
            // typed columns. SQLite shipped with minSdk 33 (Android 13) is < 3.35 and does not
            // support `ALTER TABLE ... DROP COLUMN`, so we rebuild the table.
            db.execSQL(
                "CREATE TABLE `location_reports_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`latitude` REAL NOT NULL, " +
                    "`longitude` REAL NOT NULL, " +
                    "`altitude` REAL NOT NULL, " +
                    "`speed` REAL NOT NULL, " +
                    "`bearing` REAL NOT NULL, " +
                    "`accuracy` REAL NOT NULL, " +
                    "`permanentlyFailed` INTEGER NOT NULL, " +
                    "`permanentFailureCode` INTEGER NOT NULL, " +
                    "`permanentFailureReason` TEXT NOT NULL, " +
                    "`submissionAttempts` INTEGER NOT NULL, " +
                    "`submittedAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")"
            )
            db.execSQL(
                "INSERT INTO `location_reports_new` (" +
                    "`id`, `timestamp`, `latitude`, `longitude`, `altitude`, " +
                    "`speed`, `bearing`, `accuracy`, " +
                    "`permanentlyFailed`, `permanentFailureCode`, `permanentFailureReason`, " +
                    "`submissionAttempts`, `submittedAt`, `createdAt`" +
                    ") SELECT " +
                    "`id`, `timestamp`, `latitude`, `longitude`, `altitude`, " +
                    "`speed`, `bearing`, `accuracy`, " +
                    "`permanentlyFailed`, `permanentFailureCode`, `permanentFailureReason`, " +
                    "`submissionAttempts`, `submittedAt`, `createdAt` " +
                    "FROM `location_reports`"
            )
            db.execSQL("DROP TABLE `location_reports`")
            db.execSQL("ALTER TABLE `location_reports_new` RENAME TO `location_reports`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_submittedAt_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`submittedAt`, `permanentlyFailed`, `timestamp`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_location_reports_permanentlyFailed_timestamp` " +
                    "ON `location_reports` (`permanentlyFailed`, `timestamp`)"
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
                    // reliably. Allow destructive migration only from v1.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .build()
            INSTANCE = instance
            instance
          }
    }
  }
}
