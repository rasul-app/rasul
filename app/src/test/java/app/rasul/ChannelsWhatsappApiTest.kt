package app.rasul

import app.rasul.bridge.BridgeChannelsApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.WhatsappMode
import app.rasul.bridge.channelException
import app.rasul.bridge.whatsappError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

/** Request shapes, decoding and error mapping of the WhatsApp setup and channel settings routes. */
class ChannelsWhatsappApiTest {
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

    private fun failure(status: Int, body: String, call: suspend BridgeChannelsApi.() -> Unit): ChannelException =
        assertThrows(ChannelException::class.java) { runBlocking { api(status, body).call() } }

    private val pairing = "wa_pairing_0001"
    private val base = "/v1/profiles/profile1/desktop/channels"

    // ---------- setup ----------

    @Test fun startPostsModeAndNumbers() = runBlocking {
        val answer = """{"pairing_id":"$pairing","status":"installing"}"""
        val start = api(200, answer).startWhatsappSetup(WhatsappMode.SelfChat, listOf("+10000000000", "10000000001", "+10000000000"))
        assertEquals(pairing, start.pairingId)
        assertEquals("installing", start.status)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("mode", "allowed_users"), body.keys)
        assertEquals("self-chat", body.getValue("mode").jsonPrimitive.content)
        assertEquals("+10000000000,10000000001", body.getValue("allowed_users").jsonPrimitive.content)
        assertEquals("POST", hits.single().method)
        assertEquals("$base/whatsapp/setup", hits.single().url.encodedPath)
    }

    @Test fun startWithoutNumbersSendsOnlyTheMode() = runBlocking {
        api(200, """{"pairing_id":"$pairing","status":"starting"}""").startWhatsappSetup(WhatsappMode.Bot, emptyList())
        assertEquals(setOf("mode"), Json.parseToJsonElement(bodies.single()).jsonObject.keys)
        assertEquals("bot", Json.parseToJsonElement(bodies.single()).jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test fun badNumbersNeverLeaveThePhone() {
        val bad = listOf(listOf("12a"), listOf("+"), listOf("1".repeat(21)), listOf("1 2"), (1..21).map { "1000000000$it" })
        bad.forEach { numbers ->
            val error = assertThrows(ChannelException::class.java) { runBlocking { api().startWhatsappSetup(WhatsappMode.Bot, numbers) } }
            assertEquals(ChannelError.Invalid, error.error)
        }
        assertTrue(hits.isEmpty())
    }

    @Test fun statusReadsTheSetupAndKeepsTheQrOnlyWhileWaiting() = runBlocking {
        val waiting = api(200, """{"pairing_id":"$pairing","status":"waiting","qr_payload":"2@abc,def","expires_at":"2026-10-18T10:00:00+00:00"}""")
            .whatsappSetupStatus(pairing)
        assertEquals("2@abc,def", waiting.qrPayload)
        assertEquals("2026-10-18T10:00:00+00:00", waiting.expiresAt)
        assertFalse(waiting.terminal)
        assertEquals("GET", hits[0].method)
        assertEquals("$base/whatsapp/setup/$pairing", hits[0].url.encodedPath)
        val connected = api(200, """{"pairing_id":"$pairing","status":"connected","qr_payload":"2@abc","account_name":" Work phone ","account_phone_last4":"0000"}""")
            .whatsappSetupStatus(pairing)
        assertNull(connected.qrPayload)
        assertEquals("Work phone", connected.accountName)
        assertEquals("0000", connected.accountPhoneLast4)
        assertTrue(connected.terminal)
    }

    @Test fun anOddQrOrLast4IsDropped() = runBlocking {
        val odd = api(200, """{"pairing_id":"$pairing","status":"waiting","qr_payload":"café","account_phone_last4":"12345"}""")
            .whatsappSetupStatus(pairing)
        assertNull(odd.qrPayload)
        assertNull(odd.accountPhoneLast4)
        val long = api(200, """{"pairing_id":"$pairing","status":"waiting","qr_payload":"${"a".repeat(1025)}"}""").whatsappSetupStatus(pairing)
        assertNull(long.qrPayload)
        val control = api(200, """{"pairing_id":"$pairing","status":"waiting","qr_payload":"a\nb"}""").whatsappSetupStatus(pairing)
        assertNull(control.qrPayload)
        val edge = api(200, """{"pairing_id":"$pairing","status":"waiting","qr_payload":"${"a".repeat(1024)}"}""").whatsappSetupStatus(pairing)
        assertEquals(1024, edge.qrPayload!!.length)
    }

    @Test fun anUnknownStatusOrAnotherPairingIsRejected() {
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"pairing_id":"$pairing","status":"done"}""") { whatsappSetupStatus(pairing) }.error)
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"pairing_id":"wa_pairing_0002","status":"waiting"}""") { whatsappSetupStatus(pairing) }.error)
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"pairing_id":"../x","status":"waiting"}""") { startWhatsappSetup(WhatsappMode.Bot, emptyList()) }.error)
    }

    @Test fun everyStatusIsAccepted() = runBlocking {
        listOf("installing", "starting", "waiting", "connected", "error", "cancelled").forEach { status ->
            assertEquals(status, api(200, """{"pairing_id":"$pairing","status":"$status"}""").whatsappSetupStatus(pairing).status)
        }
    }

    @Test fun errorCodesMapToTheirOwnError() {
        assertEquals(ChannelError.WhatsappBridgeMissing, whatsappError("whatsapp_bridge_missing"))
        assertEquals(ChannelError.WhatsappInstallFailed, whatsappError("whatsapp_install_failed"))
        assertEquals(ChannelError.WhatsappTimeout, whatsappError("whatsapp_timeout"))
        assertEquals(ChannelError.WhatsappLoggedOut, whatsappError("whatsapp_logged_out"))
        assertEquals(ChannelError.WhatsappSetupFailed, whatsappError("whatsapp_setup_failed"))
        assertEquals(ChannelError.WhatsappSetupFailed, whatsappError("anything else"))
        assertEquals(ChannelError.WhatsappSetupFailed, whatsappError(null))
    }

    @Test fun applyPostsForceOnly() = runBlocking {
        val result = api(200, """{"ok":true,"restart_started":true,"needs_restart":false}""").applyWhatsappSetup(pairing, true)
        assertTrue(result.restartStarted)
        assertFalse(result.needsRestart)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("force"), body.keys)
        assertEquals("true", body.getValue("force").jsonPrimitive.content)
        assertEquals("$base/whatsapp/setup/$pairing/apply", hits.single().url.encodedPath)
    }

    @Test fun applyThatSaysNotOkIsRejected() {
        assertEquals(ChannelError.InvalidResponse, failure(200, """{"ok":false}""") { applyWhatsappSetup(pairing, false) }.error)
    }

    @Test fun busyApplyCarriesTheAgentCount() {
        val error = failure(409, """{"error":"gateway_busy","active_agents":3}""") { applyWhatsappSetup(pairing, false) }
        assertEquals(ChannelError.Busy, error.error)
        assertEquals(3, error.activeAgents)
    }

    @Test fun cancelDeletesTheSetup() = runBlocking {
        api(200, """{"ok":true}""").cancelWhatsappSetup(pairing)
        assertEquals("DELETE", hits.single().method)
        assertEquals("$base/whatsapp/setup/$pairing", hits.single().url.encodedPath)
    }

    @Test fun anUnsafePairingNeverReachesAPath() {
        listOf<suspend BridgeChannelsApi.() -> Unit>(
            { whatsappSetupStatus("../x") }, { applyWhatsappSetup("short", false) }, { cancelWhatsappSetup("a/b/c/d/e/f/g/h") },
        ).forEach { call ->
            assertEquals(ChannelError.Invalid, assertThrows(ChannelException::class.java) { runBlocking { api().call() } }.error)
        }
        assertTrue(hits.isEmpty())
    }

    @Test fun everySetupTokenHasItsOwnError() {
        val cases = listOf(
            404 to ("channel_setup_not_found" to ChannelError.SetupNotFound),
            409 to ("channel_setup_not_ready" to ChannelError.SetupNotReady),
            410 to ("channel_setup_expired" to ChannelError.SetupExpired),
            409 to ("gateway_busy" to ChannelError.Busy),
            404 to ("channel_not_found" to ChannelError.ChannelNotFound),
            400 to ("channel_unknown_setting" to ChannelError.SettingUnknown),
            400 to ("channel_setting_invalid" to ChannelError.SettingInvalid),
            400 to ("channel_setting_redacted" to ChannelError.SettingRedacted),
            400 to ("whatsapp_bridge_missing" to ChannelError.WhatsappBridgeMissing),
            400 to ("whatsapp_install_failed" to ChannelError.WhatsappInstallFailed),
            400 to ("whatsapp_timeout" to ChannelError.WhatsappTimeout),
            400 to ("whatsapp_logged_out" to ChannelError.WhatsappLoggedOut),
            400 to ("whatsapp_setup_failed" to ChannelError.WhatsappSetupFailed),
        )
        cases.forEach { (status, pair) ->
            assertEquals(pair.first, pair.second, channelException(status, """{"error":"${pair.first}"}""").error)
        }
    }

    @Test fun notReadyKeepsTheFixedMessage() {
        val error = channelException(409, """{"error":"channel_setup_not_ready","message":"Scan the code first."}""")
        assertEquals(ChannelError.SetupNotReady, error.error)
        assertEquals("Scan the code first.", error.detail)
    }

    // ---------- settings ----------

    private val row = """{"id":"discord","name":"Discord","enabled":true,"configured":true,"needs_restart":true,
        "fields":[{"key":"BOT_TOKEN","required":true,"is_set":true,"is_list":false,"prompt":"Bot token"}]}"""

    @Test fun saveSendsOnlyTheValuesAndClearKeysGiven() = runBlocking {
        val saved = api(200, row).saveSettings("discord", mapOf("BOT_TOKEN" to "abc123"), listOf("ALLOWED_USERS"))
        assertTrue(saved.needsRestart)
        val request = hits.single()
        assertEquals("PUT", request.method)
        assertEquals("$base/discord/settings", request.url.encodedPath)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("values", "clear"), body.keys)
        assertEquals(mapOf("BOT_TOKEN" to "abc123"), body.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(listOf("ALLOWED_USERS"), body.getValue("clear").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aSaveWithOnlyValuesHasNoClearKey() = runBlocking {
        api(200, row).saveSettings("discord", mapOf("BOT_TOKEN" to "abc123"), emptyList())
        assertEquals(setOf("values"), Json.parseToJsonElement(bodies.single()).jsonObject.keys)
    }

    @Test fun saveRefusesWhatTheBridgeWouldRefuseBeforeAnyRequest() {
        fun refuse(values: Map<String, String>, clear: List<String>, expected: ChannelError) {
            val error = assertThrows(ChannelException::class.java) { runBlocking { api().saveSettings("discord", values, clear) } }
            assertEquals(expected, error.error)
        }
        refuse(emptyMap(), emptyList(), ChannelError.Invalid)
        refuse(mapOf("A" to "x"), listOf("A"), ChannelError.Invalid)
        refuse((1..9).associate { "K$it" to "v" }, emptyList(), ChannelError.Invalid)
        refuse(emptyMap(), (1..9).map { "K$it" }, ChannelError.Invalid)
        refuse(mapOf("bad key" to "x"), emptyList(), ChannelError.SettingUnknown)
        refuse(emptyMap(), listOf("lower"), ChannelError.SettingUnknown)
        refuse(mapOf("BOT_TOKEN" to "abc****def"), emptyList(), ChannelError.SettingRedacted)
        refuse(mapOf("BOT_TOKEN" to "a".repeat(4097)), emptyList(), ChannelError.SettingInvalid)
        refuse(mapOf("BOT_TOKEN" to "café"), emptyList(), ChannelError.SettingInvalid)
        refuse(mapOf("BOT_TOKEN" to "a\tb"), emptyList(), ChannelError.SettingInvalid)
        assertTrue(hits.isEmpty())
    }

    @Test fun saveAnswersForAnotherChannelAreRejected() {
        assertEquals(ChannelError.InvalidResponse, failure(200, row.replace("discord", "slack")) { saveSettings("discord", mapOf("BOT_TOKEN" to "x"), emptyList()) }.error)
    }

    @Test fun serverRefusalsOfASaveKeepTheirCode() {
        val unknown = failure(400, """{"error":"channel_unknown_setting"}""") { saveSettings("discord", mapOf("BOT_TOKEN" to "x"), emptyList()) }
        assertEquals(ChannelError.SettingUnknown, unknown.error)
        val gone = failure(404, """{"error":"channel_not_found"}""") { saveSettings("discord", mapOf("BOT_TOKEN" to "x"), emptyList()) }
        assertEquals(ChannelError.ChannelNotFound, gone.error)
    }
}
