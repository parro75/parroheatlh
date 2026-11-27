package fr.parro.santesync

import android.content.Context
import android.os.PowerManager
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Journal des exécutions de la synchro (les 50 dernières) et état de l'exemption de batterie.
 *
 * Sert à trancher sans deviner, quand une synchro manque : la tâche n'a jamais démarré (Android l'a
 * retenue), ou elle a démarré et échoué. Rien de médical : heure, origine, résultat, exemption.
 */
object SyncLog {
    private const val MAX = 50
    private const val FILE = "synclog"

    data class Run(
        val at: Long,
        val trigger: String,
        /** Heure à laquelle Android aurait dû lancer ce passage (0 = inconnue). */
        val planned: Long,
        /** ok, nouvel essai, échec, sauté (une autre synchro vient de passer), ou une note (« mise à jour »). */
        val result: String,
        val detail: String,
        val exempt: Boolean,
    )

    fun batteryExempt(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun record(ctx: Context, trigger: String, planned: Long, result: String, detail: String) {
        val sp = sp(ctx)
        val old = runCatching { JSONArray(sp.getString("runs", "[]")) }.getOrElse { JSONArray() }
        val out = JSONArray().put(JSONObject()
            .put("at", System.currentTimeMillis()).put("trigger", trigger).put("planned", planned)
            .put("result", result).put("detail", detail.take(160)).put("exempt", batteryExempt(ctx)))
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        sp.edit { putString("runs", out.toString()) }
    }

    fun runs(ctx: Context): List<Run> {
        val a = runCatching { JSONArray(sp(ctx).getString("runs", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Run(it.optLong("at"), it.optString("trigger"), it.optLong("planned"), it.optString("result"),
                it.optString("detail"), it.optBoolean("exempt", true))
        }
    }

    /** Heure prévue du prochain passage du matin (posée par MorningWorker.schedule). */
    fun setMorningPlanned(ctx: Context, at: Long) = sp(ctx).edit { putLong("morning_planned", at) }
    fun morningPlanned(ctx: Context): Long = sp(ctx).getLong("morning_planned", 0L)

    /** Dernier passage horaire : le suivant est attendu une heure après. */
    fun setLastAuto(ctx: Context, at: Long) = sp(ctx).edit { putLong("last_auto", at) }
    fun autoPlanned(ctx: Context): Long = sp(ctx).getLong("last_auto", 0L).let { if (it > 0) it + 3_600_000L else 0L }

    /**
     * Première exécution d'une nouvelle version (ouverture de l'app OU synchro en arrière-plan) : note dans
     * le journal si l'exemption a survécu à la mise à jour. Renvoie true si une mise à jour vient d'être vue.
     */
    @Synchronized
    fun checkUpdate(ctx: Context): Boolean {
        val sp = sp(ctx)
        val seen = sp.getInt("version_code", 0)
        if (seen == BuildConfig.VERSION_CODE) return false
        sp.edit { putInt("version_code", BuildConfig.VERSION_CODE) }
        if (seen == 0) return false // premier lancement après une installation neuve : rien à comparer
        record(ctx, "mise à jour", 0L, "note",
            "version ${BuildConfig.VERSION_NAME} : exemption de batterie ${if (batteryExempt(ctx)) "conservée" else "PERDUE"}")
        return true
    }

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    fun time(ms: Long): String = FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** Retard par rapport à l'heure prévue, lisible (« +2 h 10 »), ou null si inconnue ou à l'heure. */
    fun lateness(r: Run): String? {
        if (r.planned <= 0) return null
        val min = (r.at - r.planned) / 60_000
        if (min < 10) return null
        return if (min < 60) "+$min min" else "+${min / 60} h ${"%02d".format(min % 60)}"
    }

    fun asText(ctx: Context): String = buildString {
        appendLine("Synchros de Parro Health ${BuildConfig.VERSION_NAME} — exemption de batterie : " +
            if (batteryExempt(ctx)) "oui" else "NON")
        runs(ctx).forEach { r ->
            append("${time(r.at)} ${r.trigger} : ${r.result}")
            if (r.planned > 0) append(" (prévu ${time(r.planned)}${lateness(r)?.let { ", $it" } ?: ""})")
            if (!r.exempt) append(" [non exemptée]")
            if (r.detail.isNotEmpty()) append(" — ${r.detail}")
            appendLine()
        }
    }
}
