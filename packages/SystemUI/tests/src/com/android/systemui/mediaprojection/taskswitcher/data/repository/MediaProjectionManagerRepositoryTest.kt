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

import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.view.ContentRecordingSession
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.mediaprojection.taskswitcher.data.model.MediaProjectionState
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class MediaProjectionManagerRepositoryTest : SysuiTestCase() {

    private val mediaProjectionManager = mock<MediaProjectionManager>()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val tasksRepo = FakeTasksRepository()

    private lateinit var callback: MediaProjectionManager.Callback
    private lateinit var repo: MediaProjectionManagerRepository

    @Before
    fun setUp() {
        whenever(mediaProjectionManager.addCallback(any(), any())).thenAnswer {
            callback = it.arguments[0] as MediaProjectionManager.Callback
            return@thenAnswer Unit
        }
        repo =
            MediaProjectionManagerRepository(
                mediaProjectionManager = mediaProjectionManager,
                handler = Handler.getMain(),
                applicationScope = testScope.backgroundScope,
                tasksRepository = tasksRepo
            )
    }

    @Test
    fun mediaProjectionState_onStart_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            callback.onStart(TEST_MEDIA_INFO)

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onStop_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            callback.onStop(TEST_MEDIA_INFO)

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onSessionSet_sessionNull_emitsNotProjecting() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            callback.onRecordingSessionSet(TEST_MEDIA_INFO, /* session= */ null)

            assertThat(state).isEqualTo(MediaProjectionState.NotProjecting)
        }

    @Test
    fun mediaProjectionState_onSessionSet_contentToRecordDisplay_emitsEntireScreen() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            val session = ContentRecordingSession.createDisplaySession(/* displayToMirror= */ 123)
            callback.onRecordingSessionSet(TEST_MEDIA_INFO, session)

            assertThat(state).isEqualTo(MediaProjectionState.EntireScreen)
        }

    @Test
    fun mediaProjectionState_onSessionSet_tokenNull_emitsEntireScreen() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            val session =
                ContentRecordingSession.createTaskSession(/* taskWindowContainerToken= */ null)
            callback.onRecordingSessionSet(TEST_MEDIA_INFO, session)

            assertThat(state).isEqualTo(MediaProjectionState.EntireScreen)
        }

    @Test
    fun mediaProjectionState_sessionSet_taskWithToken_noMatchingRunningTask_emitsEntireScreen() =
        testScope.runTest {
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            val taskWindowContainerToken = Binder()
            val session = ContentRecordingSession.createTaskSession(taskWindowContainerToken)
            callback.onRecordingSessionSet(TEST_MEDIA_INFO, session)

            assertThat(state).isEqualTo(MediaProjectionState.EntireScreen)
        }

    @Test
    fun mediaProjectionState_sessionSet_taskWithToken_matchingRunningTask_emitsSingleTask() =
        testScope.runTest {
            val token = FakeTasksRepository.createToken()
            val task = FakeTasksRepository.createTask(taskId = 1, token = token)
            tasksRepo.addRunningTask(task)
            val state by collectLastValue(repo.mediaProjectionState)
            runCurrent()

            val session = ContentRecordingSession.createTaskSession(token.asBinder())
            callback.onRecordingSessionSet(TEST_MEDIA_INFO, session)

            assertThat(state).isEqualTo(MediaProjectionState.SingleTask(task))
        }

    companion object {
        val TEST_MEDIA_INFO =
            MediaProjectionInfo(/* packageName= */ "com.test.package", UserHandle.CURRENT)
    }
}
