package app.rasul.ui

import android.content.Context
import app.rasul.R
import app.rasul.data.HermesError

internal fun rateLimitMessage(error: HermesError.Server, context: Context): String? = when (error.serverCode ?: error.message) {
    "fork_rate_limited" -> context.getString(R.string.fork_rate_limited)
    "model_rate_limited" -> context.getString(R.string.model_rate_limited)
    "fork_invalid_fields" -> context.getString(R.string.fork_invalid_fields)
    "model_invalid_fields" -> context.getString(R.string.model_invalid_fields)
    else -> null
}
