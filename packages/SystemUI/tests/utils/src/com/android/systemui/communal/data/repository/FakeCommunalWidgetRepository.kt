package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Fake implementation of [CommunalWidgetRepository] */
class FakeCommunalWidgetRepository(private val coroutineScope: CoroutineScope) :
    CommunalWidgetRepository {
    private val _communalWidgets = MutableStateFlow<List<CommunalWidgetContentModel>>(emptyList())
    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> = _communalWidgets

    private var nextWidgetId = 1

    fun setCommunalWidgets(inventory: List<CommunalWidgetContentModel>) {
        _communalWidgets.value = inventory
    }

    override fun addWidget(
        provider: ComponentName,
        priority: Int,
        configurator: WidgetConfigurator?
    ) {
        coroutineScope.launch {
            val id = nextWidgetId++
            val providerInfo = AppWidgetProviderInfo().apply { this.provider = provider }
            val configured = configurator?.configureWidget(id) ?: true
            if (configured) {
                onConfigured(id, providerInfo, priority)
            }
        }
    }

    override fun deleteWidget(widgetId: Int) {
        if (_communalWidgets.value.none { it.appWidgetId == widgetId }) {
            return
        }

        _communalWidgets.value = _communalWidgets.value.filter { it.appWidgetId != widgetId }
    }

    private fun onConfigured(id: Int, providerInfo: AppWidgetProviderInfo, priority: Int) {
        _communalWidgets.value += listOf(CommunalWidgetContentModel(id, providerInfo, priority))
    }
}
