package fr.parro.santesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import fr.parro.santesync.Prefs
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val SLEEP_PERIODS = listOf("7" to "7 nuits", "30" to "30", "90" to "3 mois", "365" to "1 an", "all" to "Tout")

/** Écart type « circulaire simple » autour de minuit : 00:30 compte comme 24:30 pour les couchers. */
private fun clockStats(times: List<String>, aroundMidnight: Boolean): Pair<Double, Double>? {
    val m = times.mapNotNull { t ->
        runCatching { val (h, mm) = t.split(":").map(String::toInt); h * 60 + mm + if (aroundMidnight && h < 12) 1440 else 0 }.getOrNull()
    }
    if (m.size < 2) return null
    val avg = m.average()
    return avg to sqrt(m.sumOf { (it - avg) * (it - avg) } / m.size)
}

private fun clock(min: Double): String {
    val m = min.roundToInt() % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

@Composable
fun SleepDetail(refresh: Int, onLogin: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    val goal = Prefs(ctx).sleepGoalMin.toDouble()
    var period by rememberSaveable { mutableStateOf("30") }
    var selDate by rememberSaveable { mutableStateOf<String?>(null) }
    var selMonth by rememberSaveable { mutableStateOf<Int?>(null) }
    val nightState by rememberLoad(refresh, selDate) {
        Api.get(ctx, "night", selDate?.let { mapOf("date" to it) } ?: emptyMap())
    }
    val rangeState by rememberLoad(refresh, period) {
        if (period == "all") Api.get(ctx, "trends", mapOf("metric" to "sleep_minutes", "period" to "month",
            "start" to "2010-01-01", "end" to LocalDate.now().toString()))
        else Api.get(ctx, "sleep", range(period.toInt()))
    }
    Screen {
        DetailHeader("Sommeil", onBack)
        Loaded(nightState, onLogin) { res -> NightCard(res.optJSONObject("night"), res.optString("date")) }

        ParroCard(gap = 12.dp) {
            Segmented(SLEEP_PERIODS, period, { period = it; selMonth = null })
            Loaded(rangeState, onLogin) { data ->
                if (period == "all") {
                    val rows = data.getJSONArray("rows").objects()
                    val pts = rows.map { r ->
                        val k = r.optString("period")
                        BarPoint(k, monthLabel(k), if (k.endsWith("-01")) "'" + k.substring(2, 4) else "", r.dbl("avg"))
                    }
                    val sel = selMonth?.takeIf { it < pts.size } ?: (pts.size - 1)
                    if (pts.isEmpty()) Muted("Pas de nuit enregistrée.")
                    else {
                        ChartCaption("Moyenne de ${pts[sel].label}", pts[sel].value?.let(::hm) ?: "—",
                            hm(pts.mapNotNull { it.value }.average()), color = c.sleep)
                        InteractiveBars(pts, c.sleep, sel, { selMonth = it }, goal = goal, goalLabel = "objectif ${hm(goal)}",
                            floor = 240.0, description = "Sommeil moyen par mois")
                        Muted("Une barre = la moyenne d'un mois, depuis ${monthLabel(pts.first().key)}.", 11.sp)
                    }
                } else {
                    val nights = nightsByDate(data)
                    val dates = allDays(period.toInt())
                    val ticks = dayTicks(dates)
                    val pts = dates.mapIndexed { i, d -> BarPoint(d, longDate(d), ticks[i], nights[d]?.let(::asleep)) }
                    val sel = dates.indexOf(selDate ?: "").takeIf { it >= 0 } ?: dates.indexOfLast { nights[it] != null }
                    val vals = pts.mapNotNull { it.value }
                    if (sel >= 0) ChartCaption("Nuit du ${longDate(dates[sel])}", pts[sel].value?.let(::hm) ?: "—",
                        vals.takeIf { it.isNotEmpty() }?.average()?.let(::hm), color = c.sleep)
                    InteractiveBars(pts, c.sleep, sel.takeIf { it >= 0 }, { i -> if (pts[i].value != null) selDate = dates[i] },
                        goal = goal, goalLabel = "objectif ${hm(goal)}", floor = 180.0, description = "Durée de sommeil par nuit")
                    Muted("Touche une barre pour afficher les phases de cette nuit.", 11.sp)

                    val ns = nights.values.toList()
                    val bed = clockStats(ns.mapNotNull { it.str("bedtime") }, true)
                    val wake = clockStats(ns.mapNotNull { it.str("wake") }, false)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox("Coucher moyen", bed?.let { clock(it.first) } ?: "—", bed?.let { "± ${it.second.roundToInt()} min" },
                            Modifier.weight(1f), bg = c.inset)
                        StatBox("Lever moyen", wake?.let { clock(it.first) } ?: "—", wake?.let { "± ${it.second.roundToInt()} min" },
                            Modifier.weight(1f), bg = c.inset)
                        StatBox("Objectif", "${vals.count { it >= goal }} / ${vals.size}", "nuits atteintes",
                            Modifier.weight(1f), bg = c.inset)
                    }
                }
            }
        }

        if (period != "all") Loaded(rangeState, onLogin) { data ->
            val nights = nightsByDate(data)
            val dates = allDays(period.toInt())
            val scores = dates.map { nights[it]?.dbl("score") }
            if (scores.count { it != null } >= 2) {
                var selScore by rememberSaveable(period) { mutableStateOf<Int?>(null) }
                val s = selScore ?: scores.indexOfLast { it != null }
                ParroCard {
                    ChartCaption("Score de sommeil · ${longDate(dates[s])}", scores[s]?.let(::int) ?: "—",
                        int(scores.filterNotNull().average()), color = c.score)
                    ScrubLine(scores, c.score, ::int, s, { selScore = it }, height = 140.dp,
                        axis = listOf(shortDate(dates.first()), shortDate(dates.last())), description = "Score de sommeil")
                }
            }
        }
    }
}

@Composable
private fun NightCard(n: JSONObject?, date: String) {
    val c = P.c
    if (n == null) {
        ParroCard { CardTitle("Nuit du ${longDate(date)}"); Muted("Pas de nuit enregistrée pour ce jour.") }
        return
    }
    val st = n.optJSONObject("stages_min")
    val awake = (st?.dbl("awake") ?: 0.0) + (st?.dbl("awake_in_bed") ?: 0.0)
    Column {
        Muted("Nuit du ${LocalDate.parse(date).minusDays(1).dayOfMonth} au ${longDate(date)}", 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Num(asleep(n)?.let(::hm) ?: "—", 52.sp, c.sleep, androidx.compose.ui.text.font.FontWeight.Bold)
            n.dbl("score")?.let { Pill("score ${int(it)}", c.sleepSoft, c.text) }
        }
        Muted("au lit de ${n.str("bedtime") ?: "?"} à ${n.str("wake") ?: "?"}" +
            (if (awake > 0) " · ${awake.roundToInt()} min d'éveil" else ""), 13.sp)
    }
    val segs = n.optJSONArray("segments")?.objects()?.map { Seg(it.optString("stage"), it.optDouble("start_min"), it.optDouble("dur_min")) }.orEmpty()
    ParroCard {
        CardTitle("Phases de la nuit")
        if (segs.isEmpty()) Muted("Phases non détaillées pour cette nuit (la montre n'était peut-être pas portée).")
        else {
            Hypnogram(segs, n.dbl("span_min") ?: segs.maxOf { it.start + it.dur }, n.str("bedtime"))
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageBox("Profond", st?.dbl("deep"), c.deep, Modifier.weight(1f))
                StageBox("Paradoxal", st?.dbl("rem"), c.rem, Modifier.weight(1f))
                StageBox("Léger", (st?.dbl("light") ?: 0.0) + (st?.dbl("sleeping") ?: 0.0), c.light, Modifier.weight(1f))
                StageBox("Éveil", awake, c.awake, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StageBox(label: String, minutes: Double?, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(P.c.inset).padding(8.dp)) {
        Muted(label, 11.sp, maxLines = 1)
        Num(minutes?.takeIf { it > 0 }?.let { if (it < 60) "${it.roundToInt()} min" else hm(it) } ?: "—", 14.sp, color)
    }
}
