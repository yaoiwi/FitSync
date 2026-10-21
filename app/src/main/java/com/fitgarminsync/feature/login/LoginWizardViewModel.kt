package com.fitgarminsync.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitgarminsync.data.db.AccountEntity
import com.fitgarminsync.data.db.AccountRegion
import com.fitgarminsync.data.db.AccountStatus
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.db.SourceType
import com.fitgarminsync.data.db.SyncCursorEntity
import com.fitgarminsync.data.network.CredentialStore
import com.fitgarminsync.data.network.GarminSsoClient
import com.fitgarminsync.data.network.GarminSsoLoginRequest
import com.fitgarminsync.data.network.GarminSsoResult
import com.fitgarminsync.data.network.MemoryCookieJar
import com.fitgarminsync.data.network.StorableCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 登录向导：**只走网页登录**。
 *
 * 为什么不再要账号密码：
 * 1. Garmin 的移动端 JSON 接口会被设备侧边缘防护拦（`403 Access is Denied`）；
 * 2. 网页登录在 WebView 里跑真实浏览器引擎，JS 挑战 / TLS 指纹 / Cookie 都由系统处理；
 * 3. 密码不必经过本应用，少存一份敏感数据。
 *
 * 流程：选数据源 → 选区域 → 风险知情 → 打开 Garmin 官方登录页 → 截获 ticket → 换 token → 落库。
 */
@HiltViewModel
class LoginWizardViewModel @Inject constructor(
    private val repository: FitDataRepository,
    private val garminSsoClient: GarminSsoClient,
    private val credentialStore: CredentialStore,
    private val cookieJar: MemoryCookieJar,
) : ViewModel() {

    data class WizardUiState(
        val step: Int = 1,
        val selectedSource: String = SourceType.GARMIN.code,
        val selectedRegion: String = AccountRegion.CN.code,
        val riskAccepted: Boolean = false,
        val busy: Boolean = false,
        val errorMessage: String? = null,
        val successMessage: String? = null,
        val showWebLogin: Boolean = false,
    )

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState

    fun onSourceSelected(source: String) {
        _uiState.update { it.copy(selectedSource = source) }
    }

    fun onRegionSelected(region: String) {
        _uiState.update { it.copy(selectedRegion = region) }
    }

    fun onRiskAccepted(accepted: Boolean) {
        _uiState.update { it.copy(riskAccepted = accepted) }
    }

    fun nextStep() {
        val state = _uiState.value
        when {
            state.step < 3 -> _uiState.update {
                it.copy(step = state.step + 1, errorMessage = null, successMessage = null)
            }
            // 第 3 步：确认风险声明后直接进网页登录
            !state.riskAccepted -> _uiState.update {
                it.copy(errorMessage = "请先阅读并接受风险声明")
            }
            else -> openWebLogin()
        }
    }

    fun previousStep() {
        _uiState.update {
            it.copy(
                step = maxOf(1, it.step - 1),
                busy = false,
                errorMessage = null,
                successMessage = null,
            )
        }
    }

    fun reset() {
        _uiState.value = WizardUiState()
    }

    fun openWebLogin() {
        _uiState.update { it.copy(showWebLogin = true, errorMessage = null) }
    }

    fun closeWebLogin() {
        _uiState.update { it.copy(showWebLogin = false) }
    }

    /**
     * WebView 截获到 ticket（来自重定向 URL 的 ?ticket= 或页面内 fetch 的 serviceTicketId）。
     *
     * [cookie] 是 WebView 登录后的 SSO 会话 Cookie。CAS ticket 与签发它的会话绑定，
     * 不带 Cookie 去换 token 会被判成"ticket 不存在"（真机已复现 401）。
     */
    fun onWebLoginTicket(ticket: String, cookie: String?) {
        val region = AccountRegion.from(_uiState.value.selectedRegion)
        cookieJar.importFromHeader(region.ssoHost, cookie)
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, showWebLogin = false, errorMessage = null) }
            try {
                val result = garminSsoClient.completeWithTicket(
                    request = GarminSsoLoginRequest(region = region),
                    ticket = ticket,
                )
                persistAccountAfterLogin(regionCode = region.code, ssoResult = result)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        errorMessage = "登录失败：${describeError(e)}",
                    )
                }
            }
        }
    }

    private suspend fun persistAccountAfterLogin(
        regionCode: String,
        ssoResult: GarminSsoResult,
    ) {
        val session = ssoResult.session
            ?: throw IllegalStateException("登录成功但缺少会话信息")
        val profile = ssoResult.profile
        // 网页登录拿不到邮箱，账号标识用 Garmin 的 displayName；没有再退回区域占位
        val externalId = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: "garmin-${regionCode}-${System.currentTimeMillis()}"
        val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: "Garmin 账号 ($regionCode)"

        val existing = repository.getAccountByUniqueKey(
            sourceType = SourceType.GARMIN.code,
            region = regionCode,
            externalUserId = externalId,
        )

        val now = System.currentTimeMillis()
        val expiresAt = session.expiresAtMs.takeIf { it > 0 } ?: (now + TOKEN_TTL_FALLBACK_MS)

        val account = existing?.copy(
            displayName = displayName,
            fullName = profile?.fullName?.takeIf { it.isNotBlank() } ?: existing.fullName,
            avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: existing.avatarUrl,
            status = AccountStatus.NORMAL.code,
            enabled = true,
            tokenExpiresAtMs = expiresAt,
        ) ?: AccountEntity(
            sourceType = SourceType.GARMIN.code,
            region = regionCode,
            externalUserId = externalId,
            displayName = displayName,
            fullName = profile?.fullName ?: "",
            avatarUrl = profile?.avatarUrl ?: "",
            status = AccountStatus.NORMAL.code,
            enabled = true,
            addedAtMs = now,
            tokenExpiresAtMs = expiresAt,
        )

        val accountId = repository.upsertAccount(account)

        // 关键：把 token 写进加密凭据仓。之前整条链路没有一处调用 save()，
        // 同步时 load() 永远拿不到令牌，这也是"同步不了"的直接原因之一。
        credentialStore.save(
            StorableCredential(
                sourceType = SourceType.GARMIN.code,
                region = regionCode,
                externalUserId = externalId,
                email = profile?.email.orEmpty(),
                password = "", // 网页登录不保存密码
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                sessionKey = session.sessionKey,
                // 与 AccountEntity 用同一个 expiresAt：服务端没下发 expires_in 时也有兜底值，
                // 否则是 0，同步时就无法提前换牌，只能等 401
                tokenExpiresAtMs = expiresAt,
                oauth1Token = session.oauth1Token,
                oauth1Secret = session.oauth1Secret,
            )
        )

        listOf("activity", "sleep", "heartRate", "daily").forEach { domain ->
            if (repository.getSyncCursor(accountId, domain) == null) {
                repository.upsertSyncCursor(
                    SyncCursorEntity(
                        accountId = accountId,
                        dataDomain = domain,
                        lastSyncDate = LocalDate.now().toString(),
                        lastSyncTsMs = now,
                        nextCursor = "",
                    )
                )
            }
        }

        _uiState.update {
            it.copy(
                busy = false,
                step = 4,
                successMessage = if (existing != null) {
                    "账号已更新，同步令牌已刷新"
                } else {
                    "账号已添加，已获取同步令牌"
                },
            )
        }
    }

    /** 异常本身常是固定文案，真正原因在 cause 里（HTTP 状态、响应片段） */
    private fun describeError(e: Throwable): String {
        val own = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        val cause = generateSequence(e.cause) { it.cause }
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
            .firstOrNull { it != own }
        return if (cause != null) "$own（$cause）" else own
    }

    private companion object {
        const val TOKEN_TTL_FALLBACK_MS = 3_600_000L
    }
}

private fun MutableStateFlow<LoginWizardViewModel.WizardUiState>.update(
    block: (LoginWizardViewModel.WizardUiState) -> LoginWizardViewModel.WizardUiState
) {
    value = block(value)
}
