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
