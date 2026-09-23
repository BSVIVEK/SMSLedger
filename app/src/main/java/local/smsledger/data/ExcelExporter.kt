package local.smsledger.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import local.smsledger.core.XlsxWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ExcelExporter(private val context: Context, private val db: LedgerDatabase) {
    val file: File get() = File(context.filesDir, "exports/SMS-Ledger.xlsx")
    suspend fun export() {
        val dao = db.dao()
        val target = file
        target.parentFile!!.mkdirs()
        val temp = File(target.parentFile, "SMS-Ledger.tmp")
        try {
            FileOutputStream(temp).use { out ->
                XlsxWriter().write(out, dao.all().map { it.entry }, dao.rules().map { it.model() }, dao.instruments().map { it.model() })
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (context.getSharedPreferences("settings", 0).getBoolean("publicExport", false)) publishLocalCopy(target)
            dao.saveExport(ExportState(dirty = false, updated = System.currentTimeMillis()))
        } finally { temp.delete() }
    }
    private fun publishLocalCopy(file: File) {
        val prefs = context.getSharedPreferences("settings", 0)
        val resolver = context.contentResolver
        var uri = prefs.getString("downloadUri", null)?.let(Uri::parse)
        if (uri != null) {
            val exists = resolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { it.moveToFirst() } == true
            if (!exists) uri = null
        }
        if (uri == null) {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "SMS-Ledger.xlsx")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SMS Ledger")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: error("Cannot create local workbook")
            check(prefs.edit().putString("downloadUri", uri.toString()).commit())
        }
        val destination = requireNotNull(uri)
        resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }, null, null)
        val stream = resolver.openOutputStream(destination, "wt") ?: error("Cannot write local workbook")
        stream.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }
}
