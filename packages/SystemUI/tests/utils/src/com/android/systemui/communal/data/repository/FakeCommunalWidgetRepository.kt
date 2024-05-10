package com.android.systemui.communal.data.repository

import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [CommunalWidgetRepository] */
class FakeCommunalWidgetRepository : CommunalWidgetRepository {
    private val _communalWidgets = MutableStateFlow<List<CommunalWidgetContentModel>>(emptyList())
    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> = _communalWidgets

    fun setCommunalWidgets(inventory: List<CommunalWidgetContentModel>) {
        _communalWidgets.value = inventory
    }
}
