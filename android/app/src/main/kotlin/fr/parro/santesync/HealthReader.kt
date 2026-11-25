package fr.parro.santesync

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/** Lit Health Connect et produit les enregistrements au format attendu par POST /ingest. */
class HealthReader(private val client: HealthConnectClient) {

    companion object {
        private const val TAG = "SanteSync"
        private const val PAGE_SIZE = 1000

        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class,
            SleepSessionRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
            OxygenSaturationRecord::class,
            ExerciseSessionRecord::class,
            DistanceRecord::class,
            TotalCaloriesBurnedRecord::class,
            ActiveCaloriesBurnedRecord::class,
        )

        val READ_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()

        fun wantedPermissions(client: HealthConnectClient): Set<String> = buildSet {
            addAll(READ_PERMISSIONS)
            val f = client.features
            if (f.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            if (f.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }

        private val STAGES = mapOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake_in_bed",
        )

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** Nuit prise en compte pour la FC au repos (les siestes sont ignorées). */
        private val MIN_NIGHT: Duration = Duration.ofHours(3)
        private val RHR_WINDOW: Duration = Duration.ofMinutes(30)
        private const val RHR_MIN_SAMPLES_IN_WINDOW = 15
        private const val RHR_MIN_SAMPLES_NIGHT = 60
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private lateinit var granted: Set<String>

    private fun has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

    private fun offsetAt(instant: Instant, recorded: ZoneOffset?): ZoneOffset =
        recorded ?: zone.rules.getOffset(instant)

    private fun iso(instant: Instant, offset: ZoneOffset): String =
        OffsetDateTime.ofInstant(instant, offset).format(ISO)

    private fun record(
        type: String, id: String, start: Instant, end: Instant?, offset: ZoneOffset, data: JSONObject,
    ) = JSONObject().apply {
        put("type", type)
        put("id", id)
        put("start", iso(start, offset))
        if (end != null) put("end", iso(end, offset))
        put("zone_offset", offset.id.let { if (it == "Z") "+00:00" else it })
        put("data", data)
    }

    /** Toutes les pages d'un type sur [from, to). */
    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = PAGE_SIZE,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }

    suspend fun read(from: Instant, to: Instant, grantedPermissions: Set<String>): JSONArray {
        granted = grantedPermissions
        val out = JSONArray()
        fun add(o: JSONObject) { out.put(o) }

        dailyAggregates(from, to).forEach(::add)

        if (has(RestingHeartRateRecord::class)) readAll(RestingHeartRateRecord::class, from, to).forEach { r ->
            val off = offsetAt(r.time, r.zoneOffset)
            add(record("resting_heart_rate", r.metadata.id, r.time, null, off,
                JSONObject().put("bpm", r.beatsPerMinute).put("source", r.metadata.dataOrigin.packageName)))
        }
        if (has(HeartRateVariabilityRmssdRecord::class)) readAll(HeartRateVariabilityRmssdRecord::class, from, to).forEach { r ->
            val off = offsetAt(r.time, r.zoneOffset)
            add(record("hrv_rmssd", r.metadata.id, r.time, null, off,
                JSONObject().put("rmssd_ms", r.heartRateVariabilityMillis).put("source", r.metadata.dataOrigin.packageName)))
        }
        if (has(WeightRecord::class)) readAll(WeightRecord::class, from, to).forEach { r ->
            add(record("weight", r.metadata.id, r.time, null, offsetAt(r.time, r.zoneOffset),
                JSONObject().put("kg", r.weight.inKilograms).put("source", r.metadata.dataOrigin.packageName)))
        }
        if (has(BodyFatRecord::class)) readAll(BodyFatRecord::class, from, to).forEach { r ->
            add(record("body_fat", r.metadata.id, r.time, null, offsetAt(r.time, r.zoneOffset),
                JSONObject().put("percent", r.percentage.value).put("source", r.metadata.dataOrigin.packageName)))
        }
        if (has(OxygenSaturationRecord::class)) readAll(OxygenSaturationRecord::class, from, to).forEach { r ->
            add(record("oxygen_saturation", r.metadata.id, r.time, null, offsetAt(r.time, r.zoneOffset),
                JSONObject().put("percent", r.percentage.value).put("source", r.metadata.dataOrigin.packageName)))
        }
        val sleeps = if (has(SleepSessionRecord::class)) readAll(SleepSessionRecord::class, from, to) else emptyList()
        sleeps.forEach { r ->
            val off = offsetAt(r.endTime, r.endZoneOffset ?: r.startZoneOffset)
            val stages = JSONArray()
            r.stages.forEach { s ->
                stages.put(JSONObject()
                    .put("start", iso(s.startTime, off))
                    .put("end", iso(s.endTime, off))
                    .put("stage", STAGES[s.stage] ?: "unknown"))
            }
            val data = JSONObject().put("stages", stages).put("source", r.metadata.dataOrigin.packageName)
            r.title?.let { data.put("title", it) }
            add(record("sleep_session", r.metadata.id, r.startTime, r.endTime, off, data))
        }
        // FC au repos CALCULÉE : Samsung ne la publie pas dans Health Connect, mais y écrit la FC ~1×/min.
        if (has(HeartRateRecord::class)) sleeps
            .filter { Duration.between(it.startTime, it.endTime) >= MIN_NIGHT }
            .forEach { r -> restingHeartRate(r)?.let(::add) }
        if (has(ExerciseSessionRecord::class)) readAll(ExerciseSessionRecord::class, from, to).forEach { r ->
            val off = offsetAt(r.startTime, r.startZoneOffset)
            val data = JSONObject()
                // Code numérique : la table code → nom de la bibliothèque est une API restreinte,
                // le serveur fait la correspondance (shc/exercise_types.py).
                .put("exercise_type", r.exerciseType)
                .put("source", r.metadata.dataOrigin.packageName)
            r.title?.let { data.put("title", it) }
            exerciseMetrics(r.startTime, r.endTime, data)
            add(record("exercise_session", r.metadata.id, r.startTime, r.endTime, off, data))
        }
        return out
    }

    /**
     * FC au repos d'une nuit : plus basse moyenne glissante sur 30 min des mesures de FC pendant le sommeil
     * (fenêtre d'au moins 15 mesures), plus la moyenne de toute la nuit (« FC de sommeil » de Samsung).
     * Seuls ces deux chiffres quittent le téléphone, jamais les mesures brutes.
     */
    private suspend fun restingHeartRate(night: SleepSessionRecord): JSONObject? {
        val samples = readAll(HeartRateRecord::class, night.startTime, night.endTime)
            .flatMap { it.samples }
            .filter { !it.time.isBefore(night.startTime) && it.time.isBefore(night.endTime) && it.beatsPerMinute in 25L..220L }
            .sortedBy { it.time }
        if (samples.size < RHR_MIN_SAMPLES_NIGHT) return null
        var best = Double.MAX_VALUE
        var sum = 0L
        var lo = 0
        for (hi in samples.indices) {
            sum += samples[hi].beatsPerMinute
            while (Duration.between(samples[lo].time, samples[hi].time) > RHR_WINDOW) {
                sum -= samples[lo].beatsPerMinute; lo++
            }
            val n = hi - lo + 1
            if (n >= RHR_MIN_SAMPLES_IN_WINDOW) best = minOf(best, sum.toDouble() / n)
        }
        if (best == Double.MAX_VALUE) return null
        val avg = samples.sumOf { it.beatsPerMinute }.toDouble() / samples.size
        val off = offsetAt(night.endTime, night.endZoneOffset ?: night.startZoneOffset)
        return record("resting_heart_rate", "calc:rhr:${night.metadata.id}", night.endTime, null, off,
            JSONObject()
                .put("bpm", Math.round(best))
                .put("sleeping_avg", Math.round(avg * 10) / 10.0)
                .put("samples", samples.size)
                .put("method", "plus basse moyenne glissante 30 min pendant le sommeil")
                .put("source", "calcul Parro Health"))
    }

    /** Pas et FC par jour local : Health Connect dédoublonne selon la priorité des sources. */
    private suspend fun dailyAggregates(from: Instant, to: Instant): List<JSONObject> {
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (has(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (has(HeartRateRecord::class)) metrics += setOf(
            HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT,
        )
        if (metrics.isEmpty()) return emptyList()
        val firstDay: LocalDate = from.atZone(zone).toLocalDate()
        val endExclusive: LocalDateTime = to.atZone(zone).toLocalDate().plusDays(1).atStartOfDay()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(firstDay.atStartOfDay(), endExclusive),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        val out = mutableListOf<JSONObject>()
        for (g in groups) {
            val day = g.startTime.toLocalDate()
            val start = g.startTime.atZone(zone)
            val end = g.endTime.atZone(zone)
            val off = start.offset
            g.result[StepsRecord.COUNT_TOTAL]?.let { steps ->
                out += record("steps_daily", "steps:$day", start.toInstant(), end.toInstant(), off,
                    JSONObject().put("count", steps))
            }
            val n = g.result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L
            if (n > 0) {
                val hr = JSONObject().put("samples", n)
                g.result[HeartRateRecord.BPM_MIN]?.let { hr.put("min", it) }
                g.result[HeartRateRecord.BPM_AVG]?.let { hr.put("avg", it) }
                g.result[HeartRateRecord.BPM_MAX]?.let { hr.put("max", it) }
                out += record("heart_rate_daily", "hr:$day", start.toInstant(), end.toInstant(), off, hr)
            }
        }
        return out
    }

    /** Distance, calories, FC et pas sur la plage d'une séance. Une erreur n'empêche pas la séance. */
    private suspend fun exerciseMetrics(start: Instant, end: Instant, data: JSONObject) {
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (has(DistanceRecord::class)) metrics += DistanceRecord.DISTANCE_TOTAL
        if (has(TotalCaloriesBurnedRecord::class)) metrics += TotalCaloriesBurnedRecord.ENERGY_TOTAL
        if (has(ActiveCaloriesBurnedRecord::class)) metrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (has(HeartRateRecord::class)) metrics += setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX)
        if (has(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (metrics.isEmpty()) return
        try {
            val r = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, end)))
            r[DistanceRecord.DISTANCE_TOTAL]?.let { data.put("distance_m", it.inMeters) }
            val kcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL] ?: r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
            kcal?.let { data.put("calories_kcal", it.inKilocalories) }
            r[HeartRateRecord.BPM_AVG]?.let { data.put("avg_hr", it) }
            r[HeartRateRecord.BPM_MAX]?.let { data.put("max_hr", it) }
            r[StepsRecord.COUNT_TOTAL]?.let { data.put("steps", it) }
        } catch (e: Exception) {
            Log.w(TAG, "agrégat de séance impossible", e)
        }
    }
}
