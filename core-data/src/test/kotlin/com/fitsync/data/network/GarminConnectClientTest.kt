package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Connect API 解析测试：字段缺失必须降级为默认值，不能整批失败。 */
class GarminConnectClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GarminConnectClient

    private val base: String get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        GarminDiagnostics.clear()
        server = MockWebServer()
        server.start()
        client = GarminConnectClient(OkHttpClient()).apply {
            endpoints = { GarminEndpoints("$base/sso", "$base/api", "$base/web") }
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `日汇总_解析主要字段`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"calendarDate":"2026-10-17","totalSteps":8123,"totalDistanceMeters":6234.5,
                   "floorsClimbed":7,"activeKilocalories":420.0,"restingKilocalories":1600.0,
                   "restingHeartRate":52,"minHeartRate":45,"maxHeartRate":143,
                   "averageStressLevel":31,"maxStressLevel":77,
                   "bodyBatteryLowestValue":18,"bodyBatteryHighestValue":72,"averageSpo2":96}"""
            )
        )

        val s = client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")!!

        assertEquals("2026-10-17", s.date)
        assertEquals(8123, s.steps)
        assertEquals(52, s.restingHr)
        assertEquals(72, s.bodyBatteryMax)
        assertEquals(96, s.spo2Avg)
        val req = server.takeRequest()
        assertEquals("Bearer AT", req.getHeader("Authorization"))
        // 路径里不能带账号：新网关会把它判成越权，回 message 为 null 的 403
        assertTrue("应请求无用户名的日汇总：${req.path}", req.path!!.startsWith("/api/usersummary-service/usersummary/daily/?"))
        assertFalse("URL 里不该出现账号：${req.path}", req.path!!.contains("runner42"))
        assertTrue(req.path!!.contains("calendarDate=2026-10-17"))
        // 身份头只剩 UA：多带 X-Garmin-* 反而会被判伪造
        assertEquals("GCM-iOS-5.22.1.4", req.getHeader("User-Agent"))
        assertNull("不该再带 X-Garmin-User-Agent", req.getHeader("X-Garmin-User-Agent"))
    }

    @Test
    fun `睡眠_解析时长与评分_缺字段不崩`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"dailySleepDTO":{"calendarDate":"2026-10-17",
                   "sleepTimeSeconds":27000,"awakeSleepSeconds":1800,
                   "sleepStartTimestampGMT":1760640000000,"sleepEndTimestampGMT":1760670000000,
                   "avgHeartRate":54,"averageSpO2Value":95,"averageRespirationValue":14.2,
                   "sleepScores":{"overall":{"value":81}}}}"""
            )
        )

        val s = client.fetchSleep(AccountRegion.INTERNATIONAL, "AT", "runner42", "2026-10-17")!!

        assertEquals(450, s.totalSleepMin)
        assertEquals(480, s.inBedMin)
        assertEquals(81, s.sleepScore)
        assertEquals("sleep-2026-10-17", s.externalId)
    }

    @Test
    fun `睡眠_没有数据时返回null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"dailySleepDTO":{}}"""))
        assertEquals(null, client.fetchSleep(AccountRegion.CN, "AT", "u", "2026-10-17"))
    }

    @Test
    fun `活动列表_解析Gmt时间与类型`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"activityId":987654,"activityName":"Morning Run",
                    "activityType":{"typeKey":"running"},
                    "startTimeGMT":"2026-10-17 00:30:00","duration":1830.5,"distance":5021.0,
                    "averageHR":148,"maxHR":171,"calories":310.0,"elevationGain":42.0}]"""
            )
        )

        val list = client.fetchActivities(AccountRegion.CN, "AT", limit = 5)

        assertEquals(1, list.size)
        val a = list.first()
        assertEquals("987654", a.externalId)
        assertEquals("running", a.sport)
        assertEquals(1830L, a.durationSec)
        assertEquals(5021.0, a.distanceM, 0.01)
        assertEquals(148, a.avgHr)
        assertTrue("GMT 时间应被解析：${a.startTimeMs}", a.startTimeMs > 0)
    }

    @Test
    fun `全0的日汇总视为无数据`() {
        // Garmin 对"今天还没生成数据"会返回 200 + 全 0。
        // 旧实现会把它写库，导致首页最新一天被顶成全 0，看起来像数据消失。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"calendarDate":"2026-10-18","totalSteps":0,"totalDistanceMeters":0.0,
                   "activeKilocalories":0.0,"restingHeartRate":0}"""
            )
        )
        assertNull(client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-18"))
    }

    @Test
    fun `日汇总_无用户名路径404时回退旧路径`() {
        // 中国区早期账号的老网关不认 /daily/?calendarDate=，必须能退回带账号的形式，
        // 否则换端点会把原本能用的账号弄成「首页空了」。
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"calendarDate":"2026-10-19","totalSteps":4321}""")
        )

        val s = client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-19")!!

        assertEquals(4321, s.steps)
        assertFalse(server.takeRequest().path!!.contains("runner42"))
        assertTrue(server.takeRequest().path!!.contains("/daily/runner42"))
    }

    @Test
    fun `睡眠_旧端点403时改用新端点`() {
        // 带账号的 old 路径被新网关判越权；改用 /sleep-service/sleep/dailySleepData
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":null,"error":"ForbiddenException"}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"dailySleepDTO":{"calendarDate":"2026-10-19","sleepTimeSeconds":7200,
                   "awakeSleepSeconds":600,"sleepStartTimestampGMT":1760812800000,
                   "sleepEndTimestampGMT":1760820600000,"sleepScores":{"overall":{"value":70}}}}"""
            )
        )

        val s = client.fetchSleep(AccountRegion.CN, "AT", "runner42", "2026-10-19")!!

        assertEquals(120, s.totalSleepMin)
        assertEquals(70, s.sleepScore)
        val retried = server.takeRequest().let { server.takeRequest() }
        assertEquals("/api/sleep-service/sleep/dailySleepData?date=2026-10-19", retried.path)
    }

    @Test
    fun `401时抛出可识别异常`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val error = runCatching {
            client.fetchDailySummary(AccountRegion.CN, "expired", "u", "2026-10-17")
        }.exceptionOrNull()
        // 必须是 Unauthorized 而不是 Unknown：同步引擎靠这个类型判断"可以换令牌重试"
        assertTrue(
            "401 应归类为可自愈的 Unauthorized，实际是 ${error?.javaClass?.simpleName}",
            error is GarminNetworkException.Unauthorized,
        )
        assertTrue(error!!.message!!.contains("401"))
    }

    // ============================ 上传（健康数据搬运） ============================

    @Test
    fun `上传_必须走 fit 路由并解析 uploadId`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"detailedImportResult":{"uploadId":123456,"messages":[]}}""")
        )

        val outcome = client.uploadActivityFileOutcome(
            region = AccountRegion.CN,
            accessToken = "token",
            fileName = "health-2026-10-29.fit",
            bytes = ByteArray(16),
        )

        assertTrue("uploadId 非空应判成功：${outcome.body}", outcome.ok)
        val request = server.takeRequest()
        // 不带 .fit 会落到旧 Web 上传路由，纯 Bearer 请求会被判 419
        assertEquals("/api/upload-service/upload/.fit", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        // API 域只发必要头：多余的 NK / DI-Backend 会被网关转到错误的内部服务（500）
        assertNull("API 域不该带 Origin", request.getHeader("Origin"))
        assertNull("API 域不该带 DI-Backend", request.getHeader("DI-Backend"))
    }

    @Test
    fun `上传_419 换 web 域重试并带出 Garmin 原因`() {
        server.enqueue(
            MockResponse().setResponseCode(419).setBody(
                """{"detailedImportResult":{"uploadId":"","uploadUuid":"null","owner":11814876}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(419).setBody(
                """{"detailedImportResult":{"uploadId":"","messages":[{"content":"Device is not registered"}]}}"""
            )
        )

        val outcome = client.uploadActivityFileOutcome(
            region = AccountRegion.CN,
            accessToken = "token",
            fileName = "health-2026-10-29.fit",
            bytes = ByteArray(16),
        )

        assertTrue(!outcome.ok)
        assertEquals(419, outcome.code)
        assertEquals("Device is not registered", outcome.detail)
        assertEquals(2, server.requestCount)
        val retried = server.takeRequest().let { server.takeRequest() }
        assertEquals("/web/upload-service/upload/.fit", retried.path)
    }

    @Test
    fun `上传_2xx 但 uploadId 为空视为失败`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"detailedImportResult":{"uploadId":"","messages":[{"content":"Invalid file"}]}}"""
            )
        )

        val outcome = client.uploadActivityFileOutcome(
            region = AccountRegion.CN,
            accessToken = "token",
            fileName = "health-2026-10-29.fit",
            bytes = ByteArray(16),
        )

        assertTrue("uploadId 为空不能算成功", !outcome.ok)
        assertEquals("Invalid file", outcome.detail)
    }

    // ============================ 账号路径标识 ============================

    @Test
    fun `GUID 昵称_改用数字 profileId 作为路径标识`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"displayName":"","profileId":11814876,"fullName":"Runner"}""")
        )

        val user = client.resolvePathUser(
            AccountRegion.CN,
            "token",
            "371d55ac-80da-47eb-a856-87246836ee69",
        )

        assertEquals("11814876", user)
        assertEquals("/api/userprofile-service/socialProfile", server.takeRequest().path)
    }

    @Test
    fun `正常昵称_不额外请求 profile`() {
        val user = client.resolvePathUser(AccountRegion.CN, "token", "runner42")
        assertEquals("runner42", user)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `上传_Bearer 被拒且有 OAuth1 时改用签名重试`() {
        server.enqueue(MockResponse().setResponseCode(419).setBody("""{"detailedImportResult":{}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"detailedImportResult":{"uploadId":987,"messages":[]}}""")
        )

        val outcome = client.uploadActivityFileOutcome(
            region = AccountRegion.CN,
            accessToken = "bearer",
            fileName = "health-2026-10-29.fit",
            bytes = ByteArray(16),
            oauth1Token = "o1-token",
            oauth1Secret = "o1-secret",
        )

        assertTrue("OAuth1 重试应成功：${outcome.body}", outcome.ok)
        server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "第二次应带 OAuth1 签名，实际：${second.getHeader("Authorization")}",
            second.getHeader("Authorization")!!.startsWith("OAuth "),
        )
    }

    // ============================ 设备列表（序列号哨兵值） ============================

    @Test
    fun `设备_serialNumber 是哨兵值时改用 unitId`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"deviceId":3310945678,"unitId":3310945678,
                     "serialNumber":9223372036854775807,"productId":3843,
                     "productDisplayName":"Forerunner 255","primary":true}]"""
            )
        )

        val fetch = client.fetchDevicesDetailed(AccountRegion.CN, "token")

        assertEquals(1, fetch.devices.size)
        val device = fetch.devices.first()
        assertEquals("序列号应来自 unitId", 3310945678L, device.serialNumber)
        assertTrue("应标记序列号取自 unitId", device.serialFromUnitId)
        assertEquals(9223372036854775807L, device.rawSerialNumber)
    }

    @Test
    fun `设备_列表只有哨兵值时用详情接口补序列号`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"deviceId":3310945678,"serialNumber":9223372036854775807,
                     "productDisplayName":"Forerunner 255"}]"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"deviceId":3310945678,"serialNumber":3310945678,"productId":3843,
                    "productDisplayName":"Forerunner 255"}"""
            )
        )

        val fetch = client.fetchDevicesDetailed(AccountRegion.CN, "token")

        assertEquals(1, fetch.devices.size)
        assertEquals(3310945678L, fetch.devices.first().serialNumber)
        assertEquals(3843, fetch.devices.first().productId)
        server.takeRequest()
        val detail = server.takeRequest()
        assertEquals("/api/device-service/deviceregistration/device/3310945678", detail.path)
    }

    @Test
    fun `设备_全部是哨兵值时明确报无效值`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"serialNumber":9223372036854775807,"productDisplayName":"Forerunner 255"}]"""
            )
        )

        val fetch = client.fetchDevicesDetailed(AccountRegion.CN, "token")

        assertTrue("不应解析出设备", fetch.devices.isEmpty())
        assertTrue(
            "错误信息应说明是无效值：${fetch.error}",
            fetch.error!!.contains("无效值"),
        )
    }

    @Test
    fun `上传_四条路由都失败时带出尝试轨迹`() {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}""")
            )
        }

        val outcome = client.uploadActivityFileOutcome(
            region = AccountRegion.CN,
            accessToken = "bearer",
            fileName = "health-2026-10-29.fit",
            bytes = ByteArray(16),
            oauth1Token = "o1-token",
            oauth1Secret = "o1-secret",
        )

        assertTrue("应失败", !outcome.ok)
        assertEquals(401, outcome.code)
        assertTrue("轨迹应含四种尝试：${outcome.trace}", outcome.trace.contains("Bearer/API"))
        assertTrue("轨迹应含 OAuth1：${outcome.trace}", outcome.trace.contains("OAuth1/API"))
        assertTrue("文案应带轨迹：${outcome.errorText}", outcome.errorText.contains("尝试："))
    }

    // ============================ 403 与 429 必须分开 ============================
    //
    // 403 是「请求被拒绝」，等多久都不会自己好；429 才是限流。
    // 两者混在一起时，一个 403 会被当成限流一路锁到 48 小时，
    // 用户看到的就是「永远显示账号限制、同步不了」。

    @Test
    fun `403_直接判 Forbidden_只发一次请求`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Access is Denied"))

        val error = assertThrows(GarminNetworkException.Forbidden::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }

        assertTrue("文案应说明是 403：${error.message}", error.message!!.contains("403"))
        assertTrue("文案应说明不是限流：${error.message}", error.message!!.contains("不是限流"))
        // 不再「换 UA 再试一次」：试过浏览器 UA、试过 Android 设备头，都是同样的 403
        assertEquals("403 不应重复请求", 1, server.requestCount)
        val req = server.takeRequest()
        assertTrue("必须始终用 iOS 指纹：${req.getHeader("User-Agent")}",
            req.getHeader("User-Agent")!!.startsWith("GCM-iOS"))
    }

    @Test
    fun `请求身份只有iOS_UA_不带设备头`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"calendarDate":"2026-10-17","totalSteps":1}"""
            )
        )

        client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")

        val req = server.takeRequest()
        assertEquals("GCM-iOS-5.22.1.4", req.getHeader("User-Agent"))
        // 1.14.8 补了一整套 X-Garmin-*，403 一条没少；garth 0.8.0 只发 UA 就能读数据，
        // 说明这批头不是通行证，多带只会被当成伪造
        assertNull("不该带 X-Garmin-User-Agent", req.getHeader("X-Garmin-User-Agent"))
        assertNull("不该带 X-Garmin-Paired-App-Version", req.getHeader("X-Garmin-Paired-App-Version"))
        assertNull("不该带 X-Garmin-Client-Platform", req.getHeader("X-Garmin-Client-Platform"))
        // NK / DI-Backend 是网页 Cookie 模式的头，配 Bearer 会 500，绝不能带
        assertNull("DI 模式不应带 NK", req.getHeader("NK"))
        assertNull("DI 模式不应带 DI-Backend", req.getHeader("DI-Backend"))
    }

    @Test
    fun `302 跳登录页_当作令牌失效且不跟随`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://sso.garmin.cn/sso/signin")
        )

        assertThrows(GarminNetworkException.Unauthorized::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }

        // 跟着跳过去会在跨域时丢掉 Authorization，最后变成一个看不出原因的 403，
        // 续期逻辑永远等不到 401 —— 这正是「一直 403」的成因
        assertEquals("不应跟随重定向", 1, server.requestCount)
    }

    @Test
    fun `403_保留 Garmin 原话并记入诊断`() {
        val body = """{"message":"HTTP 403 Forbidden","error":"ForbiddenException"}"""
        server.enqueue(MockResponse().setResponseCode(403).setBody(body))

        val error = assertThrows(GarminNetworkException.Forbidden::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }
        assertTrue(
            "文案应带 Garmin 原话：${error.message}",
            error.message!!.contains("ForbiddenException"),
        )

        val entry = GarminDiagnostics.latest()!!
        assertEquals(403, entry.code)
        assertTrue("应能看出请求确实带了令牌（长度 ${entry.tokenLength}）", entry.tokenLength > 0)
    }

    @Test
    fun `连续失败也保持同一身份_不轮换 UA`() {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(403).setBody("Access is Denied"))
        }

        repeat(2) {
            runCatching {
                client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
            }
        }

        repeat(2) {
            val req = server.takeRequest()
            assertEquals(
                "第 ${it + 1} 次 UA 被换掉了",
                "GCM-iOS-5.22.1.4",
                req.getHeader("User-Agent"),
            )
            assertNull("第 ${it + 1} 次多带了设备头", req.getHeader("X-Garmin-User-Agent"))
        }
    }

    @Test
    fun `429_带 cf-ray 也按限流处理_不换身份重试`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("cf-ray", "8a1b2c3d4e5f-SJC")
        )

        assertThrows(GarminNetworkException.RiskBlocked::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }
        // 连续硬撞只会把锁定推得更久，冷却交给上层
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429_判为限流并带回 Retry-After`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
        )

        val error = assertThrows(GarminNetworkException.RiskBlocked::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }

        assertEquals(120_000L, error.retryAfterMs)
    }

    @Test
    fun `429_没有 Retry-After 时仍判为限流`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val error = assertThrows(GarminNetworkException.RiskBlocked::class.java) {
            client.fetchDailySummary(AccountRegion.CN, "AT", "runner42", "2026-10-17")
        }

        assertNull(error.retryAfterMs)
    }

    @Test
    fun `socialProfile_403 不吞成空昵称`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Access is Denied"))

        assertThrows(GarminNetworkException.Forbidden::class.java) {
            // GUID 才会触发查 socialProfile；普通昵称直接返回，不打请求
            client.resolvePathUser(
                AccountRegion.CN,
                "AT",
                "371d55ac-80da-47eb-9e5f-0d5b0e6e9a11",
            )
        }
    }
}
