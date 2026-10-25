package de.rezeptapp

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UserFacingException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** 1. Post von Instagram, TikTok oder Facebook laden (yt-dlp). */
object PostFetcher {
    data class Post(val title: String, val description: String, val author: String, val url: String, val hasVideo: Boolean)

    @Volatile private var initialized = false

    @Synchronized
    fun ensureInit(ctx: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        initialized = true
    }

    /** Instagram und TikTok aendern sich oft, deshalb yt-dlp hoechstens einmal pro Tag aktualisieren. */
    fun maybeUpdate(ctx: Context) {
        val s = Settings(ctx)
        if (System.currentTimeMillis() - s.lastYtdlpUpdate < 24 * 3600 * 1000L) return
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            s.lastYtdlpUpdate = System.currentTimeMillis()
        }
    }

    private fun request(ctx: Context, url: String) = YoutubeDLRequest(url).apply {
        if (CookieExport.hasCookies(ctx)) addOption("--cookies", CookieExport.file(ctx).absolutePath)
        addOption("--no-warnings")
    }

    private fun friendly(e: Throwable): UserFacingException {
        val msg = e.message.orEmpty()
        val low = msg.lowercase()
        return if (listOf("login", "cookie", "rate-limit", "private", "authentication").any { it in low }) {
            UserFacingException("Die Plattform verlangt eine Anmeldung. Melde dich in den Einstellungen bei Instagram oder Facebook an.", e)
        } else {
            UserFacingException("Post konnte nicht geladen werden: ${msg.lines().lastOrNull { it.isNotBlank() } ?: msg}", e)
        }
    }

    fun fetch(ctx: Context, url: String): Post {
        ensureInit(ctx)
        val req = request(ctx, url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
        }
        val out = try {
            YoutubeDL.getInstance().execute(req, null, null).out
        } catch (e: Exception) {
            throw friendly(e)
        }
        val info = JSONObject(out.trim().lines().last { it.startsWith("{") })
        val first = info.optJSONArray("entries")?.optJSONObject(0) ?: info
        fun pick(k: String) = info.optString(k).takeIf { it.isNotBlank() && it != "null" }
            ?: first.optString(k).takeIf { it.isNotBlank() && it != "null" } ?: ""
        val hasVideo = first.optDouble("duration", 0.0) > 0 || first.has("formats")
        return Post(
            title = pick("title"),
            description = pick("description"),
            author = pick("uploader").ifBlank { pick("channel") },
            url = info.optString("webpage_url").ifBlank { url },
            hasVideo = hasVideo,
        )
    }

    /** Laedt nur den Ton als 16-kHz-Mono-WAV herunter. */
    fun downloadAudio(ctx: Context, url: String): File {
        ensureInit(ctx)
        val dir = File(ctx.cacheDir, "audio").apply { deleteRecursively(); mkdirs() }
        val req = request(ctx, url).apply {
            addOption("--no-playlist")
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", "wav")
            addOption("--postprocessor-args", "ffmpeg:-ar 16000 -ac 1")
            addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        }
        try {
            YoutubeDL.getInstance().execute(req, null, null)
        } catch (e: Exception) {
            throw friendly(e)
        }
        return dir.listFiles()?.firstOrNull { it.extension == "wav" }
            ?: throw UserFacingException("Kein Ton im Post gefunden.")
    }
}

/** Normale Rezept-Webseiten (Chefkoch, Blogs ...) laden. */
object WebFetcher {
    data class Page(val url: String, val html: String)

    fun fetch(url: String): Page {
        var current = url
        repeat(5) {
            val conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.6")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw UserFacingException("Weiterleitung ohne Ziel.")
                    current = java.net.URL(java.net.URL(current), loc).toString()
                    return@repeat
                }
                if (code >= 400) throw UserFacingException("Die Webseite antwortet mit Fehler $code.")
                val csName = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(conn.contentType ?: "")?.groupValues?.get(1)
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (out.size() < 4_000_000) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val cs = runCatching { charset(csName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
                return Page(current, String(bytes, cs))
            } catch (e: UserFacingException) {
                throw e
            } catch (e: Exception) {
                throw UserFacingException("Webseite konnte nicht geladen werden: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
        throw UserFacingException("Zu viele Weiterleitungen.")
    }
}

/** Liest eine 16-Bit-PCM-WAV-Datei als Float-Samples. */
object WavReader {
    fun read(file: File): Pair<FloatArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") { "Keine WAV-Datei" }
            var sampleRate = 16000
            var channels = 1
            var bits = 16
            while (raf.filePointer < raf.length()) {
                val chunk = ByteArray(8)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt())
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = bb.getShort(2).toInt()
                    sampleRate = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                } else if (id == "data") {
                    require(bits == 16) { "Nur 16-Bit-WAV wird unterstützt" }
                    val len = minOf(size, raf.length() - raf.filePointer).toInt()
                    val bytes = ByteArray(len)
                    raf.readFully(bytes)
                    val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get(i * channels + c) / 32768f
                        out[i] = sum / channels
                    }
                    return out to sampleRate
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
        }
        throw UserFacingException("Die Tonspur ist leer.")
    }
}

/** 2. Gesprochenes per Whisper (sherpa-onnx) in Text umwandeln. Laeuft offline. */
object Transcriber {
    fun transcribe(ctx: Context, wav: File, onProgress: (Int) -> Unit): String {
        val spec = ModelManager.whisperSpec(ctx)
        if (!ModelManager.isReady(ctx, spec)) throw UserFacingException("Das Whisper-Modell fehlt. Lade es in den Einstellungen herunter.")
        val (samples, rate) = WavReader.read(wav)
        val f = spec.files.map { ModelManager.file(ctx, it.fileName).absolutePath }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = f[0], decoder = f[1], language = Settings(ctx).language),
                tokens = f[2],
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ),
        )
        val recognizer = OfflineRecognizer(null, config)
        try {
            // Whisper verarbeitet hoechstens 30 Sekunden am Stueck
            val chunk = rate * 28
            val parts = mutableListOf<String>()
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + chunk, samples.size)
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples.copyOfRange(start, end), rate)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
                } finally {
                    stream.release()
                }
                start = end
                onProgress((start * 100L / samples.size).toInt())
            }
            return parts.joinToString(" ")
        } finally {
            recognizer.release()
        }
    }
}

/** 3. Lokale Sprach-KI (LiteRT-LM) sortiert Zutaten und Schritte. */
object Structurer {
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private const val MAX_INPUT_CHARS = 7000 // Modell hat 4096 Token Kontext

    @Synchronized
    private fun engine(ctx: Context): Engine {
        val path = ModelManager.llmPath(ctx)
            ?: throw UserFacingException("Die Sprach-KI fehlt. Lade sie in den Einstellungen herunter.")
        engine?.let { if (loadedPath == path) return it }
        close()
        val backends = if (Settings(ctx).preferGpu) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var last: Throwable? = null
        for (b in backends) {
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = b, cacheDir = ctx.cacheDir.path))
                e.initialize()
                engine = e
                loadedPath = path
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw UserFacingException("Die KI konnte nicht geladen werden: ${last?.message}", last)
    }

    @Synchronized
    fun structure(ctx: Context, text: String): RecipeData {
        val e = engine(ctx)
        val input = RecipeParser.PROMPT + text.take(MAX_INPUT_CHARS)
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val sampler = SamplerConfig(topK = if (attempt == 0) 1 else 20, topP = 0.95, temperature = if (attempt == 0) 0.1 else 0.5)
                val answer = e.createConversation(ConversationConfig(samplerConfig = sampler)).use { c ->
                    c.sendMessage(input).toString()
                }
                return RecipeParser.parseLlm(answer)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw UserFacingException("Die KI hat kein lesbares Ergebnis geliefert. Versuche \"Neu auswerten\".", lastError)
    }

    /** Uebersetzt ein bereits strukturiertes Rezept ins Deutsche. */
    @Synchronized
    fun translate(ctx: Context, data: RecipeData): RecipeData {
        val e = engine(ctx)
        val input = RecipeParser.TRANSLATE_PROMPT + data.toJson().toString()
        if (input.length > 9000) throw UserFacingException("Das Rezept ist zu lang für die Übersetzung auf dem Handy.")
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val sampler = SamplerConfig(topK = if (attempt == 0) 1 else 20, topP = 0.95, temperature = if (attempt == 0) 0.1 else 0.4)
                val answer = e.createConversation(ConversationConfig(samplerConfig = sampler)).use { c -> c.sendMessage(input).toString() }
                val out = RecipeParser.parseLlm(answer)
                // Plausibilitaet: die Uebersetzung darf keine Zutaten oder Schritte verlieren
                if (out.ingredients.size >= data.ingredients.size && out.steps.size >= data.steps.size) return out
                lastError = IllegalStateException("Übersetzung unvollständig")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw UserFacingException("Die Übersetzung hat nicht geklappt. Das Rezept bleibt in der Originalsprache.", lastError)
    }

    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }
}
