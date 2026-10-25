package de.rezeptapp

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Rezepte als JSON-Dateien im privaten App-Speicher. */
object RecipeStore {
    private lateinit var dir: File
    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "rezepte").apply { mkdirs() }
        reload()
    }

    @Synchronized
    private fun reload() {
        _recipes.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { Recipe.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.created }
    }

    fun get(id: Long): Recipe? = _recipes.value.firstOrNull { it.id == id }

    fun findByUrl(url: String): Recipe? = _recipes.value.firstOrNull { it.url == url }

    @Synchronized
    fun save(r: Recipe) {
        File(dir, "${r.id}.json").writeText(r.toJson().toString(2))
        reload()
    }

    @Synchronized
    fun delete(id: Long) {
        File(dir, "$id.json").delete()
        reload()
        runCatching { MealPlanStore.dropRecipe(id) }
    }
}

/** Einfache Einstellungen. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    var whisperModel: String
        get() = p.getString("whisper", "small") ?: "small"
        set(v) = p.edit().putString("whisper", v).apply()

    /** "" = automatisch, sonst z. B. "de" oder "en" */
    var language: String
        get() = p.getString("sprache", "") ?: ""
        set(v) = p.edit().putString("sprache", v).apply()

    var preferGpu: Boolean
        get() = p.getBoolean("gpu", true)
        set(v) = p.edit().putBoolean("gpu", v).apply()

    /** Personen im Haushalt, fuer Einkaufslisten aus dem Essensplan */
    var household: Int
        get() = p.getInt("haushalt", 2)
        set(v) = p.edit().putInt("haushalt", v).apply()

    var planSlots: Set<String>
        get() = p.getStringSet("plan_slots", setOf("Abendessen")) ?: setOf("Abendessen")
        set(v) = p.edit().putStringSet("plan_slots", v).apply()

    var assumeStaples: Boolean
        get() = p.getBoolean("grundzutaten", true)
        set(v) = p.edit().putBoolean("grundzutaten", v).apply()

    var lastYtdlpUpdate: Long
        get() = p.getLong("ytdlp_update", 0L)
        set(v) = p.edit().putLong("ytdlp_update", v).apply()
}

data class ModelFile(val fileName: String, val url: String, val approxMb: Int)

data class ModelSpec(val key: String, val label: String, val files: List<ModelFile>) {
    val totalMb get() = files.sumOf { it.approxMb }
}

/** Download und Verwaltung der KI-Modelle (laufen danach komplett offline). */
object ModelManager {
    private const val HF = "https://huggingface.co"
    const val CUSTOM_LLM = "eigenes-modell.litertlm"

    val LLM = ModelSpec(
        "llm", "Sprach-KI Qwen 2.5 (1.5B)",
        listOf(
            ModelFile(
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "$HF/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
                1600,
            ),
        ),
    )

    private fun whisper(size: String, mb: Pair<Int, Int>) = ModelSpec(
        "whisper-$size", "Spracherkennung Whisper $size",
        listOf(
            ModelFile("$size-encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-$size/resolve/main/$size-encoder.int8.onnx?download=true", mb.first),
            ModelFile("$size-decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-$size/resolve/main/$size-decoder.int8.onnx?download=true", mb.second),
            ModelFile("$size-tokens.txt", "$HF/csukuangfj/sherpa-onnx-whisper-$size/resolve/main/$size-tokens.txt?download=true", 1),
        ),
    )

    val WHISPER = mapOf(
        "base" to whisper("base", 30 to 100),
        "small" to whisper("small", 110 to 260),
    )

    fun dir(ctx: Context): File = (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }

    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    private fun okMarker(ctx: Context, name: String) = File(dir(ctx), "$name.ok")

    fun isReady(ctx: Context, f: ModelFile) = file(ctx, f.fileName).exists() && okMarker(ctx, f.fileName).exists()

    fun isReady(ctx: Context, spec: ModelSpec) = spec.files.all { isReady(ctx, it) }

    fun hasCustomLlm(ctx: Context) = file(ctx, CUSTOM_LLM).exists() && okMarker(ctx, CUSTOM_LLM).exists()

    fun llmPath(ctx: Context): String? = when {
        hasCustomLlm(ctx) -> file(ctx, CUSTOM_LLM).absolutePath
        isReady(ctx, LLM) -> file(ctx, LLM.files[0].fileName).absolutePath
        else -> null
    }

    fun whisperSpec(ctx: Context) = WHISPER[Settings(ctx).whisperModel] ?: WHISPER.getValue("small")

    fun whisperReady(ctx: Context) = isReady(ctx, whisperSpec(ctx))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** Startet fehlende Downloads ueber den Android-Downloadmanager. */
    fun download(ctx: Context, spec: ModelSpec) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        for (f in spec.files) {
            if (isReady(ctx, f) || downloadId(ctx, f) != null) continue
            file(ctx, f.fileName).delete()
            val req = DownloadManager.Request(Uri.parse(f.url))
                .setTitle("RezeptApp: ${f.fileName}")
                .setDescription(spec.label)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, "models", f.fileName)
                .setAllowedOverMetered(true)
            prefs(ctx).edit().putLong(f.fileName, dm.enqueue(req)).apply()
        }
    }

    private fun downloadId(ctx: Context, f: ModelFile): Long? =
        prefs(ctx).getLong(f.fileName, -1L).takeIf { it >= 0 }

    data class Progress(val doneBytes: Long, val totalBytes: Long, val running: Boolean, val failed: Boolean)

    /** Fragt den Downloadmanager ab und setzt bei Erfolg die Fertig-Markierung. */
    fun progress(ctx: Context, spec: ModelSpec): Progress {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        var done = 0L
        var total = 0L
        var running = false
        var failed = false
        for (f in spec.files) {
            if (isReady(ctx, f)) {
                val len = file(ctx, f.fileName).length()
                done += len; total += len
                continue
            }
            val id = downloadId(ctx, f) ?: run { total += f.approxMb * 1_000_000L; null } ?: continue
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    prefs(ctx).edit().remove(f.fileName).apply()
                    total += f.approxMb * 1_000_000L
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                done += so
                total += if (tot > 0) tot else f.approxMb * 1_000_000L
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        okMarker(ctx, f.fileName).writeText("ok")
                        prefs(ctx).edit().remove(f.fileName).apply()
                    }
                    DownloadManager.STATUS_FAILED -> {
                        failed = true
                        prefs(ctx).edit().remove(f.fileName).apply()
                        dm.remove(id)
                    }
                    else -> running = true
                }
            }
        }
        return Progress(done, total, running, failed)
    }

    fun delete(ctx: Context, spec: ModelSpec) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        spec.files.forEach { f ->
            downloadId(ctx, f)?.let { dm.remove(it) }
            prefs(ctx).edit().remove(f.fileName).apply()
            file(ctx, f.fileName).delete()
            okMarker(ctx, f.fileName).delete()
        }
    }

    /** Kopiert eine vom Nutzer gewaehlte .litertlm-Datei (z. B. Gemma) in den Modellordner. */
    fun importCustomLlm(ctx: Context, uri: Uri) {
        val target = file(ctx, CUSTOM_LLM)
        okMarker(ctx, CUSTOM_LLM).delete()
        ctx.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
        okMarker(ctx, CUSTOM_LLM).writeText("ok")
    }

    fun removeCustomLlm(ctx: Context) {
        file(ctx, CUSTOM_LLM).delete()
        okMarker(ctx, CUSTOM_LLM).delete()
    }
}

/** Speichert die Anmeldung aus dem Login-Fenster als cookies.txt fuer yt-dlp. */
object CookieExport {
    private val sites = listOf(
        "https://www.instagram.com" to ".instagram.com",
        "https://www.facebook.com" to ".facebook.com",
        "https://www.tiktok.com" to ".tiktok.com",
    )

    fun file(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    fun hasCookies(ctx: Context) = file(ctx).let { it.exists() && it.length() > 40 }

    fun export(ctx: Context): Int {
        val cm = CookieManager.getInstance()
        cm.flush()
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        var count = 0
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        for ((url, domain) in sites) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                val name = kv.substringBefore('=')
                val value = kv.substringAfter('=')
                sb.append("$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
                count++
            }
        }
        file(ctx).writeText(sb.toString())
        return count
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
    }
}

data class ShoppingItem(
    val id: Long,
    val text: String,
    val recipe: String = "",
    val done: Boolean = false,
    val category: String = ShoppingCategories.categorize(text),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("rezept", recipe).put("erledigt", done).put("kategorie", category)

    companion object {
        fun fromJson(o: JSONObject) = ShoppingItem(
            id = o.getLong("id"),
            text = o.optString("text"),
            recipe = o.optString("rezept"),
            done = o.optBoolean("erledigt"),
            category = o.optString("kategorie").ifBlank { ShoppingCategories.categorize(o.optString("text")) },
        )
    }
}

/** Einkaufsliste als JSON-Datei. */
object ShoppingStore {
    private lateinit var file: File
    private val _items = MutableStateFlow<List<ShoppingItem>>(emptyList())
    val items: StateFlow<List<ShoppingItem>> = _items
    private var nextId = System.currentTimeMillis()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "einkaufsliste.json")
        _items.value = runCatching {
            val arr = org.json.JSONArray(file.readText())
            (0 until arr.length()).map { ShoppingItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(list: List<ShoppingItem>) {
        _items.value = list
        file.writeText(org.json.JSONArray(list.map { it.toJson() }).toString(2))
    }

    @Synchronized
    fun add(texts: List<String>, recipe: String = "") {
        val existing = _items.value
        val fresh = texts.map { it.trim() }.filter { it.isNotEmpty() }
            .filter { t -> existing.none { !it.done && it.text.equals(t, ignoreCase = true) } }
            .map { ShoppingItem(nextId++, it, recipe) }
        write(existing + fresh)
    }

    fun toggle(id: Long) = write(_items.value.map { if (it.id == id) it.copy(done = !it.done) else it })

    fun remove(id: Long) = write(_items.value.filterNot { it.id == id })

    fun clearDone() = write(_items.value.filterNot { it.done })

    fun clearAll() = write(emptyList())

    fun replaceAll(list: List<ShoppingItem>) = write(list)

    fun asText(): String = _items.value.filterNot { it.done }
        .groupBy { it.category }
        .toSortedMap(compareBy<String> { ShoppingCategories.ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })
        .entries.joinToString("\n\n") { (cat, list) -> "$cat\n" + list.joinToString("\n") { "☐ ${it.text}" } }
}

/** Sicherung aller Rezepte und der Einkaufsliste in einer Datei. */
object Backup {
    fun export(ctx: Context, uri: Uri): Int {
        val recipes = RecipeStore.recipes.value
        val root = JSONObject()
            .put("app", "RezeptApp")
            .put("format", 1)
            .put("exportiert", System.currentTimeMillis())
            .put("rezepte", org.json.JSONArray(recipes.map { it.toJson() }))
            .put("einkaufsliste", org.json.JSONArray(ShoppingStore.items.value.map { it.toJson() }))
            .put("essensplan", org.json.JSONArray(MealPlanStore.entries.value.map { it.toJson() }))
            .put("vorrat", org.json.JSONArray(PantryStore.items.value))
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return recipes.size
    }

    /** Liest eine Sicherung ein. Gleiche Rezepte (gleiche ID oder gleicher Link) werden ersetzt. */
    fun import(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        require(root.optString("app") == "RezeptApp") { "Das ist keine Sicherung der RezeptApp." }
        val arr = root.getJSONArray("rezepte")
        var count = 0
        for (i in 0 until arr.length()) {
            val r = runCatching { Recipe.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
            RecipeStore.findByUrl(r.url)?.takeIf { it.id != r.id && r.url.isNotBlank() }?.let { RecipeStore.delete(it.id) }
            RecipeStore.save(r)
            count++
        }
        root.optJSONArray("einkaufsliste")?.let { a ->
            if (ShoppingStore.items.value.isEmpty() && a.length() > 0) {
                ShoppingStore.replaceAll((0 until a.length()).map { ShoppingItem.fromJson(a.getJSONObject(it)) })
            }
        }
        root.optJSONArray("essensplan")?.let { a ->
            if (MealPlanStore.entries.value.isEmpty() && a.length() > 0) {
                MealPlanStore.replaceAll((0 until a.length()).map { MealEntry.fromJson(a.getJSONObject(it)) })
            }
        }
        root.optJSONArray("vorrat")?.let { a -> PantryStore.add((0 until a.length()).map { a.getString(it) }) }
        return count
    }
}

/** Essensplan als JSON-Datei. */
object MealPlanStore {
    private lateinit var file: File
    private val _entries = MutableStateFlow<List<MealEntry>>(emptyList())
    val entries: StateFlow<List<MealEntry>> = _entries
    private var nextId = System.currentTimeMillis()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "essensplan.json")
        _entries.value = runCatching {
            val arr = org.json.JSONArray(file.readText())
            (0 until arr.length()).map { MealEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(list: List<MealEntry>) {
        _entries.value = list.sortedWith(compareBy({ it.date }, { MealEntry.SLOTS.indexOf(it.slot) }))
        file.writeText(org.json.JSONArray(_entries.value.map { it.toJson() }).toString(2))
    }

    fun forDate(date: String) = _entries.value.filter { it.date == date }

    /** Setzt einen Platz (ersetzt einen vorhandenen Eintrag fuer Datum und Mahlzeit). */
    fun set(date: String, slot: String, recipeId: Long?, text: String = "") {
        val rest = _entries.value.filterNot { it.date == date && it.slot == slot }
        write(rest + MealEntry(nextId++, date, slot, recipeId, text))
    }

    fun remove(id: Long) = write(_entries.value.filterNot { it.id == id })

    /** Fuegt erzeugte Eintraege ein und ersetzt dabei gleiche Plaetze. */
    fun putAll(list: List<MealEntry>) {
        val keys = list.map { it.date to it.slot }.toSet()
        write(_entries.value.filterNot { (it.date to it.slot) in keys } + list)
    }

    fun clearRange(dates: Collection<String>) = write(_entries.value.filterNot { it.date in dates })

    fun replaceAll(list: List<MealEntry>) = write(list)

    /** Rezepte, die nicht mehr existieren, aus dem Plan entfernen. */
    fun dropRecipe(recipeId: Long) = write(_entries.value.filterNot { it.recipeId == recipeId })
}

/** Vorhandene Lebensmittel. */
object PantryStore {
    private lateinit var file: File
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "vorrat.json")
        _items.value = runCatching {
            val arr = org.json.JSONArray(file.readText())
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(list: List<String>) {
        _items.value = list
        file.writeText(org.json.JSONArray(list).toString(2))
    }

    fun add(texts: List<String>) {
        val cur = _items.value
        val fresh = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .filter { t -> cur.none { it.equals(t, ignoreCase = true) } }
        write(cur + fresh)
    }

    fun remove(item: String) = write(_items.value.filterNot { it == item })

    fun clear() = write(emptyList())

    fun replaceAll(list: List<String>) = write(list)
}
