package com.fitsync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.FitDataRepositoryImpl
import com.fitsync.data.db.FitSyncDatabase
import com.fitsync.data.importer.LocalDataImporter
import com.fitsync.data.settings.AccountAliasStore
import com.fitsync.data.settings.PrimaryAccountStore
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.MemoryCookieJar
import com.fitsync.data.network.StorableCredential
import com.fitsync.data.health.HealthSyncService
import com.fitsync.sync.SyncCoordinator
import com.fitsync.data.sync.ActivityCopyService
import com.fitsync.data.sync.GarminSyncEngine
import okhttp3.OkHttpClient
import com.fitsync.feature.home.HomeScreen
import com.fitsync.feature.home.HomeViewModel
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM 级冒烟测试（Robolectric）：不依赖真机/模拟器，验证
 * 1) Room 19 张表能真实建起来；
 * 2) 首页在数据为空、有数据两种状态下都能完成 Compose 渲染。
 *
 * 这里刻意绕开 Hilt（直接构造 HomeViewModel），目的是把崩溃面缩小到
 * 「数据层 + Compose 渲染」，Activity/Hilt 的问题需要真机 logcat 才能确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSmokeTest {

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }


    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 对齐 compose-ui 1.6.8 的真实行为：它只提供 androidx.compose.ui.platform.LocalLifecycleOwner
     * （不是 lifecycle-runtime-compose 2.8 的新 LocalLifecycleOwner）。
     * 如果哪天把 collectAsStateWithLifecycle 加回来，这里会像真机一样崩掉。
     */
    private fun testLifecycleOwner(): LifecycleOwner = TestLifecycleOwner()

    private fun renderHome(viewModel: com.fitsync.feature.home.HomeViewModel) {
        val owner = testLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeScreen(onAddAccount = {}, viewModel = viewModel)
            }
        }
    }

    /** 首页测试不触发同步，构造一个不会被调用的引擎即可 */
    private fun buildEngine(repository: com.fitsync.data.db.FitDataRepository): GarminSyncEngine {
        val noopStore = object : CredentialStore {
            override fun load(sourceType: String, region: String, externalUserId: String): StorableCredential? = null
            override fun save(credential: StorableCredential) = Unit
            override fun delete(sourceType: String, region: String, externalUserId: String) = Unit
        }
        val http = OkHttpClient()
        return GarminSyncEngine(
            repository = repository,
            connectClient = GarminConnectClient(http),
            credentialStore = noopStore,
            ssoClient = GarminSsoClient(noopStore, http, MemoryCookieJar()),
        )
    }

    /** 活动复制服务：首页测试不会调用它，构造一个指向内存库的实例即可 */
    private fun buildCopyService(repository: com.fitsync.data.db.FitDataRepository): ActivityCopyService {
        val noopStore = object : CredentialStore {
            override fun load(sourceType: String, region: String, externalUserId: String): StorableCredential? = null
            override fun save(credential: StorableCredential) = Unit
            override fun delete(sourceType: String, region: String, externalUserId: String) = Unit
        }
        val http = OkHttpClient()
        return ActivityCopyService(
            repository = repository,
            connectClient = GarminConnectClient(http),
            credentialStore = noopStore,
            ssoClient = GarminSsoClient(noopStore, http, MemoryCookieJar()),
        )
    }

    /** 健康搬运服务：首页测试不会调用它 */
    private fun buildHealthService(repository: com.fitsync.data.db.FitDataRepository): HealthSyncService {
        val noopStore = object : CredentialStore {
            override fun load(sourceType: String, region: String, externalUserId: String): StorableCredential? = null
            override fun save(credential: StorableCredential) = Unit
            override fun delete(sourceType: String, region: String, externalUserId: String) = Unit
        }
        val http = OkHttpClient()
        return HealthSyncService(
            context = ApplicationProvider.getApplicationContext(),
            repository = repository,
            connectClient = GarminConnectClient(http),
            credentialStore = noopStore,
            ssoClient = GarminSsoClient(noopStore, http, MemoryCookieJar()),
        )
    }

    /** 协调器：首页测试只用到状态，构造一个指向内存库的实例 */
    private fun buildCoordinator(repository: com.fitsync.data.db.FitDataRepository): SyncCoordinator {
        val noopStore = object : CredentialStore {
            override fun load(sourceType: String, region: String, externalUserId: String): StorableCredential? = null
            override fun save(credential: StorableCredential) = Unit
            override fun delete(sourceType: String, region: String, externalUserId: String) = Unit
        }
        val http = OkHttpClient()
        val store = PrimaryAccountStore(ApplicationProvider.getApplicationContext())
        return SyncCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            repository = repository,
            syncEngine = buildEngine(repository),
            primaryAccountStore = store,
            localImporter = LocalDataImporter(repository),
            activityCopyService = buildCopyService(repository),
            healthSyncService = buildHealthService(repository),
        )
    }

    private fun buildDatabase(): FitSyncDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FitSyncDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()

    @Test
    fun 数据库可以建库并写入账号() = runBlocking {
        val db = buildDatabase()
        val repository = FitDataRepositoryImpl(db)

        val id = repository.upsertAccount(
            AccountEntity(
                sourceType = "garmin",
                region = "cn",
                externalUserId = "demo@garmin.cn",
                displayName = "演示账号",
                addedAtMs = 1L,
            )
        )

        assertEquals(1L, id)
        val accounts = repository.observeEnabledAccounts().first()
        assertEquals(1, accounts.size)
        assertEquals("演示账号", accounts.first().displayName)
        db.close()
    }

    @Test
    fun 首页空状态可以渲染() {
        val db = buildDatabase()
        val repo = FitDataRepositoryImpl(db)
        val viewModel = HomeViewModel(
            repo,
            PrimaryAccountStore(ApplicationProvider.getApplicationContext()),
            buildCoordinator(repo),
            AccountAliasStore(ApplicationProvider.getApplicationContext()),
        )

        renderHome(viewModel)

        composeRule.onNodeWithText("还没有账号").assertIsDisplayed()
        db.close()
    }

    @Test
    fun 首页有账号时可以渲染() {
        val db = buildDatabase()
        val repository = FitDataRepositoryImpl(db)
        runBlocking {
            repository.upsertAccount(
                AccountEntity(
                    sourceType = "garmin",
                    region = "cn",
                    externalUserId = "demo@garmin.cn",
                    displayName = "演示账号",
                )
            )
        }
        val viewModel = HomeViewModel(
            repository,
            PrimaryAccountStore(ApplicationProvider.getApplicationContext()),
            buildCoordinator(repository),
            AccountAliasStore(ApplicationProvider.getApplicationContext()),
        )

        renderHome(viewModel)

        // 账号列表在底部导航的「账号」页，先切过去（导航栏图标的 contentDescription 就是页签名）
        composeRule.onNodeWithContentDescription("账号").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("演示账号", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("演示账号", substring = true).assertIsDisplayed()
        db.close()
    }
}
