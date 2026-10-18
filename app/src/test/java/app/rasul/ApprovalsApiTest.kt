package app.rasul

import app.rasul.bridge.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ApprovalsApiTest {
    private val id = "a".repeat(32)
    private val item = """{"request_id":"$id","session_key":"sk","profile":"sample","surface":"shell","command":"rclone move ./reports drive:backups","description":"Sync the reports folder.","allow_session":true,"allow_permanent":false,"expires_at":1900000000.0}"""
    private val listBody = """{"approvals":[$item]}"""

    private fun client(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun response(request: Request, code: Int, body: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("response")
            .body(body.toResponseBody()).build()

    @Test fun listAndAnswerUseTheExactContractRoutesAndShapes() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client { request ->
            seen += request
            if (request.method == "POST") {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val value = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(setOf("choice"), value.keys)
                assertEquals("session", value.getValue("choice").jsonPrimitive.content)
                response(request, 200, """{"request_id":"$id","state":"answered"}""")
            } else response(request, 200, listBody)
        }
        try {
            val api = BridgeApprovalsApi(http, "sample")
            val items = api.list()
            assertEquals("/v1/profiles/sample/approvals", seen[0].url.encodedPath)
            assertEquals(
                ApprovalItemDto(id, "sk", "sample", "shell", "rclone move ./reports drive:backups",
                    "Sync the reports folder.", true, false, 1900000000.0), items.single())
            val answered = api.answer(id, "session")
            assertEquals("/v1/profiles/sample/approvals/$id/answer", seen[1].url.encodedPath)
            assertEquals(ApprovalAnsweredDto(id, "answered"), answered)
            assertTrue(seen.all { it.header("Authorization") == null })
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun everyApprovalTokenMapsToItsSpecificMessage() {
        val values = mapOf(
            "approvals_invalid" to ApprovalsError.Invalid,
            "approvals_invalid_choice" to ApprovalsError.InvalidChoice,
            "approvals_unauthorized" to ApprovalsError.Unauthorized,
            "approvals_not_found" to ApprovalsError.NotFound,
            "approvals_conflict" to ApprovalsError.Conflict,
            "approvals_expired" to ApprovalsError.Expired,
            "approvals_rate_limited" to ApprovalsError.RateLimited,
            "Route not found" to ApprovalsError.Unsupported,
            "Registered device certificate required" to ApprovalsError.DeviceRevoked,
        )
        values.forEach { (token, expected) ->
            assertEquals(token, expected, approvalsError(400, """{"error":"$token"}"""))
        }
        assertEquals(ApprovalsError.Invalid, approvalsError(400, ""))
        assertEquals(ApprovalsError.Unauthorized, approvalsError(401, ""))
        assertEquals(ApprovalsError.DeviceRevoked, approvalsError(403, ""))
        assertEquals(ApprovalsError.NotFound, approvalsError(404, ""))
        assertEquals(ApprovalsError.Conflict, approvalsError(409, ""))
        assertEquals(ApprovalsError.Expired, approvalsError(410, ""))
        assertEquals(ApprovalsError.RateLimited, approvalsError(429, ""))
        assertEquals(ApprovalsError.InvalidResponse, approvalsError(418, "{}"))
    }

    @Test fun malformedPayloadsAndUnsafeIdsAreRejected() {
        val bad = listOf(
            "{}",
            """{"approvals":[{"request_id":"UPPER","session_key":"sk","profile":"sample","surface":"s","command":"c","description":"d","allow_session":true,"allow_permanent":true,"expires_at":1.0}]}""",
            """{"approvals":[{"request_id":"$id","session_key":"sk","profile":"Bad","surface":"s","command":"c","description":"d","allow_session":true,"allow_permanent":true,"expires_at":1.0}]}""",
            """{"approvals":[{"request_id":"$id","session_key":"sk","profile":"sample","surface":"s","command":"c","description":"d","allow_session":true,"allow_permanent":true,"expires_at":1e999}]}""",
        )
        bad.forEach { raw ->
            val failure = runCatching { decodeApprovalList(raw) }.exceptionOrNull()
            assertEquals(raw, ApprovalsError.InvalidResponse, (failure as ApprovalsException).error)
        }
        val http = OkHttpClient()
        try {
            for (badId in listOf("..", "default/..", "%2e%2e", "Default", " default")) {
                assertTrue(badId, runCatching { BridgeApprovalsApi(http, badId) }.isFailure)
            }
            val api = BridgeApprovalsApi(http, "default")
            runBlocking {
                assertTrue(runCatching { api.answer("Z".repeat(32), "once") }.isFailure)
                assertTrue(runCatching { api.answer(id, "bogus") }.isFailure)
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun sseSnapshotNewAndResolvedFramesParse() = runBlocking {
        val second = "b".repeat(32)
        val body = ": heartbeat\n\nevent: snapshot\ndata: $listBody\n\nevent: new\ndata: {\"profile\":\"sample\",\"request_id\":\"$second\"}\n\nevent: resolved\ndata: {\"profile\":\"sample\",\"request_id\":\"$id\"}\n\n"
        val http = client { request ->
            assertEquals("/v1/profiles/sample/approvals/events", request.url.encodedPath)
            response(request, 200, body)
        }
        try {
            val events = withTimeout(5000) { BridgeApprovalsApi(http, "sample").events().take(3).toList() }
            assertEquals(ApprovalsEvent.Snapshot(listOf(decodeApprovalList(listBody).single())), events[0])
            assertEquals(ApprovalsEvent.New(second), events[1])
            assertEquals(ApprovalsEvent.Resolved(id), events[2])
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun missingApprovalsRouteAndErrorBodiesSurfaceContractErrors() = runBlocking {
        val http = client { request ->
            if (request.url.encodedPath.endsWith("events")) response(request, 404, """{"error":"Route not found"}""")
            else response(request, 429, """{"error":"approvals_rate_limited"}""")
        }
        try {
            val api = BridgeApprovalsApi(http, "sample")
            val listFailure = runCatching { api.list() }.exceptionOrNull()
            assertEquals(ApprovalsError.RateLimited, (listFailure as ApprovalsException).error)
            val streamFailure = runCatching { withTimeout(5000) { api.events().toList() } }.exceptionOrNull()
            assertEquals(ApprovalsError.Unsupported, (streamFailure as ApprovalsException).error)
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun bridgeWideRoutesCarryEveryProfileAndNameThemInEvents() = runBlocking {
        val second = "b".repeat(32)
        val seen = mutableListOf<String>()
        val body = "event: snapshot\ndata: $listBody\n\nevent: new\ndata: {\"profile\":\"other\",\"request_id\":\"$second\"}\n\nevent: resolved\ndata: {\"profile\":\"sample\",\"request_id\":\"$id\"}\n\n"
        val http = client { request ->
            seen += request.url.encodedPath
            if (request.url.encodedPath.endsWith("events")) response(request, 200, body) else response(request, 200, listBody)
        }
        try {
            val api = BridgeWideApprovalsApi(http)
            assertEquals("sample", api.list().single().profile)
            val events = withTimeout(5000) { api.events().take(3).toList() }
            assertEquals(listOf("/v1/approvals", "/v1/approvals/events"), seen)
            assertEquals(ApprovalsEvent.New(second, "other"), events[1])
            assertEquals(ApprovalsEvent.Resolved(id, "sample"), events[2])
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun anOlderBridgeAnswers404OnTheBridgeWideRoutes() = runBlocking {
        val http = client { request -> response(request, 404, """{"error":"Route not found"}""") }
        try {
            val api = BridgeWideApprovalsApi(http)
            assertEquals(ApprovalsError.Unsupported, (runCatching { api.list() }.exceptionOrNull() as ApprovalsException).error)
            val stream = runCatching { withTimeout(5000) { api.events().toList() } }.exceptionOrNull()
            assertEquals(ApprovalsError.Unsupported, (stream as ApprovalsException).error)
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }
}
