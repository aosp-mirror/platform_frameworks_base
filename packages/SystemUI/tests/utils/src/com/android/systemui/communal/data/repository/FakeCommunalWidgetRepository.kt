package com.android.systemui.communal.data.repository

import android.content.ComponentName
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Fake implementation of [CommunalWidgetRepository] */
class FakeCommunalWidgetRepository(private val coroutineScope: CoroutineScope) :
    CommunalWidgetRepository {
    private val _communalWidgets = MutableStateFlow<List<CommunalWidgetContentModel>>(emptyList())
    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> = _communalWidgets
    private val _widgetAdded = MutableSharedFlow<Int>()
    val widgetAdded: Flow<Int> = _widgetAdded

    private var nextWidgetId = 1

    fun setCommunalWidgets(inventory: List<CommunalWidgetContentModel>) {
        _communalWidgets.value = inventory
    }

    override fun addWidget(
        provider: ComponentName,
        priority: Int,
        configureWidget: suspend (id: Int) -> Boolean
    ) {
        coroutineScope.launch {
            val id = nextWidgetId++
            if (configureWidget.invoke(id)) {
                _widgetAdded.emit(id)
            }
        }
    }
}
