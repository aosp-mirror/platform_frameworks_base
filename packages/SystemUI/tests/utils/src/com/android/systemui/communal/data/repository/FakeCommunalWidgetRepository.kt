package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.UserHandle
import com.android.systemui.communal.shared.model.CommunalContentSize
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

    private fun updateListFromDatabase() {
        _communalWidgets.value = fakeDatabase.values.sortedWith(compareBy { it.rank }).toList()
    }

    fun setCommunalWidgets(inventory: List<CommunalWidgetContentModel>) {
        _communalWidgets.value = inventory
    }

    override fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        rank: Int?,
        configurator: WidgetConfigurator?,
    ) {
        coroutineScope.launch {
            val id = nextWidgetId++
            val providerInfo = createAppWidgetProviderInfo(provider, user.identifier)

            val configured = configurator?.configureWidget(id) != false
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
        spanY: Int = CommunalContentSize.FixedSize.HALF.span,
    ) {
        fakeDatabase[appWidgetId] =
            CommunalWidgetContentModel.Available(
                appWidgetId = appWidgetId,
                rank = rank,
                providerInfo =
                    createAppWidgetProviderInfo(
                        ComponentName.unflattenFromString(componentName)!!,
                        userId,
                        category,
                    ),
                spanY = spanY,
            )
        updateListFromDatabase()
        nextWidgetId = appWidgetId + 1
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
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
        updateListFromDatabase()
    }

    override fun deleteWidget(widgetId: Int) {
        fakeDatabase.remove(widgetId)
        _communalWidgets.value = fakeDatabase.values.toList()
    }

    private fun reorderDatabase(widgetIdToRankMap: Map<Int, Int>) {
        for ((id, rank) in widgetIdToRankMap) {
            val widget = fakeDatabase[id] ?: continue
            fakeDatabase[id] =
                when (widget) {
                    is CommunalWidgetContentModel.Available -> {
                        widget.copy(rank = rank)
                    }
                    is CommunalWidgetContentModel.Pending -> {
                        widget.copy(rank = rank)
                    }
                }
        }
    }

    override fun updateWidgetOrder(widgetIdToRankMap: Map<Int, Int>) {
        reorderDatabase(widgetIdToRankMap)
        updateListFromDatabase()
    }

    override fun resizeWidget(appWidgetId: Int, spanY: Int, widgetIdToRankMap: Map<Int, Int>) {
        val widget = fakeDatabase[appWidgetId] ?: return

        fakeDatabase[appWidgetId] =
            when (widget) {
                is CommunalWidgetContentModel.Available -> {
                    widget.copy(spanY = spanY)
                }
                is CommunalWidgetContentModel.Pending -> {
                    widget.copy(spanY = spanY)
                }
            }
        reorderDatabase(widgetIdToRankMap)
        updateListFromDatabase()
    }

    override fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>) {}

    override fun abortRestoreWidgets() {}

    private fun onConfigured(id: Int, providerInfo: AppWidgetProviderInfo, rank: Int) {
        fakeDatabase[id] =
            CommunalWidgetContentModel.Available(
                appWidgetId = id,
                providerInfo = providerInfo,
                rank = rank,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
        updateListFromDatabase()
    }

    private fun createAppWidgetProviderInfo(
        componentName: ComponentName,
        userId: Int,
        category: Int = AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
    ): AppWidgetProviderInfo {
        return AppWidgetProviderInfo().apply {
            provider = componentName
            widgetCategory = category
            providerInfo =
                ActivityInfo().apply {
                    applicationInfo =
                        ApplicationInfo().apply { uid = userId * UserHandle.PER_USER_RANGE }
                }
        }
    }
}
