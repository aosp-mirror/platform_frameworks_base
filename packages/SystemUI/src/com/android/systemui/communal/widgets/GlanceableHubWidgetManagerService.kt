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
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.widget.RemoteViews
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IAppWidgetHostListener
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IGlanceableHubWidgetsListener
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Service for the [GlanceableHubWidgetManager], which runs in a foreground user in Headless System
 * User Mode (HSUM), manages widgets as the user who owns them, and communicates back to the
 * headless system user, where these widgets are rendered.
 */
class GlanceableHubWidgetManagerService
@Inject
constructor(
    private val widgetRepository: CommunalWidgetRepository,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val communalWidgetHost: CommunalWidgetHost,
    glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
    @CommunalLog logBuffer: LogBuffer,
) : LifecycleService() {

    init {
        // The service should only run in a foreground user.
        glanceableHubMultiUserHelper.assertNotInHeadlessSystemUser()
    }

    private val logger = Logger(logBuffer, TAG)
    private val widgetListenersRegistry = WidgetListenerRegistry()

    override fun onCreate() {
        super.onCreate()

        logger.i("Service created")

        communalWidgetHost.startObservingHost()
        appWidgetHost.startListening()
    }

    override fun onDestroy() {
        super.onDestroy()

        logger.i("Service destroyed")

        appWidgetHost.stopListening()
        communalWidgetHost.stopObservingHost()

        // Cancel all widget listener jobs and unregister listeners
        widgetListenersRegistry.kill()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return WidgetManagerServiceBinder().asBinder()
    }

    private fun addWidgetsListenerInternal(listener: IGlanceableHubWidgetsListener?) {
        if (listener == null) {
            throw IllegalStateException("Listener cannot be null")
        }

        if (!listener.asBinder().isBinderAlive) {
            throw IllegalStateException("Listener binder is dead")
        }

        val job =
            widgetRepository.communalWidgets
                .onEach { widgets -> listener.onWidgetsUpdated(widgets) }
                .launchIn(lifecycleScope)
        widgetListenersRegistry.register(listener, job)
    }

    private fun removeWidgetsListenerInternal(listener: IGlanceableHubWidgetsListener?) {
        if (listener == null) {
            throw IllegalStateException("Listener cannot be null")
        }

        widgetListenersRegistry.unregister(listener)
    }

    private fun setAppWidgetHostListenerInternal(
        appWidgetId: Int,
        listener: IAppWidgetHostListener?,
    ) {
        if (listener == null) {
            throw IllegalStateException("Listener cannot be null")
        }

        appWidgetHost.setListener(appWidgetId, createListener(listener))
    }

    private fun addWidgetInternal(provider: ComponentName?, user: UserHandle?, rank: Int) {
        if (provider == null) {
            throw IllegalStateException("Provider cannot be null")
        }

        if (user == null) {
            throw IllegalStateException("User cannot be null")
        }

        // TODO(b/375036327): Add support for widget configuration
        widgetRepository.addWidget(provider, user, rank, configurator = null)
    }

    private fun deleteWidgetInternal(appWidgetId: Int) {
        widgetRepository.deleteWidget(appWidgetId)
    }

    private fun updateWidgetOrderInternal(appWidgetIds: IntArray?, ranks: IntArray?) {
        if (appWidgetIds == null || ranks == null) {
            throw IllegalStateException("appWidgetIds and ranks cannot be null")
        }

        if (appWidgetIds.size != ranks.size) {
            throw IllegalStateException("appWidgetIds and ranks must be the same size")
        }

        widgetRepository.updateWidgetOrder(appWidgetIds.zip(ranks).toMap())
    }

    private fun resizeWidgetInternal(
        appWidgetId: Int,
        spanY: Int,
        appWidgetIds: IntArray?,
        ranks: IntArray?,
    ) {
        if (appWidgetIds == null || ranks == null) {
            throw IllegalStateException("appWidgetIds and ranks cannot be null")
        }

        if (appWidgetIds.size != ranks.size) {
            throw IllegalStateException("appWidgetIds and ranks must be the same size")
        }

        widgetRepository.resizeWidget(appWidgetId, spanY, appWidgetIds.zip(ranks).toMap())
    }

    private fun createListener(listener: IAppWidgetHostListener): AppWidgetHostListener {
        return object : AppWidgetHostListener {
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

    private inner class WidgetManagerServiceBinder : IGlanceableHubWidgetManagerService.Stub() {
        override fun addWidgetsListener(listener: IGlanceableHubWidgetsListener?) {
            val iden = clearCallingIdentity()

            try {
                addWidgetsListenerInternal(listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun removeWidgetsListener(listener: IGlanceableHubWidgetsListener?) {
            val iden = clearCallingIdentity()

            try {
                removeWidgetsListenerInternal(listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun setAppWidgetHostListener(appWidgetId: Int, listener: IAppWidgetHostListener?) {
            val iden = clearCallingIdentity()

            try {
                setAppWidgetHostListenerInternal(appWidgetId, listener)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun addWidget(provider: ComponentName?, user: UserHandle?, rank: Int) {
            val iden = clearCallingIdentity()

            try {
                addWidgetInternal(provider, user, rank)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun deleteWidget(appWidgetId: Int) {
            val iden = clearCallingIdentity()

            try {
                deleteWidgetInternal(appWidgetId)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun updateWidgetOrder(appWidgetIds: IntArray?, ranks: IntArray?) {
            val iden = clearCallingIdentity()

            try {
                updateWidgetOrderInternal(appWidgetIds, ranks)
            } finally {
                restoreCallingIdentity(iden)
            }
        }

        override fun resizeWidget(
            appWidgetId: Int,
            spanY: Int,
            appWidgetIds: IntArray?,
            ranks: IntArray?,
        ) {
            val iden = clearCallingIdentity()

            try {
                resizeWidgetInternal(appWidgetId, spanY, appWidgetIds, ranks)
            } finally {
                restoreCallingIdentity(iden)
            }
        }
    }

    /**
     * Registry of widget listener binders, which handles canceling the job associated with a
     * listener when it is unregistered, or when the binder is dead.
     */
    private class WidgetListenerRegistry : RemoteCallbackList<IGlanceableHubWidgetsListener>() {
        private val jobs = mutableMapOf<IGlanceableHubWidgetsListener, Job>()

        fun register(listener: IGlanceableHubWidgetsListener, job: Job) {
            if (register(listener)) {
                synchronized(jobs) { jobs[listener] = job }
            } else {
                job.cancel()
            }
        }

        override fun unregister(listener: IGlanceableHubWidgetsListener?): Boolean {
            synchronized(jobs) { jobs.remove(listener)?.cancel() }
            return super.unregister(listener)
        }

        override fun onCallbackDied(listener: IGlanceableHubWidgetsListener?) {
            synchronized(jobs) { jobs.remove(listener)?.cancel() }
            super.onCallbackDied(listener)
        }

        override fun kill() {
            synchronized(jobs) {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
            }
            super.kill()
        }
    }

    companion object {
        private const val TAG = "GlanceableHubWidgetManagerService"
    }
}
