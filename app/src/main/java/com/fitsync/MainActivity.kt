package com.fitsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fitsync.navigation.FitSyncRoot
import com.fitsync.sync.SyncCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var syncCoordinator: SyncCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 崩溃栈是落盘的，这里读取上一次崩溃记录；命中则先展示，方便真机上报
            var crashTrace by remember { mutableStateOf(CrashStore.load(applicationContext)) }
            if (crashTrace == null) {
                FitSyncRoot()
            } else {
                val context = LocalContext.current
                CrashReportScreen(
                    trace = crashTrace ?: "",
                    onCopy = { copyToClipboard(context, "FitSync crash", it) },
                    onShare = { shareText(context, it) },
                    onDismiss = {
                        CrashStore.consume(applicationContext)
                        crashTrace = null
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 打开 App / 回到前台就同步；协调器里有 5 分钟节流，避免反复拉数据
        syncCoordinator.autoSyncOnOpen()
    }
}
