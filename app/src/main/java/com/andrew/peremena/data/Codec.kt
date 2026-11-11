package com.andrew.peremena.data

import com.andrew.peremena.model.AppData
import com.andrew.peremena.model.DayOverride
import com.andrew.peremena.model.Due
import com.andrew.peremena.model.Lesson
import com.andrew.peremena.model.Schedule
import com.andrew.peremena.model.Signals
import com.andrew.peremena.model.Status
import com.andrew.peremena.model.Task
import com.andrew.peremena.model.Template
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * JSON-формат из ТЗ, раздел 5. Используется встроенный в Android org.json —
 * без дополнительных библиотек и плагинов компилятора.
 */
object Codec {
    private val dayKeys = mapOf(
        DayOfWeek.MONDAY to "mon", DayOfWeek.TUESDAY to "tue", DayOfWeek.WEDNESDAY to "wed",
        DayOfWeek.THURSDAY to "thu", DayOfWeek.FRIDAY to "fri", DayOfWeek.SATURDAY to "sat",
        DayOfWeek.SUNDAY to "sun",
    )

    // ---------------- запись ----------------

    fun encodeApp(d: AppData): String = JSONObject().apply {
        put("version", 1)
        put("schedule", scheduleToJson(d.schedule))
        put("tasks", JSONArray().apply { d.tasks.forEach { put(taskToJson(it)) } })
        put("recentRooms", JSONArray(d.recentRooms))
    }.toString(2)

    fun encodeSchedule(s: Schedule): String = scheduleToJson(s).toString(2)

    private fun scheduleToJson(s: Schedule) = JSONObject().apply {
        put("version", 1)
        put("timezone", s.timezone)
        put("isExample", s.isExample)
        put("templates", JSONArray().apply {
            s.templates.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("lessons", JSONArray().apply {
                        t.lessons.forEach { l ->
                            put(JSONObject().put("n", l.n).put("start", hhmm(l.start)).put("end", hhmm(l.end)))
                        }
                    })
                })
            }
        })
        put("week", JSONObject().apply {
            dayKeys.forEach { (day, key) -> put(key, s.week[day] ?: JSONObject.NULL) }
        })
        put("overrides", JSONArray().apply {
            s.overrides.forEach { o -> put(JSONObject().put("date", o.date.toString()).put("template", o.templateId ?: JSONObject.NULL)) }
        })
        put("signals", JSONObject().apply {
            put("beforeEndMin", s.signals.beforeEndMin)
            put("atBell", s.signals.atBell)
            put("beforeStartMin", s.signals.beforeStartMin)
            put("sound", s.signals.sound)
            put("ignoreSilent", s.signals.ignoreSilent)
        })
    }

    private fun taskToJson(t: Task) = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("room", t.room)
        put("notes", t.notes)
        put("due", when (val d = t.due) {
            is Due.Break -> JSONObject().put("type", "break").put("date", d.date.toString()).put("afterLesson", d.afterLesson)
            is Due.At -> JSONObject().put("type", "time").put("at", d.at.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
            Due.None -> JSONObject().put("type", "none")
        })
        put("remindBeforeMin", t.remindBeforeMin)
        put("repeat", if (t.repeatSchoolDays) JSONObject().put("days", "school") else JSONObject.NULL)
        put("status", t.status.name.lowercase())
        put("createdAt", t.createdAt.toString())
        put("updatedAt", t.updatedAt.toString())
        put("doneAt", t.doneAt?.toString() ?: JSONObject.NULL)
        put("source", t.source)
        put("externalId", t.externalId ?: JSONObject.NULL)
    }

    // ---------------- чтение ----------------

    fun decodeApp(text: String): AppData {
        val o = JSONObject(text)
        val def = AppData.default()
        val schedule = o.optJSONObject("schedule")?.let { scheduleFromJson(it) } ?: def.schedule
        val tasks = o.optJSONArray("tasks")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> runCatching { taskFromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.orEmpty()
        val rooms = o.optJSONArray("recentRooms")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        return AppData(schedule, tasks, rooms)
    }

    /** Чтение файла расписания (экспорт/импорт). Бросает исключение с понятным текстом. */
    fun decodeSchedule(text: String): Schedule {
        val o = JSONObject(text)
        // Принимаем и чистый файл расписания, и полный файл данных приложения.
        val s = o.optJSONObject("schedule") ?: o
        require(s.has("templates")) { "В файле нет раздела templates — это не расписание звонков" }
        return scheduleFromJson(s)
    }

    private fun scheduleFromJson(o: JSONObject): Schedule {
        val tArr = o.getJSONArray("templates")
        val templates = (0 until tArr.length()).map { i ->
            val t = tArr.getJSONObject(i)
            val lArr = t.optJSONArray("lessons") ?: JSONArray()
            val lessons = (0 until lArr.length()).map { j ->
                val l = lArr.getJSONObject(j)
                Lesson(l.optInt("n", j + 1), LocalTime.parse(l.getString("start").padStart(5, '0')), LocalTime.parse(l.getString("end").padStart(5, '0')))
            }
            Template(t.getString("id"), t.optString("name", t.getString("id")), lessons)
        }
        val wObj = o.optJSONObject("week") ?: JSONObject()
        val week = dayKeys.entries.associate { (day, key) -> day to wObj.optStringOrNull(key) }
        val oArr = o.optJSONArray("overrides") ?: JSONArray()
        val overrides = (0 until oArr.length()).map { i ->
            val ov = oArr.getJSONObject(i)
            DayOverride(LocalDate.parse(ov.getString("date")), ov.optStringOrNull("template"))
        }
        val sObj = o.optJSONObject("signals") ?: JSONObject()
        val def = Signals()
        val signals = Signals(
            beforeEndMin = sObj.optInt("beforeEndMin", def.beforeEndMin),
            atBell = sObj.optBoolean("atBell", def.atBell),
            beforeStartMin = sObj.optInt("beforeStartMin", def.beforeStartMin),
            sound = sObj.optBoolean("sound", def.sound),
            ignoreSilent = sObj.optBoolean("ignoreSilent", def.ignoreSilent),
        )
        return Schedule(
            templates = templates,
            week = week,
            overrides = overrides,
            signals = signals,
            isExample = o.optBoolean("isExample", false),
            timezone = o.optString("timezone", "Europe/Moscow"),
        )
    }

    private fun taskFromJson(o: JSONObject): Task {
        val now = OffsetDateTime.now()
        val dueObj = o.optJSONObject("due")
        val due: Due = when (dueObj?.optString("type")) {
            "break" -> Due.Break(LocalDate.parse(dueObj.getString("date")), dueObj.getInt("afterLesson"))
            "time" -> Due.At(parseDateTime(dueObj.getString("at")))
            else -> Due.None
        }
        return Task(
            id = o.getString("id"),
            title = o.getString("title"),
            room = o.optString("room", ""),
            notes = o.optString("notes", ""),
            due = due,
            remindBeforeMin = o.optInt("remindBeforeMin", 0),
            // В MVP любой повтор трактуется как «каждый учебный день».
            repeatSchoolDays = o.optJSONObject("repeat") != null,
            status = when (o.optString("status")) {
                "done" -> Status.DONE
                "cancelled" -> Status.CANCELLED
                else -> Status.OPEN
            },
            createdAt = o.optStringOrNull("createdAt")?.let { OffsetDateTime.parse(it) } ?: now,
            updatedAt = o.optStringOrNull("updatedAt")?.let { OffsetDateTime.parse(it) } ?: now,
            doneAt = o.optStringOrNull("doneAt")?.let { OffsetDateTime.parse(it) },
            source = o.optString("source", "reminder"),
            externalId = o.optStringOrNull("externalId"),
        )
    }

    /** «2026-11-11T14:20:00+03:00» или без смещения — в локальное время телефона. */
    private fun parseDateTime(s: String): LocalDateTime =
        runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
            .getOrElse { LocalDateTime.parse(s) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
