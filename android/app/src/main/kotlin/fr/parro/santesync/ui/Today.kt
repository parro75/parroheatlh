package fr.parro.santesync.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import fr.parro.santesync.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.LocalDate

// ---- Aujourd'hui : tableau de bord (26/11/2026) ---------------------------------------------------------

data class TodayData(
    val today: JSONObject, val report: JSONObject, val weekly: JSONObject, val steps: JSONObject, val sleep: JSONObject,
    val heart: JSONObject, val recovery: JSONObject, val body: JSONObject, val stress: JSONObject,
)

enum class Detail { SLEEP, HEART, ACTIVITY, BODY, DIAGNOSTIC }

/** Nuit principale de chaque jour de réveil (la plus longue : une sieste ne la remplace pas). */
fun nightsByDate(sleep: JSONObject): Map<String, JSONObject> =
    sleep.getJSONArray("sessions").objects().groupBy { it.optString("date") }
        .mapValues { (_, l) -> l.maxBy { asleep(it) ?: 0.0 } }

fun asleep(n: JSONObject): Double? = n.dbl("asleep_min") ?: n.dbl("in_bed_min")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(refresh: Int, syncing: Boolean, onSync: () -> Unit, onLogin: () -> Unit, open: (Detail, String?) -> Unit) {
    val ctx = LocalContext.current
    val prefs = Prefs(ctx)
    val state by rememberLoad(refresh) {
        coroutineScope {
            val r14 = range(14)
            val today = async { Api.get(ctx, "today") }
            val report = async { Api.get(ctx, "report") }
            val weekly = async { Api.get(ctx, "weekly") }
            val steps = async { Api.get(ctx, "steps", r14) }
            val sleep = async { Api.get(ctx, "sleep", r14) }
            val heart = async { Api.get(ctx, "heart_rate", r14) }
            val recovery = async { Api.get(ctx, "recovery", range(30)) }
            val body = async { Api.get(ctx, "body", range(120)) }
            val stress = async { Api.get(ctx, "stress", r14) }
            TodayData(today.await(), report.await(), weekly.await(), steps.await(), sleep.await(), heart.await(),
                recovery.await(), body.await(), stress.await())
        }
    }
    PullToRefreshBox(isRefreshing = syncing, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
        Screen {
            PageTitle(fullDate(LocalDate.now()), "Aujourd'hui") { SyncChip(syncing, prefs, onSync) }
            Loaded(state, onLogin) { d -> TodayContent(d, prefs, open) }
        }
    }
}

@Composable
private fun SyncChip(syncing: Boolean, prefs: Prefs, onSync: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "sync").animateFloat(1f, 0.3f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.clip(RoundedCornerShape(22.dp)).border(1.dp, P.c.line, RoundedCornerShape(22.dp)).background(P.c.surface)
        .clickable(role = Role.Button, enabled = !syncing, onClick = onSync).heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(if (syncing) P.c.warn else P.c.good, modifier = Modifier.alpha(if (syncing) pulse else 1f))
        Text(if (syncing) "Synchro…" else "Synchro " + ago(prefs.lastSync), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = P.c.text)
    }
}

@Composable
private fun TodayContent(d: TodayData, prefs: Prefs, open: (Detail, String?) -> Unit) {
    val c = P.c
    val t = d.today
    val goals = t.optJSONObject("goals")
    val stepsGoal = goals?.dbl("steps") ?: prefs.stepsGoal.toDouble()
    val sleepGoal = goals?.dbl("sleep_min") ?: prefs.sleepGoalMin.toDouble()
    val stepsToday = t.dbl("steps_today") ?: 0.0
    val night = t.optJSONObject("last_night")
    val nightMin = night?.let(::asleep)

    // ---- anneaux ----
    ParroCard(radius = 28.dp, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Rings((stepsToday / stepsGoal).toFloat(), c.activity, ((nightMin ?: 0.0) / sleepGoal).toFloat(), c.sleep,
                description = "Pas : ${(100 * stepsToday / stepsGoal).toInt()} % de l'objectif ; sommeil : " +
                    "${(100 * (nightMin ?: 0.0) / sleepGoal).toInt()} % de l'objectif")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RingLegend("Pas", c.activity, int(stepsToday),
                    if (stepsToday >= stepsGoal) "objectif de ${int(stepsGoal)} atteint" else "sur ${int(stepsGoal)} · encore ${int(stepsGoal - stepsToday)}")
                RingLegend("Sommeil", c.sleep, nightMin?.let(::hm) ?: "—",
                    nightMin?.let { "objectif ${hm(sleepGoal)} · ${(100 * it / sleepGoal).toInt()} %" } ?: "pas encore de nuit")
            }
        }
    }

    ReportCard(d.report)
    if (d.weekly.str("narrative") != null) WeeklyCard(d.weekly)

    // ---- tuiles ----
    val nights = nightsByDate(d.sleep)
    val days7 = allDays(7)
    val days14 = allDays(14)
    val hrDays = d.heart.getJSONArray("days").objects().associateBy { it.optString("date") }
    val rhr14 = days14.map { hrDays[it]?.dbl("resting") }
    val rhrNow = t.optJSONObject("resting_hr")?.dbl("bpm")
    val rhrBase = rhr14.dropLast(1).filterNotNull().takeIf { it.size >= 3 }?.average()
    val hrvDays = d.recovery.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("hrv_ms") }
    val hrv = t.optJSONObject("hrv")
    val stepsDays = d.steps.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("steps") }
    val yesterday = LocalDate.now().minusDays(1).toString()
    val weighings = d.body.getJSONArray("days").objects().filter { it.dbl("weight_kg") != null }
    val lastW = weighings.lastOrNull()
    val refW = lastW?.let { l ->
        val ld = LocalDate.parse(l.optString("date"))
        weighings.lastOrNull { !LocalDate.parse(it.optString("date")).isAfter(ld.minusDays(28)) }
    }
    val stressDays = d.stress.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("avg") }
    val stress = t.optJSONObject("stress")

    TileRow({ m ->
        Tile("Nuit", ParroIcons.moon, c.sleep, nightMin?.let(::hm) ?: "—", null,
            night?.let { n -> listOfNotNull(n.dbl("score")?.let { "score ${int(it)}" }, "${n.str("bedtime") ?: "?"} → ${n.str("wake") ?: "?"}").joinToString(" · ") }
                ?: "pas encore de nuit",
            days7.map { nights[it]?.let(::asleep) }, { open(Detail.SLEEP, null) }, m)
    }, { m ->
        Tile("FC au repos", ParroIcons.heart, c.heart, rhrNow?.let(::int) ?: "—", "bpm",
            if (rhrNow != null && rhrBase != null) "${signed(rhrNow - rhrBase, 0)} vs ta moyenne 14 j" else "calculée la nuit",
            rhr14, { open(Detail.HEART, "rhr") }, m)
    })
    TileRow({ m ->
        Tile("HRV nocturne", ParroIcons.pulse, c.hrv, hrv?.dbl("value")?.let(::int) ?: "—", "ms",
            hrv?.str("date")?.let { "au ${shortDate(it)} · export Samsung" } ?: "pas de donnée",
            allDays(30).map { hrvDays[it] }, { open(Detail.HEART, "hrv") }, m)
    }, { m ->
        Tile("Pas", ParroIcons.bolt, c.activity, stepsDays[yesterday]?.let(::int) ?: "—", null,
            "hier · moy. 7 j : ${d.steps.dbl("daily_avg")?.let(::int) ?: "—"}",
            days14.map { stepsDays[it] }, { open(Detail.ACTIVITY, null) }, m)
    })
    TileRow({ m ->
        Tile("Poids", ParroIcons.scale, c.body, lastW?.dbl("weight_kg")?.let { dec(it) } ?: "—", "kg",
            when {
                lastW == null -> "pas de pesée récente"
                refW != null && refW !== lastW -> "${signed(lastW.dbl("weight_kg")!! - refW.dbl("weight_kg")!!, 1, " kg")} depuis le ${shortDate(refW.optString("date"))}"
                else -> "pesée du ${shortDate(lastW.optString("date"))}"
            },
            weighings.takeLast(12).map { it.dbl("weight_kg") }, { open(Detail.BODY, null) }, m)
    }, { m ->
        Tile("Stress", ParroIcons.gauge, c.stress, stress?.dbl("value")?.let(::int) ?: "—", "/ 100",
            stress?.str("date")?.let { "au ${shortDate(it)} · Samsung" } ?: "pas de donnée",
            days14.map { stressDays[it] }, { open(Detail.HEART, "stress") }, m)
    })

    WeekCard(stepsDays, nights, stepsGoal, sleepGoal)

    Muted("Synchro du téléphone : ${ago(prefs.lastSync)}" +
        (t.str("last_export_day")?.let { " · dernier export Samsung : ${shortDate(it)}" } ?: ""), 11.sp,
        Modifier.fillMaxWidth().padding(top = 2.dp), color = P.c.faint)
}

@Composable
private fun TileRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        a(Modifier.weight(1f)); b(Modifier.weight(1f))
    }
}

@Composable
private fun RingLegend(label: String, color: Color, value: String, sub: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(color); Muted(label)
        }
        Num(value, 24.sp)
        Muted(sub)
    }
}

/** Rapport du jour : texte rédigé par l'IA locale (Ollama), signaux calculés par règles sur le serveur. */
@Composable
fun ReportCard(report: JSONObject) {
    val flags = report.optJSONObject("facts")?.optJSONArray("flags")?.objects().orEmpty()
    var open by rememberSaveable { mutableStateOf(false) }
    val text = report.str("narrative")
    ParroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(P.c.sleepSoft), contentAlignment = Alignment.Center) {
                Icon(ParroIcons.doc, contentDescription = null, tint = P.c.sleep, modifier = Modifier.size(18.dp))
            }
            CardTitle("Rapport du matin", report.str("generated_at")?.let { "rédigé par l'IA locale · ${it.take(16).replace('T', ' ')} UTC" }
                ?: "rédigé par l'IA locale")
        }
        if (text == null) {
            Body(if (report.optJSONObject("facts")?.optBoolean("ready") == true)
                "Ta nuit est arrivée : le rapport est en cours de rédaction (quelques minutes)."
            else "Le rapport sera rédigé dès que ta nuit aura été synchronisée.")
        } else Body(text, maxLines = if (open) Int.MAX_VALUE else 3)
        if (open) flags.forEach { FlagLine(it.optString("level"), it.optString("text")) }
        if (text != null || flags.isNotEmpty()) Text(
            if (open) "Réduire" else if (flags.isNotEmpty()) "Lire la suite · ${flags.size} point${if (flags.size > 1) "s" else ""} à noter" else "Lire la suite",
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }.padding(vertical = 8.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.c.sleep)
        if (open) report.str("model")?.let { Muted("Rédigé par $it · signaux calculés par règles · ce n'est pas un avis médical", 11.sp) }
    }
}

@Composable
fun WeeklyCard(weekly: JSONObject) {
    val f = weekly.optJSONObject("facts")
    var open by rememberSaveable { mutableStateOf(false) }
    ParroCard {
        CardTitle("Rapport de la semaine", f?.let { "du ${shortDate(it.optString("start"))} au ${shortDate(it.optString("end"))}" })
        Body(weekly.optString("narrative"), maxLines = if (open) Int.MAX_VALUE else 3)
        if (open) f?.optJSONArray("flags")?.objects()?.forEach { FlagLine(it.optString("level"), it.optString("text")) }
        Text(if (open) "Réduire" else "Lire la suite",
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }.padding(vertical = 8.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.c.sleep)
    }
}

/** Semaine en cours (lundi → dimanche) : pas et sommeil rapportés à l'objectif. */
@Composable
private fun WeekCard(steps: Map<String, Double?>, nights: Map<String, JSONObject>, stepsGoal: Double, sleepGoal: Double) {
    val monday = startOfWeek()
    val today = LocalDate.now()
    ParroCard(radius = 22.dp, gap = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CardTitle("Cette semaine")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(P.c.activity); Muted("pas"); Dot(P.c.sleep); Muted("sommeil")
            }
        }
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { k ->
                val day = monday.plusDays(k.toLong())
                val iso = day.toString()
                val future = day.isAfter(today)
                val s = if (future) 0f else ((steps[iso] ?: 0.0) / stepsGoal).toFloat().coerceIn(0.04f, 1f)
                val n = if (future) 0f else ((nights[iso]?.let(::asleep) ?: 0.0) / sleepGoal).toFloat().coerceIn(0.04f, 1f)
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom) {
                    Row(Modifier.height(72.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Bar(s, P.c.activity, future); Bar(n, P.c.sleep, future)
                    }
                    Text(dayInitial(day), Modifier.padding(top = 4.dp), fontSize = 11.sp,
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (day == today) P.c.text else P.c.muted)
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, future: Boolean) {
    Box(Modifier.width(8.dp).fillMaxHeight(if (future) 0.06f else fraction).clip(RoundedCornerShape(4.dp))
        .background(if (future) P.c.surfaceHigh else color))
}
