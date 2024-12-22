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

package com.android.systemui.communal

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings.Secure.USER_SETUP_COMPLETE
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.widgets.CommunalWidgetModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

@SysUISingleton
class CommunalBackupRestoreStartable
@Inject
constructor(
    private val broadcastDispatcher: BroadcastDispatcher,
    private val communalInteractor: CommunalInteractor,
    @CommunalLog logBuffer: LogBuffer,
    private val secureSettings: SecureSettings,
    handler: Handler,
) : CoreStartable, BroadcastReceiver() {

    private val logger = Logger(logBuffer, TAG)

    private var oldToNewWidgetIdMap = emptyMap<Int, Int>()
    private var userSetupComplete = false

    override fun start() {
        broadcastDispatcher.registerReceiver(
            receiver = this,
            filter = IntentFilter(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED),
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            logger.w("On app widget host restored, but intent is null")
            return
        }

        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED) {
            return
        }

        val hostId = intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, 0)
        if (hostId != CommunalWidgetModule.APP_WIDGET_HOST_ID) {
            return
        }

        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS)
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)

        if (oldIds == null || newIds == null || oldIds.size != newIds.size) {
            logger.w("On app widget host restored, but old to new ids mapping is invalid")
            communalInteractor.abortRestoreWidgets()
            return
        }

        oldToNewWidgetIdMap = oldIds.zip(newIds).toMap()

        logger.i({ "On old to new widget ids mapping updated: $str1" }) {
            str1 = oldToNewWidgetIdMap.toString()
        }

        maybeRestoreWidgets()

        // Start observing if user setup is not complete
        if (!userSetupComplete) {
            startObservingUserSetupComplete()
        }
    }

    private val userSetupObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                maybeRestoreWidgets()

                // Stop observing once user setup is complete
                if (userSetupComplete) {
                    stopObservingUserSetupComplete()
                }
            }
        }

    private fun maybeRestoreWidgets() {
        val newValue = secureSettings.getInt(USER_SETUP_COMPLETE) > 0

        if (userSetupComplete != newValue) {
            userSetupComplete = newValue
            logger.i({ "User setup complete: $bool1" }) { bool1 = userSetupComplete }
        }

        if (userSetupComplete && oldToNewWidgetIdMap.isNotEmpty()) {
            logger.i("Starting to restore widgets")
            communalInteractor.restoreWidgets(oldToNewWidgetIdMap.toMap())
            oldToNewWidgetIdMap = emptyMap()
        }
    }

    private fun startObservingUserSetupComplete() {
        secureSettings.registerContentObserverSync(USER_SETUP_COMPLETE, userSetupObserver)
    }

    private fun stopObservingUserSetupComplete() {
        secureSettings.unregisterContentObserverSync(userSetupObserver)
    }

    companion object {
        const val TAG = "CommunalBackupRestoreStartable"
    }
}
