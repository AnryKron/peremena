package com.andrew.peremena.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.SignalEvent
import com.andrew.peremena.model.SignalKind
import com.andrew.peremena.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Планирует будильники на ближайшие ~36 часов.
 *
 * Почему setAlarmClock: обычные «точные» будильники в режиме Doze система
 * разрешает не чаще раза в ~9 минут на приложение, а у нас сигналы идут
 * через 2 минуты (за 2 мин до конца пары и в момент звонка). setAlarmClock
 * этим ограничением не режется. Побочный эффект: в шторке виден значок
 * будильника и время ближайшего сигнала.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val ACTION_SIGNAL = "com.andrew.peremena.SIGNAL"
    const val ACTION_REFRESH = "com.andrew.peremena.REFRESH"
    const val EXTRA_KIND = "kind"
    const val EXTRA_LESSON = "lesson"
    const val EXTRA_TASKS = "tasks"
    const val EXTRA_AT = "at"
    const val EXTRA_TEST = "test"

    private const val MAX_ALARMS = 64
    private const val RC_REFRESH = 90_000
    private const val RC_TEST = 90_001
    private const val PREFS = "alarms"
    private const val KEY_COUNT = "count"

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    @Synchronized
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 1. Снять всё, что было запланировано раньше.
        val prev = prefs.getInt(KEY_COUNT, MAX_ALARMS)
        for (i in 0 until maxOf(prev, 0)) {
            signalIntent(ctx, i, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }

        // 2. Посчитать и поставить новые.
        val data = Repo.state.value
        val now = LocalDateTime.now()
        val events = Plan.events(data.schedule, data.tasks, now.plusSeconds(20), now.plusHours(36)).take(MAX_ALARMS)
        events.forEachIndexed { i, e -> setAlarm(ctx, am, signalIntent(ctx, i, e, 0)!!, e.at) }
        prefs.edit().putInt(KEY_COUNT, events.size).apply()

        // 3. Контрольный пересчёт: завтра в 00:05 (на случай дней без сигналов).
        val refreshAt = now.toLocalDate().plusDays(1).atTime(0, 5)
        val refresh = PendingIntent.getBroadcast(
            ctx, RC_REFRESH,
            Intent(ctx, SignalReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(refreshAt), refresh)

        Log.i(TAG, "Запланировано сигналов: ${events.size}, первый: ${events.firstOrNull()?.at}")
    }

    /** Проверочный сигнал через минуту. */
    fun scheduleTest(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = LocalDateTime.now().plusMinutes(1)
        val e = SignalEvent(at, SignalKind.BELL, 0)
        val intent = buildIntent(ctx, e).putExtra(EXTRA_TEST, true)
        val pi = PendingIntent.getBroadcast(ctx, RC_TEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(ctx, am, pi, at)
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, pi: PendingIntent, at: LocalDateTime) {
        val t = millis(at)
        try {
            if (canExact(ctx)) {
                val show = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
            } else {
                // Без разрешения на точные будильники — неточно (может опоздать на минуты).
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Нет разрешения на точный будильник", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    private fun buildIntent(ctx: Context, e: SignalEvent) =
        Intent(ctx, SignalReceiver::class.java)
            .setAction(ACTION_SIGNAL)
            .putExtra(EXTRA_KIND, e.kind.name)
            .putExtra(EXTRA_LESSON, e.lessonN)
            .putExtra(EXTRA_TASKS, e.taskIds.toTypedArray())
            .putExtra(EXTRA_AT, millis(e.at))

    /** e == null — только для поиска/отмены (extras не участвуют в сравнении интентов). */
    private fun signalIntent(ctx: Context, i: Int, e: SignalEvent?, extraFlags: Int): PendingIntent? {
        val intent = if (e != null) buildIntent(ctx, e)
        else Intent(ctx, SignalReceiver::class.java).setAction(ACTION_SIGNAL)
        return PendingIntent.getBroadcast(
            ctx, i, intent,
            extraFlags or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun millis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
