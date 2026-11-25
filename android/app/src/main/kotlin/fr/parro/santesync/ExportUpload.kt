package fr.parro.santesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Envoi d'un export Samsung Health (zip) au serveur, par morceaux (le serveur l'importe ensuite seul).
 * Le zip est d'abord copié dans le stockage privé de l'appli (le droit de lecture d'un partage est
 * temporaire), puis envoyé par un travail au premier plan, et la copie est supprimée à la fin.
 */
object ExportUpload {
    private const val NAME = "parro-export-upload"
    const val CHANNEL = "parro_import"

    fun localFile(context: Context) = File(context.filesDir, "export-a-envoyer.zip")

    /** Copie le zip choisi ou partagé, puis lance l'envoi. Renvoie null si OK, sinon un message. */
    suspend fun enqueue(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val dst = localFile(context)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(4)
                val n = input.read(head)
                if (n < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return@withContext "Ce fichier n'est pas un zip"
                dst.outputStream().use { out -> out.write(head, 0, n); input.copyTo(out, 1 shl 20) }
            } ?: return@withContext "Fichier illisible"
        } catch (e: IOException) {
            dst.delete(); return@withContext "Copie impossible : ${e.message}"
        }
        setStatus(context, "En attente d'envoi (${dst.length() / (1 shl 20)} Mio)…")
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ExportUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
        null
    }

    fun status(context: Context): String =
        context.getSharedPreferences("parro_export", Context.MODE_PRIVATE).getString("status", "") ?: ""

    fun setStatus(context: Context, s: String) =
        context.getSharedPreferences("parro_export", Context.MODE_PRIVATE).edit { putString("status", s) }
}

class ExportUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val prefs = Prefs(context)

    private fun foreground(text: String, progress: Int?): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ExportUpload.CHANNEL, "Import d'export", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, ExportUpload.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Parro Health — import de l'export Samsung")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (progress != null) setProgress(100, progress, false) else setProgress(0, 0, true) }
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(42, n)
    }

    private suspend fun say(text: String, progress: Int? = null) {
        ExportUpload.setStatus(applicationContext, text)
        runCatching { setForeground(foreground(text, progress)) }
    }

    override suspend fun doWork(): Result {
        val file = ExportUpload.localFile(applicationContext)
        if (!file.exists()) return Result.failure()
        return try {
            say("Calcul de l'empreinte…")
            val sha = withContext(Dispatchers.IO) {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            val start = call("POST", "/ingest-export/start",
                JSONObject().put("size", file.length()).put("sha256", sha).toString().toByteArray(), "application/json")
            val id = start.getString("id")
            val chunk = start.getInt("chunk_size")
            val total = ((file.length() + chunk - 1) / chunk).toInt()
            withContext(Dispatchers.IO) {
                file.inputStream().use { s ->
                    val buf = ByteArray(chunk)
                    for (i in 0 until total) {
                        var n = 0
                        while (n < chunk) { val r = s.read(buf, n, chunk - n); if (r < 0) break; n += r }
                        val part = buf.copyOf(n)
                        var attempt = 0
                        while (true) {
                            try { call("PUT", "/ingest-export/$id/$i", part, "application/octet-stream"); break }
                            catch (e: IOException) { if (++attempt >= 3) throw e; delay(5_000L * attempt) }
                        }
                        say("Envoi : morceau ${i + 1}/$total", (i + 1) * 100 / total)
                    }
                }
            }
            call("POST", "/ingest-export/$id/finish", ByteArray(0), "application/json")
            say("Import en cours sur le serveur…")
            var state = JSONObject()
            for (k in 0 until 180) { // jusqu'à 15 min
                delay(5_000)
                state = call("GET", "/ingest-export/$id", null, null)
                if (state.optString("state") in setOf("done", "error")) break
            }
            val msg = when (state.optString("state")) {
                "done" -> {
                    val r = state.getJSONObject("result")
                    val written = r.keys().asSequence().sumOf { r.getJSONObject(it).optInt("written") }
                    "Import terminé : $written nouveaux enregistrements"
                }
                "error" -> "Import refusé : ${state.optString("error")}"
                else -> "Import toujours en cours sur le serveur (vérifie plus tard)"
            }
            ExportUpload.setStatus(applicationContext, msg)
            Result.success()
        } catch (e: Exception) {
            ExportUpload.setStatus(applicationContext, "Échec de l'envoi : ${e.message}")
            Result.failure()
        } finally {
            file.delete()
        }
    }

    private suspend fun call(method: String, path: String, body: ByteArray?, type: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(prefs.serverUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
                conn.setRequestProperty("User-Agent", Sync.userAgent("import"))
                if (body != null) {
                    conn.doOutput = true
                    type?.let { conn.setRequestProperty("Content-Type", it) }
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw HttpStatusException(code, text)
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
}
