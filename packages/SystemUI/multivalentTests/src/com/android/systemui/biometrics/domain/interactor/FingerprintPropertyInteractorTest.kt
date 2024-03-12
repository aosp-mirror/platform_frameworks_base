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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.SensorLocationInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FingerprintPropertyInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.fingerprintPropertyInteractor }
    private val repository by lazy { kosmos.fingerprintPropertyRepository }
    private val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    private val displayRepository by lazy { kosmos.displayRepository }

    @Test
    fun propertiesInitialized() =
        testScope.runTest {
            val propertiesInitialized by collectLastValue(underTest.propertiesInitialized)
            assertThat(propertiesInitialized).isFalse()

            repository.supportsUdfps()
            runCurrent()
            assertThat(propertiesInitialized).isTrue()
        }

    @Test
    fun sensorLocation_resolution1f() =
        testScope.runTest {
            val currSensorLocation by collectLastValue(underTest.sensorLocation)

            displayRepository.emitDisplayChangeEvent(0)
            runCurrent()
            repository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.UDFPS_OPTICAL,
                sensorLocations =
                    mapOf(
                        Pair("", SensorLocationInternal("", 4, 4, 2)),
                        Pair("otherDisplay", SensorLocationInternal("", 1, 1, 1))
                    )
            )
            runCurrent()
            configurationRepository.setScaleForResolution(1f)
            runCurrent()

            assertThat(currSensorLocation?.centerX).isEqualTo(4)
            assertThat(currSensorLocation?.centerY).isEqualTo(4)
            assertThat(currSensorLocation?.radius).isEqualTo(2)
        }

    @Test
    fun sensorLocation_resolution2f() =
        testScope.runTest {
            val currSensorLocation by collectLastValue(underTest.sensorLocation)

            displayRepository.emitDisplayChangeEvent(0)
            runCurrent()
            repository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.UDFPS_OPTICAL,
                sensorLocations =
                    mapOf(
                        Pair("", SensorLocationInternal("", 4, 4, 2)),
                        Pair("otherDisplay", SensorLocationInternal("", 1, 1, 1))
                    )
            )
            runCurrent()
            configurationRepository.setScaleForResolution(2f)
            runCurrent()

            assertThat(currSensorLocation?.centerX).isEqualTo(4 * 2)
            assertThat(currSensorLocation?.centerY).isEqualTo(4 * 2)
            assertThat(currSensorLocation?.radius).isEqualTo(2 * 2)
        }
}
