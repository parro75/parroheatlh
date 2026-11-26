package fr.parro.santesync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- Journal des accès (26/11/2026) : qui a lu ou écrit quoi, quand, d'où ------------------------------

private val SECURITY = setOf("appairage", "connexion", "connexion-echec", "connexion-verrouillee", "jeton-rejoue",
    "client-enregistre", "ecriture-refusee", "jetons-emis", "jetons-revoques")
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun who(actor: String): String = when {
    actor == "app" -> "Cette app"
    actor.startsWith("claude:") -> "Claude"
    actor == "claude" -> "Claude"
    actor == "job" -> "Rapport (IA locale)"
    actor == "ha" -> "Home Assistant"
    actor.startsWith("appareil:") -> "Téléphone ${actor.removePrefix("appareil:")}"
    actor == "proprietaire" -> "Toi"
    actor == "jeton-ecriture" -> "Jeton d'écriture"
    actor == "systeme" -> "Serveur"
    else -> "Inconnu"
}

private fun what(action: String): String {
    val map = mapOf(
        "appairage" to "Appairage d'un appareil", "connexion" to "Connexion réussie", "connexion-echec" to "Connexion refusée",
        "connexion-verrouillee" to "Connexion bloquée", "jeton-rejoue" to "Jeton rejoué : session révoquée",
        "client-enregistre" to "Nouveau client OAuth", "ecriture-refusee" to "Écriture refusée",
        "jetons-emis" to "Jetons émis", "jetons-revoques" to "Déconnexion",
    )
    map[action]?.let { return it }
    if (action.startsWith("mcp ")) return "Lecture : " + action.removePrefix("mcp ").replace('_', ' ')
    val (method, path) = action.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val leaf = path.removePrefix("/api/v1/").removePrefix("report-job/")
    return when {
        path == "/ingest" -> "Synchro (écriture)"
        path.startsWith("/ingest-export") -> "Export Samsung (envoi)"
        method == "GET" -> "Lecture : " + leaf.replace('_', ' ').replace("/", " › ")
        else -> "$method $leaf"
    }
}

@Composable
fun AccessLogScreen(refresh: Int, onLogin: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = P.c
    var filter by rememberSaveable { mutableStateOf("all") }
    val state by rememberLoad(refresh) { Api.get(ctx, "access-log", mapOf("limit" to "500")) }
    Screen {
        DetailHeader("Journal des accès", onBack)
        Loaded(state, onLogin) { res ->
            val chain = res.optJSONObject("chain")
            val ok = chain?.optBoolean("ok") == true
            ParroCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(if (ok) c.good else c.heart, 10.dp)
                    CardTitle(if (ok) "Chaîne intacte" else "⚠ Chaîne rompue : une ligne a été modifiée ou supprimée",
                        "${chain?.optInt("entries") ?: 0} lignes · chaque ligne scelle la précédente (SHA-256)")
                }
                Muted("Toute lecture ou écriture de tes données est tracée ici : par cette app, Claude, le rapport, " +
                    "Home Assistant ou le téléphone. Aucune donnée de santé n'y figure.", 12.sp)
            }
            Segmented(listOf("all" to "Tout", "read" to "Lectures", "write" to "Écritures", "sec" to "Sécurité"), filter, { filter = it })
            val all = res.getJSONArray("entries").objects()
            val list = all.filter { e ->
                val a = e.optString("action")
                when (filter) {
                    "read" -> a.startsWith("GET ") || a.startsWith("mcp ")
                    "write" -> a.startsWith("POST /ingest") || a.startsWith("PUT /ingest") || a.startsWith("POST /ingest-export")
                    "sec" -> a in SECURITY || e.optInt("status") >= 400
                    else -> true
                }
            }
            if (list.isEmpty()) Muted("Aucune ligne pour ce filtre.")
            var lastDay = ""
            list.forEach { e ->
                val t = runCatching { Instant.parse(e.optString("ts").replace("+00:00", "Z")).atZone(ZoneId.systemDefault()) }.getOrNull()
                val day = t?.toLocalDate()?.let { fullDate(it) } ?: ""
                if (day != lastDay) { Muted(day, 12.sp, Modifier.padding(top = 6.dp)); lastDay = day }
                val status = e.optInt("status")
                val alert = e.optString("action") in SECURITY || status >= 400
                val color: Color = when { status >= 400 -> c.heart; alert -> c.warn; else -> c.faint }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(color, modifier = Modifier.padding(top = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(what(e.optString("action")), fontSize = 14.sp, fontWeight = if (alert) FontWeight.SemiBold else FontWeight.Normal,
                            color = c.text)
                        Muted(listOfNotNull(who(e.optString("actor")), e.optString("detail").takeIf { it.isNotBlank() },
                            e.optString("ip").takeIf { it.isNotBlank() }?.let { "IP $it" },
                            status.takeIf { it >= 400 }?.let { "refusé ($it)" }).joinToString(" · "), 12.sp)
                    }
                    Muted(t?.format(TIME) ?: "", 11.sp)
                }
            }
        }
    }
}
