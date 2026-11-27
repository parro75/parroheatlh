package fr.parro.santesync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import fr.parro.santesync.Diagnostic

@Composable
fun DiagnosticScreen(tick: Int, onRequestExtra: () -> Unit, onFixBattery: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by rememberLoad(tick) { Diagnostic.run(HealthConnectClient.getOrCreate(ctx)) }
    Screen {
        SyncRunsSection(tick, onFixBattery)
        Section("Ce que Health Connect contient (30 jours)") {
            Text("Nombre d'enregistrements par type et application source. Les types de santé reproductive " +
                "ne sont ni demandés ni lus.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Retour") }
                OutlinedButton(onClick = onRequestExtra) { Text("Autoriser plus de types") }
            }
        }
        Loaded(state, onLogin = {}) { lines ->
            Section("Résultat") {
                Button(onClick = {
                    fr.parro.santesync.LockGuard.leaving()
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Diagnostic.asText(lines))
                    }, "Partager le diagnostic"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Partager le diagnostic") }
                Spacer(Modifier.height(8.dp))
                lines.sortedWith(compareBy({ !it.allowed }, { -(it.count ?: -1) })).forEach { l ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(l.type, style = MaterialTheme.typography.bodyMedium)
                            Text(when {
                                !l.allowed -> "non autorisé"
                                l.count == null -> "illisible"
                                else -> (if (l.capped) "≥ " else "") + l.count
                            }, color = if (l.allowed && (l.count ?: 0) > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (l.sources.isNotEmpty()) Text(l.sources.entries.joinToString(", ") { "${it.key} (${it.value})" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
