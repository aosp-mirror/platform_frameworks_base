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

package com.android.systemui.mediaprojection.taskswitcher.domain.interactor

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
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionManager.Companion.createSingleTaskSession
import com.android.systemui.mediaprojection.taskswitcher.data.repository.MediaProjectionManagerRepository
import com.android.systemui.mediaprojection.taskswitcher.domain.model.TaskSwitchState
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
class TaskSwitchInteractorTest : SysuiTestCase() {

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

    @Test
    fun taskSwitchChanges_notProjecting_foregroundTaskChange_emitsNotProjectingTask() =
        testScope.runTest {
            val backgroundTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(backgroundTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnStop()
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.NotProjectingTask)
        }

    @Test
    fun taskSwitchChanges_projectingScreen_foregroundTaskChange_emitsNotProjectingTask() =
        testScope.runTest {
            val backgroundTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(backgroundTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = FakeMediaProjectionManager.createDisplaySession()
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.NotProjectingTask)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskDifferent_emitsTaskChanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(token = projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState)
                .isEqualTo(
                    TaskSwitchState.TaskSwitched(
                        projectedTask = projectedTask,
                        foregroundTask = foregroundTask
                    )
                )
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskDifferent_thenSwitched_emitsUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(token = projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)
            interactor.switchProjectedTask(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskDifferent_thenWentBack_emitsUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(token = projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)
            interactor.goBackToTask(projectedTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskLauncher_emitsTaskUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1, baseIntent = LAUNCHER_INTENT)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskSame_emitsTaskUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.addRunningTasks(projectedTask)
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = createSingleTaskSession(projectedTask.token.asBinder())
            )
            fakeActivityTaskManager.moveTaskToForeground(projectedTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    companion object {
        private val LAUNCHER_INTENT: Intent =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    }
}
