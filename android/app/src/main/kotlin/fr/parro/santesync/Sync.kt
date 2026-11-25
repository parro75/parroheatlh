package fr.parro.santesync

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed class SyncResult(val message: String) {
    class Ok(message: String) : SyncResult(message)
    /** Erreur passagère (réseau, serveur 5xx) : WorkManager réessaiera. */
    class Retry(message: String) : SyncResult(message)
    /** Erreur de configuration (jeton, permission) : réessayer ne sert à rien. */
    class Fatal(message: String) : SyncResult(message)
}

class HttpStatusException(val code: Int, body: String) : IOException("HTTP $code : ${body.take(200)}")

/** Origine d'une synchro : affichée dans l'app ET envoyée au serveur (en-tête User-Agent). */
enum class Trigger(val label: String) { AUTO("auto"), MORNING("auto-matin"), MANUAL("manuel") }

object Sync {
    private val OVERLAP: Duration = Duration.ofDays(2)
    private val FIRST_IMPORT: Duration = Duration.ofDays(30)
    private val FIRST_IMPORT_HISTORY: Duration = Duration.ofDays(365)
    private const val CHUNK = 1000

    /** Version du calcul local (FC au repos) : l'incrémenter relance un rattrapage complet. */
    private const val CALC_VERSION = 1

    fun userAgent(kind: String) =
        "ParroHealth/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; $kind)"

    suspend fun run(context: Context, trigger: Trigger): SyncResult {
        val prefs = Prefs(context)
        val result = try {
            doRun(context, prefs, trigger)
        } catch (e: SecurityException) {
            SyncResult.Fatal("Permission Health Connect refusée : ${e.message}")
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403 || e.code == 404 || e.code == 422) SyncResult.Fatal(e.message ?: "HTTP ${e.code}")
            else SyncResult.Retry(e.message ?: "HTTP ${e.code}")
        } catch (e: IOException) {
            SyncResult.Retry("Réseau : ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Quota Health Connect, RemoteException, HealthConnectException… : passager le plus souvent.
            SyncResult.Retry("${e.javaClass.simpleName} : ${e.message}")
        }
        val stamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .format(Instant.now().atZone(ZoneId.systemDefault()))
        prefs.lastStatus = "$stamp (${trigger.label}) — ${result.message}"
        return result
    }

    private suspend fun doRun(context: Context, prefs: Prefs, trigger: Trigger): SyncResult {
        if (!prefs.isConfigured) return SyncResult.Fatal("Serveur non configuré (URL https:// et jeton de 32+ caractères)")
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            return SyncResult.Fatal("Health Connect indisponible sur cet appareil")
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.none { it in HealthReader.READ_PERMISSIONS })
            return SyncResult.Fatal("Aucune permission de lecture accordée")

        val now = Instant.now()
        val first = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) FIRST_IMPORT_HISTORY else FIRST_IMPORT
        // Nouveau calcul local (ou première synchro) : on relit toute la profondeur disponible.
        val backfill = prefs.calcVersion < CALC_VERSION
        val from = if (backfill) now.minus(first) else prefs.lastSync?.minus(OVERLAP) ?: now.minus(first)

        val records = HealthReader(client).read(from, now, granted)
        val (journal, journalIds) = Journal.pending(context)
        for (k in 0 until journal.length()) records.put(journal.get(k))
        var sent = 0
        var i = 0
        while (i < records.length()) {
            val chunk = JSONArray()
            for (j in i until minOf(i + CHUNK, records.length())) chunk.put(records.get(j))
            sent += postRecords(prefs, chunk, trigger)
            i += CHUNK
        }
        if (journalIds.isNotEmpty()) Journal.markSent(context, journalIds)
        prefs.lastSync = now
        if (backfill) prefs.calcVersion = CALC_VERSION
        val missing = HealthReader.READ_PERMISSIONS.count { it !in granted }
        val note = if (missing > 0) " ($missing type(s) non autorisé(s))" else ""
        return SyncResult.Ok("$sent enregistrements envoyés depuis le ${from.atZone(ZoneId.systemDefault()).toLocalDate()}$note")
    }

    suspend fun postRecords(prefs: Prefs, records: JSONArray, trigger: Trigger): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().put("records", records).toString().toByteArray(Charsets.UTF_8)
        val conn = URL("${prefs.serverUrl}/ingest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", userAgent(trigger.label))
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, text)
            JSONObject(text).optInt("accepted", records.length())
        } finally {
            conn.disconnect()
        }
    }
}
