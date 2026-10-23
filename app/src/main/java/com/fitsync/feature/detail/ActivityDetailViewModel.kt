package com.fitsync.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitsync.data.db.ActivityRouteEntity
import com.fitsync.data.db.ActivitySampleEntity
import com.fitsync.data.db.FitSyncDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 活动详情用：读 FIT 解析出来的采样点与轨迹 */
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    db: FitSyncDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val activityId: Long = savedStateHandle.get<Long>("activityId") ?: 0L

    private val _samples = MutableStateFlow<List<ActivitySampleEntity>>(emptyList())
    val samples: StateFlow<List<ActivitySampleEntity>> = _samples.asStateFlow()

    private val _routes = MutableStateFlow<List<ActivityRouteEntity>>(emptyList())
    val routes: StateFlow<List<ActivityRouteEntity>> = _routes.asStateFlow()

    init {
        viewModelScope.launch {
            if (activityId <= 0L) return@launch
            _samples.value = db.activitySampleDao().byActivity(activityId)
            _routes.value = db.activityRouteDao().byActivity(activityId)
        }
    }
}
