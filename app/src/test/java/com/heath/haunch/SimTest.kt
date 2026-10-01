package com.heath.haunch

import com.heath.haunch.sim.Balance
import com.heath.haunch.sim.Finger
import com.heath.haunch.sim.Lesson
import com.heath.haunch.sim.Rank
import com.heath.haunch.sim.Seeds
import com.heath.haunch.sim.Side
import com.heath.haunch.sim.Sim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimTest {
    @Test
    fun idleRunEndsAndNamesAnArch() {
        val sim = Sim(OPENING)
        stepUntil(sim, { Finger.NONE }, 90.0)
        assertTrue(sim.over)
        assertTrue(sim.collapsedCount >= 2)
        assertTrue(sim.sentence.contains("arch"))
        assertTrue(sim.sentence.contains("haunch"))
    }

    @Test
    fun campingOneArchLetsTheOtherTwoFall() {
        val sim = Sim(OPENING)
        stepUntil(sim, { campMiddle(it) }, 120.0)
        assertTrue(sim.over)
        assertTrue(sim.arch(0).collapsed)
        assertTrue(sim.arch(2).collapsed)
    }

    @Test
    fun wrongSideCollapsesSoonerThanWaiting() {
        val idle = Sim(OPENING)
        stepUntil(idle, { Finger.NONE }, 30.0) { !it.arch(1).collapseAt.isNaN() }
        val wrong = Sim(OPENING)
        stepUntil(wrong, {
            Finger(1, it.arch(1).side.opposite())
        }, 30.0) { !it.arch(1).collapseAt.isNaN() }
        assertFalse(idle.arch(1).collapseAt.isNaN())
        assertTrue(wrong.arch(1).collapseAt < idle.arch(1).collapseAt)
    }

    @Test
    fun cleanBraceSurvivesTheOpeningMinute() {
        val sim = Sim(OPENING)
        val bot = CleanBot()
        stepUntil(sim, { bot.finger(it) }, 61.0)
        assertFalse(
            "died at ${sim.time}s mortar ${sim.mortar} dry ${sim.dry} purity ${sim.purity}: ${sim.sentence}",
            sim.over,
        )
        assertTrue("time ${sim.time}", sim.time >= 60.0)
        assertTrue("purity ${sim.purity}", sim.purity > 0.5)
    }

    @Test
    fun mashingDiesEarlierThanTheCleanScript() {
        val mash = Sim(OPENING)
        stepUntil(mash, { mashWrong(it) }, 90.0)
        val clean = Sim(OPENING)
        val bot = CleanBot()
        stepUntil(clean, { bot.finger(it) }, 60.0)
        assertTrue(mash.over)
        assertTrue(mash.time < clean.time)
    }

    @Test
    fun sameSeedAndFingerMatch() {
        val a = Sim(OPENING)
        val b = Sim(OPENING)
        val botA = CleanBot()
        val botB = CleanBot()
        val steps = (20.0 / Balance.STEP).toInt()
        repeat(steps) {
            val fingerA = botA.finger(a)
            val fingerB = botB.finger(b)
            a.step(fingerA)
            b.step(fingerB)
            assertEquals(a.fingerprint(), b.fingerprint())
        }
    }

    @Test
    fun sideDoesNotFlipAboveTheCap() {
        val sim = Sim(OPENING)
        sim.debugFreeze(1, 0.85)
        val side = sim.arch(1).side
        repeat((5.0 / Balance.STEP).toInt()) { sim.step(Finger.NONE) }
        assertEquals(side, sim.arch(1).side)
        assertTrue(sim.arch(1).stress > Balance.FLIP_STRESS_CAP)

        sim.debugFreeze(1, 0.20)
        val period = sim.arch(1).period
        val steps = (period / Balance.STEP).toInt() + 3
        repeat(steps) { sim.step(Finger.NONE) }
        assertNotEquals(side, sim.arch(1).side)
        assertTrue(sim.arch(1).stress <= Balance.FLIP_STRESS_CAP)
    }

    @Test
    fun tonightSeedDependsOnlyOnTheDate() {
        val night = Seeds.daily("2026-09-30")
        assertEquals(night, Seeds.daily("2026-09-30"))
        assertNotEquals(night, Seeds.daily("2026-10-01"))
        val a = Sim(night)
        val b = Sim(Seeds.daily("2026-09-30"))
        repeat(400) {
            a.step(Finger.NONE)
            b.step(Finger.NONE)
        }
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun lieLeavesAReadableTruthWindow() {
        assertFalse(Balance.lieAllowed(0.97, 0.38))
        assertTrue(Balance.lieAllowed(0.55, 0.20))
        for (t in listOf(75.0, 90.0, 150.0, 240.0, 300.0)) {
            val row = Balance.band(t)
            assertTrue("t=$t", row.liar)
            assertTrue("t=$t", Balance.lieAllowed(row.crackAt, row.rise))
        }
        assertFalse(Balance.band(60.0).liar)
        assertTrue(Balance.band(40.0).pins)
        assertFalse(Balance.band(40.0).falsePins)
        assertTrue(Balance.band(90.0).falsePins)
    }

    @Test
    fun ranksGateTimeAndPurity() {
        assertEquals(Rank.DUST, Rank.of(10.0, 1.0))
        assertEquals(Rank.APPRENTICE, Rank.of(30.0, 0.1))
        assertEquals(Rank.APPRENTICE, Rank.of(80.0, 0.40))
        assertEquals(Rank.JOURNEYMAN, Rank.of(70.0, 0.50))
        assertEquals(Rank.SETTER, Rank.of(110.0, 0.58))
        assertEquals(Rank.CROWN, Rank.of(300.0, 0.75))
        assertEquals(Rank.VOUSSOIR, Rank.of(300.0, 0.70))
    }

    @Test
    fun teachNeedsFourLateBracesThenARehearsal() {
        val sim = Sim(OPENING, Lesson.TEACH)
        val bot = CleanBot()
        stepUntil(sim, { bot.finger(it) }, 40.0) { it.lesson == Lesson.REHEARSAL }
        assertEquals(Lesson.REHEARSAL, sim.lesson)
        assertTrue(sim.teachCleans >= Balance.TEACH_CLEANS)
        assertFalse(sim.over)
        stepUntil(sim, { bot.finger(it) }, 12.0)
        assertTrue(sim.lessonFinished)
        assertFalse(sim.lessonFailed)
    }

    private fun campMiddle(sim: Sim): Finger {
        val arch = sim.arch(1)
        if (!sim.isLive(1) || arch.collapsed) return Finger.NONE
        return Finger(1, arch.side)
    }

    private fun mashWrong(sim: Sim): Finger {
        var best = -1
        var bestStress = -1.0
        for (i in 0..2) {
            val arch = sim.arch(i)
            if (!sim.isLive(i) || arch.collapsed) continue
            if (arch.stress > bestStress) {
                best = i
                bestStress = arch.stress
            }
        }
        if (best < 0) return Finger(1, Side.LEFT)
        return Finger(best, sim.arch(best).side.opposite())
    }

    private fun stepUntil(
        sim: Sim,
        finger: (Sim) -> Finger,
        seconds: Double,
        done: (Sim) -> Boolean = { false },
    ) {
        val limit = (seconds / Balance.STEP).toInt()
        var n = 0
        while (n < limit && !sim.over && !sim.lessonFinished && !done(sim)) {
            sim.step(finger(sim))
            n += 1
        }
    }

    private class CleanBot {
        private var hold: Int? = null

        fun finger(sim: Sim): Finger {
            val crack = sim.band().crackAt
            val engage = (crack + 0.06).coerceAtMost(0.84)
            val release = (engage - 0.08).coerceAtLeast(0.05)
            val held = hold
            if (held != null) {
                val arch = sim.arch(held)
                if (sim.isLive(held) && !arch.collapsed && !sim.dry && arch.stress > release) {
                    return Finger(held, arch.side)
                }
                hold = null
            }
            if (sim.dry) return Finger.NONE
            var best = -1
            var bestStress = engage - 1e-6
            for (i in 0..2) {
                val arch = sim.arch(i)
                if (!sim.isLive(i) || arch.collapsed) continue
                val urgent = arch.stress >= 0.82
                if (sim.mortar < 0.12 && !urgent) continue
                if (arch.stress >= engage && arch.stress > bestStress) {
                    best = i
                    bestStress = arch.stress
                }
            }
            if (best < 0) return Finger.NONE
            hold = best
            return Finger(best, sim.arch(best).side)
        }
    }

    companion object {
        private const val OPENING = 1L
    }
}
