package com.heath.haunch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.heath.haunch.audio.Bed
import com.heath.haunch.haptic.Pulse
import com.heath.haunch.persist.Save

class GameActivity : AppCompatActivity() {
    private lateinit var game: GameView
    private lateinit var bed: Bed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        preferHighRefresh()

        val save = Save(this)
        bed = Bed()
        val pulse = Pulse(this)
        pulse.enabled = save.haptics
        bed.muted = !save.sound
        game = GameView(this, save, bed, pulse)
        game.onExit = { finish() }
        setContentView(game)
        ViewCompat.setOnApplyWindowInsetsListener(game) { _, insets ->
            val cut = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            game.setSafe(cut.top, cut.bottom, cut.left, cut.right)
            insets
        }
        onBackPressedDispatcher.addCallback(this) { game.onBack() }
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        preferHighRefresh()
        bed.start()
        game.setLoop(true)
    }

    override fun onPause() {
        game.onHostPause()
        game.setLoop(false)
        bed.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (::bed.isInitialized) bed.release()
        super.onDestroy()
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun preferHighRefresh() {
        val panel = display ?: return
        val best = panel.supportedModes.maxByOrNull { it.refreshRate } ?: return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        window.attributes = attrs
    }
}
