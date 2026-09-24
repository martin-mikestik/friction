package dev.martin.friction

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Gets a callback from the system every time a window changes. When the new
 * foreground app is on the block list (and not in its grace period), it opens
 * FrictionActivity on top of it.
 */
class FrictionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastClass: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FLog.i(AREA, "service CONNECTED; blocked=${BlockList.get(this)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        if (pkg != lastPkg || cls != lastClass) {
            FLog.d(AREA, "window: $pkg / $cls")
        }
        lastPkg = pkg
        lastClass = cls
        maybeBlock(pkg, "window-change")
    }

    private fun maybeBlock(pkg: String, reason: String) {
        if (pkg == packageName) return
        if (!BlockList.isBlocked(this, pkg)) return

        if (Session.isScreenBusy()) {
            FLog.d(AREA, "skip $pkg ($reason): friction screen already up/starting")
            return
        }
        val grace = Session.graceRemainingMs(pkg)
        if (grace > 0) {
            FLog.d(AREA, "skip $pkg ($reason): in grace, ${grace}ms left")
            return
        }

        FLog.i(AREA, "BLOCK $pkg ($reason) -> launching friction screen")
        Session.onLaunchRequested()
        val intent = Intent(this, FrictionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(FrictionActivity.EXTRA_PKG, pkg)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            FLog.e(AREA, "startActivity failed", e)
            return
        }
        // Android can silently refuse background activity starts; detect that.
        handler.postDelayed({
            if (!Session.screenAlive) {
                FLog.w(AREA, "friction screen did NOT appear within ${Session.LAUNCH_TIMEOUT_MS}ms for $pkg (launch blocked by the system?)")
            }
        }, Session.LAUNCH_TIMEOUT_MS)
    }

    /** Called by FrictionActivity after the countdown: re-check once the grace period ends. */
    fun scheduleGraceCheck(pkg: String) {
        handler.postDelayed({
            val active = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            val foreground = active ?: lastPkg
            FLog.i(AREA, "grace over for $pkg; active window=$active, last event=$lastPkg")
            if (foreground == pkg) maybeBlock(pkg, "grace-expired")
        }, Session.GRACE_MS + 200)
    }

    override fun onInterrupt() {
        FLog.w(AREA, "service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        FLog.w(AREA, "service UNBOUND (disabled in settings or killed)")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        FLog.w(AREA, "service destroyed")
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val AREA = "Service"
        @Volatile var instance: FrictionAccessibilityService? = null
            private set
    }
}
