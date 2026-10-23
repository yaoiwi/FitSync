package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private val base: String get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `网页ticket能换到accessToken`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("oauth_token=OA1-TOKEN&oauth_token_secret=OA1-SECRET&mfa_token=")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"AT-1","refresh_token":"RT-1","expires_in":3600,"jti":"jti-1"}"""
            )
        )
        server.enqueue(
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

        val preauth = server.takeRequest()
        assertTrue("必须带 OAuth1 签名：${preauth.headers}", preauth.getHeader("Authorization")!!.startsWith("OAuth "))
        assertTrue(preauth.getHeader("Authorization")!!.contains("oauth_consumer_key="))
        assertTrue(preauth.path!!.contains("/api/oauth-service/oauth/preauthorized"))
        assertTrue(
            "网页登录的 ticket 要用 login-url=sso/embed：${preauth.path}",
            preauth.path!!.contains("login-url=") && preauth.path!!.contains("sso%2Fembed"),
        )
        assertTrue(preauth.path!!.contains("ticket=ST-123-abc"))

        val exchange = server.takeRequest()
        assertEquals("POST", exchange.method)
        assertTrue(exchange.path!!.contains("/oauth-service/oauth/exchange/user/2.0"))
        val body = exchange.body.readUtf8()
        assertTrue("必须带 audience：$body", body.contains("audience=GARMIN_CONNECT_MOBILE_ANDROID_DI"))
        assertTrue(
            "第二步要用上一步的 oauth_token 签名",
            exchange.getHeader("Authorization")!!.contains("oauth_token="),
        )

        val profile = server.takeRequest()
        assertEquals("Bearer AT-1", profile.getHeader("Authorization"))
    }

    @Test
    fun `ticket被拒时带上状态码`() {
        // 客户端会依次尝试 service / clientId / login-url 三组参数
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized: bad ticket"))
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
            server.enqueue(MockResponse().setResponseCode(200).setBody("mfa_token=only"))
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
        server.enqueue(
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

        val request = server.takeRequest()
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
