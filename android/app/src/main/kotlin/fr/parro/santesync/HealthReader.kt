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
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
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

        /** Types lus enregistrement par enregistrement (les pas et la FC passent par des agrégats). */
        val RAW_TYPES: List<KClass<out Record>> = listOf(
            RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, WeightRecord::class,
            BodyFatRecord::class, OxygenSaturationRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class,
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

        for (type in RAW_TYPES) {
            if (!has(type)) continue
            @Suppress("UNCHECKED_CAST")
            readAll(type as KClass<Record>, from, to).forEach { r -> convert(r).forEach(::add) }
        }
        return out
    }

    /** Un enregistrement Health Connect → 0, 1 ou 2 enregistrements serveur (nuit + FC au repos calculée). */
    private suspend fun convert(r: Record): List<JSONObject> {
        val src = r.metadata.dataOrigin.packageName
        return when (r) {
            is RestingHeartRateRecord -> listOf(record("resting_heart_rate", r.metadata.id, r.time, null,
                offsetAt(r.time, r.zoneOffset), JSONObject().put("bpm", r.beatsPerMinute).put("source", src)))
            is HeartRateVariabilityRmssdRecord -> listOf(record("hrv_rmssd", r.metadata.id, r.time, null,
                offsetAt(r.time, r.zoneOffset), JSONObject().put("rmssd_ms", r.heartRateVariabilityMillis).put("source", src)))
            is WeightRecord -> listOf(record("weight", r.metadata.id, r.time, null, offsetAt(r.time, r.zoneOffset),
                JSONObject().put("kg", r.weight.inKilograms).put("source", src)))
            is BodyFatRecord -> listOf(record("body_fat", r.metadata.id, r.time, null, offsetAt(r.time, r.zoneOffset),
                JSONObject().put("percent", r.percentage.value).put("source", src)))
            is OxygenSaturationRecord -> listOf(record("oxygen_saturation", r.metadata.id, r.time, null,
                offsetAt(r.time, r.zoneOffset), JSONObject().put("percent", r.percentage.value).put("source", src)))
            is SleepSessionRecord -> {
                val off = offsetAt(r.endTime, r.endZoneOffset ?: r.startZoneOffset)
                val stages = JSONArray()
                r.stages.forEach { st ->
                    stages.put(JSONObject().put("start", iso(st.startTime, off)).put("end", iso(st.endTime, off))
                        .put("stage", STAGES[st.stage] ?: "unknown"))
                }
                val data = JSONObject().put("stages", stages).put("source", src)
                r.title?.let { data.put("title", it) }
                // FC au repos CALCULÉE : Samsung ne la publie pas dans Health Connect, mais y écrit la FC ~1×/min.
                val rhr = if (has(HeartRateRecord::class) && Duration.between(r.startTime, r.endTime) >= MIN_NIGHT)
                    restingHeartRate(r) else null
                listOfNotNull(record("sleep_session", r.metadata.id, r.startTime, r.endTime, off, data), rhr)
            }
            is ExerciseSessionRecord -> {
                val data = JSONObject()
                    // Code numérique : la table code → nom de la bibliothèque est une API restreinte,
                    // le serveur fait la correspondance (shc/exercise_types.py).
                    .put("exercise_type", r.exerciseType).put("source", src)
                r.title?.let { data.put("title", it) }
                exerciseMetrics(r.startTime, r.endTime, data)
                listOf(record("exercise_session", r.metadata.id, r.startTime, r.endTime, offsetAt(r.startTime, r.startZoneOffset), data))
            }
            else -> emptyList()
        }
    }

    private fun startOf(r: Record): Instant? = when (r) {
        is RestingHeartRateRecord -> r.time
        is HeartRateVariabilityRmssdRecord -> r.time
        is WeightRecord -> r.time
        is BodyFatRecord -> r.time
        is OxygenSaturationRecord -> r.time
        is SleepSessionRecord -> r.startTime
        is ExerciseSessionRecord -> r.startTime
        else -> null
    }

    /** Jeton du journal des changements, pour les types « bruts » autorisés (pas les pas ni la FC : volumineux). */
    suspend fun changesToken(grantedPermissions: Set<String>): String? {
        granted = grantedPermissions
        val types = RAW_TYPES.filter { has(it) }.toSet()
        return if (types.isEmpty()) null else client.getChangesToken(ChangesTokenRequest(types))
    }

    data class Changes(val records: JSONArray, val nextToken: String?, val expired: Boolean, val deletions: Int, val late: Int)

    /**
     * Ce que la relecture de la fenêtre [windowStart, maintenant] ne voit pas : les SUPPRESSIONS (marqueur
     * {"type": "deletion", "id"}) et les ajouts ou corrections d'enregistrements plus anciens que la fenêtre.
     */
    suspend fun changesSince(token: String, windowStart: Instant, grantedPermissions: Set<String>): Changes {
        granted = grantedPermissions
        val out = JSONArray()
        var next: String? = token
        var deletions = 0
        var late = 0
        while (next != null) {
            val resp = client.getChanges(next)
            if (resp.changesTokenExpired) return Changes(out, null, true, deletions, late)
            for (ch in resp.changes) when (ch) {
                is DeletionChange -> { out.put(JSONObject().put("type", "deletion").put("id", ch.recordId)); deletions++ }
                is UpsertionChange -> {
                    val r = ch.record
                    val start = startOf(r)
                    if (start != null && start.isBefore(windowStart)) { convert(r).forEach { out.put(it) }; late++ }
                }
            }
            next = resp.nextChangesToken
            if (!resp.hasMore) break
        }
        return Changes(out, next, false, deletions, late)
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
