package com.heath.haunch

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.heath.haunch.audio.Bed
import com.heath.haunch.haptic.Pulse
import com.heath.haunch.persist.Save
import com.heath.haunch.sim.Balance
import com.heath.haunch.sim.Finger
import com.heath.haunch.sim.Lesson
import com.heath.haunch.sim.Sample
import com.heath.haunch.sim.Seeds
import com.heath.haunch.sim.Side
import com.heath.haunch.sim.Sim
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

class GameView(
    context: Context,
    private val save: Save,
    private val bed: Bed,
    private val pulse: Pulse,
) : View(context) {

    var onExit: () -> Unit = {}

    private enum class Screen { TITLE, PLAY, PAUSE, REPLAY, RESULT }
    private enum class Mode { ENDLESS, TONIGHT }

    private var screen = Screen.TITLE
    private var mode = Mode.ENDLESS
    private var sim: Sim? = null
    private var teaching = false
    private var finger = Finger.NONE
    private var accumulator = 0.0
    private var replayClock = 0.0
    private var committed = false
    private var kept: Save.Kept? = null
    private var debug = false
    private var banner = ""
    private var bannerUntil = 0L
    private var pop = 0f
    private var hitFlash = 0f
    private var shake = 0f
    private var looping = false
    private var lastNanos = 0L
    private var pressed: String? = null
    private var rankDownAt = 0L

    private var safeTop = 0
    private var safeBottom = 0
    private var safeLeft = 0
    private var safeRight = 0

    private val archRects = Array(3) { RectF() }
    private val hits = ArrayList<Hit>(8)
    private val round = Path()
    private val opening = Path()
    private val crack = Path()

    private val serif = Typeface.create("serif", Typeface.NORMAL)
    private val title = textPaint(42f, 0xFFF4EBD8.toInt(), true)
    private val body = textPaint(18f, 0xFFE7DCC8.toInt(), false)
    private val small = textPaint(13f, 0xFFB7AB9A.toInt(), false)
    private val stone = fill(0xFF2C3344.toInt())
    private val asleep = fill(0xFF1A1E28.toInt())
    private val course = fill(0xFF232836.toInt())
    private val light = fill(0xFFE7A15A.toInt())
    private val fissure = stroke(0xFFF3E6D2.toInt(), 3f)
    private val mortarPaint = fill(0xFFC4783A.toInt())
    private val trough = fill(0xFF1A140F.toInt())
    private val post = fill(0xFF3A312A.toInt())
    private val ink = fill(0xCC0C0E14.toInt())
    private val line = stroke(0xFFE7A15A.toInt(), 2f)
    private val ghost = fill(0x66E7A15A.toInt())
    private val hot = fill(0xFFE7A15A.toInt())
    private val flash = fill(0x66E7A15A.toInt())
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shade: LinearGradient? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!looping) return
            if (lastNanos != 0L) {
                val dt = ((nanos - lastNanos) / 1_000_000_000.0).coerceIn(0.0, 0.05)
                tick(dt)
            }
            lastNanos = nanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        keepScreenOn = true
        isClickable = true
        contentDescription = "Haunch"
    }

    fun setSafe(top: Int, bottom: Int, left: Int, right: Int) {
        safeTop = top
        safeBottom = bottom
        safeLeft = left
        safeRight = right
        invalidate()
    }

    fun setLoop(on: Boolean) {
        if (on == looping) return
        looping = on
        if (on) {
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    fun onHostPause() {
        if (screen == Screen.PLAY) {
            screen = Screen.PAUSE
            finger = Finger.NONE
        }
    }

    fun onBack() {
        when (screen) {
            Screen.PLAY -> {
                screen = Screen.PAUSE
                finger = Finger.NONE
            }
            Screen.REPLAY -> {
                screen = Screen.RESULT
                commitScore()
            }
            Screen.PAUSE, Screen.RESULT -> {
                screen = Screen.TITLE
                sim = null
                finger = Finger.NONE
            }
            Screen.TITLE -> onExit()
        }
        invalidate()
    }

    private fun start(next: Mode) {
        mode = next
        committed = false
        kept = null
        accumulator = 0.0
        finger = Finger.NONE
        if (!save.tutorialDone) {
            sim = Sim(TEACH_SEED, Lesson.TEACH)
            teaching = true
            banner = "Hold the bright side."
            bannerUntil = SystemClock.uptimeMillis() + 60_000L
        } else {
            beginScored()
        }
        screen = Screen.PLAY
    }

    private fun beginScored() {
        val seed = if (mode == Mode.TONIGHT) {
            Seeds.daily(LocalDate.now().toString())
        } else {
            Random.nextLong()
        }
        sim = Sim(seed, Lesson.DONE)
        teaching = false
        committed = false
        kept = null
        accumulator = 0.0
        finger = Finger.NONE
        banner = "Hold the bright side."
        bannerUntil = SystemClock.uptimeMillis() + 4_000L
    }

    private fun tick(dt: Double) {
        val running = sim
        if (screen == Screen.REPLAY && running != null) {
            replayClock += dt
            if (replayClock >= 2.5) {
                screen = Screen.RESULT
                commitScore()
            }
            silence()
            return
        }
        if (screen != Screen.PLAY || running == null) {
            silence()
            return
        }
        running.beginFrame()
        accumulator += dt
        var steps = 0
        while (accumulator >= Balance.STEP && steps < 8 && !running.over && !running.lessonFinished) {
            running.step(finger)
            accumulator -= Balance.STEP
            steps += 1
        }
        if (steps == 8) accumulator = 0.0
        react(running)
        if (running.lessonFinished && teaching) {
            save.tutorialDone = true
            beginScored()
            banner = "Hold the bright side."
            bannerUntil = SystemClock.uptimeMillis() + 3_000L
            return
        }
        pop = (pop - (dt * 2.8)).toFloat().coerceAtLeast(0f)
        hitFlash = (hitFlash - (dt * 2.2)).toFloat().coerceAtLeast(0f)
        shake = (shake - (dt * 4.0)).toFloat().coerceAtLeast(0f)
        if (running.over) {
            finger = Finger.NONE
            screen = Screen.RESULT
            commitScore()
        }
    }

    private fun react(running: Sim) {
        if (running.edgeClean) {
            pulse.thud()
            pop = 1f
            hitFlash = 1f
            shake = 1f
            bed.knock = 1f
        }
        if (running.edgeWaste) {
            pulse.buzzWrong()
            bed.scrape = 1f
        }
        for (i in 0..2) if (running.edgeCrack[i]) pulse.tick(i)
        if (save.sound && screen == Screen.PLAY) {
            bed.level0 = if (running.isLive(0)) running.arch(0).stress.toFloat() * 0.35f else 0f
            bed.level1 = if (running.isLive(1)) running.arch(1).stress.toFloat() * 0.35f else 0f
            bed.level2 = if (running.isLive(2)) running.arch(2).stress.toFloat() * 0.35f else 0f
        } else {
            silence()
        }
    }

    private fun silence() {
        bed.level0 = 0f
        bed.level1 = 0f
        bed.level2 = 0f
    }

    private fun commitScore() {
        if (committed) return
        committed = true
        val running = sim ?: return
        if (running.lessonFailed || running.lesson != Lesson.DONE) return
        kept = save.record(
            date = LocalDate.now().toString(),
            tonight = mode == Mode.TONIGHT,
            time = running.time,
            purity = running.purity,
            score = running.score,
            rank = running.rank,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF1C1914.toInt(), 0xFF10141C.toInt(), 0xFF0C0E14.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        shadePaint.shader = shade
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        val running = sim
        val d = resources.displayMetrics.density
        if (shake > 0f) {
            val wobble = kotlin.math.sin(SystemClock.uptimeMillis() / 18.0).toFloat() * shake * 7f * d
            canvas.translate(wobble, 0f)
        }
        if (running != null && screen != Screen.TITLE) {
            layoutArches()
            drawScaffold(canvas)
            val ghost = ghostSample(running)
            for (i in 0..2) drawArch(canvas, i, running, ghost)
            if (screen == Screen.PLAY) drawPin(canvas, running)
            if (screen == Screen.REPLAY) drawTrail(canvas, running)
            drawMortar(canvas, running)
            drawHud(canvas, running)
            if (finger.arch != null && screen == Screen.PLAY) drawThumb(canvas)
        }
        when (screen) {
            Screen.TITLE -> drawTitle(canvas)
            Screen.PAUSE -> drawPause(canvas)
            Screen.RESULT -> drawResult(canvas)
            Screen.PLAY, Screen.REPLAY -> Unit
        }
        if (hitFlash > 0f) {
            val alpha = (hitFlash * 110f).toInt().coerceIn(0, 140)
            flash.color = (alpha shl 24) or 0x00F3E6D2
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flash)
        }
    }

    private fun ghostSample(running: Sim): Sample? {
        if (screen != Screen.REPLAY) return null
        val samples = running.rewind
        if (samples.isEmpty()) return null
        val u = (replayClock / 2.5).coerceIn(0.0, 0.999)
        return samples[(u * (samples.size - 1)).toInt()]
    }

    private fun layoutArches() {
        val d = resources.displayMetrics.density
        val left = safeLeft + 28f * d
        val right = width - safeRight - 28f * d
        val top = safeTop + 78f * d
        val bottom = height - safeBottom - 96f * d
        val gap = 14f * d
        val band = (bottom - top - gap * 2f) / 3f
        for (i in 0..2) {
            val y = top + i * (band + gap)
            archRects[i].set(left, y, right, y + band)
        }
    }

    private fun drawScaffold(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val top = archRects[0].top - 8f * d
        val bot = archRects[2].bottom + 8f * d
        canvas.drawRect(archRects[0].left - 12f * d, top, archRects[0].left - 4f * d, bot, post)
        canvas.drawRect(archRects[0].right + 4f * d, top, archRects[0].right + 12f * d, bot, post)
    }

    private fun drawArch(canvas: Canvas, index: Int, running: Sim, ghost: Sample?) {
        val rect = archRects[index]
        val arch = running.arch(index)
        val live = ghost?.live?.get(index) ?: running.isLive(index)
        val collapsed = ghost?.collapsed?.get(index) ?: arch.collapsed
        val stress = ghost?.stress?.get(index) ?: arch.stress.toFloat()
        val cracked = ghost?.cracked?.get(index) ?: (stress >= running.band().crackAt)
        val shownLeft = ghost?.shownLeft?.get(index) ?: (arch.shownSide() == Side.LEFT)
        val block = if (live && !collapsed) stone else asleep
        round.reset()
        round.addRoundRect(rect, 22f, 22f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(round)
        canvas.drawRect(rect, block)
        if (live && !collapsed) {
            var y = rect.top + 10f
            while (y < rect.bottom) {
                canvas.drawRect(rect.left, y, rect.right, y + 2f, course)
                y += 16f
            }
            val sprung = if (pop > 0f && stress < 0.85f) pop * 0.22f else 0f
            val open = (1f - stress * stress + sprung).coerceIn(0.05f, 1f)
            val cx = rect.centerX()
            val half = rect.width() * 0.38f * open
            val crown = rect.top + rect.height() * 0.22f
            val foot = rect.bottom - rect.height() * 0.14f
            opening.reset()
            opening.moveTo(cx - half, foot)
            opening.cubicTo(cx - half * 0.92f, crown, cx + half * 0.92f, crown, cx + half, foot)
            opening.close()
            val heat = (0xFF + ((1f - stress) * 40)).toInt().coerceIn(180, 255)
            light.color = (0xFF shl 24) or (heat shl 16) or (0x8A shl 8) or 0x3A
            canvas.drawPath(opening, light)
            val key = RectF(cx - 10f, rect.top + 8f, cx + 10f, rect.top + 22f)
            canvas.drawRect(key, post)
        }
        canvas.restore()
        if (live && !collapsed && stress > 0.18f) {
            val pulse = 0.62f + 0.38f * kotlin.math.sin(SystemClock.uptimeMillis() / 110.0 + index).toFloat()
            val alpha = ((70 + 170 * stress) * pulse).toInt().coerceIn(40, 230)
            hot.color = (alpha shl 24) or 0x00E7A15A
            val cx = if (shownLeft) rect.left + rect.width() * 0.22f else rect.right - rect.width() * 0.22f
            canvas.drawCircle(cx, rect.centerY(), rect.height() * (0.28f + stress * 0.24f), hot)
            hot.color = 0xFFF7E7C8.toInt()
            val tip = if (shownLeft) -1f else 1f
            crack.reset()
            crack.moveTo(cx - tip * 16f, rect.centerY() - 18f)
            crack.lineTo(cx + tip * 18f, rect.centerY())
            crack.lineTo(cx - tip * 16f, rect.centerY() + 18f)
            fissure.strokeWidth = 6f
            fissure.color = 0xFFF7E7C8.toInt()
            canvas.drawPath(crack, fissure)
        }
        if (live && !collapsed && cracked) {
            drawCrack(canvas, rect, shownLeft, index)
        }
        if (collapsed) {
            fissure.strokeWidth = 4f
            canvas.drawLine(rect.left + 18f, rect.centerY(), rect.right - 18f, rect.centerY() + 8f, fissure)
        }
    }

    private fun drawCrack(canvas: Canvas, rect: RectF, left: Boolean, index: Int) {
        val x = if (left) rect.left + rect.width() * 0.22f else rect.right - rect.width() * 0.22f
        val y0 = rect.top + rect.height() * 0.28f
        val y1 = rect.bottom - rect.height() * 0.22f
        val lean = if (left) 1f else -1f
        val jag = 6f + index * 2f
        crack.reset()
        crack.moveTo(x, y0)
        crack.lineTo(x + lean * jag, y0 + (y1 - y0) * 0.28f)
        crack.lineTo(x - lean * jag * 0.4f, y0 + (y1 - y0) * 0.55f)
        crack.lineTo(x + lean * jag * 0.8f, y1)
        fissure.strokeWidth = 3.5f
        canvas.drawPath(crack, fissure)
    }

    private fun drawPin(canvas: Canvas, running: Sim) {
        val pin = running.activePin() ?: return
        val rect = archRects[pin.first]
        val travel = pin.second.toFloat().coerceIn(0f, 1f)
        val y = rect.top + rect.height() * travel
        val flicker = pin.third && (SystemClock.uptimeMillis() / 90L) % 2L == 0L
        ghost.color = if (flicker) 0x55F3E6D2.toInt() else if (pin.third) 0xFFD64532.toInt() else 0xFFF3E6D2.toInt()
        val x = rect.centerX()
        val s = 11f
        opening.reset()
        opening.moveTo(x, y - s)
        opening.lineTo(x + s * 0.7f, y)
        opening.lineTo(x, y + s)
        opening.lineTo(x - s * 0.7f, y)
        opening.close()
        canvas.drawPath(opening, ghost)
    }

    private fun drawTrail(canvas: Canvas, running: Sim) {
        val samples = running.rewind
        if (samples.isEmpty()) return
        val u = (replayClock / 2.5).coerceIn(0.0, 0.999)
        val last = (u * (samples.size - 1)).toInt()
        ghost.color = 0x88E7A15A.toInt()
        for (i in 0..last) {
            val sample = samples[i]
            if (!sample.down) continue
            canvas.drawCircle(sample.fx * width, sample.fy * height, 7f, ghost)
        }
    }

    private fun drawThumb(canvas: Canvas) {
        ghost.color = 0xCCE7A15A.toInt()
        canvas.drawCircle(finger.x * width, finger.y * height, 28f, ghost)
    }

    private fun drawMortar(canvas: Canvas, running: Sim) {
        val d = resources.displayMetrics.density
        val rect = RectF(
            archRects[2].left,
            height - safeBottom - 64f * d,
            archRects[2].right,
            height - safeBottom - 28f * d,
        )
        canvas.drawRoundRect(rect, 10f, 10f, trough)
        val inner = RectF(rect.left + 6f, rect.top + 6f, rect.right - 6f, rect.bottom - 6f)
        val fillRight = inner.left + inner.width() * running.mortar.toFloat().coerceIn(0f, 1f)
        mortarPaint.color = if (running.dry) 0xFF6E6258.toInt() else 0xFFC4783A.toInt()
        canvas.drawRoundRect(RectF(inner.left, inner.top, fillRight, inner.bottom), 6f, 6f, mortarPaint)
        val label = if (running.dry) "Mortar dry" else "Mortar"
        small.color = 0xFFB7AB9A.toInt()
        canvas.drawText(label, rect.left, rect.top - 8f * d, small)
    }

    private fun drawHud(canvas: Canvas, running: Sim) {
        val d = resources.displayMetrics.density
        val top = safeTop + 28f * d
        title.textSize = 28f * d
        title.color = 0xFFF4EBD8.toInt()
        val rank = running.rank.label
        canvas.drawText(rank, safeLeft + 24f * d, top, title)
        val rankWidth = title.measureText(rank)
        hits.add(Hit("rank", RectF(safeLeft + 20f * d, top - 32f * d, safeLeft + 28f * d + rankWidth, top + 12f * d)))
        small.color = 0xFFE7DCC8.toInt()
        small.textAlign = Paint.Align.RIGHT
        canvas.drawText(scoreText(running.score), width - safeRight - 24f * d, top, small)
        small.textAlign = Paint.Align.LEFT
        var note = ""
        if (running.lesson == Lesson.TEACH) {
            note = "Hold the bright side  ${running.teachCleans} / ${Balance.TEACH_CLEANS}"
        } else if (running.lesson == Lesson.REHEARSAL) {
            note = "Both bright sides"
        } else if (SystemClock.uptimeMillis() < bannerUntil) {
            note = banner
        }
        if (note.isNotEmpty()) {
            small.color = 0xFFB7AB9A.toInt()
            canvas.drawText(note, safeLeft + 24f * d, top + 22f * d, small)
        }
        if (debug) {
            val row = running.band()
            val line = "t ${secs(running.time)}  m ${"%.2f".format(Locale.US, running.mortar)}  " +
                "p ${"%.2f".format(Locale.US, running.purity)}  rise ${row.rise}"
            canvas.drawText(line, safeLeft + 24f * d, top + 40f * d, small)
        }
    }

    private fun drawTitle(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        title.textSize = 54f * d
        title.textAlign = Paint.Align.CENTER
        title.color = 0xFFF4EBD8.toInt()
        canvas.drawText("Haunch", cx, height * 0.28f, title)
        body.textAlign = Paint.Align.CENTER
        body.color = 0xFFB7AB9A.toInt()
        body.textSize = 16f * d
        canvas.drawText("Three arches. One thumb.", cx, height * 0.28f + 28f * d, body)
        val y = height * 0.46f
        button(canvas, "endless", "Endless", cx, y, d)
        button(canvas, "tonight", "Tonight's arch", cx, y + 68f * d, d)
        small.textAlign = Paint.Align.CENTER
        small.color = 0xFFB7AB9A.toInt()
        small.textSize = 13f * d
        val best = if (save.hasBest()) {
            "Best ${save.bestRank.label}  ·  ${secs(save.bestTime.toDouble())}s  ·  ${save.bestScore}"
        } else {
            "No stone kept yet."
        }
        canvas.drawText(best, cx, y + 148f * d, small)
        if (save.dailyDate == LocalDate.now().toString() && save.dailyScore > 0) {
            canvas.drawText("Tonight ${save.dailyScore}", cx, y + 172f * d, small)
        }
        title.textAlign = Paint.Align.LEFT
        body.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawPause(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
        val d = resources.displayMetrics.density
        val cx = width / 2f
        title.textAlign = Paint.Align.CENTER
        title.textSize = 32f * d
        canvas.drawText("Tools down", cx, height * 0.28f, title)
        body.textAlign = Paint.Align.CENTER
        body.textSize = 15f * d
        body.color = 0xFFB7AB9A.toInt()
        canvas.drawText("The stone waits.", cx, height * 0.28f + 26f * d, body)
        val y = height * 0.42f
        button(canvas, "resume", "Resume", cx, y, d)
        val haptics = if (save.haptics) "Haptics on" else "Haptics off"
        val sound = if (save.sound) "Sound on" else "Sound off"
        button(canvas, "haptics", haptics, cx, y + 64f * d, d)
        button(canvas, "sound", sound, cx, y + 128f * d, d)
        button(canvas, "title", "Leave", cx, y + 192f * d, d)
        title.textAlign = Paint.Align.LEFT
        body.textAlign = Paint.Align.LEFT
    }

    private fun drawResult(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
        val running = sim ?: return
        val d = resources.displayMetrics.density
        val cx = width / 2f
        title.textAlign = Paint.Align.CENTER
        title.textSize = 28f * d
        val heading = when {
            running.lessonFailed -> "The rehearsal fell"
            else -> running.rank.label
        }
        canvas.drawText(heading, cx, height * 0.22f, title)
        body.textAlign = Paint.Align.CENTER
        body.textSize = 16f * d
        body.color = 0xFFE7DCC8.toInt()
        val sentence = running.sentence.ifEmpty { "The stone closed." }
        drawWrapped(canvas, sentence, cx, height * 0.22f + 36f * d, width - 64f * d, body)
        if (!running.lessonFailed && running.lesson == Lesson.DONE) {
            small.textAlign = Paint.Align.CENTER
            small.color = 0xFFB7AB9A.toInt()
            small.textSize = 14f * d
            val stats = "${secs(running.time)}s   ·   purity ${"%.2f".format(Locale.US, running.purity)}   ·   ${scoreText(running.score)}"
            canvas.drawText(stats, cx, height * 0.46f, small)
            val note = kept
            val extra = when {
                note == null -> ""
                note.newRank -> "New rank."
                note.newBest -> "New best."
                note.dailyBest -> "Tonight's best."
                else -> "Kept."
            }
            if (extra.isNotEmpty()) canvas.drawText(extra, cx, height * 0.46f + 22f * d, small)
        }
        val y = height * 0.62f
        if (running.lessonFailed) {
            button(canvas, "retry", "Rehearsal again", cx, y, d)
        } else {
            button(canvas, "again", "Again", cx, y, d)
        }
        button(canvas, "title", "Leave", cx, y + 68f * d, d)
        title.textAlign = Paint.Align.LEFT
        body.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawWrapped(canvas: Canvas, text: String, cx: Float, top: Float, width: Float, paint: Paint) {
        val words = text.split(" ")
        var line = ""
        var y = top
        for (word in words) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(trial) > width && line.isNotEmpty()) {
                canvas.drawText(line, cx, y, paint)
                y += paint.textSize * 1.35f
                line = word
            } else {
                line = trial
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line, cx, y, paint)
    }

    private fun button(canvas: Canvas, id: String, label: String, cx: Float, cy: Float, d: Float) {
        body.textSize = 18f * d
        val w = body.measureText(label).coerceAtLeast(180f * d) + 36f * d
        val h = 48f * d
        val rect = RectF(cx - w / 2f, cy, cx + w / 2f, cy + h)
        val hot = pressed == id
        line.strokeWidth = 1.5f * d
        line.color = if (hot) 0xFFF4EBD8.toInt() else 0xFFE7A15A.toInt()
        canvas.drawRoundRect(rect, 8f * d, 8f * d, line)
        body.color = 0xFFF4EBD8.toInt()
        body.textAlign = Paint.Align.CENTER
        canvas.drawText(label, cx, cy + h * 0.64f, body)
        body.textAlign = Paint.Align.LEFT
        hits.add(Hit(id, rect))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (screen == Screen.PLAY && event.pointerCount >= 2) {
                    screen = Screen.PAUSE
                    finger = Finger.NONE
                    pressed = null
                    return true
                }
                pressed = hit(event.x, event.y)
                if (pressed == "rank") rankDownAt = SystemClock.uptimeMillis()
                if (screen == Screen.PLAY && pressed == null) finger = mapFinger(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (screen == Screen.PLAY && pressed == null && event.pointerCount == 1) {
                    finger = mapFinger(event)
                }
                if (pressed == "rank" && SystemClock.uptimeMillis() - rankDownAt > 500L) {
                    debug = !debug
                    pressed = null
                }
            }
            MotionEvent.ACTION_UP -> {
                val up = hit(event.x, event.y)
                if (up != null && up == pressed && up != "rank") activate(up)
                if (screen == Screen.PLAY) finger = Finger.NONE
                pressed = null
            }
            MotionEvent.ACTION_CANCEL -> {
                finger = Finger.NONE
                pressed = null
            }
        }
        return true
    }

    private fun activate(id: String) {
        when (id) {
            "endless" -> start(Mode.ENDLESS)
            "tonight" -> start(Mode.TONIGHT)
            "resume" -> {
                screen = Screen.PLAY
                accumulator = 0.0
            }
            "haptics" -> {
                save.haptics = !save.haptics
                pulse.enabled = save.haptics
            }
            "sound" -> {
                save.sound = !save.sound
                bed.muted = !save.sound
            }
            "again" -> start(mode)
            "retry" -> {
                sim?.restartRehearsal()
                screen = Screen.PLAY
                finger = Finger.NONE
                accumulator = 0.0
                committed = false
            }
            "title" -> {
                screen = Screen.TITLE
                sim = null
                finger = Finger.NONE
            }
        }
        invalidate()
    }

    private fun mapFinger(event: MotionEvent): Finger {
        if (archRects[0].isEmpty) return Finger.NONE
        val x = event.x
        val y = event.y
        for (i in 0..2) {
            val rect = archRects[i]
            if (y < rect.top || y > rect.bottom) continue
            val t = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
            val side = when {
                t < 0.46f -> Side.LEFT
                t > 0.54f -> Side.RIGHT
                else -> null
            }
            val nx = if (width == 0) 0f else x / width
            val ny = if (height == 0) 0f else y / height
            return Finger(i, side, nx, ny)
        }
        return Finger.NONE
    }

    private fun hit(x: Float, y: Float): String? {
        for (i in hits.lastIndex downTo 0) {
            if (hits[i].rect.contains(x, y)) return hits[i].id
        }
        return null
    }

    private fun textPaint(size: Float, color: Int, titleFace: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = serif
            letterSpacing = if (titleFace) 0.04f else 0.02f
        }
    }

    private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun secs(time: Double): String = "%.1f".format(Locale.US, time)

    private fun scoreText(score: Double): String = "%,d".format(Locale.US, score.toInt())

    private data class Hit(val id: String, val rect: RectF)

    companion object {
        private const val TEACH_SEED = 7L
    }
}
