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
    suspend fun get(context: Context, name: String, params: Map<String, String> = emptyMap()): JSONObject {
        val token = OAuth.accessToken(context) ?: throw NotLoggedInException()
        return try {
            call(context, name, params, token)
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            val fresh = OAuth.accessToken(context, forceRefresh = true) ?: throw NotLoggedInException()
            call(context, name, params, fresh)
        }
    }

    private suspend fun call(context: Context, name: String, params: Map<String, String>, token: String): JSONObject =
        withContext(Dispatchers.IO) {
            val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val conn = URL("${Prefs(context).serverUrl}/api/v1/$name" + if (q.isEmpty()) "" else "?$q")
                .openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("User-Agent", Sync.userAgent("lecture"))
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw HttpStatusException(code, text)
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
}
