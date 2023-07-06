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
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.mediaprojection.taskswitcher.data.repository.ActivityTaskManagerTasksRepository
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionRepository
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
    private val mediaRepo = FakeMediaProjectionRepository()
    private val tasksRepo =
        ActivityTaskManagerTasksRepository(
            activityTaskManager = fakeActivityTaskManager.activityTaskManager,
            applicationScope = testScope.backgroundScope,
            backgroundDispatcher = dispatcher
        )

    private val interactor = TaskSwitchInteractor(mediaRepo, tasksRepo)

    @Test
    fun taskSwitchChanges_notProjecting_foregroundTaskChange_emitsNotProjectingTask() =
        testScope.runTest {
            mediaRepo.stopProjecting()
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 1))

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.NotProjectingTask)
        }

    @Test
    fun taskSwitchChanges_projectingScreen_foregroundTaskChange_emitsNotProjectingTask() =
        testScope.runTest {
            mediaRepo.projectEntireScreen()
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 1))

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.NotProjectingTask)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskDifferent_emitsTaskChanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1)
            mediaRepo.switchProjectedTask(projectedTask)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

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
    fun taskSwitchChanges_projectingTask_foregroundTaskLauncher_emitsTaskUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 1, baseIntent = LAUNCHER_INTENT)
            mediaRepo.switchProjectedTask(projectedTask)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    @Test
    fun taskSwitchChanges_projectingTask_foregroundTaskSame_emitsTaskUnchanged() =
        testScope.runTest {
            val projectedTask = createTask(taskId = 0)
            val foregroundTask = createTask(taskId = 0)
            mediaRepo.switchProjectedTask(projectedTask)
            val taskSwitchState by collectLastValue(interactor.taskSwitchChanges)

            fakeActivityTaskManager.moveTaskToForeground(foregroundTask)

            assertThat(taskSwitchState).isEqualTo(TaskSwitchState.TaskUnchanged)
        }

    companion object {
        private val LAUNCHER_INTENT: Intent =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    }
}
