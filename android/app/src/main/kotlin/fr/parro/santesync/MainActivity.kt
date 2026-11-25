package fr.parro.santesync

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import fr.parro.santesync.ui.DiagnosticScreen
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import fr.parro.santesync.ui.ActivityScreen
import fr.parro.santesync.ui.HeartScreen
import fr.parro.santesync.ui.HomeScreen
import fr.parro.santesync.ui.ParroTheme
import fr.parro.santesync.ui.SettingsActions
import fr.parro.santesync.ui.SettingsScreen
import fr.parro.santesync.ui.SleepScreen
import fr.parro.santesync.ui.TrendsScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Accueil", Icons.Filled.Home),
    SLEEP("Sommeil", Icons.Filled.Star),
    HEART("Cœur", Icons.Filled.Favorite),
    ACTIVITY("Activité", Icons.Filled.Place),
    BODY("Corps", Icons.Filled.Person),
    TRENDS("Tendances", Icons.Filled.DateRange),
    SETTINGS("Réglages", Icons.Filled.Settings),
}

class MainActivity : FragmentActivity() {
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var pickZip: ActivityResultLauncher<Array<String>>
    private lateinit var askNotifications: ActivityResultLauncher<String>
    private var resumeTick by mutableIntStateOf(0)
    private var showDiagnostic by mutableStateOf(false)

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

    override fun onStart() {
        super.onStart()
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            resumeTick++
        }
        askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        WeighReminder.schedule(this, WeighReminder.Policy.AT_START) // sans effet si le rappel est désactivé
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
            openDiagnostic = { showDiagnostic = true },
            confirmBiometric = { cb -> authenticate("Activer le verrouillage", cb) },
            pickExportZip = {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
        )
        // Planifie les synchros (horaire + matin) à chaque ouverture : rattrape les installations existantes.
        if (Prefs(this).isConfigured) SyncWorker.schedule(this)
        val firstTab = if (Prefs(this).isConfigured) Tab.HOME else Tab.SETTINGS
        setContent {
            ParroTheme {
                if (locked) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Parro Health est verrouillée", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = ::unlock) { Text("Déverrouiller") }
                    }
                    return@ParroTheme
                }
                var tab by rememberSaveable { mutableIntStateOf(firstTab.ordinal) }
                var refresh by rememberSaveable { mutableIntStateOf(0) }
                val current = Tab.entries[tab]
                val toLogin = { OAuth.start(this) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (current == Tab.HOME) getString(R.string.app_name) else current.title) },
                            actions = {
                                if (current != Tab.SETTINGS) IconButton(onClick = { refresh++ }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                                }
                                IconButton(onClick = { tab = Tab.SETTINGS.ordinal }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.filter { it != Tab.SETTINGS }.forEach { t ->
                                NavigationBarItem(
                                    selected = t == current, onClick = { tab = t.ordinal },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.title) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        val key = refresh + resumeTick
                        when (current) {
                            Tab.HOME -> HomeScreen(key, toLogin)
                            Tab.SLEEP -> SleepScreen(key, toLogin)
                            Tab.HEART -> HeartScreen(key, toLogin)
                            Tab.ACTIVITY -> ActivityScreen(key, toLogin)
                            Tab.BODY -> fr.parro.santesync.ui.BodyScreen(key, toLogin)
                            Tab.TRENDS -> TrendsScreen(key, toLogin)
                            Tab.SETTINGS -> if (showDiagnostic) DiagnosticScreen(resumeTick,
                                onRequestExtra = { requestPermissions.launch(Diagnostic.EXTRA_PERMISSIONS) },
                                onBack = { showDiagnostic = false })
                            else SettingsScreen(actions, resumeTick) { refresh++ }
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
