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

package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(JUnit4::class)
class FingerprintRepositoryImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()
    private lateinit var testScope: TestScope

    @Mock private lateinit var fingerprintManager: FingerprintManager
    private lateinit var repository: FingerprintPropertyRepositoryImpl

    @Captor
    private lateinit var fingerprintAuthenticatorsCaptor:
        ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback.Stub>

    @Before
    fun setup() {
        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        repository =
            FingerprintPropertyRepositoryImpl(testScope.backgroundScope, fingerprintManager)
        testScope.runCurrent()

        verify(fingerprintManager)
            .addAuthenticatorsRegisteredCallback(fingerprintAuthenticatorsCaptor.capture())
    }

    @Test
    fun initializeProperties() =
        testScope.runTest {
            val isInitialized = collectLastValue(repository.isInitialized)

            assertDefaultProperties()
            assertThat(isInitialized()).isFalse()

            val fingerprintProps =
                listOf(
                    FingerprintSensorPropertiesInternal(
                        1 /* sensorId */,
                        SensorProperties.STRENGTH_STRONG,
                        5 /* maxEnrollmentsPerUser */,
                        listOf<ComponentInfoInternal>(
                            ComponentInfoInternal(
                                "sensor" /* componentId */,
                                "vendor/model/revision" /* hardwareVersion */,
                                "1.01" /* firmwareVersion */,
                                "00000001" /* serialNumber */,
                                "" /* softwareVersion */
                            )
                        ),
                        FingerprintSensorProperties.TYPE_REAR,
                        false /* halControlsIllumination */,
                        true /* resetLockoutRequiresHardwareAuthToken */,
                        listOf<SensorLocationInternal>(
                            SensorLocationInternal(
                                "" /* displayId */,
                                540 /* sensorLocationX */,
                                1636 /* sensorLocationY */,
                                130 /* sensorRadius */
                            ),
                            SensorLocationInternal(
                                "display_id_1" /* displayId */,
                                100 /* sensorLocationX */,
                                300 /* sensorLocationY */,
                                20 /* sensorRadius */
                            )
                        )
                    )
                )

            fingerprintAuthenticatorsCaptor.value.onAllAuthenticatorsRegistered(fingerprintProps)

            assertThat(repository.sensorId.value).isEqualTo(1)
            assertThat(repository.strength.value).isEqualTo(SensorStrength.STRONG)
            assertThat(repository.sensorType.value).isEqualTo(FingerprintSensorType.REAR)

            assertThat(repository.sensorLocations.value.size).isEqualTo(2)
            assertThat(repository.sensorLocations.value).containsKey("display_id_1")
            with(repository.sensorLocations.value["display_id_1"]!!) {
                assertThat(displayId).isEqualTo("display_id_1")
                assertThat(sensorLocationX).isEqualTo(100)
                assertThat(sensorLocationY).isEqualTo(300)
                assertThat(sensorRadius).isEqualTo(20)
            }
            assertThat(repository.sensorLocations.value).containsKey("")
            with(repository.sensorLocations.value[""]!!) {
                assertThat(displayId).isEqualTo("")
                assertThat(sensorLocationX).isEqualTo(540)
                assertThat(sensorLocationY).isEqualTo(1636)
                assertThat(sensorRadius).isEqualTo(130)
            }
            assertThat(isInitialized()).isTrue()
        }

    private fun assertDefaultProperties() {
        assertThat(repository.sensorId.value).isEqualTo(-1)
        assertThat(repository.strength.value).isEqualTo(SensorStrength.CONVENIENCE)
        assertThat(repository.sensorType.value).isEqualTo(FingerprintSensorType.UNKNOWN)
    }
}
