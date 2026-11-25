package fr.parro.santesync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Notification du rapport (jour et semaine) émise PAR L'APPLI après une synchro (25/11/2026) : le texte
 * vient du serveur en HTTPS et ne passe ni par Home Assistant ni par Google (Firebase).
 * - verrouillage biométrique actif → « Ton rapport est prêt », sans contenu ;
 * - sinon le rapport complet, mais l'écran verrouillé n'en montre que la version publique (sans contenu).
 * Seuls les rapports récents sont annoncés (pas de rattrapage d'un vieux rapport à l'installation).
 */
object ReportNotifier {
    private const val CHANNEL = "rapports"
    private const val ID_DAY = 2001
    private const val ID_WEEK = 2002

    suspend fun check(context: Context) {
        val prefs = Prefs(context)
        runCatching { Api.get(context, "report") }.getOrNull()?.let { r ->
            val key = r.optJSONObject("facts")?.optString("date").orEmpty()
            if (key.isNotEmpty() && key != prefs.notifiedDay && isFresh(r, 18) &&
                show(context, ID_DAY, "Rapport santé", "Ton rapport est prêt", r, prefs.lockEnabled)) prefs.notifiedDay = key
        }
        runCatching { Api.get(context, "weekly") }.getOrNull()?.let { r ->
            val key = r.optJSONObject("facts")?.optString("week").orEmpty()
            if (key.isNotEmpty() && key != prefs.notifiedWeek && isFresh(r, 36) &&
                show(context, ID_WEEK, "Rapport de la semaine", "Ton rapport de la semaine est prêt", r, prefs.lockEnabled))
                prefs.notifiedWeek = key
        }
    }

    private fun isFresh(r: JSONObject, hours: Long): Boolean {
        if (r.isNull("narrative") || r.optString("narrative").isBlank()) return false
        val at = runCatching { OffsetDateTime.parse(r.optString("generated_at")).toInstant() }.getOrNull() ?: return false
        return at.isAfter(Instant.now().minus(Duration.ofHours(hours)))
    }

    /** Texte complet : le récit + les points calculés par les règles (déjà inclus dans le texte de secours). */
    private fun fullText(r: JSONObject): String {
        val narrative = r.optString("narrative").trim()
        if (r.optString("model").startsWith("secours")) return narrative
        val flags = r.optJSONObject("facts")?.optJSONArray("flags") ?: return narrative
        if (flags.length() == 0) return "$narrative\n\nRien d'inhabituel."
        return narrative + "\n\nÀ noter :\n" + (0 until flags.length()).joinToString("\n") {
            val f = flags.getJSONObject(it)
            (if (f.optString("level") == "attention") "⚠ " else "• ") + f.optString("text")
        }
    }

    private fun show(context: Context, id: Int, title: String, neutral: String, r: JSONObject, locked: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rapports santé", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun base() = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_parro)
            .setContentTitle(title).setContentIntent(open).setAutoCancel(true)
        val public = base().setContentText(neutral).build()
        val n = if (locked) base().setContentText(neutral)
        else {
            val text = fullText(r)
            base().setContentText(text.lineSequence().firstOrNull { it.isNotBlank() } ?: neutral)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        nm.notify(id, n.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build())
        return true
    }
}
