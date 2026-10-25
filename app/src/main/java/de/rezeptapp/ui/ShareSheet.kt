package de.rezeptapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Messenger, die direkt angeboten werden, wenn sie installiert sind. */
private val MESSENGERS = listOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.thoughtcrime.securesms" to "Signal",
    "com.facebook.orca" to "Messenger",
    "org.telegram.messenger" to "Telegram",
    "org.thunderdog.challegram" to "Telegram X",
    "com.google.android.apps.messaging" to "SMS",
    "com.samsung.android.messaging" to "SMS",
)

private data class Target(val pkg: String, val label: String, val icon: ImageBitmap?)

private fun sendIntent(text: String, subject: String?) = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text)
    if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
}

private fun installedTargets(ctx: Context): List<Target> {
    val pm = ctx.packageManager
    return MESSENGERS.mapNotNull { (pkg, label) ->
        val ok = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg), 0).isNotEmpty()
        if (!ok) null else Target(pkg, label, runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull())
    }.distinctBy { it.label }
}

/** Teilen-Fenster mit Vorschau, direkten Messenger-Knoepfen, "Andere App" und "Kopieren". */
@Composable
fun ShareDialog(title: String, text: String, subject: String? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val targets = remember { installedTargets(ctx) }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent)
            onClose()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "App nicht gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Text(text, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (targets.isEmpty()) {
                    Text("Kein Messenger gefunden. Nutze \"Andere App\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                // Messenger-Knoepfe, drei pro Zeile
                targets.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Column(
                                Modifier.weight(1f).clickable { open(sendIntent(text, subject).setPackage(t.pkg)) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (t.icon != null) Image(t.icon, t.label, Modifier.size(44.dp))
                                Text(t.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(Intent.createChooser(sendIntent(text, subject), title)) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.MoreHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Andere App")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(ctx, "Kopiert", Toast.LENGTH_SHORT).show()
                        onClose()
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Kopieren")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Schließen") } },
    )
}
