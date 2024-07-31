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

package com.android.systemui.statusbar.chips.screenrecord.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@SmallTest
class ScreenRecordChipInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val screenRecordRepo = kosmos.screenRecordRepository
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository

    private val underTest = kosmos.screenRecordChipInteractor

    @Test
    fun screenRecordState_doingNothingState_matches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest).isInstanceOf(ScreenRecordChipModel.DoingNothing::class.java)
        }

    @Test
    fun screenRecordState_startingState_matches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(400)

            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(400))
        }

    @Test
    fun screenRecordState_recordingState_matches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest).isInstanceOf(ScreenRecordChipModel.Recording::class.java)
        }

    @Test
    fun screenRecordState_projectionIsNotProjecting_recordedTaskNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = null))
        }

    @Test
    fun screenRecordState_projectionIsEntireScreen_recordedTaskNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen("host.package")

            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = null))
        }

    @Test
    fun screenRecordState_projectionIsSingleTask_recordedTaskMatches() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            val task = createTask(taskId = 1)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    "host.package",
                    hostDeviceName = null,
                    task,
                )

            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = task))
        }

    @Test
    fun stopRecording_sendsToRepo() =
        testScope.runTest {
            assertThat(screenRecordRepo.stopRecordingInvoked).isFalse()

            underTest.stopRecording()
            runCurrent()

            assertThat(screenRecordRepo.stopRecordingInvoked).isTrue()
        }
}
