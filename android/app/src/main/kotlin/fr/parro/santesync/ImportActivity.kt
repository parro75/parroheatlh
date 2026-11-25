package fr.parro.santesync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Cible de partage : « Partager » un zip d'export Samsung Health vers Parro Health. */
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
        CoroutineScope(Dispatchers.Main).launch {
            val err = ExportUpload.enqueue(applicationContext, uri)
            Toast.makeText(applicationContext, err ?: "Export reçu : envoi au serveur en cours (voir la notification)",
                Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
