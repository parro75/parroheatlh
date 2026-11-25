package fr.parro.santesync

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/** Réglages et état de synchro, dans le stockage privé de l'appli (allowBackup=false). */
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

    val isConfigured: Boolean
        get() = serverUrl.startsWith("https://") && token.length >= 32
}
