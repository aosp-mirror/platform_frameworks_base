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

package com.android.systemui.mediaprojection.data.repository

import android.hardware.display.displayManager
import android.media.projection.MediaProjectionInfo
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import android.view.ContentRecordingSession
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createToken
import com.android.systemui.mediaprojection.taskswitcher.FakeMediaProjectionManager.Companion.createDisplaySession
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeTasksRepository
import com.android.systemui.mediaprojection.taskswitcher.fakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.fakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaProjectionManagerRepositoryTest : SysuiTestCase() {

    private val kosmos = taskSwitcherKosmos()
    private val testScope = kosmos.testScope

    private val fakeMediaProjectionManager = kosmos.fakeMediaProjectionManager
    private val fakeActivityTaskManager = kosmos.fakeActivityTaskManager
    private val displayManager = kosmos.displayManager

    private val repo = kosmos.realMediaProjectionRepository

    @Test
    fun switchProjectedTask_stateIsUpdatedWithNewTask() =
        testScope.runTest {
            val task = createTask(taskId = 1)
            val state by collectLastValue(repo.mediaProjectionState)

            fakeActivityTaskManager.addRunningTasks(task)
            repo.switchProjectedTask(task)

            assertThat(state).isInstanceOf(MediaProjectionState.Projecting.SingleTask::class.java)
            assertThat((state as MediaProjectionState.Projecting.SingleTask).task).isEqualTo(task)
        }

    @Test
    fun mediaProjectionState_onStart_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            fakeMediaProjectionManager.dispatchOnStart()

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onStop_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            fakeMediaProjectionManager.dispatchOnStop()

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onSessionSet_sessionNull_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = null)

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onSessionSet_contentToRecordDisplay_emitsEntireScreen() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = ContentRecordingSession.createDisplaySession(/* displayToMirror= */ 123)
            )

            assertThat(state).isInstanceOf(MediaProjectionState.Projecting.EntireScreen::class.java)
        }

    @Test
    fun mediaProjectionState_sessionSet_taskWithToken_noMatchingRunningTask_emitsEntireScreen() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val taskWindowContainerToken = Binder()
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = ContentRecordingSession.createTaskSession(taskWindowContainerToken)
            )

            assertThat(state).isInstanceOf(MediaProjectionState.Projecting.EntireScreen::class.java)
        }

    @Test
    fun mediaProjectionState_entireScreen_hasHostPackage() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val info =
                MediaProjectionInfo(
                    /* packageName= */ "com.media.projection.repository.test",
                    /* handle= */ UserHandle.getUserHandleForUid(UserHandle.myUserId()),
                    /* launchCookie = */ null,
                )
            fakeMediaProjectionManager.dispatchOnSessionSet(
                info = info,
                session = createDisplaySession(),
            )

            assertThat((state as MediaProjectionState.Projecting.EntireScreen).hostPackage)
                .isEqualTo("com.media.projection.repository.test")
        }

    @Test
    fun mediaProjectionState_entireScreen_validVirtualDisplayId_hasHostDeviceName() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val session = ContentRecordingSession.createDisplaySession(/* displayToMirror= */ 123)
            session.virtualDisplayId = 45
            val displayInfo = mock<Display>().apply { whenever(this.name).thenReturn("Test Name") }
            whenever(displayManager.getDisplay(45)).thenReturn(displayInfo)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = session)

            assertThat((state as MediaProjectionState.Projecting.EntireScreen).hostDeviceName)
                .isEqualTo("Test Name")
        }

    @Test
    fun mediaProjectionState_entireScreen_invalidVirtualDisplayId_nullHostDeviceName() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val session = ContentRecordingSession.createDisplaySession(/* displayToMirror= */ 123)
            session.virtualDisplayId = 45
            whenever(displayManager.getDisplay(45)).thenReturn(null)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = session)

            assertThat((state as MediaProjectionState.Projecting.EntireScreen).hostDeviceName)
                .isNull()
        }

    @Test
    fun mediaProjectionState_sessionSet_taskWithToken_matchingRunningTask_emitsSingleTask() =
        testScope.runTest {
            val token = createToken()
            val task = createTask(taskId = 1, token = token)
            fakeActivityTaskManager.addRunningTasks(task)
            val state by collectLastValue(repo.mediaProjectionState)

            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = ContentRecordingSession.createTaskSession(token.asBinder())
            )

            assertThat(state).isInstanceOf(MediaProjectionState.Projecting.SingleTask::class.java)
            assertThat((state as MediaProjectionState.Projecting.SingleTask).task).isEqualTo(task)
        }

    @Test
    fun mediaProjectionState_singleTask_hasHostPackage() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val token = createToken()
            val task = createTask(taskId = 1, token = token)
            fakeActivityTaskManager.addRunningTasks(task)

            val info =
                MediaProjectionInfo(
                    /* packageName= */ "com.media.projection.repository.test",
                    /* handle= */ UserHandle.getUserHandleForUid(UserHandle.myUserId()),
                    /* launchCookie = */ null,
                )
            fakeMediaProjectionManager.dispatchOnSessionSet(
                info = info,
                session = ContentRecordingSession.createTaskSession(token.asBinder())
            )

            assertThat((state as MediaProjectionState.Projecting.SingleTask).hostPackage)
                .isEqualTo("com.media.projection.repository.test")
        }

    @Test
    fun mediaProjectionState_singleTask_validVirtualDisplayId_hasHostDeviceName() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val token = createToken()
            val task = createTask(taskId = 1, token = token)
            fakeActivityTaskManager.addRunningTasks(task)

            val session = ContentRecordingSession.createTaskSession(token.asBinder())
            session.virtualDisplayId = 45
            val displayInfo = mock<Display>().apply { whenever(this.name).thenReturn("Test Name") }
            whenever(displayManager.getDisplay(45)).thenReturn(displayInfo)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = session)

            assertThat((state as MediaProjectionState.Projecting.SingleTask).hostDeviceName)
                .isEqualTo("Test Name")
        }

    @Test
    fun mediaProjectionState_singleTask_invalidVirtualDisplayId_nullHostDeviceName() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)

            val token = createToken()
            val task = createTask(taskId = 1, token = token)
            fakeActivityTaskManager.addRunningTasks(task)

            val session = ContentRecordingSession.createTaskSession(token.asBinder())
            session.virtualDisplayId = 45
            whenever(displayManager.getDisplay(45)).thenReturn(null)

            fakeMediaProjectionManager.dispatchOnSessionSet(session = session)

            assertThat((state as MediaProjectionState.Projecting.SingleTask).hostDeviceName)
                .isNull()
        }

    /** Regression test for b/352483752. */
    @Test
    fun mediaProjectionState_sessionStartedThenImmediatelyStopped_emitsOnlyNotProjecting() =
        testScope.runTest {
            val fakeTasksRepo = FakeTasksRepository()
            val repoWithTimingControl =
                MediaProjectionManagerRepository(
                    // fakeTasksRepo lets us have control over when the background dispatcher
                    // finishes fetching the tasks info.
                    tasksRepository = fakeTasksRepo,
                    mediaProjectionManager = fakeMediaProjectionManager.mediaProjectionManager,
                    displayManager = displayManager,
                    handler = Handler.getMain(),
                    applicationScope = kosmos.applicationCoroutineScope,
                    backgroundDispatcher = kosmos.testDispatcher,
                    mediaProjectionServiceHelper = fakeMediaProjectionManager.helper,
                    logger = logcatLogBuffer("TestMediaProjection"),
                )

            val state by collectLastValue(repoWithTimingControl.mediaProjectionState)

            val token = createToken()
            val task = createTask(taskId = 1, token = token)

            // Dispatch a session using a task session so that MediaProjectionManagerRepository
            // has to ask TasksRepository for the tasks info.
            fakeMediaProjectionManager.dispatchOnSessionSet(
                session = ContentRecordingSession.createTaskSession(token.asBinder())
            )
            // FakeTasksRepository is set up to not return the tasks info until the test manually
            // calls [FakeTasksRepository#setRunningTaskResult]. At this point,
            // MediaProjectionManagerRepository is waiting for the tasks info and hasn't emitted
            // anything yet.

            // Before the tasks info comes back, dispatch a stop event.
            fakeMediaProjectionManager.dispatchOnStop()

            // Then let the tasks info come back.
            fakeTasksRepo.setRunningTaskResult(task)

            // Verify that MediaProjectionManagerRepository threw away the tasks info because
            // a newer callback event (#onStop) occurred.
            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun stopProjecting_invokesManager() =
        testScope.runTest {
            repo.stopProjecting()

            verify(fakeMediaProjectionManager.mediaProjectionManager).stopActiveProjection()
        }
}
