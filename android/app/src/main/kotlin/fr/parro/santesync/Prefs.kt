package fr.parro.santesync

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/** Réglages et état de synchro, dans le stockage privé de l'appli (sauvegarde et transfert exclus). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("santesync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit { putString("server_url", v.trim().trimEnd('/')) }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit { putString("token", v.trim()) }

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

    /** Verrouillage biométrique à l'ouverture (option des Réglages). */
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) = sp.edit { putBoolean("lock_enabled", v) }

    val isConfigured: Boolean
        get() = serverUrl.startsWith("https://") && token.length >= 32
}
