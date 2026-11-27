package fr.parro.santesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---- Corps : poids et masse grasse ---------------------------------------------------------------------
// Une valeur par jour de pesée (la dernière du jour ; doublons ignorés par le serveur). Les courbes ne
// relient que les jours pesés : une pesée par semaine reste lisible sur 5 ans.

private val BODY_PERIODS = listOf("90" to "3 mois", "365" to "1 an", "1825" to "5 ans", "all" to "Tout")
private val DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun BodyDetail(refresh: Int, onLogin: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    var period by rememberSaveable { mutableStateOf("365") }
    var selW by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    var selF by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    val state by rememberLoad(refresh, period) {
        val end = LocalDate.now()
        val start = if (period == "all") LocalDate.of(2010, 1, 1) else end.minusDays(period.toLong() - 1)
        Api.get(ctx, "body", mapOf("start" to start.toString(), "end" to end.toString()))
    }
    Screen {
        DetailHeader("Corps", onBack)
        Loaded(state, onLogin) { body ->
            val days = body.getJSONArray("days").objects()
            val weights = days.filter { it.dbl("weight_kg") != null }
            val fats = days.filter { it.dbl("body_fat_pct") != null }
            val sw = selW?.takeIf { it < weights.size } ?: (weights.size - 1)
            val cur = weights.getOrNull(sw)
            Column {
                Muted(cur?.let { "Pesée du ${longDate(it.optString("date"))}" } ?: "Aucune pesée sur la période", 13.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num(cur?.dbl("weight_kg")?.let { dec(it) } ?: "—", 52.sp, c.body, FontWeight.Bold)
                    Muted("kg", 16.sp)
                    days.firstOrNull { it.optString("date") == cur?.optString("date") }?.dbl("body_fat_pct")
                        ?.let { Pill("${dec(it)} % de masse grasse", c.bodySoft, c.text) }
                }
                cur?.let { w ->
                    val d = LocalDate.parse(w.optString("date"))
                    val ref = weights.lastOrNull { !LocalDate.parse(it.optString("date")).isAfter(d.minusDays(28)) }
                    ref?.let { r -> Muted("${signed(w.dbl("weight_kg")!! - r.dbl("weight_kg")!!, 1, " kg")} depuis le ${shortDate(r.optString("date"))}", 13.sp) }
                }
            }
            body.optJSONObject("weight_goal")?.let { WeightGoalCard(it, refresh) }
            ParroCard(gap = 12.dp) {
                Segmented(BODY_PERIODS, period, { period = it }, accent = c.bodySoft)
                if (weights.size < 2) Muted("Pas assez de pesées pour une courbe.")
                else {
                    CardTitle("Poids")
                    ScrubLine(weights.map { it.dbl("weight_kg") }, c.body, { "${dec(it)} kg" }, sw, { selW = it },
                        axis = listOf(shortDate(weights.first().optString("date")), shortDate(weights.last().optString("date"))),
                        description = "Courbe du poids")
                }
            }
            if (fats.size >= 2) ParroCard(gap = 10.dp) {
                val sf = selF?.takeIf { it < fats.size } ?: (fats.size - 1)
                ChartCaption("Masse grasse · ${longDate(fats[sf].optString("date"))}", "${dec(fats[sf].dbl("body_fat_pct")!!)} %",
                    null, color = c.stress)
                ScrubLine(fats.map { it.dbl("body_fat_pct") }, c.stress, { "${dec(it)} %" }, sf, { selF = it }, height = 140.dp,
                    axis = listOf(shortDate(fats.first().optString("date")), shortDate(fats.last().optString("date"))),
                    description = "Courbe de la masse grasse")
                Muted("Estimée par la balance (impédance) : la valeur absolue peut être décalée de quelques points, " +
                    "la tendance est fiable si tu te pèses dans les mêmes conditions.", 11.sp)
            }
            if (weights.isNotEmpty()) ParroCard(gap = 0.dp) {
                CardTitle("Pesées (${weights.size})")
                days.reversed().take(60).forEachIndexed { i, d ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(runCatching { LocalDate.parse(d.optString("date")).format(DMY) }.getOrDefault(d.optString("date")),
                            Modifier.weight(1f), fontSize = 14.sp, color = c.text)
                        Num(d.dbl("weight_kg")?.let { "${dec(it)} kg" } ?: "—", 15.sp, modifier = Modifier.weight(1f))
                        Muted(d.dbl("body_fat_pct")?.let { "${dec(it)} %" } ?: "", 13.sp)
                    }
                }
            }
        }
    }
}
