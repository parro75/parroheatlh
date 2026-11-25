package fr.parro.santesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Journal d'événements noté dans l'appli (étiquettes + note). Gardé dans un fichier privé, envoyé au serveur
 * par /ingest (type « journal ») tout de suite si possible, sinon avec la synchro suivante. Une suppression
 * est envoyée comme une mise à jour (data.deleted = true).
 */
object Journal {
    val TAGS = listOf(
        "alcool" to "Alcool", "cafe_tardif" to "Café tardif", "malade" to "Malade", "voyage" to "Voyage",
        "seance_intense" to "Séance intense", "stress" to "Stress", "repas_tardif" to "Repas tardif",
        "ecrans_tard" to "Écrans tard", "medicament" to "Médicament", "autre" to "Autre",
    )
    private val lock = Mutex()

    data class Entry(val id: String, val at: Instant, val tags: List<String>, val note: String?,
                     val deleted: Boolean = false, val pending: Boolean = true)

    private fun file(c: Context) = File(c.filesDir, "journal.json")

    private fun load(c: Context): MutableList<Entry> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Entry(o.getString("id"), Instant.parse(o.getString("at")),
                (0 until o.getJSONArray("tags").length()).map { o.getJSONArray("tags").getString(it) },
                o.optString("note").takeIf { it.isNotEmpty() }, o.optBoolean("deleted"), o.optBoolean("pending"))
        }.toMutableList()
    }

    private fun save(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("at", e.at.toString()).put("tags", JSONArray(e.tags))
                .put("note", e.note ?: "").put("deleted", e.deleted).put("pending", e.pending))
        }
        val tmp = File(c.filesDir, "journal.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(c)) // écriture atomique
    }

    suspend fun entries(c: Context): List<Entry> = lock.withLock {
        withContext(Dispatchers.IO) { load(c).filter { !it.deleted }.sortedByDescending { it.at } }
    }

    suspend fun add(c: Context, at: Instant, tags: List<String>, note: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            val list = load(c)
            list += Entry(UUID.randomUUID().toString(), at, tags, note?.trim()?.takeIf { it.isNotEmpty() }?.take(500))
            save(c, list)
        }
    }

    suspend fun delete(c: Context, id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            save(c, load(c).map { if (it.id == id) it.copy(deleted = true, pending = true) else it })
        }
    }

    /** Enregistrements à envoyer (format /ingest) et leurs identifiants. */
    suspend fun pending(c: Context): Pair<JSONArray, List<String>> = lock.withLock {
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            val ids = mutableListOf<String>()
            load(c).filter { it.pending }.forEach { e ->
                val off = ZoneId.systemDefault().rules.getOffset(e.at)
                arr.put(JSONObject()
                    .put("type", "journal").put("id", "journal:${e.id}")
                    .put("start", OffsetDateTime.ofInstant(e.at, off).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("zone_offset", off.id.let { if (it == "Z") "+00:00" else it })
                    .put("data", JSONObject().put("tags", JSONArray(e.tags)).apply {
                        e.note?.let { put("note", it) }
                        if (e.deleted) put("deleted", true)
                    }))
                ids += e.id
            }
            arr to ids
        }
    }

    suspend fun markSent(c: Context, ids: List<String>) = lock.withLock {
        withContext(Dispatchers.IO) {
            save(c, load(c).map { if (it.id in ids) it.copy(pending = false) else it }
                .filter { !(it.deleted && !it.pending) })
        }
    }

    /** Envoi immédiat des entrées en attente ; en cas d'échec, elles partiront avec la synchro suivante. */
    suspend fun flush(c: Context): Boolean {
        val (records, ids) = pending(c)
        if (ids.isEmpty()) return true
        return try {
            Sync.postRecords(Prefs(c), records, Trigger.MANUAL)
            markSent(c, ids); true
        } catch (e: Exception) {
            false
        }
    }
}
