package fr.parro.santesync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.parro.santesync.Journal
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = listOf("now" to "Maintenant", "2h" to "Il y a 2 h", "hier21" to "Hier soir (21 h)")
private val FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/** Journal d'événements : noter en un geste, liste des 14 derniers jours (local, utilisable hors ligne). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<Journal.Entry>()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { entries = Journal.entries(ctx).filter { it.at.isAfter(Instant.now().minus(Duration.ofDays(14))) } }
    val labels = Journal.TAGS.toMap()

    Section("Journal") {
        Button(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Noter un événement") }
        if (entries.isEmpty()) Text("Rien de noté ces 14 derniers jours. Alcool, café tardif, maladie… : " +
            "ces notes aident à comprendre tes nuits.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp))
        entries.take(10).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable {
                scope.launch { Journal.delete(ctx, e.id); Journal.flush(ctx); tick++ }
            }, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(e.tags.joinToString(", ") { labels[it] ?: it }, style = MaterialTheme.typography.bodyMedium)
                    e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text(e.at.atZone(ZoneId.systemDefault()).format(FMT) + if (e.pending) " ⏳" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entries.isNotEmpty()) Text("Toucher une entrée pour la supprimer · ⏳ = pas encore envoyée",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }

    if (open) {
        val chosen = remember { mutableStateListOf<String>() }
        var whenKey by remember { mutableStateOf("now") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Noter un événement") },
            text = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Journal.TAGS.forEach { (k, label) ->
                            FilterChip(selected = k in chosen, onClick = { if (k in chosen) chosen.remove(k) else chosen.add(k) },
                                label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WHEN.forEach { (k, label) -> FilterChip(selected = whenKey == k, onClick = { whenKey = k }, label = { Text(label) }) }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Note (facultative)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = chosen.isNotEmpty(), onClick = {
                    val zone = ZoneId.systemDefault()
                    val at = when (whenKey) {
                        "2h" -> Instant.now().minus(Duration.ofHours(2))
                        "hier21" -> LocalDate.now(zone).minusDays(1).atTime(LocalTime.of(21, 0)).atZone(zone).toInstant()
                        else -> Instant.now()
                    }
                    scope.launch { Journal.add(ctx, at, chosen.toList(), note); Journal.flush(ctx); tick++ }
                    open = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        )
    }
}
