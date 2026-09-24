package dev.martin.friction.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.martin.friction.BuildConfig
import dev.martin.friction.FLog
import dev.martin.friction.FrictionAccessibilityService
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.data.StatsLog
import dev.martin.friction.engine.Engine
import dev.martin.friction.formatMinutes
import dev.martin.friction.service.GateController
import dev.martin.friction.service.UsageSource
import kotlinx.coroutines.delay
import java.time.ZoneId

// ------------------------------------------------------------------ permission checks

fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, FrictionAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    return splitter.any { ComponentName.unflattenFromString(it) == expected }
}

fun isBatteryUnrestricted(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

fun Context.openSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        FLog.e("Main", "could not open ${intent.action}", e)
    }
}

fun Context.share(subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    openSafely(Intent.createChooser(send, subject))
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(nav: Nav, resumeTick: Int) {
    val context = LocalContext.current
    val a11y = remember(resumeTick) { isAccessibilityEnabled(context) }
    val usage = remember(resumeTick) { UsageSource.hasPermission(context) }
    Page("Friction", onBack = null, actions = { Text("v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp) }) {
        if (!a11y || !usage) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Setup incomplete", fontWeight = FontWeight.SemiBold, color = BadRed)
                    if (!a11y) Text("• Accessibility service is off: nothing is blocked.")
                    if (!usage) Text("• Usage access is off: stages will never advance.")
                    TextButton(onClick = { nav.push(Screen.Setup) }) { Text("Open setup") }
                }
            }
        }
        ItemCard("Status", "Stages, sessions and blocks right now") { nav.push(Screen.Status) }
        SectionTitle("Rules")
        ItemCard("App groups", "Which apps, and which stage sequence they follow") { nav.push(Screen.Groups) }
        ItemCard("Stage sequences", "How the day escalates") { nav.push(Screen.StageSequences) }
        ItemCard("Task sequences", "What you must pass to open an app") { nav.push(Screen.TaskSequences) }
        ItemCard("Task variants", "The building blocks of sequences") { nav.push(Screen.TaskVariants) }
        ItemCard("Interruption variants", "Disturbances during sessions") { nav.push(Screen.InterruptionVariants) }
        SectionTitle("Other")
        ItemCard("Setup", "Permissions, daily reset time, export / import") { nav.push(Screen.Setup) }
        ItemCard("Log", "Everything Friction did (for debugging)") { nav.push(Screen.Log) }
    }
}

// ------------------------------------------------------------------ setup

@Composable
private fun PermissionCard(title: String, granted: Boolean, explanation: String, actions: List<Pair<String, () -> Unit>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (granted) "ON" else "OFF", color = if (granted) OkGreen else BadRed, fontWeight = FontWeight.Bold)
            }
            Text(explanation, fontSize = 14.sp)
            actions.forEach { (label, action) -> OutlinedButton(onClick = action) { Text(label, fontSize = 13.sp) } }
        }
    }
}

@Composable
fun SetupScreen(nav: Nav, resumeTick: Int) {
    val context = LocalContext.current
    val config by ConfigStore.config.collectAsState()
    val a11y = remember(resumeTick) { isAccessibilityEnabled(context) }
    val usage = remember(resumeTick) { UsageSource.hasPermission(context) }
    val overlay = remember(resumeTick) { Settings.canDrawOverlays(context) }
    val battery = remember(resumeTick) { isBatteryUnrestricted(context) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    Page("Setup", onBack = nav::pop) {
        PermissionCard(
            "1. Accessibility service (required)", a11y,
            "Lets Friction see which app opens. If the toggle is greyed out: App info → ⋮ → \"Allow restricted settings\".",
            listOf(
                "Open Accessibility settings" to { context.openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "Open App info" to { context.openSafely(appInfo) },
            ),
        )
        PermissionCard(
            "2. Usage access (required)", usage,
            "Measures how long you've used each group today, which decides the stage.",
            listOf("Grant" to { context.openSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
        )
        PermissionCard(
            "3. Display over other apps (recommended)", overlay,
            "Backup permission for opening Friction's screens from the background.",
            listOf("Grant" to {
                context.openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }),
        )
        PermissionCard(
            "4. Unrestricted battery (recommended)", battery,
            "Keeps Samsung from putting Friction to sleep.",
            listOf("Grant" to {
                context.openSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }),
        )

        SectionTitle("Daily reset")
        Hint("At this time every group returns to its first stage, sessions end and usage starts from zero. Blocks keep running.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Hour", config.resetHour.toDouble(), { h ->
                ConfigStore.update("reset hour ${h.toInt()}") { it.copy(resetHour = h.toInt()) }
            }, Modifier.weight(1f), "0–23", 0.0, 23.0)
            NumberField("Minute", config.resetMinute.toDouble(), { m ->
                ConfigStore.update("reset minute ${m.toInt()}") { it.copy(resetMinute = m.toInt()) }
            }, Modifier.weight(1f), "0–59", 0.0, 59.0)
        }

        SectionTitle("Configuration")
        Hint("Export shares the whole configuration as JSON (backup, or to edit it with Claude). Import replaces it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { context.share("Friction configuration", ConfigStore.export()) }) { Text("Export") }
            OutlinedButton(onClick = { importing = true }) { Text("Import") }
        }
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import configuration") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Paste JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        ConfigStore.import(text)
                        importing = false
                        message = "Imported."
                    } catch (e: Exception) {
                        message = "Invalid JSON: ${e.message}"
                    }
                }) { Text("Replace everything") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
    message?.let { MessageDialog("Import", it) { message = null } }
}

// ------------------------------------------------------------------ status

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun StatusScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    Page("Status", onBack = nav::pop) {
        if (FrictionAccessibilityService.instance == null) Text("Accessibility service is not running.", color = BadRed)
        val dayStart = Engine.dayStartMs(now, config.resetHour, config.resetMinute, ZoneId.systemDefault())
        Hint("Day started ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(dayStart))}")
        if (config.groups.isEmpty()) Hint("No app groups yet.")
        config.groups.forEach { g ->
            // `refresh` and `now` are read so this recomputes every second / after debug actions.
            val usage = remember(now / 5000, refresh) { GateController.usageMs(g, now) }
            val st = remember(now, refresh) { GateController.state(g.id) }
            val stages = Engine.stagesOf(config, g)
            val idx = if (stages.isEmpty()) -1 else Engine.stageIndexFor(usage, stages)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.name, fontWeight = FontWeight.SemiBold)
                    Text("${g.packages.size} app(s) · used today: ${fmtDuration(usage)}")
                    if (idx >= 0) {
                        val stage = stages[idx]
                        val seqName = config.taskSequence(stage.taskSequenceId)?.name ?: "free"
                        var line = "Stage ${idx + 1} of ${stages.size} ($seqName)"
                        if (idx < stages.lastIndex) {
                            val end = stages.take(idx + 1).sumOf { (it.durationMinutes * 60_000).toLong() }
                            line += " · next stage in ${fmtDuration((end - usage).coerceAtLeast(0))} of usage"
                        }
                        Text(line)
                    } else {
                        Text("No stage sequence: not restricted", color = Muted)
                    }
                    val sess = st.session
                    if (sess != null && st.dayStartMs == dayStart) {
                        val left = if (sess.endsAtMs == Long.MAX_VALUE) "unlimited" else fmtDuration((sess.endsAtMs - now).coerceAtLeast(0)) + " left"
                        Text("Session: $left", color = OkGreen)
                        if (st.interruption != null) Text("Interruption active", color = Muted)
                    } else {
                        Text("No active session", color = Muted)
                    }
                    if (st.blockedUntilMs > now) Text("BLOCKED for ${fmtDuration(st.blockedUntilMs - now)}", color = BadRed)
                    if (st.debugExtraUsageMs > 0 && st.dayStartMs == dayStart) {
                        Text("(includes ${formatMinutes(st.debugExtraUsageMs / 60_000.0)} of debug usage)", color = Muted, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { GateController.debugAddUsage(g.id, 10); refresh++ }) { Text("Debug: +10 min", fontSize = 12.sp) }
                        TextButton(onClick = { GateController.debugClear(g.id); refresh++ }) { Text("Debug: clear", fontSize = 12.sp) }
                    }
                }
            }
        }
        Hint("Debug buttons exist for testing and are recorded in the stats log.")
    }
}

// ------------------------------------------------------------------ log

@Composable
fun LogScreen(nav: Nav, resumeTick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val text = remember(resumeTick, refresh) { FLog.readAll() }
    val tail = remember(text) { text.lines().takeLast(500).joinToString("\n") }
    Page("Log", onBack = nav::pop, scroll = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh", fontSize = 12.sp) }
            OutlinedButton(onClick = { FLog.clear(); refresh++ }) { Text("Clear", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { context.share("Friction log v${BuildConfig.VERSION_NAME}", text.takeLast(300_000)) }) {
                Text("Share log", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { context.share("Friction stats", StatsLog.readAll().takeLast(300_000)) }) {
                Text("Share stats", fontSize = 12.sp)
            }
        }
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
            Text(
                if (tail.isBlank()) "(empty)" else tail,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = Color(0xFFDDDDDD),
            )
        }
    }
}
