package fr.parro.santesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

// ---- Tendances : le long terme (12 mois en mois, 5 ans et tout l'historique en années) ------------------

private data class TrendMetric(val key: String, val label: String, val fmt: (Double) -> String, val total: Boolean = false)

private val TREND_METRICS = listOf(
    TrendMetric("sleep_minutes", "Sommeil", ::hm),
    TrendMetric("resting_hr", "FC au repos", { "${int(it)} bpm" }),
    TrendMetric("hrv_nightly", "HRV", { "${int(it)} ms" }),
    TrendMetric("steps", "Pas", { int(it) }),
    TrendMetric("weight", "Poids", { "${dec(it)} kg" }),
    TrendMetric("body_fat", "Masse grasse", { "${dec(it)} %" }),
    TrendMetric("workout_minutes", "Sport", { "${int(it)} min" }, total = true),
    TrendMetric("sleep_score", "Score sommeil", { int(it) }),
    TrendMetric("sleeping_hr", "FC sommeil", { "${int(it)} bpm" }),
    TrendMetric("stress", "Stress", { int(it) }),
)

@Composable
private fun metricColor(key: String): Color = when (key) {
    "sleep_minutes" -> P.c.sleep; "sleep_score" -> P.c.score; "resting_hr", "sleeping_hr" -> P.c.heart
    "hrv_nightly" -> P.c.hrv; "steps" -> P.c.activity; "workout_minutes" -> P.c.score
    "weight" -> P.c.body; "body_fat" -> P.c.stress; else -> P.c.stress
}

@Composable
fun TrendsScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var metric by rememberSaveable { mutableStateOf("sleep_minutes") }
    var span by rememberSaveable { mutableStateOf("12") }
    var sel by rememberSaveable(metric, span) { mutableStateOf<Int?>(null) }
    val m = TREND_METRICS.first { it.key == metric }
    val color = metricColor(metric)
    val state by rememberLoad(refresh, metric) {
        coroutineScope {
            val q = { p: String -> mapOf("metric" to metric, "period" to p, "start" to "2010-01-01", "end" to LocalDate.now().toString()) }
            val month = async { Api.get(ctx, "trends", q("month")) }
            val year = async { Api.get(ctx, "trends", q("year")) }
            month.await() to year.await()
        }
    }
    Screen {
        PageTitle("Sur le long terme", "Tendances")
        ChipRow(TREND_METRICS.map { Triple(it.key, it.label, metricColor(it.key)) }, metric) { metric = it }
        Loaded(state, onLogin) { (monthT, yearT) ->
            val val_: (org.json.JSONObject) -> Double? = { r -> if (m.total) r.dbl("total") else r.dbl("avg") }
            val months = monthT.getJSONArray("rows").objects()
            val years = yearT.getJSONArray("rows").objects()
            val now = LocalDate.now()
            val pts = when (span) {
                "12" -> {
                    val from = now.minusMonths(11).toString().take(7)
                    months.filter { it.optString("period") >= from }.map { r ->
                        val k = r.optString("period")
                        BarPoint(k, (if (m.total) "Total de " else "Moyenne de ") + monthLabel(k),
                            MONTHS_SHORT[k.substring(5, 7).toInt() - 1].take(1).uppercase(), val_(r))
                    }
                }
                else -> years.filter { span == "all" || it.optString("period").toInt() >= now.year - 4 }.map { r ->
                    val k = r.optString("period")
                    BarPoint(k, (if (m.total) "Total de " else "Moyenne de ") + k, "'" + k.takeLast(2), val_(r))
                }
            }
            val s = sel?.takeIf { it < pts.size && pts[it].value != null } ?: pts.indexOfLast { it.value != null }
            val vals = pts.mapNotNull { it.value }
            ParroCard(gap = 12.dp) {
                Segmented(listOf("12" to "12 mois", "5" to "5 ans", "all" to "Tout"), span, { span = it })
                if (s < 0) Muted("Pas de donnée pour cette mesure.")
                else {
                    ChartCaption(pts[s].label, m.fmt(pts[s].value!!), null, color = color)
                    InteractiveBars(pts, color, s, { sel = it }, height = 180.dp,
                        floor = if (metric in setOf("weight", "body_fat", "resting_hr", "sleeping_hr", "sleep_score", "sleep_minutes", "hrv_nightly"))
                            (vals.min() * 0.85) else 0.0, description = "Tendance : ${m.label}")
                    val first = (if (span == "12") months else years).firstOrNull { val_(it) != null }?.optString("period")
                    Muted((if (span == "12") "Une barre = un mois" else "Une barre = une année") +
                        (if (m.total) " (total)." else " (moyenne des jours mesurés).") +
                        (first?.let { " Données depuis ${if (it.length == 7) monthLabel(it) else it}." } ?: ""), 11.sp)
                }
            }
            if (vals.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Moyenne", m.fmt(vals.average()), modifier = Modifier.weight(1f))
                StatBox("Plus haut", m.fmt(vals.max()), modifier = Modifier.weight(1f))
                StatBox("Plus bas", m.fmt(vals.min()), modifier = Modifier.weight(1f))
            }
            // ---- même mois, d'une année à l'autre ----
            val mm = "%02d".format(now.monthValue)
            val yoy = months.filter { it.optString("period").endsWith("-$mm") && val_(it) != null }.takeLast(5)
            if (yoy.size >= 2 && yoy.maxOf { val_(it)!! } > 0) ParroCard(radius = 22.dp) {
                CardTitle("${MONTHS_SHORT[now.monthValue - 1].replaceFirstChar(Char::titlecase)} d'une année à l'autre",
                    if (now.dayOfMonth < 28) "le mois en cours n'est pas terminé" else null)
                val top = yoy.maxOf { val_(it)!! }
                yoy.forEach { r ->
                    val v = val_(r)!!
                    val current = r.optString("period").startsWith(now.year.toString())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Num(r.optString("period").take(4), 13.sp, P.c.muted, modifier = Modifier.width(40.dp))
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(P.c.inset)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((v / top).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(5.dp))
                                .background(if (current) color else color.copy(alpha = 0.45f)))
                        }
                        Num(m.fmt(v), 13.sp, modifier = Modifier.width(84.dp))
                    }
                }
            }
        }
    }
}

