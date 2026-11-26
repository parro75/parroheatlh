package fr.parro.santesync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cible de partage : « Partager » un zip d'export Samsung Health vers Parro Health.
 * Cette activité est exportée (toute appli peut lui envoyer un fichier) : rien ne part sans une
 * confirmation explicite, qui montre le nom et la taille du fichier (26/11/2026). Sans elle, une appli
 * malveillante pourrait faire importer un faux export dans la base.
 */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null || !Prefs(this).isConfigured) {
            Toast.makeText(this, if (uri == null) "Aucun fichier reçu" else "Configure d'abord le serveur dans Parro Health",
                Toast.LENGTH_LONG).show()
            finish(); return
        }
        var name = "fichier sans nom"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val from = referrer?.host ?: callingPackage
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Envoyer cet export au serveur ?")
            .setMessage("« $name »" + (if (size >= 0) " (${Formatter.formatShortFileSize(this, size)})" else "") +
                (from?.let { "\nPartagé par : $it" } ?: "") +
                "\n\nIl sera importé dans ta base. N'accepte que si tu viens de partager toi-même ton export Samsung Health.")
            .setPositiveButton("Envoyer") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    val err = ExportUpload.enqueue(applicationContext, uri)
                    Toast.makeText(applicationContext, err ?: "Export reçu : envoi au serveur en cours (voir la notification)",
                        Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNegativeButton("Annuler") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
