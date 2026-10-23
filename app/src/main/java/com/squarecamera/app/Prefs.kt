package com.squarecamera.app

import android.content.Context
import androidx.camera.core.ImageCapture

/** Photo shape, as width ÷ height when the phone is upright. */
enum class Ratio(val label: String, val portrait: Float) {
    SQUARE("1:1", 1f),
    FULL("4:3", 3f / 4f),
    WIDE("16:9", 9f / 16f);

    fun next(): Ratio = entries[(ordinal + 1) % entries.size]
}

/** The camera's settings, remembered between launches. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)

    var ratio: Ratio
        get() = runCatching { Ratio.valueOf(prefs.getString("ratio", null) ?: "") }.getOrDefault(Ratio.SQUARE)
        set(v) = prefs.edit().putString("ratio", v.name).apply()

    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(v) = prefs.edit().putBoolean("grid", v).apply()

    /** ImageCapture.FLASH_MODE_OFF / _AUTO / _ON. */
    var flash: Int
        get() = prefs.getInt("flash", ImageCapture.FLASH_MODE_OFF)
        set(v) = prefs.edit().putInt("flash", v).apply()

    /** Self-timer in seconds: 0, 3 or 10. */
    var timer: Int
        get() = prefs.getInt("timer", 0)
        set(v) = prefs.edit().putInt("timer", v).apply()
}
