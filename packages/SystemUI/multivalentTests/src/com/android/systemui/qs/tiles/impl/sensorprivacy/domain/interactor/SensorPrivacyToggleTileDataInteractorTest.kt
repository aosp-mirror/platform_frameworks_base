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

package com.android.systemui.qs.tiles.impl.sensorprivacy.domain.interactor

import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.hardware.SensorPrivacyManager.Sensors.MICROPHONE
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.sensorprivacy.SensorPrivacyToggleTileDataInteractor
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class SensorPrivacyToggleTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val mockSensorPrivacyController =
        mock<IndividualSensorPrivacyController> {
            whenever(isSensorBlocked(eq(CAMERA))).thenReturn(false) // determines initial value
        }
    private val testUser = UserHandle.of(1)
    private val underTest =
        SensorPrivacyToggleTileDataInteractor(
            testScope.testScheduler,
            mockSensorPrivacyController,
            CAMERA
        )

    @Test
    fun availability_isTrue() =
        testScope.runTest {
            whenever(mockSensorPrivacyController.supportsSensorToggle(eq(CAMERA))).thenReturn(true)

            val availability = underTest.availability(testUser).toCollection(mutableListOf())
            runCurrent()

            assertThat(availability).hasSize(1)
            assertThat(availability.last()).isTrue()
        }

    @Test
    fun tileData_matchesPrivacyControllerIsSensorBlocked() =
        testScope.runTest {
            val callbackCaptor = argumentCaptor<IndividualSensorPrivacyController.Callback>()
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            verify(mockSensorPrivacyController).addCallback(callbackCaptor.capture())
            val callback = callbackCaptor.value

            runCurrent()
            assertThat(data!!.isBlocked).isFalse()

            callback.onSensorBlockedChanged(CAMERA, true)
            runCurrent()
            assertThat(data!!.isBlocked).isTrue()

            callback.onSensorBlockedChanged(CAMERA, false)
            runCurrent()
            assertThat(data!!.isBlocked).isFalse()

            callback.onSensorBlockedChanged(MICROPHONE, true)
            runCurrent()
            assertThat(data!!.isBlocked).isFalse() // We're NOT listening for MIC sensor changes
        }
}
