package com.soum91.reynardtv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Streams Gecko's authenticated response directly to disk without re-requesting the URL. */
class TvDownloads(private val context: Context) {
    private data class Task(val name: String, var state: String)
    private val tasks = mutableListOf<Task>()
    private val worker = Executors.newSingleThreadExecutor()
    private val ranges = Executors.newFixedThreadPool(4)

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
        val labels = snapshot.map { "${it.name}  ·  ${it.state}" } + "+ FetchDock: Download direct HTTPS link"
        AlertDialog.Builder(context).setTitle("Downloads / FetchDock")
            .setItems(labels.toTypedArray()) { _, index -> if (index == snapshot.size) showDirectInput() }
            .setPositiveButton("Close", null).show()
    }

    private fun showDirectInput() {
        val entry = EditText(context).apply { setSingleLine(); hint = "https://host/file.zip" }
        AlertDialog.Builder(context).setTitle("FetchDock direct download")
            .setMessage("For direct, publicly accessible HTTPS files. Authenticated browser downloads continue using Gecko's existing stream.")
            .setView(entry).setPositiveButton("Download") { _, _ ->
                downloadDirect(entry.text.toString().trim())
            }.setNegativeButton("Cancel", null).show()
    }

    /** Also used by the updater to save a release APK to TV Downloads. */
    fun downloadDirect(url: String) {
        if (!url.startsWith("https://")) {
            Toast.makeText(context, "Only HTTPS links are accepted", Toast.LENGTH_LONG).show()
            return
        }
        val name = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.take(120) ?: "download"
        val task = Task(name, "Connecting")
        synchronized(tasks) { tasks.add(0, task) }
        worker.execute {
            try {
                downloadParallelIfSafe(url, name, task)
                task.state = "Saved"
            } catch (e: Exception) { task.state = "Failed: ${e.message ?: "Network error"}" }
        }
        Toast.makeText(context, "Downloading $name. Check Downloads for status.", Toast.LENGTH_LONG).show()
    }

    /** Four independent bounded range requests; only for strong-ETag, identity-coded responses. */
    private fun downloadParallelIfSafe(url: String, name: String, task: Task) {
        val probe = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept-Encoding", "identity")
            instanceFollowRedirects = true
        }
        val length: Long
        val etag: String?
        val rangesAllowed: Boolean
        try {
            probe.connect()
            check(probe.url.protocol == "https") { "Insecure redirect rejected" }
            length = probe.contentLengthLong
            etag = probe.getHeaderField("ETag")
            rangesAllowed = probe.responseCode == 200 &&
                probe.getHeaderField("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true &&
                probe.contentEncoding.isNullOrBlank() && etag?.startsWith('"') == true &&
                length >= 4L * 1024 * 1024
        } finally { probe.disconnect() }

        if (!rangesAllowed) {
            task.state = "Single stream (range not supported)"
            downloadSingle(url, name)
            return
        }

        val work = File(context.cacheDir, "fetchdock-${System.nanoTime()}")
        check(work.mkdirs()) { "Cannot prepare temporary download" }
        try {
            val tasks = (0 until 4).map { index ->
                Callable {
                    val start = length * index / 4
                    val end = length * (index + 1) / 4 - 1
                    val file = File(work, "part-$index")
                    val request = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        requestMethod = "GET"
                        setRequestProperty("Range", "bytes=$start-$end")
                        setRequestProperty("If-Range", etag)
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    try {
                        check(request.responseCode == 206) { "Server did not return partial content" }
                        check(request.url.protocol == "https") { "Insecure redirect rejected" }
                        check(request.getHeaderField("Content-Range") == "bytes $start-$end/$length") {
                            "Range mismatch"
                        }
                        check(request.contentEncoding.isNullOrBlank()) { "Encoded partial response" }
                        check(request.getHeaderField("ETag") == etag) { "File changed during download" }
                        request.inputStream.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                        check(file.length() == end - start + 1) { "Incomplete part" }
                        file
                    } finally { request.disconnect() }
                }
            }
            task.state = "Fetching 4 segments"
            val results = ranges.invokeAll(tasks).map { it.get() }
            val inputs = Collections.enumeration(results.map { FileInputStream(it) })
            SequenceInputStream(inputs).use { save(name, it) }
        } catch (e: Exception) {
            // Never merge partial/changed files. One new GET can safely retry public URLs.
            task.state = "Range validation failed, retrying single stream"
            downloadSingle(url, name)
        } finally { work.listFiles()?.forEach { it.delete() }; work.delete() }
    }

    private fun downloadSingle(url: String, name: String) {
        val request = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            check(request.responseCode in 200..299) { "HTTP ${request.responseCode}" }
            check(request.url.protocol == "https") { "Insecure redirect rejected" }
            request.inputStream.use { save(name, it) }
        } finally { request.disconnect() }
    }
}
