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

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.os.Binder
import android.testing.AndroidTestingRunner
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ActivityTaskManagerTasksRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var activityTaskManager: ActivityTaskManager

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var repo: ActivityTaskManagerTasksRepository
    private lateinit var taskStackListener: TaskStackListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(activityTaskManager.registerTaskStackListener(any())).thenAnswer {
            taskStackListener = it.arguments[0] as TaskStackListener
            return@thenAnswer Unit
        }
        repo =
            ActivityTaskManagerTasksRepository(
                activityTaskManager,
                applicationScope = testScope.backgroundScope,
                backgroundDispatcher = dispatcher
            )
    }

    @Test
    fun findRunningTaskFromWindowContainerToken_noMatch_returnsNull() {
        whenever(activityTaskManager.getTasks(Integer.MAX_VALUE))
            .thenReturn(
                listOf(
                    createTaskInfo(newTaskId = 1, windowContainerToken = createToken()),
                    createTaskInfo(newTaskId = 2, windowContainerToken = createToken())
                )
            )

        testScope.runTest {
            val matchingTask =
                repo.findRunningTaskFromWindowContainerToken(windowContainerToken = Binder())

            assertThat(matchingTask).isNull()
        }
    }

    @Test
    fun findRunningTaskFromWindowContainerToken_matchingToken_returnsTaskInfo() {
        val expectedToken = createToken()
        val expectedTask = createTaskInfo(newTaskId = 1, windowContainerToken = expectedToken)

        whenever(activityTaskManager.getTasks(Integer.MAX_VALUE))
            .thenReturn(
                listOf(
                    createTaskInfo(newTaskId = 2, windowContainerToken = createToken()),
                    expectedTask
                )
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
            runCurrent()

            taskStackListener.onTaskMovedToFront(createTaskInfo(newTaskId = 1))
            assertThat(foregroundTask?.taskId).isEqualTo(1)

            taskStackListener.onTaskMovedToFront(createTaskInfo(newTaskId = 2))
            assertThat(foregroundTask?.taskId).isEqualTo(2)

            taskStackListener.onTaskMovedToFront(createTaskInfo(newTaskId = 3))
            assertThat(foregroundTask?.taskId).isEqualTo(3)
        }

    @Test
    fun foregroundTask_lastValueIsCached() =
        testScope.runTest {
            val foregroundTaskA by collectLastValue(repo.foregroundTask)
            runCurrent()
            taskStackListener.onTaskMovedToFront(createTaskInfo(newTaskId = 1))
            assertThat(foregroundTaskA?.taskId).isEqualTo(1)

            val foregroundTaskB by collectLastValue(repo.foregroundTask)
            assertThat(foregroundTaskB?.taskId).isEqualTo(1)
        }

    private fun createToken(): WindowContainerToken {
        val realToken = object : IWindowContainerToken.Stub() {}
        return WindowContainerToken(realToken)
    }

    private fun createTaskInfo(
        windowContainerToken: WindowContainerToken = createToken(),
        newTaskId: Int,
    ): RunningTaskInfo {
        return RunningTaskInfo().apply {
            token = windowContainerToken
            taskId = newTaskId
        }
    }
}
