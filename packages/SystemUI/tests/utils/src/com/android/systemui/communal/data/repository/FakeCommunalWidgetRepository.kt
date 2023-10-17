package com.android.systemui.communal.data.repository

import com.android.systemui.communal.data.model.CommunalWidgetMetadata
import com.android.systemui.communal.shared.CommunalAppWidgetInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [CommunalWidgetRepository] */
class FakeCommunalWidgetRepository : CommunalWidgetRepository {
    private val _stopwatchAppWidgetInfo = MutableStateFlow<CommunalAppWidgetInfo?>(null)
    override val stopwatchAppWidgetInfo: Flow<CommunalAppWidgetInfo?> = _stopwatchAppWidgetInfo
    override var communalWidgetAllowlist: List<CommunalWidgetMetadata> = emptyList()

    fun setStopwatchAppWidgetInfo(appWidgetInfo: CommunalAppWidgetInfo) {
        _stopwatchAppWidgetInfo.value = appWidgetInfo
    }
}
