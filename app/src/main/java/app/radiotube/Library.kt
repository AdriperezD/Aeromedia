package app.radiotube

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Yt.init(this)
    }
}

data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val live: Boolean = false,
    /** The stream yt-dlp hands back is an HLS manifest rather than a plain audio file. */
    val hls: Boolean = false,
    /** The user typed this title, so yt-dlp metadata must not overwrite it. */
    val custom: Boolean = false,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumb get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val thumbLarge get() = "https://i.ytimg.com/vi/$id/hq720.jpg"
}

/** Playback errors travel from the service to the UI through here (same process). */
object Bus {
    val error = MutableStateFlow<String?>(null)
}

object Items {
    const val SCHEME = "radiotube"

    /** The URI is a placeholder; [Yt.resolve] swaps in the real stream when ExoPlayer opens it. */
    fun build(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(Uri.parse("$SCHEME://v/${t.id}" + if (t.hls) "?hls=1" else ""))
        .apply { if (t.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist.ifEmpty { "YouTube" })
                .setArtworkUri(Uri.parse(t.thumb))
                .build()
        )
        .build()
}

object Library {
    private val state = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = state
    private var file: File? = null
    private var prefs: SharedPreferences? = null
    private val io = Executors.newSingleThreadExecutor()

    private val LINK = Regex(
        """(?:youtu\.be/|youtube\.com/(?:watch\?(?:[^\s#]*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"""
    )

    /** Every YouTube video id found in free text (a pasted link, a share blurb, several lines…). */
    fun findIds(text: String): List<String> = LINK.findAll(text).map { it.groupValues[1] }.distinct().toList()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "library.json")
        file = f
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        state.value = try {
            val array = JSONArray(f.readText())
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Track(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    durationMs = o.optLong("duration"),
                    positionMs = o.optLong("position"),
                    live = o.optBoolean("live"),
                    hls = o.optBoolean("hls"),
                    custom = o.optBoolean("custom"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Track? = state.value.firstOrNull { it.id == id }

    /** The track that was on last time, so the app can have its stream ready before the user asks. */
    var lastPlayed: Track?
        get() = prefs?.getString("last", null)?.let(::get)
        set(value) { prefs?.edit()?.putString("last", value?.id)?.apply() }

    /** Returns true when the track is new. An existing one is only renamed if a name was given. */
    fun add(id: String, name: String? = null): Boolean {
        val clean = name?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
        var added = false
        mutate { list ->
            if (list.any { it.id == id }) {
                if (clean == null) list else list.map { if (it.id == id) it.copy(title = clean, custom = true) else it }
            } else {
                added = true
                list + Track(id = id, title = clean ?: "YouTube · $id", custom = clean != null)
            }
        }
        return added
    }

    fun remove(id: String) = mutate { list -> list.filter { it.id != id } }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(160)
        if (clean.isNotEmpty()) edit(id) { it.copy(title = clean, custom = true) }
    }

    fun setPosition(id: String, positionMs: Long) = edit(id) { it.copy(positionMs = positionMs.coerceAtLeast(0)) }

    fun setHls(id: String, hls: Boolean) = edit(id) { it.copy(hls = hls) }

    fun setMeta(id: String, title: String, artist: String, durationMs: Long, live: Boolean, hls: Boolean) = edit(id) {
        it.copy(
            title = if (it.custom || title.isEmpty()) it.title else title.take(160),
            artist = artist.ifEmpty { it.artist },
            durationMs = if (durationMs > 0) durationMs else it.durationMs,
            live = live,
            hls = hls,
        )
    }

    private fun edit(id: String, change: (Track) -> Track) =
        mutate { list -> list.map { if (it.id == id) change(it) else it } }

    @Synchronized
    private fun mutate(change: (List<Track>) -> List<Track>) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        val target = file ?: return
        io.execute {
            val array = JSONArray()
            for (t in next) {
                array.put(
                    JSONObject()
                        .put("id", t.id).put("title", t.title).put("artist", t.artist)
                        .put("duration", t.durationMs).put("position", t.positionMs)
                        .put("live", t.live).put("hls", t.hls).put("custom", t.custom)
                )
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(target)
        }
    }
}
