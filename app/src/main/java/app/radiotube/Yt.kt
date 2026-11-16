package app.radiotube

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/** yt-dlp, running on the phone: turns a video id into a playable audio stream URL. */
object Yt {
    class Stream(val url: String, val headers: Map<String, String>, val hls: Boolean, val expiresAt: Long)

    /** The player guessed the wrong container for this track; the service rebuilds the item and retries. */
    class HlsMismatch(val hls: Boolean) : IOException("stream type changed")

    /** Shown in the header while the engine is busy; null when idle. */
    val status = MutableStateFlow<String?>(null)

    private const val TAG = "RadioTube"
    private const val PLAYLIST_LIMIT = 300
    private const val UPDATE_EVERY_MS = 12 * 60 * 60 * 1000L

    // Audio-only over plain HTTP when it exists, otherwise HLS (live streams), never fragmented DASH.
    private const val FORMAT =
        "bestaudio[protocol^=http][protocol!*=dash]/bestaudio[protocol^=m3u8]/" +
            "best[protocol^=m3u8][height<=480]/best[protocol^=http][protocol!*=dash][height<=480]/" +
            "best[protocol!*=dash]"

    // This client hands out audio URLs directly. The default ones need a JavaScript challenge that takes
    // QuickJS about half a minute on a budget phone, so they are only the fallback.
    private val FAST_CLIENT = listOf("--extractor-args", "youtube:player_client=visionos")
    private val FINAL_ERROR = Regex(
        "unavailable|private video|removed|terminated|recording is not available|video is not available|" +
            "not available in your country|members-only|confirm your age|live event will begin",
        RegexOption.IGNORE_CASE,
    )

    private lateinit var app: Context
    private val ready = CountDownLatch(1)
    @Volatile private var initError: String? = null
    @Volatile private var lastUpdateAttempt = 0L

    /** Extractions share the yt-dlp file; an update replaces it and must run alone. */
    private val binary = ReentrantReadWriteLock()
    private val cache = ConcurrentHashMap<String, Stream>()
    private val locks = ConcurrentHashMap<String, Any>()

    private val prefs get() = app.getSharedPreferences("yt", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        thread(name = "yt-init") {
            try {
                status.value = "Preparando el motor…"
                YoutubeDL.getInstance().init(app)
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp init failed", e)
                initError = "No se pudo iniciar yt-dlp: ${e.message}"
            }
            if (initError == null) {
                val last = prefs.getLong("updated", 0)
                // The yt-dlp bundled in the APK is old, so the very first run updates before anything plays.
                if (last == 0L) update()
                ready.countDown()
                if (last != 0L && System.currentTimeMillis() - last > UPDATE_EVERY_MS) update()
            } else {
                ready.countDown()
            }
            status.value = null
        }
    }

    private fun update(): Boolean {
        lastUpdateAttempt = System.currentTimeMillis()
        status.value = "Actualizando yt-dlp…"
        return try {
            binary.write { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply()
            Log.i(TAG, "yt-dlp is now ${YoutubeDL.getInstance().versionName(app)}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "yt-dlp update failed", e)
            false
        } finally {
            status.value = null
        }
    }

    fun invalidate(id: String) {
        cache.remove(id)
    }

    /** Fetches title/duration for a newly saved link and warms the stream cache so the first play is instant. */
    fun prefetch(id: String) {
        thread(name = "yt-prefetch") {
            try {
                resolve(id)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch $id: ${e.message}")
            }
        }
    }

    /**
     * Reads a YouTube playlist (titles only, no per-video extraction) and saves it as a list. [name] overrides
     * the playlist's own title.
     */
    fun importPlaylist(listId: String, name: String? = null) {
        thread(name = "yt-playlist") {
            status.value = "Importando lista de YouTube…"
            try {
                if (!ready.await(3, TimeUnit.MINUTES)) throw IOException("El motor todavía se está preparando.")
                initError?.let { throw IOException(it) }
                val out = run(
                    listOf(
                        "--flat-playlist", "--dump-single-json", "--playlist-end", PLAYLIST_LIMIT.toString(),
                        "--no-warnings", "--socket-timeout", "15", "https://www.youtube.com/playlist?list=$listId",
                    )
                )
                val json = JSONObject(out.lineSequence().last { it.startsWith("{") })
                val entries = json.optJSONArray("entries")
                val found = ArrayList<Track>()
                for (i in 0 until (entries?.length() ?: 0)) {
                    val e = entries!!.optJSONObject(i) ?: continue
                    val id = e.optString("id")
                    val title = e.optString("title")
                    // Deleted and private videos still appear in a playlist, as placeholders.
                    if (id.length != 11 || title.isEmpty() || title.startsWith("[")) continue
                    found += Track(
                        id = id, title = title.take(160),
                        artist = e.optString("channel").ifEmpty { e.optString("uploader") },
                        durationMs = (e.optDouble("duration", 0.0) * 1000).toLong(),
                    )
                }
                if (found.isEmpty()) throw IOException("La lista está vacía o es privada.")
                val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: json.optString("title")
                val total = Library.importPlaylist(listId, title, found)
                Bus.notice.value = "Lista importada: $total pistas"
            } catch (e: Exception) {
                Log.w(TAG, "playlist $listId: ${e.message}")
                Bus.notice.value = "No se pudo importar la lista: ${e.message}"
            } finally {
                status.value = null
            }
        }
    }

    /** Blocking. [expectHls] is what the caller's media item was built for; a mismatch throws [HlsMismatch]. */
    @Throws(IOException::class)
    fun resolve(id: String, expectHls: Boolean? = null): Stream {
        if (!ready.await(3, TimeUnit.MINUTES)) throw IOException("El motor todavía se está preparando; prueba en un momento.")
        initError?.let { throw IOException(it) }
        synchronized(locks.getOrPut(id) { Any() }) {
            val now = System.currentTimeMillis()
            val stream = cache[id]?.takeIf { it.expiresAt > now + 60_000 } ?: try {
                extract(id)
            } catch (e: IOException) {
                // YouTube changes often; a stale yt-dlp is the usual reason an extraction fails.
                if (!isFinal(e) && now - lastUpdateAttempt > 10 * 60_000 && update()) extract(id) else throw e
            }
            cache[id] = stream
            if (expectHls != null && stream.hls != expectHls) throw HlsMismatch(stream.hls)
            return stream
        }
    }

    private fun extract(id: String): Stream {
        val started = System.currentTimeMillis()
        val out = try {
            run(FAST_CLIENT + extractArgs(id))
        } catch (e: IOException) {
            // Removed, private or region-locked videos fail the same way on every client.
            if (isFinal(e)) throw e
            Log.w(TAG, "fast client failed for $id (${e.message}); trying yt-dlp's defaults")
            run(extractArgs(id))
        }
        val json = try {
            JSONObject(out.lineSequence().last { it.startsWith("{") })
        } catch (e: Exception) {
            throw IOException("yt-dlp no devolvió datos para este vídeo.")
        }
        val url = json.optString("url")
        if (url.isEmpty()) throw IOException("yt-dlp no encontró un formato de audio reproducible.")
        val hls = json.optString("protocol").startsWith("m3u8")
        val headers = HashMap<String, String>()
        json.optJSONObject("http_headers")?.let { h -> for (k in h.keys()) headers[k] = h.optString(k) }
        val live = json.optBoolean("is_live")
        Library.setMeta(
            id = id,
            title = json.optString("title"),
            artist = json.optString("channel").ifEmpty { json.optString("uploader") },
            durationMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
            live = live,
            hls = hls,
        )
        val now = System.currentTimeMillis()
        val expire = Regex("""[?&/]expire[=/](\d{9,11})""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAt = (expire?.times(1000) ?: (now + 2 * 60 * 60_000L)).coerceAtMost(now + 6 * 60 * 60_000L)
        Log.i(TAG, "resolved $id in ${now - started} ms (format ${json.optString("format_id")}, hls=$hls, live=$live)")
        return Stream(url, headers, hls, expiresAt)
    }

    /** Errors that no other client or newer yt-dlp would fix: the video is gone, private, or we were cancelled. */
    private fun isFinal(e: IOException) = e is InterruptedIOException || FINAL_ERROR.containsMatchIn(e.message.orEmpty())

    private fun extractArgs(id: String) = listOf(
        "--dump-json", "--no-playlist", "--no-warnings", "--socket-timeout", "15", "-f", FORMAT,
        "https://www.youtube.com/watch?v=$id",
    )

    /**
     * Runs yt-dlp and returns its stdout. The library's own runner starts Python on the zipped yt-dlp, which
     * recompiles every module on each launch; running the unpacked copy lets Python keep its bytecode cache.
     */
    private fun run(args: List<String>): String = binary.read {
        val bin = File(app.applicationInfo.nativeLibraryDir)
        val python = File(app.noBackupFilesDir, "${YoutubeDL.baseName}/packages/python")
        val command = listOf(File(bin, "libpython.so").path, source().path) + listOf(
            "--ignore-config", "--cache-dir", File(app.cacheDir, "yt-dlp").path,
            "--js-runtimes", "quickjs:" + File(bin, "libqjs.so").path,
        ) + args
        val process = ProcessBuilder(command).apply {
            environment().apply {
                put("LD_LIBRARY_PATH", "${python.path}/usr/lib")
                put("SSL_CERT_FILE", "${python.path}/usr/etc/tls/cert.pem")
                put("PATH", System.getenv("PATH") + ":" + bin.path)
                put("PYTHONHOME", "${python.path}/usr")
                put("HOME", "${python.path}/usr")
                put("TMPDIR", app.cacheDir.path)
            }
        }.start()
        val err = StringBuilder()
        val errors = thread(name = "yt-stderr") {
            try { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } catch (e: IOException) { }
        }
        val watchdog = thread(name = "yt-watchdog") {
            try { if (!process.waitFor(150, TimeUnit.SECONDS)) process.destroyForcibly() } catch (e: InterruptedException) { }
        }
        try {
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            errors.join(2000)
            if (code != 0) throw IOException(friendly(err.toString()))
            out
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cancelado")
        } finally {
            watchdog.interrupt()
        }
    }

    /** The yt-dlp zip unpacked into a directory, redone whenever an update replaces the zip. */
    private fun source(): File = synchronized(this) {
        val base = File(app.noBackupFilesDir, YoutubeDL.baseName)
        val zip = File(base, "${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
        val dir = File(base, "src-${zip.length()}-${zip.lastModified()}")
        if (File(dir, "__main__.py").exists()) return dir
        base.listFiles { f -> f.name.startsWith("src-") }?.forEach { it.deleteRecursively() }
        val tmp = File(base, "src-tmp")
        tmp.deleteRecursively()
        try {
            val raw = zip.inputStream().buffered()
            // The release file is a zip with a "#!/usr/bin/env python3" line glued in front.
            raw.mark(2)
            if (raw.read() == '#'.code) { while (true) { val c = raw.read(); if (c == '\n'.code || c < 0) break } } else raw.reset()
            ZipInputStream(raw).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val target = File(tmp, entry.name)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw IOException("No se pudo preparar yt-dlp: ${e.message}")
        }
        if (!tmp.renameTo(dir)) throw IOException("No se pudo preparar yt-dlp.")
        dir
    }

    private fun friendly(raw: String?): String {
        val line = raw.orEmpty().lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR") }
            ?: raw.orEmpty().trim().lineSequence().lastOrNull().orEmpty()
        val text = line.removePrefix("ERROR:").trim().replace(Regex("""^\[[^\]]+]\s*[\w-]{11}:\s*"""), "")
        return text.take(220).ifEmpty { "yt-dlp no pudo extraer el audio." }
    }
}
