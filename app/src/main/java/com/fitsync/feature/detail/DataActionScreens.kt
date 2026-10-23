package com.fitsync.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.feature.home.HomeViewModel
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 「数据」分组里三个操作的独立页面。
 *
 * 原来点下去直接弹一个 AlertDialog（Material 样式，和 HyperOS 不搭），
 * 现在改成 HyperIsland 骨架的全屏页：顶部大标题，下面「说明 / 影响 / 操作」分区。
 */

// ============================ 导入本地数据 ============================

@Composable
fun ImportScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val syncState by viewModel.syncState.collectAsState()
    // 文件选择器放在页面内：选完直接交给本页的 ViewModel 解析，不需要回到首页
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment ?: "import"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.importFailed("无法读取所选文件（可能没有访问权限）")
        } else {
            viewModel.importData(bytes, name)
        }
    }

    DataActionPage(
        title = "导入本地数据",
        subtitle = "把导出的活动与健康数据合并进本地库",
        onBack = onBack,
    ) {
        item {
            MiuixSectionTitle("支持格式")
            MiuixCard {
                FormatRow("ZIP", "Garmin / Fitbit 官方导出包")
                MiuixDivider()
                FormatRow("JSON", "本应用或其它工具的 JSON 导出")
                MiuixDivider()
                FormatRow("CSV", "按列对齐的表格导出")
            }
        }
        item {
            MiuixSectionTitle("说明")
            MiuixCard {
                Text(
                    text = "导入只写本地数据库，不会上传到任何服务器；" +
                        "同一次活动按时间去重，重复导入不会产生副本。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            MiuixPrimaryButton(
                text = if (syncState.syncing) "正在导入…" else "选择文件",
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/json",
                            "text/csv",
                            "text/comma-separated-values",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !syncState.syncing,
                loading = syncState.syncing,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            syncState.message?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FormatRow(ext: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MiuixPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(ext, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MiuixPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(desc, fontSize = 14.5.sp, color = MiuiText1)
    }
}

// ============================ 复制活动到另一账号 ============================

@Composable
fun CopyActivitiesScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val garminAccounts = accounts.filter { it.sourceType == "garmin" }
    val target = garminAccounts.firstOrNull { it.id != primaryAccountId }

    DataActionPage(
        title = "复制活动",
        subtitle = "把主账号的运动记录上传到另一个 Garmin 账号",
        onBack = onBack,
    ) {
        item {
            MiuixSectionTitle("复制方向")
            MiuixCard {
                MiuixRow(
                    title = "来源",
                    summary = "主账号",
                    trailing = {
                        Text(
                            text = accounts.firstOrNull { it.id == primaryAccountId }
                                ?.let { aliases[it.id] ?: it.displayName.ifBlank { it.externalUserId } }
                                ?: "未设置",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "目标",
                    summary = target?.let { regionLabel(it.region) } ?: "需要另一个 Garmin 账号",
                    trailing = {
                        Text(
                            text = target?.let { aliases[it.id] ?: it.displayName.ifBlank { it.externalUserId } }
                                ?: "—",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                        )
                    },
                )
            }
        }
        item {
            MiuixSectionTitle("范围与限制")
            MiuixCard {
                BulletLine("复制最近 10 条活动：跑步、骑行、健身等运动记录")
                MiuixDivider(startIndent = 18)
                BulletLine("心率、睡眠、步数等健康数据 Garmin 不开放写入接口，无法复制")
                MiuixDivider(startIndent = 18)
                BulletLine("复制会在目标账号生成新记录，不可撤销")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            MiuixPrimaryButton(
                text = if (syncState.syncing) "正在复制…" else "开始复制",
                onClick = viewModel::copyActivitiesToOtherAccount,
                enabled = target != null && !syncState.syncing,
                loading = syncState.syncing,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            syncState.message?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ============================ 搬运健康数据 ============================

@Composable
fun CopyHealthScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val target = accounts.filter { it.sourceType == "garmin" }.firstOrNull { it.id != primaryAccountId }

    DataActionPage(
        title = "搬运健康数据",
        subtitle = "把主账号的步数、睡眠、压力上传到另一账号",
        onBack = onBack,
    ) {
        item {
            MiuixSectionTitle("搬运范围")
            MiuixCard {
                BulletLine("最近 7 天的步数、睡眠、压力，以 monitoring FIT 上传")
                MiuixDivider(startIndent = 18)
                BulletLine("目标账号必须已绑定这块手表，否则 Garmin 会拒绝")
                MiuixDivider(startIndent = 18)
                BulletLine("睡眠 / HRV 类数据 Garmin 只允许回溯约 1 个月")
            }
        }
        item {
            MiuixSectionTitle("目标账号")
            MiuixCard {
                MiuixRow(
                    title = target?.let { aliases[it.id] ?: it.displayName.ifBlank { it.externalUserId } }
                        ?: "还需要一个 Garmin 账号",
                    summary = target?.let { regionLabel(it.region) } ?: "先在账号页添加另一个区",
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            MiuixPrimaryButton(
                text = if (syncState.syncing) "正在搬运…" else "开始搬运",
                onClick = viewModel::copyHealthToOtherAccount,
                enabled = target != null && !syncState.syncing,
                loading = syncState.syncing,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "实验功能：上传结果取决于目标账号的设备绑定情况，失败不会损坏本地数据。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MiuixSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            syncState.message?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ============================ 公共骨架 ============================

@Composable
private fun DataActionPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = title,
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                content = content,
            )
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text("·", fontSize = 13.sp, color = MiuixSummary, modifier = Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MiuixSummary)
    }
}
