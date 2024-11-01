/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.IBinder
import android.os.UserHandle
import android.widget.RemoteViews
import com.android.server.servicewatcher.ServiceWatcher
import com.android.server.servicewatcher.ServiceWatcher.ServiceListener
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IAppWidgetHostListener
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IGlanceableHubWidgetsListener
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose

/**
 * Manages updates to Glanceable Hub widgets and requests to edit them from the headless system
 * user.
 *
 * It communicates with the remote [GlanceableHubWidgetManagerService] which runs in the foreground
 * user, and abstracts the IPC details from the rest of the system.
 */
@SysUISingleton
class GlanceableHubWidgetManager
@Inject
constructor(
    glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
    @CommunalLog logBuffer: LogBuffer,
    serviceWatcherFactory: ServiceWatcherFactory<GlanceableHubWidgetManagerServiceInfo?>,
) : ServiceListener<GlanceableHubWidgetManagerServiceInfo?> {

    init {
        // The manager should only be used in the headless system user.
        glanceableHubMultiUserHelper.assertInHeadlessSystemUser()
    }

    private val logger = Logger(logBuffer, TAG)

    private val serviceWatcher by lazy { serviceWatcherFactory.create(this) }

    val widgets = conflatedCallbackFlow {
        val callback =
            object : IGlanceableHubWidgetsListener.Stub() {
                override fun onWidgetsUpdated(widgets: List<CommunalWidgetContentModel>?) {
                    trySend(widgets ?: emptyList())
                }
            }
        runOnService { service -> service.addWidgetsListener(callback) }
        awaitClose { runOnService { service -> service.removeWidgetsListener(callback) } }
    }

    fun register() {
        serviceWatcher.register()
    }

    fun unregister() {
        serviceWatcher.unregister()
    }

    override fun onBind(binder: IBinder?, serviceInfo: GlanceableHubWidgetManagerServiceInfo?) {
        logger.i("Service bound")
    }

    override fun onUnbind() {
        logger.i("Service unbound")
    }

    /** Requests the foreground user to set a [AppWidgetHostListener] for the given app widget. */
    fun setAppWidgetHostListener(appWidgetId: Int, listener: AppWidgetHostListener) =
        runOnService { service ->
            service.setAppWidgetHostListener(appWidgetId, createIAppWidgetHostListener(listener))
        }

    /** Requests the foreground user to add a widget. */
    fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        rank: Int?,
        configurator: WidgetConfigurator?,
    ) = runOnService { service ->
        // TODO(b/375036327): Add support for widget configuration
        service.addWidget(provider, user, rank ?: -1)
    }

    /** Requests the foreground user to delete a widget. */
    fun deleteWidget(appWidgetId: Int) = runOnService { service ->
        service.deleteWidget(appWidgetId)
    }

    /** Requests the foreground user to update widget order. */
    fun updateWidgetOrder(widgetIdToRankMap: Map<Int, Int>) = runOnService { service ->
        service.updateWidgetOrder(
            widgetIdToRankMap.keys.toIntArray(),
            widgetIdToRankMap.values.toIntArray(),
        )
    }

    /** Requests the foreground user to resize a widget. */
    fun resizeWidget(appWidgetId: Int, spanY: Int, widgetIdToRankMap: Map<Int, Int>) =
        runOnService { service ->
            service.resizeWidget(
                appWidgetId,
                spanY,
                widgetIdToRankMap.keys.toIntArray(),
                widgetIdToRankMap.values.toIntArray(),
            )
        }

    private fun runOnService(block: (IGlanceableHubWidgetManagerService) -> Unit) {
        serviceWatcher.runOnBinder(
            object : ServiceWatcher.BinderOperation {
                override fun run(binder: IBinder?) {
                    block(IGlanceableHubWidgetManagerService.Stub.asInterface(binder))
                }

                override fun onError(t: Throwable?) {
                    // TODO(b/375236794): handle failure in case service is unbound
                }
            }
        )
    }

    private fun createIAppWidgetHostListener(
        listener: AppWidgetHostListener
    ): IAppWidgetHostListener {
        return object : IAppWidgetHostListener.Stub() {
            override fun onUpdateProviderInfo(appWidget: AppWidgetProviderInfo?) {
                listener.onUpdateProviderInfo(appWidget)
            }

            override fun updateAppWidget(views: RemoteViews?) {
                listener.updateAppWidget(views)
            }

            override fun updateAppWidgetDeferred(packageName: String?, appWidgetId: Int) {
                listener.updateAppWidgetDeferred(packageName, appWidgetId)
            }

            override fun onViewDataChanged(viewId: Int) {
                listener.onViewDataChanged(viewId)
            }
        }
    }

    companion object {
        private const val TAG = "GlanceableHubWidgetManager"
    }
}
