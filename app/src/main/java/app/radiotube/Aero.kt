package app.radiotube

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Frutiger Aero building blocks: sky, bubbles, glass, glossy orbs. Everything is drawn, no bitmaps. */
object Aero {
    val Navy = Color(0xFF07345F)
    val Sub = Color(0xFF2B669A)
    val Danger = Color(0xFFB3261E)
    val TitleShadow = Shadow(Color(0x66003A7A), Offset(0f, 2f), 8f)
}

class OrbColors(val top: Color, val mid: Color, val bottom: Color, val glow: Color, val rim: Color, val content: Color)

val OrbBlue = OrbColors(Color(0xFF8FDCFF), Color(0xFF1E90E8), Color(0xFF0A58B8), Color(0xFF8CF6FF), Color(0xFF063F8A), Color.White)
val OrbGreen = OrbColors(Color(0xFFD2FA7A), Color(0xFF5CC21F), Color(0xFF2E8F12), Color(0xFFE2FF8A), Color(0xFF1E6A0C), Color.White)
val OrbRed = OrbColors(Color(0xFFFFA99A), Color(0xFFE8402E), Color(0xFFB01E12), Color(0xFFFFC2B0), Color(0xFF7A120A), Color.White)
val OrbGlass = OrbColors(Color(0xE6FFFFFF), Color(0x99EAF6FF), Color(0x80BFE4FF), Color(0xCCFFFFFF), Color(0xFF5A9BD0), Aero.Navy)

private class Bubble(val x: Float, val radius: Float, val laps: Int, val phase: Float, val sway: Float)

private val bubbles = listOf(
    Bubble(0.08f, 26f, 1, 0.05f, 10f), Bubble(0.22f, 12f, 2, 0.40f, 6f), Bubble(0.34f, 40f, 1, 0.62f, 14f),
    Bubble(0.47f, 9f, 3, 0.15f, 5f), Bubble(0.58f, 20f, 2, 0.83f, 9f), Bubble(0.69f, 54f, 1, 0.30f, 16f),
    Bubble(0.80f, 14f, 2, 0.55f, 7f), Bubble(0.92f, 30f, 1, 0.90f, 12f), Bubble(0.15f, 7f, 3, 0.72f, 4f),
    Bubble(0.63f, 11f, 3, 0.48f, 6f), Bubble(0.40f, 17f, 2, 0.22f, 8f), Bubble(0.87f, 8f, 3, 0.02f, 5f),
)

@Composable
fun AeroBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0A55BE), 0.30f to Color(0xFF1B9DEE), 0.62f to Color(0xFF63D6EC),
                0.86f to Color(0xFF8FE9B4), 1f to Color(0xFFB2F07A),
            )
        )
        // Sun flare, top right.
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * 0.88f, h * 0.04f), radius = w * 0.75f,
            ),
            radius = w * 0.75f, center = Offset(w * 0.88f, h * 0.04f),
        )
        // Light ribbons sweeping across the sky.
        val ribbon = Path().apply {
            moveTo(-w * 0.1f, h * 0.33f)
            cubicTo(w * 0.30f, h * 0.20f, w * 0.62f, h * 0.44f, w * 1.1f, h * 0.25f)
            lineTo(w * 1.1f, h * 0.36f)
            cubicTo(w * 0.62f, h * 0.55f, w * 0.30f, h * 0.27f, -w * 0.1f, h * 0.46f)
            close()
        }
        drawPath(ribbon, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))))
        val ribbon2 = Path().apply {
            moveTo(-w * 0.1f, h * 0.72f)
            cubicTo(w * 0.25f, h * 0.84f, w * 0.60f, h * 0.58f, w * 1.1f, h * 0.70f)
            lineTo(w * 1.1f, h * 0.76f)
            cubicTo(w * 0.60f, h * 0.66f, w * 0.25f, h * 0.93f, -w * 0.1f, h * 0.80f)
            close()
        }
        drawPath(ribbon2, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.10f), Color.Transparent)))
        // Lime glow rising from the bottom, like sunlit grass.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFE6FF9A).copy(alpha = 0.75f), Color.Transparent),
                center = Offset(w * 0.2f, h * 1.05f), radius = w * 0.9f,
            ),
            radius = w * 0.9f, center = Offset(w * 0.2f, h * 1.05f),
        )
    }
    val drift = rememberInfiniteTransition(label = "bubbles")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "drift")
    Canvas(Modifier.fillMaxSize()) {
        val t = drift.value
        for (b in bubbles) {
            val r = b.radius.dp.toPx()
            val rise = (t * b.laps + b.phase) % 1f
            val y = size.height + r - rise * (size.height + 2 * r)
            val x = b.x * size.width + sin(rise * 2 * PI.toFloat() * 2 + b.phase * 6f) * b.sway.dp.toPx()
            drawBubble(Offset(x, y), r)
        }
    }
}

private fun DrawScope.drawBubble(center: Offset, r: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent, 0.62f to Color.White.copy(alpha = 0.05f), 1f to Color.White.copy(alpha = 0.38f),
            center = center, radius = r,
        ),
        radius = r, center = center,
    )
    drawCircle(Color.White.copy(alpha = 0.55f), radius = r, center = center, style = Stroke(width = 1.dp.toPx()))
    drawOval(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f), Color.Transparent),
            startY = center.y - r * 0.80f, endY = center.y - r * 0.25f,
        ),
        topLeft = Offset(center.x - r * 0.55f, center.y - r * 0.80f), size = Size(r * 0.75f, r * 0.50f),
    )
    drawCircle(Color.White.copy(alpha = 0.45f), radius = r * 0.10f, center = Offset(center.x + r * 0.45f, center.y + r * 0.50f))
}

/** Translucent white glass with a gloss band over its top half and a bright rim. */
fun Modifier.glass(shape: Shape, tint: Color = Color.White, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.66f * strength), tint.copy(alpha = 0.36f * strength))))
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.06f)),
                startY = 0f, endY = size.height * 0.5f,
            ),
            size = Size(size.width, size.height * 0.5f),
        )
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.40f))), shape)

private fun DrawScope.orbBody(c: OrbColors, corner: Float) {
    val radius = CornerRadius(corner, corner)
    drawRoundRect(Brush.verticalGradient(listOf(c.top, c.mid, c.bottom)), cornerRadius = radius)
    // Light pooling at the bottom, as if the button were lit from inside.
    drawRoundRect(
        Brush.radialGradient(
            listOf(c.glow.copy(alpha = 0.85f), Color.Transparent),
            center = Offset(size.width / 2, size.height * 1.02f), radius = size.maxDimension * 0.55f,
        ),
        cornerRadius = radius,
    )
    // Specular highlight across the top.
    val inset = size.minDimension * 0.07f
    val glossHeight = size.height * 0.46f
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.90f), Color.White.copy(alpha = 0.12f)),
            startY = inset, endY = inset + glossHeight,
        ),
        topLeft = Offset(inset * 1.6f, inset), size = Size(size.width - inset * 3.2f, glossHeight),
        cornerRadius = CornerRadius(glossHeight, glossHeight),
    )
}

@Composable
fun Orb(
    size: Dp,
    colors: OrbColors = OrbBlue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (colors === OrbGlass) 0.dp else 6.dp, CircleShape, ambientColor = colors.rim, spotColor = colors.rim)
            .clip(CircleShape)
            .drawBehind { orbBody(colors, this.size.minDimension / 2) }
            .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), colors.rim.copy(alpha = 0.55f))), CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun AeroPill(text: String, colors: OrbColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    Box(
        modifier
            .height(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (colors === OrbGlass) 0.dp else 4.dp, CircleShape, ambientColor = colors.rim, spotColor = colors.rim)
            .clip(CircleShape)
            .drawBehind { orbBody(colors, size.height / 2) }
            .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), colors.rim.copy(alpha = 0.55f))), CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = colors.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            style = if (colors === OrbGlass) TextStyle.Default else TextStyle(shadow = Shadow(colors.rim.copy(alpha = 0.7f), Offset(0f, 1f), 3f)),
        )
    }
}

/** A small glossy capsule: a list tab, or a toggle when [selected] changes on tap. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AeroChip(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, active: OrbColors = OrbBlue) {
    val colors = if (selected) active else OrbGlass
    Box(
        Modifier
            .height(38.dp)
            .clip(CircleShape)
            .drawBehind { orbBody(colors, size.height / 2) }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), colors.rim.copy(alpha = 0.45f))), CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = colors.content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            style = if (selected) TextStyle(shadow = Shadow(colors.rim.copy(alpha = 0.7f), Offset(0f, 1f), 3f)) else TextStyle.Default,
        )
    }
}

enum class Glyph { Play, Pause, Next, Previous, Plus, Repeat, RepeatOne, Shuffle, Close, Download }

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, color: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round)
        fun triangle(x0: Float, x1: Float) = Path().apply {
            moveTo(s * x0, s * 0.16f); lineTo(s * x1, s * 0.5f); lineTo(s * x0, s * 0.84f); close()
        }
        when (glyph) {
            Glyph.Play -> drawPath(triangle(0.26f, 0.86f), color)
            Glyph.Pause -> {
                val bar = Size(s * 0.20f, s * 0.64f)
                val round = CornerRadius(s * 0.05f)
                drawRoundRect(color, Offset(s * 0.20f, s * 0.18f), bar, round)
                drawRoundRect(color, Offset(s * 0.60f, s * 0.18f), bar, round)
            }
            Glyph.Next -> {
                drawPath(triangle(0.14f, 0.70f), color)
                drawRoundRect(color, Offset(s * 0.72f, s * 0.16f), Size(s * 0.13f, s * 0.68f), CornerRadius(s * 0.04f))
            }
            Glyph.Previous -> {
                drawPath(triangle(0.86f, 0.30f), color)
                drawRoundRect(color, Offset(s * 0.15f, s * 0.16f), Size(s * 0.13f, s * 0.68f), CornerRadius(s * 0.04f))
            }
            Glyph.Plus -> {
                drawLine(color, Offset(s * 0.5f, s * 0.14f), Offset(s * 0.5f, s * 0.86f), s * 0.16f, StrokeCap.Round)
                drawLine(color, Offset(s * 0.14f, s * 0.5f), Offset(s * 0.86f, s * 0.5f), s * 0.16f, StrokeCap.Round)
            }
            Glyph.Download -> {
                val w = s * 0.13f
                drawLine(color, Offset(s * 0.5f, s * 0.10f), Offset(s * 0.5f, s * 0.58f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.27f, s * 0.38f), Offset(s * 0.5f, s * 0.62f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.73f, s * 0.38f), Offset(s * 0.5f, s * 0.62f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.16f, s * 0.88f), Offset(s * 0.84f, s * 0.88f), w, StrokeCap.Round)
            }
            Glyph.Close -> {
                drawLine(color, Offset(s * 0.2f, s * 0.2f), Offset(s * 0.8f, s * 0.8f), s * 0.14f, StrokeCap.Round)
                drawLine(color, Offset(s * 0.8f, s * 0.2f), Offset(s * 0.2f, s * 0.8f), s * 0.14f, StrokeCap.Round)
            }
            Glyph.Repeat, Glyph.RepeatOne -> {
                val r = s * 0.34f
                drawArc(color, -50f, 290f, false, Offset(s * 0.5f - r, s * 0.5f - r), Size(r * 2, r * 2), style = stroke)
                // Arrow head where the loop closes (at -50°), pointing along the direction of travel.
                val a = (-50f) * PI.toFloat() / 180f
                val tip = Offset(s * 0.5f + r * cos(a), s * 0.5f + r * sin(a))
                drawPath(Path().apply {
                    moveTo(tip.x - s * 0.20f, tip.y - s * 0.10f)
                    lineTo(tip.x + s * 0.10f, tip.y - s * 0.16f)
                    lineTo(tip.x + s * 0.02f, tip.y + s * 0.14f)
                    close()
                }, color)
                if (glyph == Glyph.RepeatOne) {
                    drawLine(color, Offset(s * 0.5f, s * 0.38f), Offset(s * 0.5f, s * 0.64f), s * 0.11f, StrokeCap.Round)
                }
            }
            Glyph.Shuffle -> {
                val up = Path().apply { moveTo(s * 0.10f, s * 0.72f); cubicTo(s * 0.45f, s * 0.72f, s * 0.45f, s * 0.28f, s * 0.78f, s * 0.28f) }
                val down = Path().apply { moveTo(s * 0.10f, s * 0.28f); cubicTo(s * 0.45f, s * 0.28f, s * 0.45f, s * 0.72f, s * 0.78f, s * 0.72f) }
                drawPath(up, color, style = stroke)
                drawPath(down, color, style = stroke)
                for (y in listOf(0.28f, 0.72f)) {
                    drawPath(Path().apply {
                        moveTo(s * 0.74f, s * (y - 0.14f)); lineTo(s * 0.96f, s * y); lineTo(s * 0.74f, s * (y + 0.14f)); close()
                    }, color)
                }
            }
        }
    }
}

/** A glass groove that fills with glossy lime; drag or tap to seek. */
@Composable
fun AeroSeekBar(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        val track = 12.dp.toPx()
        val knob = 11.dp.toPx()
        val top = (size.height - track) / 2
        val left = knob
        val width = size.width - 2 * knob
        val round = CornerRadius(track / 2)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x80063F8A), Color(0x40147AD0)), startY = top, endY = top + track),
            Offset(left, top), Size(width, track), round,
        )
        drawRoundRect(Color.White.copy(alpha = 0.75f), Offset(left, top), Size(width, track), round, style = Stroke(1.dp.toPx()))
        val filled = width * shown
        if (filled > 1f) {
            val fill = Size(filled.coerceAtLeast(track), track)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFDFFF8A), Color(0xFF6CCB24), Color(0xFF3FA516)), startY = top, endY = top + track),
                Offset(left, top), fill, round,
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.55f), Offset(left + track * 0.3f, top + track * 0.14f),
                Size((fill.width - track * 0.6f).coerceAtLeast(0f), track * 0.34f), CornerRadius(track),
            )
        }
        if (enabled) {
            val center = Offset(left + filled, size.height / 2)
            drawCircle(Color(0x55003A7A), knob, center + Offset(0f, 1.5.dp.toPx()))
            drawCircle(Brush.verticalGradient(listOf(Color.White, Color(0xFFBFE6FF)), startY = center.y - knob, endY = center.y + knob), knob, center)
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF8FDCFF), Color(0xFF1479D6)), startY = center.y - knob * 0.55f, endY = center.y + knob * 0.55f), knob * 0.52f, center)
            drawCircle(Color.White.copy(alpha = 0.9f), knob, center, style = Stroke(1.dp.toPx()))
        }
    }
}

/** Three bars that dance while music plays. */
@Composable
fun Equalizer(active: Boolean, modifier: Modifier = Modifier, color: Color = Color(0xFF3FA516)) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = listOf(520, 380, 640).map { duration ->
        transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse), label = "bar")
    }
    Canvas(modifier.size(18.dp)) {
        val bar = size.width / 5
        heights.forEachIndexed { i, state ->
            val h = size.height * if (active) state.value else 0.35f
            drawRoundRect(color, Offset(i * bar * 2, size.height - h), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

@Composable
fun AeroField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFDCEEFA), Color.White)))
            .border(1.dp, Color(0xFF7FB2DC), shape)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, color = Aero.Sub.copy(alpha = 0.65f), fontSize = 15.sp, maxLines = 1)
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
            textStyle = TextStyle(color = Aero.Navy, fontSize = 15.sp), cursorBrush = SolidColor(Aero.Navy),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AeroDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFAFFFFFF), Color(0xF2D6EEFF))))
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(listOf(Color(0x66BFE6FF), Color.Transparent), endY = size.height * 0.35f),
                        size = Size(size.width, size.height * 0.35f),
                    )
                }
                .border(1.5.dp, Color.White, shape)
                .padding(20.dp)
        ) {
            Text(title, color = Aero.Navy, fontSize = 22.sp, fontWeight = FontWeight.Light)
            content()
        }
    }
}
