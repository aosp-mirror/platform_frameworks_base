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

package com.android.systemui.biometrics.ui.viewmodel

import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManagerGlobal
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import android.view.Surface
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.SideFpsOverlayInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsOverlayInteractorImpl
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit

private const val DISPLAY_ID = 2

@SmallTest
@RunWith(JUnit4::class)
class SideFpsOverlayViewModelTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()
    private var testScope: TestScope = TestScope(StandardTestDispatcher())

    private val fingerprintRepository = FakeFingerprintPropertyRepository()
    private lateinit var interactor: SideFpsOverlayInteractor
    private lateinit var viewModel: SideFpsOverlayViewModel

    enum class DeviceConfig {
        X_ALIGNED,
        Y_ALIGNED,
    }

    private lateinit var deviceConfig: DeviceConfig
    private lateinit var indicatorBounds: Rect
    private lateinit var displayBounds: Rect
    private lateinit var sensorLocation: SensorLocationInternal
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var boundsWidth: Int = 0
    private var boundsHeight: Int = 0

    @Before
    fun setup() {
        interactor = SideFpsOverlayInteractorImpl(fingerprintRepository)

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
    }

    @Test
    fun testOverlayOffsets() =
        testScope.runTest {
            viewModel = SideFpsOverlayViewModel(mContext, interactor)

            val interactorOffsets by collectLastValue(interactor.overlayOffsets)
            val viewModelOffsets by collectLastValue(viewModel.overlayOffsets)

            assertThat(viewModelOffsets).isEqualTo(interactorOffsets)
        }

    private fun testWithDisplay(
        deviceConfig: DeviceConfig = DeviceConfig.X_ALIGNED,
        isReverseDefaultRotation: Boolean = false,
        initInfo: DisplayInfo.() -> Unit = {},
        block: () -> Unit
    ) {
        this.deviceConfig = deviceConfig

        when (deviceConfig) {
            DeviceConfig.X_ALIGNED -> {
                displayWidth = 3000
                displayHeight = 1500
                sensorLocation = SensorLocationInternal("", 2500, 0, 0)
                boundsWidth = 200
                boundsHeight = 100
            }
            DeviceConfig.Y_ALIGNED -> {
                displayWidth = 2500
                displayHeight = 2000
                sensorLocation = SensorLocationInternal("", 0, 300, 0)
                boundsWidth = 100
                boundsHeight = 200
            }
        }

        indicatorBounds = Rect(0, 0, boundsWidth, boundsHeight)
        displayBounds = Rect(0, 0, displayWidth, displayHeight)

        val displayInfo = DisplayInfo()
        displayInfo.initInfo()

        val dmGlobal = Mockito.mock(DisplayManagerGlobal::class.java)
        val display =
            Display(
                dmGlobal,
                DISPLAY_ID,
                displayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
            )

        whenever(dmGlobal.getDisplayInfo(ArgumentMatchers.eq(DISPLAY_ID))).thenReturn(displayInfo)

        val sideFpsOverlayViewModelContext =
            context.createDisplayContext(display) as SysuiTestableContext
        sideFpsOverlayViewModelContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_reverseDefaultRotation,
            isReverseDefaultRotation
        )
        viewModel = SideFpsOverlayViewModel(sideFpsOverlayViewModelContext, interactor)

        block()
    }

    /**
     * {@link SideFpsOverlayViewModel#updateSensorBounds} calculates indicator placement for
     * ROTATION_0, and uses RotateUtils.rotateBounds to map to the correct indicator location given
     * the device rotation. Assuming RotationUtils.rotateBounds works correctly, tests for indicator
     * placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForXAlignedSensor_0() =
        testScope.runTest {
            testWithDisplay(
                deviceConfig = DeviceConfig.X_ALIGNED,
                isReverseDefaultRotation = false,
                { rotation = Surface.ROTATION_0 }
            ) {
                viewModel.updateSensorBounds(indicatorBounds, displayBounds, sensorLocation)

                val displayInfo: DisplayInfo = DisplayInfo()
                context.display!!.getDisplayInfo(displayInfo)
                assertThat(displayInfo.rotation).isEqualTo(Surface.ROTATION_0)

                assertThat(viewModel.sensorBounds.value).isNotNull()
                assertThat(viewModel.sensorBounds.value.left)
                    .isEqualTo(sensorLocation.sensorLocationX)
                assertThat(viewModel.sensorBounds.value.top).isEqualTo(0)
            }
        }

    /**
     * {@link SideFpsOverlayViewModel#updateSensorBounds} calculates indicator placement for
     * ROTATION_270 in reverse default rotation. It then uses RotateUtils.rotateBounds to map to the
     * correct indicator location given the device rotation. Assuming RotationUtils.rotateBounds
     * works correctly, tests for indicator placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForXAlignedSensor_InReverseDefaultRotation_270() =
        testScope.runTest {
            testWithDisplay(
                deviceConfig = DeviceConfig.X_ALIGNED,
                isReverseDefaultRotation = true,
                { rotation = Surface.ROTATION_270 }
            ) {
                viewModel.updateSensorBounds(indicatorBounds, displayBounds, sensorLocation)

                assertThat(viewModel.sensorBounds.value).isNotNull()
                assertThat(viewModel.sensorBounds.value.left)
                    .isEqualTo(sensorLocation.sensorLocationX)
                assertThat(viewModel.sensorBounds.value.top).isEqualTo(0)
            }
        }

    /**
     * {@link SideFpsOverlayViewModel#updateSensorBounds} calculates indicator placement for
     * ROTATION_0, and uses RotateUtils.rotateBounds to map to the correct indicator location given
     * the device rotation. Assuming RotationUtils.rotateBounds works correctly, tests for indicator
     * placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForYAlignedSensor_0() =
        testScope.runTest {
            testWithDisplay(
                deviceConfig = DeviceConfig.Y_ALIGNED,
                isReverseDefaultRotation = false,
                { rotation = Surface.ROTATION_0 }
            ) {
                viewModel.updateSensorBounds(indicatorBounds, displayBounds, sensorLocation)

                assertThat(viewModel.sensorBounds.value).isNotNull()
                assertThat(viewModel.sensorBounds.value.left).isEqualTo(displayWidth - boundsWidth)
                assertThat(viewModel.sensorBounds.value.top)
                    .isEqualTo(sensorLocation.sensorLocationY)
            }
        }

    /**
     * {@link SideFpsOverlayViewModel#updateSensorBounds} calculates indicator placement for
     * ROTATION_270 in reverse default rotation. It then uses RotateUtils.rotateBounds to map to the
     * correct indicator location given the device rotation. Assuming RotationUtils.rotateBounds
     * works correctly, tests for indicator placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForYAlignedSensor_InReverseDefaultRotation_270() =
        testScope.runTest {
            testWithDisplay(
                deviceConfig = DeviceConfig.Y_ALIGNED,
                isReverseDefaultRotation = true,
                { rotation = Surface.ROTATION_270 }
            ) {
                viewModel.updateSensorBounds(indicatorBounds, displayBounds, sensorLocation)

                assertThat(viewModel.sensorBounds.value).isNotNull()
                assertThat(viewModel.sensorBounds.value.left).isEqualTo(displayWidth - boundsWidth)
                assertThat(viewModel.sensorBounds.value.top)
                    .isEqualTo(sensorLocation.sensorLocationY)
            }
        }
}
