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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Rappel de pesée (25/11/2026) : chaque jour ou un jour par semaine, à l'heure choisie dans les Réglages.
 * Ne sonne PAS si une pesée existe déjà aujourd'hui dans Health Connect (balance connectée via Google Health,
 * Samsung Health…). Lu localement : aucune requête au serveur. Le texte ne contient aucune donnée de santé.
 * Travail unique qui se replanifie lui-même (même principe que MorningWorker).
 */
class WeighReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.weighReminder != WeighReminder.OFF) {
            if (!WeighReminder.weighedToday(ctx)) WeighReminder.notify(ctx)
            WeighReminder.schedule(ctx, WeighReminder.Policy.FROM_WORKER)
        }
        return Result.success()
    }
}

object WeighReminder {
    const val OFF = "off"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    val TIME_OPTIONS = listOf(390, 420, 450, 480, 540) // 6 h 30 … 9 h
    val DAYS = listOf(1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Jeu", 5 to "Ven", 6 to "Sam", 7 to "Dim")
    private const val NAME = "parro-rappel-pesee"
    private const val CHANNEL = "rappels"
    private const val NOTIF_ID = 3001

    enum class Policy { FROM_SETTINGS, AT_START, FROM_WORKER }

    /** Prochain rappel STRICTEMENT après `now` (null si désactivé). Fonction pure, testée. */
    fun next(now: LocalDateTime, mode: String, day: Int, minutes: Int): LocalDateTime? {
        if (mode != DAILY && mode != WEEKLY) return null
        val time = LocalTime.of(minutes / 60, minutes % 60)
        var d: LocalDate = now.toLocalDate()
        repeat(8) {
            val candidate = d.atTime(time)
            if (candidate.isAfter(now) && (mode == DAILY || d.dayOfWeek == DayOfWeek.of(day))) return candidate
            d = d.plusDays(1)
        }
        return null
    }

    fun schedule(context: Context, policy: Policy) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        val at = next(LocalDateTime.now(), prefs.weighReminder, prefs.weighDay, prefs.weighMinutes)
        if (at == null) { wm.cancelUniqueWork(NAME); return }
        val req = OneTimeWorkRequestBuilder<WeighReminderWorker>()
            .setInitialDelay(Duration.between(LocalDateTime.now(), at).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        val existing = when (policy) {
            Policy.FROM_SETTINGS -> ExistingWorkPolicy.REPLACE
            Policy.AT_START -> ExistingWorkPolicy.KEEP
            Policy.FROM_WORKER -> ExistingWorkPolicy.APPEND_OR_REPLACE // sans s'annuler soi-même
        }
        wm.enqueueUniqueWork(NAME, existing, req)
    }

    suspend fun weighedToday(context: Context): Boolean = try {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        HealthConnectClient.getOrCreate(context)
            .readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(start), pageSize = 1))
            .records.isNotEmpty()
    } catch (_: Exception) {
        false // lecture impossible : mieux vaut un rappel de trop qu'un rappel manqué
    }

    fun notify(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIF_ID, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_parro)
            .setContentTitle("Pense à te peser")
            .setContentText("Le matin, à jeun, pieds nus : c'est la mesure la plus comparable d'un jour à l'autre.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Le matin, à jeun, pieds nus : c'est la mesure la plus comparable d'un jour à l'autre."))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
