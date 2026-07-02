package com.financedashboard.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes a table to a cache-dir CSV and hands it to the system share sheet. */
object TableExporter {

    fun shareCsv(context: Context, fileName: String, header: List<String>, rows: List<List<String>>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.bufferedWriter().use { w ->
            fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) {
                "\"${v.replace("\"", "\"\"")}\""
            } else v
            w.appendLine(header.joinToString(",") { esc(it) })
            for (row in rows) w.appendLine(row.joinToString(",") { esc(it) })
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export $fileName"))
    }
}
