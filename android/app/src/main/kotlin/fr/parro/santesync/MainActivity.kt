package fr.parro.santesync

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var perms: TextView
    private lateinit var status: TextView
    private lateinit var syncButton: Button
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        url = findViewById(R.id.server_url)
        token = findViewById(R.id.token)
        perms = findViewById(R.id.permissions)
        status = findViewById(R.id.status)
        syncButton = findViewById(R.id.sync_now)

        url.setText(prefs.serverUrl)
        token.setText(prefs.token)

        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { refresh() }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.authorize).setOnClickListener { authorize() }
        syncButton.setOnClickListener { syncNow() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun save() {
        val u = url.text.toString().trim()
        if (!u.startsWith("https://")) {
            Toast.makeText(this, R.string.need_https, Toast.LENGTH_LONG).show()
            return
        }
        if (token.text.toString().trim().length < 32) {
            Toast.makeText(this, R.string.need_token, Toast.LENGTH_LONG).show()
            return
        }
        val changedServer = u.trimEnd('/') != prefs.serverUrl
        prefs.serverUrl = u
        prefs.token = token.text.toString()
        if (changedServer) prefs.lastSync = null // nouveau serveur : import complet
        SyncWorker.schedule(this)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun authorize() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE ->
                requestPermissions.launch(HealthReader.wantedPermissions(HealthConnectClient.getOrCreate(this)))
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri())
            )
            else -> Toast.makeText(this, R.string.hc_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun syncNow() {
        syncButton.isEnabled = false
        status.setText(R.string.syncing)
        // Au premier plan : pas besoin de la permission d'arrière-plan, et pas de quota de fond.
        lifecycleScope.launch {
            Sync.run(applicationContext)
            syncButton.isEnabled = true
            refresh()
        }
    }

    private fun refresh() {
        status.text = prefs.lastStatus.ifEmpty { getString(R.string.never_synced) }
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            perms.setText(R.string.hc_unavailable)
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = try {
                client.permissionController.getGrantedPermissions()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                perms.text = e.message ?: e.javaClass.simpleName
                return@launch
            }
            val wanted = HealthReader.wantedPermissions(client)
            val readOk = HealthReader.READ_PERMISSIONS.count { it in granted }
            val bg = when {
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in wanted -> getString(R.string.feature_absent)
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted -> getString(R.string.yes)
                else -> getString(R.string.no)
            }
            val hist = when {
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in wanted -> getString(R.string.feature_absent)
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted -> getString(R.string.yes)
                else -> getString(R.string.no)
            }
            perms.text = getString(R.string.permissions_summary, readOk, HealthReader.READ_PERMISSIONS.size, bg, hist)
        }
    }
}
