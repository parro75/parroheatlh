package fr.parro.santesync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Inventaire de ce que Health Connect contient réellement, type par type, sur 30 jours, avec les applis
 * sources. Sert à décider quoi lire ensuite. Types de forme et de signes vitaux seulement : les types de
 * santé reproductive ne sont ni demandés ni lus (accès sans rapport avec l'appli).
 */
object Diagnostic {
    /** Types lus pour le diagnostic seulement (en plus de ceux de la synchro). */
    val EXTRA_TYPES: List<KClass<out Record>> = listOf(
        BloodPressureRecord::class, BloodGlucoseRecord::class, BodyTemperatureRecord::class,
        RespiratoryRateRecord::class, BasalMetabolicRateRecord::class, LeanBodyMassRecord::class,
        BodyWaterMassRecord::class, BoneMassRecord::class, HeightRecord::class, HydrationRecord::class,
        NutritionRecord::class, FloorsClimbedRecord::class, ElevationGainedRecord::class, PowerRecord::class,
        SpeedRecord::class, StepsCadenceRecord::class, CyclingPedalingCadenceRecord::class,
    )
    val EXTRA_PERMISSIONS: Set<String> = EXTRA_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()

    data class Line(val type: String, val allowed: Boolean, val count: Int?, val capped: Boolean, val sources: Map<String, Int>)

    suspend fun run(client: HealthConnectClient): List<Line> {
        val granted = client.permissionController.getGrantedPermissions()
        val to = Instant.now()
        val from = to.minus(Duration.ofDays(30))
        return (HealthReader.RECORD_TYPES + EXTRA_TYPES).distinct().map { type ->
            val name = type.simpleName?.removeSuffix("Record") ?: "?"
            if (HealthPermission.getReadPermission(type) !in granted) return@map Line(name, false, null, false, emptyMap())
            try {
                val resp = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000))
                val sources = resp.records.groupingBy { it.metadata.dataOrigin.packageName }.eachCount()
                Line(name, true, resp.records.size, resp.pageToken != null, sources)
            } catch (e: Exception) {
                Line(name, true, null, false, mapOf("erreur : ${e.javaClass.simpleName}" to 0))
            }
        }
    }

    fun asText(lines: List<Line>): String = buildString {
        appendLine("Diagnostic Health Connect — 30 derniers jours (Parro Health ${BuildConfig.VERSION_NAME})")
        lines.forEach { l ->
            val n = when {
                !l.allowed -> "non autorisé"
                l.count == null -> "illisible"
                else -> (if (l.capped) "≥ " else "") + l.count
            }
            append("${l.type} : $n")
            if (l.sources.isNotEmpty()) append(" — " + l.sources.entries.joinToString(", ") { "${it.key} (${it.value})" })
            appendLine()
        }
    }
}
