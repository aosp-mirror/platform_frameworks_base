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

import android.app.ActivityTaskManager
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.Color
import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.view.Display
import android.view.DisplayInfo
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.test.filters.SmallTest
import com.airbnb.lottie.model.KeyPath
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.settingslib.Utils
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.data.repository.FakeBiometricStatusRepository
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractorImpl
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.SideFpsSensorInteractor
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.LottieCallback
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.domain.interactor.DeviceEntrySideFpsOverlayInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor
import com.android.systemui.keyguard.ui.viewmodel.SideFpsProgressBarViewModel
import com.android.systemui.log.SideFpsLogger
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shared.Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.unfold.compat.ScreenSizeFoldProvider
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var activityTaskManager: ActivityTaskManager
    @Mock private lateinit var faceAuthInteractor: KeyguardFaceAuthInteractor
    @Mock
    private lateinit var fingerprintInteractiveToAuthProvider: FingerprintInteractiveToAuthProvider
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var screenSizeFoldProvider: ScreenSizeFoldProvider
    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var windowManager: WindowManager

    private val contextDisplayInfo = DisplayInfo()

    private val bouncerRepository = FakeKeyguardBouncerRepository()
    private val biometricSettingsRepository = FakeBiometricSettingsRepository()
    private val biometricStatusRepository = FakeBiometricStatusRepository()
    private val deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
    private val displayRepository = FakeDisplayRepository()
    private val displayStateRepository = FakeDisplayStateRepository()
    private val fingerprintPropertyRepository = FakeFingerprintPropertyRepository()

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

    private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    private lateinit var biometricStatusInteractor: BiometricStatusInteractor
    private lateinit var deviceEntrySideFpsOverlayInteractor: DeviceEntrySideFpsOverlayInteractor
    private lateinit var displayStateInteractor: DisplayStateInteractorImpl
    private lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    private lateinit var sfpsSensorInteractor: SideFpsSensorInteractor

    private lateinit var sideFpsProgressBarViewModel: SideFpsProgressBarViewModel

    private lateinit var underTest: SideFpsOverlayViewModel

    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var boundsWidth: Int = 0
    private var boundsHeight: Int = 0

    private lateinit var deviceConfig: DeviceConfig
    private lateinit var sensorLocation: SensorLocationInternal

    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    enum class DeviceConfig {
        X_ALIGNED,
        Y_ALIGNED,
    }

    @Before
    fun setup() {
        mSetFlagsRule.enableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)

        mContext = spy(mContext)

        val resources = mContext.resources
        whenever(mContext.display)
            .thenReturn(
                Display(mock(DisplayManagerGlobal::class.java), 1, contextDisplayInfo, resources)
            )

        alternateBouncerInteractor =
            AlternateBouncerInteractor(
                mock(StatusBarStateController::class.java),
                mock(KeyguardStateController::class.java),
                bouncerRepository,
                fingerprintPropertyRepository,
                biometricSettingsRepository,
                FakeSystemClock(),
                keyguardUpdateMonitor,
                testScope.backgroundScope,
            )

        biometricStatusInteractor =
            BiometricStatusInteractorImpl(activityTaskManager, biometricStatusRepository)

        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository,
                displayRepository,
            )
        displayStateInteractor.setScreenSizeFoldProvider(screenSizeFoldProvider)

        primaryBouncerInteractor =
            PrimaryBouncerInteractor(
                bouncerRepository,
                mock(BouncerView::class.java),
                mock(Handler::class.java),
                mock(KeyguardStateController::class.java),
                mock(KeyguardSecurityModel::class.java),
                mock(PrimaryBouncerCallbackInteractor::class.java),
                mock(FalsingCollector::class.java),
                mock(DismissCallbackRegistry::class.java),
                mContext,
                keyguardUpdateMonitor,
                FakeTrustRepository(),
                testScope.backgroundScope,
                selectedUserInteractor,
                faceAuthInteractor
            )

        deviceEntrySideFpsOverlayInteractor =
            DeviceEntrySideFpsOverlayInteractor(
                testScope.backgroundScope,
                mContext,
                deviceEntryFingerprintAuthRepository,
                primaryBouncerInteractor,
                alternateBouncerInteractor,
                keyguardUpdateMonitor
            )

        whenever(fingerprintInteractiveToAuthProvider.enabledForCurrentUser)
            .thenReturn(MutableStateFlow(false))

        sfpsSensorInteractor =
            SideFpsSensorInteractor(
                mContext,
                fingerprintPropertyRepository,
                windowManager,
                displayStateInteractor,
                Optional.of(fingerprintInteractiveToAuthProvider),
                mock(),
                SideFpsLogger(logcatLogBuffer("SfpsLogger"))
            )

        sideFpsProgressBarViewModel =
            SideFpsProgressBarViewModel(
                mContext,
                mock(),
                sfpsSensorInteractor,
                mock(),
                displayStateInteractor,
                StandardTestDispatcher(),
                testScope.backgroundScope,
            )

        underTest =
            SideFpsOverlayViewModel(
                mContext,
                biometricStatusInteractor,
                deviceEntrySideFpsOverlayInteractor,
                displayStateInteractor,
                sfpsSensorInteractor,
                sideFpsProgressBarViewModel,
            )
    }

    @Test
    fun updatesOverlayViewProperties_onDisplayRotationChange_xAlignedSensor() {
        testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewProperties by collectLastValue(underTest.overlayViewProperties)

            runCurrent()

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)
        }
    }

    @Test
    fun updatesOverlayViewProperties_onDisplayRotationChange_yAlignedSensor() {
        testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.Y_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewProperties by collectLastValue(underTest.overlayViewProperties)

            runCurrent()

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(0f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            assertThat(overlayViewProperties?.indicatorAsset).isEqualTo(R.raw.sfps_pulse_landscape)
            assertThat(overlayViewProperties?.overlayViewRotation).isEqualTo(180f)
        }
    }

    @Test
    fun updatesOverlayViewParams_onDisplayRotationChange_xAlignedSensor() {
        testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewParams by collectLastValue(underTest.overlayViewParams)

            underTest.setLottieBounds(Rect(0, 0, boundsWidth, boundsHeight))
            runCurrent()

            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(sensorLocation.sensorLocationX)
            assertThat(overlayViewParams!!.y).isEqualTo(0)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(0)
            assertThat(overlayViewParams!!.y)
                .isEqualTo(
                    displayHeight - sensorLocation.sensorLocationX - sensorLocation.sensorRadius * 2
                )

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x)
                .isEqualTo(
                    displayWidth - sensorLocation.sensorLocationX - sensorLocation.sensorRadius * 2
                )
            assertThat(overlayViewParams!!.y).isEqualTo(displayHeight - boundsHeight)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParams!!.y).isEqualTo(sensorLocation.sensorLocationX)
        }
    }

    @Test
    fun updatesOverlayViewParams_onDisplayRotationChange_yAlignedSensor() {
        testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.Y_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )

            val overlayViewParams by collectLastValue(underTest.overlayViewParams)

            underTest.setLottieBounds(Rect(0, 0, boundsWidth, boundsHeight))
            runCurrent()

            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParams!!.y).isEqualTo(sensorLocation.sensorLocationY)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(sensorLocation.sensorLocationY)
            assertThat(overlayViewParams!!.y).isEqualTo(0)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(overlayViewParams).isNotNull()
            assertThat(overlayViewParams!!.x).isEqualTo(0)
            assertThat(overlayViewParams!!.y)
                .isEqualTo(
                    displayHeight - sensorLocation.sensorLocationY - sensorLocation.sensorRadius * 2
                )

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
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
        testScope.runTest {
            val lottieCallbacks by collectLastValue(underTest.lottieCallbacks)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            sideFpsProgressBarViewModel.setVisible(false)

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
        testScope.runTest {
            val lottieCallbacks by collectLastValue(underTest.lottieCallbacks)
            setDarkMode(true)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            sideFpsProgressBarViewModel.setVisible(false)

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
        testScope.runTest {
            val lottieCallbacks by collectLastValue(underTest.lottieCallbacks)
            setDarkMode(false)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            sideFpsProgressBarViewModel.setVisible(false)

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
        bouncerRepository.setPrimaryShow(isShowing)
        bouncerRepository.setPrimaryStartingToHide(false)
        val primaryStartDisappearAnimation = if (isAnimatingAway) Runnable {} else null
        bouncerRepository.setPrimaryStartDisappearAnimation(primaryStartDisappearAnimation)

        whenever(keyguardUpdateMonitor.isFingerprintDetectionRunning)
            .thenReturn(fpsDetectionRunning)
        whenever(keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
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

        whenever(windowManager.maximumWindowMetrics)
            .thenReturn(
                WindowMetrics(
                    Rect(0, 0, displayWidth, displayHeight),
                    mock(WindowInsets::class.java),
                )
            )

        contextDisplayInfo.uniqueId = DISPLAY_ID

        fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = mapOf(DISPLAY_ID to sensorLocation)
        )

        displayStateRepository.setIsInRearDisplayMode(isInRearDisplayMode)

        displayStateRepository.setCurrentRotation(rotation)

        displayRepository.emitDisplayChangeEvent(0)
        runCurrent()
    }

    companion object {
        private const val DISPLAY_ID = "displayId"
    }
}
