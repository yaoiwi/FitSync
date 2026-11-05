package com.fitsync.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.data.settings.HealthCopyLedger
import com.fitsync.feature.home.HomeViewModel
import com.fitsync.feature.home.TimeFmt
import com.fitsync.permission.rememberNotificationPermissionGate
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.components.MiuixTextButton
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.components.accountTitle
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import java.time.LocalDate
import kotlinx.coroutines.launch
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
    val accounts by viewModel.accounts.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val garminAccounts = accounts.filter { it.sourceType == "garmin" }
    // null = 只进本机的「本地导入」账号；选了账号则写进该账号并自动写回 Garmin
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
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
            viewModel.importData(bytes, name, targetAccountId)
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
            MiuixSectionTitle("导入到")
            MiuixCard {
                MiuixRow(
                    title = "仅本地",
                    summary = if (targetAccountId == null) {
                        "已选择 · 只写本机，不消耗 Garmin 请求"
                    } else {
                        "只写本机，不上传到 Garmin"
                    },
                    onClick = { targetAccountId = null },
                )
                garminAccounts.forEach { account ->
                    MiuixDivider(startIndent = 18)
                    val selected = targetAccountId == account.id
                    MiuixRow(
                        title = aliases[account.id] ?: accountTitle(account.displayName, account.region),
                        summary = if (selected) {
                            "已选择 · 导入后自动写回这个账号"
                        } else {
                            "${regionLabel(account.region)} · 导入后自动写回"
                        },
                        onClick = { targetAccountId = account.id },
                    )
                }
            }
        }
        item {
            MiuixSectionTitle("说明")
            MiuixCard {
                Text(
                    text = "选具体账号时：数据写进该账号，并把这些天的步数、睡眠、压力" +
                        "重新生成 monitoring 文件上传到 Garmin（一天一个请求，可中断续跑）。" +
                        "选「仅本地」则完全不联网。\n\n" +
                        "同一天的数据按日期合并：文件里有值的字段才更新，" +
                        "文件里没有的字段（如压力、血氧、身体电量）保留本地已有值，不会被清零；" +
                        "重复导入同一份文件不会产生副本，也不会改动任何数据。",
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
                                ?.let { aliases[it.id] ?: accountTitle(it.displayName, it.region) }
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
                            text = target?.let { aliases[it.id] ?: accountTitle(it.displayName, it.region) }
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
                BulletLine("心率、睡眠、步数等健康数据不在这个入口，请用「搬运健康数据」")
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

/**
 * 跨度对应的耗时预估。
 *
 * 按「一天约 3 个请求、每个请求 1.5 秒」估算，再算上 200 天一片的 20 秒休息。
 * 写出来是为了让用户在点之前就知道 365 天要等二三十分钟，而不是点了以后以为卡死。
 */
private fun estimateLabel(days: Int): String = when {
    days <= 7 -> "约 30 秒、20 个请求"
    days <= 30 -> "约 2 分钟、90 个请求"
    days <= 90 -> "约 6 分钟、270 个请求"
    days <= 365 -> "约 25 分钟、1100 个请求，分 2 片自动续跑；没被限流会自动提速，期间通知栏显示进度"
    // 730 天正好是 Garmin 对第三方开放的健康数据回溯上限，再往前要不到
    else -> "约 50 分钟、2200 个请求，分 4 片自动续跑；" +
        "已经搬过的日期会先跳过，实际只跑缺的那些天"
}

/** 跨度文案：365 / 730 用年，其余用天。分段控件、按钮、弹窗共用一套，避免两处写死 */
private fun spanLabel(days: Int): String = when (days) {
    365 -> "1 年"
    730 -> "2 年"
    else -> "$days 天"
}

@Composable
fun CopyHealthScreen(
    onBack: () -> Unit,
    onOpenReport: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val copyDays by viewModel.copyHealthDays.collectAsState()
    val riskAcknowledged by viewModel.copyRiskAcknowledged.collectAsState()
    // 长任务要挂前台服务，进度靠通知展示；Android 13+ 在此刻问一次最合适
    val ensureNotification = rememberNotificationPermissionGate()
    var showRisk by remember { mutableStateOf(false) }
    var dontAskAgain by remember { mutableStateOf(false) }
    val garminAccounts = accounts.filter { it.sourceType == "garmin" }
    val source = garminAccounts.firstOrNull { it.id == primaryAccountId } ?: garminAccounts.firstOrNull()
    val target = garminAccounts.firstOrNull { it.id != source?.id }
    // 730 天是 Garmin 对第三方开放的健康数据回溯上限（与 HealthSyncService.MAX_COPY_DAYS 一致）
    val dayOptions = listOf(7, 30, 90, 365, 730)
    val dayIndex = dayOptions.indexOf(copyDays).let { if (it < 0) 0 else it }

    // 上一次搬运停在哪：跟着搬运状态变（消息一变就重读一次台账）
    val progress = remember(syncState.syncing, syncState.message, source?.id, target?.id) {
        viewModel.copyProgressOf(source?.id, target?.id)
    }
    val paceLabel = remember(syncState.syncing) { viewModel.copyPaceLabel() }
    val remaining = progress?.let {
        HealthCopyLedger.remainingDays(it.oldestDate, dayOptions[dayIndex], LocalDate.now())
    }
    // 上次没跑完（进程被清掉 / 撞限流主动停）：只有进这个页面才读，不打扰首页
    val interrupted = remember(syncState.syncing, syncState.message) { viewModel.interruptedCopy() }

    DataActionPage(
        title = "搬运健康数据",
        subtitle = "把主账号的健康数据转发到另一账号",
        onBack = onBack,
    ) {
        if (interrupted != null && !syncState.syncing) {
            item {
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(
                            text = "上次搬运没跑完",
                            fontSize = 16.sp,
                            color = MiuiText1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "上次选的是 ${interrupted.days} 天范围，中途停下了" +
                                "（锁屏被系统清掉，或撞到限流）。已经搬过去的天数都记着，" +
                                "继续会从断点往前走，不会重来。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MiuixSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            MiuixTextButton(
                                text = "不再提示",
                                onClick = { viewModel.dismissInterruptedCopy() },
                            )
                            Spacer(Modifier.width(8.dp))
                            MiuixPrimaryButton(
                                text = "继续搬运",
                                onClick = {
                                    viewModel.setCopyHealthDays(interrupted.days)
                                    viewModel.dismissInterruptedCopy()
                                    ensureNotification { viewModel.copyHealthToOtherAccount() }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            MiuixSectionTitle("搬运范围")
            MiuixCard {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        text = "往前搬多少天",
                        fontSize = 13.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    MiuixSegmentedControl(
                        options = dayOptions.map(::spanLabel),
                        selectedIndex = dayIndex,
                        onSelect = { viewModel.setCopyHealthDays(dayOptions[it]) },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = estimateLabel(dayOptions[dayIndex]),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 并发档位会随「连续多少天没被限流」自己变，说清楚它就不像玄学
                    Text(
                        text = paceLabel,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary.copy(alpha = 0.85f),
                    )
                    if (progress != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append("上次搬到 ").append(progress.oldestDate)
                                append("（记着 ").append(progress.copiedDays).append(" 天）")
                                if (remaining == null || remaining <= 0) {
                                    append("，选中的这些天都搬过了")
                                } else {
                                    append("，选中的 ").append(dayOptions[dayIndex])
                                    append(" 天里还有约 ").append(remaining).append(" 天要搬")
                                }
                            },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixSummary,
                        )
                        // 用户主动要求「现在就再核一遍」时用：正常流程会按窗口自己复查，
                        // 但这个按钮能立刻重来，不用等 3 天/14 天的计时
                        Spacer(Modifier.height(8.dp))
                        MiuixTextButton(
                            text = "重新核对最近 3 天",
                            onClick = {
                                ensureNotification { viewModel.recheckRecentCopy() }
                            },
                        )
                        Text(
                            text = "把这 3 天的记账作废再下载一次，只补新出现的文件。" +
                                "手表拖了几天才把睡眠传上云时，用这个不用等自动复查",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MiuixSummary.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
        item {
            MiuixSectionTitle("核对")
            MiuixCard {
                MiuixRow(
                    title = "搬运体检",
                    summary = "按天核对：哪天有睡眠、哪天还在等手表补传、哪天是空包",
                    showArrow = true,
                    onClick = onOpenReport,
                )
            }
        }
        item {
            MiuixSectionTitle("风险说明")
            MiuixCard {
                BulletLine("和日常同步共用 Garmin 的请求配额：短时间内反复触发限流，账号会被锁 48 小时，期间所有同步都停")
                MiuixDivider(startIndent = 18)
                BulletLine("一天一个数据包，睡眠、压力、身体电量都在里面一起搬，不用单独操作")
                MiuixDivider(startIndent = 18)
                BulletLine("某天只有部分文件传上去时不算完成，这次不记账，再点一次会只补缺的那个（比如睡眠）")
                MiuixDivider(startIndent = 18)
                BulletLine("搬过的日期开工前直接跳过、不再发请求；最近 3 天会再核对一次文件清单，还没出现睡眠的日期会一直盯到 14 天——手表隔了一周才补传也接得住")
                MiuixDivider(startIndent = 18)
                BulletLine("目标账号已有同一天的文件会跳过，不覆盖、不删除原有数据")
                MiuixDivider(startIndent = 18)
                BulletLine("源账号那天没戴表睡（包里没有睡眠文件）就搬不过去——只转发已有数据，不会凭空生成")
                MiuixDivider(startIndent = 18)
                BulletLine("目标账号必须已绑定并激活 wellness 设备，否则 Garmin 会拒绝（419）")
                MiuixDivider(startIndent = 18)
                BulletLine("不可撤销：搬过去的数据只能在 Garmin Connect 网页端逐条删除")
                MiuixDivider(startIndent = 18)
                BulletLine("搬完要在目标账号下同步一次，首页才看得到")
                MiuixDivider(startIndent = 18)
                BulletLine("搬运期间通知栏显示进度，可随时点「停止」；锁屏也会继续跑，嫌慢可到「设置 → 同步 → 后台加速」放行")
            }
        }
        item {
            MiuixSectionTitle("源账号")
            MiuixCard {
                MiuixRow(
                    title = source?.let { aliases[it.id] ?: accountTitle(it.displayName, it.region) }
                        ?: "还没有 Garmin 账号",
                    summary = listOfNotNull(
                        source?.let { regionLabel(it.region) },
                        "搬运来源 · 由「主账号」决定",
                    ).joinToString(" · "),
                )
            }
        }
        item {
            MiuixSectionTitle("目标账号")
            MiuixCard {
                MiuixRow(
                    title = target?.let { aliases[it.id] ?: accountTitle(it.displayName, it.region) }
                        ?: "还需要一个 Garmin 账号",
                    summary = target?.let { regionLabel(it.region) } ?: "先在账号页添加另一个区",
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            MiuixPrimaryButton(
                text = if (syncState.syncing) {
                    "正在搬运…"
                } else {
                    "开始搬运（${spanLabel(copyDays)}）"
                },
                onClick = {
                    // 两层确认：通用风险只弹一次；1 年这种长跨度每次都提醒耗时与中断风险
                    if (!riskAcknowledged || copyDays >= 365) {
                        showRisk = true
                    } else {
                        ensureNotification { viewModel.copyHealthToOtherAccount() }
                    }
                },
                enabled = target != null && !syncState.syncing,
                loading = syncState.syncing,
                modifier = Modifier.padding(horizontal = 16.dp),
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

    if (showRisk) {
        val longSpan = copyDays >= 365
        MiuixActionDialog(
            title = if (longSpan && riskAcknowledged) "这次会跑很久" else "开始前请了解",
            confirmText = "我已了解，开始",
            onConfirm = {
                showRisk = false
                if (dontAskAgain) viewModel.acknowledgeCopyRisk()
                ensureNotification { viewModel.copyHealthToOtherAccount() }
            },
            onDismiss = { showRisk = false },
        ) {
            Column {
                if (!riskAcknowledged) {
                    RiskLine("① 和日常同步共用 Garmin 的请求配额：短时间内反复触发限流，账号会被锁 48 小时，期间所有同步都停")
                    RiskLine("② 目标账号已有同一天的文件会跳过，不覆盖、不删除原有数据")
                    RiskLine("③ 目标账号没绑定并激活 wellness 设备时，Garmin 会直接拒绝（419）")
                    RiskLine("④ 不可撤销：搬过去的数据只能在 Garmin Connect 网页端逐条删除")
                }
                if (longSpan) {
                    // 只有 365 / 730 两档会走到这里，直接按档给数字，别再乘除
                    val minutes = if (copyDays > 365) 50 else 25
                    val requests = if (copyDays > 365) 2200 else 1100
                    val shards = (copyDays + 199) / 200
                    Text(
                        text = "本次约 $minutes 分钟、约 $requests 个请求，分 $shards 片自动续跑" +
                            "（默认两天并行，连续 20 天没被限流会自动提到三天，撞到 429 就退回一天）。" +
                            "开始后通知栏会有一条常驻进度，可以锁屏、切到别的应用，只要通知还在就还在搬；" +
                            "想停随时点通知里的「停止」。中断不会白跑：已搬的天数都保留，下次会从断点接着搬。" +
                            "已经搬过的日期会在开工前跳过，不会再打一遍请求；只有最近 3 天会再核对一次" +
                            "文件清单——手表当晚才补传的睡眠就是靠这一步补进去的。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixSummary,
                    )
                }
                if (!riskAcknowledged) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixGroupBg)
                            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("以后不再提示这几条", fontSize = 14.sp, color = MiuiText1)
                            Text(
                                text = "长跨度仍会提醒耗时",
                                fontSize = 11.sp,
                                color = MiuixSummary,
                            )
                        }
                        MiuixSwitch(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it },
                        )
                    }
                }
            }
        }
    }
}

/** 弹窗里的风险条目，统一字号与行距 */
@Composable
private fun RiskLine(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MiuixSummary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ============================ 导出数据 ============================

@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun writeTo(uri: android.net.Uri?, format: HomeViewModel.ExportFormat) {
        if (uri == null) return
        busy = true
        message = null
        scope.launch {
            val outcome = runCatching {
                val file = viewModel.buildExport(format)
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                    ?: error("无法写入所选位置")
                file
            }
            busy = false
            message = outcome.fold(
                onSuccess = { f ->
                    "已导出 ${f.summary.activities} 条活动、${f.summary.days} 天汇总、" +
                        "${f.summary.nights} 晚睡眠（${(f.bytes.size / 1024).coerceAtLeast(1)} KB）"
                },
                onFailure = { "导出失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HomeViewModel.ExportFormat.JSON.mime),
    ) { uri -> writeTo(uri, HomeViewModel.ExportFormat.JSON) }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HomeViewModel.ExportFormat.CSV.mime),
    ) { uri -> writeTo(uri, HomeViewModel.ExportFormat.CSV) }

    DataActionPage(
        title = "导出数据",
        subtitle = "把本地库里的记录保存成文件",
        onBack = onBack,
    ) {
        item {
            MiuixSectionTitle("包含什么")
            MiuixCard {
                BulletLine("运动记录：类型、开始时间、时长、距离、心率、热量、爬升")
                MiuixDivider(startIndent = 18)
                BulletLine("每日汇总：步数、距离、楼层、活动/静息热量、静息心率、压力、血氧")
                MiuixDivider(startIndent = 18)
                BulletLine("睡眠：时长、在床时长、评分、平均心率与呼吸率")
                MiuixDivider(startIndent = 18)
                BulletLine("不包含账号密码与访问令牌，导出文件里拿不到登录凭据")
            }
        }
        item {
            MiuixSectionTitle("格式")
            MiuixCard {
                FormatRow("JSON", "字段完整，可以用「导入本地数据」再读回来")
                MiuixDivider()
                FormatRow("ZIP", "三个 CSV：活动 / 日汇总 / 睡眠，方便表格软件打开")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            MiuixPrimaryButton(
                text = if (busy) "正在导出…" else "导出 JSON",
                onClick = { jsonLauncher.launch(exportFileName("json")) },
                enabled = !busy,
                loading = busy,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            MiuixTextButton(
                text = "导出 CSV（ZIP）",
                onClick = { zipLauncher.launch(exportFileName("zip")) },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            message?.let { msg ->
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

private fun exportFileName(ext: String): String = "fitsync-${TimeFmt.fileStamp()}.$ext"

// ============================ 公共骨架 ============================

@Composable
internal fun DataActionPage(
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
