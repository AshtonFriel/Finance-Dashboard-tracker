package com.financedashboard.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financedashboard.app.data.CsvImporter
import com.financedashboard.app.data.SettingsStore
import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.notify.NotifyScheduler
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric reproductions of the two device-reported crashes:
 * the notifications toggle and the CSV import flow.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReproTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `notification scheduler enable and disable do not crash`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        NotifyScheduler.setEnabled(context, true)
        NotifyScheduler.setEnabled(context, false)
    }

    @Test
    fun `settings store notification toggle round trips`() = runBlocking {
        val settings = SettingsStore(context)
        settings.setNotificationsEnabled(true)
        assertTrue(settings.notificationsEnabledNow())
        settings.setNotificationsEnabled(false)
        assertTrue(!settings.notificationsEnabledNow())
    }

    @Test
    fun `csv import preview and confirm flow`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val importer = CsvImporter(context, db)

        val file = File.createTempFile("balances", ".csv").apply {
            writeText(
                """
                Date,Balance,Account
                2026-06-01,1000.00,Checking (...0001)
                2026-06-01,-500.00,Card A
                2026-07-01,1100.00,Checking (...0001)
                2026-07-01,-400.00,Card A
                """.trimIndent()
            )
        }
        val uri = Uri.fromFile(file)

        val preview = importer.previewBalances(uri)
        assertNull(preview.error)
        assertEquals(4, preview.rows)
        assertEquals(2, preview.accounts)

        val current = importer.currentBalancesSummary()
        assertEquals(0, current.first)

        val result = importer.importBalances(uri)
        assertTrue(result is CsvImporter.Result.Balances)
        assertEquals(4, (result as CsvImporter.Result.Balances).rows)
        db.close()
    }

    @Test
    fun `transactions import preview flow`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val importer = CsvImporter(context, db)
        val file = File.createTempFile("transactions", ".csv").apply {
            writeText(
                "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed\n" +
                    "2026-06-15,Shop,Shopping,Card,POS,,-12.34,,Me,\n"
            )
        }
        val uri = Uri.fromFile(file)
        val preview = importer.previewTransactions(uri)
        assertNull(preview.error)
        val result = importer.importTransactions(uri)
        assertTrue(result is CsvImporter.Result.Transactions)
        db.close()
    }
}
