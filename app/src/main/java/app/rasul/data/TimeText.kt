package app.rasul.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.DateFormat
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * Hermes sends sessions' times as epoch seconds (numbers) and jobs' times as ISO-8601 strings with an offset
 * (cron/jobs.py writes timezone-aware values). Both are shown in the phone's zone and locale.
 * A string that is not offset ISO-8601 is shown as received. Null/other -> null.
 */
fun timeText(e: JsonElement?): String? {
    val p = e as? JsonPrimitive ?: return null
    if (p.isString) return p.contentOrNull?.takeIf { it.isNotBlank() }?.let { isoText(it) }
    val secs = p.doubleOrNull ?: return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date((secs * 1000).toLong()))
}

internal fun isoText(s: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale).withZone(zone).format(OffsetDateTime.parse(s.trim()))
    } catch (e: DateTimeException) {
        s
    }

/** The same two wire shapes [timeText] reads, as epoch milliseconds. Unparseable/other -> null. */
fun timeMillis(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    if (p.isString) return try {
        OffsetDateTime.parse(p.content.trim()).toInstant().toEpochMilli()
    } catch (e: DateTimeException) {
        null
    }
    return p.doubleOrNull?.let { (it * 1000.0).toLong() }
}

/** Day-of-week + short time for chat separators ("Thursday 8:46 PM" in EN; localized in AR). */
fun chatSeparatorText(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val dt = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEEE", locale).format(dt)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(dt)
    return "$day $time"
}
