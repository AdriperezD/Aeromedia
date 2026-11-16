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
import java.util.UUID
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

/** A named list of saved tracks. [source] is the YouTube playlist it was imported from, if any. */
data class Playlist(val id: String, val name: String, val trackIds: List<String> = emptyList(), val source: String? = null)

/** Messages from background work to the UI (same process). */
object Bus {
    /** Why playback failed; shown in the now-playing panel. */
    val error = MutableStateFlow<String?>(null)
    /** One-off notice, shown as a toast. */
    val notice = MutableStateFlow<String?>(null)
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
    private val lists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = lists
    private var file: File? = null
    private var prefs: SharedPreferences? = null
    private val io = Executors.newSingleThreadExecutor()

    private val LINK = Regex(
        """(?:youtu\.be/|youtube\.com/(?:watch\?(?:[^\s#]*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"""
    )
    private val LIST_LINK = Regex("""youtube\.com/playlist\?(?:[^\s#]*&)?list=([A-Za-z0-9_-]{10,})""")

    /** Every YouTube video id found in free text (a pasted link, a share blurb, several lines…). */
    fun findIds(text: String): List<String> = LINK.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Playlist ids from playlist links. A video link that merely carries a list= parameter counts as the video. */
    fun findPlaylistIds(text: String): List<String> = LIST_LINK.findAll(text).map { it.groupValues[1] }.distinct().toList()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "library.json")
        file = f
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        try {
            val text = f.readText().trim()
            // The first version stored a bare array of tracks.
            val root = if (text.startsWith("[")) JSONObject().put("tracks", JSONArray(text)) else JSONObject(text)
            val array = root.optJSONArray("tracks") ?: JSONArray()
            state.value = List(array.length()) { i ->
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
            val saved = root.optJSONArray("playlists") ?: JSONArray()
            lists.value = List(saved.length()) { i ->
                val o = saved.getJSONObject(i)
                val ids = o.optJSONArray("tracks") ?: JSONArray()
                Playlist(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    trackIds = List(ids.length()) { ids.getString(it) },
                    source = o.optString("source").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            // First run, or an unreadable file: start empty.
        }
    }

    fun get(id: String): Track? = state.value.firstOrNull { it.id == id }

    fun playlist(id: String?): Playlist? = lists.value.firstOrNull { it.id == id }

    /** The track that was on last time, so the app can have its stream ready before the user asks. */
    var lastPlayed: Track?
        get() = prefs?.getString("last", null)?.let(::get)
        set(value) { prefs?.edit()?.putString("last", value?.id)?.apply() }

    /** Which list the player's queue mirrors: a playlist id, or null for the whole library. */
    var context: String?
        get() = prefs?.getString("context", null)
        set(value) { prefs?.edit()?.putString("context", value)?.apply() }

    /** The tracks of a list in order; null when that playlist no longer exists. */
    fun queue(context: String?): List<Track>? =
        if (context == null) state.value else playlist(context)?.trackIds?.mapNotNull(::get)

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

    @Synchronized
    fun remove(id: String) {
        mutate { list -> list.filter { it.id != id } }
        mutateLists { all -> all.map { it.copy(trackIds = it.trackIds - id) } }
    }

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

    /** Returns the new playlist's id, or null when the name is blank. */
    fun createPlaylist(name: String): String? {
        val clean = name.trim().take(60).ifEmpty { return null }
        val id = UUID.randomUUID().toString()
        mutateLists { it + Playlist(id, clean) }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().take(60)
        if (clean.isNotEmpty()) mutateLists { all -> all.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    /** Deletes the list. With [withTracks], the tracks that are in no other list leave the library too. */
    @Synchronized
    fun deletePlaylist(id: String, withTracks: Boolean) {
        val doomed = playlist(id) ?: return
        mutateLists { all -> all.filter { it.id != id } }
        if (!withTracks) return
        val kept = lists.value.flatMapTo(HashSet()) { it.trackIds }
        val gone = doomed.trackIds.filterTo(HashSet()) { it !in kept }
        mutate { list -> list.filter { it.id !in gone } }
    }

    fun setMember(playlistId: String, trackId: String, member: Boolean) = mutateLists { all ->
        all.map {
            when {
                it.id != playlistId -> it
                member -> if (trackId in it.trackIds) it else it.copy(trackIds = it.trackIds + trackId)
                else -> it.copy(trackIds = it.trackIds - trackId)
            }
        }
    }

    /**
     * Saves a YouTube playlist: unknown videos join the library, and the playlist is created, or, if it was
     * imported before, gains whatever is new. Returns how many tracks the playlist now holds.
     */
    @Synchronized
    fun importPlaylist(source: String, name: String, found: List<Track>): Int {
        mutate { list -> list + found.filter { f -> list.none { it.id == f.id } }.distinctBy { it.id } }
        val ids = found.map { it.id }.distinct()
        mutateLists { all ->
            if (all.any { it.source == source }) {
                all.map { if (it.source == source) it.copy(trackIds = (it.trackIds + ids).distinct()) else it }
            } else {
                all + Playlist(UUID.randomUUID().toString(), name.trim().take(60).ifEmpty { "Lista de YouTube" }, ids, source)
            }
        }
        return lists.value.firstOrNull { it.source == source }?.trackIds?.size ?: 0
    }

    private fun edit(id: String, change: (Track) -> Track) =
        mutate { list -> list.map { if (it.id == id) change(it) else it } }

    @Synchronized
    private fun mutate(change: (List<Track>) -> List<Track>) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        save()
    }

    @Synchronized
    private fun mutateLists(change: (List<Playlist>) -> List<Playlist>) {
        val next = change(lists.value)
        if (next == lists.value) return
        lists.value = next
        save()
    }

    private fun save() {
        val target = file ?: return
        val tracks = state.value
        val playlists = lists.value
        io.execute {
            val trackArray = JSONArray()
            for (t in tracks) {
                trackArray.put(
                    JSONObject()
                        .put("id", t.id).put("title", t.title).put("artist", t.artist)
                        .put("duration", t.durationMs).put("position", t.positionMs)
                        .put("live", t.live).put("hls", t.hls).put("custom", t.custom)
                )
            }
            val listArray = JSONArray()
            for (p in playlists) {
                listArray.put(
                    JSONObject().put("id", p.id).put("name", p.name)
                        .put("tracks", JSONArray(p.trackIds)).put("source", p.source ?: "")
                )
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(JSONObject().put("tracks", trackArray).put("playlists", listArray).toString())
            tmp.renameTo(target)
        }
    }
}
