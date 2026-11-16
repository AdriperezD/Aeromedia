package app.radiotube

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Background audio: the saved library is ExoPlayer's playlist, and each item is resolved by yt-dlp on demand. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val retries = HashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val uri = spec.uri
            if (uri.scheme != Items.SCHEME) return@Factory spec
            val stream = Yt.resolve(uri.lastPathSegment.orEmpty(), expectHls = uri.getQueryParameter("hls") == "1")
            spec.buildUpon().setUri(stream.url).setHttpRequestHeaders(stream.headers).build()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Sound only: when a stream carries video (live HLS), never decode it.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setCallback(callback).setSessionActivity(open).build()

        scope.launch { Library.tracks.collect { sync() } }
        scope.launch { Library.playlists.collect { sync() } }
        scope.launch {
            while (true) {
                delay(4000)
                if (player.isPlaying) rememberPosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        rememberPosition()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private val callback = object : MediaSession.Callback {
        // Controllers only send media ids; the playable item is always rebuilt from the library.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { Library.get(it.mediaId)?.let(Items::build) }.toMutableList())
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val previous = old.mediaItem?.mediaId ?: return
            if (previous == new.mediaItem?.mediaId) return
            // A track that played to its end starts over next time; one the user left resumes where it was.
            val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            if (Library.get(previous)?.live != true) Library.setPosition(previous, if (finished) 0 else old.positionMs)
            Bus.error.value = null
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { Library.lastPlayed = Library.get(it.mediaId) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                player.currentMediaItem?.let { retries.remove(it.mediaId) }
                Bus.error.value = null
            } else if (player.playbackState == Player.STATE_READY) {
                rememberPosition()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) player.currentMediaItem?.let { Library.setPosition(it.mediaId, 0) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            var cause: Throwable? = error
            var root: Throwable = error
            var mismatch: Yt.HlsMismatch? = null
            while (cause != null) {
                if (cause is Yt.HlsMismatch) mismatch = cause
                root = cause
                cause = cause.cause
            }
            val index = player.currentMediaItemIndex
            if (mismatch != null) {
                // yt-dlp returned HLS where a plain file was expected (or the reverse): rebuild and go again.
                Library.setHls(id, mismatch.hls)
                val track = Library.get(id) ?: return
                val position = if (track.live) C.TIME_UNSET else player.currentPosition
                player.replaceMediaItem(index, Items.build(track))
                player.seekTo(index, position)
                player.prepare()
                return
            }
            val attempt = retries[id] ?: 0
            if (attempt < 2) {
                // Stream URLs expire after a few hours; ask yt-dlp for a fresh one.
                retries[id] = attempt + 1
                Yt.invalidate(id)
                player.prepare()
                return
            }
            Bus.error.value = root.message ?: "No se pudo reproducir."
        }
    }

    private fun rememberPosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.isCurrentMediaItemLive || Library.get(id)?.live == true) return
        if (player.playbackState == Player.STATE_READY) Library.setPosition(id, player.currentPosition)
    }

    /** Keeps the running queue in step with the list it was started from: removals, additions, renames. */
    private fun sync() {
        if (player.mediaItemCount == 0) return
        val byId = Library.tracks.value.associateBy { it.id }
        // Null when the playlist being played was deleted: keep playing what is queued, minus deleted tracks.
        val wanted = Library.queue(Library.context)
        val wantedIds = wanted?.mapTo(HashSet()) { it.id }
        for (i in player.mediaItemCount - 1 downTo 0) {
            val id = player.getMediaItemAt(i).mediaId
            if (id !in byId || (wantedIds != null && id !in wantedIds)) player.removeMediaItem(i)
        }
        val present = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
        for (t in wanted.orEmpty()) if (t.id !in present) player.addMediaItem(Items.build(t))
        for (i in 0 until player.mediaItemCount) {
            val current = player.getMediaItemAt(i)
            val wanted = Items.build(byId[current.mediaId] ?: continue)
            // The URI changes only through the error handler above; here only labels are refreshed.
            if (current.localConfiguration?.uri != wanted.localConfiguration?.uri) continue
            if (current.mediaMetadata.title?.toString() != wanted.mediaMetadata.title?.toString() ||
                current.mediaMetadata.artist?.toString() != wanted.mediaMetadata.artist?.toString()
            ) {
                player.replaceMediaItem(i, wanted)
            }
        }
    }
}
