package com.fitsync.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.SyncLogEntity
import com.fitsync.feature.home.HomeViewModel
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 同步记录：把 `sync_logs` 里发生过的事摊开给用户看。
 *
 * 之前同步失败只有一个会消失的 Toast 文案，用户回到首页只看到"今天没有数据"，
 * 完全没法判断是没同步、同步失败，还是 Garmin 那边没数据。
 */
@Composable
fun SyncLogsScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val logs by viewModel.syncLogs.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val aliases by viewModel.aliases.collectAsState()

    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = "同步记录",
                subtitle = if (logs.isEmpty()) "还没有记录" else "最近 ${logs.size} 次",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            ) {
                if (logs.isEmpty()) {
                    item {
                        MiuixSectionTitle("暂无记录")
                        MiuixCard {
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                                Text("同步一次之后，这里会显示时间、结果和条数。", fontSize = 14.sp, color = MiuixSummary)
                            }
                        }
                    }
                } else {
                    item {
                        MiuixSectionTitle("最近 ${logs.size} 次")
                        MiuixCard {
                            logs.forEachIndexed { index, log ->
                                LogRow(
                                    log = log,
                                    accountName = accountName(log.accountId, accounts, aliases),
                                )
                                if (index != logs.lastIndex) MiuixDivider(startIndent = 30)
                            }
                        }
                    }
                    item {
                        Text(
                            text = "失败的记录会保留原因；重试直接点右上角的同步。",
                            fontSize = 12.sp,
                            color = MiuixSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    log: SyncLogEntity,
    accountName: String,
) {
    val ok = log.status == "success"
    Row(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF34C759) else MiuixError)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(log.tsMs),
                    fontSize = 15.sp,
                    color = MiuiText1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ok) "成功" else "失败",
                    fontSize = 12.sp,
                    color = if (ok) Color(0xFF34C759) else MiuixError,
                )
            }
            Text(
                text = buildString {
                    append(accountName)
                    if (ok) {
                        append(" · ${log.records} 条")
                    } else if (log.errorMsg.isNotBlank()) {
                        append(" · ${log.errorMsg}")
                    }
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixSummary,
            )
        }
        if (log.durationMs > 0) {
            Text(
                text = "${log.durationMs / 1000} s",
                fontSize = 12.sp,
                color = MiuixSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun accountName(
    accountId: Long,
    accounts: List<AccountEntity>,
    aliases: Map<Long, String>,
): String {
    val account = accounts.firstOrNull { it.id == accountId } ?: return "已删除的账号"
    return aliases[account.id] ?: account.displayName.ifBlank { account.externalUserId }
}

private fun formatTime(tsMs: Long): String {
    if (tsMs <= 0) return "—"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = tsMs }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "M-d HH:mm"
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(tsMs))
}
