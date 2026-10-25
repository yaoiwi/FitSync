package com.fitsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixCardSurface
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary

/**
 * HyperOS 风格对话框外壳：26dp 圆角卡片、标题 + 说明 + 内容 + 底部按钮行。
 *
 * 没用 Material3 的 AlertDialog，因为它的圆角、内边距、按钮排布都和 HyperOS 差得远，
 * 这里直接用 [Dialog] 加自绘卡片，视觉和页面里的卡片一致。
 */
@Composable
private fun DialogShell(
    title: String,
    onDismiss: () -> Unit,
    message: String? = null,
    content: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MiuixCardSurface)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp),
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuiText1,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = MiuixSummary,
                    lineHeight = 19.sp,
                )
            }
            content()
            Spacer(Modifier.height(10.dp))
            buttons()
        }
    }
}

/**
 * 文本输入对话框。
 *
 * [neutralText] 非空时在左侧多一个次要按钮（用于「恢复默认」这类操作）。
 */
@Composable
fun MiuixInputDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    placeholder: String = "",
    confirmText: String = "保存",
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val tick = rememberTick()

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    DialogShell(
        title = title,
        onDismiss = onDismiss,
        message = message,
        content = {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixGroupBg)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (draft.isEmpty() && placeholder.isNotEmpty()) {
                    Text(text = placeholder, fontSize = 15.sp, color = MiuixSummary)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(fontSize = 15.sp, color = MiuiText1),
                    singleLine = true,
                    cursorBrush = SolidColor(MiuixPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            tick()
                            onConfirm(draft)
                        },
                    ),
                )
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (neutralText != null && onNeutral != null) {
                    DialogTextAction(
                        text = neutralText,
                        color = MiuixSummary,
                        onClick = {
                            tick()
                            onNeutral()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
                DialogTextAction(
                    text = "取消",
                    color = MiuixSummary,
                    onClick = {
                        tick()
                        onDismiss()
                    },
                )
                Spacer(Modifier.width(4.dp))
                DialogTextAction(
                    text = confirmText,
                    color = MiuixPrimary,
                    emphasized = true,
                    onClick = {
                        tick()
                        onConfirm(draft)
                    },
                )
            }
        },
    )
}

/** 二次确认对话框，用于删除这类不可撤销操作 */
@Composable
fun MiuixConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    cancelText: String = "取消",
    destructive: Boolean = false,
) {
    val tick = rememberTick()
    DialogShell(
        title = title,
        onDismiss = onDismiss,
        message = message,
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogTextAction(
                    text = cancelText,
                    color = MiuixSummary,
                    onClick = {
                        tick()
                        onDismiss()
                    },
                )
                Spacer(Modifier.width(4.dp))
                DialogTextAction(
                    text = confirmText,
                    color = if (destructive) MiuixError else MiuixPrimary,
                    emphasized = true,
                    onClick = {
                        tick()
                        onConfirm()
                    },
                )
            }
        },
    )
}

@Composable
private fun DialogTextAction(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            color = color,
        )
    }
}
