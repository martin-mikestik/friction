package dev.martin.friction.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dev.martin.friction.FLog
import dev.martin.friction.engine.UsageCalc
import dev.martin.friction.engine.UsageEvent

/** Reads app usage from Android's UsageStats (needs the "Usage access" permission). */
object UsageSource {
    private const val LOOKBACK_MS = 6 * 60 * 60 * 1000L

    // UsageEvents.Event constants as literals (some were only named in later SDKs).
    private const val ACTIVITY_RESUMED = 1
    private const val ACTIVITY_PAUSED = 2
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground time of [packages] between [fromMs] and [toMs]; null if permission is missing. */
    fun foregroundMs(context: Context, packages: Set<String>, fromMs: Long, toMs: Long): Long? {
        if (packages.isEmpty()) return 0L
        if (!hasPermission(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val list = ArrayList<UsageEvent>()
        try {
            val events = usm.queryEvents(fromMs - LOOKBACK_MS, toMs)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val kind = when (e.eventType) {
                    ACTIVITY_RESUMED -> UsageEvent.Kind.RESUMED
                    ACTIVITY_PAUSED -> UsageEvent.Kind.PAUSED
                    SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN -> UsageEvent.Kind.SCREEN_OFF
                    DEVICE_SHUTDOWN -> UsageEvent.Kind.SHUTDOWN
                    else -> null
                } ?: continue
                list += UsageEvent(e.timeStamp, e.packageName ?: "", e.className, kind)
            }
        } catch (ex: Exception) {
            FLog.e("Usage", "queryEvents failed", ex)
            return null
        }
        return UsageCalc.foregroundMs(list, fromMs, toMs, packages)
    }
}
