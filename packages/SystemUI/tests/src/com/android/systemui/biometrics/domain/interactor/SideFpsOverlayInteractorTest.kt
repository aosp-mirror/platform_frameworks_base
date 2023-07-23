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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.SensorLocationInternal
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(JUnit4::class)
class SideFpsOverlayInteractorTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()
    private lateinit var testScope: TestScope

    private val fingerprintRepository = FakeFingerprintPropertyRepository()

    private lateinit var interactor: SideFpsOverlayInteractor

    @Before
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())
        interactor = SideFpsOverlayInteractorImpl(fingerprintRepository)
    }

    @Test
    fun testGetOverlayOffsets() =
        testScope.runTest {
            fingerprintRepository.setProperties(
                sensorId = 1,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.REAR,
                sensorLocations =
                    mapOf(
                        "" to
                            SensorLocationInternal(
                                "" /* displayId */,
                                540 /* sensorLocationX */,
                                1636 /* sensorLocationY */,
                                130 /* sensorRadius */
                            ),
                        "display_id_1" to
                            SensorLocationInternal(
                                "display_id_1" /* displayId */,
                                100 /* sensorLocationX */,
                                300 /* sensorLocationY */,
                                20 /* sensorRadius */
                            )
                    )
            )

            var offsets = interactor.getOverlayOffsets("display_id_1")
            assertThat(offsets.displayId).isEqualTo("display_id_1")
            assertThat(offsets.sensorLocationX).isEqualTo(100)
            assertThat(offsets.sensorLocationY).isEqualTo(300)
            assertThat(offsets.sensorRadius).isEqualTo(20)

            offsets = interactor.getOverlayOffsets("invalid_display_id")
            assertThat(offsets.displayId).isEqualTo("")
            assertThat(offsets.sensorLocationX).isEqualTo(540)
            assertThat(offsets.sensorLocationY).isEqualTo(1636)
            assertThat(offsets.sensorRadius).isEqualTo(130)
        }
}
