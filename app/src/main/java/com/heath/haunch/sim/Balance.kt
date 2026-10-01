package com.heath.haunch.sim

/**
 * Every difficulty number lives here. Tuning a run means editing this file
 * and re-running SimTest, which locks the shape of the curve.
 */
object Balance {
    const val STEP = 1.0 / 120.0
    // A brace has to pull stress down in a short tap. If recovery is weak, three arches
    // cannot be held without draining the mortar, and perfect play dies in the first minute.
    const val RECOVER = 1.4
    const val SNAP = 0.30
    const val SNAP_COST = 0.03
    const val WRONG_SNAP = 0.18
    const val WRONG_MULT = 1.8
    const val DRY_UNLOCK = 0.25
    /** A failing side waits while stress is above this, so a flip is never the thing that kills you. */
    const val FLIP_STRESS_CAP = 0.80
    const val LIE_SECONDS = 0.20
    const val TRUTH_FLOOR = 0.18
    const val PIN_FALL = 1.1
    const val PIN_HEAL = 0.35
    const val PIN_MORTAR = 0.20
    const val FALSE_PIN_HURT = 0.20
    const val TEACH_CLAMP = 0.92
    const val TEACH_CLEANS = 3
    const val REHEARSAL_SECONDS = 8.0

    val PERIODS = doubleArrayOf(1.25, 1.70, 2.15)

    data class Band(
        val rise: Double,
        val crackAt: Double,
        val regen: Double,
        val drain: Double,
        val pins: Boolean,
        val falsePins: Boolean,
        val liar: Boolean,
    )

    fun band(t: Double): Band {
        val row = when {
            t < 8.0 -> Band(0.42, 0.20, 0.55, 0.08, pins = false, falsePins = false, liar = false)
            t < 20.0 -> Band(0.48, 0.22, 0.50, 0.10, pins = false, falsePins = false, liar = false)
            t < 45.0 -> Band(0.52, 0.24, 0.46, 0.10, pins = true, falsePins = false, liar = false)
            t < 80.0 -> Band(0.58, 0.28, 0.40, 0.12, pins = true, falsePins = false, liar = false)
            t < 130.0 -> Band(0.66, 0.32, 0.34, 0.14, pins = true, falsePins = true, liar = false)
            else -> Band(0.78, 0.36, 0.28, 0.16, pins = true, falsePins = true, liar = false)
        }
        return row.copy(pins = t >= 16.0, falsePins = t >= 50.0, liar = t >= 36.0)
    }

    /** A lie may open only when a truthful crack still has time to be read before collapse. */
    fun lieAllowed(stress: Double, rise: Double): Boolean {
        if (rise <= 0.0) return false
        val room = (1.0 - stress) / rise
        return room >= LIE_SECONDS + TRUTH_FLOOR
    }
}

enum class Rank(val label: String, val minTime: Double, val minPurity: Double) {
    DUST("Dust", 0.0, 0.0),
    APPRENTICE("Apprentice", 30.0, 0.0),
    JOURNEYMAN("Journeyman", 70.0, 0.50),
    SETTER("Setter", 110.0, 0.58),
    KEYSTONE("Keystone", 160.0, 0.64),
    VOUSSOIR("Voussoir", 220.0, 0.70),
    CROWN("Crown", 300.0, 0.75),
    ;

    companion object {
        fun of(time: Double, purity: Double): Rank {
            var best = DUST
            for (rank in entries) {
                if (time >= rank.minTime && purity >= rank.minPurity) best = rank
            }
            return best
        }
    }
}

enum class Side {
    LEFT, RIGHT;

    fun opposite(): Side = if (this == LEFT) RIGHT else LEFT
}

enum class Lesson { TEACH, REHEARSAL, DONE }

data class Finger(
    val arch: Int?,
    val side: Side?,
    val x: Float = 0f,
    val y: Float = 0f,
) {
    companion object {
        val NONE = Finger(null, null)
    }
}
