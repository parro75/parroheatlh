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

    val isConfigured: Boolean
        get() = serverUrl.startsWith("https://") && token.length >= 32
}
