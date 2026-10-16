package app.rasul

import app.rasul.bridge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CreateBotApiTest {
    @Test fun templatesMatchTheConfiguredBlankTemplateAndRejectMalformedFields() {
        assertEquals(listOf(ProfileTemplate("blank", "Blank")), parseTemplates("""[{"id":"blank","label":"Blank"}]"""))
        for (body in listOf("{}", "[{}]", """[{"id":"blank"}]""", """[{"id":"blank","label":4}]""",
            """[{"id":"../blank","label":"Blank"}]""", """[{"id":"blank","label":"Blank"},{"id":"blank","label":"Blank"}]""")) {
            assertTrue(runCatching { parseTemplates(body) }.isFailure)
        }
    }
    @Test fun nameRuleUsesTheEntireUnmodifiedStringIncludingReservedName() {
        listOf("aa", "ab-12", "--", "--clone-all", "a".repeat(32)).forEach { assertTrue(it, validBotName(it)) }
        listOf("", "a", "a".repeat(33), "default", "Aa", " aa", "aa ", "aa\n", "aa_bb", "aa/bb", "مساعد", "aa.bb").forEach { assertFalse(it, validBotName(it)) }
    }
    @Test fun requestRoutesFieldsAndOperationResponsesFollowTheBridge() = runBlocking {
        val operationId = UUID.randomUUID().toString().replace("-", "")
        val request = CreateBotRequest(UUID.randomUUID().toString(), "new-profile", "blank")
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val received = chain.request(); seen += received
            assertNull(received.header("Authorization"))
            val text = when (received.url.encodedPath) {
                "/v1/profile-templates" -> """[{"id":"blank","label":"Blank"}]"""
                "/v1/profiles" -> {
                    assertEquals("POST", received.method)
                    val buffer = okio.Buffer(); received.body!!.writeTo(buffer)
                    val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    assertEquals(setOf("request_id", "name", "template"), json.keys)
                    assertEquals(request.requestId, json.getValue("request_id").jsonPrimitive.content)
                    assertEquals(request.name, json.getValue("name").jsonPrimitive.content)
                    assertEquals(request.template, json.getValue("template").jsonPrimitive.content)
                    """{"operation_id":"$operationId"}"""
                }
                "/v1/operations/$operationId" -> """{"state":"done","profile_id":"new-profile"}"""
                else -> error("unexpected route")
            }
            Response.Builder().request(received).protocol(Protocol.HTTP_1_1).code(if (received.method == "POST") 202 else 200)
                .message("response").body(text.toResponseBody()).build()
        }.build()
        try {
            val api = BridgeCreateBotApi(client)
            assertEquals("blank", api.templates().single().id)
            assertEquals(operationId, api.create(request))
            assertEquals(CreateBotOperation("done", "new-profile"), api.operation(operationId))
            assertEquals(listOf("GET", "POST", "GET"), seen.map { it.method })
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun eachBridgeErrorTokenAndStatusMapsToASpecificError() {
        val cases = listOf(
            Triple(400, "Invalid profile request", CreateBotError.InvalidRequest),
            Triple(400, "A canonical request UUID is required", CreateBotError.InvalidRequestId),
            Triple(400, "Invalid profile name", CreateBotError.InvalidName),
            Triple(409, "Request UUID was used for a different operation", CreateBotError.RequestConflict),
            Triple(400, "Unknown template", CreateBotError.TemplateUnknown),
            Triple(400, "Template source is unavailable", CreateBotError.TemplateUnavailable),
            Triple(409, "Profile name already exists or is reserved", CreateBotError.NameTaken),
            Triple(404, "Unknown operation", CreateBotError.OperationUnknown),
            Triple(403, "Registered device certificate required", CreateBotError.DeviceRevoked),
            Triple(503, "Profile key needs local initialization", CreateBotError.NotReady),
            Triple(502, "Upstream unavailable", CreateBotError.Unreachable),
            Triple(404, "Route not found", CreateBotError.RouteMissing),
            Triple(431, "Headers too large", CreateBotError.HeadersTooLarge),
            Triple(400, "Invalid header", CreateBotError.InvalidHeader),
            Triple(400, "Duplicate header", CreateBotError.DuplicateHeader),
            Triple(400, "Invalid request", CreateBotError.InvalidRequest),
            Triple(400, "Unsupported framing", CreateBotError.UnsupportedFraming),
            Triple(400, "Invalid content length", CreateBotError.InvalidLength),
            Triple(413, "Request body too large", CreateBotError.BodyTooLarge),
        )
        cases.forEach { (status, token, error) -> assertEquals(token, error, createBotError(status, "{\"error\":\"$token\"}")) }
        assertEquals(CreateBotError.RateLimited, createBotError(429, ""))
        assertEquals(CreateBotError.InvalidResponse, createBotError(400, "{}"))
        assertEquals(CreateBotError.Unreachable, createBotError(502, ""))
    }
    @Test fun transportFailureHasTheUnreachableCreationMessage() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException() }.build()
        try {
            try { BridgeCreateBotApi(client).templates(); fail("expected transport error") }
            catch (e: CreateBotException) { assertEquals(CreateBotError.Unreachable, e.error) }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun operationParsingMatchesEveryBridgeStateAndRejectsMissingOrUnsafeValues() {
        assertEquals(CreateBotOperation("running"), parseOperation("""{"state":"running"}"""))
        assertEquals("new-profile", parseOperation("""{"state":"done","profile_id":"new-profile"}""").profileId)
        for (error in listOf("Profile creation failed; inspect locally", "Outcome uncertain; inspect profiles locally")) {
            assertEquals(error, parseOperation("{\"state\":\"failed\",\"error\":\"$error\"}").error)
        }
        for (body in listOf("{}", """{"state":"unknown"}""", """{"state":"done"}""", """{"state":"done","profile_id":"../aa"}""", """{"state":"failed"}""")) {
            assertTrue(runCatching { parseOperation(body) }.isFailure)
        }
    }
}
