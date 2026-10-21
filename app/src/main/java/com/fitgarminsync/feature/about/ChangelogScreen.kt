package com.fitgarminsync.feature.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitgarminsync.BuildConfig
import com.fitgarminsync.ui.about.AboutContent
import com.fitgarminsync.ui.components.GlassHost
import com.fitgarminsync.ui.components.HyperPage
import com.fitgarminsync.ui.components.MiuixCard
import com.fitgarminsync.ui.components.MiuixDivider
import com.fitgarminsync.ui.theme.FitGarminSyncTheme
import com.fitgarminsync.ui.theme.MiuiText1
import com.fitgarminsync.ui.theme.MiuixPrimary
import com.fitgarminsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/** 更新日志页（独立入口，与开源致谢分开） */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    FitGarminSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = "更新日志",
                subtitle = "当前版本 ${BuildConfig.VERSION_NAME}",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            ) {
                item {
                    MiuixCard {
                        AboutContent.changeLogs.forEachIndexed { index, entry ->
                            ChangeLogBlock(
                                version = entry.version,
                                date = entry.date,
                                highlights = entry.highlights,
                                isLatest = index == 0,
                            )
                            if (index != AboutContent.changeLogs.lastIndex) {
                                MiuixDivider(startIndent = 18)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
internal fun ChangeLogBlock(
    version: String,
    date: String,
    highlights: List<String>,
    isLatest: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = version,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuiText1,
            )
            if (isLatest) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("最新", fontSize = 10.sp, color = MiuixPrimary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(date, fontSize = 11.5.sp, color = MiuixSummary)
        }
        Spacer(Modifier.height(6.dp))
        highlights.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("·", fontSize = 12.5.sp, color = MiuixSummary, modifier = Modifier.width(10.dp))
                Text(text = line, fontSize = 12.5.sp, lineHeight = 18.sp, color = MiuixSummary)
            }
        }
    }
}
