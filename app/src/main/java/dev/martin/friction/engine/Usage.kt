package dev.martin.friction.engine

/** A simplified UsageStats event. */
data class UsageEvent(val timeMs: Long, val pkg: String, val cls: String?, val kind: Kind) {
    enum class Kind { RESUMED, PAUSED, SCREEN_OFF, SHUTDOWN }
}

object UsageCalc {
    /**
     * Foreground time of [packages] within [fromMs, toMs].
     *
     * Assumes one foreground app at a time: when another package resumes, the previous
     * one's interval ends (this also protects against missing PAUSED events). Friction's own
     * screens therefore stop the clock of the app underneath them.
     * Pass events starting a bit before [fromMs] so an app already open at [fromMs] is counted.
     */
    fun foregroundMs(events: List<UsageEvent>, fromMs: Long, toMs: Long, packages: Set<String>): Long {
        var total = 0L
        var currentPkg: String? = null
        var currentStart = 0L
        val openClasses = mutableSetOf<String?>()

        fun close(at: Long) {
            val pkg = currentPkg ?: return
            if (pkg in packages) {
                val a = maxOf(currentStart, fromMs)
                val b = minOf(at, toMs)
                if (b > a) total += b - a
            }
            currentPkg = null
            openClasses.clear()
        }

        for (e in events.sortedBy { it.timeMs }) {
            if (e.timeMs > toMs) break
            when (e.kind) {
                UsageEvent.Kind.RESUMED -> {
                    if (currentPkg != e.pkg) {
                        close(e.timeMs)
                        currentPkg = e.pkg
                        currentStart = e.timeMs
                    }
                    openClasses += e.cls
                }
                UsageEvent.Kind.PAUSED -> {
                    if (currentPkg == e.pkg) {
                        openClasses -= e.cls
                        if (openClasses.isEmpty()) close(e.timeMs)
                    }
                }
                UsageEvent.Kind.SCREEN_OFF, UsageEvent.Kind.SHUTDOWN -> close(e.timeMs)
            }
        }
        close(toMs)
        return total
    }
}
