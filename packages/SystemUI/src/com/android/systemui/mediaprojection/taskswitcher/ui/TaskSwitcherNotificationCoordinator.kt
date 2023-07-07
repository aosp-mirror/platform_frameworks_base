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

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.NotShowing
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState.Showing
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel
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
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val viewModel: TaskSwitcherNotificationViewModel,
) {

    fun start() {
        applicationScope.launch {
            viewModel.uiState.flowOn(mainDispatcher).collect { uiState ->
                Log.d(TAG, "uiState -> $uiState")
                when (uiState) {
                    is Showing -> showNotification(uiState)
                    is NotShowing -> hideNotification()
                }
            }
        }
    }

    private fun showNotification(uiState: Showing) {
        val text =
            """
            Sharing pauses when you switch apps.
            Share this app instead.
            Switch back.
            """
                .trimIndent()
        // TODO(b/286201515): Create actual notification.
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun hideNotification() {}

    companion object {
        private const val TAG = "TaskSwitchNotifCoord"
    }
}
