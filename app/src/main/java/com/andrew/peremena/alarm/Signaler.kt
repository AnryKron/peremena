package com.andrew.peremena.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.andrew.peremena.model.Signals

/**
 * Вибрация и короткий звук.
 * ignoreSilent = true: используем «будильниковый» тип звука/вибрации,
 * который Android не глушит в беззвучном режиме (если «Не беспокоить»
 * разрешает будильники — по умолчанию разрешает).
 */
object Signaler {
    /** Звонок: два длинных импульса. */
    private val BELL = longArrayOf(0, 600, 250, 600)
    /** Задача: три коротких. */
    private val TASK = longArrayOf(0, 200, 150, 200, 150, 200)
    /** Задача + звонок одновременно. */
    private val BOTH = longArrayOf(0, 600, 250, 600, 400, 200, 150, 200, 150, 200)

    enum class Pattern { BELL, TASK, BOTH }

    fun alert(ctx: Context, pattern: Pattern, signals: Signals, onDone: () -> Unit) {
        val timings = when (pattern) {
            Pattern.BELL -> BELL
            Pattern.TASK -> TASK
            Pattern.BOTH -> BOTH
        }
        vibrate(ctx, timings, signals.ignoreSilent)

        var ringtone: Ringtone? = null
        if (signals.sound) {
            ringtone = runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(ctx, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(if (signals.ignoreSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            }.getOrNull()
        }
        // Даём вибрации и звуку доиграть, затем отпускаем приёмник (лимит ~10 с).
        val total = timings.sum().coerceAtMost(4000) + 500
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { ringtone?.stop() }
            onDone()
        }, if (signals.sound) maxOf(total, 3500) else total)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context, timings: LongArray, ignoreSilent: Boolean) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(timings, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val usage = if (ignoreSilent) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
        } else {
            val attrs = AudioAttributes.Builder()
                .setUsage(if (ignoreSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, attrs)
        }
    }
}
