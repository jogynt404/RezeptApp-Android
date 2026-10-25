package de.rezeptapp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.Ingredient
import de.rezeptapp.RecipeData
import de.rezeptapp.RecipeStore

/** Eine bearbeitbare Zutatenzeile. Eigene ID, damit Compose Zeilen beim Loeschen nicht vertauscht. */
private class IngRow(val key: Long, amount: String, unit: String, name: String) {
    var amount by mutableStateOf(amount)
    var unit by mutableStateOf(unit)
    var name by mutableStateOf(name)
}

private class StepRow(val key: Long, text: String) {
    var text by mutableStateOf(text)
}

val DEFAULT_TAGS = listOf("Schnell", "Vegetarisch", "Vegan", "Backen", "Dessert", "Frühstück", "Abendessen", "Meal Prep")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(id: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    val r = remember(id) { RecipeStore.get(id) }
    if (r == null) { LaunchedEffect(Unit) { onDone() }; return }
    val d = r.data ?: RecipeData()
    var keyGen by remember { mutableLongStateOf(0L) }
    fun nextKey(): Long { keyGen += 1; return keyGen }

    var title by remember { mutableStateOf(r.title) }
    var servings by remember { mutableStateOf(d.servings) }
    var time by remember { mutableStateOf(d.time) }
    var notes by remember { mutableStateOf(d.notes) }
    val ings = remember { mutableStateListOf<IngRow>().apply { d.ingredients.forEachIndexed { i, x -> add(IngRow(-1L - i, x.amount, x.unit, x.name)) } } }
    val steps = remember { mutableStateListOf<StepRow>().apply { d.steps.forEachIndexed { i, x -> add(StepRow(-1L - i, x)) } } }
    val tags = remember { mutableStateListOf<String>().apply { addAll(r.tags) } }
    var newTag by remember { mutableStateOf("") }
    val suggestions = remember(recipes) { (DEFAULT_TAGS + recipes.flatMap { it.tags }).distinct() }

    fun save() {
        val data = d.copy(
            title = title.trim(),
            servings = servings.trim(),
            time = time.trim(),
            notes = notes.trim(),
            ingredients = ings.map { Ingredient(it.amount.trim(), it.unit.trim(), it.name.trim()) }.filter { it.name.isNotEmpty() || it.amount.isNotEmpty() },
            steps = steps.map { it.text.trim() }.filter { it.isNotEmpty() },
            isRecipe = true,
        )
        RecipeStore.save(r.copy(title = title.trim().ifBlank { "Rezept" }, data = data, tags = tags.toList(), error = null))
        Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
        onDone()
    }

    Scaffold(topBar = {
        Bar("Bearbeiten", onDone) { TextButton(onClick = { save() }) { Text("Speichern") } }
    }) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Titel") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(servings, { servings = it }, Modifier.weight(1f), label = { Text("Portionen") }, placeholder = { Text("z. B. 4 Portionen") }, singleLine = true)
                OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text("Zeit") }, placeholder = { Text("z. B. 30 Min") }, singleLine = true)
            }

            SectionTitle("Kategorien")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { t ->
                    FilterChip(
                        selected = t in tags,
                        onClick = { if (t in tags) tags.remove(t) else tags.add(t) },
                        label = { Text(t) },
                    )
                }
                tags.filter { it !in suggestions }.forEach { t ->
                    FilterChip(selected = true, onClick = { tags.remove(t) }, label = { Text(t) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newTag, { newTag = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Neue Kategorie") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                IconButton(onClick = {
                    val t = newTag.trim().removePrefix("#")
                    if (t.isNotEmpty() && t !in tags) tags.add(t)
                    newTag = ""
                }) { Icon(Icons.Default.Add, "Kategorie hinzufügen") }
            }

            SectionTitle("Zutaten")
            ings.forEachIndexed { i, row ->
                key(row.key) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(row.amount, { row.amount = it }, Modifier.width(76.dp), placeholder = { Text("Menge") }, singleLine = true)
                        OutlinedTextField(row.unit, { row.unit = it }, Modifier.width(76.dp), placeholder = { Text("Einh.") }, singleLine = true)
                        OutlinedTextField(row.name, { row.name = it }, Modifier.weight(1f), placeholder = { Text("Zutat") }, singleLine = true)
                        IconButton(onClick = { ings.removeAt(i) }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Entfernen") }
                    }
                }
            }
            OutlinedButton(onClick = { ings.add(IngRow(nextKey(), "", "", "")) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Zutat")
            }

            SectionTitle("Zubereitung")
            steps.forEachIndexed { i, row ->
                key(row.key) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("${i + 1}.", Modifier.padding(top = 18.dp).width(26.dp), color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(row.text, { row.text = it }, Modifier.weight(1f), minLines = 2)
                        Column {
                            IconButton(onClick = { if (i > 0) { steps.removeAt(i); steps.add(i - 1, row) } }, Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Nach oben") }
                            IconButton(onClick = { steps.removeAt(i) }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Entfernen") }
                            IconButton(onClick = { if (i < steps.size - 1) { steps.removeAt(i); steps.add(i + 1, row) } }, Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Nach unten") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { steps.add(StepRow(nextKey(), "")) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Schritt")
            }

            SectionTitle("Hinweise")
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Tipps, Varianten ...") })

            Button(onClick = { save() }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Speichern") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
