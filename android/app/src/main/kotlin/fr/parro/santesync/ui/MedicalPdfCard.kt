package fr.parro.santesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val c = P.c
    val scope = rememberCoroutineScope()
    var months by rememberSaveable { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    ParroCard(gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.heartSoft), contentAlignment = Alignment.Center) {
                Icon(ParroIcons.doc, contentDescription = null, tint = c.heart, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) { CardTitle("Fiche pour le médecin", "PDF : mesures et courbes, sans tes notes du journal") }
        }
        Segmented(MedicalPdf.MONTHS, months, { months = it; file = null; message = null }, accent = c.heartSoft)
        val f = file
        if (f != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Ouvrir la fiche", { if (!MedicalPdf.open(ctx, f)) message = "Aucun lecteur PDF : utilise « Partager »." },
                color = c.heart, modifier = Modifier.weight(1f))
            SecondaryButton("Partager", { MedicalPdf.share(ctx, f) }, Modifier.weight(1f))
        } else PrimaryButton(if (busy) "Création…" else "Créer la fiche (${months} mois)", {
            busy = true; message = null
            scope.launch {
                try {
                    val created = MedicalPdf.download(ctx, months.toInt())
                    file = created
                    if (!MedicalPdf.open(ctx, created)) message = "Aucun lecteur PDF : utilise « Partager »."
                } catch (_: NotLoggedInException) {
                    onLogin()
                } catch (e: Exception) {
                    message = "Échec : ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy, color = c.heart)
        message?.let { Muted(it) }
    }
}
