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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.screenrecord.domain.model.ScreenRecordTileModel
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class ScreenRecordTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val controller = mock<RecordingController>()
    private val underTest: ScreenRecordTileDataInteractor =
        ScreenRecordTileDataInteractor(testScope.testScheduler, controller)

    private val isRecording = ScreenRecordTileModel.Recording
    private val isDoingNothing = ScreenRecordTileModel.DoingNothing
    private val isStarting0 = ScreenRecordTileModel.Starting(0)

    @Test
    fun isAvailable_returnsTrue() = runTest {
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isTrue()
    }

    @Test
    fun dataMatchesController() =
        testScope.runTest {
            whenever(controller.isRecording).thenReturn(false)
            whenever(controller.isStarting).thenReturn(false)

            val callbackCaptor = argumentCaptor<RecordingController.RecordingStateChangeCallback>()

            val lastModel by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            verify(controller).addCallback(callbackCaptor.capture())
            val callback = callbackCaptor.value

            assertThat(lastModel).isEqualTo(isDoingNothing)

            val expectedModelStartingIn1 = ScreenRecordTileModel.Starting(1)
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
            whenever(controller.isRecording).thenReturn(true)
            whenever(controller.isStarting).thenReturn(false)

            val lastModel by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            assertThat(lastModel).isEqualTo(isRecording)
        }

    @Test
    fun data_whenStarting_matchesController() =
        testScope.runTest {
            whenever(controller.isRecording).thenReturn(false)
            whenever(controller.isStarting).thenReturn(true)

            val lastModel by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            assertThat(lastModel).isEqualTo(isStarting0)
        }

    @Test
    fun data_whenRecordingAndStarting_matchesControllerRecording() =
        testScope.runTest {
            whenever(controller.isRecording).thenReturn(true)
            whenever(controller.isStarting).thenReturn(true)

            val lastModel by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            assertThat(lastModel).isEqualTo(isRecording)
        }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
