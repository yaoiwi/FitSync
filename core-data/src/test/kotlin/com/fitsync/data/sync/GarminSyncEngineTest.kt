package com.fitsync.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.FitDataRepositoryImpl
import com.fitsync.data.db.FitSyncDatabase
import com.fitsync.data.db.SourceType
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminEndpoints
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.MemoryCookieJar
import com.fitsync.data.network.StorableCredential
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同步引擎集成测试：真实 Room（内存库）+ MockWebServer。
 * 验证「拉取 → 映射 → 落库 → 推进游标 → 记日志」整条链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminSyncEngineTest {

    @Test
    fun `同步写入日汇总_睡眠_活动并推进游标`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"calendarDate":"2026-10-18","totalSteps":9000,"totalDistanceMeters":7000.0,
                   "floorsClimbed":9,"activeKilocalories":500.0,"restingKilocalories":1500.0,
                   "restingHeartRate":50,"averageStressLevel":28,"maxStressLevel":70,
                   "bodyBatteryLowestValue":20,"bodyBatteryHighestValue":80,"averageSpo2":97}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"dailySleepDTO":{"calendarDate":"2026-10-18","sleepTimeSeconds":25200,
                   "awakeSleepSeconds":1200,"sleepStartTimestampGMT":1760700000000,
                   "sleepEndTimestampGMT":1760730000000,"sleepScores":{"overall":{"value":79}}}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"activityId":111,"activityName":"Run","activityType":{"typeKey":"running"},
                    "startTimeGMT":"2026-10-18 01:00:00","duration":1800,"distance":5000,
                    "averageHR":150,"maxHR":175,"calories":300,"elevationGain":30}]"""
            )
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FitSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = FitDataRepositoryImpl(db)

        val accountId = repository.upsertAccount(
            AccountEntity(
                sourceType = SourceType.GARMIN.code,
                region = "cn",
                externalUserId = "runner42",
                displayName = "runner42",
            )
        )

        val store = FakeCredentialStore(
            StorableCredential(
                externalUserId = "runner42",
                email = "a@b.com",
                password = "pw",
                accessToken = "AT",
                refreshToken = "RT",
            )
        )
        val http = OkHttpClient()
        val sso = GarminSsoClient(store, http, MemoryCookieJar())
        val connect = GarminConnectClient(http).apply {
            endpoints = { GarminEndpoints("$base/sso", "$base/api", "$base/web") }
        }
        val engine = GarminSyncEngine(repository, connect, store, sso)

        val outcome = engine.syncAccount(accountId, backfillDays = 1)

        assertTrue("同步应成功：${outcome.message}", outcome.ok)
        assertEquals(3, outcome.records)

        assertEquals(1, repository.observeDailySummaries(accountId).first().size)
        assertEquals(9000, repository.observeDailySummaries(accountId).first().first().steps)
        assertEquals(1, repository.observeSleepSessions(accountId).first().size)
        assertEquals(420, repository.observeSleepSessions(accountId).first().first().totalSleepMin)
        assertEquals(1, repository.observeActivities(accountId).first().size)
        assertEquals("running", repository.observeActivities(accountId).first().first().sport)

        assertNotNull("游标应被推进", repository.getSyncCursor(accountId, "daily"))
        assertTrue("账号应记录同步时间", (repository.getAccount(accountId)?.lastSyncAtMs ?: 0) > 0)
        assertEquals("success", repository.observeRecentSyncLogs(accountId).first().first().status)

        db.close()
        server.shutdown()
    }

    /**
     * 回归用例：服务端提前作废令牌时，同步必须自己换一张再重试，而不是报 401 失败。
     *
     * 场景：本地令牌"看起来没过期"（expiresAt 在未来），但服务端照样 401。
     * 引擎应捕获 Unauthorized → 调 refreshSession → 用新令牌重跑一遍。
     */
    @Test
    fun `401时自动续期并重试`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')

        // 第一次日汇总请求被判 401
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        // 续期后重跑：日汇总 → 睡眠 → 活动
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"calendarDate":"2026-10-18","totalSteps":4200,"totalDistanceMeters":3000.0,
                   "floorsClimbed":3,"activeKilocalories":200.0,"restingKilocalories":1400.0,
                   "restingHeartRate":52}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FitSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = FitDataRepositoryImpl(db)

        val accountId = repository.upsertAccount(
            AccountEntity(
                sourceType = SourceType.GARMIN.code,
                region = "cn",
                externalUserId = "runner42",
                displayName = "runner42",
            )
        )

        val store = FakeCredentialStore(
            StorableCredential(
                externalUserId = "runner42",
                accessToken = "AT-OLD",
                // 明确"还没到期"，逼引擎走 401 重试而不是预刷新
                tokenExpiresAtMs = System.currentTimeMillis() + 30 * 60 * 1000,
            )
        )
        val http = OkHttpClient()
        val refreshes = ArrayList<String>()
        val sso = object : GarminSsoClient(store, http, MemoryCookieJar()) {
            override suspend fun refreshSession(credential: StorableCredential): StorableCredential {
                refreshes += credential.accessToken
                return credential.copy(accessToken = "AT-NEW")
            }
        }
        val connect = GarminConnectClient(http).apply {
            endpoints = { GarminEndpoints("$base/sso", "$base/api", "$base/web") }
        }
        val engine = GarminSyncEngine(repository, connect, store, sso)

        val outcome = engine.syncAccount(accountId, backfillDays = 1)

        assertTrue("401 后应自动续期重试成功：${outcome.message}", outcome.ok)
        assertEquals("旧令牌应被用于续期", listOf("AT-OLD"), refreshes)
        assertEquals("新令牌应写回凭据仓", "AT-NEW", store.saved?.accessToken)
        assertEquals(4200, repository.observeDailySummaries(accountId).first().first().steps)
        assertEquals("success", repository.observeRecentSyncLogs(accountId).first().first().status)

        db.close()
        server.shutdown()
    }

    /**
     * 续期都救不回来时，账号必须标记 EXPIRED，并给出"请重新登录"的提示，
     * 而不是抛一个看不懂的 401。
     */
    @Test
    fun `续期失败时账号标记过期`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FitSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = FitDataRepositoryImpl(db)

        val accountId = repository.upsertAccount(
            AccountEntity(
                sourceType = SourceType.GARMIN.code,
                region = "cn",
                externalUserId = "runner42",
                displayName = "runner42",
            )
        )
        val store = FakeCredentialStore(
            StorableCredential(externalUserId = "runner42", accessToken = "AT-OLD")
        )
        val sso = object : GarminSsoClient(store, OkHttpClient(), MemoryCookieJar()) {
            override suspend fun refreshSession(credential: StorableCredential): StorableCredential {
                throw IllegalStateException("oauth1 已被吊销")
            }
        }
        val connect = GarminConnectClient(OkHttpClient()).apply {
            endpoints = { GarminEndpoints("$base/sso", "$base/api", "$base/web") }
        }
        val engine = GarminSyncEngine(repository, connect, store, sso)

        val outcome = engine.syncAccount(accountId, backfillDays = 1)

        assertEquals(false, outcome.ok)
        assertTrue("提示应告诉用户去重新登录：${outcome.message}", outcome.message.contains("重新登录"))
        assertEquals("expired", repository.getAccount(accountId)?.status)
        assertEquals("failed", repository.observeRecentSyncLogs(accountId).first().first().status)

        db.close()
        server.shutdown()
    }

    private class FakeCredentialStore(private val credential: StorableCredential) : CredentialStore {
        var saved: StorableCredential? = null

        override fun load(sourceType: String, region: String, externalUserId: String) =
            credential

        override fun save(credential: StorableCredential) {
            saved = credential
        }

        override fun delete(sourceType: String, region: String, externalUserId: String) = Unit
    }
}
