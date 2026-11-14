package com.examhelper.shua.data.pdf

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PdfExportNaming {
    fun fileName(
        bankName: String,
        timestamp: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val safeBank = bankName.ifBlank { "错题本" }
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
            .take(60)
            .ifBlank { "错题本" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .apply { this.timeZone = timeZone }
            .format(timestamp)
        return "导出错题-$safeBank-$stamp.pdf"
    }
}
