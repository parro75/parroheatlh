package fr.parro.santesync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Fiche de synthèse PDF pour un rendez-vous médical (26/11/2026) : générée par le serveur à la demande,
 * gardée dans le cache PRIVÉ de l'app (une seule fiche à la fois, l'ancienne est effacée), puis ouverte ou
 * partagée par une URI temporaire (FileProvider) : aucune autre app ne la voit sans ton geste.
 */
object MedicalPdf {
    val MONTHS = listOf("3" to "3 mois", "6" to "6 mois", "12" to "12 mois")
    private const val DIR = "fiches"

    suspend fun download(context: Context, months: Int): File {
        val bytes = Api.bytes(context, "pdf/summary", mapOf("months" to months.toString()))
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "synthese-sante-${LocalDate.now()}-$months-mois.pdf").apply { writeBytes(bytes) }
        }
    }

    /** Efface la fiche du cache (appelé au verrouillage de l'app ou à la demande). */
    fun clear(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fiches", file)

    /** Ouvre dans le lecteur PDF du téléphone (impression possible depuis celui-ci). Faux si aucun lecteur. */
    fun open(context: Context, file: File): Boolean = try {
        LockGuard.leaving()
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun share(context: Context, file: File) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        LockGuard.leaving()
        context.startActivity(Intent.createChooser(send, "Envoyer la fiche").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
