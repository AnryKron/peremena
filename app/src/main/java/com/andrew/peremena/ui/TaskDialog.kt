package com.andrew.peremena.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.AppData
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.Task
import java.time.LocalDateTime
import java.time.LocalTime

private enum class WhenMode { NEXT, AFTER_N, TIME, NONE }

/** Новая задача (task == null) или редактирование существующей. */
@Composable
fun TaskDialog(data: AppData, task: Task?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = data.schedule
    val now = remember { LocalDateTime.now() }
    val today = now.toLocalDate()

    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var room by rememberSaveable { mutableStateOf(task?.room ?: "") }
    var mode by rememberSaveable {
        mutableStateOf(
            when (task?.due) {
                null -> WhenMode.NEXT
                is Due.Break -> WhenMode.AFTER_N
                is Due.At -> WhenMode.TIME
                Due.None -> WhenMode.NONE
            },
        )
    }
    var afterN by rememberSaveable { mutableIntStateOf((task?.due as? Due.Break)?.afterLesson ?: 1) }
    var timeMin by rememberSaveable {
        mutableIntStateOf((task?.due as? Due.At)?.at?.toLocalTime()?.let { it.hour * 60 + it.minute } ?: (now.hour * 60 + now.minute + 30).coerceAtMost(23 * 60 + 59))
    }
    var repeat by rememberSaveable { mutableStateOf(task?.repeatSchoolDays ?: false) }

    val maxLessons = s.templates.maxOfOrNull { it.lessons.size }?.coerceAtLeast(1) ?: 6
    val time = LocalTime.of(timeMin / 60, timeMin % 60)

    // Во что превратится выбор «когда».
    val due: Due? = when (mode) {
        WhenMode.NEXT -> Plan.nextBreak(s, now)
        WhenMode.AFTER_N -> {
            val keepDate = (task?.due as? Due.Break)?.takeIf { it.afterLesson == afterN }?.date
            when {
                keepDate != null -> Due.Break(keepDate, afterN)
                Plan.lessonEnd(s, today, afterN)?.isAfter(now) == true -> Due.Break(today, afterN)
                else -> Plan.nextOccurrence(s, Due.Break(today, afterN), today)
            }
        }
        WhenMode.TIME -> {
            val keepDate = (task?.due as? Due.At)?.at?.toLocalDate()
            val d = keepDate ?: if (today.atTime(time).isAfter(now)) today else today.plusDays(1)
            Due.At(d.atTime(time))
        }
        WhenMode.NONE -> Due.None
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (task == null) runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Новая задача" else "Задача") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Что сделать") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = room, onValueChange = { room = it },
                    label = { Text("Аудитория (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (data.recentRooms.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        data.recentRooms.forEach { r -> AssistChip(onClick = { room = r }, label = { Text(r) }) }
                    }
                }

                Text("Когда", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mode == WhenMode.NEXT, { mode = WhenMode.NEXT }, { Text("Ближайшая перемена") })
                    FilterChip(mode == WhenMode.AFTER_N, { mode = WhenMode.AFTER_N }, { Text("После пары…") })
                    FilterChip(mode == WhenMode.TIME, {
                        mode = WhenMode.TIME
                        TimePickerDialog(ctx, { _, h, m -> timeMin = h * 60 + m }, time.hour, time.minute, true).show()
                    }, { Text("Время…") })
                    FilterChip(mode == WhenMode.NONE, { mode = WhenMode.NONE }, { Text("Без срока") })
                }
                if (mode == WhenMode.AFTER_N) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..maxLessons).forEach { n ->
                            FilterChip(afterN == n, { afterN = n }, { Text("$n") })
                        }
                    }
                }
                if (mode == WhenMode.TIME) {
                    TextButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> timeMin = h * 60 + m }, time.hour, time.minute, true).show()
                    }) { Text("Время: ${Plan.hhmm(time)} — изменить") }
                }

                Text(
                    when {
                        due == null -> "В расписании нет подходящей пары — задача сохранится без срока."
                        due == Due.None -> "Без напоминания, просто в списке."
                        else -> "Напомню: " + dueLabel(s, due, today)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (due == null) Color(0xFFB4562B) else Color.Gray,
                )

                if (mode != WhenMode.NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = repeat, onCheckedChange = { repeat = it })
                        Text("Повторять каждый учебный день")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    Repo.addOrUpdateTask(task?.id, title, room, due ?: Due.None, repeat && mode != WhenMode.NONE)
                    onDismiss()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (task != null) {
                    TextButton(onClick = { Repo.deleteTask(task.id); onDismiss() }) {
                        Text("Удалить", color = Color(0xFFB4562B))
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
