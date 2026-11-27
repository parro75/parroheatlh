package fr.parro.santesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Repas notés dans l'app : une recette de la collection (calories Ciqual calculées par le serveur, × portions)
 * ou une saisie libre. Gardés dans un fichier privé (utilisable hors ligne, 60 jours affichés) et envoyés au
 * serveur par /ingest (type « meal »), tout de suite si possible, sinon avec la synchro suivante.
 */
object Meals {
    val SLOTS = listOf("matin" to "Matin", "midi" to "Midi", "soir" to "Soir", "collation" to "Collation")
    val NUTRIENTS = listOf("kcal", "proteines", "glucides", "lipides", "fibres", "sucres", "sel")
    private const val KEEP_DAYS = 60L
    private val lock = Mutex()

    data class Meal(
        val id: String, val at: Instant, val slot: String, val source: String, val label: String,
        val recipe: String?, val portions: Double?, val values: Map<String, Double>,
        val deleted: Boolean = false, val pending: Boolean = true,
    ) {
        val kcal: Double get() = values["kcal"] ?: 0.0
    }

    /** Repas le plus probable selon l'heure (proposé par défaut). */
    fun slotFor(t: LocalTime): String = when {
        t.isBefore(LocalTime.of(10, 30)) -> "matin"
        t.isBefore(LocalTime.of(14, 30)) -> "midi"
        t.isBefore(LocalTime.of(17, 0)) -> "collation"
        t.isBefore(LocalTime.of(22, 0)) -> "soir"
        else -> "collation"
    }

    private fun file(c: Context) = File(c.filesDir, "meals.json")

    private fun load(c: Context): MutableList<Meal> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        val arr = runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val v = o.optJSONObject("values") ?: JSONObject()
            Meal(o.getString("id"), Instant.parse(o.getString("at")), o.getString("slot"), o.getString("source"),
                o.optString("label"), o.optString("recipe").takeIf { it.isNotEmpty() },
                o.optDouble("portions").takeIf { !it.isNaN() },
                v.keys().asSequence().associateWith { v.getDouble(it) }, o.optBoolean("deleted"), o.optBoolean("pending"))
        }.toMutableList()
    }

    private fun save(c: Context, list: List<Meal>) {
        val horizon = Instant.now().minus(Duration.ofDays(KEEP_DAYS))
        val arr = JSONArray()
        list.filter { it.pending || it.at.isAfter(horizon) }.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("at", m.at.toString()).put("slot", m.slot).put("source", m.source)
                .put("label", m.label).put("recipe", m.recipe ?: "").apply { m.portions?.let { put("portions", it) } }
                .put("values", JSONObject(m.values)).put("deleted", m.deleted).put("pending", m.pending))
        }
        val tmp = File(c.filesDir, "meals.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(c)) // écriture atomique
    }

    suspend fun list(c: Context): List<Meal> = lock.withLock {
        withContext(Dispatchers.IO) { load(c).filter { !it.deleted }.sortedByDescending { it.at } }
    }

    suspend fun add(c: Context, at: Instant, slot: String, source: String, label: String, recipe: String?,
                    portions: Double?, values: Map<String, Double>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val list = load(c)
            list += Meal(UUID.randomUUID().toString(), at, slot, source, label.trim().take(200), recipe, portions,
                values.filterKeys { it in NUTRIENTS }.mapValues { (_, v) -> Math.round(v * 10) / 10.0 })
            save(c, list)
        }
    }

    suspend fun delete(c: Context, id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            save(c, load(c).map { if (it.id == id) it.copy(deleted = true, pending = true) else it })
        }
    }

    suspend fun pending(c: Context): Pair<JSONArray, List<String>> = lock.withLock {
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            val ids = mutableListOf<String>()
            load(c).filter { it.pending }.forEach { m ->
                val off = ZoneId.systemDefault().rules.getOffset(m.at)
                val data = if (m.deleted) JSONObject().put("deleted", true) else JSONObject()
                    .put("slot", m.slot).put("source", m.source).put("label", m.label).apply {
                        m.recipe?.let { put("recipe", it) }
                        m.portions?.let { put("portions", it) }
                        m.values.forEach { (k, v) -> put(k, v) }
                    }
                arr.put(JSONObject()
                    .put("type", "meal").put("id", "meal:${m.id}")
                    .put("start", OffsetDateTime.ofInstant(m.at, off).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("zone_offset", off.id.let { if (it == "Z") "+00:00" else it })
                    .put("data", data))
                ids += m.id
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

    /** Envoi immédiat ; en cas d'échec, les repas partiront avec la synchro suivante. */
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
