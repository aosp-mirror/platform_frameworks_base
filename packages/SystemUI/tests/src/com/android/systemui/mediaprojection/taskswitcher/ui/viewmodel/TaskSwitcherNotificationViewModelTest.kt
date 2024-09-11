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

package com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.FakeMediaProjectionManager.Companion.createDisplaySession
import com.android.systemui.mediaprojection.taskswitcher.FakeMediaProjectionManager.Companion.createSingleTaskSession
import com.android.systemui.mediaprojection.taskswitcher.fakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.fakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherKosmos
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherViewModel
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel.Companion.NOTIFICATION_MAX_SHOW_DURATION
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class TaskSwitcherNotificationViewModelTest : SysuiTestCase() {

    private val kosmos = taskSwitcherKosmos()
    private val testScope = kosmos.testScope
    private val fakeActivityTaskManager = kosmos.fakeActivityTaskManager
    private val fakeMediaProjectionManager = kosmos.fakeMediaProjectionManager
    private val viewModel = kosmos.taskSwitcherViewModel

    @Test
    fun uiState_notProjecting_emitsNotShowing() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.uiState)

            fakeMediaProjectionManager.dispatchOnStop()

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_notProjecting_foregroundTaskChanged_emitsNotShowing() =
        testScope.runTest {
            val backgroundTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(backgroundTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnStop()
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingEntireScreen_emitsNotShowing() =
        testScope.runTest {
            val uiState by collectLastValue(viewModel.uiState)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = createDisplaySession())

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingEntireScreen_foregroundTaskChanged_emitsNotShowing() =
        testScope.runTest {
            val backgroundTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(backgroundTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(session = createDisplaySession())
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingTask_foregroundTaskChanged_different_emitsShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(uiState)
                .isEqualTo(TaskSwitcherNotificationUiState.Showing(projectedTask, foregroundTask))
        }

    @Test
    fun uiState_taskChanged_beforeDelayLimit_stillEmitsShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            testScheduler.advanceTimeBy(NOTIFICATION_MAX_SHOW_DURATION - 1.milliseconds)
            assertThat(uiState)
                .isEqualTo(TaskSwitcherNotificationUiState.Showing(projectedTask, foregroundTask))
        }

    @Test
    fun uiState_taskChanged_afterDelayLimit_emitsNotShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            testScheduler.advanceTimeBy(NOTIFICATION_MAX_SHOW_DURATION)
            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingTask_foregroundTaskChanged_thenTaskSwitched_emitsNotShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)
            viewModel.onSwitchTaskClicked(foregroundTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingTask_foregroundTaskChanged_thenGoBack_emitsNotShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)
            viewModel.onGoBackToTaskClicked(projectedTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingTask_foregroundTaskChanged_same_emitsNotShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(projectedTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    @Test
    fun uiState_projectingTask_foregroundTaskChanged_different_taskIsLauncher_emitsNotShowing() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 1)
            val foregroundTask = createTask(taskId = 2, baseIntent = LAUNCHER_INTENT)
            val uiState by collectLastValue(viewModel.uiState)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(uiState).isEqualTo(TaskSwitcherNotificationUiState.NotShowing)
        }

    companion object {
        private val LAUNCHER_INTENT: Intent =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    }
}
