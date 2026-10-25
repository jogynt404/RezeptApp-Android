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
