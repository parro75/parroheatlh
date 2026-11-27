package fr.parro.santesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Journal
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = listOf("now" to "Maintenant", "2h" to "Il y a 2 h", "hier21" to "Hier soir")
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

/** Journal d'événements : noter en un geste (local, utilisable hors ligne), frise des 30 derniers jours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen() {
    val ctx = LocalContext.current
    val c = P.c
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<Journal.Entry>()) }
    val chosen = remember { mutableStateListOf<String>() }
    var whenKey by remember { mutableStateOf("now") }
    var note by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Journal.Entry?>(null) }
    LaunchedEffect(tick) { entries = Journal.entries(ctx).filter { it.at.isAfter(Instant.now().minus(Duration.ofDays(30))) } }
    val labels = Journal.TAGS.toMap()

    Screen {
        PageTitle("Repas et ce qui peut jouer sur ta nuit", "Journal")
        MealsSection()
        ParroCard(gap = 14.dp) {
            CardTitle("Noter maintenant")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Journal.TAGS.forEach { (k, label) ->
                    val on = k in chosen
                    Text(label, Modifier.clip(RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) c.sleep else c.line, RoundedCornerShape(20.dp))
                        .background(if (on) c.sleepSoft else Color.Transparent)
                        .clickable(role = Role.Checkbox) { if (on) chosen.remove(k) else chosen.add(k) }
                        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) c.text else c.text.copy(alpha = 0.8f))
                }
            }
            Segmented(WHEN, whenKey, { whenKey = it })
            OutlinedTextField(note, { note = it }, label = { Text("Note (facultative)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.sleep, unfocusedBorderColor = c.line,
                    focusedContainerColor = c.inset, unfocusedContainerColor = c.inset))
            PrimaryButton(if (chosen.isEmpty()) "Choisis au moins un élément" else "Enregistrer (${chosen.size})", {
                val zone = ZoneId.systemDefault()
                val at = when (whenKey) {
                    "2h" -> Instant.now().minus(Duration.ofHours(2))
                    "hier21" -> LocalDate.now(zone).minusDays(1).atTime(LocalTime.of(21, 0)).atZone(zone).toInstant()
                    else -> Instant.now()
                }
                val tags = chosen.toList()
                val n = note
                scope.launch { Journal.add(ctx, at, tags, n); Journal.flush(ctx); tick++ }
                chosen.clear(); note = ""; whenKey = "now"
            }, enabled = chosen.isNotEmpty())
        }

        CardTitle("30 derniers jours")
        if (entries.isEmpty()) Muted("Rien de noté. Alcool, café tardif, maladie… : ces notes sont rappelées dans tes rapports.")
        entries.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(14.dp).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Dot(if (e.pending) c.warn else c.sleep, 10.dp)
                }
                ParroCard(Modifier.weight(1f), radius = 18.dp, padding = 12.dp, gap = 2.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Muted(whenText(e.at) + if (e.pending) " · pas encore envoyée" else "")
                            Text(e.tags.joinToString(" · ") { labels[it] ?: it }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            e.note?.takeIf { it.isNotBlank() }?.let { Muted(it, 13.sp, color = c.text.copy(alpha = 0.8f)) }
                        }
                        IconButton(onClick = { toDelete = e }) {
                            Icon(ParroIcons.trash, contentDescription = "Supprimer", tint = c.faint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer cette entrée ?") },
            text = { Text(e.tags.joinToString(", ") { labels[it] ?: it } + " — " + whenText(e.at)) },
            confirmButton = { TextButton(onClick = {
                scope.launch { Journal.delete(ctx, e.id); Journal.flush(ctx); tick++ }; toDelete = null
            }) { Text("Supprimer", color = c.heart) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
            containerColor = c.surface,
        )
    }
}

private fun whenText(at: Instant): String {
    val z = at.atZone(ZoneId.systemDefault())
    val d = z.toLocalDate()
    val today = LocalDate.now()
    val day = when (d) {
        today -> "Aujourd'hui"
        today.minusDays(1) -> "Hier"
        else -> fullDate(d)
    }
    return "$day, ${z.format(HOUR)}"
}
