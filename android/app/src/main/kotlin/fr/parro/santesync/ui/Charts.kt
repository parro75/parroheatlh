package fr.parro.santesync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ---- graphiques interactifs (26/11/2026) ---------------------------------------------------------------
// Toucher ou glisser le doigt sélectionne un point ; l'écran affiche alors sa valeur en grand au-dessus.

data class BarPoint(val key: String, val label: String, val tick: String, val value: Double?)

@Composable
private fun rememberReveal(key: Any?, millis: Int = 600): Animatable<Float, *> {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) { anim.snapTo(0f); anim.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) }
    return anim
}

/** Barres : sélection au toucher ou en glissant, ligne d'objectif en pointillés, croissance animée. */
@Composable
fun InteractiveBars(
    points: List<BarPoint>, color: Color, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    goal: Double? = null, goalLabel: String? = null, floor: Double = 0.0, height: Dp = 170.dp, description: String = "Graphique en barres",
) {
    val c = P.c
    val anim = rememberReveal(points)
    val measurer = rememberTextMeasurer()
    val n = points.size
    val top = (points.mapNotNull { it.value } + listOfNotNull(goal)).maxOrNull()?.let { it + (it - floor) * 0.08 } ?: 1.0
    val span = (top - floor).takeIf { it > 0 } ?: 1.0
    val dim = color.copy(alpha = 0.42f)
    val tickStyle = TextStyle(fontSize = 10.sp, color = c.muted)
    fun pick(x: Float, width: Int) { if (n > 0) onSelect((x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)) }
    Canvas(modifier.fillMaxWidth().height(height + 18.dp).semantics { contentDescription = description }
        .pointerInput(n) { detectTapGestures { pick(it.x, size.width) } }
        .pointerInput(n) { detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) } }) {
        if (n == 0) return@Canvas
        val chartH = size.height - 18.dp.toPx()
        val slot = size.width / n
        val gap = when { n > 60 -> 1.dp.toPx(); n > 14 -> 3.dp.toPx(); else -> 8.dp.toPx() }
        val w = (slot - gap).coerceAtLeast(1.5f)
        val r = min(w / 2, 8.dp.toPx())
        fun y(v: Double) = (chartH - ((v - floor) / span * (chartH - 4.dp.toPx()))).toFloat()
        points.forEachIndexed { i, p ->
            val v = p.value ?: return@forEachIndexed
            val h = ((chartH - y(v)) * anim.value).coerceAtLeast(2.dp.toPx())
            val col = when {
                i == selected -> color
                goal != null && v >= goal -> color.copy(alpha = 0.8f)
                goal == null -> color.copy(alpha = 0.6f)
                else -> dim
            }
            drawRoundRect(col, Offset(i * slot + (slot - w) / 2, chartH - h), Size(w, h), CornerRadius(r, r))
            if (p.tick.isNotEmpty()) {
                val t = measurer.measure(p.tick, tickStyle)
                drawText(t, topLeft = Offset((i * slot + slot / 2 - t.size.width / 2f).coerceIn(0f, size.width - t.size.width),
                    chartH + 4.dp.toPx()))
            }
        }
        goal?.let { g ->
            val gy = y(g)
            drawLine(c.muted, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            goalLabel?.let { label ->
                val t = measurer.measure(label, TextStyle(fontSize = 10.sp, color = color))
                val x = size.width - t.size.width - 2.dp.toPx()
                val ty = (gy - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)
                drawRect(c.surface, Offset(x - 4.dp.toPx(), ty), Size(t.size.width + 6.dp.toPx(), t.size.height.toFloat()))
                drawText(t, topLeft = Offset(x, ty))
            }
        }
    }
}

/** Courbe qu'on parcourt du doigt : point et trait vertical suivent, moyenne en pointillés, tracé animé. */
@Composable
fun ScrubLine(
    values: List<Double?>, color: Color, format: (Double) -> String, selected: Int?, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, axis: List<String> = emptyList(), height: Dp = 180.dp, average: Boolean = true,
    description: String = "Courbe",
) {
    val c = P.c
    val anim = rememberReveal(values, 900)
    val measurer = rememberTextMeasurer()
    val n = values.size
    val present = values.indices.filter { values[it] != null }
    val vs = present.map { values[it]!! }
    val avg = if (vs.isNotEmpty()) vs.average() else 0.0
    val lo0 = vs.minOrNull() ?: 0.0
    val hi0 = vs.maxOrNull() ?: 1.0
    val pad = ((hi0 - lo0).takeIf { it > 0 } ?: (abs(hi0) * 0.1 + 1)) * 0.15
    val lo = lo0 - pad
    val hi = hi0 + pad
    fun nearest(i: Int): Int? = present.minByOrNull { abs(it - i) }
    fun pick(x: Float, width: Int) {
        if (n < 1) return
        val i = if (n == 1) 0 else ((x / width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
        nearest(i)?.let(onSelect)
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
            .pointerInput(n) { detectTapGestures { pick(it.x, size.width) } }
            .pointerInput(n) { detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) } }) {
            if (present.isEmpty()) return@Canvas
            val padY = 8.dp.toPx()
            fun x(i: Int) = if (n <= 1) size.width / 2 else i * size.width / (n - 1)
            fun y(v: Double) = (padY + (hi - v) / (hi - lo) * (size.height - 2 * padY)).toFloat()
            if (average && vs.size > 1) {
                val ay = y(avg)
                drawLine(c.faint, Offset(0f, ay), Offset(size.width, ay), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                val t = measurer.measure("moy. " + format(avg), TextStyle(fontSize = 10.sp, color = c.muted))
                drawText(t, topLeft = Offset(size.width - t.size.width, (ay - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
            val line = Path()
            val area = Path()
            var runStart = -1
            var prev = -1
            fun closeRun() {
                if (runStart >= 0 && prev > runStart) {
                    area.lineTo(x(prev), size.height); area.lineTo(x(runStart), size.height); area.close()
                }
            }
            present.forEach { i ->
                val p = Offset(x(i), y(values[i]!!))
                if (prev >= 0 && i == prev + 1) {
                    line.lineTo(p.x, p.y); area.lineTo(p.x, p.y)
                } else {
                    closeRun()
                    line.moveTo(p.x, p.y); area.moveTo(p.x, p.y); runStart = i
                }
                prev = i
            }
            closeRun()
            clipRect(right = size.width * anim.value) {
                drawPath(area, color.copy(alpha = 0.12f))
                drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                present.forEach { i -> // points isolés (pesées espacées, trous) : visibles quand même
                    val alone = (i - 1 !in present) && (i + 1 !in present)
                    if (alone || n <= 20) drawCircle(color, 2.5.dp.toPx(), Offset(x(i), y(values[i]!!)))
                }
            }
            selected?.takeIf { it in present }?.let { s ->
                val p = Offset(x(s), y(values[s]!!))
                drawLine(c.line, Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(c.surface, 10.dp.toPx(), p)
                drawCircle(color, 6.5.dp.toPx(), p)
            }
        }
        if (axis.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            axis.forEach { Muted(it, 11.sp) }
        }
    }
}

/** Mini-courbe des tuiles (7 à 30 points). */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    val anim = rememberReveal(values, 700)
    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val vs = values.filterNotNull()
        if (vs.size < 2) return@Canvas
        val lo = vs.min()
        val hi = vs.max().let { if (it - lo < 1e-9) lo + 1 else it }
        val n = values.size
        val path = Path()
        var pen = false
        values.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val p = Offset(i * size.width / (n - 1).coerceAtLeast(1), (3.dp.toPx() + (hi - v) / (hi - lo) * (size.height - 6.dp.toPx())).toFloat())
            if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            pen = true
        }
        clipRect(right = size.width * anim.value) {
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Deux anneaux concentriques (pas, sommeil) qui se remplissent à l'ouverture. */
@Composable
fun Rings(outer: Float, outerColor: Color, inner: Float, innerColor: Color, modifier: Modifier = Modifier, description: String) {
    val track = P.c.surfaceHigh
    val anim = rememberReveal(outer to inner, 1100)
    Canvas(modifier.size(150.dp).semantics { contentDescription = description }) {
        val stroke = 13.dp.toPx()
        listOf(Triple(size.minDimension / 2 - stroke / 2, outer, outerColor),
            Triple(size.minDimension / 2 - stroke / 2 - 18.dp.toPx(), inner, innerColor)).forEach { (r, p, col) ->
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(2 * r, 2 * r)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(stroke))
            val sweep = 360f * p.coerceIn(0f, 1f) * anim.value
            if (sweep > 0.5f) drawArc(col, -90f, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

// ---- hypnogramme ---------------------------------------------------------------------------------------

data class Seg(val stage: String, val start: Double, val dur: Double)

/** Rang de la phase : 0 éveil, 1 paradoxal, 2 léger, 3 profond (Samsung et Health Connect). */
fun stageRow(stage: String): Int = when (stage) {
    "awake", "awake_in_bed", "out_of_bed" -> 0
    "rem" -> 1
    "deep" -> 3
    else -> 2 // light, sleeping, unknown
}

@Composable
fun Hypnogram(segs: List<Seg>, span: Double, bedtime: String?, modifier: Modifier = Modifier) {
    val c = P.c
    val colors = listOf(c.awake, c.rem, c.light, c.deep)
    val anim = rememberReveal(segs, 800)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(64.dp).height(104.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("Éveil", "Paradoxal", "Léger", "Profond").forEach { Muted(it, 11.sp, maxLines = 1) }
            }
            Canvas(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(8.dp)).background(c.inset)
                .semantics { contentDescription = "Hypnogramme de la nuit" }) {
                val rowH = 20.dp.toPx()
                val step = (size.height - rowH) / 3
                clipRect(right = size.width * anim.value) {
                    segs.forEach { s ->
                        val row = stageRow(s.stage)
                        val x = (s.start / span * size.width).toFloat()
                        val w = (s.dur / span * size.width).toFloat().coerceAtLeast(1.5f)
                        drawRoundRect(colors[row], Offset(x, row * step), Size(w, rowH), CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                    }
                }
            }
        }
        val t0 = bedtime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (t0 != null && span > 0) Row(Modifier.fillMaxWidth().padding(start = 72.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0.0, 1 / 3.0, 2 / 3.0, 1.0).forEach { f -> Muted(t0.plusMinutes((span * f).roundToInt().toLong()).toString(), 11.sp) }
        }
    }
}
