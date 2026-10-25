package de.rezeptapp

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RecipeStore.init(this)
        ShoppingStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ImportService.CHANNEL, "Rezepte auslesen", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ImportService.CHANNEL_DONE, "Rezept fertig", NotificationManager.IMPORTANCE_DEFAULT))
        // Python und yt-dlp vorbereiten, damit der erste Import schneller startet
        CoroutineScope(Dispatchers.IO).launch { runCatching { PostFetcher.ensureInit(this@App) } }
    }
}

data class JobState(
    val running: Boolean = false,
    val step: String = "",
    val progress: Int = -1,
    val error: String? = null,
    val doneRecipeId: Long? = null,
    val warning: String? = null,
    val queued: Int = 0,
)

/** Vordergrund-Dienst: arbeitet weiter, auch wenn du zurueck zu Instagram wechselst. */
class ImportService : Service() {
    private data class Job(val url: String? = null, val forceAudio: Boolean = false, val redoId: Long? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "import"
        const val CHANNEL_DONE = "fertig"
        private const val NOTIF_ID = 1
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_REDO = "redo"

        private val _state = MutableStateFlow(JobState())
        val state: StateFlow<JobState> = _state

        fun enqueue(ctx: Context, url: String, forceAudio: Boolean) = start(ctx) {
            putExtra(EXTRA_URL, url); putExtra(EXTRA_AUDIO, forceAudio)
        }

        fun redo(ctx: Context, id: Long) = start(ctx) { putExtra(EXTRA_REDO, id) }

        fun clearResult() {
            _state.value = _state.value.copy(error = null, doneRecipeId = null, warning = null)
        }

        private fun start(ctx: Context, extras: Intent.() -> Unit) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ImportService::class.java).apply(extras))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RezeptApp:import").apply { acquire(30 * 60 * 1000L) }
        scope.launch { for (job in queue) process(job) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Startet ...", -1), type)
        val job = when {
            intent?.hasExtra(EXTRA_REDO) == true -> Job(redoId = intent.getLongExtra(EXTRA_REDO, 0))
            intent?.getStringExtra(EXTRA_URL) != null -> Job(url = intent.getStringExtra(EXTRA_URL), forceAudio = intent.getBooleanExtra(EXTRA_AUDIO, false))
            else -> null
        }
        if (job != null) {
            _state.value = _state.value.copy(queued = pending.incrementAndGet())
            queue.trySend(job)
        } else if (pending.get() == 0) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun update(step: String, progress: Int = -1) {
        _state.value = _state.value.copy(running = true, step = step, progress = progress, error = null, doneRecipeId = null, warning = null)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(step, progress))
    }

    private fun process(job: Job) {
        try {
            val id = if (job.redoId != null) redo(job.redoId) else import(job.url!!, job.forceAudio)
            val r = RecipeStore.get(id)
            _state.value = _state.value.copy(running = false, step = "Fertig", progress = -1, doneRecipeId = id, warning = r?.error)
            notifyDone(r)
        } catch (e: Throwable) {
            val msg = if (e is UserFacingException) e.message else "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            _state.value = _state.value.copy(running = false, step = "", progress = -1, error = msg)
        } finally {
            val left = pending.decrementAndGet()
            _state.value = _state.value.copy(queued = left)
            if (left == 0) {
                Structurer.close() // Speicher freigeben
                stopSelf()
            }
        }
    }

    private fun import(url: String, forceAudio: Boolean): Long =
        if (RecipeParser.isSocial(url)) importSocial(url, forceAudio) else importWeb(url)

    /** Webseite: zuerst eingebettete Rezeptdaten (ohne KI), sonst Seitentext an die KI. */
    private fun importWeb(url: String): Long {
        update("Webseite wird geladen ...")
        val page = WebFetcher.fetch(url)
        val text = WebRecipeParser.visibleText(page.html).take(12000)
        val existing = RecipeStore.findByUrl(page.url)
        val parsed = WebRecipeParser.parse(page.html)
        if (parsed != null && parsed.data.isRecipe) {
            val r = Recipe(
                id = existing?.id ?: System.currentTimeMillis(), url = page.url,
                title = parsed.data.title.ifBlank { WebRecipeParser.title(page.html).ifBlank { "Rezept" } },
                author = parsed.author, description = text, data = parsed.data,
                tags = existing?.tags ?: emptyList(), created = existing?.created ?: System.currentTimeMillis(),
            )
            RecipeStore.save(r)
            return r.id
        }
        if (text.isBlank()) throw UserFacingException("Auf der Webseite wurde kein Text gefunden.")
        val base = Recipe(
            id = existing?.id ?: System.currentTimeMillis(), url = page.url,
            title = WebRecipeParser.title(page.html).ifBlank { "Rezept" }, description = text,
            tags = existing?.tags ?: emptyList(), created = existing?.created ?: System.currentTimeMillis(),
        )
        return structureAndSave(base)
    }

    private fun importSocial(url: String, forceAudio: Boolean): Long {
        update("Post wird geladen ...")
        PostFetcher.ensureInit(this)
        PostFetcher.maybeUpdate(this)
        val post = PostFetcher.fetch(this, url)

        var transcript = ""
        if ((forceAudio || !RecipeParser.looksLikeRecipe(post.description)) && post.hasVideo) {
            if (!ModelManager.whisperReady(this)) {
                if (post.description.isBlank()) throw UserFacingException("Im Post steht kein Text. Für das Video fehlt noch das Whisper-Modell (Einstellungen).")
            } else {
                update("Ton wird geladen ...")
                val wav = PostFetcher.downloadAudio(this, url)
                update("Video wird angehört ...", 0)
                transcript = Transcriber.transcribe(this, wav) { update("Video wird angehört ...", it) }
                wav.parentFile?.deleteRecursively()
            }
        }

        val existing = RecipeStore.findByUrl(post.url)
        val base = Recipe(
            id = existing?.id ?: System.currentTimeMillis(),
            url = post.url, title = post.title.ifBlank { "Rezept" }, author = post.author,
            description = post.description, transcript = transcript,
            tags = existing?.tags ?: emptyList(), created = existing?.created ?: System.currentTimeMillis(),
        )
        if (base.sourceText().isBlank()) throw UserFacingException("Der Post enthält weder Text noch Ton.")
        return structureAndSave(base)
    }

    private fun redo(id: Long): Long {
        val r = RecipeStore.get(id) ?: throw UserFacingException("Rezept nicht gefunden.")
        return structureAndSave(r)
    }

    private fun structureAndSave(base: Recipe): Long {
        update("KI sortiert Zutaten und Schritte ...")
        val saved = try {
            val data = Structurer.structure(this, base.sourceText())
            base.copy(data = data, title = data.title.ifBlank { base.title }, error = null)
        } catch (e: UserFacingException) {
            base.copy(error = e.message) // Rohtext trotzdem speichern
        }
        RecipeStore.save(saved)
        return saved.id
    }

    private fun openAppIntent(id: Long? = null) = PendingIntent.getActivity(
        this, (id ?: 0L).toInt(),
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (id != null) putExtra(MainActivity.EXTRA_OPEN_ID, id)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rezept wird ausgelesen")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyDone(r: Recipe?) {
        if (r == null) return
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(r.title)
            .setContentText(if (r.error != null) "Gespeichert, aber nicht sortiert" else "Rezept ist fertig")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(r.id))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(r.id.toInt(), n) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}
