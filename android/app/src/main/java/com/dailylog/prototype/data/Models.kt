package com.dailylog.prototype.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

enum class Lang(val code: String, val pill: String) { EN("en", "English"), HI("hi", "हिंदी"), MR("mr", "मराठी") }

data class Label(val en: String, val hi: String?, val mr: String?) {
    fun pick(lang: Lang): String =
        when (lang) {
            Lang.EN -> en
            Lang.HI -> hi ?: en
            Lang.MR -> mr ?: en
        }
}

/** One row on the confirm card. [value] is kept as text so the member can edit it. */
data class Field(
    val key: String,
    val label: Label,
    val value: String,
    val numeric: Boolean,
    val unit: String?,
    val context: String?,
    val slot: String?,
    val eventTime: String?,
) {
    fun display(): String = if (unit != null) "$value $unit" else value

    fun timeText(): String? =
        eventTime?.let {
            runCatching {
                val t = OffsetDateTime.parse(it)
                val h = t.hour % 12
                "%d:%02d %s".format(if (h == 0) 12 else h, t.minute, if (t.hour < 12) "AM" else "PM")
            }.getOrNull()
        }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("key", key)
            put("value", if (numeric) value.toDoubleOrNull() ?: value else value)
            unit?.let { put("unit", it) }
            context?.let { put("context", it) }
            slot?.let { put("slot", it) }
            eventTime?.let { put("event_time", it) }
        }
}

data class MealItem(val name: String, val category: String, val count: Int?) {
    fun display(): String = if (count != null && count > 1) "$name ×$count" else name

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("category", category)
            count?.let { put("count", it) }
        }
}

data class Entry(
    val id: String,
    val kind: String,
    val status: String,
    val logDate: LocalDate,
    val summary: String,
    val fields: List<Field>,
    val redFlags: List<String>,
    val questions: List<String>,
    val transcript: String?,
    val slot: String?,
    val slotLabel: Label?,
    val items: List<MealItem>,
    val loggedLater: Boolean,
    val confirmedAt: Instant?,
    val engine: String?,
)

data class StripDay(val date: LocalDate, val status: String, val isToday: Boolean)

data class DayView(val date: LocalDate, val entries: List<Entry>, val strip: List<StripDay>)

data class LoginResult(val token: String, val phone: String, val isNew: Boolean)

// ------------------------------------------------------------------ JSON readers

private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

fun labelOf(json: JSONObject?, fallback: String): Label =
    Label(json?.str("en") ?: fallback, json?.str("hi"), json?.str("mr"))

fun fieldOf(json: JSONObject): Field {
    val raw = json.opt("value")
    val numeric = raw is Number
    val value =
        when (raw) {
            is Number -> raw.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
            else -> raw?.toString().orEmpty()
        }
    val key = json.getString("key")
    return Field(
        key = key,
        label = labelOf(json.optJSONObject("label"), key),
        value = value,
        numeric = numeric,
        unit = json.str("unit"),
        context = json.str("context"),
        slot = json.str("slot"),
        eventTime = json.str("event_time"),
    )
}

fun itemOf(json: JSONObject): MealItem =
    MealItem(json.getString("name"), json.optString("category", "other"), if (json.isNull("count")) null else json.optInt("count"))

fun entryOf(json: JSONObject): Entry =
    Entry(
        id = json.getString("id"),
        kind = json.optString("kind", "text"),
        status = json.optString("status"),
        logDate = LocalDate.parse(json.getString("log_date")),
        summary = json.str("summary").orEmpty(),
        fields = json.optJSONArray("fields").objects().map(::fieldOf),
        redFlags = json.optJSONArray("red_flags").strings(),
        questions = json.optJSONArray("questions").strings(),
        transcript = json.str("transcript"),
        slot = json.str("slot"),
        slotLabel = json.optJSONObject("slot_label")?.let { labelOf(it, json.optString("slot")) },
        items = json.optJSONArray("items").objects().map(::itemOf),
        loggedLater = json.optBoolean("logged_later"),
        confirmedAt = json.str("confirmed_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
        engine = json.str("engine"),
    )

fun dayOf(json: JSONObject): DayView =
    DayView(
        date = LocalDate.parse(json.getString("log_date")),
        entries = json.optJSONArray("entries").objects().map(::entryOf),
        strip =
            json.optJSONArray("strip").objects().map {
                StripDay(LocalDate.parse(it.getString("date")), it.optString("status"), it.optBoolean("is_today"))
            },
    )
