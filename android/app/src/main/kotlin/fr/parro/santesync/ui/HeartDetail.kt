package fr.parro.santesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs

private val HEART_PERIODS = listOf("30" to "30 j", "90" to "3 mois", "365" to "1 an", "all" to "Tout")

private data class HeartMetric(val key: String, val label: String, val trend: String, val unit: String, val threshold: Double)

private val HEART_METRICS = listOf(
    HeartMetric("rhr", "Repos", "resting_hr", "bpm", 1.0),
    HeartMetric("hrv", "HRV", "hrv_nightly", "ms", 2.0),
    HeartMetric("stress", "Stress", "stress", "", 3.0),
    HeartMetric("day", "Journée", "heart_rate_avg", "bpm", 1.0),
)

private data class HeartData(val hr: JSONObject, val recovery: JSONObject, val stress: JSONObject, val spo2: JSONObject)

@Composable
fun HeartDetail(refresh: Int, onLogin: () -> Unit, onBack: () -> Unit, initial: String?) {
    val ctx = LocalContext.current
    val c = P.c
    var metric by rememberSaveable { mutableStateOf(initial ?: "rhr") }
    var period by rememberSaveable { mutableStateOf("30") }
    var sel by rememberSaveable(metric, period) { mutableStateOf<Int?>(null) }
    val m = HEART_METRICS.first { it.key == metric }
    val color = when (metric) { "hrv" -> c.hrv; "stress" -> c.stress; else -> c.heart }
    val soft = when (metric) { "hrv" -> c.hrvSoft; "stress" -> c.bodySoft; else -> c.heartSoft }
    val fmt: (Double) -> String = { v -> int(v) + if (m.unit.isEmpty()) "" else " ${m.unit}" }

    val rangeState by rememberLoad(refresh, period) {
        if (period == "all") null else coroutineScope {
            val r = range(period.toInt())
            val hr = async { Api.get(ctx, "heart_rate", r) }
            val rec = async { Api.get(ctx, "recovery", r) }
            val st = async { Api.get(ctx, "stress", r) }
            val sp = async { Api.get(ctx, "spo2", r) }
            HeartData(hr.await(), rec.await(), st.await(), sp.await())
        }
    }
    val allState by rememberLoad(refresh, period, metric) {
        if (period != "all") null else Api.get(ctx, "trends", mapOf("metric" to m.trend, "period" to "month",
            "start" to "2010-01-01", "end" to LocalDate.now().toString()))
    }

    Screen {
        DetailHeader("Cœur", onBack)
        Segmented(HEART_METRICS.map { it.key to it.label }, metric, { metric = it }, accent = soft)

        // ---- série affichée : (libellés, valeurs) ----
        val series: Pair<List<String>, List<Double?>>? = if (period == "all") {
            (allState as? Load.Ok)?.value?.let { t ->
                val rows = t.getJSONArray("rows").objects()
                rows.map { monthLabel(it.optString("period")) } to rows.map { it.dbl("avg") }
            }
        } else (rangeState as? Load.Ok)?.value?.let { d ->
            val dates = allDays(period.toInt())
            val byDate: Map<String, Double?> = when (metric) {
                "rhr" -> d.hr.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("resting") }
                "day" -> d.hr.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("avg") }
                "hrv" -> d.recovery.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("hrv_ms") }
                else -> d.stress.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("avg") }
            }
            dates.map(::longDate) to dates.map { byDate[it] }
        }

        val loading = if (period == "all") allState else rangeState
        if (series == null) Loaded(loading, onLogin) {}
        else {
            val (labels, values) = series
            val present = values.indices.filter { values[it] != null }
            val s = sel?.takeIf { it in present } ?: present.lastOrNull()
            val vs = values.filterNotNull()
            val avg = vs.takeIf { it.isNotEmpty() }?.average()
            Column {
                Muted(s?.let { (if (period == "all") "Moyenne de " else "") + labels[it] } ?: "Pas de mesure sur la période", 13.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num(s?.let { int(values[it]!!) } ?: "—", 52.sp, color, FontWeight.Bold)
                    if (m.unit.isNotEmpty()) Muted(m.unit, 16.sp)
                }
                if (s != null && avg != null) Muted("${signed(values[s]!! - avg, 1, if (m.unit.isEmpty()) "" else " ${m.unit}")} " +
                    "par rapport à ta moyenne sur la période", 13.sp)
            }
            ParroCard(gap = 12.dp) {
                Segmented(HEART_PERIODS, period, { period = it }, accent = soft)
                if (present.size < 2) Muted("Pas assez de mesures sur la période." +
                    if (metric == "rhr") " La FC au repos est calculée par l'app depuis août 2026." else "")
                else {
                    ScrubLine(values, color, fmt, s, { sel = it },
                        axis = listOf(labels.first(), labels[labels.size / 2], labels.last()),
                        description = "Courbe : ${m.label}")
                    Muted("Glisse le doigt sur la courbe pour lire chaque " + (if (period == "all") "mois." else "jour."), 11.sp)
                }
            }
            if (present.size >= 10) Insight(values.filterNotNull(), m, color)
            if (metric == "hrv") Muted("Source : exports Samsung Health (à jour au dernier export).", 11.sp)
        }

        // ---- repères complémentaires ----
        (rangeState as? Load.Ok)?.value?.let { d ->
            val hrDays = d.hr.getJSONArray("days").objects()
            val sleeping = hrDays.lastOrNull { it.dbl("sleeping") != null }?.dbl("sleeping")
            val dayAvg = hrDays.lastOrNull { it.dbl("avg") != null }?.dbl("avg")
            val spo2 = d.spo2.getJSONArray("days").objects().lastOrNull()
            val stress = d.stress.getJSONArray("days").objects().mapNotNull { it.dbl("avg") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Pendant le sommeil", sleeping?.let { "${int(it)} bpm" } ?: "—", "dernière nuit mesurée", Modifier.weight(1f))
                StatBox("Moyenne de la journée", dayAvg?.let { "${int(it)} bpm" } ?: "—", "dernier jour mesuré", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("SpO2 nocturne", spo2?.dbl("avg")?.let { "${dec(it, 0)} %" } ?: "—",
                    spo2?.dbl("min")?.let { "minimum ${dec(it, 0)} % · ${shortDate(spo2.optString("date"))}" }, Modifier.weight(1f))
                StatBox("Stress moyen", stress.takeIf { it.isNotEmpty() }?.average()?.let(::int) ?: "—", "sur 100, sur la période",
                    Modifier.weight(1f))
            }
        }
    }
}

/** Tendance de la période, calculée par règle (début vs fin) : jamais interprétée. */
@Composable
private fun Insight(values: List<Double>, m: HeartMetric, color: Color) {
    val first = values.take(7).average()
    val last = values.takeLast(7).average()
    val d = last - first
    val unit = if (m.unit.isEmpty()) " points" else " ${m.unit}"
    val what = when (m.key) { "rhr" -> "Ta FC au repos"; "hrv" -> "Ta HRV nocturne"; "stress" -> "Ton stress moyen"; else -> "Ta FC moyenne" }
    val text = if (abs(d) < m.threshold) "$what est stable sur la période (écart de ${dec(abs(d))}$unit entre le début et la fin)."
    else "$what est en ${if (d < 0) "baisse" else "hausse"} : ${dec(abs(d))}$unit de ${if (d < 0) "moins" else "plus"} " +
        "à la fin de la période qu'au début (moyennes des 7 premières et 7 dernières mesures)."
    ParroCard(radius = 20.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(color, modifier = Modifier.padding(top = 6.dp))
            Body(text)
        }
    }
}
