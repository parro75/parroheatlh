package fr.parro.santesync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Synchro rapide du matin : toutes les 15 min de 5 h à 11 h, pour que la nuit (et donc le rapport du matin)
 * arrive sur le serveur peu après le réveil. Travail unique qui se replanifie lui-même : hors du créneau,
 * rien ne se réveille jusqu'au lendemain 5 h. La synchro horaire continue en parallèle.
 */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = LocalTime.now()
        if (!now.isBefore(START) && now.isBefore(END)) Sync.run(applicationContext, Trigger.MORNING)
        else SyncLog.record(applicationContext, Trigger.MORNING.label, SyncLog.morningPlanned(applicationContext),
            "hors créneau", "lancé après 11 h : passage ignoré")
        schedule(applicationContext, fromWorker = true) // enchaîné APRÈS ce passage, sans l'annuler
        return Result.success()
    }

    companion object {
        private const val NAME = "parro-morning"
        val START: LocalTime = LocalTime.of(5, 0)
        val END: LocalTime = LocalTime.of(11, 0)
        private val STEP: Duration = Duration.ofMinutes(15)

        /** Délai jusqu'au prochain passage : +15 min dans le créneau, sinon le prochain 5 h. */
        fun delayFrom(now: LocalDateTime): Duration {
            val t = now.toLocalTime()
            if (!t.isBefore(START) && t.plus(STEP).isBefore(END)) return STEP
            val next = if (t.isBefore(START)) now.toLocalDate().atTime(START) else now.toLocalDate().plusDays(1).atTime(START)
            return Duration.between(now, next)
        }

        fun schedule(context: Context, fromWorker: Boolean = false) {
            val delay = delayFrom(LocalDateTime.now()).toMillis()
            val req = OneTimeWorkRequestBuilder<MorningWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            // KEEP garde le passage déjà prévu : l'heure prévue notée reste la sienne.
            if (fromWorker || SyncLog.morningPlanned(context) < System.currentTimeMillis())
                SyncLog.setMorningPlanned(context, System.currentTimeMillis() + delay)
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, req)
        }
    }
}
