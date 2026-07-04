package com.financedashboard.app

import android.app.Application
import androidx.work.Configuration
import java.io.File

/**
 * Provides WorkManager configuration for on-demand initialization. Because the
 * app ships a custom Application, relying on the default androidx.startup
 * initializer is fragile (it was the cause of the notifications-toggle crash);
 * implementing [Configuration.Provider] makes WorkManager initialize lazily and
 * reliably the first time it is used.
 */
class FinanceApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
    }

    /**
     * Records any uncaught exception to a local file before the system kills
     * the process, so device-only crashes can be shared and diagnosed. The
     * previous handler still runs (system crash dialog / logcat unchanged).
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile(this).writeText(
                    buildString {
                        appendLine("time: ${java.time.ZonedDateTime.now()}")
                        appendLine("thread: ${thread.name}")
                        appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("app: ${packageManager.getPackageInfo(packageName, 0).versionName}")
                        appendLine()
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    }
                )
            } catch (_: Throwable) {
                // Never let crash recording cause its own crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashFile(context: android.content.Context): File = File(context.filesDir, "last-crash.txt")
    }
}
