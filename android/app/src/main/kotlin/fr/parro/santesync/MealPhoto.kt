package fr.parro.santesync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Photo d'un repas HORS collection de recettes (restaurant, plat improvisé…) : réduite et réencodée sur le
 * téléphone (aucune métadonnée, ni lieu ni appareil), envoyée signée au serveur, analysée par l'IA locale,
 * puis effacée côté serveur. Ici, on ne garde que l'identifiant de l'analyse en attente (24 h au plus).
 */
object MealPhoto {
    private const val MAX_SIDE = 1024
    private const val PREFS = "meal_photos"

    /** Fichier temporaire pour l'appareil photo (cache privé, partagé le temps de la prise de vue). */
    fun cameraUri(c: Context): Uri {
        val dir = File(c.cacheDir, "repas").apply { mkdirs() }
        val f = File(dir, "prise.jpg")
        return FileProvider.getUriForFile(c, "${c.packageName}.fiches", f)
    }

    fun clearCamera(c: Context) {
        File(c.cacheDir, "repas").listFiles()?.forEach { it.delete() }
    }

    /** JPEG de 1024 px au plus : l'orientation est appliquée, les métadonnées (EXIF, GPS) ne sont pas recopiées. */
    suspend fun prepare(c: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(w, h))
            dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /** Envoi signé (jeton d'écriture + clé matérielle du téléphone) ; renvoie l'identifiant de l'analyse. */
    suspend fun upload(c: Context, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val prefs = Prefs(c)
        DeviceKey.ensurePaired(c)
        val url = URL("${prefs.serverUrl}/api/v1/meal-photo")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("User-Agent", Sync.userAgent("photo"))
            DeviceKey.headers("POST", url, jpeg).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(jpeg.size)
            conn.outputStream.use { it.write(jpeg) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, text)
            JSONObject(text).getString("id").also { remember(c, it) }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun result(c: Context, id: String): JSONObject = Api.get(c, "meal-photo", mapOf("id" to id))

    // ---- analyses en attente (identifiants seulement) ----
    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(c: Context): List<Pair<String, Long>> {
        val a = runCatching { JSONArray(sp(c).getString("ids", "[]")) }.getOrElse { JSONArray() }
        val horizon = System.currentTimeMillis() - 24 * 3_600_000L // le serveur oublie au bout de 24 h
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("id") to it.getLong("at") }
            .filter { it.second > horizon }
    }

    private fun remember(c: Context, id: String) = save(c, pending(c) + (id to System.currentTimeMillis()))

    fun forget(c: Context, id: String) = save(c, pending(c).filter { it.first != id })

    private fun save(c: Context, list: List<Pair<String, Long>>) {
        val a = JSONArray()
        list.forEach { (id, at) -> a.put(JSONObject().put("id", id).put("at", at)) }
        sp(c).edit { putString("ids", a.toString()) }
    }
}
