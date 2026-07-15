package com.livetranslate.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ExportTranslator {
    /**
     * Export session as Markdown.
     *
     * Format (two sections): full source block, then full translation block.
     * Continuous Live transcripts are not reliably sentence-aligned, so pairing
     * line-by-line would invent structure. Two sections stay faithful to the stream.
     */
    fun buildMarkdown(
        sourceText: String,
        translationText: String,
        stoppedAt: LocalDateTime = LocalDateTime.now(),
    ): String {
        val timeLabel = stoppedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return buildString {
            appendLine("# 翻译结果")
            appendLine()
            appendLine("- 停止时间：`$timeLabel`")
            appendLine()
            appendLine("## 原文")
            appendLine()
            appendLine(sourceText.ifBlank { "（无）" }.trim())
            appendLine()
            appendLine("## 译文")
            appendLine()
            appendLine(translationText.ifBlank { "（无）" }.trim())
            appendLine()
        }
    }

    /** e.g. 7月14日-22:35-翻译结果.md */
    fun fileName(stoppedAt: LocalDateTime = LocalDateTime.now()): String {
        val month = stoppedAt.monthValue
        val day = stoppedAt.dayOfMonth
        val hm = stoppedAt.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "${month}月${day}日-$hm-翻译结果.md"
    }

    /**
     * Write to public Downloads. Returns display path or content URI string.
     */
    fun saveToDownloads(context: Context, fileName: String, content: String): Result<String> {
        return runCatching {
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建下载文件")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("无法写入文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }
        }
    }
}
