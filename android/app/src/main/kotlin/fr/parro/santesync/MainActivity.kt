package fr.parro.santesync

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
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
    TRENDS("Tendances", Icons.Filled.DateRange),
    SETTINGS("Réglages", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private var resumeTick by mutableIntStateOf(0)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            resumeTick++
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
        )
        val firstTab = if (Prefs(this).isConfigured) Tab.HOME else Tab.SETTINGS
        setContent {
            ParroTheme {
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
                            Tab.TRENDS -> TrendsScreen(key, toLogin)
                            Tab.SETTINGS -> SettingsScreen(actions, resumeTick) { refresh++ }
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
