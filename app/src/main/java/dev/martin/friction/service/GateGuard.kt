package dev.martin.friction.service

import android.os.SystemClock

/** Tracks whether Friction's gate screen is up (or was just requested), to avoid double launches. */
object GateGuard {
    const val LAUNCH_TIMEOUT_MS = 2_000L

    @Volatile private var launchRequestedAt = 0L
    @Volatile var screenAlive = false
        private set

    private fun now() = SystemClock.elapsedRealtime()

    fun isBusy(): Boolean =
        screenAlive || (launchRequestedAt != 0L && now() - launchRequestedAt < LAUNCH_TIMEOUT_MS)

    /** False between a launch request and the screen actually appearing. */
    @Volatile var appearedSinceLaunch = true
        private set

    fun onLaunchRequested() { launchRequestedAt = now(); appearedSinceLaunch = false }
    fun onScreenCreated() { screenAlive = true; appearedSinceLaunch = true; launchRequestedAt = 0L }
    fun onScreenDestroyed() { screenAlive = false }
}
