package dev.martin.friction

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.martin.friction.ui.GroupEditScreen
import dev.martin.friction.ui.GroupsScreen
import dev.martin.friction.ui.HomeScreen
import dev.martin.friction.ui.InterruptionVariantEditScreen
import dev.martin.friction.ui.InterruptionVariantsScreen
import dev.martin.friction.ui.LogScreen
import dev.martin.friction.ui.Nav
import dev.martin.friction.ui.Screen
import dev.martin.friction.ui.SetupScreen
import dev.martin.friction.ui.StageSequenceEditScreen
import dev.martin.friction.ui.StageSequencesScreen
import dev.martin.friction.ui.StatusScreen
import dev.martin.friction.ui.TaskSequenceEditScreen
import dev.martin.friction.ui.TaskSequencesScreen
import dev.martin.friction.ui.TaskVariantEditScreen
import dev.martin.friction.ui.TaskVariantsScreen

class MainActivity : ComponentActivity() {

    /** Bumped on every onResume so screens re-check permissions after returning from Settings. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FLog.d("Main", "MainActivity created")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val nav = remember { Nav() }
                    BackHandler(enabled = nav.canPop) { nav.pop() }
                    val s = nav.current
                    key(nav.stack.size, s) {
                    when (s) {
                        Screen.Home -> HomeScreen(nav, resumeTick)
                        Screen.Status -> StatusScreen(nav)
                        Screen.Setup -> SetupScreen(nav, resumeTick)
                        Screen.Log -> LogScreen(nav, resumeTick)
                        Screen.Groups -> GroupsScreen(nav)
                        is Screen.GroupEdit -> GroupEditScreen(nav, s.id)
                        Screen.StageSequences -> StageSequencesScreen(nav)
                        is Screen.StageSequenceEdit -> StageSequenceEditScreen(nav, s.id)
                        Screen.TaskSequences -> TaskSequencesScreen(nav)
                        is Screen.TaskSequenceEdit -> TaskSequenceEditScreen(nav, s.id)
                        Screen.TaskVariants -> TaskVariantsScreen(nav)
                        is Screen.TaskVariantEdit -> TaskVariantEditScreen(nav, s.id, s.typeId)
                        Screen.InterruptionVariants -> InterruptionVariantsScreen(nav)
                        is Screen.InterruptionVariantEdit -> InterruptionVariantEditScreen(nav, s.id, s.typeId)
                    }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
