package com.fitgarminsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitgarminsync.ui.theme.*

/**
 * 登录向导 4 步共享组件（原型 Screen A/B/C）。
 */
@Composable
fun LoginWizardStepper(
    step: Int, // 1..4
    labels: List<String> = listOf("数据源", "区域", "登录", "完成"),
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val n = index + 1
            val state = when {
                n < step -> StepState.DONE
                n == step -> StepState.ACTIVE
                else -> StepState.IDLE
            }
            StepperItem(n = n, label = label, state = state)
            if (index != labels.lastIndex) {
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(if (n < step) MiuiGreen else MiuiText3.copy(alpha = 0.3f),
                            RoundedCornerShape(1.dp))
                )
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

private enum class StepState { IDLE, ACTIVE, DONE }

@Composable
private fun StepperItem(
    n: Int,
    label: String,
    state: StepState,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    when (state) {
                        StepState.ACTIVE -> MiuiAccent
                        StepState.DONE -> MiuiGreen
                        StepState.IDLE -> MiuiText3.copy(alpha = 0.25f)
                    },
                    shape = RoundedCornerShape(percent = 50)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (state) { StepState.DONE -> "✓" else -> n.toString() },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            color = when (state) {
                StepState.ACTIVE -> MiuiAccent
                StepState.DONE -> MiuiGreen
                StepState.IDLE -> MiuiText3
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 数据源选项卡（原型 Screen A：Garmin / Fitbit / 本地文件）。
 */
@Composable
fun DataSourceCard(
    iconText: String,
    iconBg: Color,
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MiuiAccent.copy(alpha = 0.06f) else MiuiCard,
                RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.5.dp,
                color = if (selected) MiuiAccent else MiuiText3.copy(alpha = 0.2f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(iconBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(iconText, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MiuiText1)
            Text(desc, style = MaterialTheme.typography.labelSmall,
                color = MiuiText3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, if (selected) MiuiAccent else MiuiText3.copy(alpha = 0.4f),
                    RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(10.dp).background(MiuiAccent, RoundedCornerShape(percent = 50)))
            }
        }
    }
}

/**
 * 账号卡 4 状态（原型 Screen E）。
 * status: normal / syncing / mfa / expired
 */
@Composable
fun AccountCard(
    accountName: String,
    subline: String,
    status: String,
    progress: Float? = null,
    onPrimaryAction: String,
    onPrimary: () -> Unit,
    onSecondary: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    metaLines: List<String> = emptyList(),
) {
    val statusColor = statusBarColor(status)
    val statusLabel = when (status) {
        "normal" -> "正常"
        "syncing" -> "同步中"
        "mfa" -> "需 MFA"
        "expired" -> "已过期"
        else -> status
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuiCard, RoundedCornerShape(14.dp))
            .border(1.dp, MiuiText3.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).background(statusColor))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(accountName, style = MaterialTheme.typography.titleSmall, color = MiuiText1)
                Text(subline, style = MaterialTheme.typography.labelSmall, color = MiuiText3)
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = statusColor.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.size(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }

        if (metaLines.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                metaLines.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MiuiText3)
                }
            }
        }

        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                Text(onPrimaryAction, fontSize = 13.sp)
            }
            if (onSecondary != null && onSecondaryClick != null) {
                OutlinedButton(onClick = onSecondaryClick, modifier = Modifier.weight(1f)) {
                    Text(onSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}