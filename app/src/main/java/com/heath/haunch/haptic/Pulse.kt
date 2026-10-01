package com.heath.haunch.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Short stone taps. Upper arches hit harder so the hand can tell them apart. */
class Pulse(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    var enabled: Boolean = true
    private var lastAt: Long = 0L

    fun tick(arch: Int) {
        val ms = longArrayOf(12L, 16L, 22L)
        val amp = intArrayOf(70, 140, 220)
        val i = arch.coerceIn(0, 2)
        buzz(ms[i], amp[i])
    }

    fun thud() = buzz(18L, 160)

    fun buzzWrong() = buzz(28L, 255)

    private fun buzz(ms: Long, amplitude: Int) {
        if (!enabled) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < 45L) return
        lastAt = now
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        motor.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
    }
}
