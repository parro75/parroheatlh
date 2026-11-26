package fr.parro.santesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.parro.santesync.MedicalPdf
import fr.parro.santesync.NotLoggedInException
import kotlinx.coroutines.launch
import java.io.File

/** Fiche PDF pour un rendez-vous médical : 3, 6 ou 12 mois, créée par le serveur, ouverte ou partagée ici. */
@Composable
fun MedicalPdfCard(onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var months by rememberSaveable { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Section("Fiche pour le médecin (PDF)") {
        Text("Résumé des mesures (sommeil, cœur, SpO2, poids, activité) avec les courbes de la période. " +
            "Les notes du journal n'y figurent pas.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Choices(MedicalPdf.MONTHS, months) { months = it; file = null; message = null }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        val f = MedicalPdf.download(ctx, months.toInt())
                        file = f
                        if (!MedicalPdf.open(ctx, f)) message = "Aucun lecteur PDF : utilise « Partager »."
                    } catch (_: NotLoggedInException) {
                        onLogin()
                    } catch (e: Exception) {
                        message = "Échec : ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Création…" else "Créer la fiche") }
            file?.let { f -> OutlinedButton(onClick = { MedicalPdf.share(ctx, f) }) { Text("Partager") } }
        }
        message?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
