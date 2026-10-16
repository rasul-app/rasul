package app.rasul.data

/** What a tab that reads from the active connection can show. No generic failure case. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object NoConnection : Load<Nothing>
    data class NoKey(val connection: String) : Load<Nothing>
    data class Failed(val error: HermesError) : Load<Nothing>

    /** [source] is the connection that produced [value]; actions on [value] must go to exactly that target. */
    data class Ready<T>(val connection: String, val value: T, val source: ActiveTarget.Ready) : Load<T>
}

/** Runs [call] against exactly this target and maps every outcome to a [Load]. */
suspend fun <T> ActiveTarget.load(
    accept: (ActiveTarget.Ready) -> Boolean = { true },
    call: suspend (HermesTarget) -> HermesResult<T>,
): Load<T> =
    when (this) {
        ActiveTarget.None -> Load.NoConnection
        is ActiveTarget.Failed -> Load.Failed(error)
        is ActiveTarget.NoKey -> Load.NoKey(name)
        is ActiveTarget.Unreadable -> Load.Failed(HermesError.Storage(detail))
        is ActiveTarget.Ready ->
            if (!accept(this)) {
                Load.Failed(HermesError.ConnectionChanged)
            } else {
                when (val r = call(target)) {
                    is HermesResult.Ok -> Load.Ready(name, r.value, this)
                    is HermesResult.Err -> Load.Failed(r.error)
                }
            }
    }

/**
 * Runs [call] against the store's active connection read now. [accept] lets a caller that
 * was opened for one specific connection refuse any other (-> ConnectionChanged).
 */
suspend fun <T> ConnectionStore.loadActive(
    accept: (ActiveTarget.Ready) -> Boolean = { true },
    call: suspend (HermesTarget) -> HermesResult<T>,
): Load<T> = activeTarget().load(accept, call)
