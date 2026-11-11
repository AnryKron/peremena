package com.andrew.peremena.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

// Модели данных. Формат JSON описан в ТЗ, раздел 5 (см. data/Codec.kt).
// Здесь — только чистый Kotlin + java.time, без Android, чтобы логику
// можно было проверять обычными юнит-тестами.

data class Lesson(val n: Int, val start: LocalTime, val end: LocalTime)

data class Template(val id: String, val name: String, val lessons: List<Lesson>)

/** Замена расписания на конкретную дату. templateId == null — «без звонков». */
data class DayOverride(val date: LocalDate, val templateId: String?)

data class Signals(
    /** За сколько минут до конца пары сигналить (0 — не сигналить). */
    val beforeEndMin: Int = 2,
    /** Сигнал в момент звонка на перемену. */
    val atBell: Boolean = true,
    /** За сколько минут до начала пары сигналить (0 — не сигналить). */
    val beforeStartMin: Int = 0,
    /** Короткий звук вместе с вибрацией. */
    val sound: Boolean = false,
    /** Вибрировать/звучать даже в беззвучном режиме (через канал будильника). */
    val ignoreSilent: Boolean = true,
)

data class Schedule(
    val templates: List<Template>,
    val week: Map<DayOfWeek, String?>,
    val overrides: List<DayOverride>,
    val signals: Signals,
    /** true, пока пользователь не отредактировал пример расписания. */
    val isExample: Boolean = false,
    val timezone: String = "Europe/Moscow",
)

sealed interface Due {
    /** На перемене после пары [afterLesson] в день [date]. */
    data class Break(val date: LocalDate, val afterLesson: Int) : Due
    /** В точное время. */
    data class At(val at: LocalDateTime) : Due
    /** Без срока. */
    data object None : Due
}

enum class Status { OPEN, DONE, CANCELLED }

data class Task(
    val id: String,
    val title: String,
    val room: String = "",
    val notes: String = "",
    val due: Due = Due.None,
    val remindBeforeMin: Int = 0,
    /** Повторять каждый учебный день (в MVP — единственный вид повтора). */
    val repeatSchoolDays: Boolean = false,
    val status: Status = Status.OPEN,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val doneAt: OffsetDateTime? = null,
    val source: String = "reminder",
    val externalId: String? = null,
)

data class AppData(
    val schedule: Schedule,
    val tasks: List<Task>,
    val recentRooms: List<String> = emptyList(),
) {
    companion object {
        /** Пример расписания. Время пар условное — его нужно заменить реальным. */
        fun default(): AppData {
            fun l(n: Int, s: String, e: String) = Lesson(n, LocalTime.parse(s), LocalTime.parse(e))
            val normal = Template(
                "normal", "Обычный",
                listOf(
                    l(1, "09:00", "10:30"),
                    l(2, "10:40", "12:10"),
                    l(3, "12:40", "14:10"),
                    l(4, "14:20", "15:50"),
                    l(5, "16:00", "17:30"),
                ),
            )
            val short = Template(
                "short", "Сокращённый",
                listOf(
                    l(1, "09:00", "10:00"),
                    l(2, "10:10", "11:10"),
                    l(3, "11:30", "12:30"),
                    l(4, "12:40", "13:40"),
                ),
            )
            val week = DayOfWeek.values().associateWith { d ->
                if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) null else "normal"
            }
            return AppData(
                schedule = Schedule(
                    templates = listOf(normal, short),
                    week = week,
                    overrides = emptyList(),
                    signals = Signals(),
                    isExample = true,
                ),
                tasks = emptyList(),
            )
        }
    }
}
