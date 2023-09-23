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

package com.android.systemui.mediaprojection.taskswitcher.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.NotShowing
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.Showing
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel
import com.android.systemui.util.NotificationChannels
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Coordinator responsible for showing/hiding the task switcher notification. */
@SysUISingleton
class TaskSwitcherNotificationCoordinator
@Inject
constructor(
    private val context: Context,
    private val notificationManager: NotificationManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val viewModel: TaskSwitcherNotificationViewModel,
) {
    fun start() {
        applicationScope.launch {
            viewModel.uiState.flowOn(mainDispatcher).collect { uiState ->
                Log.d(TAG, "uiState -> $uiState")
                when (uiState) {
                    is Showing -> showNotification()
                    is NotShowing -> hideNotification()
                }
            }
        }
    }

    private fun showNotification() {
        notificationManager.notify(TAG, NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        // TODO(b/286201261): implement actions
        val actionSwitch =
            Notification.Action.Builder(
                    /* icon = */ null,
                    context.getString(R.string.media_projection_task_switcher_action_switch),
                    /* intent = */ null
                )
                .build()

        val actionBack =
            Notification.Action.Builder(
                    /* icon = */ null,
                    context.getString(R.string.media_projection_task_switcher_action_back),
                    /* intent = */ null
                )
                .build()

        val channel =
            NotificationChannel(
                NotificationChannels.HINTS,
                context.getString(R.string.media_projection_task_switcher_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        notificationManager.createNotificationChannel(channel)
        return Notification.Builder(context, channel.id)
            .setSmallIcon(R.drawable.qs_screen_record_icon_on)
            .setAutoCancel(true)
            .setContentText(context.getString(R.string.media_projection_task_switcher_text))
            .addAction(actionSwitch)
            .addAction(actionBack)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_VIBRATE)
            .build()
    }

    private fun hideNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "TaskSwitchNotifCoord"
        private const val NOTIFICATION_ID = 5566
    }
}
