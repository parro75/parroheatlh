package fr.parro.santesync.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.parro.santesync.Api
import fr.parro.santesync.NotLoggedInException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---- chargement ----------------------------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String, val needsLogin: Boolean = false) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = Load.Loading
        value = try {
            Load.Ok(block())
        } catch (e: NotLoggedInException) {
            Load.Err("Connecte l'app à ton serveur pour voir tes données.", needsLogin = true)
        } catch (e: Exception) {
            Load.Err(e.message ?: e.javaClass.simpleName)
        }
    }

@Composable
fun <T> Loaded(state: Load<T>, onLogin: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        is Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Load.Err -> Column(Modifier.padding(16.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (state.needsLogin) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onLogin) { Text("Se connecter") }
            }
        }
        is Load.Ok -> content(state.value)
    }
}

// ---- utilitaires JSON et dates -------------------------------------------------------------------

fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private val SHORT = DateTimeFormatter.ofPattern("dd/MM")
fun shortDate(iso: String): String = runCatching { LocalDate.parse(iso).format(SHORT) }.getOrDefault(iso)

fun range(days: Int): Map<String, String> {
    val end = LocalDate.now()
    return mapOf("start" to end.minusDays(days - 1L).toString(), "end" to end.toString())
}

/** Tous les jours de la plage, pour une frise continue (trou = pas de donnée). */
fun allDays(days: Int): List<String> {
    val end = LocalDate.now()
    return (days - 1 downTo 0).map { end.minusDays(it.toLong()).toString() }
}

fun hm(minutes: Double): String {
    val m = minutes.roundToInt()
    return "${m / 60} h ${"%02d".format(m % 60)}"
}

fun int(v: Double) = v.roundToInt().toString()

// ---- éléments communs ------------------------------------------------------------------------------

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

@Composable
fun Screen(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) { content() }
}

private val DAY_OPTIONS = listOf("14" to "14 jours", "30" to "30 jours", "90" to "90 jours", "365" to "1 an")

// ---- Accueil ---------------------------------------------------------------------------------------

data class HomeData(val today: JSONObject, val steps: JSONObject, val sleep: JSONObject, val report: JSONObject)

@Composable
fun HomeScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val state by rememberLoad(refresh) {
        HomeData(Api.get(ctx, "today"), Api.get(ctx, "steps", range(14)), Api.get(ctx, "sleep", range(14)),
            Api.get(ctx, "report"))
    }
    Screen {
        JournalCard() // local : utilisable hors ligne et sans connexion au serveur
        Loaded(state, onLogin) { d ->
            val t = d.today
            ReportCard(d.report)
            Section("Dernière nuit") {
                val n = t.optJSONObject("last_night")
                if (n == null) Text("Pas de nuit enregistrée hier soir.")
                else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("Endormi", n.dbl("asleep_min")?.let(::hm) ?: n.dbl("in_bed_min")?.let(::hm) ?: "—",
                        "${n.str("bedtime") ?: "?"} → ${n.str("wake") ?: "?"}")
                    n.dbl("score")?.let { Stat("Score", int(it), "Samsung") }
                }
            }
            Section("Aujourd'hui") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("Pas", "%,d".format((t.dbl("steps_today") ?: 0.0).toLong()).replace(',', ' '),
                        t.dbl("steps_7d_avg")?.let { "moy. 7 j : ${int(it)}" })
                    t.optJSONObject("resting_hr")?.let { r ->
                        Stat("FC au repos", "${r.dbl("bpm")?.let(::int) ?: "—"} bpm", shortDate(r.optString("date")))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    t.optJSONObject("hrv")?.let { Stat("HRV nocturne", "${int(it.optDouble("value"))} ms", "au ${shortDate(it.optString("date"))}") }
                    t.optJSONObject("energy")?.let { Stat("Énergie", int(it.optDouble("value")), "au ${shortDate(it.optString("date"))}") }
                    t.optJSONObject("stress")?.let { Stat("Stress", int(it.optDouble("value")), "au ${shortDate(it.optString("date"))}") }
                }
            }
            Section("Pas — 14 jours") { StepsBars(d.steps, 14) }
            Section("Sommeil — 14 nuits") { SleepBars(d.sleep, 14) }
            Text(
                "Synchro du téléphone : ${t.str("last_phone_sync")?.take(16)?.replace('T', ' ') ?: "jamais"} (UTC)" +
                    (t.str("last_export_day")?.let { " · dernier export Samsung : ${shortDate(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rapport du jour : texte rédigé par l'IA locale (machine Ollama) et signaux calculés par règles sur le serveur. */
@Composable
fun ReportCard(report: JSONObject) {
    val flags = report.optJSONObject("facts")?.optJSONArray("flags")?.objects().orEmpty()
    Section("Rapport du jour") {
        val text = report.str("narrative")
        if (text == null) {
            Text(if (report.optJSONObject("facts")?.optBoolean("ready") == true)
                "Ta nuit est arrivée : le rapport est en cours de rédaction (quelques minutes)."
            else "Le rapport sera rédigé dès que ta nuit aura été synchronisée.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            flags.forEach { f ->
                val attention = f.optString("level") == "attention"
                Text((if (attention) "⚠ " else "• ") + f.optString("text"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        report.str("model")?.let { m ->
            Spacer(Modifier.height(6.dp))
            Text("Rédigé par $m · signaux calculés par règles · ce n'est pas un avis médical",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StepsBars(steps: JSONObject, days: Int) {
    val byDay = steps.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("steps") }
    val dates = allDays(days)
    BarChart(dates.map(::shortDate), dates.map { byDay[it] }, SeriesColors.steps, ::int, reference = 10000.0)
}

/** Une barre empilée par nuit (profond, paradoxal, léger, éveillé ; « non détaillé » si pas de phases). */
@Composable
fun SleepBars(sleep: JSONObject, days: Int) {
    val byDay = mutableMapOf<String, DoubleArray>()
    sleep.getJSONArray("sessions").objects().forEach { s ->
        val a = byDay.getOrPut(s.optString("date")) { DoubleArray(5) }
        val st = s.optJSONObject("stages_min")
        if (st != null && st.length() > 0) {
            a[0] += st.dbl("deep") ?: 0.0; a[1] += st.dbl("rem") ?: 0.0
            a[2] += (st.dbl("light") ?: 0.0) + (st.dbl("sleeping") ?: 0.0)
            a[3] += (st.dbl("awake") ?: 0.0) + (st.dbl("awake_in_bed") ?: 0.0)
        } else a[4] += s.dbl("asleep_min") ?: s.dbl("in_bed_min") ?: 0.0
    }
    val dates = allDays(days)
    StackedBarChart(
        labels = dates.map(::shortDate),
        stacks = dates.map { d -> (byDay[d] ?: DoubleArray(5)).toList() },
        colors = listOf(SeriesColors.deep, SeriesColors.rem, SeriesColors.light, SeriesColors.awake,
            MaterialTheme.colorScheme.outline),
        names = listOf("profond", "paradoxal", "léger", "éveil", "non détaillé"),
        format = ::hm,
    )
}

// ---- Sommeil ---------------------------------------------------------------------------------------

@Composable
fun SleepScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableStateOf("30") }
    val state by rememberLoad(refresh, days) { Api.get(ctx, "sleep", range(days.toInt())) }
    Screen {
        Choices(DAY_OPTIONS, days) { days = it }
        Loaded(state, onLogin) { sleep ->
            val n = days.toInt()
            Section("Durée et phases") {
                SleepBars(sleep, n)
                sleep.dbl("avg_asleep_min")?.let { Text("Moyenne endormi : ${hm(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
            val sessions = sleep.getJSONArray("sessions").objects()
            val scores = sessions.associate { it.optString("date") to it.dbl("score") }
            if (scores.values.any { it != null }) Section("Score de sommeil") {
                val dates = allDays(n)
                LineChart(dates.map(::shortDate), listOf(Triple("score", dates.map { scores[it] }, SeriesColors.score)), ::int)
                sleep.dbl("avg_score")?.let { Text("Moyenne : ${int(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
            Section("Nuits") {
                sessions.reversed().take(60).forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${shortDate(s.optString("date"))}  ${s.str("bedtime") ?: ""} → ${s.str("wake") ?: ""}")
                        Text((s.dbl("asleep_min") ?: s.dbl("in_bed_min"))?.let(::hm) ?: "—")
                        Text(s.dbl("score")?.let { "score ${int(it)}" } ?: "")
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---- Cœur ------------------------------------------------------------------------------------------

data class HeartData(val hr: JSONObject, val recovery: JSONObject, val hrv: JSONObject, val stress: JSONObject)

@Composable
fun HeartScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableStateOf("30") }
    val state by rememberLoad(refresh, days) {
        val r = range(days.toInt())
        HeartData(Api.get(ctx, "heart_rate", r), Api.get(ctx, "recovery", r), Api.get(ctx, "hrv", r), Api.get(ctx, "stress", r))
    }
    Screen {
        Choices(DAY_OPTIONS, days) { days = it }
        Loaded(state, onLogin) { d ->
            val dates = allDays(days.toInt())
            val labels = dates.map(::shortDate)
            val hr = d.hr.getJSONArray("days").objects().associateBy { it.optString("date") }
            Section("FC au repos et pendant le sommeil") {
                LineChart(labels, listOf(
                    Triple("repos (calculée)", dates.map { hr[it]?.dbl("resting") }, SeriesColors.heart),
                    Triple("sommeil", dates.map { hr[it]?.dbl("sleeping") }, SeriesColors.heartSoft),
                ), { "${int(it)} bpm" })
            }
            val nightly = d.recovery.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("hrv_ms") }
            val spot = d.hrv.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("rmssd_avg") }
            Section("Variabilité cardiaque (HRV)") {
                LineChart(labels, listOf(
                    Triple("nocturne", dates.map { nightly[it] }, SeriesColors.hrv),
                    Triple("mesures", dates.map { spot[it] }, SeriesColors.hrv.copy(alpha = 0.4f)),
                ), { "${int(it)} ms" })
                Text("Source : exports Samsung Health (mise à jour à chaque nouvel export).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val stress = d.stress.getJSONArray("days").objects().associate { it.optString("date") to it.dbl("avg") }
            Section("Stress (moyenne du jour)") {
                BarChart(labels, dates.map { stress[it] }, SeriesColors.stress, ::int)
            }
            Section("FC journalière (min · moy · max)") {
                LineChart(labels, listOf(
                    Triple("max", dates.map { hr[it]?.dbl("max") }, SeriesColors.heart.copy(alpha = 0.5f)),
                    Triple("moy", dates.map { hr[it]?.dbl("avg") }, SeriesColors.heart),
                    Triple("min", dates.map { hr[it]?.dbl("min") }, SeriesColors.heartSoft),
                ), { "${int(it)} bpm" })
            }
        }
    }
}

// ---- Activité --------------------------------------------------------------------------------------

@Composable
fun ActivityScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableStateOf("30") }
    val state by rememberLoad(refresh, days) {
        val r = range(days.toInt())
        Api.get(ctx, "steps", r) to Api.get(ctx, "workouts", r)
    }
    Screen {
        Choices(DAY_OPTIONS, days) { days = it }
        Loaded(state, onLogin) { (steps, workouts) ->
            Section("Pas") {
                StepsBars(steps, days.toInt())
                steps.dbl("daily_avg")?.let { Text("Moyenne : ${int(it)} pas/jour", style = MaterialTheme.typography.bodyMedium) }
            }
            Section("Séances") {
                val list = workouts.getJSONArray("workouts").objects().reversed()
                if (list.isEmpty()) Text("Aucune séance sur la période.")
                list.forEach { w ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${shortDate(w.optString("date"))} ${w.str("start") ?: ""} · ${w.str("type") ?: "séance"}",
                            style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(
                            w.dbl("duration_min")?.let { "${int(it)} min" },
                            w.dbl("distance_km")?.let { "%.2f km".format(it) },
                            w.dbl("calories_kcal")?.let { "${int(it)} kcal" },
                            w.dbl("avg_hr")?.let { "FC moy ${int(it)}" },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---- Tendances -------------------------------------------------------------------------------------

private val METRICS = listOf(
    "steps" to "Pas", "sleep_minutes" to "Sommeil", "sleep_score" to "Score sommeil", "hrv_nightly" to "HRV nocturne",
    "sleeping_hr" to "FC sommeil", "heart_rate_avg" to "FC moyenne", "stress" to "Stress", "energy" to "Énergie",
    "weight" to "Poids", "workout_minutes" to "Minutes de sport",
)
private val PERIODS = listOf("year" to "Par année", "month" to "Par mois", "week" to "Par semaine")

@Composable
fun TrendsScreen(refresh: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    var metric by rememberSaveable { mutableStateOf("steps") }
    var period by rememberSaveable { mutableStateOf("month") }
    val state by rememberLoad(refresh, metric, period) {
        val end = LocalDate.now()
        val start = when (period) {
            "year" -> LocalDate.of(2015, 1, 1)
            "month" -> end.minusYears(5)
            else -> end.minusWeeks(52)
        }
        Api.get(ctx, "trends", mapOf("metric" to metric, "period" to period, "start" to start.toString(), "end" to end.toString()))
    }
    Screen {
        Choices(METRICS, metric) { metric = it }
        Spacer(Modifier.height(4.dp))
        Choices(PERIODS, period) { period = it }
        Loaded(state, onLogin) { t ->
            val rows = t.getJSONArray("rows").objects()
            val fmt: (Double) -> String = when (metric) {
                "sleep_minutes" -> ::hm
                "weight" -> { v -> "%.1f kg".format(v) }
                "workout_minutes" -> { v -> "${int(v)} min" }
                else -> ::int
            }
            Section(METRICS.first { it.first == metric }.second + " — " + PERIODS.first { it.first == period }.second.lowercase()) {
                if (rows.isEmpty()) Text("Pas de donnée sur la période.")
                else BarChart(rows.map { it.optString("period") }, rows.map { it.dbl("avg") },
                    SeriesColors.score, fmt)
                Text("Moyenne des valeurs quotidiennes de chaque période.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Détail") {
                rows.reversed().forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.optString("period"), Modifier.width(96.dp))
                        Text(r.dbl("avg")?.let(fmt) ?: "—")
                        Text("${r.optInt("days")} j", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
