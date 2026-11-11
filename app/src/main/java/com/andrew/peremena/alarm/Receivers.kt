package com.andrew.peremena.alarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.SignalKind
import com.andrew.peremena.model.Status
import java.time.LocalDate

/** Срабатывание будильника: вибрация + уведомления, затем пересчёт следующих. */
class SignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        if (intent.action == AlarmScheduler.ACTION_REFRESH) {
            AlarmScheduler.reschedule(context)
            return
        }
        if (intent.action != AlarmScheduler.ACTION_SIGNAL) return

        val pending = goAsync()
        try {
            val data = Repo.state.value
            val kind = runCatching { SignalKind.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "") }
                .getOrDefault(SignalKind.TASKS)
            val lessonN = intent.getIntExtra(AlarmScheduler.EXTRA_LESSON, 0)
            val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
            val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis())
            val ids = intent.getStringArrayExtra(AlarmScheduler.EXTRA_TASKS)?.toSet().orEmpty()

            // Телефон был выключен и будильник «догнал» с опозданием > 10 мин — не шумим.
            if (!test && System.currentTimeMillis() - at > 10 * 60_000) {
                finish(context, pending, reschedule = true)
                return
            }

            // Актуальные задачи этого сигнала (вдруг их уже выполнили).
            val tasks = data.tasks.filter { it.id in ids && it.status == Status.OPEN }

            // Для «скоро конец пары» — подсказка, что сделать на перемене.
            val today = LocalDate.now()
            val ahead = if (kind == SignalKind.END_SOON) data.tasks.filter {
                it.status == Status.OPEN && it.due == Due.Break(today, lessonN)
            } else emptyList()

            val isBell = kind != SignalKind.TASKS
            if (!isBell && tasks.isEmpty()) {
                finish(context, pending, reschedule = true)
                return
            }
            if (isBell) Notifier.showBell(context, data.schedule, kind, lessonN, ahead, test)
            tasks.forEach { Notifier.showTask(context, data.schedule, it) }

            val pattern = when {
                isBell && tasks.isNotEmpty() -> Signaler.Pattern.BOTH
                isBell -> Signaler.Pattern.BELL
                else -> Signaler.Pattern.TASK
            }
            Signaler.alert(context, pattern, data.schedule.signals) {
                finish(context, pending, reschedule = !test)
            }
        } catch (e: Exception) {
            finish(context, pending, reschedule = true)
        }
    }

    private fun finish(ctx: Context, pending: PendingResult, reschedule: Boolean) {
        try {
            if (reschedule) AlarmScheduler.reschedule(ctx)
        } finally {
            runCatching { pending.finish() }
        }
    }
}

/** Кнопки «Выполнено» и «На след. перемену» в уведомлении о задаче. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_DONE -> Repo.completeTask(id)
            ACTION_POSTPONE -> Repo.postponeTask(id)
        }
        Notifier.cancelTask(context, id)
    }

    companion object {
        const val ACTION_DONE = "com.andrew.peremena.DONE"
        const val ACTION_POSTPONE = "com.andrew.peremena.POSTPONE"
        private const val EXTRA_ID = "id"

        fun intent(ctx: Context, action: String, taskId: String): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            (action + taskId).hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** После перезагрузки, смены времени/пояса, обновления приложения — пересчитать будильники. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        AlarmScheduler.reschedule(context)
    }
}
