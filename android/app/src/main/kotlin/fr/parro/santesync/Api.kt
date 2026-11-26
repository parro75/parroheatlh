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

    /** POST JSON authentifié par le jeton OAuth (appairage de l'appareil). */
    suspend fun post(context: Context, name: String, json: JSONObject): JSONObject =
        JSONObject(String(fetch(context, name, emptyMap(), json.toString().toByteArray(Charsets.UTF_8)), Charsets.UTF_8))

    private suspend fun fetch(context: Context, path: String, params: Map<String, String>, body: ByteArray? = null): ByteArray {
        val token = OAuth.accessToken(context) ?: throw NotLoggedInException()
        return try {
            call(context, path, params, token, body)
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            val fresh = OAuth.accessToken(context, forceRefresh = true) ?: throw NotLoggedInException()
            call(context, path, params, fresh, body)
        }
    }

    private suspend fun call(context: Context, path: String, params: Map<String, String>, token: String,
                             body: ByteArray? = null): ByteArray =
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
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
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
