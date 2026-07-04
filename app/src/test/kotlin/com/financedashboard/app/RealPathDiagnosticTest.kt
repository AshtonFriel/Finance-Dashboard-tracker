package com.financedashboard.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.notify.NotifyScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real device code paths (not in-memory / not test-initialized)
 * to surface whatever the on-device crashes are. Any thrown exception here is
 * a candidate root cause.
 */
@RunWith(RobolectricTestRunner::class)
class RealPathDiagnosticTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `real AppDatabase get and query`() = runBlocking {
        // This is what the app actually calls — including the SQLCipher attempt
        // and its fallback. Must not throw.
        val db = AppDatabase.get(context)
        val count = db.transactionDao().count().first()
        println("DIAG: db opened, transaction count=$count")
    }

    @Test
    fun `real NotifyScheduler enable`() {
        // No WorkManagerTestInitHelper — this is the production init path.
        NotifyScheduler.setEnabled(context, true)
        println("DIAG: NotifyScheduler.setEnabled(true) returned")
        NotifyScheduler.setEnabled(context, false)
    }
}
