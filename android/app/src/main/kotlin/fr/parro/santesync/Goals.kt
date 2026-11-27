package fr.parro.santesync

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Objectifs personnels → enregistrement « goals » (id fixe : le serveur garde le dernier). */
object Goals {
    val SLEEP_OPTIONS = listOf(390, 420, 450, 480, 510)
    val STEPS_OPTIONS = listOf(6000, 8000, 10000, 12000)
    const val WEIGHT_MIN = 40
    const val WEIGHT_MAX = 200

    fun record(prefs: Prefs): JSONObject {
        val now = OffsetDateTime.now()
        return JSONObject().put("type", "goals").put("id", "goals")
            .put("start", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("zone_offset", now.offset.id.let { if (it == "Z") "+00:00" else it })
            .put("data", JSONObject().put("sleep_min", prefs.sleepGoalMin).put("steps", prefs.stepsGoal).apply {
                if (prefs.weightLow > 0 && prefs.weightDate.isNotEmpty()) {
                    put("weight_low", prefs.weightLow).put("weight_high", prefs.weightHigh)
                        .put("weight_date", prefs.weightDate).put("weight_since", prefs.weightSince)
                }
            })
    }

    /** Envoi immédiat ; sinon il partira avec la synchro suivante (goalsPending reste vrai). */
    suspend fun flush(context: android.content.Context) {
        val prefs = Prefs(context)
        if (!prefs.goalsPending || !prefs.isConfigured) return
        try {
            Sync.postRecords(prefs, org.json.JSONArray().put(record(prefs)), Trigger.MANUAL)
            prefs.goalsPending = false
        } catch (_: Exception) {
        }
    }
}
