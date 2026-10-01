package com.heath.haunch.sim

import java.util.ArrayDeque
import kotlin.random.Random

class Arch(val index: Int) {
    var stress: Double = 0.0
    var side: Side = Side.LEFT
    var clock: Double = 0.0
    var period: Double = 3.0
    var collapsed: Boolean = false
    var collapseAt: Double = Double.NaN
    var sideAtCollapse: Side = Side.LEFT
    var lieLeft: Double = 0.0
    var wasCracked: Boolean = false
    var frozen: Boolean = false

    fun shownSide(): Side = if (lieLeft > 0.0) side.opposite() else side

    fun arm(seed: Long) {
        stress = 0.0
        side = Seeds.initialSide(seed, index)
        clock = 0.0
        period = Seeds.period(seed, index)
        collapsed = false
        collapseAt = Double.NaN
        sideAtCollapse = side
        lieLeft = 0.0
        wasCracked = false
        frozen = false
    }
}

class Sample(
    val stress: FloatArray,
    val shownLeft: BooleanArray,
    val cracked: BooleanArray,
    val collapsed: BooleanArray,
    val live: BooleanArray,
    val fx: Float,
    val fy: Float,
    val down: Boolean,
)

private class Pin(
    val arch: Int,
    val falsePin: Boolean,
    var age: Double = 0.0,
    var resolved: Boolean = false,
)

class Sim(
    val seed: Long,
    lesson: Lesson = Lesson.DONE,
) {
    val arches: Array<Arch> = Array(3) { Arch(it) }
    var lesson: Lesson = lesson
        private set
    var time: Double = 0.0
        private set
    var mortar: Double = 1.0
        private set
    var dry: Boolean = false
        private set
    var clean: Int = 0
        private set
    var waste: Int = 0
        private set
    var collapsedCount: Int = 0
        private set
    var over: Boolean = false
        private set
    var sentence: String = ""
        private set
    var teachCleans: Int = 0
        private set
    var lessonFinished: Boolean = false
        private set
    var lessonFailed: Boolean = false
        private set

    var edgeClean: Boolean = false
    var edgeWaste: Boolean = false
    val edgeCrack = BooleanArray(3)

    var rewind: List<Sample> = emptyList()
        private set

    val purity: Double
        get() = if (clean + waste == 0) 1.0 else clean.toDouble() / (clean + waste)

    val score: Double
        get() = time * (0.55 + 0.45 * purity) * 100.0

    val rank: Rank
        get() = Rank.of(time, purity)

    private val rng = Random(seed)
    private val history = ArrayDeque<Sample>(360)
    private var pin: Pin? = null
    private var pinCooldown = 12.0
    private var pinsArmed = false
    private var lastKey: BraceKey? = null
    private var deathArch = 1
    private var deathFinger: Finger = Finger.NONE

    init {
        resetStone(keepLesson = true)
    }

    fun arch(index: Int): Arch = arches[index]

    fun band(): Balance.Band = when (lesson) {
        Lesson.TEACH -> Balance.band(0.0)
        Lesson.REHEARSAL -> Balance.band(20.0)
        Lesson.DONE -> Balance.band(time)
    }

    fun isLive(index: Int): Boolean = when (lesson) {
        Lesson.TEACH -> index == 1
        Lesson.REHEARSAL -> index == 1 || index == 0
        Lesson.DONE -> when {
            time < 15.0 -> index == 1
            time < 40.0 -> index == 1 || index == 0
            else -> true
        }
    }

    fun activePin(): Triple<Int, Double, Boolean>? {
        val current = pin ?: return null
        if (current.resolved && current.age >= Balance.PIN_FALL) return null
        return Triple(current.arch, current.age / Balance.PIN_FALL, current.falsePin)
    }

    fun beginFrame() {
        edgeClean = false
        edgeWaste = false
        edgeCrack.fill(false)
    }

    /** Hold stress still so a test can prove the side clock waits. */
    fun debugFreeze(index: Int, stress: Double) {
        arches[index].frozen = true
        arches[index].stress = stress
    }

    fun restartRehearsal() {
        lesson = Lesson.REHEARSAL
        lessonFailed = false
        lessonFinished = false
        resetStone(keepLesson = true)
    }

    fun step(finger: Finger) {
        if (over || lessonFinished) return
        val dt = Balance.STEP
        time += dt
        val row = band()

        val truth = Array(3) { arches[it].side }
        val effective = braceWorks(finger)
        noteBrace(finger, effective, truth, row)

        for (i in 0..2) {
            val arch = arches[i]
            val live = isLive(i)
            if (!live || arch.collapsed) continue
            if (arch.frozen) continue
            val onThis = effective && finger.arch == i && finger.side != null
            val correct = onThis && finger.side == truth[i]
            val wrong = onThis && finger.side != truth[i]
            if (correct) {
                arch.stress -= Balance.RECOVER * dt
            } else {
                var rise = row.rise
                if (wrong) rise *= Balance.WRONG_MULT
                arch.stress += rise * dt
            }
            if (lesson == Lesson.TEACH) {
                arch.stress = arch.stress.coerceAtMost(Balance.TEACH_CLAMP)
            }
            arch.stress = arch.stress.coerceIn(0.0, 1.0)
        }

        spendMortar(effective, row, dt)
        tickLies(row, dt)
        tickPins(finger, row, dt)
        collapseAny(finger)
        tickSides(dt)
        record(finger)
        finishLesson()
    }

    fun fingerprint(): String {
        val sb = StringBuilder()
        sb.append(time).append('|').append(mortar).append('|').append(dry).append('|')
        for (arch in arches) {
            sb.append(arch.stress)
                .append(',')
                .append(arch.side)
                .append(',')
                .append(arch.collapsed)
                .append(',')
                .append(arch.lieLeft)
                .append(';')
        }
        val current = pin
        if (current != null) {
            sb.append("p").append(current.arch).append(':').append(current.age).append(':').append(current.falsePin)
        }
        sb.append('|').append(clean).append(',').append(waste)
        return sb.toString()
    }

    private fun braceWorks(finger: Finger): Boolean {
        if (finger.arch == null || finger.side == null) return false
        if (dry || mortar <= 0.0) return false
        val arch = arches[finger.arch]
        return isLive(finger.arch) && !arch.collapsed
    }

    private fun noteBrace(finger: Finger, effective: Boolean, truth: Array<Side>, row: Balance.Band) {
        val key = if (effective && finger.arch != null && finger.side != null) {
            BraceKey(finger.arch, finger.side, truth[finger.arch])
        } else {
            null
        }
        if (key == lastKey) return
        lastKey = key
        if (key == null) return
        val arch = arches[key.arch]
        val correct = key.hand == key.truth
        val cracked = arch.stress >= row.crackAt
        if (correct && cracked) {
            clean += 1
            edgeClean = true
            if (lesson == Lesson.TEACH) teachCleans += 1
        } else {
            waste += 1
            edgeWaste = true
        }
    }

    private fun spendMortar(effective: Boolean, row: Balance.Band, dt: Double) {
        if (effective) {
            mortar -= row.drain * dt
            if (mortar <= 0.0) {
                mortar = 0.0
                dry = true
                lastKey = null
            }
        } else {
            mortar = (mortar + row.regen * dt).coerceAtMost(1.0)
            if (dry && mortar >= Balance.DRY_UNLOCK) dry = false
        }
    }

    private fun tickLies(row: Balance.Band, dt: Double) {
        for (i in 0..2) {
            val arch = arches[i]
            if (!isLive(i) || arch.collapsed) {
                arch.lieLeft = 0.0
                continue
            }
            val cracked = arch.stress >= row.crackAt
            if (cracked && !arch.wasCracked) {
                edgeCrack[i] = true
                val mayLie = row.liar && lesson == Lesson.DONE && Balance.lieAllowed(arch.stress, row.rise)
                if (mayLie && rng.nextDouble() < 0.5) {
                    arch.lieLeft = Balance.LIE_SECONDS
                }
            }
            if (!cracked) arch.wasCracked = false else arch.wasCracked = true
            if (arch.lieLeft > 0.0) arch.lieLeft = (arch.lieLeft - dt).coerceAtLeast(0.0)
        }
    }

    private fun tickPins(finger: Finger, row: Balance.Band, dt: Double) {
        if (lesson != Lesson.DONE || !row.pins) return
        if (!pinsArmed) {
            pinsArmed = true
            pinCooldown = 11.0 + rng.nextDouble() * 5.0
        }
        val current = pin
        if (current == null) {
            pinCooldown -= dt
            if (pinCooldown <= 0.0) spawnPin(row)
            return
        }
        current.age += dt
        val inWindow = current.age >= Balance.PIN_FALL * 0.75 && current.age <= Balance.PIN_FALL
        val onArch = finger.arch == current.arch && finger.side != null
        if (inWindow && onArch && !current.resolved && isLive(current.arch) && !arches[current.arch].collapsed) {
            current.resolved = true
            val arch = arches[current.arch]
            if (current.falsePin) {
                arch.stress = (arch.stress + Balance.FALSE_PIN_HURT).coerceAtMost(1.0)
            } else {
                arch.stress = (arch.stress - Balance.PIN_HEAL).coerceAtLeast(0.0)
                mortar = (mortar + Balance.PIN_MORTAR).coerceAtMost(1.0)
                if (dry && mortar >= Balance.DRY_UNLOCK) dry = false
            }
        }
        if (current.age >= Balance.PIN_FALL) {
            pin = null
            pinCooldown = 11.0 + rng.nextDouble() * 5.0
        }
    }

    private fun spawnPin(row: Balance.Band) {
        val living = (0..2).filter { isLive(it) && !arches[it].collapsed }
        if (living.isEmpty()) {
            pinCooldown = 2.0
            return
        }
        val arch = living[rng.nextInt(living.size)]
        val falsePin = row.falsePins && rng.nextDouble() < 0.34
        pin = Pin(arch, falsePin)
    }

    private fun collapseAny(finger: Finger) {
        for (i in 0..2) {
            val arch = arches[i]
            if (arch.frozen || arch.collapsed || !isLive(i)) continue
            if (arch.stress < 1.0) continue
            arch.collapsed = true
            arch.collapseAt = time
            arch.sideAtCollapse = arch.side
            arch.stress = 1.0
            collapsedCount += 1
            if (collapsedCount >= 2 && !over) {
                over = true
                deathArch = i
                deathFinger = finger
                sentence = buildSentence()
                rewind = history.toList()
            }
        }
    }

    private fun tickSides(dt: Double) {
        for (i in 0..2) {
            val arch = arches[i]
            if (!isLive(i) || arch.collapsed) continue
            // Above the cap the clock waits. The side the player can see stays put.
            if (arch.stress > Balance.FLIP_STRESS_CAP) continue
            arch.clock += dt
            while (arch.clock >= arch.period) {
                arch.clock -= arch.period
                arch.side = arch.side.opposite()
            }
        }
    }

    private fun record(finger: Finger) {
        if (lesson == Lesson.TEACH) return
        val row = band()
        val sample = Sample(
            stress = FloatArray(3) { arches[it].stress.toFloat() },
            shownLeft = BooleanArray(3) { arches[it].shownSide() == Side.LEFT },
            cracked = BooleanArray(3) { arches[it].stress >= row.crackAt },
            collapsed = BooleanArray(3) { arches[it].collapsed },
            live = BooleanArray(3) { isLive(it) },
            fx = finger.x,
            fy = finger.y,
            down = finger.arch != null,
        )
        history.addLast(sample)
        while (history.size > 300) history.removeFirst()
    }

    private fun finishLesson() {
        if (lesson == Lesson.TEACH && teachCleans >= Balance.TEACH_CLEANS) {
            lesson = Lesson.REHEARSAL
            resetStone(keepLesson = true)
            return
        }
        if (lesson == Lesson.REHEARSAL && over) {
            lessonFailed = true
            return
        }
        if (lesson == Lesson.REHEARSAL && time >= Balance.REHEARSAL_SECONDS && !over) {
            lessonFinished = true
        }
    }

    private fun resetStone(keepLesson: Boolean) {
        time = 0.0
        mortar = 1.0
        dry = false
        clean = 0
        waste = 0
        collapsedCount = 0
        over = false
        sentence = ""
        lastKey = null
        pin = null
        pinsArmed = false
        pinCooldown = 12.0
        history.clear()
        rewind = emptyList()
        if (!keepLesson) lesson = Lesson.DONE
        for (arch in arches) arch.arm(seed)
    }

    private fun buildSentence(): String {
        val names = arrayOf("upper arch", "middle arch", "lower arch")
        val fallen = arches[deathArch]
        val haunch = if (fallen.sideAtCollapse == Side.LEFT) "Left" else "Right"
        val heldArch = deathFinger.arch
        val hand = when (heldArch) {
            null -> "Your hand was off the stone."
            deathArch -> {
                val held = if (deathFinger.side == Side.LEFT) "left" else "right"
                "You were on its $held haunch."
            }
            else -> "You were holding the ${names[heldArch]}."
        }
        return "$haunch haunch of the ${names[deathArch]} collapsed. $hand"
    }

    private data class BraceKey(val arch: Int, val hand: Side, val truth: Side)
}
