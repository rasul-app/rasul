package app.rasul

import app.rasul.bridge.BridgeDesktopApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.HermesUpdateDto
import app.rasul.bridge.HttpBridgeStatusApi
import app.rasul.desktop.updateReasonError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request shape, parsing and error mapping of the hermes/update, mcp manage and /v1/status routes. */
class HermesUpdateApiTest {
    private val hits = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    private fun client(status: Int, body: String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        hits += request
        bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun api(status: Int = 200, body: String = "{}") = BridgeDesktopApi(BridgeEndpoint(client(status, body), "profile1"))

    private fun failure(api: BridgeDesktopApi, call: suspend BridgeDesktopApi.() -> Unit): DesktopError = try {
        runBlocking { api.call() }
        error("expected the call to fail")
    } catch (e: DesktopException) { e.error }

    private val fullStatus = """{"current":"0.21.4","latest":"9f3c2ab4e1d0","behind":6,"can_apply":true,"reason":null,
        "changes":[{"title":"Faster startup"},{"title":"Fix scheduled jobs"}],"state":"idle","active_agents":2,
        "queued_at":null,"result":null}"""

    @Test fun getUpdateParsesTheWholeStatusObject() = runBlocking {
        val status = api(200, fullStatus).hermesUpdate()
        assertEquals("GET", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/hermes/update", hits.single().url.encodedPath)
        assertEquals("0.21.4", status.current); assertEquals(6, status.behind); assertTrue(status.canApply)
        assertEquals(listOf("Faster startup", "Fix scheduled jobs"), status.changes.map { it.title })
        assertEquals(2, status.activeAgents); assertTrue(status.updateAvailable)
    }

    @Test fun unknownBehindWithApplyStillCountsAsAvailableButNotWithoutApply() {
        assertTrue(HermesUpdateDto(behind = null, canApply = true).updateAvailable)
        assertFalse(HermesUpdateDto(behind = null, canApply = false).updateAvailable)
        assertFalse(HermesUpdateDto(behind = 0, canApply = true).updateAvailable)
        assertTrue(HermesUpdateDto(behind = 3, canApply = false).updateAvailable)
    }

    @Test fun getUpdateParsesQueuedRunningAndResult() = runBlocking {
        val queued = api(200, """{"current":"1","latest":null,"behind":null,"can_apply":false,"reason":"update_in_progress",
            "changes":[],"state":"queued","active_agents":null,"queued_at":"2026-10-18T09:30:00+00:00","result":null}""").hermesUpdate()
        assertTrue(queued.isQueued); assertNull(queued.activeAgents); assertNull(queued.behind)
        assertEquals("2026-10-18T09:30:00+00:00", queued.queuedAt)
        val done = api(200, """{"current":"2","state":"done","result":{"ok":true,"from":"1","to":"2","message":"Updated."}}""").hermesUpdate()
        assertEquals("2", done.result?.to); assertTrue(done.result?.ok == true)
    }

    @Test fun postSendsExactlyTheModeKeyAndParsesThe202Body() = runBlocking {
        val client = api(202, fullStatus.replace("\"idle\"", "\"queued\""))
        assertTrue(client.startHermesUpdate(HermesUpdateDto.MODE_WHEN_IDLE).isQueued)
        val request = hits.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/profiles/profile1/desktop/hermes/update", request.url.encodedPath)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("mode"), body.keys)
        assertEquals("when_idle", body.getValue("mode").jsonPrimitive.content)
    }

    @Test fun nowModeIsSentAsNow() = runBlocking {
        api(202, fullStatus).startHermesUpdate(HermesUpdateDto.MODE_NOW)
        assertEquals("now", Json.parseToJsonElement(bodies.single()).jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test fun anyOtherModeIsRefusedBeforeARequest() {
        assertEquals(DesktopError.InvalidRequest, failure(api()) { startHermesUpdate("later") })
        assertTrue(hits.isEmpty())
    }

    @Test fun deleteCancelsAndParsesTheIdleStatus() = runBlocking {
        val status = api(200, """{"state":"idle","result":{"ok":false,"message":"Cancelled."}}""").cancelHermesUpdate()
        assertEquals("DELETE", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/hermes/update", hits.single().url.encodedPath)
        assertEquals("idle", status.state)
        assertEquals("Cancelled.", status.result?.message)
    }

    private val updateTokens = listOf(
        Triple(403, "bridge_elevated", DesktopError.BridgeElevated),
        Triple(403, "dashboard_elevated", DesktopError.DashboardElevated),
        Triple(403, "cannot_verify_dashboard_rights", DesktopError.CannotVerifyDashboard),
        Triple(403, "dashboard_not_running", DesktopError.DashboardNotRunning),
        Triple(403, "not_applicable", DesktopError.NotApplicable),
        Triple(403, "check_failed", DesktopError.CheckFailed),
        Triple(409, "up_to_date", DesktopError.UpToDate),
        Triple(409, "update_in_progress", DesktopError.UpdateInProgress),
        Triple(409, "update_running", DesktopError.UpdateRunning),
        Triple(409, "nothing_queued", DesktopError.NothingQueued),
        Triple(429, "update_rate_limited", DesktopError.UpdateRateLimited),
        Triple(502, "update_not_started", DesktopError.UpdateNotStarted),
    )

    @Test fun everyUpdateReasonOnPostMapsToItsOwnError() {
        updateTokens.forEach { (status, token, expected) ->
            assertEquals(token, expected, failure(api(status, """{"error":"$token"}""")) { startHermesUpdate("now") })
        }
    }

    @Test fun everyUpdateReasonOnDeleteAndGetMapsToItsOwnError() {
        updateTokens.forEach { (status, token, expected) ->
            assertEquals(token, expected, failure(api(status, """{"error":"$token"}""")) { cancelHermesUpdate() })
            assertEquals(token, expected, failure(api(status, """{"error":"$token"}""")) { hermesUpdate() })
        }
    }

    @Test fun updateErrorsHaveDistinctMessagesNeverTheGenericOnes() {
        val messages = updateTokens.map { it.third.message }
        assertEquals(messages.size, messages.toSet().size)
        val generic = setOf(
            DesktopError.DesktopFailed, DesktopError.InvalidResponse, DesktopError.RateLimited, DesktopError.InvalidRequest,
            DesktopError.DeviceRevoked,
        ).map { it.message }
        assertTrue(messages.none { it in generic })
    }

    @Test fun existingMappingsAreUnchanged() {
        assertEquals(DesktopError.DeviceRevoked, failure(api(403, "{}")) { hermesUpdate() })
        assertEquals(DesktopError.NotFound, failure(api(404, """{"error":"Route not found"}""")) { hermesUpdate() })
        assertEquals(DesktopError.RateLimited, failure(api(429, """{"error":"desktop_rate_limited"}""")) { hermesUpdate() })
        assertEquals(DesktopError.InvalidRequest, failure(api(400, """{"error":"desktop_invalid"}""")) { startHermesUpdate("now") })
        assertEquals(DesktopError.Unavailable, failure(api(503, """{"error":"desktop_unavailable"}""")) { hermesUpdate() })
        assertEquals(DesktopError.InvalidResponse, failure(api(200, "not json")) { hermesUpdate() })
    }

    @Test fun statusReasonUsesTheSameTokens() {
        updateTokens.forEach { (_, token, expected) -> assertEquals(expected, updateReasonError(token)) }
        assertNull(updateReasonError("something_new"))
        assertNull(updateReasonError(null))
    }

    // ---------- MCP manage ----------

    @Test fun mcpToggleUsesPutWithEnabledOnly() = runBlocking {
        val result = api(200, """{"name":"files","enabled":false}""").setMcpEnabled("files", false)
        assertEquals("files" to false, result.name to result.enabled)
        val request = hits.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/profiles/profile1/desktop/mcp/files/enabled", request.url.encodedPath)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("enabled"), body.keys)
        assertFalse(body.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun mcpNamesThatCouldEscapeThePathAreRefusedBeforeARequest() {
        listOf("../x", "a/b", "", ".hidden", "a b", "x".repeat(65)).forEach { name ->
            assertEquals(name, DesktopError.InvalidRequest, failure(api()) { setMcpEnabled(name, true) })
            assertEquals(name, DesktopError.InvalidRequest, failure(api()) { testMcp(name) })
        }
        assertTrue(hits.isEmpty())
    }

    @Test fun mcpToggleAnswerForAnotherNameIsInvalid() {
        assertEquals(DesktopError.InvalidResponse, failure(api(200, """{"name":"other","enabled":true}""")) { setMcpEnabled("files", true) })
    }

    @Test fun mcpTestPostsAnEmptyBodyAndParsesOkAndFailure() = runBlocking {
        val ok = api(200, """{"name":"files","ok":true,"tools":[{"name":"read","description":"Reads"},{"name":"list"}],"prompts":1}""").testMcp("files")
        assertEquals("POST", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/mcp/files/test", hits.single().url.encodedPath)
        assertEquals("", bodies.single())
        assertTrue(ok.ok); assertEquals(2, ok.tools.size); assertEquals(1, ok.prompts); assertNull(ok.error)
        val bad = api(200, """{"name":"files","ok":false,"tools":[],"error":"connection refused"}""").testMcp("files")
        assertFalse(bad.ok); assertEquals("connection refused", bad.error)
    }

    @Test fun mcpCatalogParsesInstallableAndNotInstallable() = runBlocking {
        val entries = api(200, """{"entries":[
            {"id":"notes","name":"Notes","description":"Local notes","installed":false,"enabled":false,"installable":true},
            {"id":"mail","name":"Mail","installed":false,"enabled":false,"installable":false},
            {"id":"fs","name":"Files","installed":true,"enabled":true,"installable":false}]}""").mcpCatalog()
        assertEquals("/v1/profiles/profile1/desktop/mcp/catalog", hits.single().url.encodedPath)
        assertEquals(listOf(true, false, false), entries.map { it.installable })
        assertEquals(listOf(false, false, true), entries.map { it.installed })
        assertNull(entries[1].description)
    }

    @Test fun mcpInstallSendsOnlyTheIdAndChecksTheReceipt() = runBlocking {
        api(200, """{"id":"notes","state":"installed"}""").installMcp("notes")
        assertEquals("POST", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/mcp/catalog/install", hits.single().url.encodedPath)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("id"), body.keys)
        assertEquals("notes", body.getValue("id").jsonPrimitive.content)
        assertEquals(DesktopError.InvalidResponse, failure(api(200, """{"id":"notes","state":"started"}""")) { installMcp("notes") })
        assertEquals(DesktopError.InvalidResponse, failure(api(200, """{"id":"mail","state":"installed"}""")) { installMcp("notes") })
    }

    @Test fun mcpRateLimitsHaveTheirOwnMessageOtherRoutesKeepTheGenericOne() {
        val limited = """{"error":"desktop_rate_limited"}"""
        assertEquals(DesktopError.McpRateLimited, failure(api(429, limited)) { testMcp("files") })
        assertEquals(DesktopError.McpRateLimited, failure(api(429, limited)) { installMcp("notes") })
        assertEquals(DesktopError.McpRateLimited, failure(api(429, limited)) { mcpCatalog() })
        assertEquals(DesktopError.McpRateLimited, failure(api(429, limited)) { setMcpEnabled("files", true) })
        assertEquals(DesktopError.RateLimited, failure(api(429, limited)) { skills() })
        assertNotEquals(DesktopError.RateLimited.message, DesktopError.McpRateLimited.message)
    }

    @Test fun mcpOlderBridgeIsTheUpdateBridgeMessage() {
        assertEquals(DesktopError.NotFound, failure(api(404, """{"error":"Route not found"}""")) { mcpCatalog() })
    }

    // ---------- /v1/status ----------

    private val statusBody = """{"bridge_version":"0.2.6","hermes_home_found":true,
        "profiles":[{"id":"default","api_key_configured":true,"gateway_reachable":false}],
        "hermes":{"version":"0.22.0","active_agents":1},"dashboard_found":false,"dashboard_check":"timeout",
        "approvals_listener":true,"urls":{"lan":1,"tailscale":0,"public":2}}"""

    @Test fun statusReadsTheBridgeLevelRouteAndParsesEveryField() = runBlocking {
        val status = HttpBridgeStatusApi(client(200, statusBody)).status()
        assertEquals("GET", hits.single().method)
        assertEquals("/v1/status", hits.single().url.encodedPath)
        assertEquals("0.2.6", status.bridgeVersion); assertTrue(status.hermesHomeFound)
        assertEquals(false, status.profiles.single().gatewayReachable)
        assertEquals("0.22.0", status.hermes?.version); assertEquals(1, status.hermes?.activeAgents)
        assertTrue(status.dashboardTimedOut)
        assertEquals(Triple(1, 0, 2), status.urls?.let { Triple(it.lan, it.tailscale, it.publicUrls) })
    }

    @Test fun statusKeepsUnknownHermesFieldsNull() = runBlocking {
        val status = HttpBridgeStatusApi(client(200, """{"bridge_version":"0.2.6","hermes":{"version":null,"active_agents":null},
            "dashboard_found":true,"dashboard_check":"ok"}""")).status()
        assertNull(status.hermes?.version); assertNull(status.hermes?.activeAgents)
        assertFalse(status.dashboardTimedOut)
    }

    @Test fun statusErrorsAreSpecific() {
        fun statusFailure(code: Int, body: String): DesktopError = try {
            runBlocking { HttpBridgeStatusApi(client(code, body)).status() }
            error("expected the call to fail")
        } catch (e: DesktopException) { e.error }
        assertEquals(DesktopError.NotFound, statusFailure(404, """{"error":"Route not found"}"""))
        assertEquals(DesktopError.DeviceRevoked, statusFailure(403, "{}"))
        assertEquals(DesktopError.RateLimited, statusFailure(429, "{}"))
        assertEquals(DesktopError.InvalidResponse, statusFailure(200, "[]"))
    }
}
