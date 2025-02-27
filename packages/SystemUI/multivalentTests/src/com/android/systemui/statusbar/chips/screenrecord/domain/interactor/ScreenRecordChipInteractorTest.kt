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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRecordChipInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
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
    @DisableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_flagOff_doesNotAutomaticallySwitchToRecordingBasedOnTime() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            // WHEN screen record should start in 900ms
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(900)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(900))

            // WHEN 900ms has elapsed
            advanceTimeBy(901)

            // THEN we don't automatically update to the recording state if the flag is off
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(900))
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_flagOn_automaticallySwitchesToRecordingBasedOnTime() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            // WHEN screen record should start in 900ms
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(900)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(900))

            // WHEN 900ms has elapsed
            advanceTimeBy(901)

            // THEN we automatically update to the recording state
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = null))
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_recordingBeginsEarly_switchesToRecording() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            // WHEN screen record should start in 900ms
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(900)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(900))

            // WHEN we update to the Recording state earlier than 900ms
            advanceTimeBy(800)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            val task = createTask(taskId = 1)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    "host.package",
                    hostDeviceName = null,
                    task,
                )

            // THEN we immediately switch to Recording, and we have the task
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = task))

            // WHEN more than 900ms has elapsed
            advanceTimeBy(200)

            // THEN we still stay in the Recording state and we have the task
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = task))
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_secondRecording_doesNotAutomaticallyStart() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            // First recording starts, records, and stops
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(900)
            advanceTimeBy(900)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            advanceTimeBy(5000)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.DoingNothing
            advanceTimeBy(10000)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.DoingNothing)

            // WHEN a second recording is starting
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(2900)

            // THEN we stay as starting and do not switch to Recording (verifying the auto-start
            // timer is reset)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(2900))
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_startingButThenDoingNothing_doesNotAutomaticallyStart() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            // WHEN a screen recording is starting in 500ms
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(500)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(500))

            // But it's cancelled after 300ms
            advanceTimeBy(300)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN we don't automatically start the recording 200ms later
            advanceTimeBy(201)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.DoingNothing)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_AUTO_START_SCREEN_RECORD_CHIP)
    fun screenRecordState_multipleStartingValues_autoStartResets() =
        testScope.runTest {
            val latest by collectLastValue(underTest.screenRecordState)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(2900)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(2900))

            advanceTimeBy(2800)

            // WHEN there's 100ms left to go before auto-start, but then we get a new start time
            // that's in 500ms
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(500)

            // THEN we don't auto-start in 100ms
            advanceTimeBy(101)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Starting(500))

            // THEN we *do* auto-start 400ms later
            advanceTimeBy(401)
            assertThat(latest).isEqualTo(ScreenRecordChipModel.Recording(recordedTask = null))
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
