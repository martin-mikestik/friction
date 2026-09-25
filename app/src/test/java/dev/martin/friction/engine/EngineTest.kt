package dev.martin.friction.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class EngineTest {
    private val zone: ZoneId = ZoneId.of("Europe/Prague")
    private val cfg = Defaults.config()
    private val group = cfg.groups.first().copy(packages = setOf("com.twitter.android"))
    private val min = 60_000L

    private fun at(h: Int, m: Int, day: Int = 24) =
        ZonedDateTime.of(2026, 9, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun eval(state: GroupState, usageMin: Long, now: Long, trigger: Trigger = Trigger.OPEN) =
        Engine.evaluate(cfg, group, state, usageMin * min, now, trigger, zone, Random(1))

    @Test
    fun dayStartUsesResetTime() {
        assertEquals(at(4, 0), Engine.dayStartMs(at(10, 0), 4, 0, zone))
        assertEquals(at(4, 0, day = 23), Engine.dayStartMs(at(3, 59), 4, 0, zone))
    }

    @Test
    fun stageIndexAndLastStageIsInfinite() {
        val stages = cfg.stageSequences.first().stages
        assertEquals(0, Engine.stageIndexFor(0, stages))
        assertEquals(0, Engine.stageIndexFor(59 * min, stages))
        assertEquals(1, Engine.stageIndexFor(60 * min, stages))
        assertEquals(1, Engine.stageIndexFor(89 * min, stages))
        assertEquals(2, Engine.stageIndexFor(90 * min, stages))
        assertEquals(2, Engine.stageIndexFor(10_000 * min, stages))
    }

    @Test
    fun freeStageAllowsAndStartsUnlimitedSession() {
        val e = eval(GroupState(), 5, at(10, 0))
        assertEquals(Verdict.Allow, e.verdict)
        assertEquals(Long.MAX_VALUE, e.state.session!!.endsAtMs)
    }

    @Test
    fun stageChangeWhileInAppKicks() {
        val s1 = eval(GroupState(), 5, at(10, 0)).state
        val e = eval(s1, 60, at(11, 0), Trigger.TICK)
        assertTrue(e.verdict is Verdict.Kick)
        assertNull(e.state.session)
        val reopen = eval(e.state, 60, at(11, 1))
        assertTrue(reopen.verdict is Verdict.RunSequence)
        assertEquals("s-burnout", (reopen.verdict as Verdict.RunSequence).sequenceId)
    }

    @Test
    fun passedSequenceGivesClockTimeSession() {
        val now = at(12, 0)
        val passed = Engine.onSequencePassed(cfg, group, GroupState(), 65 * min, now, zone, Random(1))
        assertEquals(now + 15 * min, passed.session!!.endsAtMs)
        assertEquals(Verdict.Allow, eval(passed, 70, now + 14 * min, Trigger.TICK).verdict)
        // still in stage 2 but clock time is up -> kicked while in app
        assertTrue(eval(passed, 70, now + 15 * min, Trigger.TICK).verdict is Verdict.Kick)
        // ...or straight to the sequence when opening after it expired
        assertTrue(eval(passed, 70, now + 20 * min, Trigger.OPEN).verdict is Verdict.RunSequence)
    }

    @Test
    fun failBlocksInRealTimeAndSurvivesReset() {
        val now = at(3, 55)
        val failed = Engine.onSequenceFailed(GroupState(dayStartMs = at(4, 0, day = 23)), now, 10.0)
        val e = eval(failed, 100, at(4, 1))
        assertTrue(e.verdict is Verdict.Block)
        // after the block: new day, usage 0 -> free stage
        assertEquals(Verdict.Allow, eval(failed, 0, at(4, 6)).verdict)
    }

    @Test
    fun resetEndsSession() {
        val passed = Engine.onSequencePassed(cfg, group, GroupState(), 100 * min, at(3, 50), zone, Random(1))
        val e = eval(passed, 0, at(4, 1), Trigger.TICK)
        assertTrue(e.notes.any { it.startsWith("new day") })
        assertEquals(Verdict.Allow, e.verdict) // new day starts in the free stage
        assertEquals(0, e.state.session!!.stageIndex)
    }

    @Test
    fun interruptionsLaunchPauseAndRearm() {
        val passed = Engine.onSequencePassed(cfg, group, GroupState(), 100 * min, at(12, 0), zone, Random(1))
        assertEquals(2, passed.session!!.stageIndex)
        val wait = passed.launcherRemainingMs
        assertTrue(wait in 60_000L..180_000L)

        var s = Engine.tickInterruptions(cfg, group, passed, wait - 1, Random(2)).state
        assertNull(s.interruption)
        s = Engine.tickInterruptions(cfg, group, s, 1, Random(2)).state
        val active = s.interruption!!
        assertTrue(active.remainingMs in 5_000L..30_000L)

        s = Engine.tickInterruptions(cfg, group, s, active.remainingMs - 1, Random(3)).state
        assertEquals(1L, s.interruption!!.remainingMs)
        s = Engine.tickInterruptions(cfg, group, s, 1, Random(3)).state
        assertNull(s.interruption)
        assertTrue(s.launcherRemainingMs in 60_000L..180_000L)
    }

    @Test
    fun paramRangesResolveWithinBounds() {
        val spec = TaskTypes.byId(TaskTypes.TYPING)!!.params.first { it.key == TaskTypes.LENGTH }
        repeat(200) { i ->
            val v = ParamValue.Range(14.0, 20.0).resolve(spec, Random(i))
            assertTrue(v in 14.0..20.0 && v == Math.floor(v))
        }
    }

    @Test
    fun usageCountsOnlyGroupForegroundAndStopsUnderFriction() {
        val t = at(10, 0)
        val ev = listOf(
            UsageEvent(t - 10 * min, "com.twitter.android", "Main", UsageEvent.Kind.RESUMED), // opened before reset window
            UsageEvent(t + 5 * min, "dev.martin.friction", "Gate", UsageEvent.Kind.RESUMED),  // friction on top
            UsageEvent(t + 6 * min, "dev.martin.friction", "Gate", UsageEvent.Kind.PAUSED),
            UsageEvent(t + 6 * min, "com.twitter.android", "Main", UsageEvent.Kind.RESUMED),
            UsageEvent(t + 8 * min, "x", null, UsageEvent.Kind.SCREEN_OFF),
            UsageEvent(t + 20 * min, "com.twitter.android", "Main", UsageEvent.Kind.RESUMED),
        )
        val got = UsageCalc.foregroundMs(ev, t, t + 25 * min, group.packages)
        assertEquals((5 + 2 + 5) * min, got)
    }
}
