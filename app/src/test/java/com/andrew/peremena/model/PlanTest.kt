package com.andrew.peremena.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class PlanTest {
    private val data = AppData.default()
    private val s = data.schedule
    private val wed = LocalDate.of(2026, 11, 11)   // среда
    private val sat = LocalDate.of(2026, 11, 14)   // суббота
    private val stamp = OffsetDateTime.parse("2026-11-11T08:00:00+03:00")

    private fun task(id: String, due: Due, before: Int = 0, status: Status = Status.OPEN) =
        Task(id = id, title = id, due = due, remindBeforeMin = before, status = status, createdAt = stamp, updatedAt = stamp)

    @Test fun weekendHasNoBells() {
        assertEquals(DayStatus.NoBells, Plan.status(s, sat.atTime(10, 0)))
        assertTrue(Plan.lessonsFor(s, sat).isEmpty())
    }

    @Test fun statusDuringDay() {
        assertTrue(Plan.status(s, wed.atTime(8, 0)) is DayStatus.BeforeFirst)
        val inL = Plan.status(s, wed.atTime(9, 0)) as DayStatus.InLesson
        assertEquals(1, inL.lesson.n)
        val br = Plan.status(s, wed.atTime(10, 30)) as DayStatus.InBreak
        assertEquals(1, br.after.n); assertEquals(2, br.next.n)
        assertTrue(Plan.status(s, wed.atTime(17, 30)) is DayStatus.AfterLast)
    }

    @Test fun overrideWins() {
        val s2 = s.copy(overrides = listOf(DayOverride(wed, "short")))
        assertEquals(4, Plan.lessonsFor(s2, wed).size)
        val s3 = s.copy(overrides = listOf(DayOverride(wed, null)))
        assertEquals(DayStatus.NoBells, Plan.status(s3, wed.atTime(10, 0)))
        // а в пятницу — снова по неделе
        assertEquals(5, Plan.lessonsFor(s3, wed.plusDays(2)).size)
    }

    @Test fun nextBreakRollsToMonday() {
        assertEquals(Due.Break(wed, 1), Plan.nextBreak(s, wed.atTime(9, 15)))
        assertEquals(Due.Break(wed, 2), Plan.nextBreak(s, wed.atTime(10, 30)))
        // пятница после занятий -> понедельник, 1-я пара
        val fri = LocalDate.of(2026, 11, 13)
        assertEquals(Due.Break(LocalDate.of(2026, 11, 16), 1), Plan.nextBreak(s, fri.atTime(18, 0)))
    }

    @Test fun eventsAndMerge() {
        val t1 = task("a", Due.Break(wed, 1))                 // 10:30 — совпадает со звонком
        val t2 = task("b", Due.At(wed.atTime(13, 0)))          // отдельное событие
        val t3 = task("c", Due.Break(wed, 2), status = Status.DONE)
        val ev = Plan.events(s, listOf(t1, t2, t3), wed.atTime(0, 0), wed.plusDays(1).atStartOfDay())
        // 5 пар × (END_SOON + BELL) = 10, плюс задача в 13:00
        assertEquals(11, ev.size)
        val bell1 = ev.first { it.at == wed.atTime(10, 30) }
        assertEquals(SignalKind.BELL, bell1.kind)
        assertEquals(listOf("a"), bell1.taskIds)
        assertEquals(SignalKind.END_SOON, ev.first { it.at == wed.atTime(10, 28) }.kind)
        assertEquals(SignalKind.TASKS, ev.first { it.at == wed.atTime(13, 0) }.kind)
        assertTrue(ev.none { "c" in it.taskIds })
        assertEquals(ev.sortedBy { it.at }, ev)
    }

    @Test fun eventsRespectFrom() {
        val ev = Plan.events(s, emptyList(), wed.atTime(17, 29), wed.plusDays(1).atTime(9, 0))
        assertEquals(listOf(wed.atTime(17, 30)), ev.map { it.at })
    }

    @Test fun remindBefore() {
        val t = task("a", Due.Break(wed, 3), before = 5)
        assertEquals(wed.atTime(14, 5), Plan.alarmTime(s, t))
    }

    @Test fun repeatSkipsWeekend() {
        val fri = LocalDate.of(2026, 11, 13)
        assertEquals(Due.Break(LocalDate.of(2026, 11, 16), 2), Plan.nextOccurrence(s, Due.Break(fri, 2), fri))
        assertEquals(
            Due.At(LocalDateTime.of(2026, 11, 16, 8, 30)),
            Plan.nextOccurrence(s, Due.At(fri.atTime(8, 30)), fri),
        )
        assertNull(Plan.nextOccurrence(s, Due.None, fri))
    }

    @Test fun parseLessons() {
        val r = Plan.parseLessons("9:00-10:30\n 10.40 – 12.10 \n\n12:40-14:10")
        assertEquals(3, r.getOrThrow().size)
        assertEquals("09:00-10:30\n10:40-12:10\n12:40-14:10", Plan.formatLessons(r.getOrThrow()))
        assertTrue(Plan.parseLessons("09:00-08:00").isFailure)
        assertTrue(Plan.parseLessons("09:00-10:30\n10:00-11:00").isFailure)
        assertTrue(Plan.parseLessons("девять-десять").isFailure)
    }
}
