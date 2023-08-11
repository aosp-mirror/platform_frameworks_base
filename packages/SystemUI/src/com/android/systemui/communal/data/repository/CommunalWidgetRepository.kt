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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.communal.shared.CommunalAppWidgetInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** Encapsulates the state of widgets for communal mode. */
interface CommunalWidgetRepository {
    /** A flow of provider info for the stopwatch widget, or null if widget is unavailable. */
    val stopwatchAppWidgetInfo: Flow<CommunalAppWidgetInfo?>
}

@SysUISingleton
class CommunalWidgetRepositoryImpl
@Inject
constructor(
    private val appWidgetManager: AppWidgetManager,
    private val appWidgetHost: AppWidgetHost,
    broadcastDispatcher: BroadcastDispatcher,
    private val packageManager: PackageManager,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    @CommunalLog logBuffer: LogBuffer,
    featureFlags: FeatureFlags,
) : CommunalWidgetRepository {
    companion object {
        const val TAG = "CommunalWidgetRepository"
        const val WIDGET_LABEL = "Stopwatch"
    }

    private val logger = Logger(logBuffer, TAG)

    // Whether the [AppWidgetHost] is listening for updates.
    private var isHostListening = false

    // Widgets that should be rendered in communal mode.
    private val widgets: HashMap<Int, CommunalAppWidgetInfo> = hashMapOf()

    private val isUserUnlocked: Flow<Boolean> = callbackFlow {
        if (!featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)) {
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

    override val stopwatchAppWidgetInfo: Flow<CommunalAppWidgetInfo?> =
        isUserUnlocked.map { isUserUnlocked ->
            if (!isUserUnlocked) {
                clearWidgets()
                stopListening()
                return@map null
            }

            startListening()

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
