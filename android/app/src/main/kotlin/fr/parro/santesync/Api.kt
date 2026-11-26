package fr.parro.santesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NotLoggedInException : Exception("Connexion nécessaire")

/** Lecture des données sur le serveur : GET /api/v1/<nom>, jeton OAuth (portée health:read). */
object Api {
    suspend fun get(context: Context, name: String, params: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(String(fetch(context, name, params), Charsets.UTF_8))

    /** Réponse brute (fiche PDF) : même jeton, même renouvellement sur 401. */
    suspend fun bytes(context: Context, path: String, params: Map<String, String> = emptyMap()): ByteArray =
        fetch(context, path, params)

    private suspend fun fetch(context: Context, path: String, params: Map<String, String>): ByteArray {
        val token = OAuth.accessToken(context) ?: throw NotLoggedInException()
        return try {
            call(context, path, params, token)
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            val fresh = OAuth.accessToken(context, forceRefresh = true) ?: throw NotLoggedInException()
            call(context, path, params, fresh)
        }
    }

    private suspend fun call(context: Context, path: String, params: Map<String, String>, token: String): ByteArray =
        withContext(Dispatchers.IO) {
            val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val conn = URL("${Prefs(context).serverUrl}/api/v1/$path" + if (q.isEmpty()) "" else "?$q")
                .openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("User-Agent", Sync.userAgent("lecture"))
                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw HttpStatusException(code, text)
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
}
