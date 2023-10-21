/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.communal.data.model.CommunalWidgetMetadata
import com.android.systemui.communal.shared.model.CommunalAppWidgetInfo
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Encapsulates the state of widgets for communal mode. */
interface CommunalWidgetRepository {
    /** A flow of provider info for the stopwatch widget, or null if widget is unavailable. */
    val stopwatchAppWidgetInfo: Flow<CommunalAppWidgetInfo?>

    /** Widgets that are allowed to render in the glanceable hub */
    val communalWidgetAllowlist: List<CommunalWidgetMetadata>

    /** A flow of information about all the communal widgets to show. */
    val communalWidgets: Flow<List<CommunalWidgetContentModel>>
}

@SysUISingleton
class CommunalWidgetRepositoryImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val appWidgetManager: AppWidgetManager,
    private val appWidgetHost: AppWidgetHost,
    broadcastDispatcher: BroadcastDispatcher,
    communalRepository: CommunalRepository,
    private val packageManager: PackageManager,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    @CommunalLog logBuffer: LogBuffer,
    featureFlags: FeatureFlagsClassic,
) : CommunalWidgetRepository {
    companion object {
        const val TAG = "CommunalWidgetRepository"
        const val WIDGET_LABEL = "Stopwatch"
    }
    override val communalWidgetAllowlist: List<CommunalWidgetMetadata>

    private val logger = Logger(logBuffer, TAG)

    // Whether the [AppWidgetHost] is listening for updates.
    private var isHostListening = false

    init {
        communalWidgetAllowlist =
            if (communalRepository.isCommunalEnabled) getWidgetAllowlist() else emptyList()
    }

    // Widgets that should be rendered in communal mode.
    private val widgets: HashMap<Int, CommunalAppWidgetInfo> = hashMapOf()

    private val isUserUnlocked: Flow<Boolean> =
        callbackFlow {
                if (!communalRepository.isCommunalEnabled) {
                    awaitClose()
                }

                fun isUserUnlockingOrUnlocked(): Boolean {
                    return userManager.isUserUnlockingOrUnlocked(userTracker.userHandle)
                }

                fun send() {
                    trySendWithFailureLogging(isUserUnlockingOrUnlocked(), TAG)
                }

                if (isUserUnlockingOrUnlocked()) {
                    send()
                    awaitClose()
                } else {
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                send()
                            }
                        }

                    broadcastDispatcher.registerReceiver(
                        receiver,
                        IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    )

                    awaitClose { broadcastDispatcher.unregisterReceiver(receiver) }
                }
            }
            .distinctUntilChanged()

    private val isHostActive: Flow<Boolean> =
        isUserUnlocked.map {
            if (it) {
                startListening()
                true
            } else {
                stopListening()
                clearWidgets()
                false
            }
        }

    override val stopwatchAppWidgetInfo: Flow<CommunalAppWidgetInfo?> =
        isHostActive.map { isHostActive ->
            if (!isHostActive || !featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)) {
                return@map null
            }

            val providerInfo =
                appWidgetManager.installedProviders.find {
                    it.loadLabel(packageManager).equals(WIDGET_LABEL)
                }

            if (providerInfo == null) {
                logger.w("Cannot find app widget: $WIDGET_LABEL")
                return@map null
            }

            return@map addWidget(providerInfo)
        }

    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        isHostActive.map { isHostActive ->
            if (!isHostActive) {
                return@map emptyList()
            }

            // The allowlist should be fetched from the local database with all the metadata tied to
            // a widget, including an appWidgetId if it has been bound. Before the database is set
            // up, we are going to use the app widget host as the source of truth for bound widgets,
            // and rebind each time on boot.

            // Remove all previously bound widgets.
            appWidgetHost.appWidgetIds.forEach { appWidgetHost.deleteAppWidgetId(it) }

            val inventory = mutableListOf<CommunalWidgetContentModel>()

            // Bind all widgets from the allowlist.
            communalWidgetAllowlist.forEach {
                val id = appWidgetHost.allocateAppWidgetId()
                appWidgetManager.bindAppWidgetId(
                    id,
                    ComponentName.unflattenFromString(it.componentName),
                )

                inventory.add(
                    CommunalWidgetContentModel(
                        appWidgetId = id,
                        providerInfo = appWidgetManager.getAppWidgetInfo(id),
                        priority = it.priority,
                    )
                )
            }

            return@map inventory.toList()
        }

    private fun getWidgetAllowlist(): List<CommunalWidgetMetadata> {
        val componentNames =
            applicationContext.resources.getStringArray(R.array.config_communalWidgetAllowlist)
        return componentNames.mapIndexed { index, name ->
            CommunalWidgetMetadata(
                componentName = name,
                priority = componentNames.size - index,
                sizes = listOf(CommunalContentSize.HALF),
            )
        }
    }

    private fun startListening() {
        if (isHostListening) {
            return
        }

        appWidgetHost.startListening()
        isHostListening = true
    }

    private fun stopListening() {
        if (!isHostListening) {
            return
        }

        appWidgetHost.stopListening()
        isHostListening = false
    }

    private fun addWidget(providerInfo: AppWidgetProviderInfo): CommunalAppWidgetInfo {
        val existing = widgets.values.firstOrNull { it.providerInfo == providerInfo }
        if (existing != null) {
            return existing
        }

        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val widget =
            CommunalAppWidgetInfo(
                providerInfo,
                appWidgetId,
            )
        widgets[appWidgetId] = widget
        return widget
    }

    private fun clearWidgets() {
        widgets.keys.forEach { appWidgetId -> appWidgetHost.deleteAppWidgetId(appWidgetId) }
        widgets.clear()
    }
}
