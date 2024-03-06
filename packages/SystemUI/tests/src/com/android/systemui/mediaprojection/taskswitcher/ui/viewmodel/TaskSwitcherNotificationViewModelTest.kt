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
import android.os.Handler
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.mediaprojection.taskswitcher.data.repository.ActivityTaskManagerTasksRepository
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionManager.Companion.createDisplaySession
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionManager.Companion.createSingleTaskSession
import com.android.systemui.mediaprojection.taskswitcher.data.repository.MediaProjectionManagerRepository
import com.android.systemui.mediaprojection.taskswitcher.domain.interactor.TaskSwitchInteractor
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class TaskSwitcherNotificationViewModelTest : SysuiTestCase() {

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val fakeActivityTaskManager = FakeActivityTaskManager()
    private val fakeMediaProjectionManager = FakeMediaProjectionManager()

    private val tasksRepo =
        ActivityTaskManagerTasksRepository(
            activityTaskManager = fakeActivityTaskManager.activityTaskManager,
            applicationScope = testScope.backgroundScope,
            backgroundDispatcher = dispatcher
        )

    private val mediaRepo =
        MediaProjectionManagerRepository(
            mediaProjectionManager = fakeMediaProjectionManager.mediaProjectionManager,
            handler = Handler.getMain(),
            applicationScope = testScope.backgroundScope,
            tasksRepository = tasksRepo,
            backgroundDispatcher = dispatcher,
            mediaProjectionServiceHelper = fakeMediaProjectionManager.helper,
        )

    private val interactor = TaskSwitchInteractor(mediaRepo, tasksRepo)

    private val viewModel =
        TaskSwitcherNotificationViewModel(interactor, backgroundDispatcher = dispatcher)

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
