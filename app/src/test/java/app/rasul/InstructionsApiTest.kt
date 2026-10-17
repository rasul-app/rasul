package app.rasul

import app.rasul.bridge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class InstructionsApiTest {
    @Test fun requestsAndFixturesUseTheExactBridgeContract() = runBlocking {
        val original = "Original\r\nأجب بوضوح.\n"
        val edited = "Changed\n"
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            assertEquals("/v1/profiles/sample/instructions", request.url.encodedPath)
            assertNull(request.url.query)
            assertNull(request.header("Authorization"))
            val body = if (request.method == "GET") {
                buildJsonObject { put("text", original); put("revision", instructionsRevision(original)) }.toString()
            } else {
                assertEquals("PUT", request.method)
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val value = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(setOf("text", "revision"), value.keys)
                assertEquals(edited, value.getValue("text").jsonPrimitive.content)
                assertEquals(instructionsRevision(original), value.getValue("revision").jsonPrimitive.content)
                """{"revision":"${instructionsRevision(edited)}"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("response")
                .body(body.toResponseBody()).build()
        }.build()
        try {
            val api = BridgeInstructionsApi(client, "sample")
            assertEquals(InstructionsDocument(original, instructionsRevision(original)), api.load())
            assertEquals(instructionsRevision(edited), api.save(InstructionsDocument(edited, instructionsRevision(original))))
            assertEquals(listOf("GET", "PUT"), seen.map { it.method })
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun everyReachableServerTokenMapsToItsSpecificMessage() {
        val values = mapOf(
            "instructions_conflict" to InstructionsError.Conflict,
            "instructions_unsafe_path" to InstructionsError.UnsafePath,
            "instructions_too_large" to InstructionsError.TooLarge,
            "instructions_not_utf8" to InstructionsError.NotUtf8,
            "instructions_invalid" to InstructionsError.InvalidRequest,
            "instructions_profile_missing" to InstructionsError.ProfileMissing,
            "instructions_rate_limited" to InstructionsError.RateLimited,
            "instructions_unavailable" to InstructionsError.Unavailable,
            "Invalid request" to InstructionsError.InvalidRequest,
            "Registered device certificate required" to InstructionsError.DeviceRevoked,
            "Route not found" to InstructionsError.Unsupported,
            "Headers too large" to InstructionsError.HeadersTooLarge,
            "Invalid header" to InstructionsError.InvalidHeader,
            "Duplicate header" to InstructionsError.DuplicateHeader,
            "Unsupported framing" to InstructionsError.UnsupportedFraming,
            "Invalid content length" to InstructionsError.InvalidLength,
            "Request body too large" to InstructionsError.BodyTooLarge,
            "Upstream unavailable" to InstructionsError.Unreachable,
        )
        values.forEach { (token, expected) ->
            assertEquals(token, expected, instructionsError(400, """{"error":"$token"}"""))
        }
        assertEquals(InstructionsError.Conflict, instructionsError(409, ""))
        assertEquals(InstructionsError.InvalidResponse, instructionsError(418, "{}"))
    }
    @Test fun staleSaveReturnsConflictAndMalformedResponsesAreRejected() = runBlocking {
        for ((status, body, expected) in listOf(
            Triple(409, """{"error":"instructions_conflict"}""", InstructionsError.Conflict),
            Triple(200, "{}", InstructionsError.InvalidResponse),
            Triple(200, """{"text":"ok","revision":"${"0".repeat(64)}"}""", InstructionsError.InvalidResponse),
        )) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("response")
                    .body(body.toResponseBody()).build()
            }.build()
            try {
                val api = BridgeInstructionsApi(client, "default")
                val failure = runCatching { if (status == 409) api.save(InstructionsDocument("edit", instructionsRevision("old"))) else api.load() }.exceptionOrNull()
                assertEquals(expected, (failure as InstructionsException).error)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun profileIdMustMatchTheWholeCanonicalString() {
        val client = OkHttpClient()
        try {
            for (id in listOf("..", "default/..", "%2e%2e", "Default", " default", "default\n")) {
                assertTrue(id, runCatching { BridgeInstructionsApi(client, id) }.isFailure)
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
