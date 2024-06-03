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

package com.android.systemui.screenrecord.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRecordRepositoryTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val recordingController = mock<RecordingController>()

    private val underTest =
        ScreenRecordRepositoryImpl(
            bgCoroutineContext = testScope.testScheduler,
            recordingController = recordingController,
        )

    private val isRecording = ScreenRecordModel.Recording
    private val isDoingNothing = ScreenRecordModel.DoingNothing
    private val isStarting0 = ScreenRecordModel.Starting(0)

    @Test
    fun dataMatchesController() =
        testScope.runTest {
            whenever(recordingController.isRecording).thenReturn(false)
            whenever(recordingController.isStarting).thenReturn(false)

            val callbackCaptor = argumentCaptor<RecordingController.RecordingStateChangeCallback>()

            val lastModel by collectLastValue(underTest.screenRecordState)
            runCurrent()

            verify(recordingController).addCallback(callbackCaptor.capture())
            val callback = callbackCaptor.firstValue

            assertThat(lastModel).isEqualTo(isDoingNothing)

            val expectedModelStartingIn1 = ScreenRecordModel.Starting(1)
            callback.onCountdown(1)
            assertThat(lastModel).isEqualTo(expectedModelStartingIn1)

            val expectedModelStartingIn0 = isStarting0
            callback.onCountdown(0)
            assertThat(lastModel).isEqualTo(expectedModelStartingIn0)

            callback.onCountdownEnd()
            assertThat(lastModel).isEqualTo(isDoingNothing)

            callback.onRecordingStart()
            assertThat(lastModel).isEqualTo(isRecording)

            callback.onRecordingEnd()
            assertThat(lastModel).isEqualTo(isDoingNothing)
        }

    @Test
    fun data_whenRecording_matchesController() =
        testScope.runTest {
            whenever(recordingController.isRecording).thenReturn(true)
            whenever(recordingController.isStarting).thenReturn(false)

            val lastModel by collectLastValue(underTest.screenRecordState)
            runCurrent()

            assertThat(lastModel).isEqualTo(isRecording)
        }

    @Test
    fun data_whenStarting_matchesController() =
        testScope.runTest {
            whenever(recordingController.isRecording).thenReturn(false)
            whenever(recordingController.isStarting).thenReturn(true)

            val lastModel by collectLastValue(underTest.screenRecordState)
            runCurrent()

            assertThat(lastModel).isEqualTo(isStarting0)
        }

    @Test
    fun data_whenRecordingAndStarting_matchesControllerRecording() =
        testScope.runTest {
            whenever(recordingController.isRecording).thenReturn(true)
            whenever(recordingController.isStarting).thenReturn(true)

            val lastModel by collectLastValue(underTest.screenRecordState)
            runCurrent()

            assertThat(lastModel).isEqualTo(isRecording)
        }
}
