package fr.parro.santesync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
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
 * Photos d'un repas HORS collection de recettes (restaurant, plat improvisé…) : 1 à 4 photos (angles, ou
 * entrée/plat/dessert) et une description facultative. Réduites et réencodées sur le téléphone (aucune
 * métadonnée, ni lieu ni appareil), envoyées signées au serveur, analysées par l'IA locale, puis effacées côté
 * serveur avec la description. Ici, on ne garde que l'identifiant de l'analyse en attente (24 h au plus).
 */
object MealPhoto {
    const val MAX_PHOTOS = 4
    const val MAX_DESCRIPTION = 300
    private const val PUNCT = " .,;:!?'’()-/+%&°½¼¾×"

    /**
     * Filtre de frappe de la description : lettres, chiffres et ponctuation de cuisine seulement (pas de < > { } [ ]
     * ` " # ni caractère invisible), sur une ligne. Le serveur applique le même filtre et refuse en plus une
     * description qui s'adresse à l'IA (« ignore les consignes… ») ou contient une adresse web.
     */
    fun cleanDescription(text: String): String =
        text.map { if (it.isLetterOrDigit() || it in PUNCT) it else ' ' }.joinToString("")
            .replace(Regex("  +"), " ").trimStart().take(MAX_DESCRIPTION)
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

    /** Vignette pour l'écran d'ajout (1/8 de la photo préparée, soit 128 px au plus). */
    fun thumbnail(jpeg: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })

    /** Envoi signé (jeton d'écriture + clé matérielle du téléphone) ; renvoie l'identifiant de l'analyse. */
    suspend fun upload(c: Context, jpegs: List<ByteArray>, description: String): String = withContext(Dispatchers.IO) {
        val images = JSONArray().apply { jpegs.take(MAX_PHOTOS).forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) } }
        val body = JSONObject().put("description", cleanDescription(description).trim()).put("images", images)
            .toString().toByteArray(Charsets.UTF_8)
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
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", Sync.userAgent("photo"))
            DeviceKey.headers("POST", url, body).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            // Description refusée par le serveur : son message explique quoi corriger.
            if (code == 422) throw IllegalArgumentException(runCatching { JSONObject(text).getString("detail") }.getOrDefault(text))
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
