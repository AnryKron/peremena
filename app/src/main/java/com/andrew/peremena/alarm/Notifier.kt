package com.andrew.peremena.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.andrew.peremena.R
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.Schedule
import com.andrew.peremena.model.SignalKind
import com.andrew.peremena.model.Task
import com.andrew.peremena.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Уведомления. Каналы без собственного звука и вибрации: вибрацию и звук
 * подаёт [Signaler] сам — так можно сигналить и в беззвучном режиме.
 */
object Notifier {
    const val CH_BELLS = "bells"
    const val CH_TASKS = "tasks"
    private const val ID_BELL = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, desc: String) = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = desc
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch(CH_BELLS, "Звонки", "Сигналы о конце пары, перемене и начале пары"))
        nm.createNotificationChannel(ch(CH_TASKS, "Задачи", "Напоминания о задачах на перемену"))
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun taskNotificationId(taskId: String) = 1000 + (taskId.hashCode() and 0x7fffffff) % 1_000_000

    fun cancelTask(ctx: Context, taskId: String) =
        NotificationManagerCompat.from(ctx).cancel(taskNotificationId(taskId))

    /** Уведомление о звонке. tasksAhead — задачи на ближайшую перемену (для подсказки). */
    fun showBell(ctx: Context, s: Schedule, kind: SignalKind, lessonN: Int, tasksAhead: List<Task>, test: Boolean) {
        val today = LocalDate.now()
        val lessons = Plan.lessonsFor(s, today)
        val lesson = lessons.firstOrNull { it.n == lessonN }
        val next = lesson?.let { l -> lessons.firstOrNull { it.start >= l.end } }

        val (title, timeoutAt) = when {
            test -> "Проверка сигнала: всё работает" to null
            kind == SignalKind.END_SOON && lesson != null ->
                "До конца ${lessonN}-й пары ${minutesLeft(today, lesson.end)} мин" to today.atTime(lesson.end)
            kind == SignalKind.BELL && next != null ->
                "Перемена до ${Plan.hhmm(next.start)}" to today.atTime(next.start)
            kind == SignalKind.BELL -> "Занятия окончены" to LocalDateTime.now().plusMinutes(15)
            kind == SignalKind.START_SOON && lesson != null ->
                "Через ${minutesLeft(today, lesson.start)} мин ${lessonN}-я пара (${Plan.hhmm(lesson.start)})" to today.atTime(lesson.start)
            else -> "Сигнал" to null
        }
        val text = if (tasksAhead.isEmpty()) null
        else "На перемене: " + tasksAhead.joinToString("; ") { label(it) }

        val b = NotificationCompat.Builder(ctx, CH_BELLS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
        if (text != null) b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        timeoutAt?.let {
            val ms = Duration.between(LocalDateTime.now(), it).toMillis()
            if (ms > 0) b.setTimeoutAfter(ms)
        }
        notify(ctx, ID_BELL, b)
    }

    fun showTask(ctx: Context, s: Schedule, t: Task) {
        val whenText = when (val d = t.due) {
            is Due.Break -> if (Plan.lessonsFor(s, d.date).lastOrNull()?.n == d.afterLesson)
                "после занятий" else "перемена после ${d.afterLesson}-й пары"
            is Due.At -> "в ${Plan.hhmm(d.at.toLocalTime())}"
            Due.None -> ""
        }
        val text = listOfNotNull(t.room.takeIf { it.isNotBlank() }?.let { "Ауд. $it" }, whenText.ifBlank { null }).joinToString(" · ")
        val b = NotificationCompat.Builder(ctx, CH_TASKS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(t.title)
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Выполнено", ActionReceiver.intent(ctx, ActionReceiver.ACTION_DONE, t.id))
            .addAction(0, "На след. перемену", ActionReceiver.intent(ctx, ActionReceiver.ACTION_POSTPONE, t.id))
        notify(ctx, taskNotificationId(t.id), b)
    }

    private fun notify(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            // Нет разрешения на уведомления (Android 13+). Вибрация всё равно сработает.
        }
    }

    private fun minutesLeft(day: LocalDate, t: java.time.LocalTime): Long =
        maxOf(0, Duration.between(LocalDateTime.now(), day.atTime(t)).plusSeconds(30).toMinutes())

    fun label(t: Task) = if (t.room.isBlank()) t.title else "${t.title} (${t.room})"
}
