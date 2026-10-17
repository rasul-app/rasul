package app.rasul.data

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class RelativeTimeLabels(
    val now: String,
    val minutes: (count: Int, localizedCount: String) -> String,
    val hours: (count: Int, localizedCount: String) -> String,
    val yesterday: String,
    val date: (millis: Long, locale: Locale, timeZone: TimeZone) -> String,
)

fun formatRelativeTime(
    timestampMillis: Long,
    nowMillis: Long,
    locale: Locale,
    labels: RelativeTimeLabels,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val ageMillis = (nowMillis - timestampMillis).coerceAtLeast(0L)
    return when {
        ageMillis < 60_000L -> labels.now
        ageMillis < 3_600_000L -> {
            val count = (ageMillis / 60_000L).toInt()
            labels.minutes(count, NumberFormat.getIntegerInstance(locale).format(count))
        }
        ageMillis < 86_400_000L -> {
            val count = (ageMillis / 3_600_000L).toInt()
            labels.hours(count, NumberFormat.getIntegerInstance(locale).format(count))
        }
        else -> {
            val zone = timeZone.toZoneId()
            val targetDate = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            if (ChronoUnit.DAYS.between(targetDate, today) == 1L) labels.yesterday
            else labels.date(timestampMillis, locale, timeZone)
        }
    }
}
