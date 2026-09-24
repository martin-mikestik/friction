package dev.martin.friction

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.data.StateStore
import dev.martin.friction.data.StatsLog
import dev.martin.friction.engine.ActiveInterruption
import dev.martin.friction.engine.AppGroup
import dev.martin.friction.engine.Trigger
import dev.martin.friction.engine.Verdict
import dev.martin.friction.service.GateController
import dev.martin.friction.service.GateGuard
import dev.martin.friction.service.InterruptionOverlay

/**
 * Watches which app is in the foreground. When it belongs to an App Group, asks the
 * GateController what to do (allow / block screen / task sequence / kick) and, while the
 * user stays inside, ticks sessions and interruptions twice a second.
 *
 * (Class name kept from the dummy build so the Accessibility permission survives updates.)
 */
class FrictionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: InterruptionOverlay

    /** Package of the current foreground activity (null while the screen is off). */
    private var foregroundPkg: String? = null
    private var foregroundGroupId: String? = null
    private val activityCache = HashMap<String, Boolean>()

    private var ticking = false
    private var lastTickAt = 0L
    private var lastStateSaveAt = 0L

    private val tick = object : Runnable {
        override fun run() = onTick()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    FLog.d(AREA, "screen off")
                    leaveForeground()
                    foregroundPkg = null
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    FLog.d(AREA, "screen on/unlocked (${intent.action})")
                    handler.postDelayed({ refreshForegroundFromWindow("screen-on") }, 400)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = InterruptionOverlay(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        FLog.i(AREA, "service CONNECTED; groups=${ConfigStore.current.groups.map { "${it.name}(${it.packages.size})" }}")
        StatsLog.event("service_connected")
    }

    // ---------------------------------------------------------------- foreground tracking

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        val isActivity = isActivity(pkg, cls)
        val inGroup = ConfigStore.current.groupForPackage(pkg) != null
        if (!isActivity && !(inGroup && pkg != foregroundPkg)) {
            // Dialogs, keyboards, notification shade, toasts... don't change the foreground app.
            return
        }
        onForeground(pkg, "window $cls")
    }

    private fun isActivity(pkg: String, cls: String?): Boolean {
        if (cls == null) return false
        return activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun refreshForegroundFromWindow(reason: String) {
        val pkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        FLog.d(AREA, "active window after $reason: $pkg")
        if (pkg != null) {
            foregroundPkg = null // force re-evaluation
            onForeground(pkg, reason)
        }
    }

    private fun onForeground(pkg: String, reason: String) {
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        val group = ConfigStore.current.groupForPackage(pkg)
        FLog.d(AREA, "foreground: $pkg ($reason) group=${group?.name}")

        if (group?.id != foregroundGroupId) leaveForeground()
        if (group == null) return
        foregroundGroupId = group.id

        if (pkg == packageName) return
        val verdict = GateController.evaluate(group, Trigger.OPEN)
        act(group, verdict)
        if (verdict == Verdict.Allow) startTicking()
    }

    /** User left the group's apps: pause interruptions (their time stops) and stop ticking. */
    private fun leaveForeground() {
        if (foregroundGroupId != null) FLog.d(AREA, "left group $foregroundGroupId")
        foregroundGroupId = null
        stopTicking()
        if (::overlay.isInitialized) overlay.hide()
        StateStore.save()
    }

    // ---------------------------------------------------------------- actions

    private fun act(group: AppGroup, verdict: Verdict) {
        when (verdict) {
            Verdict.Allow -> {}
            is Verdict.Block -> launchGate(group, FrictionActivity.MODE_BLOCK, null)
            is Verdict.RunSequence -> launchGate(group, FrictionActivity.MODE_SEQUENCE, verdict.sequenceId)
            is Verdict.Kick -> kick(verdict.reason)
        }
    }

    private fun kick(reason: String) {
        FLog.i(AREA, "KICK -> home ($reason)")
        stopTicking()
        overlay.hide()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun launchGate(group: AppGroup, mode: String, sequenceId: String?) {
        if (GateGuard.isBusy()) {
            FLog.d(AREA, "gate screen already up/starting -> not launching $mode again")
            return
        }
        stopTicking()
        overlay.hide()
        GateGuard.onLaunchRequested()
        val intent = Intent(this, FrictionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(FrictionActivity.EXTRA_MODE, mode)
            .putExtra(FrictionActivity.EXTRA_GROUP, group.id)
            .putExtra(FrictionActivity.EXTRA_SEQUENCE, sequenceId)
        try {
            startActivity(intent)
            FLog.i(AREA, "launched gate: $mode for ${group.name}")
        } catch (e: Exception) {
            FLog.e(AREA, "startActivity failed", e)
            return
        }
        handler.postDelayed({
            if (!GateGuard.appearedSinceLaunch) {
                FLog.w(AREA, "gate screen did NOT appear within ${GateGuard.LAUNCH_TIMEOUT_MS}ms (blocked by the system?) -> going home")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, GateGuard.LAUNCH_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- ticking

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastTickAt = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun onTick() {
        if (!ticking) return
        val group = ConfigStore.current.group(foregroundGroupId)
        if (group == null || foregroundPkg == null || foregroundPkg !in group.packages) {
            stopTicking()
            overlay.hide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastTickAt).coerceIn(0L, 2_000L)
        lastTickAt = now

        val verdict = GateController.evaluate(group, Trigger.TICK)
        if (verdict != Verdict.Allow) {
            act(group, verdict)
            return
        }
        val active: ActiveInterruption? = GateController.tickInterruptions(group, elapsed)
        if (active != null) overlay.show(active) else overlay.hide()

        if (now - lastStateSaveAt > 10_000L) {
            StateStore.save()
            lastStateSaveAt = now
        }
        handler.postDelayed(tick, TICK_MS)
    }

    // ---------------------------------------------------------------- previews from the UI

    /** Show an interruption over whatever is on screen (used by the editor's preview button). */
    fun preview(a: ActiveInterruption) {
        overlay.show(a)
        handler.postDelayed({ overlay.hide() }, a.remainingMs)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onInterrupt() {
        FLog.w(AREA, "service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        FLog.w(AREA, "service UNBOUND (disabled in settings or killed)")
        StatsLog.event("service_unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        FLog.w(AREA, "service destroyed")
        instance = null
        stopTicking()
        if (::overlay.isInitialized) overlay.hide()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        StateStore.save()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val AREA = "Service"
        private const val TICK_MS = 500L

        @Volatile
        var instance: FrictionAccessibilityService? = null
            private set
    }
}
