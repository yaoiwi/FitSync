package com.fitsync.feature.personal

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixTextButton
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * Garmin 官方 Personal API 连接页。
 *
 * Personal 层是 2026-10 开放的免费只读接口：用户在 Garmin Connect 网页上
 * 自己创建 key，粘贴进来即可，不需要账号密码，也不经过任何私有接口。
 */
@Composable
fun PersonalApiScreen(
    onBack: () -> Unit,
    viewModel: PersonalApiViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }

    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = "Garmin 官方 API",
                subtitle = "只读 · 不需要账号密码",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            ) {
                item { StatusCard(connected = state.connected, lastSyncAtMs = state.lastSyncAtMs) }

                if (!state.connected) {
                    item {
                        MiuixSectionTitle("粘贴 API Key")
                        MiuixCard {
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Text(
                                    text = "在 Garmin Connect 网页「设置 → Personal API Key」创建一个 key，" +
                                        "复制后粘贴到这里。key 只显示一次，创建时记得保存。",
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixSummary,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    label = { androidx.compose.material3.Text("API Key") },
                                    singleLine = true,
                                    enabled = !state.running,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(10.dp))
                                MiuixTextButton(
                                    text = "打开 Garmin Connect 设置页",
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                        "https://connect.garmin.com/app/settings/personalApiToken",
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                if (state.running) {
                    item { ProgressCard(phase = state.phase, done = state.done, total = state.total) }
                }

                state.message?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = if (msg.startsWith("同步失败") || msg.startsWith("连接失败")) {
                                MiuixError
                            } else {
                                MiuixSummary
                            },
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    if (state.connected) {
                        MiuixPrimaryButton(
                            text = if (state.running) "同步中…" else "开始同步（最近 30 天）",
                            onClick = viewModel::sync,
                            enabled = !state.running,
                            loading = state.running,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        MiuixTextButton(
                            text = "断开连接",
                            onClick = {
                                keyInput = ""
                                viewModel.disconnect()
                            },
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MiuixPrimaryButton(
                            text = if (state.running) "验证中…" else "连接",
                            onClick = { viewModel.connect(keyInput) },
                            enabled = keyInput.isNotBlank() && !state.running,
                            loading = state.running,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                item {
                    MiuixSectionTitle("能拿到什么")
                    MiuixCard {
                        Bullet("每日汇总：步数、距离、楼层、热量、静息心率、压力、Body Battery")
                        MiuixDivider(startIndent = 18)
                        Bullet("HRV：昨夜均值、周均值与基线")
                        MiuixDivider(startIndent = 18)
                        Bullet("活动列表 + 完整 FIT 文件（轨迹、逐秒心率、配速、海拔）")
                    }
                }

                item {
                    MiuixSectionTitle("限制")
                    MiuixCard {
                        Bullet("只读：不能写回 Garmin，所以复制活动 / 搬运健康数据仍走原来的登录方式")
                        MiuixDivider(startIndent = 18)
                        Bullet("一个 key 只对应一个账号；双区需要各建一个")
                        MiuixDivider(startIndent = 18)
                        Bullet("token 24 小时过期，App 会自动重新换取")
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusCard(connected: Boolean, lastSyncAtMs: Long) {
    MiuixCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (connected) Color(0xFF34C759) else MiuixSummary),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = if (connected) "已连接" else "未连接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuiText1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (lastSyncAtMs > 0) {
                        "上次同步 " + java.text.SimpleDateFormat(
                            "MM-dd HH:mm",
                            java.util.Locale.CHINA,
                        ).format(java.util.Date(lastSyncAtMs))
                    } else {
                        "还没有同步过"
                    },
                    fontSize = 12.sp,
                    color = MiuixSummary,
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(phase: String?, done: Int, total: Int) {
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    MiuixCard {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = phase ?: "准备中",
                fontSize = 13.sp,
                color = MiuiText1,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixSummary.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(MiuixPrimary),
                )
            }
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                Text("$done / $total", fontSize = 11.5.sp, color = MiuixSummary)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text("·", fontSize = 13.sp, color = MiuixSummary, modifier = Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MiuixSummary)
    }
}
