package fr.parro.santesync.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import fr.parro.santesync.Api
import java.time.LocalDate

// ---- « Tout » : tout l'historique en moyennes MENSUELLES (25/11/2026) -------------------------------
// Jour par jour, onze ans seraient illisibles (et lourds) : une barre par mois, depuis la première donnée.

data class AllTimeMetric(val metric: String, val title: String, val fmt: (Double) -> String, val color: Color,
                         val total: Boolean = false)

val SLEEP_ALL = listOf(
    AllTimeMetric("sleep_minutes", "Sommeil par nuit", ::hm, SeriesColors.deep),
    AllTimeMetric("sleep_score", "Score de sommeil", ::int, SeriesColors.score),
)
val HEART_ALL = listOf(
    AllTimeMetric("resting_hr", "FC au repos", { "${int(it)} bpm" }, SeriesColors.heart),
    AllTimeMetric("heart_rate_avg", "FC moyenne du jour", { "${int(it)} bpm" }, SeriesColors.heartSoft),
    AllTimeMetric("sleeping_hr", "FC pendant le sommeil", { "${int(it)} bpm" }, SeriesColors.heart),
    AllTimeMetric("hrv_nightly", "HRV nocturne", { "${int(it)} ms" }, SeriesColors.hrv),
    AllTimeMetric("stress", "Stress", ::int, SeriesColors.stress),
)
val ACTIVITY_ALL = listOf(
    AllTimeMetric("steps", "Pas par jour", ::int, SeriesColors.steps),
    AllTimeMetric("workout_minutes", "Minutes de sport par mois", { "${int(it)} min" }, SeriesColors.score, total = true),
)

private fun monthLabel(key: String): String = if (key.length == 7) key.substring(5, 7) + "/" + key.substring(2, 4) else key

@Composable
fun AllTime(refresh: Int, onLogin: () -> Unit, metrics: List<AllTimeMetric>) {
    val ctx = LocalContext.current
    val state by rememberLoad(refresh, metrics) {
        val end = LocalDate.now().toString()
        metrics.map { m ->
            Api.get(ctx, "trends", mapOf("metric" to m.metric, "period" to "month", "start" to "2010-01-01", "end" to end))
        }
    }
    Loaded(state, onLogin) { results ->
        results.zip(metrics).forEach { (t, m) ->
            val rows = t.getJSONArray("rows").objects()
            if (rows.isEmpty()) return@forEach
            Section("${m.title} — depuis ${monthLabel(rows.first().optString("period"))}") {
                BarChart(rows.map { monthLabel(it.optString("period")) },
                    rows.map { if (m.total) it.dbl("total") else it.dbl("avg") }, m.color, m.fmt)
                Text(if (m.total) "Total de chaque mois." else "Moyenne des valeurs quotidiennes de chaque mois.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
