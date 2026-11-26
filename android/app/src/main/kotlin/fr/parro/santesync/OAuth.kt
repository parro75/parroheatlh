package fr.parro.santesync

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Connexion de l'app au serveur (lecture des données) : OAuth 2.1, client PUBLIC « parro-health-android »,
 * PKCE S256, mot de passe + TOTP saisis sur la page du serveur dans un onglet de navigateur.
 */
object OAuth {
    const val CLIENT_ID = "parro-health-android"
    const val REDIRECT_URI = "fr.parro.health://oauth/callback"
    const val SCOPE = "health:read"
    private val refreshLock = Mutex()

    private fun rand(n: Int): String {
        val b = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Ouvre la page de connexion du serveur ; le retour arrive dans [OAuthRedirectActivity]. */
    fun start(context: Context) {
        val prefs = Prefs(context)
        val verifier = rand(48)
        val state = rand(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        context.getSharedPreferences("parro_oauth", Context.MODE_PRIVATE).edit {
            putString("verifier", verifier); putString("state", state)
        }
        val url = "${prefs.serverUrl}/authorize?response_type=code&client_id=${enc(CLIENT_ID)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}&code_challenge=$challenge&code_challenge_method=S256" +
            "&state=$state&scope=${enc(SCOPE)}"
        LockGuard.leaving()
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    }

    /** Échange le code reçu contre des jetons. Renvoie null si tout va bien, sinon un message d'erreur. */
    suspend fun finish(context: Context, uri: Uri): String? {
        val sp = context.getSharedPreferences("parro_oauth", Context.MODE_PRIVATE)
        val verifier = sp.getString("verifier", null)
        val expected = sp.getString("state", null)
        sp.edit { clear() }
        uri.getQueryParameter("error")?.let { return "Connexion refusée : $it" }
        val code = uri.getQueryParameter("code") ?: return "Réponse sans code"
        if (verifier == null || expected == null || uri.getQueryParameter("state") != expected)
            return "Réponse inattendue (state) : recommence la connexion"
        return try {
            store(context, token(context, "grant_type=authorization_code&code=${enc(code)}" +
                "&redirect_uri=${enc(REDIRECT_URI)}&client_id=${enc(CLIENT_ID)}&code_verifier=${enc(verifier)}"))
            null
        } catch (e: IOException) {
            "Échec de l'échange : ${e.message}"
        }
    }

    /** Jeton d'accès valide (rafraîchi si besoin), ou null si une nouvelle connexion est nécessaire. */
    suspend fun accessToken(context: Context, forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val ts = TokenStore(context)
        val access = ts.accessToken
        if (!forceRefresh && access != null && System.currentTimeMillis() < ts.accessExpiresAt - 60_000) return access
        val refresh = ts.refreshToken ?: return null
        try {
            store(context, token(context, "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(CLIENT_ID)}"))
            ts.accessToken
        } catch (e: HttpStatusException) {
            if (e.code == 400 || e.code == 401) ts.clear() // jeton révoqué ou expiré : reconnexion
            null
        }
    }

    fun logout(context: Context) = TokenStore(context).clear()

    private fun store(context: Context, json: JSONObject) {
        val ts = TokenStore(context)
        ts.accessToken = json.getString("access_token")
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { ts.refreshToken = it }
        ts.accessExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
    }

    private suspend fun token(context: Context, form: String): JSONObject = withContext(Dispatchers.IO) {
        val body = form.toByteArray()
        val conn = URL("${Prefs(context).serverUrl}/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", Sync.userAgent("auth"))
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, text)
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
