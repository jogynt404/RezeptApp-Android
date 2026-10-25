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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.rezeptapp.ui.DetailScreen
import de.rezeptapp.ui.EditScreen
import de.rezeptapp.ui.HomeScreen
import de.rezeptapp.ui.LoginScreen
import de.rezeptapp.ui.PantryScreen
import de.rezeptapp.ui.PlanScreen
import de.rezeptapp.ui.RezeptTheme
import de.rezeptapp.ui.SettingsScreen
import de.rezeptapp.ui.ShoppingScreen

sealed interface Screen {
    data object Home : Screen
    data object Plan : Screen
    data object Pantry : Screen
    data object Shopping : Screen
    data class Detail(val id: Long) : Screen
    data class Edit(val id: Long) : Screen
    data object Settings : Screen
    data object Login : Screen
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_ID = "open_id"
    }

    /** Einfacher Zurueck-Stapel: das letzte Element ist der sichtbare Bildschirm. */
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val current get() = stack.last()

    private fun push(s: Screen) { stack.add(s) }
    private fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (current != Screen.Home) { stack.clear(); stack.add(Screen.Home) } }
    private fun tab(s: Screen) { stack.clear(); stack.add(s) }

    private val tabs = listOf(
        Tab(Screen.Home, "Rezepte", Icons.Default.MenuBook),
        Tab(Screen.Plan, "Plan", Icons.Default.CalendarMonth),
        Tab(Screen.Pantry, "Vorrat", Icons.Default.Kitchen),
        Tab(Screen.Shopping, "Einkauf", Icons.Default.ShoppingCart),
    )

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)

        setContent {
            RezeptTheme {
                BackHandler(enabled = stack.size > 1 || current != Screen.Home) { back() }
                val s = current
                val showTabs = tabs.any { it.screen == s }
                val shopping by ShoppingStore.items.collectAsStateWithLifecycle()
                val openItems = shopping.count { !it.done }
                Scaffold(bottomBar = {
                    if (showTabs) NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = s == t.screen,
                                onClick = { tab(t.screen) },
                                icon = {
                                    if (t.screen == Screen.Shopping && openItems > 0) BadgedBox(badge = { Badge { Text("$openItems") } }) { Icon(t.icon, null) }
                                    else Icon(t.icon, null)
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                        when (s) {
                            Screen.Home -> HomeScreen(
                                onOpen = { push(Screen.Detail(it)) },
                                onSettings = { push(Screen.Settings) },
                                onImport = { url, audio -> startImport(url, audio) },
                            )
                            Screen.Plan -> PlanScreen(onOpen = { push(Screen.Detail(it)) })
                            Screen.Pantry -> PantryScreen(onOpen = { push(Screen.Detail(it)) })
                            Screen.Shopping -> ShoppingScreen(onBack = null)
                            is Screen.Detail -> DetailScreen(
                                id = s.id,
                                onBack = { back() },
                                onRedo = { ImportService.redo(this@MainActivity, s.id); tab(Screen.Home) },
                                onEdit = { push(Screen.Edit(s.id)) },
                                onTranslate = { ImportService.translate(this@MainActivity, s.id); tab(Screen.Home) },
                            )
                            is Screen.Edit -> EditScreen(id = s.id, onDone = { back() })
                            Screen.Settings -> SettingsScreen(onBack = { back() }, onLogin = { push(Screen.Login) })
                            Screen.Login -> LoginScreen(onDone = { back() })
                        }
                    }
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
            tab(Screen.Home)
            push(Screen.Detail(intent.getLongExtra(EXTRA_OPEN_ID, 0)))
            ImportService.clearResult()
            intent.removeExtra(EXTRA_OPEN_ID)
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val url = RecipeParser.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url == null) {
                Toast.makeText(this, "Im geteilten Text wurde kein Link gefunden.", Toast.LENGTH_LONG).show()
            } else {
                startImport(url, false)
            }
            intent.action = null
        }
    }

    private fun startImport(url: String, forceAudio: Boolean) {
        if (RecipeParser.isSocial(url) && ModelManager.llmPath(this) == null) {
            Toast.makeText(this, "Bitte zuerst die KI-Modelle herunterladen.", Toast.LENGTH_LONG).show()
            tab(Screen.Home)
            push(Screen.Settings)
            return
        }
        ImportService.enqueue(this, url, forceAudio)
        tab(Screen.Home)
        Toast.makeText(this, "Rezept wird ausgelesen. Du kannst die App verlassen.", Toast.LENGTH_SHORT).show()
    }
}
