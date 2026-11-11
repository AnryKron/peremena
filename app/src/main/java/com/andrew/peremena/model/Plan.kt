package com.andrew.peremena.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Что сейчас происходит по расписанию. */
sealed interface DayStatus {
    /** Сегодня звонков нет (выходной или «без звонков»). */
    data object NoBells : DayStatus
    data class BeforeFirst(val first: Lesson) : DayStatus
    data class InLesson(val lesson: Lesson, val next: Lesson?) : DayStatus
    data class InBreak(val after: Lesson, val next: Lesson) : DayStatus
    data class AfterLast(val last: Lesson) : DayStatus
}

enum class SignalKind {
    /** За N минут до конца пары. */
    END_SOON,
    /** Звонок на перемену (или конец занятий после последней пары). */
    BELL,
    /** За N минут до начала пары. */
    START_SOON,
    /** Только задачи (время не совпало ни с каким звонком). */
    TASKS,
}

data class SignalEvent(
    val at: LocalDateTime,
    val kind: SignalKind,
    /** Номер пары, к которой относится сигнал (0 для TASKS). */
    val lessonN: Int,
    val taskIds: List<String> = emptyList(),
)

/**
 * Вся логика расписания: какие пары сегодня, где перемены, когда сигналить.
 * Без Android — проверяется юнит-тестами (app/src/test).
 */
object Plan {

    /** Шаблон на дату: замена на дату важнее расписания недели. */
    fun templateFor(s: Schedule, date: LocalDate): Template? {
        val ov = s.overrides.lastOrNull { it.date == date }
        val id = if (ov != null) ov.templateId else s.week[date.dayOfWeek]
        return id?.let { tid -> s.templates.firstOrNull { it.id == tid } }
    }

    fun lessonsFor(s: Schedule, date: LocalDate): List<Lesson> =
        templateFor(s, date)?.lessons
            ?.filter { it.end.isAfter(it.start) }
            ?.sortedBy { it.start }
            .orEmpty()

    fun isSchoolDay(s: Schedule, date: LocalDate) = lessonsFor(s, date).isNotEmpty()

    fun lessonEnd(s: Schedule, date: LocalDate, n: Int): LocalDateTime? =
        lessonsFor(s, date).firstOrNull { it.n == n }?.let { date.atTime(it.end) }

    fun status(s: Schedule, now: LocalDateTime): DayStatus {
        val lessons = lessonsFor(s, now.toLocalDate())
        if (lessons.isEmpty()) return DayStatus.NoBells
        val t = now.toLocalTime()
        if (t.isBefore(lessons.first().start)) return DayStatus.BeforeFirst(lessons.first())
        for ((i, l) in lessons.withIndex()) {
            val next = lessons.getOrNull(i + 1)
            if (!t.isBefore(l.start) && t.isBefore(l.end)) return DayStatus.InLesson(l, next)
            if (next != null && !t.isBefore(l.end) && t.isBefore(next.start)) return DayStatus.InBreak(l, next)
        }
        return DayStatus.AfterLast(lessons.last())
    }

    /**
     * Ближайшая перемена после момента [now]: конец первой пары, которая
     * заканчивается позже, чем через [minAheadMin] минут. Ищем до 30 дней вперёд.
     */
    fun nextBreak(s: Schedule, now: LocalDateTime, minAheadMin: Long = 0): Due.Break? {
        val limit = now.plusMinutes(minAheadMin)
        var d = now.toLocalDate()
        repeat(31) {
            for (l in lessonsFor(s, d)) {
                if (d.atTime(l.end).isAfter(limit)) return Due.Break(d, l.n)
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** Когда задача «наступает» (без учёта напоминания заранее). */
    fun dueTime(s: Schedule, due: Due): LocalDateTime? = when (due) {
        is Due.Break -> lessonEnd(s, due.date, due.afterLesson)
        is Due.At -> due.at
        Due.None -> null
    }

    fun alarmTime(s: Schedule, t: Task): LocalDateTime? =
        dueTime(s, t.due)?.minusMinutes(t.remindBeforeMin.toLong())

    /** Следующее появление повторяющейся задачи после того, как её выполнили. */
    fun nextOccurrence(s: Schedule, due: Due, after: LocalDate): Due? {
        var d = after.plusDays(1)
        repeat(60) {
            when (due) {
                is Due.Break -> if (lessonEnd(s, d, due.afterLesson) != null) return Due.Break(d, due.afterLesson)
                is Due.At -> if (isSchoolDay(s, d)) return Due.At(d.atTime(due.at.toLocalTime()))
                Due.None -> return null
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** Все сигналы в интервале [from, to). Совпавшие по минуте сливаются в один. */
    fun events(s: Schedule, tasks: List<Task>, from: LocalDateTime, to: LocalDateTime): List<SignalEvent> {
        val raw = mutableListOf<SignalEvent>()
        val sig = s.signals
        var d = from.toLocalDate()
        while (!d.isAfter(to.toLocalDate())) {
            for (l in lessonsFor(s, d)) {
                val end = d.atTime(l.end)
                val start = d.atTime(l.start)
                if (sig.beforeEndMin > 0) raw += SignalEvent(end.minusMinutes(sig.beforeEndMin.toLong()), SignalKind.END_SOON, l.n)
                if (sig.atBell) raw += SignalEvent(end, SignalKind.BELL, l.n)
                if (sig.beforeStartMin > 0) raw += SignalEvent(start.minusMinutes(sig.beforeStartMin.toLong()), SignalKind.START_SOON, l.n)
            }
            d = d.plusDays(1)
        }
        for (t in tasks) {
            if (t.status != Status.OPEN) continue
            val at = alarmTime(s, t) ?: continue
            raw += SignalEvent(at, SignalKind.TASKS, 0, listOf(t.id))
        }
        val inRange = raw.filter { !it.at.isBefore(from) && it.at.isBefore(to) }

        // Слияние событий одной минуты: звонок важнее задачи, задачи присоединяются.
        return inRange
            .groupBy { it.at.withSecond(0).withNano(0) }
            .map { (at, group) ->
                val main = group.filter { it.kind != SignalKind.TASKS }
                    .minByOrNull { priority(it.kind) }
                val ids = group.flatMap { it.taskIds }.distinct()
                (main ?: SignalEvent(at, SignalKind.TASKS, 0)).copy(at = at, taskIds = ids)
            }
            .sortedBy { it.at }
    }

    private fun priority(k: SignalKind) = when (k) {
        SignalKind.BELL -> 0
        SignalKind.END_SOON -> 1
        SignalKind.START_SOON -> 2
        SignalKind.TASKS -> 3
    }

    /** Разбор строк вида «09:00-10:30» (по одной паре на строку). */
    fun parseLessons(text: String): Result<List<Lesson>> {
        val out = mutableListOf<Lesson>()
        val re = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*[-–—]\s*(\d{1,2})[:.](\d{2})\s*$""")
        text.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            val m = re.matchEntire(line)
                ?: return Result.failure(IllegalArgumentException("Строка ${i + 1}: «${line.trim()}» — нужно как 09:00-10:30"))
            val (h1, m1, h2, m2) = m.destructured
            val start = runCatching { LocalTime.of(h1.toInt(), m1.toInt()) }.getOrNull()
            val end = runCatching { LocalTime.of(h2.toInt(), m2.toInt()) }.getOrNull()
            if (start == null || end == null) return Result.failure(IllegalArgumentException("Строка ${i + 1}: неверное время"))
            if (!end.isAfter(start)) return Result.failure(IllegalArgumentException("Строка ${i + 1}: конец раньше начала"))
            out += Lesson(out.size + 1, start, end)
        }
        for (i in 1 until out.size) {
            if (out[i].start.isBefore(out[i - 1].end)) {
                return Result.failure(IllegalArgumentException("Пара ${i + 1} начинается раньше, чем кончается пара $i"))
            }
        }
        return Result.success(out)
    }

    fun formatLessons(lessons: List<Lesson>): String =
        lessons.joinToString("\n") { "${hhmm(it.start)}-${hhmm(it.end)}" }

    fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
}
