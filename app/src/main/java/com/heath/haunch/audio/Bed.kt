package com.heath.haunch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Three tones a fourth apart, one per arch, plus a short scrape when a brace is wrong.
 * Synthesized so the game ships with no audio files.
 */
class Bed {
    private val rate = 22050
    private val track: AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(min.coerceAtLeast(rate / 4) * 2)
            .build()
    } catch (_: RuntimeException) {
        null
    }

    @Volatile var level0: Float = 0f
    @Volatile var level1: Float = 0f
    @Volatile var level2: Float = 0f
    @Volatile var scrape: Float = 0f
    @Volatile var muted: Boolean = false

    private val freqs = doubleArrayOf(220.0, 293.33, 391.11)
    private val phase = DoubleArray(3)
    private var noise = 0x1234567
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        val audio = track ?: return
        if (running) return
        running = true
        audio.play()
        thread = Thread({
            val buffer = ShortArray(512)
            while (running) {
                val gain = if (muted) 0f else 0.22f
                for (i in buffer.indices) {
                    var sample = 0.0
                    val levels = floatArrayOf(level0, level1, level2)
                    for (n in 0..2) {
                        phase[n] += 2.0 * Math.PI * freqs[n] / rate
                        if (phase[n] > Math.PI * 2) phase[n] -= Math.PI * 2
                        val amp = (levels[n].coerceIn(0f, 1f) * gain).toDouble()
                        sample += kotlin.math.sin(phase[n]) * amp
                    }
                    val grit = scrape.coerceIn(0f, 1f)
                    if (grit > 0.001f) {
                        noise = noise * 1103515245 + 12345
                        val white = ((noise ushr 16) and 0x7fff) / 32767.0 - 0.5
                        sample = sample * (1.0 - grit) + white * grit * 0.35
                        scrape = grit * 0.9992f
                    }
                    buffer[i] = (sample.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                }
                audio.write(buffer, 0, buffer.size)
            }
        }, "haunch-bed").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(400)
        thread = null
        track?.pause()
        track?.flush()
    }

    fun release() {
        stop()
        track?.release()
    }
}
