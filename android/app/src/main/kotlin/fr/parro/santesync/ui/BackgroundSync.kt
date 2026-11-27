package fr.parro.santesync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.LockGuard
import fr.parro.santesync.SyncLog

/** Chemin du réglage Samsung, distinct de l'exemption Android et impossible à ouvrir ou à lire depuis l'app. */
const val SAMSUNG_SLEEP_SETTING = "Sur un Samsung, vérifie aussi : Paramètres → Batterie → Limites d'utilisation en " +
    "arrière-plan → « Applications jamais mises en veille » → ajouter Parro Health (selon la version, Batterie se " +
    "trouve sous « Batterie et maintenance de l'appareil »)."

/**
 * Bannière de l'accueil quand Android a retiré l'exemption de l'optimisation de batterie (ça arrive sans
 * prévenir, par exemple à une réinstallation) : sans elle, les synchros automatiques sont retenues pendant la veille.
 */
@Composable
fun BatteryBanner(onFix: () -> Unit) {
    ParroCard(radius = 20.dp, gap = 8.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(P.c.warn, modifier = Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Body("Synchro automatique freinée")
                Muted("Android soumet de nouveau Parro Health à l'optimisation de batterie : quand le téléphone est en " +
                    "veille, les synchros peuvent attendre des heures.")
            }
        }
        PrimaryButton("Rétablir", onFix, color = P.c.warn)
    }
}

/** Section de l'écran Diagnostic : exemption, réglage Samsung et dernières exécutions de la synchro. */
@Composable
fun SyncRunsSection(tick: Int, onFix: () -> Unit) {
    val ctx = LocalContext.current
    val exempt = remember(tick) { SyncLog.batteryExempt(ctx) }
    val runs = remember(tick) { SyncLog.runs(ctx) }
    Section("Synchro en arrière-plan") {
        if (exempt) Muted("✓ Exemptée de l'optimisation de batterie", color = P.c.good)
        else {
            Muted("✗ Soumise à l'optimisation de batterie : synchros retenues pendant la veille", color = P.c.warn)
            PrimaryButton("Rétablir l'exemption", onFix, color = P.c.warn)
        }
        Muted(SAMSUNG_SLEEP_SETTING)
        Divider()
        if (runs.isEmpty()) Muted("Aucune exécution enregistrée pour l'instant.")
        runs.take(20).forEach { r ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Muted("${SyncLog.time(r.at)} · ${r.trigger}", 13.sp, color = P.c.text)
                    Muted(r.result, 13.sp, color = when (r.result) {
                        "ok" -> P.c.good
                        "sauté", "note" -> P.c.muted
                        else -> P.c.warn
                    })
                }
                val late = SyncLog.lateness(r)
                val sub = listOfNotNull(
                    if (r.planned > 0) "prévu ${SyncLog.time(r.planned)}" + (late?.let { " ($it)" } ?: "") else null,
                    if (!r.exempt) "non exemptée" else null,
                    r.detail.takeIf { it.isNotEmpty() && r.result != "ok" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Muted(sub, 11.sp, color = if (late != null || !r.exempt) P.c.warn else P.c.muted)
            }
        }
        if (runs.isNotEmpty()) SecondaryButton("Partager le journal des synchros", {
            LockGuard.leaving()
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, SyncLog.asText(ctx))
            }, "Partager le journal des synchros"))
        }, Modifier.fillMaxWidth())
    }
}
