package fr.parro.santesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.parro.santesync.Api
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ---- Corps : poids et masse grasse (25/11/2026) -----------------------------------------------------
// Une valeur par jour de pesée (la dernière du jour ; doublons ignorés par le serveur). Les graphiques
// n'affichent que les jours pesés : une pesée par semaine reste lisible sur 5 ans.

private val BODY_PERIODS = listOf("90" to "3 mois", "365" to "1 an", "1825" to "5 ans", "all" to "Tout")
private val WEIGHT_COLOR = Color(0xFF6D4C41)
private val FAT_COLOR = Color(0xFFFFA000)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MM/yy")

private fun kg(v: Double) = "%.1f kg".format(v)
private fun pct(v: Double) = "%.1f %%".format(v)

@Composable
fun BodyScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var period by rememberSaveable { mutableStateOf("365") }
    val state by rememberLoad(refresh, period) {
        val end = LocalDate.now()
        val start = if (period == "all") LocalDate.of(2010, 1, 1) else end.minusDays(period.toLong() - 1)
        Api.get(ctx, "body", mapOf("start" to start.toString(), "end" to end.toString()))
    }
    Screen {
        Choices(BODY_PERIODS, period) { period = it }
        Loaded(state, onLogin) { body ->
            val days = body.getJSONArray("days").objects()
            val weights = days.filter { it.dbl("weight_kg") != null }
            val fats = days.filter { it.dbl("body_fat_pct") != null }
            val long = days.size >= 2 &&
                ChronoUnit.DAYS.between(LocalDate.parse(days.first().optString("date")), LocalDate.parse(days.last().optString("date"))) > 330
            val label: (String) -> String = { iso ->
                if (long) runCatching { LocalDate.parse(iso).format(MONTH_YEAR) }.getOrDefault(iso) else shortDate(iso)
            }

            Section("Dernière pesée") {
                val last = weights.lastOrNull()
                if (last == null) Text("Aucune pesée sur la période.")
                else {
                    val lastDate = LocalDate.parse(last.optString("date"))
                    // variation sur environ 30 jours : dernière pesée faite au moins 30 jours avant
                    val ref = weights.lastOrNull { !LocalDate.parse(it.optString("date")).isAfter(lastDate.minusDays(30)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("Poids", kg(last.dbl("weight_kg")!!), shortDate(last.optString("date")))
                        days.lastOrNull { it.optString("date") == last.optString("date") }?.dbl("body_fat_pct")
                            ?.let { Stat("Masse grasse", pct(it), "estimation") }
                        ref?.dbl("weight_kg")?.let { r ->
                            val diff = last.dbl("weight_kg")!! - r
                            Stat("Depuis le ${shortDate(ref.optString("date"))}", (if (diff >= 0) "+" else "−") + kg(kotlin.math.abs(diff)))
                        }
                    }
                }
            }
            Section("Poids") {
                if (weights.size < 2) Text("Pas assez de pesées pour un graphique.")
                else LineChart(weights.map { label(it.optString("date")) },
                    listOf(Triple("poids", weights.map { it.dbl("weight_kg") }, WEIGHT_COLOR)), ::kg)
            }
            if (fats.size >= 2) Section("Masse grasse") {
                LineChart(fats.map { label(it.optString("date")) },
                    listOf(Triple("masse grasse", fats.map { it.dbl("body_fat_pct") }, FAT_COLOR)), ::pct)
                Text("Estimée par la balance (impédance) : la valeur absolue peut être décalée de quelques points, " +
                    "la tendance est fiable si tu te pèses dans les mêmes conditions.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Pesées (${weights.size})") {
                days.reversed().take(100).forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.optString("date").let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(it) })
                        Text(d.dbl("weight_kg")?.let(::kg) ?: "—")
                        Text(d.dbl("body_fat_pct")?.let(::pct) ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
