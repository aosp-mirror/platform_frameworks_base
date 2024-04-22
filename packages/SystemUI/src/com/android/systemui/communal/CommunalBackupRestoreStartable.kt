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
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.widgets.CommunalWidgetModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject

@SysUISingleton
class CommunalBackupRestoreStartable
@Inject
constructor(
    private val broadcastDispatcher: BroadcastDispatcher,
    private val communalInteractor: CommunalInteractor,
    @CommunalLog logBuffer: LogBuffer,
) : CoreStartable, BroadcastReceiver() {

    private val logger = Logger(logBuffer, TAG)

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

        val oldToNewWidgetIdMap = oldIds.zip(newIds).toMap()
        communalInteractor.restoreWidgets(oldToNewWidgetIdMap)
    }

    companion object {
        const val TAG = "CommunalBackupRestoreStartable"
    }
}
