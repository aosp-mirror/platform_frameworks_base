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

package com.android.systemui.mediaprojection.taskswitcher.data.repository

import android.os.Binder
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager.Companion.createToken
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
class ActivityTaskManagerTasksRepositoryTest : SysuiTestCase() {

    private val fakeActivityTaskManager = FakeActivityTaskManager()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val repo =
        ActivityTaskManagerTasksRepository(
            activityTaskManager = fakeActivityTaskManager.activityTaskManager,
            applicationScope = testScope.backgroundScope,
            backgroundDispatcher = dispatcher
        )

    @Test
    fun launchRecentTask_taskIsMovedToForeground() =
        testScope.runTest {
            val currentForegroundTask by collectLastValue(repo.foregroundTask)
            val newForegroundTask = createTask(taskId = 1)
            val backgroundTask = createTask(taskId = 2)
            fakeActivityTaskManager.addRunningTasks(backgroundTask, newForegroundTask)

            repo.launchRecentTask(newForegroundTask)

            assertThat(currentForegroundTask).isEqualTo(newForegroundTask)
        }

    @Test
    fun findRunningTaskFromWindowContainerToken_noMatch_returnsNull() {
        fakeActivityTaskManager.addRunningTasks(createTask(taskId = 1), createTask(taskId = 2))

        testScope.runTest {
            val matchingTask =
                repo.findRunningTaskFromWindowContainerToken(windowContainerToken = Binder())

            assertThat(matchingTask).isNull()
        }
    }

    @Test
    fun findRunningTaskFromWindowContainerToken_matchingToken_returnsTaskInfo() {
        val expectedToken = createToken()
        val expectedTask = createTask(taskId = 1, token = expectedToken)

        fakeActivityTaskManager.addRunningTasks(
            createTask(taskId = 2),
            expectedTask,
        )

        testScope.runTest {
            val actualTask =
                repo.findRunningTaskFromWindowContainerToken(
                    windowContainerToken = expectedToken.asBinder()
                )

            assertThat(actualTask).isEqualTo(expectedTask)
        }
    }

    @Test
    fun foregroundTask_returnsStreamOfTasksMovedToFront() =
        testScope.runTest {
            val foregroundTask by collectLastValue(repo.foregroundTask)

            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 1))
            assertThat(foregroundTask?.taskId).isEqualTo(1)

            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 2))
            assertThat(foregroundTask?.taskId).isEqualTo(2)

            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 3))
            assertThat(foregroundTask?.taskId).isEqualTo(3)
        }

    @Test
    fun foregroundTask_lastValueIsCached() =
        testScope.runTest {
            val foregroundTaskA by collectLastValue(repo.foregroundTask)
            fakeActivityTaskManager.moveTaskToForeground(createTask(taskId = 1))
            assertThat(foregroundTaskA?.taskId).isEqualTo(1)

            val foregroundTaskB by collectLastValue(repo.foregroundTask)
            assertThat(foregroundTaskB?.taskId).isEqualTo(1)
        }
}
