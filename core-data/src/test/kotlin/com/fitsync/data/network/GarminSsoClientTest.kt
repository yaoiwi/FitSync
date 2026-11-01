package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 网页登录收尾链路测试：ticket → OAuth1 → OAuth2 → profile。
 *
 * 重点断言两件曾经出错的事：
 * 1. `preauthorized` 必须带 OAuth1 签名（没有签名时 Garmin 返回 400，且不解释原因）；
 * 2. `preauthorized` 的响应是 **form-urlencoded**，不是 JSON。
 */
class GarminSsoClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GarminSsoClient
    private lateinit var store: FakeCredentialStore

    /** 老路（OAuth1）的响应队列 */
    private lateinit var queue: QueueDispatcher

    /** 新路（diauth）的响应；空了就默认 400，让老路用例不受影响 */
    private val diauthResponses = ArrayDeque<MockResponse>()

    /** 新路收到的请求，用于断言鉴权方式与表单字段 */
    private val diauthRequests = mutableListOf<RecordedRequest>()

    private val base: String get() = server.url("/").toString().trimEnd('/')

    private fun enqueue(response: MockResponse) = queue.enqueueResponse(response)

    /** 取下一个「老路」请求：diauth 的请求也进同一个队列，断言时必须跳过 */
    private fun nextLegacyRequest(): RecordedRequest {
        while (true) {
            val req = server.takeRequest()
            if (!req.path.orEmpty().startsWith("/diauth")) return req
        }
    }

    @Before
    fun setUp() {
        queue = QueueDispatcher()
        server = MockWebServer()
        server.start()
        diauthResponses.clear()
        diauthRequests.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/diauth")) {
                    diauthRequests += request
                    return diauthResponses.removeFirstOrNull()
                        ?: MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client"}""")
                }
                return queue.dispatch(request)
            }
        }
        store = FakeCredentialStore()
        client = GarminSsoClient(
            credentialStore = store,
            httpClient = OkHttpClient(),
            cookieJar = MemoryCookieJar(),
        ).apply {
            endpoints = {
                GarminEndpoints(
                    ssoBase = "$base/sso",
                    apiBase = "$base/api",
                    webBase = "$base/web",
                    mobileService = "https://mobile.integration.garmin.cn/gcm/android",
                )
            }
            diTokenUrl = "$base/diauth/token"
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `令牌scope缺CONNECT_READ时换下一个audience`() {
        // Garmin 对错误的 audience 也会正常签发令牌，只是 scope 里没有 CONNECT_READ。
        // 这种令牌能通过验签，但访问任何业务接口都是 ForbiddenException——
        // 必须在换取阶段判掉，不能存进本地。
        enqueue(
            MockResponse().setResponseCode(200)
                .setBody("oauth_token=OA1-TOKEN&oauth_token_secret=OA1-SECRET&mfa_token=")
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-NOSCOPE","refresh_token":"RT-1","expires_in":3600,
                   "scope":"GOLF_API_READ INSIGHTS_READ"}"""
            )
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-OK","refresh_token":"RT-2","expires_in":3600,
                   "scope":"CONNECT_READ CONNECT_WRITE"}"""
            )
        )
        enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"displayName":"runner42"}""")
        )

        val result = runBlocking {
            client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-1")
        }

        assertEquals("AT-OK", result.session?.accessToken)
        nextLegacyRequest() // preauthorized
        val first = nextLegacyRequest()
        assertEquals("audience=GARMIN_CONNECT_MOBILE_ANDROID_DI", first.body.readUtf8())
        val second = nextLegacyRequest()
        assertEquals("audience=GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2", second.body.readUtf8())
    }

    @Test
    fun `网页ticket能换到accessToken`() {
        enqueue(
            MockResponse().setResponseCode(200)
                .setBody("oauth_token=OA1-TOKEN&oauth_token_secret=OA1-SECRET&mfa_token=")
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-1","refresh_token":"RT-1","expires_in":3600,"jti":"jti-1"}"""
            )
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"displayName":"runner42","fullName":"Runner","profileImageUrlLarge":"https://a/b.png"}"""
            )
        )

        val result = runBlocking {
            client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-123-abc")
        }

        assertTrue(result.completed)
        assertEquals("runner42", result.profile?.displayName)
        assertEquals("AT-1", result.session?.accessToken)
        assertEquals("OA1-TOKEN", result.session?.oauth1Token)
        assertEquals("OA1-SECRET", result.session?.oauth1Secret)

        val preauth = nextLegacyRequest()
        assertTrue("必须带 OAuth1 签名：${preauth.headers}", preauth.getHeader("Authorization")!!.startsWith("OAuth "))
        assertTrue(preauth.getHeader("Authorization")!!.contains("oauth_consumer_key="))
        assertTrue(preauth.path!!.contains("/api/oauth-service/oauth/preauthorized"))
        assertTrue(
            "网页登录的 ticket 要用 login-url=sso/embed：${preauth.path}",
            preauth.path!!.contains("login-url=") && preauth.path!!.contains("sso%2Fembed"),
        )
        assertTrue(preauth.path!!.contains("ticket=ST-123-abc"))

        val exchange = nextLegacyRequest()
        assertEquals("POST", exchange.method)
        assertTrue(exchange.path!!.contains("/oauth-service/oauth/exchange/user/2.0"))
        val body = exchange.body.readUtf8()
        // 首选必须是不带季度后缀的名字：带后缀的换出来常常缺 CONNECT_READ
        assertEquals("audience=GARMIN_CONNECT_MOBILE_ANDROID_DI", body)
        assertTrue(
            "第二步要用上一步的 oauth_token 签名",
            exchange.getHeader("Authorization")!!.contains("oauth_token="),
        )

        val profile = nextLegacyRequest()
        assertEquals("Bearer AT-1", profile.getHeader("Authorization"))
    }

    @Test
    fun `ticket被拒时带上状态码`() {
        // 客户端会依次尝试 service / clientId / login-url 三组参数
        repeat(3) {
            enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized: bad ticket"))
        }

        val error = runCatching {
            runBlocking {
                client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-expired")
            }
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue("实际：${error?.message}", error!!.message!!.contains("401"))
        assertTrue(error.message!!.contains("bad ticket"))
    }

    @Test
    fun `preauthorized返回缺字段时明确报错`() {
        repeat(3) {
            enqueue(MockResponse().setResponseCode(200).setBody("mfa_token=only"))
        }

        val error = runCatching {
            runBlocking {
                client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-1")
            }
        }.exceptionOrNull()

        assertTrue("实际：${error?.message}", error!!.message!!.contains("oauth_token"))
    }

    @Test
    fun `刷新用保存的OAuth1重新换token`() {
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-NEW","refresh_token":"RT-NEW","expires_in":3600}"""
            )
        )

        val refreshed = runBlocking {
            client.refreshSession(
                StorableCredential(
                    region = AccountRegion.CN.code,
                    externalUserId = "runner42",
                    oauth1Token = "OA1-TOKEN",
                    oauth1Secret = "OA1-SECRET",
                )
            )
        }

        assertEquals("AT-NEW", refreshed.accessToken)
        assertEquals("RT-NEW", refreshed.refreshToken)
        assertTrue(refreshed.tokenExpiresAtMs > System.currentTimeMillis())

        val request = nextLegacyRequest()
        assertTrue(
            "刷新必须用 OAuth1 签名，而不是 refresh_token 表单",
            request.getHeader("Authorization")!!.contains("oauth_token=\"OA1-TOKEN\""),
        )
    }

    @Test
    fun `没有OAuth1凭据时提示重新登录`() {
        val error = runCatching {
            runBlocking {
                client.refreshSession(
                    StorableCredential(region = AccountRegion.CN.code, externalUserId = "u")
                )
            }
        }.exceptionOrNull()

        assertTrue("实际：${error?.message}", error!!.message!!.contains("重新登录"))
    }

    private class FakeCredentialStore : CredentialStore {
        val saved = mutableListOf<StorableCredential>()
        override fun load(sourceType: String, region: String, externalUserId: String) =
            saved.lastOrNull { it.externalUserId == externalUserId }

        override fun save(credential: StorableCredential) {
            saved += credential
        }

        override fun delete(sourceType: String, region: String, externalUserId: String) {
            saved.removeAll { it.externalUserId == externalUserId }
        }
    }

    @Test
    fun `新路_diauth换到DI令牌_不再走OAuth1`() {
        diauthResponses += MockResponse().setResponseCode(200).setBody(
            """{"access_token":"AT-DI","refresh_token":"RT-DI","expires_in":3600}"""
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"displayName":"runner42","fullName":"Runner"}"""
            )
        )

        val result = runBlocking {
            client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-DI")
        }

        assertEquals("AT-DI", result.session?.accessToken)
        assertEquals("RT-DI", result.session?.refreshToken)
        assertEquals(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            result.session?.diClientId,
        )
        // 新路不需要 OAuth1，也就不该再打 preauthorized
        assertTrue("新路成功时不应回退老路：${result.session?.oauth1Token}",
            result.session?.oauth1Token.isNullOrBlank())

        val req = diauthRequests.single()
        assertEquals("POST", req.method)
        // 鉴权是 Basic base64("{client_id}:"), 不是 OAuth1 签名
        assertTrue("应为 Basic 鉴权：${req.getHeader("Authorization")}",
            req.getHeader("Authorization")!!.startsWith("Basic "))
        val form = req.body.readUtf8()
        assertTrue("缺 service_ticket：$form", form.contains("service_ticket=ST-DI"))
        assertTrue("缺 grant_type：$form", form.contains("grant_type="))
        assertTrue("grant_type 应是 URL 形式：$form",
            form.contains("di-oauth2-service%2Foauth%2Fgrant%2Fservice_ticket") ||
                form.contains("di-oauth2-service/oauth/grant/service_ticket"))
        assertTrue("缺 service_url：$form", form.contains("service_url="))
        // 身份头与业务请求一致：只有 iOS UA，不带 X-Garmin-*
        assertEquals("GCM-iOS-5.22.1.4", req.getHeader("User-Agent"))
        assertNull("不该再带设备头", req.getHeader("X-Garmin-User-Agent"))
    }

    @Test
    fun `新路_全部clientId被拒时回退OAuth1`() {
        // diauth 默认 400（见 dispatcher），老路照常可用
        enqueue(
            MockResponse().setResponseCode(200)
                .setBody("oauth_token=OA1-TOKEN&oauth_token_secret=OA1-SECRET")
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-OLD","refresh_token":"RT-OLD","expires_in":3600}"""
            )
        )
        enqueue(
            MockResponse().setResponseCode(200).setBody("""{"displayName":"runner42"}""")
        )

        val result = runBlocking {
            client.completeWithTicket(GarminSsoLoginRequest(AccountRegion.CN), "ST-OLD")
        }

        assertEquals("AT-OLD", result.session?.accessToken)
        assertEquals("OA1-TOKEN", result.session?.oauth1Token)
        assertTrue("应把四个 client_id 都试过：${diauthRequests.size}", diauthRequests.size >= 4)
    }

    @Test
    fun `新路_用refresh_token续期`() {
        diauthResponses += MockResponse().setResponseCode(200).setBody(
            """{"access_token":"AT-2","refresh_token":"RT-2","expires_in":3600}"""
        )

        val refreshed = runBlocking {
            client.refreshSession(
                StorableCredential(
                    region = AccountRegion.CN.code,
                    externalUserId = "runner42",
                    accessToken = "AT-1",
                    refreshToken = "RT-1",
                    diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
                )
            )
        }

        assertEquals("AT-2", refreshed.accessToken)
        assertEquals("RT-2", refreshed.refreshToken)

        val req = diauthRequests.single()
        val form = req.body.readUtf8()
        assertTrue("缺 grant_type=refresh_token：$form", form.contains("grant_type=refresh_token"))
        assertTrue("缺 refresh_token：$form", form.contains("refresh_token=RT-1"))
        assertTrue("缺 client_id：$form", form.contains("client_id=GARMIN_CONNECT_MOBILE"))
    }

    @Test
    fun `OAuth1签名头包含必要字段`() {
        val request = OAuth1Signer.sign(
            okhttp3.Request.Builder()
                .url("$base/api/oauth-service/oauth/preauthorized?ticket=ST-1&service=x")
                .get()
                .build(),
            token = "OA1",
            tokenSecret = "SECRET",
        )
        val header = request.header("Authorization")!!
        listOf(
            "oauth_consumer_key=",
            "oauth_nonce=",
            "oauth_signature=",
            "oauth_signature_method=\"HMAC-SHA1\"",
            "oauth_timestamp=",
            "oauth_token=\"OA1\"",
            "oauth_version=\"1.0\"",
        ).forEach { assertTrue("缺少 $it：$header", header.contains(it)) }
    }
}
