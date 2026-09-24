package dev.martin.friction.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Runtime state of one App Group (persisted), and the pure rules that drive it.
// No Android code here, so it's unit-testable anywhere.
// ---------------------------------------------------------------------------

data class SessionState(
    val startedAtMs: Long,
    /** Long.MAX_VALUE = unlimited. */
    val endsAtMs: Long,
    val stageIndex: Int,
)

data class ActiveInterruption(
    /** Unique per launch, so the overlay knows when a *new* interruption started. */
    val instance: Long,
    val variantId: String,
    val typeId: String,
    val params: Resolved,
    /** In-app time left; pauses while the user is outside the group's apps. */
    val remainingMs: Long,
)

data class GroupState(
    /** Start of the "day" this state belongs to (last reset time). */
    val dayStartMs: Long = 0L,
    val session: SessionState? = null,
    /** Real-time block after a failed sequence. Survives the daily reset. */
    val blockedUntilMs: Long = 0L,
    /** In-app time until the launcher fires the next interruption; -1 = not armed. */
    val launcherRemainingMs: Long = -1L,
    val interruption: ActiveInterruption? = null,
    /** Debug only: fake extra usage for testing stages. Cleared at the daily reset. */
    val debugExtraUsageMs: Long = 0L,
)

enum class Trigger {
    /** User just brought an app of the group to the foreground. */
    OPEN,
    /** Periodic check while the user stays inside the app. */
    TICK,
}

sealed class Verdict {
    /** Let the user use the app. */
    object Allow : Verdict() { override fun toString() = "Allow" }
    /** Show the black screen with a timer. */
    data class Block(val untilMs: Long) : Verdict()
    /** Start this task sequence. */
    data class RunSequence(val sequenceId: String, val stageIndex: Int) : Verdict()
    /** The session ended while the user was in the app: send them home. */
    data class Kick(val reason: String) : Verdict()
}

data class Evaluation(val state: GroupState, val verdict: Verdict, val notes: List<String>)

data class TickResult(val state: GroupState, val note: String?)

object Engine {

    /** Most recent occurrence of the reset time that is <= now. */
    fun dayStartMs(nowMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var start = now.toLocalDate().atTime(hour, minute).atZone(zone)
        if (start.isAfter(now)) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }

    /** Which stage the group is in after [usageMs] of usage today. The last stage never ends. */
    fun stageIndexFor(usageMs: Long, stages: List<Stage>): Int {
        var acc = 0L
        for (i in stages.indices) {
            if (i == stages.lastIndex) return i
            acc += (stages[i].durationMinutes * 60_000).toLong().coerceAtLeast(0)
            if (usageMs < acc) return i
        }
        return 0
    }

    fun stagesOf(config: Config, group: AppGroup): List<Stage> =
        config.stageSequence(group.stageSequenceId)?.stages.orEmpty()

    /** A stage is free when it has no task sequence, or one with no (valid) steps. */
    fun isFree(config: Config, stage: Stage): Boolean {
        val seq = config.taskSequence(stage.taskSequenceId) ?: return true
        return seq.steps.none { config.taskVariant(it) != null }
    }

    /** Applies the daily reset if the day changed: sessions end, blocks stay. */
    fun rollDay(config: Config, state: GroupState, nowMs: Long, zone: ZoneId): Pair<GroupState, Boolean> {
        val dayStart = dayStartMs(nowMs, config.resetHour, config.resetMinute, zone)
        if (state.dayStartMs == dayStart) return state to false
        return state.copy(
            dayStartMs = dayStart, session = null, launcherRemainingMs = -1L, interruption = null, debugExtraUsageMs = 0L,
        ) to true
    }

    /**
     * Decide what happens when an app of [group] is (or stays) in the foreground.
     * [usageMs] is the group's usage since the last reset.
     */
    fun evaluate(
        config: Config,
        group: AppGroup,
        state: GroupState,
        usageMs: Long,
        nowMs: Long,
        trigger: Trigger,
        zone: ZoneId,
        rng: Random,
    ): Evaluation {
        val notes = mutableListOf<String>()
        var s = state
        val (rolled, newDay) = rollDay(config, s, nowMs, zone)
        s = rolled
        if (newDay) notes += "new day (reset) -> session cleared"

        val stages = stagesOf(config, group)
        if (stages.isEmpty()) {
            notes += "group has no stages -> allow"
            return Evaluation(s, Verdict.Allow, notes)
        }
        val idx = stageIndexFor(usageMs, stages)
        val stage = stages[idx]
        val free = isFree(config, stage)

        val session = s.session
        if (session != null) {
            val endReason = when {
                session.stageIndex != idx -> "stage changed ${session.stageIndex + 1} -> ${idx + 1}"
                nowMs >= session.endsAtMs -> "session time is up"
                else -> null
            }
            if (endReason == null) return Evaluation(s, Verdict.Allow, notes)

            notes += "session ended: $endReason"
            s = s.copy(session = null, launcherRemainingMs = -1L, interruption = null)
            if (free) {
                s = startSession(config, s, stage, idx, nowMs, rng)
                notes += "stage ${idx + 1} is free -> new session"
                return Evaluation(s, Verdict.Allow, notes)
            }
            if (trigger == Trigger.TICK) return Evaluation(s, Verdict.Kick(endReason), notes)
            // Trigger.OPEN: the session ended while they were away; fall through to the gate.
        }

        if (nowMs < s.blockedUntilMs) return Evaluation(s, Verdict.Block(s.blockedUntilMs), notes)

        if (free) {
            s = startSession(config, s, stage, idx, nowMs, rng)
            notes += "stage ${idx + 1} is free -> session started"
            return Evaluation(s, Verdict.Allow, notes)
        }
        return Evaluation(s, Verdict.RunSequence(stage.taskSequenceId!!, idx), notes)
    }

    fun startSession(config: Config, state: GroupState, stage: Stage, stageIndex: Int, nowMs: Long, rng: Random): GroupState {
        val len = stage.session.lengthMinutes
        val ends = if (len == null || len <= 0.0) Long.MAX_VALUE else nowMs + (len * 60_000).toLong()
        return state.copy(
            session = SessionState(nowMs, ends, stageIndex),
            launcherRemainingMs = armLauncher(config, stage.session, rng),
            interruption = null,
        )
    }

    /** Called when the user passed the whole task sequence. */
    fun onSequencePassed(
        config: Config, group: AppGroup, state: GroupState, usageMs: Long, nowMs: Long, zone: ZoneId, rng: Random,
    ): GroupState {
        val s = rollDay(config, state, nowMs, zone).first
        val stages = stagesOf(config, group)
        if (stages.isEmpty()) return s
        val idx = stageIndexFor(usageMs, stages)
        return startSession(config, s, stages[idx], idx, nowMs, rng)
    }

    /** Called when the user failed: the group is blocked for [blockMinutes] of real time. */
    fun onSequenceFailed(state: GroupState, nowMs: Long, blockMinutes: Double): GroupState =
        state.copy(
            session = null,
            blockedUntilMs = maxOf(state.blockedUntilMs, nowMs + (blockMinutes * 60_000).toLong()),
            launcherRemainingMs = -1L,
            interruption = null,
        )

    private fun armLauncher(config: Config, rules: SessionRules, rng: Random): Long {
        if (rules.interruptionIds.none { config.interruptionVariant(it) != null }) return -1L
        val lo = minOf(rules.launcherMinSeconds, rules.launcherMaxSeconds).coerceAtLeast(1.0)
        val hi = maxOf(rules.launcherMinSeconds, rules.launcherMaxSeconds).coerceAtLeast(lo)
        val sec = if (hi == lo) lo else lo + rng.nextDouble() * (hi - lo)
        return (sec * 1000).toLong()
    }

    /**
     * Advance interruptions by [elapsedMs] of *in-app* time (call only while the user
     * is inside one of the group's apps, so everything pauses when they leave).
     */
    fun tickInterruptions(config: Config, group: AppGroup, state: GroupState, elapsedMs: Long, rng: Random): TickResult {
        val session = state.session
            ?: return TickResult(state.copy(interruption = null, launcherRemainingMs = -1L), null)
        val rules = stagesOf(config, group).getOrNull(session.stageIndex)?.session
            ?: return TickResult(state, null)

        val active = state.interruption
        if (active != null) {
            val left = active.remainingMs - elapsedMs
            return if (left <= 0) {
                TickResult(
                    state.copy(interruption = null, launcherRemainingMs = armLauncher(config, rules, rng)),
                    "interruption ${active.variantId} finished",
                )
            } else {
                TickResult(state.copy(interruption = active.copy(remainingMs = left)), null)
            }
        }

        if (state.launcherRemainingMs < 0) return TickResult(state, null)
        val wait = state.launcherRemainingMs - elapsedMs
        if (wait > 0) return TickResult(state.copy(launcherRemainingMs = wait), null)

        val variants = rules.interruptionIds.mapNotNull { config.interruptionVariant(it) }
        if (variants.isEmpty()) return TickResult(state.copy(launcherRemainingMs = -1L), null)
        val v = variants[rng.nextInt(variants.size)]
        val type = InterruptionTypes.byId(v.typeId)
            ?: return TickResult(state.copy(launcherRemainingMs = armLauncher(config, rules, rng)), "unknown interruption type ${v.typeId}")
        val params = resolveAll(type.params, v.params, rng)
        val durationMs = ((params[InterruptionTypes.DURATION] ?: 10.0) * 1000).toLong().coerceAtLeast(500L)
        val started = ActiveInterruption(rng.nextLong(), v.id, v.typeId, params, durationMs)
        return TickResult(
            state.copy(launcherRemainingMs = -1L, interruption = started),
            "interruption '${v.name}' launched for ${durationMs}ms (hidden) params=$params",
        )
    }
}
