package de.rezeptapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.Categories
import de.rezeptapp.RecipeStore

/** Alle bekannten Kategorien: Standardvorschlaege plus selbst angelegte. */
@Composable
internal fun knownCategories(extra: Collection<String> = emptyList()): List<String> {
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    val extraList = extra.toList()
    return remember(recipes, extraList) {
        (DEFAULT_TAGS + recipes.flatMap { it.tags } + extraList).distinctBy { it.lowercase() }
    }
}

/** Auswahl von Kategorien mit Feld fuer neue Kategorien. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryChips(selected: MutableList<String>) {
    var newTag by remember { mutableStateOf("") }
    val all = knownCategories(selected)
    fun addNew() {
        val t = newTag.trim().removePrefix("#")
        if (t.isNotEmpty() && selected.none { it.equals(t, true) }) selected.add(t)
        newTag = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            all.forEach { t ->
                val on = selected.any { it.equals(t, true) }
                FilterChip(selected = on, onClick = { if (on) selected.removeAll { it.equals(t, true) } else selected.add(t) }, label = { Text(t) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newTag, { newTag = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Neue Kategorie") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addNew() }))
            IconButton(onClick = { addNew() }) { Icon(Icons.Default.Add, "Kategorie hinzufügen") }
        }
    }
}

/** Kategorien eines einzelnen Rezepts festlegen. */
@Composable
internal fun RecipeCategoryDialog(recipeId: Long, onClose: () -> Unit) {
    val r = remember(recipeId) { RecipeStore.get(recipeId) }
    if (r == null) { LaunchedEffect(Unit) { onClose() }; return }
    val selected = remember { mutableStateListOf<String>().apply { addAll(r.tags) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Kategorien") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { CategoryChips(selected) } },
        confirmButton = {
            TextButton(onClick = {
                RecipeStore.get(recipeId)?.let { RecipeStore.save(it.copy(tags = selected.toList())) }
                onClose()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

/** Mehrere Rezepte auf einmal einsortieren. */
@Composable
internal fun BulkCategoryDialog(ids: List<Long>, onDone: () -> Unit, onClose: () -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${ids.size} Rezepte einsortieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kategorien wählen, dann hinzufügen oder entfernen.", style = MaterialTheme.typography.bodySmall)
                CategoryChips(selected)
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    RecipeStore.updateAll { r -> if (r.id in ids) selected.fold(r) { acc, t -> Categories.remove(acc, t) } else r }
                    onDone()
                }) { Text("Entfernen") }
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    RecipeStore.updateAll { r -> if (r.id in ids) selected.fold(r) { acc, t -> Categories.add(acc, t) } else r }
                    onDone()
                }) { Text("Hinzufügen") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

/** Kategorie umbenennen oder aus allen Rezepten entfernen. */
@Composable
internal fun ManageCategoryDialog(tag: String, onClose: () -> Unit) {
    var name by remember { mutableStateOf(tag) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Kategorie \"$tag\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                if (!confirmDelete) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Kategorie löschen", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("Die Rezepte bleiben erhalten, nur die Kategorie wird entfernt.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { RecipeStore.updateAll { Categories.remove(it, tag) }; onClose() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Ja, löschen") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() != tag, onClick = {
                RecipeStore.updateAll { Categories.rename(it, tag, name.trim()) }
                onClose()
            }) { Text("Umbenennen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}
