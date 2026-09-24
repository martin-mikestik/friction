package dev.martin.friction

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/**
 * Transparent screen shown on top of a blocked app. The blocked app stays visible
 * (tinted, and blurred where supported) behind a 5-second countdown. When it hits
 * zero the screen closes and the app gets a 15-second grace period.
 * Back / leaving during the countdown sends you to the home screen instead.
 */
class FrictionActivity : ComponentActivity() {

    private var pkg: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Session.onScreenCreated()

        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        FLog.i(AREA, "created for '$pkg'")
        if (pkg.isEmpty()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this) {
            FLog.i(AREA, "back pressed during countdown -> home")
            goHome()
        }

        val (label, icon) = loadApp(pkg)
        setContent {
            FrictionScreen(label = label, icon = icon, onDone = ::complete, onLeave = ::goHome)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FLog.d(AREA, "onNewIntent for ${intent.getStringExtra(EXTRA_PKG)} (already showing '$pkg')")
    }

    private fun loadApp(pkg: String): Pair<String, ImageBitmap?> = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        val label = packageManager.getApplicationLabel(info).toString()
        val icon = packageManager.getApplicationIcon(info).toBitmap(192, 192).asImageBitmap()
        label to icon
    } catch (e: PackageManager.NameNotFoundException) {
        pkg to null
    }

    private fun complete() {
        if (completed || isFinishing) return
        completed = true
        Session.grantGrace(pkg)
        val service = FrictionAccessibilityService.instance
        service?.scheduleGraceCheck(pkg)
        FLog.i(AREA, "countdown done -> ${Session.GRACE_MS / 1000}s grace for $pkg (service alive=${service != null})")
        finish()
    }

    private fun goHome() {
        if (isFinishing) return
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!completed && !isFinishing) {
            FLog.i(AREA, "left the friction screen before the countdown ended (home/recents?) -> closing, no grace")
            finish()
        }
    }

    override fun onDestroy() {
        FLog.d(AREA, "destroyed (completed=$completed)")
        Session.onScreenDestroyed()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val AREA = "Screen"
    }
}

@Composable
private fun FrictionScreen(label: String, icon: ImageBitmap?, onDone: () -> Unit, onLeave: () -> Unit) {
    var remaining by remember { mutableIntStateOf(Session.COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        onDone()
    }

    // Semi-transparent tint: the blocked app is still visible behind it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3101820)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text("Opening $label in", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            remaining.toString(),
            color = Color(0xFFF2C14E),
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onLeave) {
            Text("Never mind", color = Color.White)
        }
    }
}
