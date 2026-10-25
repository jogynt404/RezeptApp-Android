package de.rezeptapp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

private enum class PlanMode(val label: String) { DAY("Tag"), WEEK("Woche"), MONTH("Monat") }

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val shortFmt = DateTimeFormatter.ofPattern("EEE d.M.", Locale.GERMAN)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)

private fun range(mode: PlanMode, anchor: LocalDate): List<LocalDate> = when (mode) {
    PlanMode.DAY -> listOf(anchor)
    PlanMode.WEEK -> anchor.with(DayOfWeek.MONDAY).let { m -> (0L until 7L).map { m.plusDays(it) } }
    PlanMode.MONTH -> anchor.withDayOfMonth(1).let { f -> (0L until f.lengthOfMonth().toLong()).map { f.plusDays(it) } }
}

private fun entryTitle(e: MealEntry, recipes: List<Recipe>): String =
    e.recipeId?.let { id -> recipes.firstOrNull { it.id == id }?.title } ?: e.text.ifBlank { "?" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val entries by MealPlanStore.entries.collectAsStateWithLifecycle()
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(PlanMode.WEEK) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showShopping by remember { mutableStateOf(false) }
    val days = range(mode, anchor)
    val activeSlots = settings.planSlots

    Scaffold(topBar = {
        Bar("Essensplan") {
            IconButton(onClick = { showShopping = true }) { Icon(Icons.Default.AddShoppingCart, "Einkaufsliste aus Plan") }
            IconButton(onClick = { showGenerate = true }) { Icon(Icons.Default.AutoAwesome, "Plan erstellen") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Ansicht waehlen
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                PlanMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, PlanMode.entries.size)) { Text(m.label) }
                }
            }
            // Zeitraum blaettern
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    anchor = when (mode) { PlanMode.DAY -> anchor.minusDays(1); PlanMode.WEEK -> anchor.minusWeeks(1); PlanMode.MONTH -> anchor.minusMonths(1) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Zurück") }
                Text(
                    when (mode) {
                        PlanMode.DAY -> anchor.format(dayFmt)
                        PlanMode.WEEK -> "KW ${days.first().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}: ${days.first().format(shortFmt)} bis ${days.last().format(shortFmt)}"
                        PlanMode.MONTH -> anchor.format(monthFmt)
                    },
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = {
                    anchor = when (mode) { PlanMode.DAY -> anchor.plusDays(1); PlanMode.WEEK -> anchor.plusWeeks(1); PlanMode.MONTH -> anchor.plusMonths(1) }
                }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Weiter") }
            }
            if (LocalDate.now() !in days) {
                TextButton(onClick = { anchor = LocalDate.now() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Heute") }
            }
            if (recipes.isEmpty()) {
                Note("Speichere zuerst ein paar Rezepte. Danach kannst du sie hier einplanen oder einen Plan erstellen lassen.")
            }

            when (mode) {
                PlanMode.DAY -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealEntry.SLOTS.forEach { slot ->
                        SlotRow(anchor, slot, entries, recipes, onOpen) { picking = anchor to slot }
                    }
                }
                PlanMode.WEEK -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(days, key = { it.toString() }) { d -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val isToday = d == LocalDate.now()
                        Text(d.format(dayFmt), fontWeight = FontWeight.Bold, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).clickable { anchor = d; mode = PlanMode.DAY })
                        val dayEntries = entries.filter { it.date == d.toString() }
                        MealEntry.SLOTS.filter { it in activeSlots || dayEntries.any { e -> e.slot == it } }.forEach { slot ->
                            SlotRow(d, slot, entries, recipes, onOpen) { picking = d to slot }
                        }
                    } }
                }
                PlanMode.MONTH -> MonthGrid(anchor, entries, recipes) { d -> anchor = d; mode = PlanMode.DAY }
            }
        }
    }

    picking?.let { (d, slot) ->
        RecipePickerDialog(
            title = "$slot · ${d.format(shortFmt)}",
            recipes = recipes,
            onPick = { id -> MealPlanStore.set(d.toString(), slot, id); picking = null },
            onText = { t -> MealPlanStore.set(d.toString(), slot, null, t); picking = null },
            onDismiss = { picking = null },
        )
    }
    if (showGenerate) GenerateDialog(days, mode.label, recipes, entries) { showGenerate = false }
    if (showShopping) PlanShoppingDialog(days, mode.label, entries, recipes) { showShopping = false }
}

@Composable
private fun SlotRow(date: LocalDate, slot: String, entries: List<MealEntry>, recipes: List<Recipe>, onOpen: (Long) -> Unit, onPick: () -> Unit) {
    val e = entries.firstOrNull { it.date == date.toString() && it.slot == slot }
    Surface(color = if (e != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp),
        tonalElevation = if (e != null) 0.dp else 1.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { if (e?.recipeId != null) onOpen(e.recipeId) else onPick() }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(slot, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (e != null) Text(entryTitle(e, recipes), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                else Text("+ hinzufügen", color = MaterialTheme.colorScheme.primary)
            }
            if (e != null) {
                IconButton(onClick = onPick) { Icon(Icons.Default.Edit, "Ändern") }
                IconButton(onClick = { MealPlanStore.remove(e.id) }) { Icon(Icons.Default.Close, "Entfernen") }
            }
        }
    }
}

@Composable
private fun MonthGrid(anchor: LocalDate, entries: List<MealEntry>, recipes: List<Recipe>, onDay: (LocalDate) -> Unit) {
    val first = anchor.withDayOfMonth(1)
    val start = first.with(DayOfWeek.MONDAY)
    val weeks = ((first.lengthOfMonth() + (first.dayOfWeek.value - 1)) + 6) / 7
    val byDate = entries.groupBy { it.date }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        Row {
            listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dIdx in 0 until 7) {
                    val d = start.plusDays((w * 7 + dIdx).toLong())
                    val inMonth = d.month == anchor.month
                    val list = byDate[d.toString()].orEmpty()
                    val today = d == LocalDate.now()
                    Column(
                        Modifier.weight(1f).height(84.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (today) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (inMonth) 0.6f else 0.2f))
                            .clickable { onDay(d) }.padding(3.dp),
                    ) {
                        Text("${d.dayOfMonth}", fontWeight = if (today) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp,
                            color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        list.take(3).forEach { e ->
                            Text(entryTitle(e, recipes), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun RecipePickerDialog(title: String, recipes: List<Recipe>, onPick: (Long) -> Unit, onText: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var free by remember { mutableStateOf("") }
    val list = recipes.filter { q.isBlank() || it.title.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Rezept suchen ...") })
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(list, key = { it.id }) { r ->
                        Text(r.title, Modifier.fillMaxWidth().clickable { onPick(r.id) }.padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (list.isEmpty()) item { Text("Kein Rezept gefunden", color = MaterialTheme.colorScheme.outline) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(free, { free = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("oder Text, z. B. Reste") })
                    IconButton(onClick = { if (free.isNotBlank()) onText(free.trim()) }) { Icon(Icons.Default.Add, "Übernehmen") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (list.isNotEmpty()) onPick(list.random().id) }, enabled = list.isNotEmpty()) {
                Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Zufällig")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateDialog(days: List<LocalDate>, label: String, recipes: List<Recipe>, entries: List<MealEntry>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val slots = remember { mutableStateListOf<String>().apply { addAll(settings.planSlots) } }
    var tag by remember { mutableStateOf<String?>(null) }
    var onlyEmpty by remember { mutableStateOf(true) }
    var usePantry by remember { mutableStateOf(false) }
    val pantry by PantryStore.items.collectAsStateWithLifecycle()
    val allTags = remember(recipes) { recipes.flatMap { it.tags }.distinct().sorted() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Plan erstellen: $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Die App verteilt deine gespeicherten Rezepte möglichst abwechslungsreich auf ${days.size} ${if (days.size == 1) "Tag" else "Tage"}.", style = MaterialTheme.typography.bodySmall)
                Text("Mahlzeiten", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealEntry.SLOTS.forEach { s ->
                        FilterChip(selected = s in slots, onClick = { if (s in slots) slots.remove(s) else slots.add(s) }, label = { Text(s) })
                    }
                }
                if (allTags.isNotEmpty()) {
                    Text("Nur Kategorie", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text("Alle") })
                        allTags.forEach { t -> FilterChip(selected = tag == t, onClick = { tag = t }, label = { Text(t) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onlyEmpty, { onlyEmpty = it }); Text("Nur leere Plätze füllen")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(usePantry, { usePantry = it }, enabled = pantry.isNotEmpty())
                    Text(if (pantry.isNotEmpty()) "Vorrat zuerst verwerten" else "Vorrat zuerst verwerten (Vorrat ist leer)")
                }
                TextButton(onClick = { MealPlanStore.clearRange(days.map { it.toString() }); onClose() }) { Text("Zeitraum leeren", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = slots.isNotEmpty(), onClick = {
                settings.planSlots = slots.toSet()
                val ordered = MealEntry.SLOTS.filter { it in slots }
                val assume = settings.assumeStaples
                val scorer: ((Recipe) -> Double)? = if (usePantry) {
                    { r: Recipe -> PantryMatcher.match(r, pantry, assume)?.score ?: 0.0 }
                } else null
                try {
                    val made = PlanGenerator.generate(days, ordered, recipes, entries, onlyEmpty, tag, pantryScore = scorer)
                    MealPlanStore.putAll(made)
                    Toast.makeText(ctx, if (made.isEmpty()) "Alle Plätze waren schon belegt" else "${made.size} Mahlzeiten geplant", Toast.LENGTH_SHORT).show()
                    onClose()
                } catch (e: PlanGenerator.NoRecipesException) {
                    Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                }
            }) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@Composable
private fun PlanShoppingDialog(days: List<LocalDate>, label: String, entries: List<MealEntry>, recipes: List<Recipe>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var persons by remember { mutableIntStateOf(settings.household) }
    var fromToday by remember { mutableStateOf(true) }
    val today = LocalDate.now()
    val used = days.filter { !fromToday || !it.isBefore(today) }.map { it.toString() }.toSet()
    val planned = entries.filter { it.date in used && it.recipeId != null }.mapNotNull { e -> recipes.firstOrNull { it.id == e.recipeId } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Einkaufsliste: $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${planned.size} geplante Rezepte. Gleiche Zutaten werden zusammengefasst.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Personen", Modifier.weight(1f))
                    IconButton(onClick = { if (persons > 1) persons-- }) { Icon(Icons.Default.Remove, "Weniger") }
                    Text("$persons", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (persons < 20) persons++ }) { Icon(Icons.Default.Add, "Mehr") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fromToday, { fromToday = it }); Text("Erst ab heute")
                }
                Text("Zutaten mit Portionsangabe werden auf die Personenzahl umgerechnet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(enabled = planned.isNotEmpty(), onClick = {
                settings.household = persons
                val merged = IngredientMerger.merge(planned.flatMap { IngredientMerger.forHousehold(it, persons) })
                ShoppingStore.add(merged.map { it.line() }, "Essensplan")
                Toast.makeText(ctx, "${merged.size} Zutaten auf der Einkaufsliste", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}
