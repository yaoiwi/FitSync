package com.fitsync.feature.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.ui.about.AboutContent
import com.fitsync.ui.about.OpenSourceGroup
import com.fitsync.ui.about.OpenSourceItem
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 开源致谢页（独立入口，与更新日志分开）。
 *
 * 按类别分组，点任意一项用浏览器打开该项目主页。
 */
@Composable
fun AcknowledgementsScreen(onBack: () -> Unit) {
    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = "开源致谢",
                subtitle = "${AboutContent.acknowledgements.sumOf { it.items.size }} 个开源项目",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            ) {
                item {
                    Text(
                        text = AboutContent.THANKS,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixSummary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                AboutContent.acknowledgements.forEach { group ->
                    item { AcknowledgementGroupCard(group) }
                }
                item {
                    Text(
                        text = "本应用与 Garmin Ltd. 无隶属关系；Garmin 是 Garmin Ltd. 的注册商标。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AcknowledgementGroupCard(group: OpenSourceGroup) {
    val context = LocalContext.current
    MiuixCard {
        Text(
            text = group.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixSummary,
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp),
        )
        MiuixDivider(startIndent = 18)
        group.items.forEachIndexed { index, item ->
            AcknowledgementRow(item) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                }
            }
            if (index != group.items.lastIndex) MiuixDivider(startIndent = 18)
        }
    }
}

@Composable
private fun AcknowledgementRow(item: OpenSourceItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontSize = 14.5.sp, color = MiuiText1)
                Spacer(Modifier.width(8.dp))
                LicenseTag(item.license)
            }
            Spacer(Modifier.height(2.dp))
            Text(item.role, fontSize = 11.5.sp, lineHeight = 16.sp, color = MiuixSummary)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 18.sp, color = MiuixSummary)
    }
}

@Composable
private fun LicenseTag(license: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Gray.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(license, fontSize = 9.5.sp, color = MiuixSummary)
    }
}
