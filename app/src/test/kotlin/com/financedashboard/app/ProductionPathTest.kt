package com.financedashboard.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Runs the exact production code paths the user reported as crashing. */
@RunWith(RobolectricTestRunner::class)
class ProductionPathTest {

    private suspend fun pump(times: Int = 20) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(50)
        }
    }

    @Test
    fun `notification toggle through the view model as in production`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        vm.setNotificationsEnabled(true)
        pump()
        vm.setNotificationsEnabled(false)
        pump()
    }

    @Test
    fun `full import flow through the view model as in production`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        val file = File.createTempFile("balances", ".csv").apply {
            writeText(
                """
                Date,Balance,Account
                2026-06-01,1000.00,Checking (...0001)
                2026-07-01,-400.00,Card A
                """.trimIndent()
            )
        }
        vm.requestImportBalances(Uri.fromFile(file))
        var waited = 0
        while (vm.pendingImport.value == null && waited < 100) { pump(2); waited++ }
        assertNotNull("preview never arrived: status=${vm.importStatus.value}", vm.pendingImport.value)
        vm.confirmPendingImport()
        waited = 0
        while (vm.importStatus.value?.startsWith("Imported") != true && waited < 100) { pump(2); waited++ }
        assertTrue("import did not complete: ${vm.importStatus.value}", vm.importStatus.value!!.startsWith("Imported"))
    }
}
