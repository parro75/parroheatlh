package fr.parro.santesync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reçoit fr.parro.health://oauth/callback?code=…&state=… après la connexion sur le serveur. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish(); return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val error = OAuth.finish(applicationContext, uri)
            if (error == null) DeviceKey.ensurePaired(applicationContext) // appairage juste après la connexion
            Toast.makeText(applicationContext, error ?: getString(R.string.login_ok), Toast.LENGTH_LONG).show()
            startActivity(Intent(this@OAuthRedirectActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
