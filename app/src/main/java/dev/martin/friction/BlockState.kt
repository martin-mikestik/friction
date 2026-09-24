package dev.martin.friction

import android.content.Context
import android.os.SystemClock

/** Persisted set of blocked package names (SharedPreferences). */
object BlockList {
    private const val PREFS = "friction"
    private const val KEY = "blocked_packages"

    @Volatile private var cache: Set<String>? = null

    fun get(context: Context): Set<String> =
        cache ?: (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()).also { cache = it }

    fun isBlocked(context: Context, pkg: String) = pkg in get(context)

    fun set(context: Context, pkg: String, blocked: Boolean) {
        val next = get(context).toMutableSet().apply { if (blocked) add(pkg) else remove(pkg) }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
        cache = next
        FLog.i("BlockList", "${if (blocked) "added" else "removed"} $pkg -> now ${next.size} blocked: $next")
    }
}

/**
 * In-memory runtime state shared by the service and the friction screen
 * (they run in the same process). Lost if the process dies — fine for the dummy.
 */
object Session {
    const val COUNTDOWN_SECONDS = 5
    const val GRACE_MS = 15_000L
    /** If the screen hasn't appeared this long after we asked for it, assume the launch failed. */
    const val LAUNCH_TIMEOUT_MS = 2_000L

    private val graceUntil = mutableMapOf<String, Long>()
    @Volatile private var launchRequestedAt = 0L
    @Volatile var screenAlive = false
        private set

    private fun now() = SystemClock.elapsedRealtime()

    @Synchronized
    fun graceRemainingMs(pkg: String): Long = ((graceUntil[pkg] ?: 0L) - now()).coerceAtLeast(0L)

    @Synchronized
    fun grantGrace(pkg: String) {
        graceUntil[pkg] = now() + GRACE_MS
    }

    /** True while the friction screen is up, or was just requested and may still be starting. */
    fun isScreenBusy(): Boolean =
        screenAlive || (launchRequestedAt != 0L && now() - launchRequestedAt < LAUNCH_TIMEOUT_MS)

    fun onLaunchRequested() { launchRequestedAt = now() }
    fun onScreenCreated() { screenAlive = true; launchRequestedAt = 0L }
    fun onScreenDestroyed() { screenAlive = false }
}
