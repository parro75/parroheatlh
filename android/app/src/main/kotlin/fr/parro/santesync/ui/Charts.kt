package fr.parro.santesync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CHART_HEIGHT = 170.dp

/** Ligne au-dessus du graphique : valeur de la barre touchée, ou rien. */
@Composable
private fun Caption(text: String?) {
    Text(text ?: " ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Barres simples ; toucher une barre affiche son libellé et sa valeur. */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Double?>,
    color: Color,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    reference: Double? = null,
) {
    var selected by remember(labels) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = (values.filterNotNull() + listOfNotNull(reference)).maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Caption(selected?.let { i -> "${labels[i]} : ${values[i]?.let(format) ?: "—"}" })
        Canvas(
            Modifier.fillMaxWidth().height(CHART_HEIGHT).pointerInput(labels) {
                detectTapGestures { pos ->
                    if (labels.isNotEmpty()) selected = (pos.x / (size.width / labels.size)).toInt().coerceIn(0, labels.size - 1)
                }
            }
        ) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bottom = size.height - 18.dp.toPx()
            drawLine(axis, Offset(0f, bottom), Offset(size.width, bottom))
            reference?.let {
                val y = bottom - (it / max * (bottom - 4.dp.toPx())).toFloat()
                drawLine(axis, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0) return@forEachIndexed
                val h = (v / max * (bottom - 4.dp.toPx())).toFloat()
                val w = (slot * 0.7f).coerceAtLeast(2f)
                drawRoundRect(
                    color = if (selected == null || selected == i) color else color.copy(alpha = 0.45f),
                    topLeft = Offset(i * slot + (slot - w) / 2, bottom - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            // premier et dernier libellés
            if (labels.isNotEmpty()) {
                val style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor)
                drawText(measurer, labels.first(), Offset(0f, bottom + 3.dp.toPx()), style)
                val last = measurer.measure(labels.last(), style)
                drawText(last, topLeft = Offset(size.width - last.size.width, bottom + 3.dp.toPx()))
            }
            drawText(measurer, format(max), Offset(0f, 0f),
                androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor))
        }
    }
}

/** Barres empilées (ex. phases de sommeil), en heures. */
@Composable
fun StackedBarChart(
    labels: List<String>,
    stacks: List<List<Double>>,
    colors: List<Color>,
    names: List<String>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    var selected by remember(labels) { mutableStateOf<Int?>(null) }
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = stacks.maxOfOrNull { it.sum() }?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Caption(selected?.let { i ->
            labels[i] + " : " + format(stacks[i].sum()) + " (" +
                stacks[i].mapIndexedNotNull { k, v -> if (v > 0) "${names[k]} ${format(v)}" else null }.joinToString(", ") + ")"
        })
        Canvas(
            Modifier.fillMaxWidth().height(CHART_HEIGHT).pointerInput(labels) {
                detectTapGestures { pos ->
                    if (labels.isNotEmpty()) selected = (pos.x / (size.width / labels.size)).toInt().coerceIn(0, labels.size - 1)
                }
            }
        ) {
            val n = stacks.size.coerceAtLeast(1)
            val slot = size.width / n
            val bottom = size.height - 4.dp.toPx()
            drawLine(axis, Offset(0f, bottom), Offset(size.width, bottom))
            stacks.forEachIndexed { i, parts ->
                var y = bottom
                val w = (slot * 0.7f).coerceAtLeast(2f)
                parts.forEachIndexed { k, v ->
                    if (v <= 0) return@forEachIndexed
                    val h = (v / max * (bottom - 4.dp.toPx())).toFloat()
                    drawRect(
                        color = colors[k].copy(alpha = if (selected == null || selected == i) 1f else 0.45f),
                        topLeft = Offset(i * slot + (slot - w) / 2, y - h), size = Size(w, h),
                    )
                    y -= h
                }
            }
        }
        Legend(names, colors)
    }
}

/** Courbes (une ou plusieurs séries), trous laissés là où il n'y a pas de donnée. */
@Composable
fun LineChart(
    labels: List<String>,
    series: List<Triple<String, List<Double?>, Color>>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    var selected by remember(labels) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val all = series.flatMap { it.second }.filterNotNull()
    val lo = (all.minOrNull() ?: 0.0).let { it - (all.maxOrNull()!! - it).coerceAtLeast(1.0) * 0.1 }.takeIf { all.isNotEmpty() } ?: 0.0
    val hi = (all.maxOrNull() ?: 1.0).let { it + (it - lo).coerceAtLeast(1.0) * 0.05 }
    Column(modifier) {
        Caption(selected?.let { i ->
            labels[i] + " : " + series.joinToString(" · ") { (name, v, _) -> "$name ${v[i]?.let(format) ?: "—"}" }
        })
        Canvas(
            Modifier.fillMaxWidth().height(CHART_HEIGHT).pointerInput(labels) {
                detectTapGestures { pos ->
                    if (labels.size > 1) selected = Math.round(pos.x / size.width * (labels.size - 1)).coerceIn(0, labels.size - 1)
                }
            }
        ) {
            val bottom = size.height - 18.dp.toPx()
            val top = 4.dp.toPx()
            fun x(i: Int) = if (labels.size <= 1) size.width / 2 else i * size.width / (labels.size - 1)
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
            drawLine(axis, Offset(0f, bottom), Offset(size.width, bottom))
            series.forEach { (_, values, color) ->
                val path = Path()
                var pen = false
                values.forEachIndexed { i, v ->
                    if (v == null) { pen = false; return@forEachIndexed }
                    if (pen) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                    pen = true
                    drawCircle(color, radius = if (selected == i) 5.dp.toPx() else 2.dp.toPx(), center = Offset(x(i), y(v)))
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            selected?.let { drawLine(axis, Offset(x(it), top), Offset(x(it), bottom)) }
            val style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor)
            if (all.isNotEmpty()) {
                drawText(measurer, format(hi), Offset(0f, 0f), style)
                drawText(measurer, format(lo), Offset(0f, bottom - 14.dp.toPx()), style)
            }
            if (labels.isNotEmpty()) {
                drawText(measurer, labels.first(), Offset(0f, bottom + 3.dp.toPx()), style)
                val last = measurer.measure(labels.last(), style)
                drawText(last, topLeft = Offset(size.width - last.size.width, bottom + 3.dp.toPx()))
            }
        }
        if (series.size > 1) Legend(series.map { it.first }, series.map { it.third })
    }
}

@Composable
fun Legend(names: List<String>, colors: List<Color>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, name ->
            Spacer(Modifier.size(8.dp).background(colors[i], CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(name, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(12.dp))
        }
    }
}
