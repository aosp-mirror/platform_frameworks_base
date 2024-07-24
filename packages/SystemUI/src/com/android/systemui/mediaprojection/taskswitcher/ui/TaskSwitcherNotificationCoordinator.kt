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

import android.app.ActivityManager.RunningTaskInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.NotShowing
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.Showing
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel
import com.android.systemui.res.R
import com.android.systemui.util.NotificationChannels
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinator responsible for showing/hiding the task switcher notification. */
@SysUISingleton
class TaskSwitcherNotificationCoordinator
@Inject
constructor(
    private val context: Context,
    private val notificationManager: NotificationManager,
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: TaskSwitcherNotificationViewModel,
    private val broadcastDispatcher: BroadcastDispatcher,
) {

    fun start() {
        applicationScope.launch {
            launch {
                viewModel.uiState.collect { uiState ->
                    Log.d(TAG, "uiState -> $uiState")
                    when (uiState) {
                        is Showing -> showNotification(uiState)
                        is NotShowing -> hideNotification()
                    }
                }
            }
            launch {
                broadcastDispatcher
                    .broadcastFlow(IntentFilter(SWITCH_ACTION)) { intent, _ ->
                        intent.requireParcelableExtra<RunningTaskInfo>(EXTRA_ACTION_TASK)
                    }
                    .collect { task: RunningTaskInfo ->
                        Log.d(TAG, "Switch action triggered: $task")
                        viewModel.onSwitchTaskClicked(task)
                    }
            }
            launch {
                broadcastDispatcher
                    .broadcastFlow(IntentFilter(GO_BACK_ACTION)) { intent, _ ->
                        intent.requireParcelableExtra<RunningTaskInfo>(EXTRA_ACTION_TASK)
                    }
                    .collect { task ->
                        Log.d(TAG, "Go back action triggered: $task")
                        viewModel.onGoBackToTaskClicked(task)
                    }
            }
        }
    }

    private fun showNotification(uiState: Showing) {
        notificationManager.notify(TAG, NOTIFICATION_ID, createNotification(uiState))
    }

    private fun createNotification(uiState: Showing): Notification {
        val actionSwitch =
            Notification.Action.Builder(
                    /* icon = */ null,
                    context.getString(R.string.media_projection_task_switcher_action_switch),
                    createActionPendingIntent(action = SWITCH_ACTION, task = uiState.foregroundTask)
                )
                .build()

        val actionBack =
            Notification.Action.Builder(
                    /* icon = */ null,
                    context.getString(R.string.media_projection_task_switcher_action_back),
                    createActionPendingIntent(action = GO_BACK_ACTION, task = uiState.projectedTask)
                )
                .build()
        return Notification.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.qs_screen_record_icon_on)
            .setAutoCancel(true)
            .setContentText(context.getString(R.string.media_projection_task_switcher_text))
            .addAction(actionSwitch)
            .addAction(actionBack)
            .build()
    }

    private fun hideNotification() {
        notificationManager.cancel(TAG, NOTIFICATION_ID)
    }

    private fun createActionPendingIntent(action: String, task: RunningTaskInfo) =
        PendingIntent.getBroadcast(
            context,
            /* requestCode= */ 0,
            Intent(action).apply { putExtra(EXTRA_ACTION_TASK, task) },
            /* flags= */ PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        private const val TAG = "TaskSwitchNotifCoord"
        private const val NOTIFICATION_ID = 5566

        private const val EXTRA_ACTION_TASK = "extra_task"

        private const val SWITCH_ACTION = "com.android.systemui.mediaprojection.SWITCH_TASK"
        private const val GO_BACK_ACTION = "com.android.systemui.mediaprojection.GO_BACK"
    }
}

private fun <T : Parcelable> Intent.requireParcelableExtra(key: String) =
    getParcelableExtra<T>(key)!!
