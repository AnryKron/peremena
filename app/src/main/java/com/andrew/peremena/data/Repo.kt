package com.andrew.peremena.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.andrew.peremena.alarm.AlarmScheduler
import com.andrew.peremena.model.AppData
import com.andrew.peremena.model.DayOverride
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.Schedule
import com.andrew.peremena.model.Status
import com.andrew.peremena.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Единственное хранилище данных приложения.
 * Файл data.json пишется через AtomicFile: при сбое во время записи
 * остаётся предыдущая целая версия (урок из Sticky Timer Notes).
 */
object Repo {
    private const val TAG = "Repo"
    private lateinit var file: AtomicFile
    private lateinit var appContext: Context
    private val _state = MutableStateFlow(AppData.default())
    val state: StateFlow<AppData> get() = _state

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        appContext = context.applicationContext
        file = AtomicFile(File(appContext.filesDir, "data.json"))
        _state.value = load()
    }

    private fun load(): AppData {
        if (!file.baseFile.exists()) return AppData.default()
        return try {
            Codec.decodeApp(String(file.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            // Файл испорчен — откладываем его, чтобы не затереть, и стартуем с примера.
            Log.e(TAG, "data.json не читается", e)
            runCatching {
                file.baseFile.copyTo(File(appContext.filesDir, "data.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            }
            AppData.default()
        }
    }

    private fun save(d: AppData) {
        val out = file.startWrite()
        try {
            out.write(Codec.encodeApp(d).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Не удалось сохранить", e)
        }
    }

    /** Любое изменение данных: сохранить и пересчитать будильники. */
    @Synchronized
    fun update(transform: (AppData) -> AppData) {
        val next = transform(_state.value)
        if (next == _state.value) return
        save(next)
        _state.value = next
        AlarmScheduler.reschedule(appContext)
    }

    // ---------------- задачи ----------------

    fun addOrUpdateTask(
        id: String?,
        title: String,
        room: String,
        due: Due,
        repeatSchoolDays: Boolean,
    ) = update { d ->
        val now = OffsetDateTime.now()
        val r = room.trim()
        val rooms = if (r.isEmpty()) d.recentRooms else (listOf(r) + d.recentRooms.filter { it != r }).take(12)
        val existing = id?.let { i -> d.tasks.firstOrNull { it.id == i } }
        val task = existing?.copy(
            title = title.trim(), room = r, due = due, repeatSchoolDays = repeatSchoolDays, updatedAt = now,
        ) ?: Task(
            id = UUID.randomUUID().toString(), title = title.trim(), room = r, due = due,
            repeatSchoolDays = repeatSchoolDays, createdAt = now, updatedAt = now,
        )
        val tasks = if (existing != null) d.tasks.map { if (it.id == task.id) task else it } else d.tasks + task
        d.copy(tasks = tasks, recentRooms = rooms)
    }

    fun completeTask(id: String) = update { d ->
        val now = OffsetDateTime.now()
        d.copy(tasks = d.tasks.map { t ->
            if (t.id != id || t.status != Status.OPEN) return@map t
            if (t.repeatSchoolDays) {
                val from = Plan.dueTime(d.schedule, t.due)?.toLocalDate() ?: LocalDate.now()
                val base = maxOf(from, LocalDate.now())
                val next = Plan.nextOccurrence(d.schedule, t.due, base)
                if (next != null) return@map t.copy(due = next, updatedAt = now, doneAt = now)
            }
            t.copy(status = Status.DONE, doneAt = now, updatedAt = now)
        })
    }

    fun reopenTask(id: String) = update { d ->
        d.copy(tasks = d.tasks.map {
            if (it.id == id) it.copy(status = Status.OPEN, doneAt = null, updatedAt = OffsetDateTime.now()) else it
        })
    }

    /** Перенести на следующую перемену (не раньше чем через минуту). */
    fun postponeTask(id: String) = update { d ->
        val next = Plan.nextBreak(d.schedule, LocalDateTime.now(), minAheadMin = 1) ?: return@update d
        d.copy(tasks = d.tasks.map {
            if (it.id == id) it.copy(due = next, updatedAt = OffsetDateTime.now()) else it
        })
    }

    /** Удаление = пометка cancelled (чтобы при будущей синхронизации задача не «воскресла»). */
    fun deleteTask(id: String) = update { d ->
        d.copy(tasks = d.tasks.map {
            if (it.id == id) it.copy(status = Status.CANCELLED, updatedAt = OffsetDateTime.now()) else it
        })
    }

    // ---------------- расписание ----------------

    fun setSchedule(s: Schedule) = update { it.copy(schedule = s) }

    /** Режим на сегодня: templateId, null — «без звонков»; reset=true — вернуть по неделе. */
    fun setTodayMode(templateId: String?, reset: Boolean = false) = update { d ->
        val today = LocalDate.now()
        val rest = d.schedule.overrides.filter { it.date != today && !it.date.isBefore(today.minusDays(7)) }
        val ov = if (reset) rest else rest + DayOverride(today, templateId)
        d.copy(schedule = d.schedule.copy(overrides = ov))
    }

    /** Чистим старые выполненные/удалённые задачи (старше 30 дней). */
    fun housekeeping() = update { d ->
        val limit = OffsetDateTime.now().minusDays(30)
        d.copy(tasks = d.tasks.filter { it.status == Status.OPEN || it.updatedAt.isAfter(limit) })
    }
}
