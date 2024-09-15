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
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.ComponentName
import android.os.Bundle
import android.os.UserHandle
import android.widget.RemoteViews
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Widget host that interacts with AppWidget service and host to bind and provide info for widgets
 * shown in the glanceable hub.
 */
class CommunalWidgetHost
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    private val appWidgetManager: Optional<AppWidgetManager>,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val selectedUserInteractor: SelectedUserInteractor,
    @CommunalLog logBuffer: LogBuffer,
) : CommunalAppWidgetHost.Observer {
    companion object {
        private const val TAG = "CommunalWidgetHost"

        /** Returns whether a particular widget requires configuration when it is first added. */
        fun requiresConfiguration(widgetInfo: AppWidgetProviderInfo): Boolean {
            val featureFlags: Int = widgetInfo.widgetFeatures
            // A widget's configuration is optional only if it's configuration is marked as optional
            // AND it can be reconfigured later.
            val configurationOptional =
                (featureFlags and WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
                    featureFlags and WIDGET_FEATURE_RECONFIGURABLE != 0)
            return widgetInfo.configure != null && !configurationOptional
        }
    }

    private val logger = Logger(logBuffer, TAG)

    private val _appWidgetProviders = MutableStateFlow(emptyMap<Int, AppWidgetProviderInfo?>())

    /**
     * A flow of mappings between an appWidgetId and its corresponding [AppWidgetProviderInfo].
     * These [AppWidgetProviderInfo]s represent app widgets that are actively bound to the
     * [CommunalAppWidgetHost].
     *
     * The [AppWidgetProviderInfo] may be null in the case that the widget is bound but its provider
     * is unavailable. For example, its package is not installed.
     */
    val appWidgetProviders: StateFlow<Map<Int, AppWidgetProviderInfo?>> =
        _appWidgetProviders.asStateFlow()

    /**
     * Allocate an app widget id and binds the widget with the provider and associated user.
     *
     * @param provider The component name of the provider.
     * @param user User handle in which the provider resides. Default value is the current user.
     * @return widgetId if binding is successful; otherwise return null
     */
    fun allocateIdAndBindWidget(provider: ComponentName, user: UserHandle? = null): Int? {
        val id = appWidgetHost.allocateAppWidgetId()
        if (
            bindWidget(
                widgetId = id,
                user = user ?: UserHandle(selectedUserInteractor.getSelectedUserId()),
                provider = provider
            )
        ) {
            logger.d("Successfully bound the widget $provider")
            onProviderInfoUpdated(id, getAppWidgetInfo(id))
            return id
        }
        appWidgetHost.deleteAppWidgetId(id)
        logger.d("Failed to bind the widget $provider")
        return null
    }

    private fun bindWidget(widgetId: Int, user: UserHandle, provider: ComponentName): Boolean {
        if (appWidgetManager.isPresent) {
            val options =
                Bundle().apply {
                    putInt(
                        AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
                    )
                }
            return appWidgetManager
                .get()
                .bindAppWidgetIdIfAllowed(widgetId, user, provider, options)
        }
        return false
    }

    @WorkerThread
    fun getAppWidgetInfo(widgetId: Int): AppWidgetProviderInfo? {
        return appWidgetManager.getOrNull()?.getAppWidgetInfo(widgetId)
    }

    fun startObservingHost() {
        appWidgetHost.addObserver(this@CommunalWidgetHost)
    }

    fun stopObservingHost() {
        appWidgetHost.removeObserver(this@CommunalWidgetHost)
    }

    fun refreshProviders() {
        bgScope.launch {
            val newProviders = mutableMapOf<Int, AppWidgetProviderInfo?>()
            appWidgetHost.appWidgetIds.forEach { appWidgetId ->
                // Listen for updates from each bound widget
                addListener(appWidgetId)

                // Fetch provider info of the widget
                newProviders[appWidgetId] = getAppWidgetInfo(appWidgetId)
            }

            _appWidgetProviders.value = newProviders.toMap()
        }
    }

    override fun onHostStartListening() {
        refreshProviders()
    }

    override fun onHostStopListening() {
        // Remove listeners
        _appWidgetProviders.value.keys.forEach { appWidgetId ->
            appWidgetHost.removeListener(appWidgetId)
        }

        // Clear providers
        _appWidgetProviders.value = emptyMap()
    }

    override fun onAllocateAppWidgetId(appWidgetId: Int) {
        addListener(appWidgetId)
    }

    override fun onDeleteAppWidgetId(appWidgetId: Int) {
        appWidgetHost.removeListener(appWidgetId)
        _appWidgetProviders.value =
            _appWidgetProviders.value.toMutableMap().also { it.remove(appWidgetId) }
    }

    private fun addListener(appWidgetId: Int) {
        appWidgetHost.setListener(
            appWidgetId,
            CommunalAppWidgetHostListener(appWidgetId, this::onProviderInfoUpdated),
        )
    }

    private fun onProviderInfoUpdated(appWidgetId: Int, providerInfo: AppWidgetProviderInfo?) {
        bgScope.launch {
            _appWidgetProviders.value =
                _appWidgetProviders.value.toMutableMap().also { it[appWidgetId] = providerInfo }
        }
    }

    /** A [AppWidgetHostListener] for [appWidgetId]. */
    private class CommunalAppWidgetHostListener(
        private val appWidgetId: Int,
        private val onUpdateProviderInfo: (Int, AppWidgetProviderInfo?) -> Unit,
    ) : AppWidgetHostListener {
        override fun onUpdateProviderInfo(providerInfo: AppWidgetProviderInfo?) {
            onUpdateProviderInfo(appWidgetId, providerInfo)
        }

        override fun onViewDataChanged(viewId: Int) {}

        override fun updateAppWidget(remoteViews: RemoteViews?) {}
    }
}
