package app.rasul

import app.rasul.bridge.BridgeChannelsApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelState
import app.rasul.bridge.channelException
import app.rasul.bridge.telegramOpenLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
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

/** Request shapes, decoding and error mapping of the /v1/profiles/{id}/desktop/channels routes. */
class ChannelsApiTest {
    private val hits = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    private fun api(status: Int = 200, body: String = "{}"): BridgeChannelsApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hits += request
            bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return BridgeChannelsApi(BridgeEndpoint(http, "profile1"))
    }

    private fun failure(status: Int, body: String, call: suspend BridgeChannelsApi.() -> Unit = { channels() }): ChannelException =
        assertThrows(ChannelException::class.java) { runBlocking { api(status, body).call() } }

    private val row = """{"id":"telegram","name":"Telegram","description":"Chat","enabled":true,"configured":true,
        "gateway_running":true,"state":"connected","error_code":null,"error_message":null,"home_channel_set":false,
        "fields":[{"key":"TOKEN","required":true,"is_set":true,"is_list":false,"prompt":"Bot token","extra":1}],"future":"x"}"""

    // ---------- decoding ----------

    @Test fun listDecodesAndIgnoresUnknownFields() = runBlocking {
        val list = api(200, """{"channels":[$row,{"id":"matrix","name":"Matrix","state":null,"fields":[]}],"more":true}""").channels()
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("telegram", first.id)
        assertTrue(first.enabled && first.configured && first.gatewayRunning)
        assertEquals(ChannelState.CONNECTED, first.state)
        assertEquals("TOKEN", first.fields.single().key)
        assertTrue(first.fields.single().isSet)
        assertNull(list[1].state)
        assertFalse(list[1].enabled)
        val request = hits.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/profiles/profile1/desktop/channels", request.url.encodedPath)
    }

    @Test fun listDropsRowsWithAnUnsafeId() = runBlocking {
        val list = api(200, """{"channels":[{"id":"../x","name":"Bad"},{"id":"ok_1","name":"Fine"}]}""").channels()
        assertEquals(listOf("ok_1"), list.map { it.id })
    }

    @Test fun unreadableBodyIsAnInvalidResponse() {
        assertEquals(ChannelError.InvalidResponse, failure(200, "not json").error)
    }

    // ---------- requests ----------

    @Test fun switchPutsOnlyEnabledAndReadsTheRowBack() = runBlocking {
        val result = api(200, row.replace("\"future\"", "\"needs_restart\":true,\"future\"")).setEnabled("telegram", false)
        assertTrue(result.needsRestart)
        val request = hits.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/enabled", request.url.encodedPath)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("enabled"), body.keys)
        assertFalse(body.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun anAnswerForAnotherChannelIsRejected() {
        val error = assertThrows(ChannelException::class.java) { runBlocking { api(200, row).setEnabled("discord", true) } }
        assertEquals(ChannelError.InvalidResponse, error.error)
    }

    @Test fun testPostsAndDecodesTheResult() = runBlocking {
        val result = api(200, """{"ok":false,"state":"startup_failed","message":"Bad token","auth_posture":"strict"}""").test("telegram")
        assertFalse(result.ok)
        assertEquals("Bad token", result.message)
        assertEquals("strict", result.authPosture)
        assertEquals("POST", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/test", hits.single().url.encodedPath)
    }

    @Test fun restartPostsTheForceFlag() = runBlocking {
        api(200, """{"ok":true}""").restart(true)
        api(200, """{"ok":true}""").restart(false)
        assertEquals("/v1/profiles/profile1/desktop/channels/restart", hits[0].url.encodedPath)
        assertTrue(Json.parseToJsonElement(bodies[0]).jsonObject.getValue("force").jsonPrimitive.boolean)
        assertFalse(Json.parseToJsonElement(bodies[1]).jsonObject.getValue("force").jsonPrimitive.boolean)
        assertEquals(setOf("force"), Json.parseToJsonElement(bodies[0]).jsonObject.keys)
    }

    @Test fun restartThatAnswersNotOkIsRejected() {
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"ok":false}""") { restart(false) }.error)
    }

    @Test fun setupStartSendsTheTrimmedNameOrNone() = runBlocking {
        val answer = """{"pairing_id":"pair_abcdef123456","deep_link":"https://t.me/bot?start=a","suggested_username":"bot","expires_at":"2026-10-18T10:00:00+00:00"}"""
        val start = api(200, answer).startTelegramSetup("  My bot  ")
        assertEquals("pair_abcdef123456", start.pairingId)
        assertEquals("bot", start.suggestedUsername)
        api(200, answer).startTelegramSetup("   ")
        api(200, answer).startTelegramSetup(null)
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/setup", hits[0].url.encodedPath)
        assertEquals("My bot", Json.parseToJsonElement(bodies[0]).jsonObject.getValue("bot_name").jsonPrimitive.content)
        assertEquals("{}", bodies[1])
        assertEquals("{}", bodies[2])
    }

    @Test fun aNameOverSixtyFourCharactersNeverLeavesThePhone() {
        val error = assertThrows(ChannelException::class.java) { runBlocking { api().startTelegramSetup("a".repeat(65)) } }
        assertEquals(ChannelError.Invalid, error.error)
        assertTrue(hits.isEmpty())
    }

    @Test fun setupStartWithAnUnsafePairingIdIsRejected() {
        val answer = """{"pairing_id":"../x","deep_link":"https://t.me/bot"}"""
        assertEquals(ChannelError.InvalidResponse, failure(200, answer) { startTelegramSetup(null) }.error)
    }

    @Test fun statusReadsTheSetupById() = runBlocking {
        val waiting = api(200, """{"status":"waiting","expires_at":"2026-10-18T10:00:00+00:00"}""").telegramSetupStatus("pair_abcdef123456")
        assertFalse(waiting.ready)
        val ready = api(200, """{"status":"ready","bot_username":"bot","owner_user_id":"123"}""").telegramSetupStatus("pair_abcdef123456")
        assertTrue(ready.ready)
        assertEquals("123", ready.ownerUserId)
        assertEquals("GET", hits[0].method)
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/setup/pair_abcdef123456", hits[0].url.encodedPath)
    }

    @Test fun anUnknownSetupStatusIsRejected() {
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"status":"done"}""") { telegramSetupStatus("pair_abcdef123456") }.error)
    }

    @Test fun applyPostsTheIdsAndForce() = runBlocking {
        val result = api(200, """{"ok":true,"bot_username":"bot","restart_started":false,"needs_restart":true}""")
            .applyTelegramSetup("pair_abcdef123456", listOf("123", "456"), false)
        assertTrue(result.needsRestart)
        assertFalse(result.restartStarted)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("allowed_user_ids", "force"), body.keys)
        assertEquals(listOf("123", "456"), body.getValue("allowed_user_ids").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body.getValue("allowed_user_ids").jsonArray.all { it.jsonPrimitive.isString })
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/setup/pair_abcdef123456/apply", hits.single().url.encodedPath)
    }

    @Test fun applyRefusesBadIdListsBeforeAnyRequest() {
        val bad = listOf(emptyList(), listOf("12a"), listOf("1".repeat(21)), (1..21).map { it.toString() })
        bad.forEach { ids ->
            val error = assertThrows(ChannelException::class.java) {
                runBlocking { api().applyTelegramSetup("pair_abcdef123456", ids, false) }
            }
            assertEquals(ChannelError.Invalid, error.error)
        }
        assertTrue(hits.isEmpty())
    }

    @Test fun cancelDeletesTheSetup() = runBlocking {
        api(200, """{"ok":true}""").cancelTelegramSetup("pair_abcdef123456")
        assertEquals("DELETE", hits.single().method)
        assertEquals("/v1/profiles/profile1/desktop/channels/telegram/setup/pair_abcdef123456", hits.single().url.encodedPath)
    }

    @Test fun unsafeIdsNeverReachAPath() {
        listOf<suspend BridgeChannelsApi.() -> Unit>(
            { setEnabled("../x", true) }, { test("A B") }, { telegramSetupStatus("short") }, { cancelTelegramSetup("a/b/c/d/e/f/g/h") },
        ).forEach { call ->
            assertEquals(ChannelError.Invalid, assertThrows(ChannelException::class.java) { runBlocking { api().call() } }.error)
        }
        assertTrue(hits.isEmpty())
    }

    // ---------- errors ----------

    @Test fun everyBridgeTokenHasItsOwnError() {
        val cases = mapOf(
            404 to ("channel_not_found" to ChannelError.ChannelNotFound),
            409 to ("channel_conflict" to ChannelError.Conflict),
            410 to ("channel_setup_expired" to ChannelError.SetupExpired),
            502 to ("channel_setup_bad_link" to ChannelError.BadLink),
        )
        cases.forEach { (status, pair) -> assertEquals(pair.second, channelException(status, """{"error":"${pair.first}"}""").error) }
        assertEquals(ChannelError.SetupNotFound, channelException(404, """{"error":"channel_setup_not_found"}""").error)
        assertEquals(ChannelError.SetupNotReady, channelException(409, """{"error":"channel_setup_not_ready"}""").error)
        assertEquals(ChannelError.Busy, channelException(409, """{"error":"gateway_busy","active_agents":2}""").error)
        assertEquals(ChannelError.Invalid, channelException(400, """{"error":"desktop_invalid"}""").error)
        assertEquals(ChannelError.RateLimited, channelException(429, """{"error":"desktop_rate_limited"}""").error)
        assertEquals(ChannelError.DesktopFailed, channelException(502, """{"error":"desktop_error"}""").error)
        assertEquals(ChannelError.Unavailable, channelException(503, """{"error":"desktop_unavailable"}""").error)
    }

    @Test fun aBare404MeansAnOlderBridgeAndIsNeverMistakenForAChannel() {
        assertEquals(ChannelError.OlderBridge, channelException(404, "").error)
        assertEquals(ChannelError.OlderBridge, channelException(404, """{"error":"Route not found"}""").error)
        assertEquals(ChannelError.OlderBridge, failure(404, "").error)
    }

    @Test fun statusFallbacksWhenTheBodyHasNoToken() {
        assertEquals(ChannelError.Forbidden, channelException(403, "").error)
        assertEquals(ChannelError.Forbidden, channelException(401, "").error)
        assertEquals(ChannelError.RateLimited, channelException(429, "<html>").error)
        assertEquals(ChannelError.SetupExpired, channelException(410, "").error)
        assertEquals(ChannelError.ProfileNotReady, channelException(503, "").error)
        assertEquals(ChannelError.DesktopFailed, channelException(502, "").error)
        assertEquals(ChannelError.InvalidResponse, channelException(418, "").error)
    }

    @Test fun conflictAndNotReadyCarryTheCleanedMessageAndBusyTheCount() {
        assertEquals("Token missing", channelException(409, """{"error":"channel_conflict","message":"  Token missing "}""").detail)
        assertEquals("Open the bot first", channelException(409, """{"error":"channel_setup_not_ready","message":"Open the bot first"}""").detail)
        assertNull(channelException(409, """{"error":"channel_conflict","message":""}""").detail)
        assertEquals("x".repeat(200), channelException(409, """{"error":"channel_conflict","message":"${"x".repeat(500)}"}""").detail)
        assertEquals(2, channelException(409, """{"error":"gateway_busy","active_agents":2}""").activeAgents)
        assertNull(channelException(409, """{"error":"gateway_busy","active_agents":null}""").activeAgents)
        assertNull(channelException(409, """{"error":"gateway_busy"}""").activeAgents)
        assertNull(channelException(409, """{"error":"gateway_busy","active_agents":-1}""").activeAgents)
    }

    @Test fun everyErrorHasItsOwnMessageResource() {
        val messages = ChannelError.entries.map { it.message }
        assertEquals(messages.size, messages.distinct().size)
        // The two refusals that ride a Hermes sentence also have a plain wording for when the sentence is empty.
        assertTrue(R.string.chan_err_conflict != R.string.chan_err_conflict_detail)
    }

    // ---------- deep link check ----------

    @Test fun onlyAnHttpsTelegramLinkMayBeOpened() {
        listOf("https://t.me/hermes_bot", "https://t.me/hermes_bot?start=abc_1-2", "https://telegram.me/hermes_bot")
            .forEach { assertEquals(it, telegramOpenLink(it)) }
    }

    @Test fun badLinksAreRefused() {
        listOf(
            null, "", "http://t.me/bot", "https://t.me", "https://t.me/", "https://evil.example/bot",
            "https://t.me.evil.example/bot", "https://user@t.me/bot", "https://user:pw@t.me/bot", "https://t.me:8443/bot",
            "https://t.me/bot#frag", "https://t.me/bot?start=a b", "https://t.me/bot?start=a%20b", "https://T.me/bot",
            "tg://resolve?domain=bot", "intent://t.me/bot#Intent;scheme=https;end", "javascript:alert(1)", "https://t.me/bot\n",
            " https://t.me/bot", "https://t.me/" + "a".repeat(65), "https://t.me/bot?" + "a".repeat(257), "https://t.me/../bot",
            "https://t.me/bot.exe", "file:///sdcard/x", "https://t.me/bot?start=a;b",
        ).forEach { assertNull("should be refused: $it", telegramOpenLink(it)) }
    }
}
