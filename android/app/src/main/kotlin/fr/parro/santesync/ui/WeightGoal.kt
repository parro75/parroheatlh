package fr.parro.santesync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ---- Objectif de poids (réglé dans Profil) : calculs faits par le serveur, affichés ici ----------------------

/** Libellé court de l'état par rapport à la trajectoire. */
fun goalStatus(g: JSONObject): String {
    val gap = g.dbl("gap_kg") ?: return g.optString("status")
    return when (g.optString("status")) {
        "en avance" -> "en avance de ${dec(kotlin.math.abs(gap))} kg"
        "en retard" -> "en retard de ${dec(kotlin.math.abs(gap))} kg"
        else -> g.optString("status")
    }
}

@Composable
fun WeightGoalCard(goal: JSONObject, refresh: Int) {
    val ctx = LocalContext.current
    val c = P.c
    val start = goal.optJSONObject("start")
    val since = start?.optString("date")?.takeIf { it.isNotEmpty() } ?: goal.optString("since")
    val weighings by rememberLoad(refresh, since) {
        Api.get(ctx, "body", mapOf("start" to since, "end" to LocalDate.now().toString())).getJSONArray("days").objects()
            .mapNotNull { d -> d.dbl("weight_kg")?.let { LocalDate.parse(d.optString("date")) to it } }
    }
    val low = goal.optDouble("low_kg")
    val high = goal.optDouble("high_kg")
    val target = goal.optDouble("target_kg")
    ParroCard(gap = 10.dp) {
        CardTitle("Objectif", "${int(low)} à ${int(high)} kg le ${longDate(goal.optString("date"))}")
        if (start == null) {
            Muted("La trajectoire commencera à ta prochaine pesée.")
            return@ParroCard
        }
        val ok = goal.optString("status") in setOf("dans les temps", "en avance", "dans la plage")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(goalStatus(goal), if (ok) c.activitySoft else c.bodySoft, c.text)
            goal.dbl("expected_today_kg")?.let { Muted("attendu aujourd'hui : ${dec(it)} kg", 13.sp) }
        }
        val s = weighings
        if (s is Load.Ok && s.value.isNotEmpty()) {
            GoalChart(s.value, LocalDate.parse(start.optString("date")), start.optDouble("kg"),
                LocalDate.parse(goal.optString("date")), target, low, high)
        }
        val toGo = goal.dbl("to_go_kg")
        val rate = goal.dbl("rate_kg_per_week")
        Muted(listOfNotNull(
            "Départ ${dec(start.optDouble("kg"))} kg le ${shortDate(start.optString("date"))}",
            toGo?.let { "reste ${dec(kotlin.math.abs(it))} kg jusqu'à ${dec(target)} kg" },
            rate?.let { "${dec(kotlin.math.abs(it), 2)} kg/semaine sur ${dec(goal.optDouble("weeks_left"), 0)} semaines" },
        ).joinToString(" · "), 13.sp)
        goal.dbl("kcal_target")?.let { t ->
            Muted("Cible de calories : ${int(t)} kcal/jour (dépense moyenne de la montre ${int(goal.optDouble("burned_avg_kcal"))} " +
                "− ${int(goal.optDouble("deficit_kcal"))}). Repère général, pas un avis médical.", 12.sp)
        }
    }
}

/** Courbe dans le temps, du départ à la date visée : plage visée, trajectoire en pointillés, pesées. */
@Composable
private fun GoalChart(points: List<Pair<LocalDate, Double>>, start: LocalDate, startKg: Double, end: LocalDate,
                      target: Double, low: Double, high: Double) {
    val c = P.c
    val measurer = rememberTextMeasurer()
    val span = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1).toFloat()
    val all = points.map { it.second } + listOf(startKg, low, high)
    val lo = (all.min() - 1.5)
    val hi = (all.max() + 1.5)
    val desc = "Trajectoire de ${dec(startKg)} à ${dec(target)} kg, ${points.size} pesées depuis le ${shortDate(start.toString())}"
    Column {
        Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = desc }) {
            val padY = 8.dp.toPx()
            fun x(d: LocalDate) = (ChronoUnit.DAYS.between(start, d) / span).coerceIn(0f, 1f) * size.width
            fun y(v: Double) = (padY + (hi - v) / (hi - lo) * (size.height - 2 * padY)).toFloat()
            // plage visée
            drawRect(c.activitySoft, Offset(0f, y(high)), Size(size.width, y(low) - y(high)))
            val band = measurer.measure("${int(low)}–${int(high)} kg", TextStyle(fontSize = 10.sp, color = c.muted))
            drawText(band, topLeft = Offset(4.dp.toPx(), y(low) - band.size.height - 2.dp.toPx()))
            // trajectoire
            drawLine(c.faint, Offset(x(start), y(startKg)), Offset(x(end), y(target)), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            // aujourd'hui
            val tx = x(LocalDate.now())
            drawLine(c.line, Offset(tx, 0f), Offset(tx, size.height), strokeWidth = 1.dp.toPx())
            // pesées
            val path = Path()
            points.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
            if (points.size > 1) drawPath(path, c.body, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            points.forEach { (d, v) -> drawCircle(c.body, 3.dp.toPx(), Offset(x(d), y(v))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("départ ${shortDate(start.toString())}", 11.sp)
            Muted("trait vertical : aujourd'hui", 11.sp)
            Muted(shortDate(end.toString()), 11.sp)
        }
    }
}
