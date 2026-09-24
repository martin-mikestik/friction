package dev.martin.friction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Bumped on every onResume so the UI re-checks permissions after returning from Settings. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FLog.d("Main", "MainActivity created")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(resumeTick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

// ---------- permission checks ----------

fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, FrictionAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    return splitter.any { ComponentName.unflattenFromString(it) == expected }
}

fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

fun isBatteryUnrestricted(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        FLog.e("Main", "could not open ${intent.action}", e)
    }
}

// ---------- UI ----------

@Composable
private fun MainScreen(resumeTick: Int) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            "Friction  ·  dummy v${BuildConfig.VERSION_NAME}",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Setup", "Apps", "Log").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> SetupTab(resumeTick)
            1 -> AppsTab()
            else -> LogTab(resumeTick)
        }
    }
}

@Composable
private fun SetupTab(resumeTick: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reading resumeTick makes this recompute every time the activity resumes.
    val a11y = remember(resumeTick) { isAccessibilityEnabled(context) }
    val overlay = remember(resumeTick) { canDrawOverlays(context) }
    val battery = remember(resumeTick) { isBatteryUnrestricted(context) }
    remember(resumeTick) {
        FLog.d("Main", "permissions: accessibility=$a11y overlay=$overlay batteryUnrestricted=$battery")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            title = "1. Accessibility service (required)",
            granted = a11y,
            explanation = "Lets Friction see which app just opened. Settings → Installed apps → Friction → toggle on.\n\n" +
                "If the toggle is greyed out (\"Restricted setting\"): open App info below, tap ⋮ in the top-right corner → " +
                "\"Allow restricted settings\", then try again.",
            actions = listOf(
                "Open Accessibility settings" to { context.open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "Open App info" to {
                    context.open(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            ),
        )
        PermissionCard(
            title = "2. Display over other apps (recommended)",
            granted = overlay,
            explanation = "A backup that lets Android open Friction's screen from the background.",
            actions = listOf(
                "Grant" to {
                    context.open(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                },
            ),
        )
        PermissionCard(
            title = "3. Unrestricted battery (recommended on Samsung)",
            granted = battery,
            explanation = "Stops Samsung's battery manager from killing Friction in the background.",
            actions = listOf(
                "Grant" to {
                    context.open(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
            ),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Transparency test", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Shows the friction screen right now, on top of this app.", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    FLog.i("Main", "manual preview of friction screen")
                    context.open(
                        Intent(context, FrictionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(FrictionActivity.EXTRA_PKG, context.packageName)
                    )
                }) { Text("Preview friction screen") }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    explanation: String,
    actions: List<Pair<String, () -> Unit>>,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (granted) "ON" else "OFF",
                    color = if (granted) Color(0xFF7BD88F) else Color(0xFFFF7A7A),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(explanation, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                actions.forEach { (label, action) ->
                    OutlinedButton(onClick = action) { Text(label, fontSize = 13.sp) }
                }
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
private fun AppsTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    var blocked by remember { mutableStateOf(BlockList.get(context)) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Text(
            "${blocked.size} blocked",
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading apps…") }
        } else {
            val shown = list
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
                .sortedWith(compareByDescending<AppEntry> { it.pkg in blocked }.thenBy { it.label.lowercase() })
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.pkg }) { app ->
                    val isBlocked = app.pkg in blocked
                    val toggle = {
                        BlockList.set(context, app.pkg, !isBlocked)
                        blocked = BlockList.get(context)
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (app.icon != null) {
                            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            Text(app.pkg, fontSize = 11.sp, color = Color.Gray)
                        }
                        Checkbox(checked = isBlocked, onCheckedChange = { toggle() })
                    }
                }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val result = pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            val icon = try {
                pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), icon)
        }
    FLog.d("Main", "loaded ${result.size} launchable apps")
    return result
}

@Composable
private fun LogTab(resumeTick: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val text = remember(resumeTick, refresh) { FLog.readAll() }
    val tail = remember(text) { text.lines().takeLast(400).joinToString("\n") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
            OutlinedButton(onClick = {
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Friction log v${BuildConfig.VERSION_NAME}")
                    .putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
                context.open(Intent.createChooser(share, "Share log"))
            }) { Text("Share") }
            OutlinedButton(onClick = { FLog.clear(); refresh++ }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
            Text(
                if (tail.isBlank()) "(empty)" else tail,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }
    }
}
