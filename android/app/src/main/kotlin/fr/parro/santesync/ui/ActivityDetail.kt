package fr.parro.santesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import fr.parro.santesync.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.LocalDate

private val ACT_PERIODS = listOf("7" to "7 j", "30" to "30 j", "90" to "3 mois", "365" to "1 an", "all" to "Tout")

/** Noms des séances (codes Health Connect côté serveur) en français. */
val WORKOUT_FR = mapOf(
    "other_workout" to "Séance", "walking" to "Marche", "running" to "Course", "running_treadmill" to "Course sur tapis",
    "biking" to "Vélo", "biking_stationary" to "Vélo d'appartement", "hiking" to "Randonnée", "swimming_pool" to "Natation",
    "swimming_open_water" to "Nage en eau libre", "strength_training" to "Musculation", "weightlifting" to "Haltérophilie",
    "elliptical" to "Elliptique", "rowing_machine" to "Rameur", "rowing" to "Aviron", "yoga" to "Yoga", "pilates" to "Pilates",
    "stretching" to "Étirements", "high_intensity_interval_training" to "Fractionné (HIIT)", "dancing" to "Danse",
    "skiing" to "Ski", "snowshoeing" to "Raquettes", "skating" to "Patin", "ice_skating" to "Patin à glace",
    "tennis" to "Tennis", "badminton" to "Badminton", "golf" to "Golf", "soccer" to "Football", "calisthenics" to "Gymnastique",
    "stair_climbing" to "Escaliers", "stair_climbing_machine" to "Simulateur d'escaliers", "exercise_class" to "Cours collectif",
    "martial_arts" to "Arts martiaux", "boxing" to "Boxe", "rock_climbing" to "Escalade",
)

fun workoutName(type: String?): String = type?.let { WORKOUT_FR[it] ?: it.replace('_', ' ').replaceFirstChar(Char::titlecase) } ?: "Séance"

@Composable
fun ActivityDetail(refresh: Int, onLogin: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    val goal = Prefs(ctx).stepsGoal.toDouble()
    var period by rememberSaveable { mutableStateOf("30") }
    var sel by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    val state by rememberLoad(refresh, period) {
        coroutineScope {
            if (period == "all") {
                val q = { metric: String -> mapOf("metric" to metric, "period" to "month", "start" to "2010-01-01", "end" to LocalDate.now().toString()) }
                val st = async { Api.get(ctx, "trends", q("steps")) }
                val wk = async { Api.get(ctx, "trends", q("workout_minutes")) }
                st.await() to wk.await()
            } else {
                val r = range(period.toInt())
                val st = async { Api.get(ctx, "steps", r) }
                val wk = async { Api.get(ctx, "workouts", r) }
                st.await() to wk.await()
            }
        }
    }
    Screen {
        DetailHeader("Activité", onBack)
        ParroCard(gap = 12.dp) {
            Segmented(ACT_PERIODS, period, { period = it }, accent = c.activitySoft)
            Loaded(state, onLogin) { (steps, workouts) ->
                val pts = if (period == "all") steps.getJSONArray("rows").objects().map { r ->
                    val k = r.optString("period")
                    BarPoint(k, "Moyenne de ${monthLabel(k)}", if (k.endsWith("-01")) "'" + k.substring(2, 4) else "", r.dbl("avg"))
                } else {
                    val byDay = steps.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("steps") }
                    val dates = allDays(period.toInt())
                    val ticks = dayTicks(dates)
                    dates.mapIndexed { i, d -> BarPoint(d, longDate(d), ticks[i], byDay[d]) }
                }
                val present = pts.indices.filter { pts[it].value != null }
                val s = sel?.takeIf { it in present } ?: present.lastOrNull()
                val vals = pts.mapNotNull { it.value }
                if (s == null) Muted("Pas de pas enregistrés sur la période.")
                else {
                    ChartCaption(pts[s].label, "${int(pts[s].value!!)} pas", int(vals.average()), color = c.activity)
                    InteractiveBars(pts, c.activity, s, { sel = it }, goal = goal, goalLabel = "objectif ${int(goal)}",
                        description = "Pas par jour")
                    if (period != "all") Muted("Objectif atteint ${vals.count { it >= goal }} jour${if (vals.count { it >= goal } > 1) "s" else ""} " +
                        "sur ${vals.size}.", 12.sp)
                }
                if (period == "all") {
                    val rows = workouts.getJSONArray("rows").objects()
                    if (rows.isNotEmpty()) {
                        var selW by rememberSaveable { mutableStateOf<Int?>(null) }
                        val wp = rows.map { r -> val k = r.optString("period")
                            BarPoint(k, monthLabel(k), if (k.endsWith("-01")) "'" + k.substring(2, 4) else "", r.dbl("total")) }
                        val ws = selW ?: (wp.size - 1)
                        Divider()
                        ChartCaption("Sport en ${wp[ws].label}", "${int(wp[ws].value ?: 0.0)} min", null, color = c.score)
                        InteractiveBars(wp, c.score, ws, { selW = it }, height = 120.dp, description = "Minutes de sport par mois")
                    }
                }
            }
        }
        if (period != "all") Loaded(state, onLogin) { (_, workouts) ->
            val list = workouts.getJSONArray("workouts").objects().reversed()
            CardTitle("Séances (${list.size})")
            if (list.isEmpty()) Muted("Aucune séance sur la période.")
            list.take(60).forEach { w -> WorkoutCard(w) }
        }
    }
}

@Composable
private fun WorkoutCard(w: JSONObject) {
    val c = P.c
    ParroCard(radius = 20.dp, padding = 14.dp, gap = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.activitySoft), contentAlignment = Alignment.Center) {
                Icon(ParroIcons.bolt, contentDescription = null, tint = c.activity, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(workoutName(w.str("type")), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Muted("${longDate(w.optString("date"))} · ${w.str("start") ?: ""}")
            }
            w.dbl("duration_min")?.let { Num("${int(it)} min", 16.sp, c.activity) }
        }
        val details = listOfNotNull(
            w.dbl("distance_km")?.let { "${dec(it, 2)} km" },
            w.dbl("calories_kcal")?.let { "${int(it)} kcal" },
            w.dbl("avg_hr")?.let { "FC moy. ${int(it)}" },
            w.dbl("max_hr")?.let { "max ${int(it)}" },
        )
        if (details.isNotEmpty()) Muted(details.joinToString(" · "), modifier = Modifier.padding(start = 50.dp))
    }
}

