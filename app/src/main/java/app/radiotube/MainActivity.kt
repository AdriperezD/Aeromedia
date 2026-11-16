package app.radiotube

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

/** What the screen knows about the player, mirrored from the service's MediaSession. */
class PlayerUi {
    var controller by mutableStateOf<MediaController?>(null)
    var currentId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var live by mutableStateOf(false)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffle by mutableStateOf(false)

    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pull()
    }

    fun pull() {
        val c = controller ?: return
        currentId = c.currentMediaItem?.mediaId
        val state = c.playbackState
        playing = c.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        buffering = c.playWhenReady && state == Player.STATE_BUFFERING
        live = c.isCurrentMediaItemLive
        position = c.currentPosition.coerceAtLeast(0)
        duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        repeat = c.repeatMode
        shuffle = c.shuffleModeEnabled
    }

    fun play(track: Track) {
        val c = controller ?: return
        Bus.error.value = null
        val state = c.playbackState
        if (currentId == track.id && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)) {
            if (c.playWhenReady) c.pause() else c.play()
            return
        }
        // Long mixes pick up where they were left, like the desktop plugin does.
        val resume = when {
            track.live -> C.TIME_UNSET
            track.positionMs > 10_000 && (track.durationMs == 0L || track.positionMs < track.durationMs - 15_000) -> track.positionMs
            else -> 0L
        }
        val index = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == track.id }
        if (index == null) {
            val library = Library.tracks.value
            c.setMediaItems(library.map(Items::build), library.indexOfFirst { it.id == track.id }.coerceAtLeast(0), resume)
        } else {
            c.seekTo(index, resume)
        }
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        Bus.error.value = null
        when {
            c.mediaItemCount == 0 -> (Library.lastPlayed ?: Library.tracks.value.firstOrNull())?.let(::play)
            c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            c.playWhenReady -> c.pause()
            else -> c.play()
        }
    }

    fun seek(fraction: Float) {
        val c = controller ?: return
        if (duration <= 0) return
        position = (duration * fraction).toLong()
        c.seekTo(position)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }
}

/** Saves every YouTube link found in [text]. Returns null when there was none, else how many were new. */
fun addLinks(text: String, name: String? = null): Int? {
    val ids = Library.findIds(text)
    if (ids.isEmpty()) return null
    var added = 0
    for (id in ids) {
        if (Library.add(id, name.takeIf { ids.size == 1 })) added++
        Yt.prefetch(id)
    }
    return added
}

class MainActivity : ComponentActivity() {
    private val ui = PlayerUi()
    private var future: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) {
            handleShare(intent)
            Library.lastPlayed?.let { Yt.prefetch(it.id) }
        }
        setContent { RadioTubeScreen(ui) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val message = when (addLinks(text)) {
            null -> "Eso no parece un vídeo de YouTube"
            0 -> "Ya estaba en tu biblioteca"
            else -> "Añadido a RadioTube"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val pending = MediaController.Builder(this, token).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending || pending.isCancelled) return@addListener
            val controller = try { pending.get() } catch (e: Exception) { return@addListener }
            controller.addListener(ui.listener)
            ui.controller = controller
            ui.pull()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        ui.controller?.removeListener(ui.listener)
        ui.controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun RadioTubeScreen(ui: PlayerUi) {
    val tracks by Library.tracks.collectAsState()
    val status by Yt.status.collectAsState()
    val error by Bus.error.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(ui.controller) {
        while (true) {
            ui.pull()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AeroBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Header(status)
            NowPlaying(ui, tracks.firstOrNull { it.id == ui.currentId }, error, hasTracks = tracks.isNotEmpty())
            Row(Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Mi biblioteca", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light,
                    style = TextStyle(shadow = Aero.TitleShadow), modifier = Modifier.weight(1f),
                )
                if (tracks.isNotEmpty()) {
                    Text("${tracks.size}", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, style = TextStyle(shadow = Aero.TitleShadow))
                }
            }
            if (tracks.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            current = track.id == ui.currentId,
                            playing = track.id == ui.currentId && ui.playing && !ui.buffering,
                            onClick = { ui.play(track) },
                            onLongClick = { editing = track },
                        )
                    }
                }
            }
        }
        Orb(
            size = 64.dp, colors = OrbGreen, onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp),
        ) { GlyphIcon(Glyph.Plus, 26.dp) }
    }

    if (adding) AddDialog(onDismiss = { adding = false })
    editing?.let { track -> EditDialog(track, onDismiss = { editing = null }) }
}

@Composable
private fun Header(status: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Radio", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light, style = TextStyle(shadow = Aero.TitleShadow))
                Text("Tube", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(shadow = Aero.TitleShadow))
            }
            Text(
                status ?: "Tu música de YouTube, solo el sonido",
                color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, style = TextStyle(shadow = Aero.TitleShadow),
            )
        }
    }
}

@Composable
private fun NowPlaying(ui: PlayerUi, track: Track?, error: String?, hasTracks: Boolean) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(92.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Nada sonando",
                    color = Aero.Navy, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val line = when {
                    error != null -> error
                    track == null -> if (hasTracks) "Toca una pista para empezar" else "Añade tu primer enlace con el botón verde"
                    ui.buffering -> "Sintonizando…"
                    else -> track.artist.ifEmpty { "YouTube" }
                }
                Text(
                    line, color = if (error != null) Aero.Danger else Aero.Sub, fontSize = 13.sp, lineHeight = 17.sp,
                    maxLines = if (error != null) 3 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val seekable = track != null && !ui.live && ui.duration > 0
        AeroSeekBar(
            fraction = if (ui.live) 1f else if (ui.duration > 0) ui.position.toFloat() / ui.duration else 0f,
            enabled = seekable, onSeek = ui::seek,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text(if (track == null) "–:––" else if (ui.live) "" else clock(ui.position), color = Aero.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    ui.live -> "● EN DIRECTO"
                    ui.duration > 0 -> clock(ui.duration)
                    track != null && track.durationMs > 0 -> clock(track.durationMs)
                    else -> "–:––"
                },
                color = if (ui.live) Aero.Danger else Aero.Sub, fontSize = 12.sp, fontWeight = if (ui.live) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val repeating = ui.repeat != Player.REPEAT_MODE_OFF
            Orb(40.dp, if (repeating) OrbGreen else OrbGlass, onClick = ui::cycleRepeat) {
                GlyphIcon(if (ui.repeat == Player.REPEAT_MODE_ONE) Glyph.RepeatOne else Glyph.Repeat, 18.dp, if (repeating) Color.White else Aero.Navy)
            }
            Orb(50.dp, OrbBlue, onClick = { ui.controller?.seekToPrevious() }) { GlyphIcon(Glyph.Previous, 20.dp) }
            Orb(72.dp, OrbBlue, onClick = ui::toggle) { GlyphIcon(if (ui.playing) Glyph.Pause else Glyph.Play, 30.dp) }
            Orb(50.dp, OrbBlue, onClick = { ui.controller?.seekToNext() }) { GlyphIcon(Glyph.Next, 20.dp) }
            Orb(40.dp, if (ui.shuffle) OrbGreen else OrbGlass, onClick = ui::toggleShuffle) {
                GlyphIcon(Glyph.Shuffle, 18.dp, if (ui.shuffle) Color.White else Aero.Navy)
            }
        }
    }
}

/** Cover art under glass: white rim plus a diagonal reflection. */
@Composable
private fun Artwork(track: Track?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .drawWithContent {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF8FDCFF), Color(0xFF1479D6))))
                drawContent()
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.55f), 0.45f to Color.White.copy(alpha = 0.10f), 0.46f to Color.Transparent,
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    )
                )
            }
            .border(2.dp, Color.White.copy(alpha = 0.95f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (track == null) {
            GlyphIcon(Glyph.Play, 34.dp, Color.White.copy(alpha = 0.9f))
        } else {
            var fallback by remember(track.id) { mutableStateOf(false) }
            AsyncImage(
                model = if (fallback) track.thumb else track.thumbLarge,
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { fallback = true },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: Track, current: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(shape, tint = if (current) Color(0xFFE9FFD0) else Color.White, strength = if (current) 1.25f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = RoundedCornerShape(13.dp)
        AsyncImage(
            model = track.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.height(50.dp).aspectRatio(16f / 9f).clip(thumb).border(1.5.dp, Color.White.copy(alpha = 0.95f), thumb),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Aero.Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val resumable = !track.live && track.positionMs > 10_000
            val detail = buildList {
                if (track.artist.isNotEmpty()) add(track.artist)
                when {
                    track.live -> add("En directo")
                    resumable && track.durationMs > 0 -> add("${clock(track.positionMs)} / ${clock(track.durationMs)}")
                    track.durationMs > 0 -> add(clock(track.durationMs))
                }
            }.joinToString(" · ").ifEmpty { "YouTube" }
            Text(detail, color = Aero.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) {
            Spacer(Modifier.width(8.dp))
            Equalizer(active = playing)
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tu biblioteca está vacía", color = Aero.Navy, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pega un enlace con el botón verde, o en YouTube pulsa Compartir y elige RadioTube.",
            color = Aero.Sub, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun clipboardText(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

@Composable
private fun AddDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AeroDialog("Añadir de YouTube", onDismiss) {
        Spacer(Modifier.height(14.dp))
        AeroField(link, { link = it; invalid = false }, "Enlace del vídeo", singleLine = false)
        Spacer(Modifier.height(10.dp))
        AeroField(name, { name = it }, "Nombre (opcional)")
        if (invalid) {
            Spacer(Modifier.height(8.dp))
            Text("Usa un enlace de vídeo, Short o directo de YouTube.", color = Aero.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeroPill("Pegar", OrbGlass, onClick = {
                val pasted = clipboardText(context)
                if (pasted.isNotBlank()) { link = pasted.trim(); invalid = false }
            })
            AeroPill("Añadir", OrbGreen, modifier = Modifier.weight(1f), onClick = {
                when (addLinks(link, name)) {
                    null -> invalid = true
                    else -> onDismiss()
                }
            })
        }
    }
}

@Composable
private fun EditDialog(track: Track, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(track.title) }
    AeroDialog("Editar pista", onDismiss) {
        Spacer(Modifier.height(14.dp))
        AeroField(name, { name = it }, "Nombre")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AeroPill("Eliminar", OrbRed, onClick = { Library.remove(track.id); onDismiss() })
            AeroPill("Guardar", OrbBlue, modifier = Modifier.weight(1f), onClick = {
                if (name.trim() != track.title) Library.rename(track.id, name)
                onDismiss()
            })
        }
    }
}
