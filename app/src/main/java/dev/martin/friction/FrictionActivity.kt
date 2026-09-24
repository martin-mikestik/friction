package dev.martin.friction

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.data.StatsLog
import dev.martin.friction.engine.Resolved
import dev.martin.friction.engine.TaskTypes
import dev.martin.friction.engine.TaskVariant
import dev.martin.friction.engine.resolveAll
import dev.martin.friction.gate.TaskHost
import dev.martin.friction.service.GateController
import dev.martin.friction.service.GateGuard
import dev.martin.friction.service.SequenceOutcome
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Friction's full-screen gate, drawn on top of the blocked app.
 *  - MODE_BLOCK:    black screen with the remaining block time.
 *  - MODE_SEQUENCE: runs a task sequence. Let Go only on the intro and between tasks;
 *                   Back / leaving / screen off anywhere else = fail.
 *  - MODE_PRACTICE: runs task variants from the editor, with no consequences.
 */
class FrictionActivity : ComponentActivity() {

    private sealed class Phase {
        object Intro : Phase()
        data class Running(val index: Int, val runId: Long, val typeId: String, val params: Resolved) : Phase()
        data class Between(val passedIndex: Int) : Phase()
        data class Failed(val index: Int, val reason: String) : Phase()
        object Passed : Phase()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = MODE_SEQUENCE
    private var groupId = ""
    private var sequenceId = ""
    private var title = ""
    private var steps: List<TaskVariant> = emptyList()
    private var phase by mutableStateOf<Phase>(Phase.Intro)
    private var closing = false
    private var sequenceStartedAt = 0L

    private val practice get() = mode == MODE_PRACTICE

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        GateGuard.onScreenCreated()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SEQUENCE
        groupId = intent.getStringExtra(EXTRA_GROUP) ?: ""
        sequenceId = intent.getStringExtra(EXTRA_SEQUENCE) ?: ""
        val cfg = ConfigStore.current
        val groupName = cfg.group(groupId)?.name ?: "?"

        when (mode) {
            MODE_PRACTICE -> {
                val ids = intent.getStringArrayExtra(EXTRA_STEPS)?.toList().orEmpty()
                steps = ids.mapNotNull { cfg.taskVariant(it) }
                title = intent.getStringExtra(EXTRA_TITLE) ?: "Practice"
            }
            MODE_SEQUENCE -> {
                val seq = cfg.taskSequence(sequenceId)
                steps = seq?.steps.orEmpty().mapNotNull { cfg.taskVariant(it) }
                title = seq?.name ?: "?"
            }
        }
        FLog.i(AREA, "created mode=$mode group=$groupName sequence=$title steps=${steps.map { it.name }}")

        onBackPressedDispatcher.addCallback(this) { onBack() }

        val blockedUntil = GateController.state(groupId).blockedUntilMs
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (mode == MODE_BLOCK) {
                    BlockScreen(groupName, blockedUntil, onHome = { goHome("block screen button") })
                } else {
                    SequenceScreen(groupName)
                }
            }
        }
    }

    // ------------------------------------------------------------------ flow

    private fun startTask(index: Int) {
        val v = steps[index]
        val type = TaskTypes.byId(v.typeId)
        val params = if (type != null) resolveAll(type.params, v.params, Random.Default) else emptyMap()
        if (index == 0) sequenceStartedAt = SystemClock.elapsedRealtime()
        FLog.i(AREA, "task ${index + 1}/${steps.size} '${v.name}' (${v.typeId}) params=$params")
        phase = Phase.Running(index, SystemClock.elapsedRealtime(), v.typeId, params)
    }

    private fun onTaskResult(runId: Long, passed: Boolean, detail: String) {
        val p = phase
        if (p !is Phase.Running || p.runId != runId || closing) return
        val v = steps[p.index]
        FLog.i(AREA, "task ${p.index + 1} '${v.name}' -> ${if (passed) "PASS" else "FAIL"}: $detail")
        if (!practice) {
            StatsLog.event("task_result", "group" to groupId, "sequence" to title, "index" to p.index,
                "variant" to v.name, "type" to v.typeId, "passed" to passed, "detail" to detail,
                "ms" to (SystemClock.elapsedRealtime() - p.runId))
        }
        if (!passed) {
            fail(detail)
            return
        }
        if (p.index == steps.lastIndex) {
            phase = Phase.Passed
            if (!practice) {
                val total = SystemClock.elapsedRealtime() - sequenceStartedAt
                GateController.onSequenceResult(groupId, sequenceId, SequenceOutcome.PASSED, "all ${steps.size} tasks in ${total}ms")
                handler.postDelayed({ closeToApp() }, 900)
            }
        } else {
            phase = Phase.Between(p.index)
        }
    }

    private fun fail(reason: String) {
        val p = phase
        if (p is Phase.Failed || p is Phase.Passed || closing) return
        val index = when (p) {
            is Phase.Running -> p.index
            is Phase.Between -> p.passedIndex
            else -> -1
        }
        phase = Phase.Failed(index, reason)
        FLog.i(AREA, "sequence FAILED: $reason")
        if (!practice) {
            GateController.onSequenceResult(groupId, sequenceId, SequenceOutcome.FAILED, "task ${index + 1}: $reason")
            handler.postDelayed({ goHome("after failure") }, 2500)
        }
    }

    private fun letGo(where: String) {
        if (closing) return
        FLog.i(AREA, "Let Go ($where)")
        if (!practice) GateController.onSequenceResult(groupId, sequenceId, SequenceOutcome.LET_GO, where)
        if (practice) closeToApp() else goHome("let go")
    }

    private fun onBack() {
        when (mode) {
            MODE_BLOCK -> goHome("back on block screen")
            MODE_PRACTICE -> closeToApp()
            else -> when (phase) {
                Phase.Intro -> letGo("back on intro")
                is Phase.Running, is Phase.Between -> fail("pressed Back")
                is Phase.Failed -> goHome("back after failure")
                Phase.Passed -> {}
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || closing) return
        when {
            mode == MODE_SEQUENCE && phase == Phase.Intro -> letGo("left the intro screen")
            mode == MODE_SEQUENCE && (phase is Phase.Running || phase is Phase.Between) -> {
                fail("left the screen (home/recents/screen off/call)")
                goHome("left during sequence")
            }
            else -> {
                FLog.d(AREA, "stopped in $mode/$phase -> closing")
                closeToApp()
            }
        }
    }

    /** Finish and reveal whatever is underneath (the unlocked app, or Friction's own UI). */
    private fun closeToApp() {
        if (closing) return
        closing = true
        finish()
    }

    private fun goHome(reason: String) {
        if (closing) return
        closing = true
        FLog.d(AREA, "going home: $reason")
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        FLog.d(AREA, "destroyed (phase=$phase)")
        handler.removeCallbacksAndMessages(null)
        GateGuard.onScreenDestroyed()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun SequenceScreen(groupName: String) {
        Box(
            Modifier.fillMaxSize().background(Color(0xE6101418)).safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val p = phase) {
                Phase.Intro -> Centered {
                    Big(if (practice) "Practice: $title" else groupName)
                    if (!practice) Small("\"$title\" · ${steps.size} task${if (steps.size == 1) "" else "s"}")
                    if (steps.isEmpty()) Small("Nothing to do.")
                    Button(onClick = { if (steps.isEmpty()) closeToApp() else startTask(0) }) { Text("Start") }
                    OutlinedButton(onClick = { letGo("Let Go on intro") }) { Text("Let Go") }
                }
                is Phase.Running -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Small("Task ${p.index + 1} of ${steps.size} · ${steps[p.index].name}")
                    Box(Modifier.padding(top = 24.dp)) {
                        key(p.runId) {
                            TaskHost(p.typeId, p.params) { passed, detail -> onTaskResult(p.runId, passed, detail) }
                        }
                    }
                }
                is Phase.Between -> Centered {
                    Big("Task ${p.passedIndex + 1} of ${steps.size} passed.")
                    Button(onClick = { startTask(p.passedIndex + 1) }) { Text("Continue") }
                    OutlinedButton(onClick = { letGo("Let Go after task ${p.passedIndex + 1}") }) { Text("Let Go") }
                }
                is Phase.Failed -> Centered {
                    Big("Failed.")
                    Small(p.reason)
                    if (practice) {
                        OutlinedButton(onClick = { closeToApp() }) { Text("Close") }
                    } else {
                        val minutes = ConfigStore.current.taskSequence(sequenceId)?.failBlockMinutes ?: 0.0
                        Small("$groupName is blocked for ${formatMinutes(minutes)}.")
                        OutlinedButton(onClick = { goHome("ok after failure") }) { Text("OK") }
                    }
                }
                Phase.Passed -> Centered {
                    Big(if (practice) "Passed." else "Access granted.")
                    if (practice) OutlinedButton(onClick = { closeToApp() }) { Text("Close") }
                }
            }
        }
    }

    companion object {
        private const val AREA = "Screen"
        const val EXTRA_MODE = "mode"
        const val EXTRA_GROUP = "group"
        const val EXTRA_SEQUENCE = "sequence"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_TITLE = "title"
        const val MODE_BLOCK = "block"
        const val MODE_SEQUENCE = "sequence"
        const val MODE_PRACTICE = "practice"

        fun practiceIntent(context: android.content.Context, title: String, variantIds: List<String>): Intent =
            Intent(context, FrictionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, MODE_PRACTICE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_STEPS, variantIds.toTypedArray())
    }
}

fun formatMinutes(m: Double): String {
    val totalSec = (m * 60).toLong()
    return if (totalSec % 60 == 0L) "${totalSec / 60} min" else "${totalSec / 60} min ${totalSec % 60} s"
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Big(text: String) {
    Text(text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
}

@Composable
private fun Small(text: String) {
    Text(text, color = Color(0xFFBBBBBB), fontSize = 15.sp, textAlign = TextAlign.Center)
}

@Composable
private fun BlockScreen(groupName: String, untilMs: Long, onHome: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val left = (untilMs - now).coerceAtLeast(0L)
    val sec = (left + 999) / 1000
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Small("$groupName is blocked")
            Text(
                "%d:%02d".format(sec / 60, sec % 60),
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
            )
            if (left == 0L) Small("You may try again.")
            OutlinedButton(onClick = onHome) { Text("Go home") }
        }
    }
}
