package com.fitsync.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.fitsync.BuildConfig
import com.fitsync.ui.components.HapticFeedback
import com.fitsync.ui.components.Haptics
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.ThemeModePref
import com.fitsync.ui.theme.UiStyle

// ============================ 设置 ============================

/** 冷却剩余时间的中文文案；超过一天要带天数，避免「23 小时」被理解成今晚就好 */
private fun cooldownLabel(ms: Long): String {
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        ms < minute -> "${(ms / 1000).coerceAtLeast(1)} 秒"
        ms < hour -> "${ms / minute} 分钟"
        ms < day -> {
            val h = ms / hour
            val m = (ms % hour) / minute
            if (m > 0) "$h 小时 $m 分" else "$h 小时"
        }
        else -> {
            val d = ms / day
            val h = (ms % day) / hour
            if (h > 0) "$d 天 $h 小时" else "$d 天"
        }
    }
}

@Composable
internal fun SettingsTab(
    themeMode: String = ThemeModePref.FOLLOW,
    onThemeModeChange: (String) -> Unit = {},
    uiStyle: UiStyle = UiStyle.Miuix,
    onUiStyleChange: (UiStyle) -> Unit = {},
    floatingNavBar: Boolean = true,
    onFloatingNavBarChange: (Boolean) -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
    onBackupDatabase: (android.net.Uri) -> Unit = {},
    onRestoreDatabase: (android.net.Uri) -> Unit = {},
) {
    val themeOptions = listOf("跟随系统", "浅色", "深色")
    val themeCodes = listOf(ThemeModePref.FOLLOW, ThemeModePref.LIGHT, ThemeModePref.DARK)
    val settingsContext = LocalContext.current
    var hapticsOn by remember { mutableStateOf(Haptics.isEnabled(settingsContext)) }
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(onBackupDatabase) }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestoreDatabase) }

    HyperPage(
        title = "设置",
        subtitle = "数据备份、外观与版本信息",
    ) {
        item {
            MiuixSectionTitle("数据")
            MiuixCard {
                MiuixRow(
                    title = "备份数据库",
                    summary = "导出到本地文件，换机可恢复；不含登录凭据",
                    showArrow = true,
                    onClick = { backupLauncher.launch("FitSync-${TimeFmt.fileStamp()}.db") },
                )
                MiuixDivider()
                MiuixRow(
                    title = "恢复数据库",
                    summary = "覆盖当前数据，重启后生效；账号需重新登录",
                    showArrow = true,
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf("application/octet-stream", "application/x-sqlite3")
                        )
                    },
                )
            }
        }

        item {
            MiuixSectionTitle("外观")
            MiuixCard {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        text = "界面风格",
                        fontSize = 16.sp,
                        color = MiuiText1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "HyperOS 是圆角卡片 + 胶囊导航；Material 3 是分层卡片 + 标准导航栏。" +
                            "换风格不影响深色设置",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    MiuixSegmentedControl(
                        options = listOf("HyperOS", "Material 3"),
                        selectedIndex = if (uiStyle == UiStyle.Md3) 1 else 0,
                        onSelect = {
                            onUiStyleChange(if (it == 1) UiStyle.Md3 else UiStyle.Miuix)
                            Haptics.perform(settingsContext, HapticFeedback.Confirm)
                        },
                    )
                }
                MiuixDivider()
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        text = "深色模式",
                        fontSize = 16.sp,
                        color = MiuiText1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "切换后全站立即生效；跟随系统会随系统设置自动变",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    MiuixSegmentedControl(
                        options = themeOptions,
                        selectedIndex = themeCodes.indexOf(themeMode).coerceAtLeast(0),
                        onSelect = { onThemeModeChange(themeCodes[it]) },
                    )
                }
                MiuixDivider()
                MiuixRow(
                    title = "悬浮导航栏",
                    summary = if (floatingNavBar) {
                        "液态玻璃悬浮样式，可左右拖动切换"
                    } else {
                        "当前为贴底标准导航栏"
                    },
                    trailing = {
                        MiuixSwitch(
                            checked = floatingNavBar,
                            onCheckedChange = { enabled ->
                                onFloatingNavBarChange(enabled)
                                Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "触感反馈",
                    summary = "点击、切换、完成时轻震一下",
                    trailing = {
                        MiuixSwitch(
                            checked = hapticsOn,
                            onCheckedChange = { enabled ->
                                hapticsOn = enabled
                                Haptics.setEnabled(settingsContext, enabled)
                                if (enabled) Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
            }
        }

        item {
            MiuixSectionTitle("关于")
            MiuixCard {
                MiuixRow(
                    title = "版本",
                    trailing = {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "数据来源",
                    summary = "Garmin Connect 中国区 / 国际区，本地加密存储",
                )
                MiuixDivider()
                MiuixRow(
                    title = "项目主页",
                    summary = "GitHub 上的 FitSync 仓库",
                    showArrow = true,
                    onClick = { openExternal(settingsContext, PROJECT_URL) },
                )
                MiuixDivider()
                MiuixRow(
                    title = "问题反馈",
                    summary = "遇到问题去提 Issue",
                    showArrow = true,
                    onClick = { openExternal(settingsContext, ISSUES_URL) },
                )
                MiuixDivider()
                MiuixRow(
                    title = "更新日志",
                    summary = "每个版本改了什么",
                    showArrow = true,
                    onClick = onOpenChangelog,
                )
                MiuixDivider()
                MiuixRow(
                    title = "开源致谢",
                    summary = "用到的所有开源项目与许可证",
                    showArrow = true,
                    onClick = onOpenAcknowledgements,
                )
            }
        }

    }
}

// ============================ 工具 ============================
// 格式化与状态映射统一放在 core-ui 的 Formatters.kt，账号详情 / 活动详情等页面共用。

/** 打开外部链接；没有浏览器时静默失败，不崩应用 */
private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private const val PROJECT_URL = "https://github.com/yaoiwi/FitSync"
private const val ISSUES_URL = "https://github.com/yaoiwi/FitSync/issues"
