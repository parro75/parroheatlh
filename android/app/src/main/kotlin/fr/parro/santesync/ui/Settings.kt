package fr.parro.santesync.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import fr.parro.santesync.BuildConfig
import fr.parro.santesync.HealthReader
import fr.parro.santesync.OAuth
import fr.parro.santesync.Prefs
import fr.parro.santesync.Sync
import fr.parro.santesync.SyncWorker
import fr.parro.santesync.TokenStore
import fr.parro.santesync.Trigger
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Actions qui exigent l'activité (lanceurs de permissions, intents système). */
class SettingsActions(
    val requestHealthPermissions: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val openHealthConnectInstall: () -> Unit,
    val toast: (String) -> Unit,
    val openDiagnostic: () -> Unit,
    /** Vérifie la biométrie avant d'activer le verrouillage ; appelle onResult(true) si réussi. */
    val confirmBiometric: (onResult: (Boolean) -> Unit) -> Unit,
    val pickExportZip: () -> Unit,
)

private fun fmt(ms: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun batteryExempt(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
fun SettingsScreen(actions: SettingsActions, resumeTick: Int, onLoginChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.serverUrl.ifEmpty { "https://" }) }
    var token by remember { mutableStateOf(prefs.token) }
    var status by remember { mutableStateOf(prefs.lastStatus) }
    var perms by remember { mutableStateOf("…") }
    var nextRun by remember { mutableStateOf<Long?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val loggedIn = remember(resumeTick, tick) { TokenStore(ctx).isLoggedIn }
    val exempt = remember(resumeTick, tick) { batteryExempt(ctx) }

    LaunchedEffect(resumeTick, tick) {
        status = prefs.lastStatus
        nextRun = SyncWorker.nextRun(ctx)
        perms = if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) "Health Connect indisponible"
        else try {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            val ok = HealthReader.READ_PERMISSIONS.count { it in granted }
            val bg = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            val hist = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            "Types autorisés : $ok/${HealthReader.READ_PERMISSIONS.size} · arrière-plan : ${if (bg) "oui" else "non"} · historique : ${if (hist) "oui" else "non"}"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    Screen {
        Section("Envoi des données (téléphone → serveur)") {
            OutlinedTextField(url, { url = it }, label = { Text("URL du serveur") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Jeton d'ingestion") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val u = url.trim()
                when {
                    !u.startsWith("https://") -> actions.toast("L'URL doit commencer par https://")
                    token.trim().length < 32 -> actions.toast("Le jeton doit faire au moins 32 caractères")
                    else -> {
                        val changed = u.trimEnd('/') != prefs.serverUrl
                        prefs.serverUrl = u; prefs.token = token
                        if (changed) { prefs.lastSync = null; OAuth.logout(ctx) }
                        SyncWorker.schedule(ctx)
                        actions.toast("Enregistré — synchro horaire planifiée"); tick++
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
        }
        Section("Health Connect") {
            Text(perms, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                        actions.openHealthConnectInstall() else actions.requestHealthPermissions()
                }) { Text("Autoriser") }
                OutlinedButton(onClick = actions.openDiagnostic) { Text("Diagnostic") }
            }
        }
        Section("Objectifs") {
            var sleepGoal by remember { mutableStateOf(prefs.sleepGoalMin) }
            var stepsGoal by remember { mutableStateOf(prefs.stepsGoal) }
            Text("Sommeil", style = MaterialTheme.typography.bodyMedium)
            Choices(fr.parro.santesync.Goals.SLEEP_OPTIONS.map { it.toString() to hm(it.toDouble()) }, sleepGoal.toString()) {
                sleepGoal = it.toInt(); prefs.sleepGoalMin = sleepGoal
                scope.launch { fr.parro.santesync.Goals.flush(ctx); fr.parro.santesync.ParroWidget.updateAll(ctx) }
            }
            Spacer(Modifier.height(6.dp))
            Text("Pas par jour", style = MaterialTheme.typography.bodyMedium)
            Choices(fr.parro.santesync.Goals.STEPS_OPTIONS.map { it.toString() to "%,d".format(it).replace(',', ' ') }, stepsGoal.toString()) {
                stepsGoal = it.toInt(); prefs.stepsGoal = stepsGoal
                scope.launch { fr.parro.santesync.Goals.flush(ctx); fr.parro.santesync.ParroWidget.updateAll(ctx) }
            }
            Text("Utilisés par les graphiques et par les règles du rapport du matin et de la semaine.",
                style = MaterialTheme.typography.bodySmall)
        }
        Section("Rappel de pesée") {
            var mode by remember { mutableStateOf(prefs.weighReminder) }
            var day by remember { mutableStateOf(prefs.weighDay) }
            var minutes by remember { mutableStateOf(prefs.weighMinutes) }
            val apply = { fr.parro.santesync.WeighReminder.schedule(ctx, fr.parro.santesync.WeighReminder.Policy.FROM_SETTINGS) }
            Choices(listOf(fr.parro.santesync.WeighReminder.OFF to "Désactivé", fr.parro.santesync.WeighReminder.DAILY to "Chaque jour",
                fr.parro.santesync.WeighReminder.WEEKLY to "Chaque semaine"), mode) { mode = it; prefs.weighReminder = it; apply() }
            if (mode == fr.parro.santesync.WeighReminder.WEEKLY) {
                Choices(fr.parro.santesync.WeighReminder.DAYS.map { it.first.toString() to it.second }, day.toString()) {
                    day = it.toInt(); prefs.weighDay = day; apply()
                }
            }
            if (mode != fr.parro.santesync.WeighReminder.OFF) {
                Choices(fr.parro.santesync.WeighReminder.TIME_OPTIONS.map { it.toString() to "${it / 60} h ${"%02d".format(it % 60)}".replace(" h 00", " h") },
                    minutes.toString()) { minutes = it.toInt(); prefs.weighMinutes = minutes; apply() }
                Text("Pas de rappel si tu t'es déjà pesé ce jour-là (balance lue dans Health Connect).",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Export Samsung Health (HRV, scores, historique)") {
            Text("Samsung Health → Réglages → Télécharger les données personnelles. Dans Mes fichiers, appui long " +
                "sur le dossier → Compresser, puis « Partager » le zip vers Parro Health, ou choisis-le ici.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = actions.pickExportZip) { Text("Choisir un zip d'export") }
            fr.parro.santesync.ExportUpload.status(ctx).takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Confidentialité") {
            var locked by remember { mutableStateOf(prefs.lockEnabled) }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Verrouiller l'app", style = MaterialTheme.typography.bodyLarge)
                    Text("Empreinte, visage ou code du téléphone à l'ouverture (après 1 min en arrière-plan).",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = locked, onCheckedChange = { on ->
                    // Le widget masque ses données dès que le verrouillage est actif.
                    val refreshWidget = { scope.launch { fr.parro.santesync.ParroWidget.updateAll(ctx) } }
                    if (!on) { prefs.lockEnabled = false; locked = false; refreshWidget() }
                    else actions.confirmBiometric { ok -> if (ok) { prefs.lockEnabled = true; locked = true; refreshWidget() } }
                })
            }
        }
        Section("Synchronisation") {
            Text(status.ifEmpty { "Jamais synchronisé" }, style = MaterialTheme.typography.bodyMedium)
            Text("Prochaine synchro automatique : " + (nextRun?.let(::fmt) ?: "non planifiée"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(enabled = !syncing, onClick = {
                syncing = true
                scope.launch {
                    // Au premier plan : pas besoin de la permission d'arrière-plan, et pas de quota de fond.
                    Sync.run(ctx.applicationContext, Trigger.MANUAL)
                    syncing = false; tick++
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (syncing) "Synchronisation…" else "Synchroniser maintenant") }
            Spacer(Modifier.height(8.dp))
            if (exempt) Text("✓ Exemptée de l'optimisation de batterie : Android ne retardera pas la synchro.",
                style = MaterialTheme.typography.bodySmall)
            else Column {
                Text("Android peut retarder la synchro horaire tant que l'app est soumise à l'optimisation de batterie.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions.requestBatteryExemption) { Text("Exempter de l'optimisation de batterie") }
            }
        }
        Section("Lecture des données (graphiques)") {
            if (loggedIn) {
                Text("✓ Connectée à ${prefs.serverUrl}. Le jeton de lecture est chiffré par le Keystore du téléphone.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { OAuth.logout(ctx); tick++; onLoginChanged() }) { Text("Se déconnecter") }
            } else {
                Text("Connexion par mot de passe + code TOTP sur la page de ton serveur (une seule fois).",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(enabled = prefs.serverUrl.startsWith("https://"), onClick = { OAuth.start(ctx) }) { Text("Se connecter") }
            }
        }
        Text("Parro Health ${BuildConfig.VERSION_NAME} · ${Sync.userAgent("…")}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth())
    }
}
