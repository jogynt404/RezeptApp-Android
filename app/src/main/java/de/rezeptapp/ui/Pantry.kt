package de.rezeptapp.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantryScreen(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val pantry by PantryStore.items.collectAsStateWithLifecycle()
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var assume by remember { mutableStateOf(settings.assumeStaples) }
    var confirmClear by remember { mutableStateOf(false) }

    val matches = remember(pantry, recipes, assume) { PantryMatcher.suggest(recipes, pantry, assume) }
    // Vorschlaege zum schnellen Antippen: haeufige Zutaten aus deinen Rezepten
    val quick = remember(pantry, recipes) {
        recipes.flatMap { r -> r.data?.ingredients.orEmpty() }
            .map { it.name.replace(Regex("\\(.*?\\)"), "").trim() }
            .filter { it.isNotBlank() && it.split(" ").size <= 2 && it.length <= 20 && !PantryMatcher.isStaple(it) }
            .groupingBy { it.replaceFirstChar { c -> c.uppercase() } }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
            .filter { q -> pantry.none { PantryMatcher.matches(q, it) } }
            .take(12)
    }

    fun addInput() {
        if (input.isNotBlank()) PantryStore.add(input.split(",", "\n"))
        input = ""
    }

    Scaffold(topBar = {
        Bar("Vorrat") {
            IconButton(onClick = { confirmClear = true }, enabled = pantry.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, "Vorrat leeren") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Trage ein, was du zu Hause hast. Die App sucht die Rezepte, für die dir am wenigsten fehlt.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        input, { input = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("z. B. Nudeln, Tomaten, Feta") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addInput() }),
                    )
                    IconButton(onClick = { addInput() }) { Icon(Icons.Default.Add, "Hinzufügen") }
                }
            }
            if (pantry.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pantry.forEach { p ->
                        InputChip(selected = true, onClick = { PantryStore.remove(p) }, label = { Text(p) },
                            trailingIcon = { Icon(Icons.Default.Close, "Entfernen", Modifier.size(16.dp)) })
                    }
                }
            }
            if (quick.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Schnell hinzufügen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { q -> SuggestionChip(onClick = { PantryStore.add(listOf(q)) }, label = { Text("+ $q") }) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gewürze, Öl und Wasser sind da")
                        Text("Salz, Pfeffer & Co. zählen nicht als fehlend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(assume, { assume = it; settings.assumeStaples = it })
                }
            }
            item { SectionTitle("Passende Rezepte") }
            when {
                pantry.isEmpty() -> item { Text("Noch nichts im Vorrat eingetragen.", color = MaterialTheme.colorScheme.outline) }
                matches.isEmpty() -> item { Text("Keines deiner Rezepte passt zu diesem Vorrat.", color = MaterialTheme.colorScheme.outline) }
            }
            items(matches.take(30), key = { it.recipe.id }) { m -> MatchCard(m, onOpen) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Vorrat leeren?") },
        confirmButton = { TextButton(onClick = { PantryStore.clear(); confirmClear = false }) { Text("Leeren") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun MatchCard(m: PantryMatcher.Match, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { onOpen(m.recipe.id) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.recipe.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.missing.isEmpty()) Text("Alles da ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { m.score.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("Du hast ${m.have.size} von ${m.total} Zutaten", style = MaterialTheme.typography.bodySmall)
            if (m.missing.isNotEmpty()) {
                Text("Es fehlt: " + m.missing.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    ShoppingStore.add(m.missing.map { it.line() }, m.recipe.title)
                    Toast.makeText(ctx, "${m.missing.size} Zutaten auf der Einkaufsliste", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(0.dp)) { Text("Fehlendes auf die Einkaufsliste") }
            }
        }
    }
}
