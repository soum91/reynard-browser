package com.soum91.reynardtv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/** Streams Gecko's authenticated response directly to disk without re-requesting the URL. */
class TvDownloads(private val context: Context) {
    private data class Task(val name: String, var state: String)
    private val tasks = mutableListOf<Task>()
    private val worker = Executors.newSingleThreadExecutor()

    fun receive(response: WebResponse) {
        val body = response.body ?: return
        val rawName = Uri.parse(response.uri).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
        val name = Uri.decode(rawName).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "download" }
        AlertDialog.Builder(context).setTitle("Download file?")
            .setMessage("$name\n\n${response.uri}")
            .setPositiveButton("Download") { _, _ ->
                val task = Task(name, "Downloading")
                synchronized(tasks) { tasks.add(0, task) }
                worker.execute {
                    try {
                        body.use { stream -> save(name, stream) }
                        task.state = "Saved"
                    } catch (e: Exception) {
                        task.state = "Failed: ${e.message ?: "I/O error"}"
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> body.close() }
            .setOnCancelListener { body.close() }
            .show()
    }

    private fun save(name: String, stream: InputStream) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create download")
            try {
                resolver.openOutputStream(target)?.use { stream.copyTo(it) } ?: error("Cannot open download")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(target, values, null, null)
            } catch (e: Exception) {
                resolver.delete(target, null, null)
                throw e
            }
        } else {
            val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            folder.mkdirs()
            val nameOnly = name.substringAfterLast('/')
            var output = File(folder, nameOnly)
            var count = 1
            while (output.exists()) {
                output = File(folder, "${nameOnly.substringBeforeLast('.', nameOnly)}-$count.${nameOnly.substringAfterLast('.', "bin")}")
                count++
            }
            FileOutputStream(output).use { stream.copyTo(it) }
        }
    }

    fun showList() {
        val snapshot = synchronized(tasks) { tasks.toList() }
        AlertDialog.Builder(context).setTitle("Downloads")
            .setItems(snapshot.map { "${it.name}  ·  ${it.state}" }.toTypedArray(), null)
            .setPositiveButton("Close", null).show()
    }
}
