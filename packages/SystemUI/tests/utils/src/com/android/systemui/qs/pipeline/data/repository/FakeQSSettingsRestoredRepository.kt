package com.android.systemui.qs.pipeline.data.repository

import com.android.systemui.qs.pipeline.data.model.RestoreData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeQSSettingsRestoredRepository : QSSettingsRestoredRepository {
    private val _restoreData = MutableSharedFlow<RestoreData>()

    override val restoreData: Flow<RestoreData>
        get() = _restoreData

    suspend fun onDataRestored(restoreData: RestoreData) {
        _restoreData.emit(restoreData)
    }
}
