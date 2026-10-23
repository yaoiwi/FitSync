package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertTrue(req.path!!.contains("/api/usersummary-service/usersummary/daily/runner42"))
        assertTrue(req.path!!.contains("calendarDate=2026-10-17"))
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
}
