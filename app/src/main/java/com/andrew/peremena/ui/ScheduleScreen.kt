package com.andrew.peremena.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andrew.peremena.alarm.AlarmScheduler
import com.andrew.peremena.data.Codec
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.AppData
import com.andrew.peremena.model.Plan
import com.andrew.peremena.model.Schedule
import com.andrew.peremena.model.Signals
import com.andrew.peremena.model.Template
import java.time.DayOfWeek
import java.util.UUID

private val dayNames = listOf(
    DayOfWeek.MONDAY to "Понедельник", DayOfWeek.TUESDAY to "Вторник", DayOfWeek.WEDNESDAY to "Среда",
    DayOfWeek.THURSDAY to "Четверг", DayOfWeek.FRIDAY to "Пятница", DayOfWeek.SATURDAY to "Суббота",
    DayOfWeek.SUNDAY to "Воскресенье",
)

@Composable
fun ScheduleScreen(data: AppData, modifier: Modifier) {
    val ctx = LocalContext.current
    val s = data.schedule
    fun save(newS: Schedule) = Repo.setSchedule(newS.copy(isExample = false))

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Codec.encodeSchedule(Repo.state.value.schedule).toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "Расписание сохранено" else "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                Codec.decodeSchedule(text)
            }
            result.onSuccess {
                Repo.setSchedule(it.copy(isExample = false))
                Toast.makeText(ctx, "Расписание загружено", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, "Файл не подходит: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Шаблоны звонков", style = MaterialTheme.typography.titleLarge)
        Text(
            "По одной паре на строку, например 09:00-10:30. Перемены считаются сами.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        s.templates.forEach { t ->
            TemplateCard(
                t = t,
                canDelete = s.templates.size > 1,
                onSave = { nt -> save(s.copy(templates = s.templates.map { if (it.id == t.id) nt else it })) },
                onDelete = {
                    save(
                        s.copy(
                            templates = s.templates.filter { it.id != t.id },
                            week = s.week.mapValues { (_, v) -> if (v == t.id) null else v },
                            overrides = s.overrides.filter { it.templateId != t.id },
                        ),
                    )
                },
            )
        }
        OutlinedButton(onClick = {
            val id = "t" + UUID.randomUUID().toString().take(6)
            save(s.copy(templates = s.templates + Template(id, "Новый шаблон", emptyList())))
        }) { Text("+ Шаблон") }

        Text("Неделя", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                dayNames.forEach { (day, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f))
                        TemplatePicker(s, s.week[day]) { id -> save(s.copy(week = s.week + (day to id))) }
                    }
                }
            }
        }

        Text("Сигналы", style = MaterialTheme.typography.titleLarge)
        SignalsCard(s.signals) { sig -> Repo.setSchedule(s.copy(signals = sig)) }

        Text("Надёжность", style = MaterialTheme.typography.titleLarge)
        ReliabilityCard(compact = false)
        Button(onClick = {
            AlarmScheduler.scheduleTest(ctx)
            Toast.makeText(ctx, "Заблокируйте экран и ждите ~1 минуту", Toast.LENGTH_LONG).show()
        }) { Text("Проверить сигнал через 1 минуту") }

        Text("Файл расписания", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("raspisanie-zvonkov.json") }) { Text("Сохранить в файл") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Загрузить") }
        }
        Text(
            "Файлом можно поделиться с коллегами, чтобы им не вбивать расписание заново.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
}

@Composable
private fun TemplateCard(t: Template, canDelete: Boolean, onSave: (Template) -> Unit, onDelete: () -> Unit) {
    var name by remember(t) { mutableStateOf(t.name) }
    var text by remember(t) { mutableStateOf(Plan.formatLessons(t.lessons)) }
    val parsed = remember(text) { Plan.parseLessons(text) }
    val changed = name != t.name || text != Plan.formatLessons(t.lessons)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                text, { text = it },
                label = { Text("Пары") },
                minLines = 3,
                isError = parsed.isFailure,
                supportingText = {
                    val err = parsed.exceptionOrNull()
                    if (err != null) Text(err.message ?: "Ошибка") else Text("Пар: ${parsed.getOrNull()?.size ?: 0}")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = changed && parsed.isSuccess && name.isNotBlank(),
                    onClick = { onSave(t.copy(name = name.trim(), lessons = parsed.getOrThrow())) },
                ) { Text("Сохранить") }
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Удалить шаблон", color = Color(0xFFB4562B)) }
                }
            }
        }
    }
}

@Composable
private fun TemplatePicker(s: Schedule, selectedId: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = selectedId?.let { id -> s.templates.firstOrNull { it.id == id }?.name } ?: "выходной"
    Box {
        TextButton(onClick = { open = true }) { Text("$label ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.templates.forEach { t ->
                DropdownMenuItem(text = { Text(t.name) }, onClick = { onPick(t.id); open = false })
            }
            DropdownMenuItem(text = { Text("выходной") }, onClick = { onPick(null); open = false })
        }
    }
}

@Composable
private fun SignalsCard(sig: Signals, onChange: (Signals) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Stepper("За сколько минут до конца пары", sig.beforeEndMin) { onChange(sig.copy(beforeEndMin = it)) }
            SwitchRow("В момент звонка на перемену", sig.atBell) { onChange(sig.copy(atBell = it)) }
            Stepper("За сколько минут до начала пары", sig.beforeStartMin) { onChange(sig.copy(beforeStartMin = it)) }
            SwitchRow("Короткий звук вместе с вибрацией", sig.sound) { onChange(sig.copy(sound = it)) }
            SwitchRow("Сигналить даже в беззвучном режиме", sig.ignoreSilent) { onChange(sig.copy(ignoreSilent = it)) }
        }
    }
}

@Composable
private fun Stepper(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) { Text("−") }
        Text(if (value == 0) "выкл" else "$value", Modifier.width(44.dp), fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(30)) }) { Text("+") }
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
