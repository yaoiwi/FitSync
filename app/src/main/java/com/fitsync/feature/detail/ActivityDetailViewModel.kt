package com.fitsync.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitsync.data.db.ActivityLapEntity
import com.fitsync.data.db.ActivityRouteEntity
import com.fitsync.data.db.ActivitySampleEntity
import com.fitsync.data.db.FitSyncDatabase
import com.fitsync.data.sync.ActivityFileLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 活动详情用：读采样点与轨迹；库里没有采样点时按需下载原始文件补一次。
 *
 * 只自动补一次：失败把原因放进 [note]，不自动重试——详情页反复进出时
 * 每次重试都会重新下载几 MB，弱网下体感很差。要重试由用户点。
 */
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    private val db: FitSyncDatabase,
    private val fileLoader: ActivityFileLoader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val activityId: Long = savedStateHandle.get<Long>("activityId") ?: 0L

    private val _samples = MutableStateFlow<List<ActivitySampleEntity>>(emptyList())
    val samples: StateFlow<List<ActivitySampleEntity>> = _samples.asStateFlow()

    private val _routes = MutableStateFlow<List<ActivityRouteEntity>>(emptyList())
    val routes: StateFlow<List<ActivityRouteEntity>> = _routes.asStateFlow()

    /** 每公里 / 每圈的分段 */
    private val _laps = MutableStateFlow<List<ActivityLapEntity>>(emptyList())
    val laps: StateFlow<List<ActivityLapEntity>> = _laps.asStateFlow()

    /** 正在从 Garmin 下载原始文件 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 加载结果提示：成功为 null，失败是一句人话 */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    init {
        viewModelScope.launch {
            if (activityId <= 0L) return@launch
            _samples.value = db.activitySampleDao().byActivity(activityId)
            _routes.value = db.activityRouteDao().byActivity(activityId)
            _laps.value = db.activityLapDao().byActivity(activityId)
            if (_samples.value.isNotEmpty()) return@launch
            load()
        }
    }

    /** 用户手动重试 */
    fun retry() {
        if (_loading.value || activityId <= 0L) return
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _loading.value = true
        val result = runCatching { fileLoader.loadSamples(activityId) }
            .getOrElse { ActivityFileLoader.LoadResult(message = it.message ?: "加载失败") }
        _samples.value = db.activitySampleDao().byActivity(activityId)
        _routes.value = db.activityRouteDao().byActivity(activityId)
        _laps.value = db.activityLapDao().byActivity(activityId)
        _note.value = result.message
        _loading.value = false
    }
}
