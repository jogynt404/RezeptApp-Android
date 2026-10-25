package de.rezeptapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.rezeptapp.ui.DetailScreen
import de.rezeptapp.ui.HomeScreen
import de.rezeptapp.ui.LoginScreen
import de.rezeptapp.ui.RezeptTheme
import de.rezeptapp.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: Long) : Screen
    data object Settings : Screen
    data object Login : Screen
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_ID = "open_id"
    }

    private var screen by mutableStateOf<Screen>(Screen.Home)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)

        setContent {
            RezeptTheme {
                BackHandler(enabled = screen != Screen.Home) {
                    screen = if (screen == Screen.Login) Screen.Settings else Screen.Home
                }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        onOpen = { screen = Screen.Detail(it) },
                        onSettings = { screen = Screen.Settings },
                        onImport = { url, audio -> startImport(url, audio) },
                    )
                    is Screen.Detail -> DetailScreen(
                        id = s.id,
                        onBack = { screen = Screen.Home },
                        onRedo = { ImportService.redo(this, s.id); screen = Screen.Home },
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Home },
                        onLogin = { screen = Screen.Login },
                    )
                    Screen.Login -> LoginScreen(onDone = { screen = Screen.Settings })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_OPEN_ID)) {
            screen = Screen.Detail(intent.getLongExtra(EXTRA_OPEN_ID, 0))
            ImportService.clearResult()
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val url = RecipeParser.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url == null) {
                Toast.makeText(this, "Kein Link von Instagram, TikTok oder Facebook gefunden.", Toast.LENGTH_LONG).show()
            } else {
                startImport(url, false)
            }
            intent.action = null
        }
    }

    private fun startImport(url: String, forceAudio: Boolean) {
        if (ModelManager.llmPath(this) == null) {
            Toast.makeText(this, "Bitte zuerst die KI-Modelle herunterladen.", Toast.LENGTH_LONG).show()
            screen = Screen.Settings
            return
        }
        ImportService.enqueue(this, url, forceAudio)
        screen = Screen.Home
        Toast.makeText(this, "Rezept wird ausgelesen. Du kannst die App verlassen.", Toast.LENGTH_SHORT).show()
    }
}
