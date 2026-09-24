package dev.martin.friction.service

import android.content.Context
import dev.martin.friction.FLog
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.data.StateStore
import dev.martin.friction.data.StatsLog
import dev.martin.friction.engine.ActiveInterruption
import dev.martin.friction.engine.AppGroup
import dev.martin.friction.engine.Engine
import dev.martin.friction.engine.GroupState
import dev.martin.friction.engine.Trigger
import dev.martin.friction.engine.Verdict
import java.time.ZoneId
import kotlin.random.Random

enum class SequenceOutcome { PASSED, FAILED, LET_GO }

/**
 * Glue between Android and the pure [Engine]: fetches usage, keeps group state,
 * persists it and writes stats. Everything runs on the main thread.
 */
object GateController {
    private const val AREA = "Gate"
    private const val USAGE_CACHE_MS = 5_000L

    private lateinit var app: Context
    private val rng = Random.Default
    private val usageCache = mutableMapOf<String, Pair<Long, Long>>() // groupId -> (computedAt, usageMs)
    private var warnedNoUsagePermission = false

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun state(groupId: String): GroupState = StateStore.get(groupId)

    private fun save(group: AppGroup, old: GroupState, new: GroupState) {
        // Persist whenever something important changed; interruption countdowns are saved periodically.
        val important = old.dayStartMs != new.dayStartMs || old.session != new.session ||
            old.blockedUntilMs != new.blockedUntilMs || old.debugExtraUsageMs != new.debugExtraUsageMs ||
            (old.interruption == null) != (new.interruption == null)
        StateStore.put(group.id, new, persist = important)
    }

    /** Group usage since today's reset (real UsageStats + debug offset), cached for a few seconds. */
    fun usageMs(group: AppGroup, nowMs: Long = System.currentTimeMillis(), fresh: Boolean = false): Long {
        val cfg = ConfigStore.current
        val dayStart = Engine.dayStartMs(nowMs, cfg.resetHour, cfg.resetMinute, zone)
        val cached = usageCache[group.id]
        val base = if (!fresh && cached != null && nowMs - cached.first < USAGE_CACHE_MS) {
            cached.second
        } else {
            val measured = UsageSource.foregroundMs(app, group.packages, dayStart, nowMs)
            if (measured == null && !warnedNoUsagePermission) {
                FLog.w(AREA, "Usage access not granted -> usage counts as 0 (stages will never advance)")
                warnedNoUsagePermission = true
            }
            (measured ?: 0L).also { usageCache[group.id] = nowMs to it }
        }
        val st = state(group.id)
        val extra = if (st.dayStartMs == dayStart) st.debugExtraUsageMs else 0L
        return base + extra
    }

    fun evaluate(group: AppGroup, trigger: Trigger): Verdict {
        val now = System.currentTimeMillis()
        val cfg = ConfigStore.current
        val old = state(group.id)
        val usage = usageMs(group, now)
        val ev = Engine.evaluate(cfg, group, old, usage, now, trigger, zone, rng)
        save(group, old, ev.state)
        ev.notes.forEach { FLog.i(AREA, "[${group.name}] $it") }
        if (trigger == Trigger.OPEN || ev.verdict != Verdict.Allow) {
            FLog.i(AREA, "[${group.name}] $trigger usage=${usage / 1000}s -> ${ev.verdict}")
        }
        when (val v = ev.verdict) {
            is Verdict.Kick -> StatsLog.event("kick", "group" to group.name, "reason" to v.reason)
            is Verdict.Block -> if (trigger == Trigger.OPEN) StatsLog.event("open_while_blocked", "group" to group.name,
                "secondsLeft" to (v.untilMs - now) / 1000)
            else -> {}
        }
        return ev.verdict
    }

    /** Advances interruptions by in-app time; returns the interruption that should be visible now. */
    fun tickInterruptions(group: AppGroup, elapsedMs: Long): ActiveInterruption? {
        val old = state(group.id)
        val res = Engine.tickInterruptions(ConfigStore.current, group, old, elapsedMs, rng)
        save(group, old, res.state)
        res.note?.let {
            FLog.i(AREA, "[${group.name}] $it")
            StatsLog.event("interruption", "group" to group.name, "note" to it)
        }
        return res.state.interruption
    }

    fun onSequenceResult(groupId: String, sequenceId: String, outcome: SequenceOutcome, detail: String) {
        val cfg = ConfigStore.current
        val group = cfg.group(groupId) ?: return
        val seq = cfg.taskSequence(sequenceId)
        val now = System.currentTimeMillis()
        val old = state(groupId)
        val new = when (outcome) {
            SequenceOutcome.PASSED -> Engine.onSequencePassed(cfg, group, old, usageMs(group, now, fresh = true), now, zone, rng)
            SequenceOutcome.FAILED -> Engine.onSequenceFailed(old, now, seq?.failBlockMinutes ?: 10.0)
            SequenceOutcome.LET_GO -> old
        }
        save(group, old, new)
        StateStore.save()
        FLog.i(AREA, "[${group.name}] sequence '${seq?.name}' -> $outcome ($detail)")
        StatsLog.event(
            "sequence_result", "group" to group.name, "sequence" to seq?.name, "outcome" to outcome.name,
            "detail" to detail, "stage" to (new.session?.stageIndex ?: old.session?.stageIndex),
        )
    }

    // ---------- debug helpers (logged, so "cheating" is visible in the stats) ----------

    fun debugAddUsage(groupId: String, minutes: Int) {
        val group = ConfigStore.current.group(groupId) ?: return
        val now = System.currentTimeMillis()
        val cfg = ConfigStore.current
        val rolled = Engine.rollDay(cfg, state(groupId), now, zone).first
        val new = rolled.copy(debugExtraUsageMs = rolled.debugExtraUsageMs + minutes * 60_000L)
        StateStore.put(groupId, new, persist = true)
        StatsLog.event("debug_add_usage", "group" to group.name, "minutes" to minutes)
    }

    fun debugClear(groupId: String) {
        val group = ConfigStore.current.group(groupId) ?: return
        val cleared = state(groupId).copy(session = null, blockedUntilMs = 0L, launcherRemainingMs = -1L, interruption = null)
        StateStore.put(groupId, cleared, persist = true)
        StatsLog.event("debug_clear_state", "group" to group.name)
    }
}
