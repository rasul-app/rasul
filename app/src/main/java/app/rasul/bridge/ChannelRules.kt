package app.rasul.bridge

// The checks of docs/bridge-desktop.md "Messaging channels" (WhatsApp setup, channel settings), made in the app before
// anything is sent so the owner gets a specific sentence and a secret never leaves the phone in a form the PC refuses.

const val MAX_WHATSAPP_NUMBERS = 20
const val MAX_SETTING_CHANGES = 8
const val MAX_SETTING_LENGTH = 4096
const val MAX_QR_PAYLOAD = 1024

enum class WhatsappMode(val wire: String) { Bot("bot"), SelfChat("self-chat") }

/** Why the allowed-numbers text was refused. */
enum class NumbersProblem { Format, TooMany, TooLong }

sealed interface WhatsappNumbers {
    data class Ok(val numbers: List<String>) : WhatsappNumbers
    data class Invalid(val problem: NumbersProblem) : WhatsappNumbers
}

private val PHONE = Regex("\\+?[0-9]{1,20}")

private fun asciiDigits(text: String): String = buildString {
    for (ch in text) {
        append(
            when {
                ch == '،' || ch == '\n' || ch == ';' -> ','
                ch.isDigit() -> Character.forDigit(Character.digit(ch, 10), 10)
                else -> ch
            },
        )
    }
}

/**
 * The comma list the owner typed, as the bridge wants it: each number decimal digits with an optional leading `+`
 * (1 to 20 digits), at most [MAX_WHATSAPP_NUMBERS], repeats dropped. Spaces around a number are ignored; an empty text
 * is an empty list. Digits typed on an Arabic keyboard and the Arabic comma are accepted.
 */
fun parseWhatsappNumbers(text: String): WhatsappNumbers {
    val parts = asciiDigits(text).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { !PHONE.matches(it) }) return WhatsappNumbers.Invalid(NumbersProblem.Format)
    val numbers = parts.distinct()
    if (numbers.size > MAX_WHATSAPP_NUMBERS) return WhatsappNumbers.Invalid(NumbersProblem.TooMany)
    return WhatsappNumbers.Ok(numbers)
}

/** Why one setting value (or the set of changes) was refused. */
enum class SettingProblem { BadKey, TooLong, NotAscii, Masked }

private val SETTING_KEY = Regex("[A-Z][A-Z0-9_]{0,63}")
private val MASK_SHAPE = Regex(".{4}\\.\\.\\..{4}")

fun isSettingKey(key: String): Boolean = SETTING_KEY.matches(key)

/** True for a value that is a masked preview rather than a real secret (same rule as the bridge). */
fun looksMasked(value: String): Boolean =
    "…" in value || "****" in value || value.startsWith("«redacted") || value.trim() == "***" || MASK_SHAPE.matches(value.trim())

/** The first reason [value] cannot be saved under [key], or null. A value must be 1 to 4096 printable ASCII characters. */
fun settingValueProblem(key: String, value: String): SettingProblem? = when {
    !isSettingKey(key) -> SettingProblem.BadKey
    value.length > MAX_SETTING_LENGTH -> SettingProblem.TooLong
    looksMasked(value) -> SettingProblem.Masked
    value.any { it.code !in 0x20..0x7e } -> SettingProblem.NotAscii
    else -> null
}

/** What a save sends: only changed values and cleared keys. */
data class SettingsDiff(
    val values: Map<String, String>,
    val clear: List<String>,
    /** Per key, why its text was refused; nothing is sent while this is not empty. */
    val problems: Map<String, SettingProblem>,
    val tooMany: Boolean,
) {
    val isEmpty: Boolean get() = values.isEmpty() && clear.isEmpty()
    val sendable: Boolean get() = !isEmpty && problems.isEmpty() && !tooMany
    override fun toString() = "SettingsDiff(values=${values.keys}, clear=$clear, problems=$problems, tooMany=$tooMany)"
}

/** List fields are typed as a comma list; the Arabic comma counts, empty entries are dropped. */
fun normalizeListSetting(text: String): String =
    text.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

/**
 * Compares what was typed with the channel's own fields. A blank input is "unchanged" and is never sent (a value is
 * never prefilled, so blank cannot mean "empty it"; clearing is its own action). A key in [clearing] wins over text.
 */
fun settingsDiff(fields: List<ChannelFieldDto>, inputs: Map<String, String>, clearing: Set<String>): SettingsDiff {
    val values = linkedMapOf<String, String>()
    val problems = linkedMapOf<String, SettingProblem>()
    val clear = mutableListOf<String>()
    for (field in fields) {
        if (field.key in clearing) {
            clear += field.key
            continue
        }
        val raw = inputs[field.key].orEmpty()
        val text = if (field.isList) normalizeListSetting(raw) else raw
        if (text.isBlank()) continue
        val problem = settingValueProblem(field.key, text)
        if (problem != null) problems[field.key] = problem else values[field.key] = text
    }
    return SettingsDiff(values, clear, problems, tooMany = values.size > MAX_SETTING_CHANGES || clear.size > MAX_SETTING_CHANGES)
}
