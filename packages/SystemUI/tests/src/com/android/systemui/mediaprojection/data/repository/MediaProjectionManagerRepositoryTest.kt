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

import android.media.projection.MediaProjectionInfo
import android.os.Binder
import android.os.UserHandle
import android.view.ContentRecordingSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createToken
import com.android.systemui.mediaprojection.taskswitcher.FakeMediaProjectionManager.Companion.createDisplaySession
import com.android.systemui.mediaprojection.taskswitcher.fakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.fakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaProjectionManagerRepositoryTest : SysuiTestCase() {

    private val kosmos = taskSwitcherKosmos()
    private val testScope = kosmos.testScope

    private val fakeMediaProjectionManager = kosmos.fakeMediaProjectionManager
    private val fakeActivityTaskManager = kosmos.fakeActivityTaskManager

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
    fun stopProjecting_invokesManager() =
        testScope.runTest {
            repo.stopProjecting()

            verify(fakeMediaProjectionManager.mediaProjectionManager).stopActiveProjection()
        }
}
