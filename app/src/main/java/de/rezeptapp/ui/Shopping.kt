package de.rezeptapp.ui

import android.content.Intent
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.ShoppingCategories
import de.rezeptapp.ShoppingStore

@Composable
fun ShoppingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by ShoppingStore.items.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    fun addInput() {
        if (input.isNotBlank()) ShoppingStore.add(input.split(",", "\n"))
        input = ""
    }

    Scaffold(topBar = {
        Bar("Einkaufsliste", onBack) {
            IconButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ShoppingStore.asText())
                }, "Einkaufsliste teilen"))
            }, enabled = items.any { !it.done }) { Icon(Icons.Default.Share, "Teilen") }
            IconButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, "Liste leeren") }
        }
    }) { pad ->
        val open = items.filter { !it.done }
            .groupBy { it.category }
            .toList()
            .sortedBy { (cat, _) -> ShoppingCategories.ORDER.indexOf(cat).let { if (it < 0) 99 else it } }
        val done = items.filter { it.done }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        input, { input = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Artikel hinzufügen (mehrere mit Komma)") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addInput() }),
                    )
                    IconButton(onClick = { addInput() }) { Icon(Icons.Default.Add, "Hinzufügen") }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) item {
                Text("Die Liste ist leer. Im Rezept auf \"Auf die Einkaufsliste\" tippen oder oben etwas eintragen.",
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 24.dp))
            }
            open.forEach { (cat, list) ->
                item(key = "cat-$cat") { SectionTitle(cat) }
                items(list, key = { it.id }) { it2 -> ShoppingRow(it2.text, it2.recipe, false, { ShoppingStore.toggle(it2.id) }, { ShoppingStore.remove(it2.id) }) }
            }
            if (done.isNotEmpty()) {
                item(key = "done-head") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ERLEDIGT (${done.size})", color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(top = 16.dp))
                        TextButton(onClick = { ShoppingStore.clearDone() }, modifier = Modifier.padding(top = 8.dp)) { Text("Entfernen") }
                    }
                }
                items(done, key = { it.id }) { it2 -> ShoppingRow(it2.text, it2.recipe, true, { ShoppingStore.toggle(it2.id) }, { ShoppingStore.remove(it2.id) }) }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Ganze Liste leeren?") },
        confirmButton = { TextButton(onClick = { ShoppingStore.clearAll(); confirmClear = false }) { Text("Leeren") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun ShoppingRow(text: String, recipe: String, done: Boolean, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(text, textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            if (recipe.isNotBlank()) Text(recipe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Entfernen") }
    }
}
