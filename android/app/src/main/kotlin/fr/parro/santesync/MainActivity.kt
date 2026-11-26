package fr.parro.santesync

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import fr.parro.santesync.ui.ActivityDetail
import fr.parro.santesync.ui.BodyDetail
import fr.parro.santesync.ui.Detail
import fr.parro.santesync.ui.DiagnosticScreen
import fr.parro.santesync.ui.HeartDetail
import fr.parro.santesync.ui.JournalScreen
import fr.parro.santesync.ui.Num
import fr.parro.santesync.ui.P
import fr.parro.santesync.ui.ParroIcons
import fr.parro.santesync.ui.ParroTheme
import fr.parro.santesync.ui.PrimaryButton
import fr.parro.santesync.ui.ProfileScreen
import fr.parro.santesync.ui.SettingsActions
import fr.parro.santesync.ui.SleepDetail
import fr.parro.santesync.ui.TodayScreen
import fr.parro.santesync.ui.TrendsScreen
import kotlinx.coroutines.launch

/** Quatre onglets (26/11/2026) ; Sommeil, Cœur, Activité et Corps sont des pages de détail ouvertes depuis l'accueil. */
private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Aujourd'hui", ParroIcons.today),
    TRENDS("Tendances", ParroIcons.trends),
    JOURNAL("Journal", ParroIcons.journal),
    PROFILE("Profil", ParroIcons.profile),
}

class MainActivity : FragmentActivity() {
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var pickZip: ActivityResultLauncher<Array<String>>
    private lateinit var askNotifications: ActivityResultLauncher<String>
    private var resumeTick by mutableIntStateOf(0)
    private var refresh by mutableIntStateOf(0)
    private var syncing by mutableStateOf(false)
    private var detail by mutableStateOf<Detail?>(null)
    private var detailArg by mutableStateOf<String?>(null)

    /** Verrouillage : l'interface est masquée tant que la biométrie n'a pas réussi. */
    private var locked by mutableStateOf(false)
    private var backgroundedAt = 0L

    private fun authenticate(title: String, onResult: (Boolean) -> Unit) {
        val allowed = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle("Données de santé").setAllowedAuthenticators(allowed).build())
    }

    private fun unlock() = authenticate("Déverrouiller Parro Health") { ok -> if (ok) locked = false }

    /** Synchro manuelle au premier plan (tirer l'accueil vers le bas, pastille, Profil) puis rechargement des écrans. */
    private fun syncNow() {
        if (syncing) return
        syncing = true
        lifecycleScope.launch {
            try {
                Sync.run(applicationContext, Trigger.MANUAL)
            } finally {
                syncing = false
                refresh++
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Verrouillage actif : pas de miniature dans les applis récentes (les captures d'écran restent permises).
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!Prefs(this).lockEnabled)
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (Prefs(this).lockEnabled && (backgroundedAt == 0L || away > 60_000)) {
            locked = true
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            resumeTick++
        }
        askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        WeighReminder.schedule(this, WeighReminder.Policy.AT_START) // sans effet si le rappel est désactivé
        if (savedInstanceState == null) MedicalPdf.clear(this) // la fiche PDF ne survit pas à un redémarrage de l'app
        // Les rapports sont annoncés par l'appli elle-même : demander l'autorisation dès l'ouverture.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        pickZip = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val err = ExportUpload.enqueue(applicationContext, uri)
                    Toast.makeText(this@MainActivity, err ?: "Envoi au serveur en cours (voir la notification)", Toast.LENGTH_LONG).show()
                    resumeTick++
                }
            }
        }
        val actions = SettingsActions(
            requestHealthPermissions = {
                requestPermissions.launch(HealthReader.wantedPermissions(HealthConnectClient.getOrCreate(this)))
            },
            requestBatteryExemption = ::requestBatteryExemption,
            openHealthConnectInstall = {
                startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri()))
            },
            toast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            openDiagnostic = { detail = Detail.DIAGNOSTIC },
            confirmBiometric = { cb -> authenticate("Activer le verrouillage", cb) },
            pickExportZip = {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
        )
        // Planifie les synchros (horaire + matin) à chaque ouverture : rattrape les installations existantes.
        if (Prefs(this).isConfigured) SyncWorker.schedule(this)
        val firstTab = if (Prefs(this).isConfigured) Tab.TODAY else Tab.PROFILE
        setContent {
            ParroTheme {
                if (locked) {
                    Column(Modifier.fillMaxSize().background(P.c.bg).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(ParroIcons.heart, contentDescription = null, tint = P.c.heart)
                        Num("Parro Health est verrouillée", 20.sp)
                        PrimaryButton("Déverrouiller", ::unlock)
                    }
                    return@ParroTheme
                }
                var tab by rememberSaveable { mutableIntStateOf(firstTab.ordinal) }
                BackHandler(enabled = detail != null) { detail = null }
                val toLogin = { OAuth.start(this) }
                val close = { detail = null }
                Scaffold(
                    containerColor = P.c.bg,
                    bottomBar = {
                        AnimatedVisibility(detail == null, enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut()) {
                            NavigationBar(containerColor = P.c.bg, tonalElevation = 0.dp) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = t.ordinal == tab, onClick = { tab = t.ordinal },
                                        icon = { Icon(t.icon, contentDescription = null) },
                                        label = { Text(t.title, fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = P.c.sleep, selectedTextColor = P.c.text, indicatorColor = P.c.sleepSoft,
                                            unselectedIconColor = P.c.muted, unselectedTextColor = P.c.muted),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize().background(P.c.bg)) {
                        val key = refresh + resumeTick
                        AnimatedContent(
                            targetState = detail to tab,
                            transitionSpec = {
                                when {
                                    targetState.first != null && initialState.first == null ->
                                        (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                                    targetState.first == null && initialState.first != null ->
                                        fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(200)))
                                    else -> fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                                }
                            },
                            label = "navigation",
                        ) { (d, t) ->
                            when (d) {
                                Detail.SLEEP -> SleepDetail(key, toLogin, close)
                                Detail.HEART -> HeartDetail(key, toLogin, close, detailArg)
                                Detail.ACTIVITY -> ActivityDetail(key, toLogin, close)
                                Detail.BODY -> BodyDetail(key, toLogin, close)
                                Detail.DIAGNOSTIC -> DiagnosticScreen(resumeTick,
                                    onRequestExtra = { requestPermissions.launch(Diagnostic.EXTRA_PERMISSIONS) }, onBack = close)
                                null -> when (Tab.entries[t]) {
                                    Tab.TODAY -> TodayScreen(key, syncing, ::syncNow, toLogin) { dd, arg -> detailArg = arg; detail = dd }
                                    Tab.TRENDS -> TrendsScreen(key, toLogin)
                                    Tab.JOURNAL -> JournalScreen()
                                    Tab.PROFILE -> ProfileScreen(actions, resumeTick, syncing, ::syncNow, toLogin) { refresh++ }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // retour d'une autorisation, de la connexion ou des réglages système
    }

    /** Boîte de dialogue système « Ignorer les optimisations de batterie ? » pour cette seule appli. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }
}
