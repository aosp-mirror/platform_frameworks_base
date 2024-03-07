/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.animation.Animator
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.ComponentName
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_UNKNOWN
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.testing.TestableLooper
import android.view.Display
import android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
import android.view.DisplayInfo
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
import android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
import android.view.WindowMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shared.Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenEver
import org.mockito.junit.MockitoJUnit

private const val DISPLAY_ID = 2
private const val SENSOR_ID = 1

private const val REAR_DISPLAY_MODE_DEVICE_STATE = 3

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class SideFpsControllerTest : SysuiTestCase() {

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock lateinit var layoutInflater: LayoutInflater
    @Mock lateinit var fingerprintManager: FingerprintManager
    @Mock lateinit var windowManager: WindowManager
    @Mock lateinit var activityTaskManager: ActivityTaskManager
    @Mock lateinit var sideFpsView: View
    @Mock lateinit var displayManager: DisplayManager
    @Mock lateinit var handler: Handler
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var fpsUnlockTracker: FpsUnlockTracker
    @Captor lateinit var overlayCaptor: ArgumentCaptor<View>
    @Captor lateinit var overlayViewParamsCaptor: ArgumentCaptor<WindowManager.LayoutParams>

    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var displayStateRepository: FakeDisplayStateRepository
    private lateinit var keyguardBouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    private lateinit var displayStateInteractor: DisplayStateInteractor

    private val executor = FakeExecutor(FakeSystemClock())
    private val testScope = TestScope(StandardTestDispatcher())

    private lateinit var overlayController: ISidefpsController
    private lateinit var sideFpsController: SideFpsController

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
        mSetFlagsRule.disableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        displayRepository = FakeDisplayRepository()
        displayStateRepository = FakeDisplayStateRepository()
        keyguardBouncerRepository = FakeKeyguardBouncerRepository()
        alternateBouncerInteractor =
            AlternateBouncerInteractor(
                mock(StatusBarStateController::class.java),
                mock(KeyguardStateController::class.java),
                keyguardBouncerRepository,
                FakeFingerprintPropertyRepository(),
                FakeBiometricSettingsRepository(),
                FakeSystemClock(),
                mock(KeyguardUpdateMonitor::class.java),
                testScope.backgroundScope,
            )
        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                context,
                executor,
                displayStateRepository,
                displayRepository,
            )

        context.addMockSystemService(DisplayManager::class.java, displayManager)
        context.addMockSystemService(WindowManager::class.java, windowManager)

        whenEver(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sideFpsView)
        whenEver(sideFpsView.requireViewById<LottieAnimationView>(eq(R.id.sidefps_animation)))
            .thenReturn(mock(LottieAnimationView::class.java))
        with(mock(ViewPropertyAnimator::class.java)) {
            whenEver(sideFpsView.animate()).thenReturn(this)
            whenEver(alpha(anyFloat())).thenReturn(this)
            whenEver(setStartDelay(anyLong())).thenReturn(this)
            whenEver(setDuration(anyLong())).thenReturn(this)
            whenEver(setListener(any())).thenAnswer {
                (it.arguments[0] as Animator.AnimatorListener).onAnimationEnd(
                    mock(Animator::class.java)
                )
                this
            }
        }
    }

    private fun testWithDisplay(
        deviceConfig: DeviceConfig = DeviceConfig.X_ALIGNED,
        isReverseDefaultRotation: Boolean = false,
        initInfo: DisplayInfo.() -> Unit = {},
        windowInsets: WindowInsets = insetsForSmallNavbar(),
        inRearDisplayMode: Boolean = false,
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
        var locations = listOf(sensorLocation)

        whenEver(fingerprintManager.sensorPropertiesInternal)
            .thenReturn(
                listOf(
                    FingerprintSensorPropertiesInternal(
                        SENSOR_ID,
                        SensorProperties.STRENGTH_STRONG,
                        5 /* maxEnrollmentsPerUser */,
                        listOf() /* componentInfo */,
                        FingerprintSensorProperties.TYPE_POWER_BUTTON,
                        true /* halControlsIllumination */,
                        true /* resetLockoutRequiresHardwareAuthToken */,
                        locations
                    )
                )
            )

        val displayInfo = DisplayInfo()
        displayInfo.initInfo()

        val dmGlobal = mock(DisplayManagerGlobal::class.java)
        val display = Display(dmGlobal, DISPLAY_ID, displayInfo, DEFAULT_DISPLAY_ADJUSTMENTS)

        whenEver(dmGlobal.getDisplayInfo(eq(DISPLAY_ID))).thenReturn(displayInfo)
        whenEver(windowManager.defaultDisplay).thenReturn(display)
        whenEver(windowManager.maximumWindowMetrics)
            .thenReturn(WindowMetrics(displayBounds, WindowInsets.CONSUMED))
        whenEver(windowManager.currentWindowMetrics)
            .thenReturn(WindowMetrics(displayBounds, windowInsets))

        val sideFpsControllerContext = context.createDisplayContext(display) as SysuiTestableContext
        sideFpsControllerContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_reverseDefaultRotation,
            isReverseDefaultRotation
        )

        val rearDisplayDeviceStates =
            if (inRearDisplayMode) intArrayOf(REAR_DISPLAY_MODE_DEVICE_STATE) else intArrayOf()
        sideFpsControllerContext.orCreateTestableResources.addOverride(
            com.android.internal.R.array.config_rearDisplayDeviceStates,
            rearDisplayDeviceStates
        )

        sideFpsController =
            SideFpsController(
                sideFpsControllerContext,
                layoutInflater,
                fingerprintManager,
                windowManager,
                activityTaskManager,
                displayManager,
                displayStateInteractor,
                executor,
                handler,
                alternateBouncerInteractor,
                TestCoroutineScope(),
                dumpManager,
                fpsUnlockTracker
            )
        displayStateRepository.setIsInRearDisplayMode(inRearDisplayMode)

        overlayController =
            ArgumentCaptor.forClass(ISidefpsController::class.java)
                .apply { verify(fingerprintManager).setSidefpsController(capture()) }
                .value

        block()
    }

    @Test
    fun testSubscribesToOrientationChangesWhenShowingOverlay() = testWithDisplay {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        verify(displayManager).registerDisplayListener(any(), eq(handler), anyLong())

        overlayController.hide(SENSOR_ID)
        executor.runAllReady()
        verify(displayManager).unregisterDisplayListener(any())
    }

    @Test
    fun testShowOverlayReasonWhenDisplayChanged() = testWithDisplay {
        sideFpsController.show(SideFpsUiRequestSource.AUTO_SHOW, REASON_AUTH_KEYGUARD)
        executor.runAllReady()
        sideFpsController.orientationListener.onDisplayChanged(1 /* displayId */)
        executor.runAllReady()

        assertThat(sideFpsController.orientationReasonListener.reason)
            .isEqualTo(REASON_AUTH_KEYGUARD)
    }

    @Test
    fun testShowsAndHides() = testWithDisplay {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        verify(windowManager).addView(overlayCaptor.capture(), any())

        reset(windowManager)
        overlayController.hide(SENSOR_ID)
        executor.runAllReady()

        verify(windowManager, never()).addView(any(), any())
        verify(windowManager).removeView(eq(overlayCaptor.value))
    }

    @Test
    fun testShowsOnce() = testWithDisplay {
        repeat(5) {
            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()
        }

        verify(windowManager).addView(any(), any())
        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun testHidesOnce() = testWithDisplay {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        repeat(5) {
            overlayController.hide(SENSOR_ID)
            executor.runAllReady()
        }

        verify(windowManager).addView(any(), any())
        verify(windowManager).removeView(any())
    }

    @Test fun testIgnoredForKeyguard() = testWithDisplay { testIgnoredFor(REASON_AUTH_KEYGUARD) }

    @Test
    fun testShowsForMostSettings() = testWithDisplay {
        whenEver(activityTaskManager.getTasks(anyInt())).thenReturn(listOf(fpEnrollTask()))
        testIgnoredFor(REASON_AUTH_SETTINGS, ignored = false)
    }

    @Test
    fun testIgnoredForVerySpecificSettings() = testWithDisplay {
        whenEver(activityTaskManager.getTasks(anyInt())).thenReturn(listOf(fpSettingsTask()))
        testIgnoredFor(REASON_AUTH_SETTINGS)
    }

    private fun testIgnoredFor(reason: Int, ignored: Boolean = true) {
        overlayController.show(SENSOR_ID, reason)
        executor.runAllReady()

        verify(windowManager, if (ignored) never() else times(1)).addView(any(), any())
    }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_0 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_90 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_180 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarCollapsedDownForXAlignedSensor_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_180 },
            windowInsets = insetsForSmallNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun hidesSfpsIndicatorWhenOccludingTaskbarForXAlignedSensor_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_180 },
            windowInsets = insetsForLargeNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = false)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_270 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_InReverseDefaultRotation_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_0 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_InReverseDefaultRotation_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_90 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarCollapsedDownForXAlignedSensor_InReverseDefaultRotation_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_90 },
            windowInsets = insetsForSmallNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun hidesSfpsIndicatorWhenOccludingTaskbarForXAlignedSensor_InReverseDefaultRotation_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_90 },
            windowInsets = insetsForLargeNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = false)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_InReverseDefaultRotation_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_180 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForXAlignedSensor_InReverseDefaultRotation_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_270 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_0 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_90 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_180 },
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_270 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarCollapsedDownForYAlignedSensor_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_270 },
            windowInsets = insetsForSmallNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun hidesSfpsIndicatorWhenOccludingTaskbarForYAlignedSensor_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_270 },
            windowInsets = insetsForLargeNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = false)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_InReverseDefaultRotation_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_0 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_InReverseDefaultRotation_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_90 },
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_InReverseDefaultRotation_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_180 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarCollapsedDownForYAlignedSensor_InReverseDefaultRotation_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_180 },
            windowInsets = insetsForSmallNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun hidesSfpsIndicatorWhenOccludingTaskbarForYAlignedSensor_InReverseDefaultRotation_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_180 },
            windowInsets = insetsForLargeNavbar()
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = false)
        }

    @Test
    fun showsSfpsIndicatorWithTaskbarForYAlignedSensor_InReverseDefaultRotation_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_270 }
        ) {
            verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible = true)
        }

    @Test
    fun verifiesSfpsIndicatorNotAddedInRearDisplayMode_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_0 },
            inRearDisplayMode = true,
        ) {
            verifySfpsIndicator_notAdded_InRearDisplayMode()
        }

    @Test
    fun verifiesSfpsIndicatorNotAddedInRearDisplayMode_90() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_90 },
            inRearDisplayMode = true,
        ) {
            verifySfpsIndicator_notAdded_InRearDisplayMode()
        }

    @Test
    fun verifiesSfpsIndicatorNotAddedInRearDisplayMode_180() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_180 },
            inRearDisplayMode = true,
        ) {
            verifySfpsIndicator_notAdded_InRearDisplayMode()
        }

    @Test
    fun verifiesSfpsIndicatorNotAddedInRearDisplayMode_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_270 },
            inRearDisplayMode = true,
        ) {
            verifySfpsIndicator_notAdded_InRearDisplayMode()
        }

    private fun verifySfpsIndicatorVisibilityOnTaskbarUpdate(sfpsViewVisible: Boolean) {
        sideFpsController.overlayOffsets = sensorLocation
    }

    private fun verifySfpsIndicator_notAdded_InRearDisplayMode() {
        sideFpsController.overlayOffsets = sensorLocation
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        verify(windowManager, never()).addView(any(), any())
    }

    fun alternateBouncerVisibility_showAndHideSideFpsUI() = testWithDisplay {
        // WHEN alternate bouncer is visible
        keyguardBouncerRepository.setAlternateVisible(true)
        executor.runAllReady()

        // THEN side fps shows UI
        verify(windowManager).addView(any(), any())
        verify(windowManager, never()).removeView(any())

        // WHEN alternate bouncer is no longer visible
        keyguardBouncerRepository.setAlternateVisible(false)
        executor.runAllReady()

        // THEN side fps UI is hidden
        verify(windowManager).removeView(any())
    }

    /**
     * {@link SideFpsController#updateOverlayParams} calculates indicator placement for ROTATION_0,
     * and uses RotateUtils.rotateBounds to map to the correct indicator location given the device
     * rotation. Assuming RotationUtils.rotateBounds works correctly, tests for indicator placement
     * in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForXAlignedSensor_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_0 }
        ) {
            sideFpsController.overlayOffsets = sensorLocation

            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)

            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())
            assertThat(overlayViewParamsCaptor.value.x).isEqualTo(sensorLocation.sensorLocationX)
            assertThat(overlayViewParamsCaptor.value.y).isEqualTo(0)
        }

    /**
     * {@link SideFpsController#updateOverlayParams} calculates indicator placement for ROTATION_270
     * in reverse default rotation. It then uses RotateUtils.rotateBounds to map to the correct
     * indicator location given the device rotation. Assuming RotationUtils.rotateBounds works
     * correctly, tests for indicator placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForXAlignedSensor_InReverseDefaultRotation_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.X_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_270 }
        ) {
            sideFpsController.overlayOffsets = sensorLocation

            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)

            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())
            assertThat(overlayViewParamsCaptor.value.x).isEqualTo(sensorLocation.sensorLocationX)
            assertThat(overlayViewParamsCaptor.value.y).isEqualTo(0)
        }

    /**
     * {@link SideFpsController#updateOverlayParams} calculates indicator placement for ROTATION_0,
     * and uses RotateUtils.rotateBounds to map to the correct indicator location given the device
     * rotation. Assuming RotationUtils.rotateBounds works correctly, tests for indicator placement
     * in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForYAlignedSensor_0() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = false,
            { rotation = Surface.ROTATION_0 }
        ) {
            sideFpsController.overlayOffsets = sensorLocation

            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)

            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())
            assertThat(overlayViewParamsCaptor.value.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParamsCaptor.value.y).isEqualTo(sensorLocation.sensorLocationY)
        }

    /**
     * {@link SideFpsController#updateOverlayParams} calculates indicator placement for ROTATION_270
     * in reverse default rotation. It then uses RotateUtils.rotateBounds to map to the correct
     * indicator location given the device rotation. Assuming RotationUtils.rotateBounds works
     * correctly, tests for indicator placement in other rotations have been omitted.
     */
    @Test
    fun verifiesIndicatorPlacementForYAlignedSensor_InReverseDefaultRotation_270() =
        testWithDisplay(
            deviceConfig = DeviceConfig.Y_ALIGNED,
            isReverseDefaultRotation = true,
            { rotation = Surface.ROTATION_270 }
        ) {
            sideFpsController.overlayOffsets = sensorLocation

            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)

            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())
            assertThat(overlayViewParamsCaptor.value.x).isEqualTo(displayWidth - boundsWidth)
            assertThat(overlayViewParamsCaptor.value.y).isEqualTo(sensorLocation.sensorLocationY)
        }

    @Test
    fun hasSideFpsSensor_withSensorProps_returnsTrue() = testWithDisplay {
        // By default all those tests assume the side fps sensor is available.
        assertThat(fingerprintManager.hasSideFpsSensor()).isTrue()
    }

    @Test
    fun hasSideFpsSensor_withoutSensorProps_returnsFalse() {
        whenEver(fingerprintManager.sensorPropertiesInternal).thenReturn(null)

        assertThat(fingerprintManager.hasSideFpsSensor()).isFalse()
    }

    @Test
    fun testLayoutParams_isKeyguardDialogType() =
        testWithDisplay(deviceConfig = DeviceConfig.Y_ALIGNED) {
            sideFpsController.overlayOffsets = sensorLocation
            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)
            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

            val lpType = overlayViewParamsCaptor.value.type

            assertThat((lpType and TYPE_KEYGUARD_DIALOG) != 0).isTrue()
        }

    @Test
    fun testLayoutParams_hasNoMoveAnimationWindowFlag() =
        testWithDisplay(deviceConfig = DeviceConfig.Y_ALIGNED) {
            sideFpsController.overlayOffsets = sensorLocation
            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)
            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

            val lpFlags = overlayViewParamsCaptor.value.privateFlags

            assertThat((lpFlags and PRIVATE_FLAG_NO_MOVE_ANIMATION) != 0).isTrue()
        }

    @Test
    fun testLayoutParams_hasTrustedOverlayWindowFlag() =
        testWithDisplay(deviceConfig = DeviceConfig.Y_ALIGNED) {
            sideFpsController.overlayOffsets = sensorLocation
            sideFpsController.updateOverlayParams(windowManager.defaultDisplay, indicatorBounds)
            overlayController.show(SENSOR_ID, REASON_UNKNOWN)
            executor.runAllReady()

            verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

            val lpFlags = overlayViewParamsCaptor.value.privateFlags

            assertThat((lpFlags and PRIVATE_FLAG_TRUSTED_OVERLAY) != 0).isTrue()
        }

    @Test
    fun primaryBouncerRequestAnimatesAlphaIn() = testWithDisplay {
        sideFpsController.show(SideFpsUiRequestSource.PRIMARY_BOUNCER, REASON_AUTH_KEYGUARD)
        executor.runAllReady()
        verify(sideFpsView).animate()
    }

    @Test
    fun alternateBouncerRequestsDoesNotAnimateAlphaIn() = testWithDisplay {
        sideFpsController.show(SideFpsUiRequestSource.ALTERNATE_BOUNCER, REASON_AUTH_KEYGUARD)
        executor.runAllReady()
        verify(sideFpsView, never()).animate()
    }

    @Test
    fun autoShowRequestsDoesNotAnimateAlphaIn() = testWithDisplay {
        sideFpsController.show(SideFpsUiRequestSource.AUTO_SHOW, REASON_AUTH_KEYGUARD)
        executor.runAllReady()
        verify(sideFpsView, never()).animate()
    }
}

private fun insetsForSmallNavbar() = insetsWithBottom(60)

private fun insetsForLargeNavbar() = insetsWithBottom(100)

private fun insetsWithBottom(bottom: Int) =
    WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .build()

private fun fpEnrollTask() = settingsTask(".biometrics.fingerprint.FingerprintEnrollEnrolling")

private fun fpSettingsTask() = settingsTask(".biometrics.fingerprint.FingerprintSettings")

private fun settingsTask(cls: String) =
    ActivityManager.RunningTaskInfo().apply {
        topActivity = ComponentName.createRelative("com.android.settings", cls)
    }
