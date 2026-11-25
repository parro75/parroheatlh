package fr.parro.santesync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (Sync.run(applicationContext, Trigger.AUTO)) {
        is SyncResult.Ok -> Result.success()
        is SyncResult.Retry -> if (runAttemptCount < 5) Result.retry() else Result.failure()
        is SyncResult.Fatal -> Result.failure()
    }

    companion object {
        private const val NAME = "santesync-periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Prochaine exécution prévue par Android (epoch ms), ou null si rien n'est planifié. */
        suspend fun nextRun(context: Context): Long? = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(NAME).get()
                .firstOrNull { !it.state.isFinished }
                ?.nextScheduleTimeMillis
                ?.takeIf { it in 1 until Long.MAX_VALUE }
        }
    }
}
