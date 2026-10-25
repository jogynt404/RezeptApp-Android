package de.rezeptapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Orange = Color(0xFFC2541B)

@Composable
fun RezeptTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFE8794A), onPrimary = Color.White, background = Color(0xFF1B1916), surface = Color(0xFF24211D),
        surfaceVariant = Color(0xFF3A2A20), secondaryContainer = Color(0xFF3A2A20),
    ) else lightColorScheme(
        primary = Orange, onPrimary = Color.White, background = Color(0xFFFAF7F2), surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFFBE9DF), secondaryContainer = Color(0xFFFBE9DF),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = actions,
    )
}

// ---------------- Startseite ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit, onShopping: () -> Unit, onImport: (String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    val job by ImportService.state.collectAsStateWithLifecycle()
    val shopping by ShoppingStore.items.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val modelsMissing = ModelManager.llmPath(ctx) == null
    val allTags = remember(recipes) { recipes.flatMap { it.tags }.distinct().sortedBy { it.lowercase() } }
    val openItems = shopping.count { !it.done }

    Scaffold(topBar = {
        Bar("RezeptApp") {
            IconButton(onClick = onShopping) {
                BadgedBox(badge = { if (openItems > 0) Badge { Text("$openItems") } }) { Icon(Icons.Default.ShoppingCart, "Einkaufsliste") }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (modelsMissing) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.clickable { onSettings() }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Erster Schritt: KI-Modelle laden", fontWeight = FontWeight.Bold)
                        Text("Einmalig ca. 2 GB im WLAN. Danach läuft die KI offline. Hier tippen.")
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tipp: In Instagram, TikTok, YouTube oder im Browser auf Teilen und dann auf RezeptApp tippen.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Link zum Post oder zur Webseite") },
                            trailingIcon = {
                                IconButton(onClick = { clipboard.getText()?.text?.let { url = RecipeParser.extractUrl(it) ?: it } }) {
                                    Icon(Icons.Default.ContentPaste, "Einfügen")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onImport(url.trim(), audio) }),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = audio, onCheckedChange = { audio = it })
                            Text("Video immer anhören")
                        }
                        Button(
                            onClick = {
                                val u = RecipeParser.extractUrl(url) ?: url.trim()
                                if (RecipeParser.isSupported(u)) { onImport(u, audio); url = "" }
                                else Toast.makeText(ctx, "Bitte einen gültigen Link einfügen (beginnt mit https://).", Toast.LENGTH_LONG).show()
                            },
                            enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        ) { Text("Rezept auslesen") }
                    }
                }
            }
            if (job.running || job.error != null || job.doneRecipeId != null) item { JobCard(job, onOpen) }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Rezepte oder Zutaten suchen ...") },
                )
            }
            if (allTags.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tagFilter == null, onClick = { tagFilter = null }, label = { Text("Alle") })
                    allTags.forEach { t ->
                        FilterChip(selected = tagFilter == t, onClick = { tagFilter = if (tagFilter == t) null else t }, label = { Text(t) })
                    }
                }
            }
            val q = query.trim().lowercase()
            val tf = tagFilter
            val list = recipes.filter { r ->
                (tf == null || r.tags.contains(tf)) && (q.isEmpty() ||
                    r.title.lowercase().contains(q) || r.author.lowercase().contains(q) ||
                    r.tags.any { it.lowercase().contains(q) } ||
                    r.data?.ingredients?.any { it.name.lowercase().contains(q) } == true)
            }
            if (list.isEmpty()) item {
                Text(if (recipes.isEmpty()) "Noch keine Rezepte" else "Nichts gefunden", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(24.dp))
            }
            items(list, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(r.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(r.created))
                        Text(listOf(r.author, date, if (r.error != null) "⚠ nicht sortiert" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (r.tags.isNotEmpty()) Text(r.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: JobState, onOpen: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                job.running -> {
                    Text(job.step, fontWeight = FontWeight.SemiBold)
                    if (job.progress >= 0) LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (job.queued > 1) Text("${job.queued - 1} weitere in der Warteschlange", style = MaterialTheme.typography.bodySmall)
                }
                job.error != null -> {
                    Text("Hat nicht geklappt", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text(job.error)
                    TextButton(onClick = { ImportService.clearResult() }) { Text("Ausblenden") }
                }
                job.doneRecipeId != null -> {
                    Text(if (job.warning != null) "Gespeichert, aber nicht sortiert" else "Fertig ✓", fontWeight = FontWeight.Bold)
                    job.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        Button(onClick = { onOpen(job.doneRecipeId); ImportService.clearResult() }) { Text("Öffnen") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { ImportService.clearResult() }) { Text("Ausblenden") }
                    }
                }
            }
        }
    }
}

// ---------------- Rezept ----------------

@Composable
fun DetailScreen(id: Long, onBack: () -> Unit, onRedo: () -> Unit, onEdit: () -> Unit, onTranslate: () -> Unit) {
    val ctx = LocalContext.current
    val recipes by RecipeStore.recipes.collectAsStateWithLifecycle()
    val r = recipes.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showShopping by remember { mutableStateOf(false) }
    val base = r?.data?.servings?.let { Scaler.baseServings(it) }
    // Bei bekannter Portionszahl wird in Portionen gezaehlt, sonst als Faktor
    var portions by remember(id, base) { mutableIntStateOf(base ?: 1) }
    var factorSteps by remember(id) { mutableIntStateOf(4) } // 4/4 = x1
    val factor = if (base != null) portions.toDouble() / base else factorSteps / 4.0
    val scaled = remember(r, factor) { r?.data?.ingredients?.let { Scaler.scale(it, factor) } ?: emptyList() }

    Scaffold(topBar = {
        Bar("Rezept", onBack) {
            if (r != null) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Bearbeiten") }
                IconButton(onClick = {
                    val text = if (factor == 1.0) r.toMarkdown() else r.copy(data = r.data?.let { dd ->
                        dd.copy(ingredients = scaled, servings = if (base != null) "$portions Portionen" else dd.servings)
                    }).toMarkdown()
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, r.title); putExtra(Intent.EXTRA_TEXT, text)
                    }, "Rezept teilen"))
                }) { Icon(Icons.Default.Share, "Teilen") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Löschen") }
            }
        }
    }) { pad ->
        if (r == null) { Box(Modifier.padding(pad).padding(24.dp)) { Text("Rezept nicht gefunden.") }; return@Scaffold }
        val d = r.data
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(r.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val meta = listOfNotNull(d?.time?.takeIf { it.isNotBlank() }, r.author.takeIf { it.isNotBlank() }?.let { "von $it" })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = MaterialTheme.colorScheme.outline)
            if (r.tags.isNotEmpty()) Text(r.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
            SourceLink(r.url)
            r.error?.let { Note(it, error = true) }
            if (d != null && d.isRecipe && !LanguageDetector.isGerman(d)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dieses Rezept ist noch nicht auf Deutsch.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onTranslate) { Text("Übersetzen") }
                    }
                }
            }
            if (d?.isRecipe == false) Note("Die KI hat hier kein Rezept gefunden. Probiere \"Video immer anhören\" oder trage es über den Stift selbst ein.")

            SectionTitle("Zutaten")
            if (d != null && d.ingredients.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (base != null) { if (portions > 1) portions-- } else if (factorSteps > 1) factorSteps-- }) { Icon(Icons.Default.Remove, "Weniger") }
                        Text(
                            if (base != null) "$portions ${if (portions == 1) "Portion" else "Portionen"}" else "Menge ×${Scaler.format(factor)}",
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        )
                        IconButton(onClick = { if (base != null) { if (portions < 99) portions++ } else if (factorSteps < 40) factorSteps++ }) { Icon(Icons.Default.Add, "Mehr") }
                    }
                }
            }
            if (d == null || d.ingredients.isEmpty()) Text("keine erkannt", color = MaterialTheme.colorScheme.outline)
            scaled.forEach { ing ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(listOf(ing.amount, ing.unit).filter { it.isNotBlank() }.joinToString(" "), fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                    Text(ing.name)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            if (scaled.isNotEmpty()) FilledTonalButton(onClick = { showShopping = true }) {
                Icon(Icons.Default.AddShoppingCart, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Auf die Einkaufsliste")
            }

            SectionTitle("Zubereitung")
            if (d == null || d.steps.isEmpty()) Text("keine erkannt", color = MaterialTheme.colorScheme.outline)
            d?.steps?.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                    Text(s)
                }
            }
            if (!d?.notes.isNullOrBlank()) Note("Hinweise: ${d!!.notes}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRedo, enabled = r.sourceText().isNotBlank()) { Text("Neu auswerten") }
                FilledTonalButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Originaltext ausblenden" else "Originaltext") }
            }
            if (showRaw) {
                if (r.description.isNotBlank()) { SectionTitle("Beschreibung"); Text(r.description, style = MaterialTheme.typography.bodySmall) }
                if (r.transcript.isNotBlank()) { SectionTitle("Gesprochen im Video"); Text(r.transcript, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete && r != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Rezept löschen?") },
        text = { Text(r.title) },
        confirmButton = { TextButton(onClick = { RecipeStore.delete(r.id); confirmDelete = false; onBack() }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
    )
    if (showShopping && r != null) AddToShoppingDialog(r.title, scaled) { showShopping = false }
}

/** Quell-Link: Plattform-Name und vollstaendige Adresse, antippen oeffnet, lang druecken kopiert. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SourceLink(url: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val host = remember(url) { runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m.") }.getOrDefault("") }
    val platform = when {
        host.contains("instagram") -> "Instagram"
        host.contains("tiktok") -> "TikTok"
        host.contains("facebook") || host.contains("fb.") -> "Facebook"
        host.contains("youtu") -> "YouTube"
        else -> host
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    onLongClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                        Toast.makeText(ctx, "Link kopiert", Toast.LENGTH_SHORT).show()
                    },
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Quelle: $platform", fontWeight = FontWeight.SemiBold)
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.OpenInNew, "Öffnen", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddToShoppingDialog(title: String, ingredients: List<Ingredient>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val checked = remember { mutableStateListOf(*Array(ingredients.size) { true }) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Auf die Einkaufsliste") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ingredients.size) { i ->
                    Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Text(ingredients[i].line())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val picked = ingredients.filterIndexed { i, _ -> checked[i] }.map { it.line() }
                ShoppingStore.add(picked, title)
                Toast.makeText(ctx, "${picked.size} Zutaten hinzugefügt", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@Composable
internal fun SectionTitle(t: String) = Text(t.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))

@Composable
internal fun Note(t: String, error: Boolean = false) = Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
    Text(t, Modifier.padding(12.dp).fillMaxWidth(), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
}

// ---------------- Einstellungen ----------------

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var whisper by remember { mutableStateOf(settings.whisperModel) }
    var lang by remember { mutableStateOf(settings.language) }
    var gpu by remember { mutableStateOf(settings.preferGpu) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            val ok = withContext(Dispatchers.IO) { runCatching { ModelManager.importCustomLlm(ctx, uri) }.isSuccess }
            importing = false
            Toast.makeText(ctx, if (ok) "Eigenes Modell übernommen" else "Datei konnte nicht kopiert werden", Toast.LENGTH_LONG).show()
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Backup.export(ctx, uri) } }
            Toast.makeText(ctx, res.fold({ "$it Rezepte gesichert" }, { "Sicherung fehlgeschlagen: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Backup.import(ctx, uri) } }
            Toast.makeText(ctx, res.fold({ "$it Rezepte wiederhergestellt" }, { "Einlesen fehlgeschlagen: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(topBar = { Bar("Einstellungen", onBack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("KI-Modelle")
            Text("Werden einmalig geladen (am besten im WLAN). Danach arbeitet die KI komplett offline.", style = MaterialTheme.typography.bodySmall)
            ModelCard(ModelManager.LLM, tick, extra = if (ModelManager.hasCustomLlm(ctx)) "Es wird dein eigenes Modell verwendet." else null)
            ModelCard(ModelManager.WHISPER.getValue(whisper), tick)

            SectionTitle("Spracherkennung")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("small" to "Genau (small)", "base" to "Schnell (base)").forEach { (k, label) ->
                    FilterChip(selected = whisper == k, onClick = { whisper = k; settings.whisperModel = k }, label = { Text(label) })
                }
            }
            Text("Sprache im Video", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "Automatisch", "de" to "Deutsch", "en" to "Englisch").forEach { (k, label) ->
                    FilterChip(selected = lang == k, onClick = { lang = k; settings.language = k }, label = { Text(label) })
                }
            }

            SectionTitle("Leistung")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Grafikchip nutzen (GPU)")
                    Text("Schneller. Falls es abstürzt, hier ausschalten.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = gpu, onCheckedChange = { gpu = it; settings.preferGpu = it; Structurer.close() })
            }

            SectionTitle("Anmeldung")
            Text("Instagram und Facebook zeigen Posts oft nur angemeldet. Die Anmeldung bleibt auf deinem Handy.", style = MaterialTheme.typography.bodySmall)
            val loggedIn = remember(tick / 5) { CookieExport.hasCookies(ctx) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLogin) { Text(if (loggedIn) "Erneut anmelden" else "Bei Instagram anmelden") }
                if (loggedIn) OutlinedButton(onClick = { CookieExport.clear(ctx); tick++ }) { Text("Abmelden") }
            }

            SectionTitle("Deutsch und Einheiten")
            Text("Neue Rezepte werden automatisch übersetzt und auf °C, g, ml, EL und TL umgerechnet. Hier lassen sich ältere Rezepte nachträglich umrechnen.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                var n = 0
                RecipeStore.recipes.value.forEach { r ->
                    val d = r.data ?: return@forEach
                    val c = UnitConverter.convert(d)
                    if (c != d) { RecipeStore.save(r.copy(data = c)); n++ }
                }
                Toast.makeText(ctx, if (n == 0) "Alles schon in deutschen Einheiten" else "$n Rezepte umgerechnet", Toast.LENGTH_LONG).show()
            }) { Text("Alle Rezepte umrechnen") }

            SectionTitle("Sicherung")
            Text("Alle Rezepte und die Einkaufsliste in eine Datei sichern, z. B. in Google Drive oder auf den PC. Beim Einlesen werden gleiche Rezepte ersetzt.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
                    exporter.launch("RezeptApp-Sicherung-$date.json")
                }) { Text("Sichern") }
                OutlinedButton(onClick = { restorer.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }) { Text("Einlesen") }
            }

            SectionTitle("Eigenes Modell (für Profis)")
            Text("Eine .litertlm-Datei vom Handy wählen, z. B. Gemma von huggingface.co/litert-community.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) { Text(if (importing) "Kopiere ..." else "Datei wählen") }
                if (ModelManager.hasCustomLlm(ctx)) OutlinedButton(onClick = { ModelManager.removeCustomLlm(ctx); Structurer.close(); tick++ }) { Text("Entfernen") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, tick: Int, extra: String? = null) {
    val ctx = LocalContext.current
    val p = remember(tick, spec.key) { ModelManager.progress(ctx, spec) }
    val ready = remember(tick, spec.key) { ModelManager.isReady(ctx, spec) }
    Card {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.label, fontWeight = FontWeight.SemiBold)
            when {
                ready -> Text("✓ Bereit (${p.totalBytes / 1_000_000} MB)", color = MaterialTheme.colorScheme.primary)
                p.running -> {
                    Text("Lädt ... ${p.doneBytes / 1_000_000} von ca. ${p.totalBytes / 1_000_000} MB")
                    LinearProgressIndicator(progress = { if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    if (p.failed) Text("Download fehlgeschlagen. Bitte erneut versuchen.", color = MaterialTheme.colorScheme.error)
                    Text("ca. ${spec.totalMb} MB", style = MaterialTheme.typography.bodySmall)
                }
            }
            extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready && !p.running) Button(onClick = { ModelManager.download(ctx, spec) }) { Text("Herunterladen") }
                if (ready || p.running) OutlinedButton(onClick = { ModelManager.delete(ctx, spec); Structurer.close() }) { Text(if (ready) "Löschen" else "Abbrechen") }
            }
        }
    }
}

// ---------------- Anmeldung ----------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var site by remember { mutableStateOf("https://www.instagram.com/accounts/login/") }
    Scaffold(topBar = {
        Bar("Anmelden", onDone) {
            TextButton(onClick = {
                val n = CookieExport.export(ctx)
                Toast.makeText(ctx, if (n > 0) "Anmeldung gespeichert" else "Keine Anmeldung gefunden", Toast.LENGTH_LONG).show()
                onDone()
            }) { Text("Fertig") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Instagram" to "https://www.instagram.com/accounts/login/", "Facebook" to "https://www.facebook.com/login/", "TikTok" to "https://www.tiktok.com/login").forEach { (l, u) ->
                    FilterChip(selected = site == u, onClick = { site = u }, label = { Text(l) })
                }
            }
            Text("Melde dich an und tippe dann oben auf Fertig.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(site)
                    }
                },
                update = { if (it.url?.startsWith(site.substringBefore("/accounts").substringBefore("/login")) != true) it.loadUrl(site) },
            )
        }
    }
}
