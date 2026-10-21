package com.fitgarminsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fitgarminsync.ui.theme.FitGarminSyncTheme
import com.fitgarminsync.ui.theme.MiuiCard
import com.fitgarminsync.ui.theme.MiuiRed
import com.fitgarminsync.ui.theme.MiuiText1
import com.fitgarminsync.ui.theme.MiuiText2

/**
 * 上次崩溃的堆栈展示页。
 * 真机拿不到 logcat 时，至少能把栈信息复制/分享/截图出来供定位。
 */
@Composable
fun CrashReportScreen(
    trace: String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val exceptionLine = trace.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
        ?: trace.lineSequence().firstOrNull().orEmpty()

    FitGarminSyncTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "上次运行崩溃了",
                style = MaterialTheme.typography.headlineMedium,
                color = MiuiRed,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = exceptionLine.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MiuiText1,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "把下面的堆栈「复制」或「分享」出来发给开发者即可定位问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MiuiText2,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = trace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuiCard)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MiuiText1,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onCopy(trace) },
                    modifier = Modifier.weight(1f),
                ) { Text("复制堆栈") }
                Button(
                    onClick = { onShare(trace) },
                    modifier = Modifier.weight(1f),
                ) { Text("分享堆栈") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("忽略并进入应用") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "FitGarminSync 崩溃堆栈")
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享崩溃堆栈").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
