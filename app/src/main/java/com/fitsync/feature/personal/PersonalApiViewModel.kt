package com.fitsync.feature.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitsync.data.sync.PersonalApiSyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalApiViewModel @Inject constructor(
    private val engine: PersonalApiSyncEngine,
) : ViewModel() {

    val state: StateFlow<PersonalApiSyncEngine.UiState> = engine.state

    fun connect(apiKey: String) = viewModelScope.launch { engine.connect(apiKey) }

    fun sync() = viewModelScope.launch { engine.sync(days = 30) }

    fun disconnect() = engine.disconnect()
}
