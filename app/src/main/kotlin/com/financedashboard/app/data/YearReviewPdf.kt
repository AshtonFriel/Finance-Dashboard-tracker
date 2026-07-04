package com.financedashboard.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.financedashboard.app.AppViewModel
import java.io.File
import kotlin.math.abs

/** Renders a Year-in-Review to a one-page PDF on-device and shares it. No network. */
object YearReviewPdf {

    private fun money(v: Double): String = (if (v < 0) "-$" else "$") + "%,.0f".format(abs(v))

    fun export(context: Context, r: AppViewModel.YearReview) {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 @72dpi
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val bg = Paint().apply { color = Color.rgb(0x0f, 0x1f, 0x17) }
        c.drawRect(0f, 0f, 595f, 842f, bg)

        val title = Paint().apply { color = Color.rgb(0x3e, 0xcf, 0x8e); textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
        val h = Paint().apply { color = Color.rgb(0xea, 0xf3, 0xee); textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { color = Color.rgb(0xc3, 0xc2, 0xb7); textSize = 13f; isAntiAlias = true }
        val big = Paint().apply { color = Color.rgb(0xea, 0xf3, 0xee); textSize = 22f; isFakeBoldText = true; isAntiAlias = true }

        var y = 60f
        c.drawText("${r.year} Year in Review", 40f, y, title); y += 40f

        c.drawText("Net worth", 40f, y, h)
        c.drawText("${money(r.netWorthStart)}  →  ${money(r.netWorthEnd)}", 200f, y, big); y += 34f
        val change = r.netWorthEnd - r.netWorthStart
        c.drawText("Change: ${money(change)}", 40f, y, body); y += 40f

        c.drawText("Cash flow", 40f, y, h); y += 24f
        c.drawText("Income: ${money(r.income)}", 40f, y, body); y += 20f
        c.drawText("Spending: ${money(r.spending)}", 40f, y, body); y += 20f
        c.drawText("Savings rate: ${r.savingsRate.toInt()}%", 40f, y, body); y += 40f

        r.attribution?.let { a ->
            c.drawText("What moved your net worth", 40f, y, h); y += 24f
            listOf(
                "Saved from income" to a.cashSaved,
                "Market growth" to a.marketChange,
                "Debt paid down" to a.debtPrincipalPaid,
                "Asset revaluation" to a.assetRevaluation,
                "Unexplained" to a.residual,
            ).filter { abs(it.second) > 1 }.forEach { (label, v) ->
                c.drawText("$label: ${money(v)}", 40f, y, body); y += 20f
            }
            y += 20f
        }

        c.drawText("Top spending categories", 40f, y, h); y += 24f
        for ((cat, amt) in r.topCategories) {
            c.drawText("$cat: ${money(amt)}", 40f, y, body); y += 20f
        }

        val footer = Paint().apply { color = Color.rgb(0x89, 0x87, 0x81); textSize = 10f; isAntiAlias = true }
        c.drawText("Generated on-device by Finance Dashboard — no data left your phone.", 40f, 810f, footer)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "year-in-review-${r.year}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${r.year} review").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
