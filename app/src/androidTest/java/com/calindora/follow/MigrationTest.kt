package com.calindora.follow

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
  @get:Rule
  val helper: MigrationTestHelper =
      MigrationTestHelper(
          InstrumentationRegistry.getInstrumentation(),
          AppDatabase::class.java,
      )

  @Test
  fun migrate2To3_addsPermanentFailureCodeAndBackfillsKnown401s() {
    helper.createDatabase(TEST_DB, 2).use { db ->
      // 401 row that should be backfilled to permanentFailureCode = 401.
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, body, permanentlyFailed, permanentFailureReason,
            submissionAttempts, submittedAt, createdAt
          ) VALUES (
            1, 1000, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
            'sig1', 'body1', 1, 'HTTP 401: Unauthorized', 5, 0, 100
          )
          """
              .trimIndent()
      )
      // Permanently-failed row with a non-401 reason; should remain code 0.
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, body, permanentlyFailed, permanentFailureReason,
            submissionAttempts, submittedAt, createdAt
          ) VALUES (
            2, 2000, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
            'sig2', 'body2', 1, 'Some other failure', 5, 0, 200
          )
          """
              .trimIndent()
      )
      // Unsubmitted row; not permanently failed; code stays 0.
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, body, permanentlyFailed, permanentFailureReason,
            submissionAttempts, submittedAt, createdAt
          ) VALUES (
            3, 3000, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
            'sig3', 'body3', 0, '', 0, 0, 300
          )
          """
              .trimIndent()
      )
    }

    helper
        .runMigrationsAndValidate(
            TEST_DB,
            3,
            /* validateDroppedTables = */ true,
            AppDatabase.MIGRATION_2_3,
        )
        .use { db ->
          db.query("SELECT id, permanentFailureCode FROM location_reports ORDER BY id").use { cursor
            ->
            assertTrue(cursor.moveToNext())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(401, cursor.getInt(1))

            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))

            assertTrue(cursor.moveToNext())
            assertEquals(3L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))

            assertTrue(!cursor.moveToNext())
          }
        }
  }

  @Test
  fun migrate3To4_createsExpectedIndices() {
    helper.createDatabase(TEST_DB, 3).close()
    helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4).use { db ->
      val indexNames = mutableSetOf<String>()
      db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='location_reports'")
          .use { cursor -> while (cursor.moveToNext()) indexNames.add(cursor.getString(0)) }
      assertTrue(
          indexNames.contains("index_location_reports_submittedAt_permanentlyFailed_timestamp")
      )
      assertTrue(indexNames.contains("index_location_reports_permanentlyFailed_timestamp"))
    }
  }

  @Test
  fun migrate4To5_dropsBodyColumnAndPreservesData() {
    helper.createDatabase(TEST_DB, 4).use { db ->
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, body, permanentlyFailed, permanentFailureCode,
            permanentFailureReason, submissionAttempts, submittedAt, createdAt
          ) VALUES (
            42, 1000, 47.6588, -117.426, 600.0, 1.5, 90.0, 5.0,
            'sigA', 'this body should be discarded', 1, 401,
            'HTTP 401: Unauthorized', 3, 0, 12345
          )
          """
              .trimIndent()
      )
    }

    helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5).use { db ->
      db.query(
              """
              SELECT id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
                     signatureInput, permanentlyFailed, permanentFailureCode,
                     permanentFailureReason, submissionAttempts, submittedAt, createdAt
              FROM location_reports
              """
                  .trimIndent()
          )
          .use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals(42L, cursor.getLong(0))
            assertEquals(1000L, cursor.getLong(1))
            assertEquals(47.6588, cursor.getDouble(2), 0.0)
            assertEquals(-117.426, cursor.getDouble(3), 0.0)
            assertEquals(600.0, cursor.getDouble(4), 0.0)
            assertEquals(1.5, cursor.getDouble(5), 0.0)
            assertEquals(90.0, cursor.getDouble(6), 0.0)
            assertEquals(5.0, cursor.getDouble(7), 0.0)
            assertEquals("sigA", cursor.getString(8))
            assertEquals(1, cursor.getInt(9))
            assertEquals(401, cursor.getInt(10))
            assertEquals("HTTP 401: Unauthorized", cursor.getString(11))
            assertEquals(3, cursor.getInt(12))
            assertEquals(0L, cursor.getLong(13))
            assertEquals(12345L, cursor.getLong(14))
            assertTrue(!cursor.moveToNext())
          }
    }
  }

  @Test
  fun migrate5To6_dropsSignatureInputColumnAndPreservesData() {
    helper.createDatabase(TEST_DB, 5).use { db ->
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, permanentlyFailed, permanentFailureCode,
            permanentFailureReason, submissionAttempts, submittedAt, createdAt
          ) VALUES (
            7, 9000, 10.0, 20.0, 30.0, 1.0, 2.0, 3.0,
            'should be discarded', 0, 0, '', 0, 0, 99
          )
          """
              .trimIndent()
      )
    }

    helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6).use { db ->
      db.query("SELECT id, timestamp, latitude FROM location_reports").use { cursor ->
        assertTrue(cursor.moveToNext())
        assertEquals(7L, cursor.getLong(0))
        assertEquals(9000L, cursor.getLong(1))
        assertEquals(10.0, cursor.getDouble(2), 0.0)
        assertTrue(!cursor.moveToNext())
      }

      // signatureInput must no longer be queryable.
      var threw = false
      try {
        db.query("SELECT signatureInput FROM location_reports").close()
      } catch (_: Exception) {
        threw = true
      }
      assertTrue("signatureInput column should not exist post-migration", threw)
    }
  }

  @Test
  fun migrateAll_v2ToLatest_preservesPrimaryKeyDataThroughEveryMigration() {
    helper.createDatabase(TEST_DB, 2).use { db ->
      db.execSQL(
          """
          INSERT INTO location_reports (
            id, timestamp, latitude, longitude, altitude, speed, bearing, accuracy,
            signatureInput, body, permanentlyFailed, permanentFailureReason,
            submissionAttempts, submittedAt, createdAt
          ) VALUES (
            1, 1000, 1.5, 2.5, 3.5, 0.5, 0.0, 4.0,
            'sig', 'body', 1, 'HTTP 401: Unauthorized', 2, 0, 50
          )
          """
              .trimIndent()
      )
    }

    // Open through Room with the production migration list to exercise the chain end-to-end.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val db =
        Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
    try {
      val report = runBlocking { db.locationReportDao().getPermanentlyFailedReports(10) }.single()
      assertEquals(1L, report.id)
      assertEquals(1000L, report.timestamp)
      assertEquals(1.5, report.latitude, 0.0)
      assertEquals(2.5, report.longitude, 0.0)
      assertEquals(401, report.permanentFailureCode) // backfilled by 2->3
      assertEquals(2, report.submissionAttempts)
      assertNotNull(report.permanentFailureReason)
    } finally {
      db.close()
    }
  }

  companion object {
    private const val TEST_DB = "migration-test"
  }
}
