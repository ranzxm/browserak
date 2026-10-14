package com.ranzxm.browserak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** Two concurrent jobs, one HTTP stream per file. Requires a dataSync service in the manifest. */
class DownloadService : Service() {
    data class JobInfo(
        val id: String,
        val name: String,
        val state: String,
        val done: Long,
        val total: Long,
        val error: String,
        val path: String,
    )

    companion object {
        private const val PREFS = "download_queue"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 4107
        private const val PLAYLIST_LIMIT = 1024 * 1024
        private const val SEGMENT_LIMIT = 128L * 1024 * 1024
        private const val QUEUE_LIMIT = 100
        private val lock = Any()
        private val active = mutableMapOf<String, Transfer>()
        @Volatile private var live: DownloadService? = null

        fun enqueue(context: Context, url: String, name: String, referer: String?, cookie: String?, userAgent: String) {
            checkedUrl(url)
            referer?.let { checkedUrl(it) }
            listOfNotNull(referer, cookie, userAgent).forEach {
                require(it.length <= 16_384 && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' }) {
                    "Invalid HTTP header"
                }
            }
            val cleanName = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim().trim('.').take(60).ifEmpty { "download" }
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("External downloads storage unavailable")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create downloads directory")
            val id = UUID.randomUUID().toString()
            val job = JSONObject().put("id", id).put("name", cleanName).put("url", url)
                .put("referer", referer ?: "").put("cookie", cookie ?: "").put("ua", userAgent)
                .put("state", "queued").put("done", 0L).put("total", -1L).put("error", "")
                .put("path", File(directory, "${id}_$cleanName").absolutePath)
            synchronized(lock) {
                val jobs = load(context)
                check(jobs.length() < QUEUE_LIMIT) { "Download queue limit reached (100 records)" }
                jobs.put(job)
                save(context, jobs)
            }
            kick(context)
        }

        fun list(context: Context): List<JobInfo> = synchronized(lock) {
            val jobs = load(context)
            (0 until jobs.length()).map { index ->
                val j = jobs.getJSONObject(index)
                JobInfo(j.getString("id"), j.getString("name"), j.getString("state"),
                    j.optLong("done"), j.optLong("total", -1L), j.optString("error"), j.getString("path"))
            }
        }

        fun pause(context: Context, id: String) {
            val connection = synchronized(lock) {
                if (changeState(context, id, setOf("queued", "running"), "paused"))
                    active[id]?.also { it.stopped = true }?.connection else null
            }
            connection?.disconnect()
        }

        fun resume(context: Context, id: String) {
            if (changeState(context, id, setOf("paused", "error", "queued"), "queued")) kick(context)
        }

        fun cancel(context: Context, id: String) {
            val connection = synchronized(lock) {
                if (!changeState(context, id, setOf("queued", "running", "paused", "error"), "cancelled")) return
                val connection = active[id]?.also { it.stopped = true }?.connection
                val jobs = load(context)
                find(jobs, id)?.let { j ->
                    val part = File(j.getString("path") + ".part")
                    if (id !in active && part.exists() && !part.delete()) {
                        j.put("error", "Could not delete partial file")
                        save(context, jobs)
                    }
                }
                connection
            }
            connection?.disconnect()
        }

        private fun kick(context: Context) {
            context.applicationContext.startForegroundService(Intent(context, DownloadService::class.java))
        }

        private fun load(context: Context): JSONArray {
            // Do not silently overwrite a corrupt queue (it contains the recovery checkpoints).
            return JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("jobs", "[]"))
        }

        private fun save(context: Context, jobs: JSONArray) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("jobs", jobs.toString()).commit()) throw IOException("Cannot persist download queue")
        }

        private fun find(jobs: JSONArray, id: String): JSONObject? =
            (0 until jobs.length()).asSequence().map { jobs.getJSONObject(it) }.firstOrNull { it.getString("id") == id }

        private fun changeState(context: Context, id: String, from: Set<String>, to: String): Boolean = synchronized(lock) {
            val jobs = load(context)
            val j = find(jobs, id) ?: return@synchronized false
            if (j.getString("state") !in from) return@synchronized false
            j.put("state", to).put("error", "")
            if (to == "cancelled") clearCredentials(j)
            save(context, jobs)
            true
        }

        private fun checkedUrl(value: String): URL {
            require(value.length <= 16_384) { "URL too long" }
            val uri = URI(value)
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)) {
                "Only HTTP(S) URLs without embedded credentials are supported"
            }
            return uri.toURL()
        }

        private fun origin(url: URL): String = "${url.protocol.lowercase(Locale.ROOT)}://${url.host.lowercase(Locale.ROOT)}:${if (url.port < 0) url.defaultPort else url.port}"

        private fun clearCredentials(job: JSONObject) {
            // Paused/error jobs retain credentials for explicit resume; terminal jobs do not.
            for (key in listOf("cookie", "referer", "ua", "url", "etag", "modified")) job.remove(key)
        }
    }

    private class Transfer {
        @Volatile var stopped = false
        @Volatile var connection: HttpURLConnection? = null
        var lastProgress = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val transfer = ThreadLocal<Transfer>()
    @Volatile private var stopping = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        foreground("Preparing downloads")
        synchronized(lock) {
            val jobs = load(this)
            for (i in 0 until jobs.length()) {
                val j = jobs.getJSONObject(i)
                if (j.getString("state") == "running" && j.getString("id") !in active) j.put("state", "queued")
            }
            save(this, jobs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        drain()
        return START_STICKY
    }

    private fun drain() {
        if (stopping) return
        while (true) {
            val token = Transfer()
            val job = synchronized(lock) {
                if (active.size >= 2) return
                val jobs = load(this)
                val next = (0 until jobs.length()).asSequence().map { jobs.getJSONObject(it) }
                    .firstOrNull { it.getString("state") == "queued" && it.getString("id") !in active }
                if (next != null) {
                    next.put("state", "running").put("error", "")
                    save(this, jobs)
                    active[next.getString("id")] = token
                    JSONObject(next.toString())
                } else null
            }
            if (job == null) {
                // stopSelfResult protects a newly delivered start command from an idle worker.
                synchronized(lock) { if (active.isEmpty()) stopSelfResult(lastStartId) }
                return
            }
            worker.execute {
                transfer.set(token)
                try {
                    withRetries(job)
                } catch (_: Stopped) {
                    // Pause/cancel/teardown already persisted the desired state.
                } catch (e: Exception) {
                    synchronized(lock) {
                        val jobs = load(this)
                        find(jobs, job.getString("id"))?.let {
                            if (!stopping && !token.stopped && it.getString("state") == "running") {
                                // Never expose exception messages containing authenticated URLs.
                                it.put("state", "error").put("error", if (e is DownloadError || e is RetryableError) e.message
                                    else "Download failed (${e.javaClass.simpleName}); resume to retry")
                                save(this, jobs)
                            }
                        }
                    }
                } finally {
                    token.connection?.disconnect()
                    token.connection = null
                    try {
                        synchronized(lock) {
                            val jobs = load(this)
                            find(jobs, job.getString("id"))?.let {
                                if (it.getString("state") == "cancelled") {
                                    val part = File(it.getString("path") + ".part")
                                    if (part.exists() && !part.delete()) {
                                        it.put("error", "Could not delete partial file")
                                        save(this, jobs)
                                    }
                                }
                            }
                        }
                    } finally {
                        synchronized(lock) { active.remove(job.getString("id")) }
                        transfer.remove()
                        main.post { live?.drain() }
                    }
                }
            }
        }
    }

    private class Stopped : IOException()
    private class DownloadError(message: String) : IOException(message)
    private class RetryableError(message: String) : IOException(message)

    private fun withRetries(initial: JSONObject) {
        var job = initial
        for (attempt in 0..3) {
            try {
                download(job)
                return
            } catch (e: IOException) {
                alive(job)
                val transient = e is RetryableError || e is SocketTimeoutException || e is SocketException ||
                    e is UnknownHostException || e is EOFException
                if (!transient || attempt == 3) throw e
                // Backoff remains interruptible by pause/cancel, without blocking the main thread.
                repeat((1 shl attempt) * 10) { alive(job); Thread.sleep(100) }
                job = synchronized(lock) {
                    JSONObject((find(load(this), job.getString("id")) ?: throw Stopped()).toString())
                }
            }
        }
    }

    private fun alive(job: JSONObject) {
        if (stopping || transfer.get()?.stopped != false || Thread.currentThread().isInterrupted) throw Stopped()
    }

    private fun checkpoint(job: JSONObject, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val token = transfer.get() ?: throw Stopped()
        if (!force && now - token.lastProgress < 1000) return
        synchronized(lock) {
            alive(job)
            val jobs = load(this)
            val stored = find(jobs, job.getString("id")) ?: throw Stopped()
            if (stored.getString("state") != "running") throw Stopped()
            for (key in listOf("done", "total", "etag", "modified", "hls", "hlsIndex", "hlsBytes", "hlsSignature")) {
                if (job.has(key)) stored.put(key, job.get(key))
            }
            save(this, jobs)
        }
        token.lastProgress = now
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                notification("${job.getString("name")} — ${job.optLong("done")} bytes", job))
        }
    }

    private fun open(job: JSONObject, initial: URL, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var url = initial
        val cookieOrigin = origin(checkedUrl(job.getString("url")))
        repeat(9) { hop ->
            alive(job)
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", job.getString("ua"))
                if (origin(url) == cookieOrigin) {
                    job.optString("cookie").takeIf { it.isNotEmpty() }?.let { setRequestProperty("Cookie", it) }
                    job.optString("referer").takeIf { it.isNotEmpty() }?.let { setRequestProperty("Referer", it) }
                }
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val token = transfer.get() ?: throw Stopped()
            token.connection = c
            try {
                alive(job)
                val status = c.responseCode
                if (status in setOf(408, 429, 500, 502, 503, 504)) throw RetryableError("Transient HTTP $status; retry limit reached")
                if (status !in listOf(301, 302, 303, 307, 308)) return c
                if (hop == 8) throw DownloadError("Too many redirects")
                val location = c.getHeaderField("Location") ?: throw DownloadError("Redirect without Location")
                val next = checkedUrl(url.toURI().resolve(location).toString())
                if (url.protocol == "https" && next.protocol != "https") throw DownloadError("Insecure HTTPS redirect refused")
                url = next
            } catch (e: Exception) {
                c.disconnect()
                if (token.connection === c) token.connection = null
                throw e
            }
            c.disconnect()
            if (token.connection === c) token.connection = null
        }
        throw DownloadError("Too many redirects")
    }

    private fun download(job: JSONObject) {
        alive(job)
        val output = File(job.getString("path"))
        val part = File(output.path + ".part")
        if (!output.exists()) {
            val url = checkedUrl(job.getString("url"))
            if (job.optBoolean("hls") || url.path.endsWith(".m3u8", true)) hls(job, url, part)
            else direct(job, url, part)
        } else if (part.exists()) throw DownloadError("Destination already exists")
        synchronized(lock) {
            alive(job)
            if (!output.exists() && !part.renameTo(output)) throw DownloadError("Cannot finalize downloaded file")
            val jobs = load(this)
            val stored = find(jobs, job.getString("id"))!!
            stored.put("state", "completed").put("done", output.length()).put("total", output.length()).put("error", "")
            clearCredentials(stored)
            save(this, jobs)
        }
    }

    private fun direct(job: JSONObject, url: URL, part: File) {
        var position = if (part.exists()) part.length() else 0L
        var validator = job.optString("etag").takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            ?: job.optString("modified").takeIf { it.isNotEmpty() }
        // ponytail: one HTTP stream; parallel ranges need separate durable, validated part checkpoints.
        if (validator == null) position = 0L
        while (true) {
            alive(job)
            val headers = if (position > 0) mapOf("Range" to "bytes=$position-", "If-Range" to validator!!) else emptyMap()
            val c = open(job, url, headers)
            try {
                val status = c.responseCode
                if (status == 416 && position > 0) { position = 0L; continue }
                if (status != 200 && status != 206) throw DownloadError("HTTP $status")
                val encoding = c.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) throw DownloadError("Encoded HTTP bodies are unsupported")
                val type = c.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if (type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")) {
                    c.disconnect()
                    if (part.exists() && !part.delete()) throw DownloadError("Cannot reset partial playlist")
                    hls(job, url, part)
                    return
                }
                var expected = c.contentLengthLong
                var total = expected
                if (status == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(c.getHeaderField("Content-Range").orEmpty())
                        ?: throw DownloadError("Invalid Content-Range")
                    val start = range.groupValues[1].toLongOrNull() ?: throw DownloadError("Invalid range start")
                    val end = range.groupValues[2].toLongOrNull() ?: throw DownloadError("Invalid range end")
                    total = range.groupValues[3].toLongOrNull() ?: throw DownloadError("Invalid range total")
                    if (start != position || end < start || end >= total) throw DownloadError("Mismatched Content-Range")
                    expected = end - start + 1
                    if (c.contentLengthLong >= 0 && c.contentLengthLong != expected) throw DownloadError("Mismatched range length")
                    if (position > 0 && job.optLong("total", -1L).let { it >= 0 && it != total }) throw DownloadError("Range total changed")
                    val etag = c.getHeaderField("ETag")
                    val modified = c.getHeaderField("Last-Modified")
                    if (position > 0 && ((etag != null && job.optString("etag").isNotEmpty() && etag != job.optString("etag")) ||
                            (etag == null && modified != null && job.optString("modified").isNotEmpty() && modified != job.optString("modified"))))
                        throw DownloadError("Range validator changed")
                } else position = 0L // Server ignored Range / If-Range changed: replace, never append.
                job.put("etag", c.getHeaderField("ETag").orEmpty()).put("modified", c.getHeaderField("Last-Modified").orEmpty())
                    .put("done", position).put("total", total)
                validator = job.optString("etag").takeIf { it.isNotEmpty() && !it.startsWith("W/") }
                    ?: job.optString("modified").takeIf { it.isNotEmpty() }
                var received = 0L
                RandomAccessFile(part, "rw").use { file ->
                    file.setLength(position)
                    file.fd.sync()
                    file.seek(position)
                    checkpoint(job, true) // Reset old bytes before persisting the new validator.
                    c.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            alive(job)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (expected >= 0 && count > expected - received) throw DownloadError("HTTP body exceeds declared length")
                            file.write(buffer, 0, count)
                            received += count
                            job.put("done", position + received)
                            checkpoint(job)
                        }
                    }
                    file.fd.sync()
                }
                if (expected >= 0 && received != expected) throw RetryableError("Truncated HTTP body; resume to retry")
                position += received
                checkpoint(job, true)
                if (total < 0 || position == total) return
                if (received == 0L) throw DownloadError("Empty range response")
                if (validator == null) throw DownloadError("Partial response without a resume validator")
            } finally {
                c.disconnect()
                if (transfer.get()?.connection === c) transfer.get()?.connection = null
            }
        }
    }

    private fun playlist(job: JSONObject, url: URL): Pair<URL, List<String>> {
        val c = open(job, url)
        try {
            if (c.responseCode != 200) throw DownloadError("Playlist HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= PLAYLIST_LIMIT) {
                    alive(job)
                    val count = input.read(buffer, 0, minOf(buffer.size, PLAYLIST_LIMIT + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > PLAYLIST_LIMIT) throw DownloadError("Playlist exceeds 1 MiB")
            if (c.contentLengthLong >= 0 && bytes.size.toLong() != c.contentLengthLong) throw RetryableError("Truncated HLS playlist")
            val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull() != "#EXTM3U") throw DownloadError("Invalid HLS playlist")
            return c.url to lines
        } finally {
            c.disconnect()
            if (transfer.get()?.connection === c) transfer.get()?.connection = null
        }
    }

    private fun hls(job: JSONObject, initial: URL, part: File) {
        job.put("hls", true)
        checkpoint(job, true)
        var url = initial
        var segments: List<URL>? = null
        repeat(4) { depth ->
            if (segments != null) return@repeat
            val (base, lines) = playlist(job, url)
            if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:") }) throw DownloadError("Encrypted HLS unsupported")
            val master = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            if (master >= 0) {
                if (depth == 3) throw DownloadError("HLS master nesting limit exceeded")
                if (Regex("(?:[:,])\\s*(AUDIO|VIDEO)=").containsMatchIn(lines[master])) throw DownloadError("Separate HLS audio/video unsupported")
                val next = lines.getOrNull(master + 1)?.takeIf { !it.startsWith('#') }
                    ?: throw DownloadError("Missing HLS variant URI")
                url = checkedUrl(base.toURI().resolve(next).toString())
            } else {
                if ("#EXT-X-ENDLIST" !in lines) throw DownloadError("Live/event HLS unsupported; playlist must be finished")
                if (lines.any { line ->
                        line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-BYTERANGE:") ||
                            line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:") ||
                            line == "#EXT-X-GAP" || line == "#EXT-X-I-FRAMES-ONLY" ||
                            (line.startsWith("#EXT-X-KEY:") && !Regex("(?:[:,])\\s*METHOD=NONE(?:,|$)").containsMatchIn(line))
                    }) throw DownloadError("Only unencrypted, complete MPEG-TS HLS segments are supported")
                val result = ArrayList<URL>()
                var expectsSegment = false
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        if (expectsSegment) throw DownloadError("Missing HLS segment")
                        expectsSegment = true
                    } else if (!line.startsWith('#')) {
                        if (!expectsSegment) throw DownloadError("Unexpected HLS URI")
                        result.add(checkedUrl(base.toURI().resolve(line).toString()))
                        if (result.size > 10_000) throw DownloadError("HLS exceeds 10000 segments")
                        expectsSegment = false
                    }
                }
                if (expectsSegment || result.isEmpty()) throw DownloadError("Incomplete/empty HLS playlist")
                segments = result
            }
        }
        val urls = segments ?: throw DownloadError("Missing HLS media playlist")
        val signature = MessageDigest.getInstance("SHA-256").digest(urls.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (job.optString("hlsSignature").let { it.isNotEmpty() && it != signature }) throw DownloadError("HLS playlist changed; cancel and enqueue again")
        var index = job.optInt("hlsIndex", 0)
        var committed = job.optLong("hlsBytes", 0L)
        if (index !in 0..urls.size || committed < 0) throw DownloadError("Invalid HLS checkpoint")
        if (!part.exists() || part.length() < committed) { index = 0; committed = 0L }
        job.put("hlsSignature", signature).put("hlsIndex", index).put("hlsBytes", committed).put("done", committed).put("total", -1L)
        checkpoint(job, true)
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(committed)
            file.seek(committed)
            while (index < urls.size) {
                alive(job)
                val c = open(job, urls[index])
                try {
                    if (c.responseCode != 200) throw DownloadError("Segment HTTP ${c.responseCode}")
                    val encoding = c.getHeaderField("Content-Encoding")
                    if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) throw DownloadError("Encoded HLS segments unsupported")
                    if (c.contentLengthLong > SEGMENT_LIMIT) throw DownloadError("HLS segment exceeds 128 MiB")
                    var received = 0L
                    c.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            alive(job)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > SEGMENT_LIMIT - received) throw DownloadError("HLS segment exceeds 128 MiB")
                            var offset = ((188 - received % 188) % 188).toInt()
                            while (offset < count) {
                                if (buffer[offset] != 0x47.toByte()) throw DownloadError("Segment is not 188-byte MPEG-TS; fMP4/packed audio unsupported")
                                offset += 188
                            }
                            file.write(buffer, 0, count)
                            received += count
                            job.put("done", committed + received)
                            checkpoint(job)
                        }
                    }
                    if (c.contentLengthLong >= 0 && received != c.contentLengthLong) throw RetryableError("Truncated HLS segment")
                    if (received == 0L || received % 188 != 0L)
                        throw DownloadError("Truncated/invalid MPEG-TS segment")
                    file.fd.sync()
                    committed += received
                    index++
                    job.put("hlsIndex", index).put("hlsBytes", committed).put("done", committed)
                    checkpoint(job, true)
                } catch (e: Exception) {
                    // An interrupted segment is never part of the durable concatenation.
                    file.setLength(committed)
                    throw e
                } finally {
                    c.disconnect()
                    if (transfer.get()?.connection === c) transfer.get()?.connection = null
                }
            }
        }
    }

    private fun notification(text: String, job: JSONObject? = null): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Browserak downloads").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (job != null) {
            val total = job.optLong("total", -1L)
            val done = job.optLong("done")
            builder.setProgress(1000, if (total > 0) ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000) else 0, total <= 0)
        }
        return builder.build()
    }

    private fun foreground(text: String) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification(text))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        try {
            synchronized(lock) {
                val jobs = load(this)
                for (i in 0 until jobs.length()) {
                    val j = jobs.getJSONObject(i)
                    if (j.getString("state") in setOf("running", "queued"))
                        j.put("state", "paused").put("error", "Android dataSync time limit reached; resume from the app")
                }
                save(this, jobs)
            }
        } finally {
            stopTransfers()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        if (live === this) live = null
        stopTransfers()
        worker.shutdownNow()
        try {
            synchronized(lock) {
                val jobs = load(this)
                for (i in 0 until jobs.length()) {
                    val j = jobs.getJSONObject(i)
                    if (j.getString("state") == "running") j.put("state", "queued")
                }
                save(this, jobs)
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            super.onDestroy()
        }
    }

    private fun stopTransfers() {
        synchronized(lock) { active.values.onEach { it.stopped = true }.mapNotNull { it.connection } }
            .forEach { it.disconnect() }
    }
}
