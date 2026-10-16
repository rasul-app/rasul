package app.rasul.bridge

import app.rasul.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class BridgeProfile(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    val ready: Boolean,
    @SerialName("default") val isDefault: Boolean,
)

@Serializable
data class ProfileSnapshot(val revision: Long, val profiles: List<BridgeProfile>)

internal val profileJson = Json { ignoreUnknownKeys = true }
internal fun parseProfiles(body: String): ProfileSnapshot = profileJson.decodeFromString<ProfileSnapshot>(body).also {
    require(it.revision >= 0 && it.profiles.map { p -> p.id }.distinct().size == it.profiles.size)
    require(it.profiles.all { p -> Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(p.id) })
}

class ProfileStreamException(val error: HermesError) : Exception()

interface ProfilesApi {
    suspend fun list(): ProfileSnapshot
    fun events(): Flow<ProfileSnapshot>
}

class BridgeProfiles(private val client: OkHttpClient) : ProfilesApi {
    private fun request(path: String, accept: String) = Request.Builder()
        .url(BridgeTransport.origin.newBuilder().addPathSegments(path).build())
        .header("Accept", accept).build()

    override suspend fun list(): ProfileSnapshot = client.bridgeResponse(request("v1/profiles", "application/json")) { response ->
        bridgeStatus(response.code, HermesError.BridgeResponseInvalid)?.let { throw ProfileStreamException(it) }
        decode(response.body?.string().orEmpty())
    }

    override fun events(): Flow<ProfileSnapshot> = channelFlow {
        client.bridgeStreamClient().bridgeResponse(request("v1/profile-events", "text/event-stream")) { response ->
            bridgeStatus(response.code, HermesError.BridgeResponseInvalid)?.let { throw ProfileStreamException(it) }
            val source = response.body?.source() ?: throw IOException()
            for (frame in source.sseFrames()) if (frame.event == "profiles") send(decode(frame.data))
        }
    }

    private fun decode(body: String): ProfileSnapshot = try { parseProfiles(body) }
    catch (_: Exception) { throw ProfileStreamException(HermesError.BridgeResponseInvalid) }
}

data class BridgeGroup(
    val id: String,
    val name: String,
    val profiles: List<BridgeProfile> = emptyList(),
    val loading: Boolean = true,
    val error: HermesError? = null,
)

data class BridgeBotsState(val groups: List<BridgeGroup> = emptyList(), val storageError: Boolean = false)

/** Each event replaces a complete snapshot, including removals and readiness changes. */
fun followProfiles(
    api: ProfilesApi,
    wait: suspend (Long) -> Unit = { delay(it) },
): Flow<BridgeGroupUpdate> = flow {
    var attempt = 0
    var listed = false
    while (currentCoroutineContext().isActive) {
        var failure: HermesError = HermesError.BridgeUnreachable
        try {
            if (!listed) { emit(BridgeGroupUpdate.Snapshot(api.list())); listed = true }
            api.events().collect {
                emit(BridgeGroupUpdate.Snapshot(it))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ProfileStreamException) { failure = e.error }
        catch (e: Exception) { failure = bridgeError(e) }
        emit(BridgeGroupUpdate.Failed(failure))
        if (failure == HermesError.DeviceRevoked || failure == HermesError.DeviceKeyRepair) return@flow
        attempt = (attempt + 1).coerceAtMost(4)
        wait(reconnectBackoffMs(attempt))
    }
}

sealed interface BridgeGroupUpdate {
    data class Snapshot(val value: ProfileSnapshot) : BridgeGroupUpdate
    data class Failed(val error: HermesError) : BridgeGroupUpdate
}

class BridgeBotsRepository(
    private val store: BridgeStore,
    private val clients: BridgeClients,
    private val api: (OkHttpClient) -> ProfilesApi = ::BridgeProfiles,
) {
    private val mutable = MutableStateFlow(BridgeBotsState())
    val state: StateFlow<BridgeBotsState> = mutable

    /** Called only by the visible, resumed Bots destination. Cancellation closes every SSE read. */
    suspend fun follow(): Unit = try {
        store.bridges.distinctUntilChanged { old, new ->
            old.map { it.copy(lastWorkingUrl = null) } == new.map { it.copy(lastWorkingUrl = null) }
        }.collectLatest { bridges ->
            val cached = mutable.value.groups.associateBy { it.id }
            mutable.value = BridgeBotsState(bridges.map { bridge ->
                cached[bridge.bridgeId]?.copy(name = bridge.name, loading = true)
                    ?: BridgeGroup(bridge.bridgeId, bridge.name)
            })
            coroutineScope {
                bridges.forEach { bridge -> launch {
                    try {
                        followProfiles(api(clients.client(bridge).client)).collect { update ->
                            mutable.update { state -> state.copy(groups = state.groups.map { group ->
                                if (group.id != bridge.bridgeId) group else when (update) {
                                    is BridgeGroupUpdate.Snapshot -> group.copy(profiles = update.value.profiles, loading = false, error = null)
                                    is BridgeGroupUpdate.Failed -> group.copy(loading = false, error = update.error)
                                }
                            }) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutable.update { s -> s.copy(groups = s.groups.map {
                        if (it.id == bridge.bridgeId) it.copy(loading = false, error = bridgeError(e)) else it
                    }) } }
                } }
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { mutable.update { it.copy(storageError = true) } }

    suspend fun target(bridgeId: String, profileId: String): HermesResult<HermesTarget> {
        val bridge = store.get(bridgeId) ?: return HermesResult.Err(HermesError.ConnectionChanged)
        val group = state.value.groups.firstOrNull { it.id == bridgeId }
            ?: return HermesResult.Err(HermesError.BridgeUnreachable)
        group.error?.let { return HermesResult.Err(it) }
        val profile = group.profiles.firstOrNull { it.id == profileId }
            ?: return HermesResult.Err(HermesError.ProfileUnknown(profileId))
        if (!profile.ready) return HermesResult.Err(HermesError.ProfileNotReady)
        return HermesResult.Ok(HermesTarget("", null, "", BridgeEndpoint(clients.client(bridge).client, profileId)))
    }
}

fun bridgeBotId(bridgeId: String, profileId: String) = "bridge:$bridgeId:$profileId"
