package app.rasul

import app.rasul.bridge.BridgeDesktopApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.LogFile
import app.rasul.bridge.LogLevel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** Request shape, parsing and error mapping of GET logs (docs/bridge-desktop.md "Logs"). */
class LogsApiTest {
    private val hits = mutableListOf<Request>()

    private fun api(status: Int = 200, body: String = "{}"): BridgeDesktopApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            hits += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return BridgeDesktopApi(BridgeEndpoint(http, "profile1"))
    }

    private fun failure(api: BridgeDesktopApi): DesktopError = try {
        runBlocking { api.logs(LogFile.Errors) }
        error("expected the call to fail")
    } catch (e: DesktopException) { e.error }

    @Test fun readsTheLinesWithFileLinesAndLevelInTheQuery() = runBlocking {
        val answer = api(200, """{"file":"agent","lines":["a ERROR x","b INFO y"]}""").logs(LogFile.Agent, 50, LogLevel.Warning)
        assertEquals("agent", answer.file)
        assertEquals(listOf("a ERROR x", "b INFO y"), answer.lines)
        val url = hits.single().url
        assertEquals("GET", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/logs", url.encodedPath)
        assertEquals("agent", url.queryParameter("file"))
        assertEquals("50", url.queryParameter("lines"))
        assertEquals("WARNING", url.queryParameter("level"))
    }

    @Test fun defaultsAskForTheErrorsFileWithTwoHundredLinesOfAnyLevel() = runBlocking {
        api(200, """{"file":"errors","lines":[]}""").logs(LogFile.Errors)
        val url = hits.single().url
        assertEquals("errors", url.queryParameter("file"))
        assertEquals("200", url.queryParameter("lines"))
        assertEquals("ALL", url.queryParameter("level"))
    }

    @Test fun lineCountStaysInsideTheContractRange() = runBlocking {
        val client = api(200, """{"file":"errors","lines":[]}""")
        client.logs(LogFile.Errors, 0, LogLevel.All)
        client.logs(LogFile.Errors, 999, LogLevel.All)
        assertEquals(listOf("1", "200"), hits.map { it.url.queryParameter("lines") })
    }

    @Test fun everyFileAndLevelHasItsQueryValue() = runBlocking {
        val client = api(200, """{"file":"x","lines":[]}""")
        LogFile.values().forEach { client.logs(it, 10, LogLevel.All) }
        LogLevel.values().forEach { client.logs(LogFile.Errors, 10, it) }
        assertEquals(listOf("errors", "agent", "gateway"), hits.take(3).map { it.url.queryParameter("file") })
        assertEquals(listOf("ALL", "ERROR", "WARNING", "INFO"), hits.drop(3).map { it.url.queryParameter("level") })
    }

    @Test fun errorCodesMapToTheirOwnMessages() {
        assertEquals(DesktopError.InvalidRequest, failure(api(400, """{"error":"desktop_invalid"}""")))
        assertEquals(DesktopError.LogsNotFound, failure(api(404, """{"error":"logs_not_found"}""")))
        assertEquals(DesktopError.DesktopFailed, failure(api(502, """{"error":"desktop_error"}""")))
        assertEquals(DesktopError.RateLimited, failure(api(429, """{"error":"desktop_rate_limited"}""")))
        assertEquals(DesktopError.RateLimited, failure(api(429, "")))
    }

    @Test fun aPlain404OnAnOlderBridgeStaysTheUpdateBridgeMessage() {
        assertEquals(DesktopError.NotFound, failure(api(404, "")))
    }

    @Test fun malformedAnswerIsAnInvalidResponse() {
        assertEquals(DesktopError.InvalidResponse, failure(api(200, "not json")))
    }
}
