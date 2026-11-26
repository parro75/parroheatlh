package fr.parro.santesync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.NotLoggedInException
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        is Load.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = P.c.sleep, strokeWidth = 3.dp)
        }
        is Load.Err -> ParroCard {
            Text(state.message, color = P.c.heart, fontSize = 14.sp)
            if (state.needsLogin) PrimaryButton("Se connecter", onLogin)
        }
        is Load.Ok -> content(state.value)
    }
}

// ---- utilitaires JSON, dates, nombres -------------------------------------------------------------------

fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

val FR: Locale = Locale.FRENCH
private val SHORT = DateTimeFormatter.ofPattern("dd/MM")
private val LONG = DateTimeFormatter.ofPattern("d MMMM", FR)
private val FULL = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)
val MONTHS_SHORT = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

fun shortDate(iso: String): String = runCatching { LocalDate.parse(iso).format(SHORT) }.getOrDefault(iso)
fun longDate(iso: String): String = runCatching { LocalDate.parse(iso).format(LONG) }.getOrDefault(iso)
fun fullDate(d: LocalDate): String = d.format(FULL).replaceFirstChar { it.titlecase(FR) }
fun dayInitial(d: LocalDate): String = "LMMJVSD"[d.dayOfWeek.value - 1].toString()
fun monthLabel(key: String): String = // « 2026-11 » → « nov. 26 »
    runCatching { MONTHS_SHORT[key.substring(5, 7).toInt() - 1] + " " + key.substring(2, 4) }.getOrDefault(key)

fun range(days: Int): Map<String, String> {
    val end = LocalDate.now()
    return mapOf("start" to end.minusDays(days - 1L).toString(), "end" to end.toString())
}

/** Tous les jours de la plage, pour une frise continue (trou = pas de donnée). */
fun allDays(days: Int): List<String> {
    val end = LocalDate.now()
    return (days - 1 downTo 0).map { end.minusDays(it.toLong()).toString() }
}

/** Repères sous un graphique quotidien : initiale du jour (7), quantième chaque semaine (≤ 31), mois sinon. */
fun dayTicks(dates: List<String>): List<String> = dates.mapIndexed { i, iso ->
    val d = LocalDate.parse(iso)
    when {
        dates.size <= 7 -> dayInitial(d)
        dates.size <= 31 -> if ((dates.size - 1 - i) % 7 == 0) d.dayOfMonth.toString() else ""
        else -> if (d.dayOfMonth == 1) MONTHS_SHORT[d.monthValue - 1].take(1).uppercase() else ""
    }
}

fun hm(minutes: Double): String {
    val m = minutes.roundToInt()
    return "${m / 60} h ${"%02d".format(m % 60)}"
}

fun int(v: Double): String = "%,d".format(FR, v.roundToInt()).replace(' ', ' ').replace(' ', ' ')
fun dec(v: Double, nd: Int = 1): String = String.format(FR, "%.${nd}f", v)
fun signed(v: Double, nd: Int = 1, unit: String = ""): String =
    (if (v > 0) "+" else if (v < 0) "−" else "±") + dec(kotlin.math.abs(v), nd) + unit

fun ago(at: Instant?): String {
    if (at == null) return "jamais"
    val m = Duration.between(at, Instant.now()).toMinutes()
    return when {
        m < 1 -> "à l'instant"
        m < 60 -> "il y a $m min"
        m < 24 * 60 -> "il y a ${m / 60} h"
        else -> "il y a ${m / (24 * 60)} j"
    }
}

fun startOfWeek(d: LocalDate = LocalDate.now()): LocalDate = d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

// ---- mise en page ------------------------------------------------------------------------------------

@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
    )
}

@Composable
fun ParroCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, radius: Dp = 24.dp, padding: Dp = 16.dp,
              gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    var m = modifier.fillMaxWidth().clip(shape).background(P.c.surface)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding).animateContentSize(), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** Carte avec titre (compatibilité : l'écran Diagnostic l'utilise). */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ParroCard { CardTitle(title); content() }
}

@Composable
fun CardTitle(text: String, sub: String? = null) {
    Column {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.c.text)
        sub?.let { Muted(it) }
    }
}

@Composable
fun PageTitle(over: String?, title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            over?.let { Text(it, fontSize = 13.sp, color = P.c.muted) }
            Text(title, fontFamily = NumFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp,
                color = P.c.text)
        }
        trailing?.invoke()
    }
}

@Composable
fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 2.dp)) {
            Icon(ParroIcons.back, contentDescription = "Retour", tint = P.c.text)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.c.text)
    }
}

@Composable
fun Num(text: String, size: TextUnit = 24.sp, color: Color = P.c.text, weight: FontWeight = FontWeight.SemiBold,
        modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = NumFont, fontWeight = weight, fontSize = size, color = color, maxLines = 1,
        letterSpacing = if (size.value >= 40) (-1).sp else 0.sp)
}

@Composable
fun Muted(text: String, size: TextUnit = 12.sp, modifier: Modifier = Modifier, color: Color = P.c.muted, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, fontSize = size, color = color, lineHeight = (size.value * 1.35).sp, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, fontSize = 14.sp, lineHeight = 21.sp, color = P.c.text.copy(alpha = 0.9f), maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
}

/** Sélecteur segmenté (périodes, mesures). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, accent: Color = P.c.sleepSoft) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.c.inset).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            val bg by animateColorAsState(if (on) accent else Color.Transparent, label = "seg")
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(11.dp)).background(bg)
                .clickable(role = Role.Tab) { onSelect(key) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) P.c.text else P.c.muted, maxLines = 1)
            }
        }
    }
}

/** Puces défilantes avec pastille de couleur (choix de la mesure). */
@Composable
fun ChipRow(options: List<Triple<String, String, Color>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label, color) ->
            val on = key == selected
            Row(Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, if (on) P.c.faint else P.c.line, RoundedCornerShape(20.dp))
                .background(if (on) P.c.surfaceHigh else Color.Transparent).clickable(role = Role.Tab) { onSelect(key) }
                .heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(color)
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) P.c.text else P.c.muted)
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier, valueColor: Color = P.c.text,
            bg: Color = P.c.surface) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(bg).padding(12.dp)) {
        Muted(label, 11.sp, maxLines = 1)
        Num(value, 18.sp, valueColor)
        sub?.let { Muted(it, 11.sp, maxLines = 2) }
    }
}

/** Tuile de l'accueil : chiffre, mini-courbe, ligne de contexte ; ouvre le détail. */
@Composable
fun Tile(label: String, icon: ImageVector, color: Color, value: String, unit: String?, sub: String, spark: List<Double?>,
         onClick: () -> Unit, modifier: Modifier = Modifier) {
    ParroCard(modifier, onClick = onClick, radius = 22.dp, gap = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Num(value, 26.sp)
            unit?.let { Muted(it, 14.sp, Modifier.padding(bottom = 3.dp)) }
        }
        Sparkline(spark, color)
        Muted(sub, maxLines = 2)
    }
}

@Composable
fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = P.c.text)
            sub?.let { Muted(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = P.c.sleep, checkedThumbColor = P.c.onAccent,
            uncheckedTrackColor = P.c.surfaceHigh, uncheckedThumbColor = P.c.muted, uncheckedBorderColor = P.c.line))
    }
}

@Composable
fun NavRow(title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = P.c.text)
            sub?.let { Muted(it) }
        }
        Icon(ParroIcons.chevron, contentDescription = null, tint = P.c.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun Stepper(value: String, color: Color, onMinus: () -> Unit, onPlus: () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        RoundIcon(ParroIcons.minus, "Diminuer : $label", onMinus)
        Num(value, 17.sp, color, modifier = Modifier.width(78.dp).padding(horizontal = 4.dp))
        RoundIcon(ParroIcons.plus, "Augmenter : $label", onPlus)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, P.c.line, CircleShape).background(P.c.inset)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = P.c.text, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = P.c.sleep,
                  modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = P.c.onAccent,
            disabledContainerColor = P.c.surfaceHigh, disabledContentColor = P.c.faint)) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, P.c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = P.c.text)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = P.c.line, thickness = 1.dp)

/** Signal du rapport : pastille ambre pour « attention », neutre sinon. */
@Composable
fun FlagLine(level: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(if (level == "attention") P.c.warn else P.c.faint, modifier = Modifier.padding(top = 6.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = P.c.text)
    }
}

/** En-tête de graphique : valeur sélectionnée à gauche, moyenne à droite. */
@Composable
fun ChartCaption(label: String, value: String, right: String?, rightLabel: String = "moyenne", color: Color = P.c.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Muted(label, maxLines = 1)
            Num(value, 24.sp, color)
        }
        right?.let {
            Column(horizontalAlignment = Alignment.End) {
                Muted(rightLabel)
                Num(it, 15.sp)
            }
        }
    }
}
