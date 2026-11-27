package fr.parro.santesync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.parro.santesync.Api
import fr.parro.santesync.Meals
import fr.parro.santesync.NotLoggedInException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

private fun portionsText(p: Double): String = when (p) {
    0.25 -> "¼"; 0.5 -> "½"; 0.75 -> "¾"
    else -> if (p % 1.0 == 0.0) p.toInt().toString() else "%.2f".format(p).trimEnd('0').trimEnd(',', '.').replace('.', ',')
}

private fun macros(v: Map<String, Double>): String =
    "P ${int(v["proteines"] ?: 0.0)} g · G ${int(v["glucides"] ?: 0.0)} g · L ${int(v["lipides"] ?: 0.0)} g"

/** Repas du jour : total, dépense de la montre, liste, et ajout (recette de la collection ou saisie libre). */
@Composable
fun MealsSection() {
    val ctx = LocalContext.current
    val c = P.c
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var meals by remember { mutableStateOf(emptyList<Meals.Meal>()) }
    var burned by remember { mutableStateOf<Double?>(null) }
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Meals.Meal?>(null) }
    val today = LocalDate.now()
    LaunchedEffect(tick) {
        meals = Meals.list(ctx).filter { it.at.atZone(ZoneId.systemDefault()).toLocalDate() == today }
        burned = runCatching {
            Api.get(ctx, "nutrition", mapOf("start" to today.toString(), "end" to today.toString()))
                .optJSONArray("days")?.optJSONObject(0)?.optJSONObject("burned")?.optDouble("total_kcal")?.takeIf { !it.isNaN() }
        }.getOrNull()
    }
    val total = Meals.NUTRIENTS.associateWith { k -> meals.sumOf { it.values[k] ?: 0.0 } }

    ParroCard(gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                SectionLabel("REPAS D'AUJOURD'HUI")
                Row(verticalAlignment = Alignment.Bottom) {
                    Num(int(total["kcal"] ?: 0.0), 30.sp, c.body)
                    Muted(" kcal", 14.sp, Modifier.padding(bottom = 4.dp))
                }
            }
            burned?.let { Muted("dépensé : ${int(it)} kcal\n(montre)", 12.sp) }
        }
        if (meals.isNotEmpty()) Muted(macros(total))
        meals.sortedBy { it.at }.forEach { m ->
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.label.ifEmpty { "Repas" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Muted(listOfNotNull(
                        Meals.SLOTS.toMap()[m.slot], m.at.atZone(ZoneId.systemDefault()).format(HOUR),
                        m.portions?.let { "${portionsText(it)} portion" + if (it > 1) "s" else "" },
                        if (m.pending) "pas encore envoyé" else null,
                    ).joinToString(" · "))
                }
                Num(int(m.kcal), 16.sp)
                IconButton(onClick = { toDelete = m }) {
                    Icon(ParroIcons.trash, contentDescription = "Supprimer", tint = c.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
        PrimaryButton("Ajouter un repas", { adding = true }, color = c.body)
        Muted("Recettes : calories estimées d'après la table Ciqual (ANSES).", 11.sp)
    }

    if (adding) AddMealDialog(onDismiss = { adding = false }) { slot, source, label, recipe, portions, values ->
        adding = false
        scope.launch { Meals.add(ctx, Instant.now(), slot, source, label, recipe, portions, values); Meals.flush(ctx); tick++ }
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ce repas ?") },
            text = { Text("${m.label} — ${int(m.kcal)} kcal") },
            confirmButton = { TextButton(onClick = {
                scope.launch { Meals.delete(ctx, m.id); Meals.flush(ctx); tick++ }; toDelete = null
            }) { Text("Supprimer", color = c.heart) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
            containerColor = c.surface,
        )
    }
}

private data class RecipeHit(val id: String, val title: String, val servings: Double?, val perServing: Map<String, Double>,
                             val complete: Boolean)

private fun hits(o: JSONObject): List<RecipeHit> {
    val a = o.optJSONArray("recipes") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { r ->
        val ps = r.optJSONObject("per_serving") ?: return@mapNotNull null
        RecipeHit(r.getString("id"), r.getString("title"), r.optDouble("servings").takeIf { !it.isNaN() },
            ps.keys().asSequence().associateWith { ps.optDouble(it, 0.0) }, r.optBoolean("complete", true))
    }
}

@Composable
private fun AddMealDialog(
    onDismiss: () -> Unit,
    onAdd: (slot: String, source: String, label: String, recipe: String?, portions: Double?, values: Map<String, Double>) -> Unit,
) {
    val ctx = LocalContext.current
    val c = P.c
    var slot by remember { mutableStateOf(Meals.slotFor(LocalTime.now())) }
    var mode by remember { mutableStateOf("recette") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RecipeHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<RecipeHit?>(null) }
    var portions by remember { mutableDoubleStateOf(1.0) }
    var label by remember { mutableStateOf("") }
    var kcalText by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.body, unfocusedBorderColor = c.line,
        focusedContainerColor = c.inset, unfocusedContainerColor = c.inset)

    LaunchedEffect(query, mode) {
        if (mode != "recette") return@LaunchedEffect
        delay(250) // pas une requête par lettre tapée
        error = null
        results = try {
            val o = Api.get(ctx, "recipes", mapOf("q" to query))
            if (!o.optBoolean("available")) { error = "Aucune collection de recettes sur le serveur."; emptyList() } else hits(o)
        } catch (e: NotLoggedInException) {
            error = "Connecte-toi (Profil) pour chercher dans tes recettes."; emptyList()
        } catch (e: Exception) {
            error = "Recettes indisponibles : ${e.message}"; emptyList()
        }
    }

    val kcalFree = kcalText.replace(',', '.').toDoubleOrNull()
    val canAdd = if (mode == "recette") chosen != null else (kcalFree != null && kcalFree in 0.0..10000.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter un repas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(Meals.SLOTS, slot, { slot = it }, accent = c.bodySoft)
                Segmented(listOf("recette" to "Recette", "saisie" to "Saisie libre"), mode, { mode = it }, accent = c.bodySoft)
                if (mode == "recette") {
                    val pick = chosen
                    if (pick == null) {
                        OutlinedTextField(query, { query = it }, label = { Text("Chercher une recette") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors)
                        error?.let { Muted(it, color = c.warn) }
                        results.take(30).forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { chosen = r; portions = if ((r.servings ?: 2.0) <= 1.0) 0.25 else 1.0 }
                                .heightIn(min = 44.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, fontSize = 14.sp, color = c.text)
                                    if (!r.complete) Muted("total partiel : un ingrédient sans correspondance", 11.sp, color = c.warn)
                                }
                                Muted("${int(r.perServing["kcal"] ?: 0.0)} kcal/portion", 12.sp)
                            }
                        }
                    } else {
                        Text(pick.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        if ((pick.servings ?: 2.0) <= 1.0) Muted("Recette notée pour une seule portion (pain, sauce…) : choisis la part mangée.")
                        else Muted("Recette pour ${portionsText(pick.servings ?: 1.0)} portions.")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stepper(portionsText(portions), c.body, { portions = (portions - 0.25).coerceAtLeast(0.25) },
                                { portions = (portions + 0.25).coerceAtMost(20.0) }, "portions")
                            Muted(if (portions == 1.0) "portion" else "portions")
                        }
                        val v = pick.perServing.mapValues { (_, x) -> x * portions }
                        Num("${int(v["kcal"] ?: 0.0)} kcal", 24.sp, c.body)
                        Muted(macros(v))
                        TextButton(onClick = { chosen = null }) { Text("Choisir une autre recette") }
                    }
                } else {
                    OutlinedTextField(label, { label = it.take(200) }, label = { Text("Quoi ? (ex. sandwich, restaurant)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors)
                    OutlinedTextField(kcalText, { kcalText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(6) },
                        label = { Text("Calories (kcal)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canAdd, onClick = {
                val pick = chosen
                if (mode == "recette" && pick != null) {
                    onAdd(slot, "recette", pick.title, pick.id, portions, pick.perServing.mapValues { (_, x) -> x * portions })
                } else if (kcalFree != null) {
                    onAdd(slot, "saisie", label.ifBlank { "Repas" }, null, null, mapOf("kcal" to kcalFree))
                }
            }) { Text("Ajouter", color = if (canAdd) c.body else c.faint) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        containerColor = c.surface,
    )
}
