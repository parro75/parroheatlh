package fr.parro.santesync

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.json.JSONObject

/** Widget d'écran d'accueil : dernière nuit, pas du jour / objectif, FC au repos. */
class ParroWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        val locked = prefs.lockEnabled
        val summary = prefs.widgetSummary.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        provideContent { Content(locked, summary, prefs.stepsGoal) }
    }

    @Composable
    private fun Content(locked: Boolean, t: JSONObject?, stepsGoal: Int) {
        val fg = ColorProvider(android.graphics.Color.WHITE.let { androidx.compose.ui.graphics.Color(it) })
        val big = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val small = TextStyle(color = fg, fontSize = 12.sp)
        Column(GlanceModifier.fillMaxSize().cornerRadius(16.dp)
            .background(androidx.compose.ui.graphics.Color(0xFF1E6B52)).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())) {
            Text("Parro Health", style = small)
            when {
                // Verrouillage actif : aucune donnée de santé sur l'écran d'accueil.
                locked -> Text("Verrouillée", style = big)
                t == null -> Text("Connecte l'app pour afficher ton résumé", style = small)
                else -> {
                    val n = t.optJSONObject("last_night")
                    val asleep = n?.let { if (it.has("asleep_min") && !it.isNull("asleep_min")) it.getDouble("asleep_min") else it.optDouble("in_bed_min") }
                    Text("Nuit " + (asleep?.takeIf { !it.isNaN() }?.let { "${it.toInt() / 60} h ${"%02d".format(it.toInt() % 60)}" } ?: "—") +
                        (n?.takeIf { it.has("score") && !it.isNull("score") }?.let { " · score ${it.getInt("score")}" } ?: ""), style = big)
                    Text("Pas ${t.optInt("steps_today")} / $stepsGoal", style = small)
                    t.optJSONObject("resting_hr")?.let { Text("FC au repos ${it.optInt("bpm")} bpm", style = small) }
                }
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) = ParroWidget().updateAll(context)
    }
}

class ParroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ParroWidget()
}

object Widget {
    /** Rafraîchit le résumé (serveur) puis le widget ; silencieux si l'app n'est pas connectée. */
    suspend fun refresh(context: Context) {
        try {
            val t = Api.get(context, "today")
            Prefs(context).widgetSummary = t.toString()
        } catch (_: Exception) {
        }
        runCatching { ParroWidget.updateAll(context) }
    }
}
