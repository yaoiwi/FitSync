package com.fitsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.ui.theme.MiuiAccent
import com.fitsync.ui.theme.MiuiCard
import com.fitsync.ui.theme.MiuiOrange
import com.fitsync.ui.theme.MiuiRed
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuiText2
import com.fitsync.ui.theme.MiuiText3
import com.fitsync.ui.theme.MiuiYellow

/**
 * MFA 6 位验证码输入框（原型 Screen D）。
 * 支持三通道切换提示：身份验证器 / 邮箱 / 短信。
 */
@Composable
fun MfaCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    channel: MfaChannel = MfaChannel.AUTHENTICATOR,
    onChannelClick: (MfaChannel) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 通道切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            MfaChannel.entries.forEach { ch ->
                val isActive = ch == channel
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isActive) MiuiAccent else MiuiCard)
                        .border(
                            width = 1.dp,
                            color = if (isActive) MiuiAccent else MiuiText3.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onChannelClick(ch) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (ch) {
                            MfaChannel.AUTHENTICATOR -> "📶 身份验证器"
                            MfaChannel.EMAIL -> "✉ 邮箱"
                            MfaChannel.SMS -> "📱 短信"
                        },
                        color = if (isActive) Color.White else MiuiText2,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(Modifier.size(20.dp))

        // 6 格输入：用单个不可见 BasicTextField 驱动输入，保留 MIUIX 六格视觉
        val focusRequester = remember { FocusRequester() }
        val inputTextFieldValue = TextFieldValue(value, TextRange(value.length, value.length))
        val inputTextStyle = TextStyle(
            color = Color.Transparent,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            for (i in 0 until 6) {
                val c = if (i < value.length) value[i].toString() else ""
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 52.dp)
                        .background(
                            color = MiuiCard,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (c.isNotEmpty()) MiuiAccent
                            else MiuiText3.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == 0) {
                        BasicTextField(
                            value = inputTextFieldValue,
                            onValueChange = { newValue ->
                                val digits = newValue.text.filter { ch -> ch.isDigit() }.take(6)
                                onValueChange(digits)
                            },
                            singleLine = true,
                            textStyle = inputTextStyle,
                            cursorBrush = SolidColor(MiuiAccent),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                capitalization = KeyboardCapitalization.None,
                            ),
                             decorationBox = { textField ->
                                 textField()
                             },
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .onFocusChanged { }
                                .padding(horizontal = 4.dp),
                        )
                    }
                    Text(
                        text = c,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MiuiText1,
                    )
                }
                if (i != 5) Spacer(Modifier.size(8.dp))
            }
        }

        Spacer(Modifier.size(12.dp))
        Text(
            text = "6 位码由 Garmin 生成，有效期约 2 分钟",
            style = MaterialTheme.typography.labelSmall,
            color = MiuiText3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

enum class MfaChannel { AUTHENTICATOR, EMAIL, SMS }

/**
 * 区域切换分段按钮（原型 Screen B：中国区 / 国际区）。
 */
@Composable
fun RegionSegmentedControl(
    selectedRegion: String, // "cn" / "com"
    onRegionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuiText3.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        listOf(
            Triple("cn", "🇨🇳 中国区", "sso.garmin.cn"),
            Triple("com", "🌐 国际区", "sso.garmin.com"),
        ).forEach { (code, label, host) ->
            val selected = code == selectedRegion
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onRegionSelected(code) }
                    .background(
                        color = if (selected) MiuiCard else Color.Transparent,
                        shape = RoundedCornerShape(11.dp)
                    )
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    color = if (selected) MiuiAccent else MiuiText2,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = host,
                    color = if (selected) MiuiAccent else MiuiText3,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 风险知情声明卡（原型 Screen C）。
 */
@Composable
fun RiskConsentCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuiCard, RoundedCornerShape(12.dp))
            .border(1.dp, MiuiText3.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MiuiAccent),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                text = "风险知情声明",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MiuiText1,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "本功能使用 Garmin 非官方接口，理论存在账号被限流 / 风控的可能。请 不要使用你的主账号 ，并建议定期在本 App 与官网交叉核对。继续即表示你已了解并接受。",
                style = MaterialTheme.typography.bodySmall,
                color = MiuiText2,
            )
        }
    }
}

/**
 * 设备端发起提示（本轮 Spike 结论：登录只能在本机发起）。
 */
@Composable
fun DeviceLocalLoginBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuiAccent.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, MiuiAccent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "📱",
            fontSize = 18.sp,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "登录只能在本机发起。出于安全，Garmin 校验来源网络，登录与 MFA 验证码必须在你这台设备上完成，不会经任何第三方服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MiuiText1,
        )
    }
}

/**
 * 登录向导底部辅助选项（原型 Screen C：粘贴 OAuth 凭证 / 改用文件导入）。
 */
@Composable
fun LoginAlternativesRow(
    onOauthClick: () -> Unit,
    onFileImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.size(12.dp))
        Text(
            text = "其他方式",
            style = MaterialTheme.typography.labelLarge,
            color = MiuiText3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOauthClick, modifier = Modifier.weight(1f)) {
                Text("🔑 粘贴 OAuth 凭证", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onFileImportClick, modifier = Modifier.weight(1f)) {
                Text("📁 改用文件导入", fontSize = 13.sp)
            }
        }
    }
}