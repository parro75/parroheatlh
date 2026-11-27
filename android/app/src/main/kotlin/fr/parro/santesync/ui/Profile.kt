package fr.parro.santesync.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import java.time.ZoneOffset
import java.time.LocalDate
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import fr.parro.santesync.BuildConfig
import fr.parro.santesync.ExportUpload
import fr.parro.santesync.Goals
import fr.parro.santesync.HealthReader
import fr.parro.santesync.OAuth
import fr.parro.santesync.ParroWidget
import fr.parro.santesync.Prefs
import fr.parro.santesync.SyncWorker
import fr.parro.santesync.TokenStore
import fr.parro.santesync.WeighReminder
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
    val openAccessLog: () -> Unit,
)

private fun fmt(ms: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun batteryExempt(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

private fun hShort(min: Int) = "${min / 60} h ${"%02d".format(min % 60)}".replace(" h 00", " h")

@Composable
fun ProfileScreen(actions: SettingsActions, resumeTick: Int, syncing: Boolean, onSync: () -> Unit, onLogin: () -> Unit,
                  onLoginChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var perms by remember { mutableStateOf("…") }
    var permsShort by remember { mutableStateOf("…") }
    var nextRun by remember { mutableStateOf<Long?>(null) }
    val loggedIn = remember(resumeTick, tick) { TokenStore(ctx).isLoggedIn }
    val exempt = remember(resumeTick, tick) { batteryExempt(ctx) }

    LaunchedEffect(resumeTick, tick, syncing) {
        nextRun = SyncWorker.nextRun(ctx)
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
            perms = "Health Connect indisponible"; permsShort = "indisponible"
        } else try {
            val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            val ok = HealthReader.READ_PERMISSIONS.count { it in granted }
            val bg = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            val hist = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            permsShort = "$ok/${HealthReader.READ_PERMISSIONS.size} types"
            perms = "$ok/${HealthReader.READ_PERMISSIONS.size} types · arrière-plan : ${if (bg) "oui" else "non"} · historique : ${if (hist) "oui" else "non"}"
        } catch (e: Exception) {
            perms = e.message ?: e.javaClass.simpleName; permsShort = "erreur"
        }
    }

    Screen {
        PageTitle(null, "Profil")
        ParroCard(radius = 22.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCell("Synchro", if (syncing) "en cours…" else ago(prefs.lastSync), if (syncing) c.warn else c.good, Modifier.weight(1f))
                StatusCell("Health Connect", permsShort, null, Modifier.weight(1f))
                StatusCell("Prochaine", nextRun?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) } ?: "—",
                    null, Modifier.weight(1f))
            }
        }

        // ---- objectifs ----
        ParroCard(padding = 16.dp, gap = 0.dp) {
            SectionLabel("OBJECTIFS")
            var sleepGoal by remember { mutableIntStateOf(prefs.sleepGoalMin) }
            var stepsGoal by remember { mutableIntStateOf(prefs.stepsGoal) }
            fun save() = scope.launch { Goals.flush(ctx); ParroWidget.updateAll(ctx) }
            fun step(list: List<Int>, cur: Int, dir: Int): Int {
                val i = list.indexOfFirst { it >= cur }.let { if (it < 0) list.size - 1 else it }
                return list[(i + dir).coerceIn(0, list.size - 1)]
            }
            GoalRow("Sommeil", "temps endormi par nuit") {
                Stepper(hm(sleepGoal.toDouble()), c.sleep, {
                    sleepGoal = step(Goals.SLEEP_OPTIONS, sleepGoal, -1); prefs.sleepGoalMin = sleepGoal; save()
                }, { sleepGoal = step(Goals.SLEEP_OPTIONS, sleepGoal, 1); prefs.sleepGoalMin = sleepGoal; save() }, "objectif de sommeil")
            }
            Divider()
            GoalRow("Pas", "par jour") {
                Stepper(int(stepsGoal.toDouble()), c.activity, {
                    stepsGoal = step(Goals.STEPS_OPTIONS, stepsGoal, -1); prefs.stepsGoal = stepsGoal; save()
                }, { stepsGoal = step(Goals.STEPS_OPTIONS, stepsGoal, 1); prefs.stepsGoal = stepsGoal; save() }, "objectif de pas")
            }
            Divider()
            var wLow by remember { mutableIntStateOf(prefs.weightLow) }
            var wHigh by remember { mutableIntStateOf(prefs.weightHigh) }
            var wDate by remember { mutableStateOf(prefs.weightDate) }
            var editingWeight by remember { mutableStateOf(false) }
            GoalRow("Poids", if (wLow > 0 && wDate.isNotEmpty()) "entre $wLow et $wHigh kg le ${dmy(wDate)}" else "aucun objectif") {
                TextButton(onClick = { editingWeight = true }) {
                    Text(if (wLow > 0) "Modifier" else "Définir", color = c.body)
                }
            }
            if (editingWeight) WeightGoalDialog(wLow, wHigh, wDate, onDismiss = { editingWeight = false },
                onRemove = {
                    prefs.weightLow = 0; prefs.weightHigh = 0; prefs.weightDate = ""; prefs.weightSince = ""
                    wLow = 0; wHigh = 0; wDate = ""; editingWeight = false; save()
                }) { lo, hi, d ->
                // départ de la trajectoire : le jour où l'objectif est créé (une modification le garde)
                if (prefs.weightLow == 0 || prefs.weightSince.isEmpty()) prefs.weightSince = LocalDate.now().toString()
                prefs.weightLow = lo; prefs.weightHigh = hi; prefs.weightDate = d
                wLow = lo; wHigh = hi; wDate = d; editingWeight = false; save()
            }
            Muted("Utilisés par les graphiques et par les règles des rapports. Poids : trajectoire dans Corps et cible " +
                "de calories du jour dans Repas.", 11.sp, Modifier.padding(top = 6.dp))
        }

        MedicalPdfCard(onLogin)

        // ---- rappels et confidentialité ----
        ParroCard(gap = 0.dp) {
            SectionLabel("RAPPELS ET CONFIDENTIALITÉ")
            var mode by remember { mutableStateOf(prefs.weighReminder) }
            var day by remember { mutableIntStateOf(prefs.weighDay) }
            var minutes by remember { mutableIntStateOf(prefs.weighMinutes) }
            val apply = { WeighReminder.schedule(ctx, WeighReminder.Policy.FROM_SETTINGS) }
            val dayName = WeighReminder.DAYS.firstOrNull { it.first == day }?.second?.lowercase() ?: ""
            ToggleRow("Rappel de pesée", when (mode) {
                WeighReminder.DAILY -> "chaque jour à ${hShort(minutes)}, sauf si une pesée existe déjà"
                WeighReminder.WEEKLY -> "chaque $dayName. à ${hShort(minutes)}, sauf si une pesée existe déjà"
                else -> "désactivé"
            }, mode != WeighReminder.OFF) { on ->
                mode = if (on) WeighReminder.WEEKLY else WeighReminder.OFF; prefs.weighReminder = mode; apply()
            }
            if (mode != WeighReminder.OFF) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf(WeighReminder.DAILY to "Chaque jour", WeighReminder.WEEKLY to "Chaque semaine"), mode,
                    { mode = it; prefs.weighReminder = it; apply() })
                if (mode == WeighReminder.WEEKLY) Segmented(WeighReminder.DAYS.map { it.first.toString() to it.second.take(1) },
                    day.toString(), { day = it.toInt(); prefs.weighDay = day; apply() })
                Segmented(WeighReminder.TIME_OPTIONS.map { it.toString() to hShort(it) }, minutes.toString(),
                    { minutes = it.toInt(); prefs.weighMinutes = minutes; apply() })
            }
            Divider()
            var locked by remember { mutableStateOf(prefs.lockEnabled) }
            ToggleRow("Verrouillage biométrique", "empreinte, visage ou code à chaque retour dans l'app, sans délai", locked) { on ->
                val refreshWidget = { scope.launch { ParroWidget.updateAll(ctx) } } // le widget masque ses données si verrouillé
                if (!on) { prefs.lockEnabled = false; locked = false; refreshWidget() }
                else actions.confirmBiometric { ok -> if (ok) { prefs.lockEnabled = true; locked = true; refreshWidget() } }
            }
        }

        // ---- synchronisation ----
        ParroCard {
            SectionLabel("SYNCHRONISATION")
            Body(prefs.lastStatus.ifEmpty { "Jamais synchronisé" })
            val paired = remember(resumeTick, tick, syncing) { prefs.deviceId }
            Muted(if (paired.isNotEmpty()) "✓ Envois signés par la clé matérielle de ce téléphone (appareil ${paired.take(8)})"
                else "Envois pas encore signés : connecte la lecture (plus bas) pour appairer ce téléphone",
                color = if (paired.isNotEmpty()) c.good else c.warn)
            Muted("Prochaine synchro automatique : " + (nextRun?.let(::fmt) ?: "non planifiée"))
            PrimaryButton(if (syncing) "Synchronisation…" else "Synchroniser maintenant", onSync, enabled = !syncing)
            var resend by remember { mutableStateOf(false) }
            SecondaryButton("Renvoyer tout l'historique (1 an)", { resend = true }, Modifier.fillMaxWidth(), enabled = !syncing)
            if (resend) androidx.compose.material3.AlertDialog(
                onDismissRequest = { resend = false },
                title = { Text("Renvoyer l'historique ?") },
                text = { Text("Le téléphone renvoie jusqu'à un an de mesures Health Connect au serveur. Utile après une " +
                    "restauration du serveur : rien n'est perdu ni dupliqué (les envois sont idempotents).") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    resend = false
                    prefs.lastSync = null; prefs.changesToken = null; prefs.calcVersion = 0 // tout relire, FC au repos comprise
                    onSync()
                }) { Text("Renvoyer") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { resend = false }) { Text("Annuler") } },
                containerColor = c.surface,
            )
            if (exempt) Muted("✓ Exemptée de l'optimisation de batterie : Android ne retardera pas la synchro.")
            else {
                Muted("Android peut retarder la synchro tant que l'app est soumise à l'optimisation de batterie.", color = c.warn)
                SecondaryButton("Exempter de l'optimisation de batterie", actions.requestBatteryExemption, Modifier.fillMaxWidth())
            }
            Muted(SAMSUNG_SLEEP_SETTING, 11.sp)
            SecondaryButton("Journal des synchros", actions.openDiagnostic, Modifier.fillMaxWidth())
        }

        // ---- données ----
        ParroCard(gap = 0.dp) {
            SectionLabel("DONNÉES")
            NavRow("Envoyer un export Samsung Health", ExportUpload.status(ctx).ifEmpty { "HRV, scores et historique : partage le zip vers l'app ou choisis-le ici" },
                actions.pickExportZip)
            Divider()
            NavRow("Autorisations Health Connect", perms) {
                if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                    actions.openHealthConnectInstall() else actions.requestHealthPermissions()
            }
            Divider()
            NavRow("Diagnostic", "journal des synchros, ce que les applis publient dans Health Connect", actions.openDiagnostic)
            Divider()
            NavRow("Journal des accès", "qui a lu ou écrit tes données, quand et d'où", actions.openAccessLog)
        }

        ServerCard(prefs, actions, loggedIn, { tick++ }, onLoginChanged)

        Muted("Parro Health ${BuildConfig.VERSION_NAME}", 11.sp, Modifier.fillMaxWidth().padding(top = 4.dp), color = c.faint)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, Modifier.padding(bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.c.muted,
        letterSpacing = 0.5.sp)
}

@Composable
private fun StatusCell(label: String, value: String, dot: androidx.compose.ui.graphics.Color?, modifier: Modifier) {
    Column(modifier) {
        Muted(label, 11.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dot?.let { Dot(it) }
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.c.text, maxLines = 1)
        }
    }
}

private fun dmy(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(iso)

/** Objectif de poids : plage (kg) et date visée ; la trajectoire vise le milieu de la plage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeightGoalDialog(low: Int, high: Int, date: String, onDismiss: () -> Unit, onRemove: () -> Unit,
                             onSave: (Int, Int, String) -> Unit) {
    val c = P.c
    var lo by remember { mutableIntStateOf(if (low > 0) low else 75) }
    var hi by remember { mutableIntStateOf(if (high > 0) high else 85) }
    var d by remember { mutableStateOf(date.ifEmpty { LocalDate.now().plusMonths(6).toString() }) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Objectif de poids") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GoalRow("Au moins", "bas de la plage") {
                    Stepper("$lo kg", c.body, { lo = (lo - 1).coerceAtLeast(Goals.WEIGHT_MIN) },
                        { lo = (lo + 1).coerceAtMost(hi) }, "bas de la plage")
                }
                GoalRow("Au plus", "haut de la plage") {
                    Stepper("$hi kg", c.body, { hi = (hi - 1).coerceAtLeast(lo) },
                        { hi = (hi + 1).coerceAtMost(Goals.WEIGHT_MAX) }, "haut de la plage")
                }
                GoalRow("Pour le", "date visée") {
                    TextButton(onClick = { picking = true }) { Text(dmy(d), color = c.body) }
                }
                Muted("La trajectoire part de ta pesée du jour du réglage et vise le milieu de la plage " +
                    "(${"%.1f".format((lo + hi) / 2.0).replace('.', ',').removeSuffix(",0")} kg).", 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(lo, hi, d) }) { Text("Enregistrer", color = c.body) } },
        dismissButton = {
            Row {
                if (low > 0) TextButton(onClick = onRemove) { Text("Retirer", color = c.heart) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
        containerColor = c.surface,
    )
    if (picking) {
        val zone = ZoneOffset.UTC
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(zone).toLocalDate().isAfter(LocalDate.now())
            })
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { d = Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
                picking = false
            }) { Text("OK", color = c.body) }
        }, dismissButton = { TextButton(onClick = { picking = false }) { Text("Annuler") } }) {
            DatePicker(state)
        }
    }
}

@Composable
private fun GoalRow(title: String, sub: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = P.c.text)
            Muted(sub)
        }
        control()
    }
}

/** Connexion au serveur : repliée par défaut (on n'y touche qu'une fois). */
@Composable
private fun ServerCard(prefs: Prefs, actions: SettingsActions, loggedIn: Boolean, changed: () -> Unit, onLoginChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    var open by remember { mutableStateOf(!prefs.isConfigured) }
    var url by remember { mutableStateOf(prefs.serverUrl.ifEmpty { "https://" }) }
    var token by remember { mutableStateOf(prefs.token) }
    ParroCard(gap = 10.dp) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Connexion au serveur", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Muted(if (loggedIn) "connectée · jeton chiffré par le Keystore" else if (prefs.isConfigured) "lecture non connectée" else "à configurer")
            }
            Text(if (open) "Masquer" else "Modifier", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.sleep)
        }
        if (open) {
            val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.sleep, unfocusedBorderColor = c.line,
                focusedContainerColor = c.inset, unfocusedContainerColor = c.inset)
            OutlinedTextField(url, { url = it }, label = { Text("URL du serveur") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            OutlinedTextField(token, { token = it }, label = { Text("Jeton d'ingestion") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            PrimaryButton("Enregistrer", {
                val u = url.trim()
                when {
                    !u.startsWith("https://") -> actions.toast("L'URL doit commencer par https://")
                    token.trim().length < 32 -> actions.toast("Le jeton doit faire au moins 32 caractères")
                    else -> {
                        val urlChanged = u.trimEnd('/') != prefs.serverUrl
                        prefs.serverUrl = u; prefs.token = token
                        if (urlChanged) { prefs.lastSync = null; OAuth.logout(ctx) }
                        SyncWorker.schedule(ctx)
                        actions.toast("Enregistré — synchro planifiée"); changed()
                    }
                }
            })
        }
        if (loggedIn) SecondaryButton("Se déconnecter (lecture)", { OAuth.logout(ctx); changed(); onLoginChanged() }, Modifier.fillMaxWidth())
        else PrimaryButton("Se connecter (mot de passe + TOTP)", { OAuth.start(ctx) }, enabled = prefs.serverUrl.startsWith("https://"))
    }
}
