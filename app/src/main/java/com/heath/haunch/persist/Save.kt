package com.heath.haunch.persist

import android.content.Context
import com.heath.haunch.sim.Rank

class Save(context: Context) {
    private val prefs = context.getSharedPreferences("haunch", Context.MODE_PRIVATE)

    var tutorialDone: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL, value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var sound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    val bestScore: Int get() = prefs.getInt(KEY_BEST_SCORE, 0)
    val bestTime: Float get() = prefs.getFloat(KEY_BEST_TIME, 0f)
    val bestPurity: Float get() = prefs.getFloat(KEY_BEST_PURITY, 0f)
    val bestRank: Rank
        get() = Rank.entries[prefs.getInt(KEY_BEST_RANK, 0).coerceIn(0, Rank.entries.lastIndex)]

    val dailyDate: String get() = prefs.getString(KEY_DAILY_DATE, "").orEmpty()
    val dailyScore: Int get() = prefs.getInt(KEY_DAILY_SCORE, 0)

    fun hasBest(): Boolean = bestTime > 0f

    data class Kept(
        val newBest: Boolean,
        val newRank: Boolean,
        val dailyBest: Boolean,
    )

    fun record(date: String, tonight: Boolean, time: Double, purity: Double, score: Double, rank: Rank): Kept {
        val edit = prefs.edit()
        val newBest = score > bestScore
        val newRank = rank.ordinal > bestRank.ordinal
        if (time > bestTime) edit.putFloat(KEY_BEST_TIME, time.toFloat())
        if (purity > bestPurity) edit.putFloat(KEY_BEST_PURITY, purity.toFloat())
        if (newBest) edit.putInt(KEY_BEST_SCORE, score.toInt())
        if (newRank) edit.putInt(KEY_BEST_RANK, rank.ordinal)
        var dailyBest = false
        if (tonight && (dailyDate != date || score > dailyScore)) {
            dailyBest = true
            edit.putString(KEY_DAILY_DATE, date)
            edit.putInt(KEY_DAILY_SCORE, score.toInt())
        }
        edit.apply()
        return Kept(newBest, newRank, dailyBest)
    }

    companion object {
        private const val KEY_TUTORIAL = "tutorial"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SOUND = "sound"
        private const val KEY_BEST_SCORE = "best_score"
        private const val KEY_BEST_TIME = "best_time"
        private const val KEY_BEST_PURITY = "best_purity"
        private const val KEY_BEST_RANK = "best_rank"
        private const val KEY_DAILY_DATE = "daily_date"
        private const val KEY_DAILY_SCORE = "daily_score"
    }
}
