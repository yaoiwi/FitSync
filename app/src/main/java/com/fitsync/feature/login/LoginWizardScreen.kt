package com.fitsync.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.BuildConfig
import com.fitsync.ui.components.DataSourceCard
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.LoginWizardStepper
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixLargeHeader
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixTextButton
import com.fitsync.ui.components.RegionSegmentedControl
import com.fitsync.ui.components.RiskConsentCard
import com.fitsync.ui.components.glassSource
import com.fitsync.ui.components.liquidGlass
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiAccent
import com.fitsync.ui.theme.MiuiRed
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixCanvas
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary

/**
 * 添加账号向导。
 *
 * 版式与首页一致：大标题 + 分组卡片 + 底部操作条。
 * 网页登录丢给 [GarminWebLoginScreen]，这里只负责前 3 步的选择与收尾。
 */
@Composable
fun LoginWizardScreen(
    onFinished: () -> Unit,
    viewModel: LoginWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    FitSyncTheme {
        if (state.showWebLogin) {
            GarminWebLoginScreen(
                regionCode = state.selectedRegion,
                onTicket = viewModel::onWebLoginTicket,
                onCancel = viewModel::closeWebLogin,
            )
            return@FitSyncTheme
        }

        // 底部操作条：玻璃浮层，贴底。放进 overlay，不参与 backdrop 采样。
        val bottomBar: @Composable BoxScope.() -> Unit = {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        tint = com.fitsync.ui.theme.MiuixCardSurface.copy(alpha = 0.78f),
                        highlightAlpha = 0.18f,
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step > 1 && state.step <= 3) {
                        Box(modifier = Modifier.weight(1f)) {
                            MiuixTextButton(
                                text = "上一步",
                                onClick = viewModel::previousStep,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    when {
                        state.step < 3 -> {
                            Box(modifier = Modifier.weight(1.4f)) {
                                MiuixPrimaryButton(
                                    text = "下一步",
                                    onClick = viewModel::nextStep,
                                    enabled = !state.busy,
                                )
                            }
                        }
                        state.step == 4 -> {
                            Box(modifier = Modifier.weight(1.4f)) {
                                MiuixPrimaryButton(text = "进入账号列表", onClick = onFinished)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        // 玻璃宿主：向导内容录成 backdrop，底部操作条放 overlay（不参与采样）
        GlassHost(
            modifier = Modifier.fillMaxSize(),
            overlay = bottomBar,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .background(MiuixCanvas),
            ) {
                MiuixLargeHeader(
                    title = "添加账号",
                    subtitle = "Garmin 中国区 / 国际区可同时登录，数据在本地合并",
                )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .glassSource()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LoginWizardStepper(step = state.step)
                }

                when (state.step) {
                    1 -> {
                        MiuixSectionTitle("选择数据来源")
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            DataSourceCard(
                                iconText = "G",
                                iconBg = MiuiAccent,
                                name = "Garmin",
                                desc = "支持中国区 / 国际区账号",
                                selected = state.selectedSource == "garmin",
                                onClick = { viewModel.onSourceSelected("garmin") },
                            )
                            Spacer(Modifier.height(10.dp))
                            DataSourceCard(
                                iconText = "L",
                                iconBg = MiuiText1,
                                name = "本地文件导入",
                                desc = "GPX / CSV / JSON",
                                selected = state.selectedSource == "file",
                                onClick = { viewModel.onSourceSelected("file") },
                            )
                        }
                    }

                    2 -> {
                        MiuixSectionTitle("选择区域")
                        MiuixCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                RegionSegmentedControl(
                                    selectedRegion = state.selectedRegion,
                                    onRegionSelected = viewModel::onRegionSelected,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "两个区可以并存：同一账号在两个区是两套数据，" +
                                        "本应用按区域分别保存游标与账号信息。",
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixSummary,
                                )
                            }
                        }
                    }

                    3 -> {
                        MiuixSectionTitle("登录 Garmin")
                        MiuixCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "账号密码只输入在 Garmin 官方页面",
                                    fontSize = 15.sp,
                                    color = MiuiText1,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "本应用不保存密码。登录成功后会自动带 ticket 返回并添加账号。",
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixSummary,
                                )
                            }
                            MiuixDivider()
                            RiskConsentCard(
                                checked = state.riskAccepted,
                                onCheckedChange = viewModel::onRiskAccepted,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            MiuixPrimaryButton(
                                text = if (state.busy) "正在准备…" else "打开 Garmin 登录页",
                                onClick = viewModel::openWebLogin,
                                enabled = state.riskAccepted,
                                loading = state.busy,
                            )
                            if (!state.riskAccepted) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "需先勾选风险知情声明。",
                                    fontSize = 12.sp,
                                    color = MiuixSummary,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "页面一直转圈通常是网络被边缘防护拦截，切换 Wi-Fi / 移动数据后重试。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MiuixSummary,
                            )
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "已添加",
                                fontSize = 26.sp,
                                color = MiuiText1,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.successMessage ?: "可以返回首页管理账号。",
                                fontSize = 13.sp,
                                color = MiuixSummary,
                            )
                        }
                    }
                }

                state.errorMessage?.let { msg ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = msg,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MiuiRed,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "FitSync v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 11.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
