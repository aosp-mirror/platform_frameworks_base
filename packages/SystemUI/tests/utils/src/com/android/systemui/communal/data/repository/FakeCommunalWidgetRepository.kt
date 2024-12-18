package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.UserHandle
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Fake implementation of [CommunalWidgetRepository] */
class FakeCommunalWidgetRepository(private val coroutineScope: CoroutineScope) :
    CommunalWidgetRepository {
    private val fakeDatabase = mutableMapOf<Int, CommunalWidgetContentModel>()

    private val _communalWidgets = MutableStateFlow<List<CommunalWidgetContentModel>>(emptyList())
    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> = _communalWidgets

    private var nextWidgetId = 1

    fun setCommunalWidgets(inventory: List<CommunalWidgetContentModel>) {
        _communalWidgets.value = inventory
    }

    override fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        rank: Int?,
        configurator: WidgetConfigurator?
    ) {
        coroutineScope.launch {
            val id = nextWidgetId++
            val providerInfo = AppWidgetProviderInfo().apply { this.provider = provider }
            val configured = configurator?.configureWidget(id) ?: true
            if (configured) {
                onConfigured(id, providerInfo, rank ?: -1)
            }
        }
    }

    fun addWidget(
        appWidgetId: Int,
        componentName: String = "pkg/cls",
        rank: Int = 0,
        category: Int = AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
        userId: Int = 0,
    ) {
        fakeDatabase[appWidgetId] =
            CommunalWidgetContentModel.Available(
                appWidgetId = appWidgetId,
                rank = rank,
                providerInfo =
                    AppWidgetProviderInfo().apply {
                        provider = ComponentName.unflattenFromString(componentName)!!
                        widgetCategory = category
                        providerInfo =
                            ActivityInfo().apply {
                                applicationInfo =
                                    ApplicationInfo().apply {
                                        uid = userId * UserHandle.PER_USER_RANGE
                                    }
                            }
                    },
            )
        _communalWidgets.value = fakeDatabase.values.toList()
    }

    fun addPendingWidget(
        appWidgetId: Int,
        componentName: String = "pkg/cls",
        rank: Int = 0,
        icon: Bitmap? = null,
        userId: Int = 0,
    ) {
        fakeDatabase[appWidgetId] =
            CommunalWidgetContentModel.Pending(
                appWidgetId = appWidgetId,
                rank = rank,
                componentName = ComponentName.unflattenFromString(componentName)!!,
                icon = icon,
                user = UserHandle(userId),
            )
        _communalWidgets.value = fakeDatabase.values.toList()
    }

    override fun deleteWidget(widgetId: Int) {
        fakeDatabase.remove(widgetId)
        _communalWidgets.value = fakeDatabase.values.toList()
    }

    override fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>) {}

    override fun abortRestoreWidgets() {}

    private fun onConfigured(id: Int, providerInfo: AppWidgetProviderInfo, rank: Int) {
        _communalWidgets.value +=
            listOf(CommunalWidgetContentModel.Available(id, providerInfo, rank))
    }
}
