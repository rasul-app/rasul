package app.rasul

import app.rasul.bridge.BridgeDesktopApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.SkillToggleResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request shape, parsing and error-token mapping for the /v1/profiles/{id}/desktop/ routes. */
class DesktopApiTest {
    private val hits = mutableListOf<Request>()

    private fun api(status: Int = 200, body: String = "{}"): BridgeDesktopApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hits += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return BridgeDesktopApi(BridgeEndpoint(http, "profile1"))
    }

    private fun requestBody(request: Request): String =
        Buffer().also { request.body?.writeTo(it) }.readUtf8()

    private fun failureOf(client: BridgeDesktopApi, call: suspend BridgeDesktopApi.() -> Unit = { skills() }): DesktopError = try {
        runBlocking { client.call() }
        error("expected the call to fail")
    } catch (e: DesktopException) { e.error }

    @Test fun jobRunsUseTheDesktopRouteWithLimitAndParseFractionalTimes() = runBlocking {
        val client = api(200, """{"runs":[
            {"id":"r1","title":"nightly","started_at":1760000000,"ended_at":1760000120,"is_active":false,"preview":"done"},
            {"id":"r2","title":null,"started_at":1760000200.5,"ended_at":null,"is_active":true,"preview":null}]}""")
        val runs = client.jobRuns("job-1", 25)
        assertEquals(2, runs.size)
        assertEquals("r1", runs[0].id); assertFalse(runs[0].isActive)
        assertEquals(1760000120L, runs[0].endedEpoch()); assertEquals("done", runs[0].preview)
        assertTrue(runs[1].isActive); assertNull(runs[1].endedEpoch()); assertEquals(1760000200L, runs[1].startedEpoch())
        val request = hits.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/profiles/profile1/desktop/jobs/job-1/runs", request.url.encodedPath)
        assertEquals("25", request.url.queryParameter("limit"))
    }

    @Test fun jobRunsRejectUnsafeIdsBeforeAnyRequest() = runBlocking {
        val error = assertThrows(DesktopException::class.java) { runBlocking { api().jobRuns("../x") } }
        assertEquals(DesktopError.NotFound, error.error)
        assertTrue(hits.isEmpty())
    }

    @Test fun skillsParseEachSwitchState() = runBlocking {
        val client = api(200, """{"skills":[
            {"name":"notes","description":"reads notes","category":"research","enabled":true,"essential":true},
            {"name":"review","enabled":false}]}""")
        val skills = client.skills()
        assertEquals(2, skills.size)
        assertEquals("notes", skills[0].name); assertTrue(skills[0].enabled); assertTrue(skills[0].isEssential)
        assertFalse(skills[1].enabled); assertFalse(skills[1].isEssential); assertNull(skills[1].description)
        assertEquals("/v1/profiles/profile1/desktop/skills", hits.single().url.encodedPath)
    }

    @Test fun togglePutsTheExactPairAndParsesEffectiveState() = runBlocking {
        val client = api(200, """{"name":"review","enabled":false}""")
        assertEquals(SkillToggleResult("review", false), client.toggleSkill("review", false))
        val request = hits.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/profiles/profile1/desktop/skills/toggle", request.url.encodedPath)
        val body = Json.parseToJsonElement(requestBody(request)).jsonObject
        assertEquals(setOf("name", "enabled"), body.keys)
        assertEquals("review", body.getValue("name").jsonPrimitive.content)
        assertFalse(body.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun catalogParsesIdentifiersAndInstalledFlags() = runBlocking {
        val client = api(200, """{"skills":[
            {"identifier":"official/notes","name":"Notes","description":"d","installed":true},
            {"identifier":"official/dev/review","name":"Review","installed":false}]}""")
        val catalog = client.skillCatalog()
        assertEquals(listOf("official/notes", "official/dev/review"), catalog.map { it.identifier })
        assertTrue(catalog[0].installed); assertFalse(catalog[1].installed)
        assertEquals("/v1/profiles/profile1/desktop/skills/catalog", hits.single().url.encodedPath)
    }

    @Test fun installPostsOnlyTheIdentifierAndAcceptsThe202Receipt() = runBlocking {
        val client = api(202, """{"identifier":"official/notes","state":"started"}""")
        client.installSkill("official/notes")
        val request = hits.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/profiles/profile1/desktop/skills/install", request.url.encodedPath)
        val body = Json.parseToJsonElement(requestBody(request)).jsonObject
        assertEquals(setOf("identifier"), body.keys)
        assertEquals("official/notes", body.getValue("identifier").jsonPrimitive.content)
    }

    @Test fun installRefusesAnythingButAnOfficialCatalogIdentifier() = runBlocking {
        listOf("notes", "official", "custom/notes", "https://example.invalid/x", "official/../x", "official//x", "official/x y")
            .forEach { bad ->
                val error = assertThrows(DesktopException::class.java) { runBlocking { api().installSkill(bad) } }
                assertEquals(bad, DesktopError.InvalidRequest, error.error)
            }
        assertTrue(hits.isEmpty())
    }

    @Test fun installRejectsReceiptsThatDoNotEchoTheIdentifierAndStarted() = runBlocking {
        listOf(
            """{"identifier":"official/other","state":"started"}""",
            """{"identifier":"official/notes","state":"done"}""",
            """{"identifier":"official/notes"}""",
        ).forEach { body ->
            val error = assertThrows(DesktopException::class.java) {
                runBlocking { api(202, body).installSkill("official/notes") }
            }
            assertEquals(body, DesktopError.InvalidResponse, error.error)
        }
    }

    @Test fun mcpParsesServers() = runBlocking {
        val client = api(200, """{"servers":[
            {"name":"files","transport":"stdio","enabled":true,"source":"config","plugin":null},
            {"name":"web","transport":"http","enabled":false,"source":"plugin","plugin":"web-tools"}]}""")
        val servers = client.mcpServers()
        assertEquals(2, servers.size)
        assertEquals("files", servers[0].name); assertEquals("stdio", servers[0].transport); assertEquals(true, servers[0].enabled)
        assertEquals("web-tools", servers[1].plugin); assertEquals(false, servers[1].enabled)
        assertEquals("/v1/profiles/profile1/desktop/mcp", hits.single().url.encodedPath)
    }

    @Test fun usageParsesDailyTotalsAndPeriod() = runBlocking {
        val client = api(200, """{"daily":[{"day":"2025-01-01","input_tokens":1200,"output_tokens":300,
            "cache_read_tokens":50,"reasoning_tokens":10,"estimated_cost":0.5,"sessions":2,"api_calls":9}],
            "totals":{"input_tokens":1200,"output_tokens":300,"estimated_cost":0.5,"sessions":2,"api_calls":9},
            "period_days":7}""")
        val report = client.usage(30)
        assertEquals(7, report.periodDays)
        assertEquals("2025-01-01", report.daily.single().day)
        assertEquals(0.5, report.totals?.estimatedCost)
        assertEquals("30", hits.single().url.queryParameter("days"))
    }

    /** Only the desktop token may map a 503 to "open Hermes"; every other 503 stays bot-readiness. */
    @Test fun desktopUnavailableOnlyWhenTheBodyCarriesTheToken() {
        assertEquals(DesktopError.Unavailable, failureOf(api(503, """{"error":"desktop_unavailable"}""")))
        assertEquals(DesktopError.ProfileNotReady, failureOf(api(503, """{"error":"profile_not_ready"}""")))
        assertEquals(DesktopError.ProfileNotReady, failureOf(api(503, "")))
        assertEquals(DesktopError.DesktopFailed, failureOf(api(502, """{"error":"desktop_error"}""")))
        assertEquals(DesktopError.RateLimited, failureOf(api(429, """{"error":"desktop_rate_limited"}""")))
        assertEquals(DesktopError.InvalidRequest, failureOf(api(400, """{"error":"desktop_invalid"}""")))
        assertEquals(DesktopError.DeviceRevoked, failureOf(api(403, "{}")))
        assertEquals(DesktopError.NotFound, failureOf(api(404, "{}")))
        assertEquals(DesktopError.InvalidResponse, failureOf(api(200, "not json")))
    }
}
