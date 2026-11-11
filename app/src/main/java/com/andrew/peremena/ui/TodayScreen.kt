package com.andrew.peremena.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.AppData
import com.andrew.peremena.model.DayStatus
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.Schedule
import com.andrew.peremena.model.Status
import com.andrew.peremena.model.Task
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(data: AppData, modifier: Modifier, onEdit: (Task) -> Unit, onOpenSchedule: () -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000)
        }
    }
    val s = data.schedule
    val today = now.toLocalDate()
    val open = data.tasks.filter { it.status == Status.OPEN }
    val nextBreak = Plan.nextBreak(s, now)
    val lessons = Plan.lessonsFor(s, today)

    val forNext = open.filter { nextBreak != null && it.due == nextBreak }
    val todayBreaks = open.filter { (it.due as? Due.Break)?.date == today && it.due != nextBreak }
    val todayTimed = open.filter { (it.due as? Due.At)?.at?.toLocalDate() == today }.sortedBy { (it.due as Due.At).at }
    val overdue = open.filter { t ->
        val d = Plan.dueTime(s, t.due)
        d != null && d.toLocalDate().isBefore(today) ||
            (t.due is Due.Break && (t.due as Due.Break).date.isBefore(today))
    }
    val later = open.filter { t ->
        val d = when (val due = t.due) {
            is Due.Break -> due.date
            is Due.At -> due.at.toLocalDate()
            Due.None -> null
        }
        d != null && d.isAfter(today) && t.due != nextBreak
    }.sortedBy { Plan.dueTime(s, it.due) }
    val noDue = open.filter { it.due == Due.None }
    val doneToday = data.tasks.filter { it.status == Status.DONE && it.doneAt?.toLocalDate() == today }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.isExample) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сейчас стоит пример расписания", fontWeight = FontWeight.SemiBold)
                    Text("Введите реальное время пар вашего колледжа, иначе сигналы будут не в те минуты.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSchedule) { Text("Открыть расписание") }
                }
            }
        }
        item { ReliabilityCard(compact = true) }
        item { StatusCard(s, now) }
        item { DayModeRow(s, today) }

        item { Header(if (nextBreak != null) "На ближайшую перемену · ${breakLabel(s, nextBreak, today)}" else "На ближайшую перемену") }
        if (forNext.isEmpty()) item { Hint("Ничего. Нажмите «+», чтобы добавить.") }
        items(forNext, key = { it.id }) { TaskRow(s, it, today, onEdit) }

        if (overdue.isNotEmpty()) {
            item { Header("Просрочено", Color(0xFFB4562B)) }
            items(overdue, key = { "o" + it.id }) { TaskRow(s, it, today, onEdit) }
        }
        if (todayBreaks.isNotEmpty() || todayTimed.isNotEmpty()) {
            item { Header("Сегодня ещё") }
            items(todayBreaks.sortedBy { Plan.dueTime(s, it.due) }, key = { "b" + it.id }) { TaskRow(s, it, today, onEdit) }
            items(todayTimed, key = { "t" + it.id }) { TaskRow(s, it, today, onEdit) }
        }
        if (later.isNotEmpty()) {
            item { Header("Позже") }
            items(later, key = { "l" + it.id }) { TaskRow(s, it, today, onEdit) }
        }
        if (noDue.isNotEmpty()) {
            item { Header("Без срока") }
            items(noDue, key = { "n" + it.id }) { TaskRow(s, it, today, onEdit) }
        }

        item { Header("Звонки сегодня") }
        if (lessons.isEmpty()) item { Hint("Сегодня без звонков.") }
        items(lessons, key = { "p" + it.n }) { l ->
            val past = !today.atTime(l.end).isAfter(now)
            val current = !now.toLocalTime().isBefore(l.start) && now.toLocalTime().isBefore(l.end)
            Text(
                "${l.n}-я пара   ${Plan.hhmm(l.start)} – ${Plan.hhmm(l.end)}",
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    past -> Color.Gray
                    else -> Color.Unspecified
                },
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            )
        }

        if (doneToday.isNotEmpty()) {
            item { Header("Выполнено сегодня") }
            items(doneToday, key = { "d" + it.id }) { TaskRow(s, it, today, onEdit) }
        }
    }
}

@Composable
private fun StatusCard(s: Schedule, now: LocalDateTime) {
    val st = Plan.status(s, now)
    val today = now.toLocalDate()
    val (title, sub) = when (st) {
        DayStatus.NoBells -> "Сегодня без звонков" to ""
        is DayStatus.BeforeFirst -> "До начала занятий" to "${countdown(now, today.atTime(st.first.start))} · 1-я пара в ${Plan.hhmm(st.first.start)}"
        is DayStatus.InLesson ->
            if (st.next != null) "До перемены ${countdown(now, today.atTime(st.lesson.end))}" to "Идёт ${st.lesson.n}-я пара, звонок в ${Plan.hhmm(st.lesson.end)}"
            else "До конца занятий ${countdown(now, today.atTime(st.lesson.end))}" to "Идёт последняя, ${st.lesson.n}-я пара"
        is DayStatus.InBreak -> "Перемена · ещё ${countdown(now, today.atTime(st.next.start))}" to "${st.next.n}-я пара в ${Plan.hhmm(st.next.start)}"
        is DayStatus.AfterLast -> "Занятия окончены" to ""
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun DayModeRow(s: Schedule, today: LocalDate) {
    val ov = s.overrides.lastOrNull { it.date == today }
    val weekTemplate = s.week[today.dayOfWeek]?.let { id -> s.templates.firstOrNull { it.id == id } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = ov == null,
            onClick = { Repo.setTodayMode(null, reset = true) },
            label = { Text("По неделе" + (weekTemplate?.let { " (${it.name})" } ?: " (выходной)")) },
        )
        s.templates.filter { it.id != weekTemplate?.id || ov != null }.forEach { t ->
            FilterChip(
                selected = ov != null && ov.templateId == t.id,
                onClick = { Repo.setTodayMode(t.id) },
                label = { Text(t.name) },
            )
        }
        FilterChip(
            selected = ov != null && ov.templateId == null,
            onClick = { Repo.setTodayMode(null) },
            label = { Text("Без звонков") },
        )
    }
}

@Composable
private fun TaskRow(s: Schedule, t: Task, today: LocalDate, onEdit: (Task) -> Unit) {
    val done = t.status == Status.DONE
    Card(
        Modifier.fillMaxWidth().clickable { onEdit(t) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = done,
                onCheckedChange = { checked -> if (checked) Repo.completeTask(t.id) else Repo.reopenTask(t.id) },
            )
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) Color.Gray else Color.Unspecified,
                )
                val sub = listOfNotNull(
                    t.room.takeIf { it.isNotBlank() }?.let { "ауд. $it" },
                    dueLabel(s, t.due, today).ifBlank { null },
                    if (t.repeatSchoolDays) "каждый учебный день" else null,
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun Header(text: String, color: Color = Color.Unspecified) =
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Color.Gray)

private val dayFmt = DateTimeFormatter.ofPattern("EE d.MM", Locale("ru"))

fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "сегодня"
    today.plusDays(1) -> "завтра"
    else -> d.format(dayFmt)
}

fun breakLabel(s: Schedule, b: Due.Break, today: LocalDate): String {
    val end = Plan.lessonEnd(s, b.date, b.afterLesson)
    val time = end?.let { Plan.hhmm(it.toLocalTime()) } ?: "?"
    return "${dayLabel(b.date, today)}, после ${b.afterLesson}-й пары ($time)"
}

fun dueLabel(s: Schedule, due: Due, today: LocalDate): String = when (due) {
    is Due.Break -> breakLabel(s, due, today)
    is Due.At -> "${dayLabel(due.at.toLocalDate(), today)} в ${Plan.hhmm(due.at.toLocalTime())}"
    Due.None -> ""
}

private fun countdown(now: LocalDateTime, to: LocalDateTime): String {
    val sec = maxOf(0, Duration.between(now, to).seconds)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val ss = sec % 60
    return if (h > 0) "$h ч %02d мин".format(m) else "%d:%02d".format(m, ss)
}
