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

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.Color
import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManagerGlobal
import android.view.Display
import android.view.DisplayInfo
import android.view.WindowInsets
import android.view.WindowMetrics
import android.view.windowManager
import androidx.test.filters.SmallTest
import com.airbnb.lottie.model.KeyPath
import com.android.keyguard.keyguardUpdateMonitor
import com.android.settingslib.Utils
import com.android.systemui.Flags.FLAG_CONSTRAINT_BP
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.data.repository.biometricStatusRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.displayStateInteractor
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.LottieCallback
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.keyguard.ui.viewmodel.sideFpsProgressBarViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.unfold.compat.ScreenSizeFoldProvider
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class SideFpsOverlayViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var fingerprintInteractiveToAuthProvider: FingerprintInteractiveToAuthProvider
    @Mock private lateinit var screenSizeFoldProvider: ScreenSizeFoldProvider

    private val contextDisplayInfo = DisplayInfo()

    private val indicatorColor =
        Utils.getColorAttrDefaultColor(
            context,
            com.android.internal.R.attr.materialColorPrimaryFixed
        )
    private val outerRimColor =
        Utils.getColorAttrDefaultColor(
            context,
            com.android.internal.R.attr.materialColorPrimaryFixedDim
        )
    private val chevronFill =
        Utils.getColorAttrDefaultColor(
            context,
            com.android.internal.R.attr.materialColorOnPrimaryFixed
        )
    private val color_blue400 =
        context.getColor(com.android.settingslib.color.R.color.settingslib_color_blue400)

    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var boundsWidth: Int = 0
    private var boundsHeight: Int = 0

    private lateinit var deviceConfig: DeviceConfig
    private lateinit var sensorLocation: SensorLocationInternal

    enum class DeviceConfig {
        X_ALIGNED,
        Y_ALIGNED,
    }

    @Before
    fun setup() {
        mContext = spy(mContext)

        val resources = mContext.resources
        whenever(mContext.display)
            .thenReturn(
                Display(mock(DisplayManagerGlobal::class.java), 1, contextDisplayInfo, resources)
            )
        kosmos.displayStateInteractor.setScreenSizeFoldProvider(screenSizeFoldProvider)

        whenever(fingerprintInteractiveToAuthProvider.enabledForCurrentUser)
            .thenReturn(MutableStateFlow(false))
    }

    @Test
    fun updatesOverlayViewProperties_onDisplayRotationChange_xAlignedSensor() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewProperties by
                collectLastValue(kosmos.sideFpsOverlayViewModel.overlayViewProperties)

            runCurrent()

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)
        }
    }

    @Test
    fun updatesOverlayViewProperties_onDisplayRotationChange_yAlignedSensor() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.Y_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewProperties by
                collectLastValue(kosmos.sideFpsOverlayViewModel.overlayViewProperties)

            runCurrent()

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)
        }
    }

    @Test
    fun updatesOverlayViewParams_onDisplayRotationChange_xAlignedSensor() {
        kosmos.testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_CONSTRAINT_BP)
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewParams by
                collectLastValue(kosmos.sideFpsOverlayViewModel.overlayViewParams)

            kosmos.sideFpsOverlayViewModel.setLottieBounds(Rect(0, 0, boundsWidth, boundsHeight))
            runCurrent()

            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(sensorLocation.sensorLocationX)
            assertThat(overlayViewParams!!.y).isEqualTo(0)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(0)
            assertThat(overlayViewParams!!.y)
                .isEqualTo(
                    displayHeight - sensorLocation.sensorLocationX - sensorLocation.sensorRadius * 2
                )

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x)
                .isEqualTo(
                    displayWidth - sensorLocation.sensorLocationX - sensorLocation.sensorRadius * 2
                )
            assertThat(overlayViewParams!!.y).isEqualTo(displayHeight - boundsHeight)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParams!!.y).isEqualTo(sensorLocation.sensorLocationX)
        }
    }

    @Test
    fun updatesOverlayViewParams_onDisplayRotationChange_yAlignedSensor() {
        kosmos.testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_CONSTRAINT_BP)
            setupTestConfiguration(
                DeviceConfig.Y_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewParams by
                collectLastValue(kosmos.sideFpsOverlayViewModel.overlayViewParams)

            kosmos.sideFpsOverlayViewModel.setLottieBounds(Rect(0, 0, boundsWidth, boundsHeight))
            runCurrent()

            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParams!!.y).isEqualTo(sensorLocation.sensorLocationY)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(sensorLocation.sensorLocationY)
            assertThat(overlayViewParams!!.y).isEqualTo(0)

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(0)
            assertThat(overlayViewParams!!.y)
                .isEqualTo(
                    displayHeight - sensorLocation.sensorLocationY - sensorLocation.sensorRadius * 2
                )

            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x)
                .isEqualTo(
                    displayWidth - sensorLocation.sensorLocationY - sensorLocation.sensorRadius * 2
                )
            assertThat(overlayViewParams!!.y).isEqualTo(displayHeight - boundsHeight)
        }
    }

    @Test
    fun updatesLottieCallbacks_onShowIndicatorForDeviceEntry() {
        kosmos.testScope.runTest {
            val lottieCallbacks by collectLastValue(kosmos.sideFpsOverlayViewModel.lottieCallbacks)

            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)

            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue600", "**"), indicatorColor))
            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue400", "**"), outerRimColor))
            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".black", "**"), chevronFill))
        }
    }

    @Test
    fun updatesLottieCallbacks_onShowIndicatorForSystemServer_inDarkMode() {
        kosmos.testScope.runTest {
            val lottieCallbacks by collectLastValue(kosmos.sideFpsOverlayViewModel.lottieCallbacks)
            setDarkMode(true)

            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)

            updatePrimaryBouncer(
                isShowing = false,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue600", "**"), color_blue400))
            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue400", "**"), color_blue400))
        }
    }

    @Test
    fun updatesLottieCallbacks_onShowIndicatorForSystemServer_inLightMode() {
        kosmos.testScope.runTest {
            val lottieCallbacks by collectLastValue(kosmos.sideFpsOverlayViewModel.lottieCallbacks)
            setDarkMode(false)

            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)

            updatePrimaryBouncer(
                isShowing = false,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".black", "**"), Color.WHITE))
            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue600", "**"), color_blue400))
            assertThat(lottieCallbacks)
                .contains(LottieCallback(KeyPath(".blue400", "**"), color_blue400))
        }
    }

    private fun setDarkMode(inDarkMode: Boolean) {
        val uiMode =
            if (inDarkMode) {
                UI_MODE_NIGHT_YES
            } else {
                UI_MODE_NIGHT_NO
            }

        mContext.resources.configuration.uiMode = uiMode
    }

    private fun updatePrimaryBouncer(
        isShowing: Boolean,
        isAnimatingAway: Boolean,
        fpsDetectionRunning: Boolean,
        isUnlockingWithFpAllowed: Boolean,
    ) {
        kosmos.keyguardBouncerRepository.setPrimaryShow(isShowing)
        kosmos.keyguardBouncerRepository.setPrimaryStartingToHide(false)
        val primaryStartDisappearAnimation = if (isAnimatingAway) Runnable {} else null
        kosmos.keyguardBouncerRepository.setPrimaryStartDisappearAnimation(
            primaryStartDisappearAnimation
        )

        whenever(kosmos.keyguardUpdateMonitor.isFingerprintDetectionRunning)
            .thenReturn(fpsDetectionRunning)
        whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
            .thenReturn(isUnlockingWithFpAllowed)
        mContext.orCreateTestableResources.addOverride(
            R.bool.config_show_sidefps_hint_on_bouncer,
            true
        )
    }

    private suspend fun TestScope.setupTestConfiguration(
        deviceConfig: DeviceConfig,
        rotation: DisplayRotation = DisplayRotation.ROTATION_0,
        isInRearDisplayMode: Boolean,
    ) {
        this@SideFpsOverlayViewModelTest.deviceConfig = deviceConfig

        when (deviceConfig) {
            DeviceConfig.X_ALIGNED -> {
                displayWidth = 3000
                displayHeight = 1500
                boundsWidth = 200
                boundsHeight = 100
                sensorLocation = SensorLocationInternal("", 2500, 0, boundsWidth / 2)
            }
            DeviceConfig.Y_ALIGNED -> {
                displayWidth = 2500
                displayHeight = 2000
                boundsWidth = 100
                boundsHeight = 200
                sensorLocation = SensorLocationInternal("", displayWidth, 300, boundsHeight / 2)
            }
        }

        whenever(kosmos.windowManager.maximumWindowMetrics)
            .thenReturn(
                WindowMetrics(
                    Rect(0, 0, displayWidth, displayHeight),
                    mock(WindowInsets::class.java),
                )
            )

        contextDisplayInfo.uniqueId = DISPLAY_ID

        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = mapOf(DISPLAY_ID to sensorLocation)
        )

        kosmos.displayStateRepository.setIsInRearDisplayMode(isInRearDisplayMode)
        kosmos.displayStateRepository.setCurrentRotation(rotation)

        kosmos.displayRepository.emitDisplayChangeEvent(0)
        runCurrent()
    }

    companion object {
        private const val DISPLAY_ID = "displayId"
    }
}
