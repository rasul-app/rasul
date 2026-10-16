package app.rasul.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** The connection the Sessions and Jobs tabs talk to: the one picked in the PC switcher, else the first saved one. */
sealed interface ActiveTarget {
    data object None : ActiveTarget
    data class Failed(val error: HermesError) : ActiveTarget

    /** A connection exists but its key is missing or can no longer be decrypted. */
    data class NoKey(val name: String) : ActiveTarget

    /** The saved connections could not be read (storage or JSON failure). */
    data class Unreadable(val detail: String) : ActiveTarget

    /**
     * [id] is the connection id. Data equality covers id, name, address, profile and key, so a screen that kept
     * the Ready it loaded from can tell later that the connection was switched, edited or deleted.
     */
    data class Ready(val id: String, val name: String, val target: HermesTarget) : ActiveTarget
}

internal fun readyOrNoKey(c: Connection, key: String?): ActiveTarget =
    if (key.isNullOrEmpty()) ActiveTarget.NoKey(c.name) else ActiveTarget.Ready(c.id, c.name, HermesTarget(c.baseUrl, c.profile, key))

suspend fun ConnectionStore.activeTarget(): ActiveTarget = try {
    val c = active()
    if (c == null) {
        ActiveTarget.None
    } else {
        readyOrNoKey(c, apiKey(c.id))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ActiveTarget.Unreadable(e.message ?: e.javaClass.simpleName)
}
