package app.rasul

import app.rasul.bridge.BridgeEndpoint
import app.rasul.data.BusyChatsResult
import app.rasul.data.BusyWhere
import app.rasul.data.HermesTarget
import app.rasul.data.busyApi
import app.rasul.data.parseBusyChats
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusyChatsTest {

    @Test
    fun parsesDesktopAndAppRowsWithEverySessionId() {
        val map = parseBusyChats(
            """[{"session_ids":["root-a","child-a"],"where":"desktop"},{"session_ids":["root-b"],"where":"app"}]""",
        )
        assertEquals(
            mapOf("root-a" to BusyWhere.Desktop, "child-a" to BusyWhere.Desktop, "root-b" to BusyWhere.App),
            map,
        )
    }

    @Test
    fun emptyAnswerIsAnEmptyMap() {
        assertEquals(emptyMap<String, BusyWhere>(), parseBusyChats("[]"))
    }

    @Test
    fun rowsWithoutAKnownWhereOrIdsAreSkipped() {
        val map = parseBusyChats(
            """[{"session_ids":["a"],"where":"somewhere"},{"where":"desktop"},{"session_ids":"not-an-array","where":"app"},{"session_ids":[],"where":"app"}]""",
        )
        assertEquals(emptyMap<String, BusyWhere>(), map)
    }

    @Test
    fun aNonArrayOrBrokenAnswerIsMalformed() {
        assertNull(parseBusyChats("""{"session_ids":["a"],"where":"desktop"}"""))
        assertNull(parseBusyChats("not json at all"))
        assertNull(parseBusyChats(""))
    }

    @Test
    fun aBlankOrMissingIdInsideTheListIsSkipped() {
        val map = parseBusyChats("""[{"session_ids":["a","","  ",3],"where":"app"}]""")
        assertEquals(mapOf("a" to BusyWhere.App), map)
    }

    @Test
    fun directTargetHasNoBusyApi() {
        assertNull(HermesTarget("http://192.0.2.7/", null, "key").busyApi())
    }

    @Test
    fun routeAnswers200404AndErrors() = runBlocking {
        var code = 200
        var payload = """[{"session_ids":["s1","s2"],"where":"desktop"}]"""
        val seen = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("m").body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = HermesTarget("http://192.0.2.7/", null, "key", BridgeEndpoint(http, "home")).busyApi()!!

        assertEquals(
            BusyChatsResult.Ok(mapOf("s1" to BusyWhere.Desktop, "s2" to BusyWhere.Desktop)),
            api.list(),
        )
        assertEquals("/v1/profiles/home/turns", seen.single().url.encodedPath)

        code = 404
        assertEquals(BusyChatsResult.Unsupported, api.list())

        code = 503
        assertEquals(BusyChatsResult.Failed, api.list())

        code = 200
        payload = "{broken"
        assertEquals(BusyChatsResult.Failed, api.list())
    }
}
