package fr.parro.santesync

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/** Réglages et état de synchro, dans le stockage privé de l'appli (sauvegarde et transfert exclus). */
class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("santesync", Context.MODE_PRIVATE)

    /** URL du serveur, HTTPS uniquement : une URL en clair n'est jamais utilisée, même si elle était stockée. */
    var serverUrl: String
        get() = (sp.getString("server_url", "") ?: "").takeIf { it.startsWith("https://") } ?: ""
        set(v) = sp.edit { putString("server_url", v.trim().trimEnd('/')) }

    /** Jeton d'ingestion, chiffré par le Keystore (TokenStore). Migration : l'ancien jeton en clair est
     *  chiffré à la première lecture puis effacé des préférences. */
    var token: String
        get() {
            val store = TokenStore(context)
            sp.getString("token", null)?.takeIf { it.isNotEmpty() }?.let { plain ->
                store.ingestToken = plain
                sp.edit { remove("token") }
                return plain
            }
            return store.ingestToken ?: ""
        }
        set(v) {
            TokenStore(context).ingestToken = v.trim()
            sp.edit { remove("token") }
        }

    /** Instant de début de la dernière synchro réussie (sert de borne à la suivante). */
    var lastSync: Instant?
        get() = sp.getLong("last_sync", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(v) = sp.edit { putLong("last_sync", v?.toEpochMilli() ?: 0L) }

    var lastStatus: String
        get() = sp.getString("last_status", "") ?: ""
        set(v) = sp.edit { putString("last_status", v) }

    /** Version du calcul local déjà appliquée à tout l'historique (voir Sync.CALC_VERSION). */
    var calcVersion: Int
        get() = sp.getInt("calc_version", 0)
        set(v) = sp.edit { putInt("calc_version", v) }

    /** Jeton du journal des changements de Health Connect (suppressions, corrections anciennes). */
    var changesToken: String?
        get() = sp.getString("changes_token", null)
        set(v) = sp.edit { if (v == null) remove("changes_token") else putString("changes_token", v) }

    /** Objectifs personnels (envoyés au serveur : les règles du rapport s'en servent). */
    var sleepGoalMin: Int
        get() = sp.getInt("goal_sleep_min", 450)
        set(v) = sp.edit { putInt("goal_sleep_min", v); putBoolean("goals_pending", true) }

    var stepsGoal: Int
        get() = sp.getInt("goal_steps", 8000)
        set(v) = sp.edit { putInt("goal_steps", v); putBoolean("goals_pending", true) }

    var goalsPending: Boolean
        get() = sp.getBoolean("goals_pending", false)
        set(v) = sp.edit { putBoolean("goals_pending", v) }

    /** Dernier résumé « today » du serveur, pour le widget (rien d'affiché si l'app est verrouillée). */
    /** Rappel de pesée : off / daily / weekly, jour (1 = lundi … 7 = dimanche), heure en minutes. */
    var weighReminder: String
        get() = sp.getString("weigh_reminder", WeighReminder.OFF) ?: WeighReminder.OFF
        set(v) = sp.edit { putString("weigh_reminder", v) }

    var weighDay: Int
        get() = sp.getInt("weigh_day", 1)
        set(v) = sp.edit { putInt("weigh_day", v) }

    var weighMinutes: Int
        get() = sp.getInt("weigh_minutes", 450)
        set(v) = sp.edit { putInt("weigh_minutes", v) }

    /** Dernier rapport (jour « AAAA-MM-JJ », semaine « SAAAA-Wnn ») déjà annoncé par une notification. */
    var notifiedDay: String
        get() = sp.getString("notified_day", "") ?: ""
        set(v) = sp.edit { putString("notified_day", v) }

    var notifiedWeek: String
        get() = sp.getString("notified_week", "") ?: ""
        set(v) = sp.edit { putString("notified_week", v) }

    var widgetSummary: String
        get() = sp.getString("widget_summary", "") ?: ""
        set(v) = sp.edit { putString("widget_summary", v) }

    /** Identifiant de la clé d'appareil acceptée par le serveur (vide = pas encore appairé). */
    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit { putString("device_id", v) }

    /** Verrouillage biométrique à l'ouverture (option des Réglages). */
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) = sp.edit { putBoolean("lock_enabled", v) }

    val isConfigured: Boolean
        get() = serverUrl.startsWith("https://") && token.length >= 32
}
