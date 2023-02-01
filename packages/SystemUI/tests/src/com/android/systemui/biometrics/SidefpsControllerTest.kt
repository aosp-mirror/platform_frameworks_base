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
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricOverlayConstants.REASON_UNKNOWN
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.testing.AndroidTestingRunner
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
import android.view.WindowMetrics
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
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

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SidefpsControllerTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock
    lateinit var layoutInflater: LayoutInflater
    @Mock
    lateinit var fingerprintManager: FingerprintManager
    @Mock
    lateinit var windowManager: WindowManager
    @Mock
    lateinit var activityTaskManager: ActivityTaskManager
    @Mock
    lateinit var sidefpsView: View
    @Mock
    lateinit var displayManager: DisplayManager
    @Mock
    lateinit var overviewProxyService: OverviewProxyService
    @Mock
    lateinit var handler: Handler
    @Captor
    lateinit var overlayCaptor: ArgumentCaptor<View>
    @Captor
    lateinit var overlayViewParamsCaptor: ArgumentCaptor<WindowManager.LayoutParams>

    private val executor = FakeExecutor(FakeSystemClock())
    private lateinit var overlayController: ISidefpsController
    private lateinit var sideFpsController: SidefpsController

    enum class DeviceConfig { X_ALIGNED, Y_ALIGNED_UNFOLDED, Y_ALIGNED_FOLDED }

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
        context.addMockSystemService(DisplayManager::class.java, displayManager)
        context.addMockSystemService(WindowManager::class.java, windowManager)

        whenEver(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sidefpsView)
        whenEver(sidefpsView.findViewById<LottieAnimationView>(eq(R.id.sidefps_animation)))
            .thenReturn(mock(LottieAnimationView::class.java))
        with(mock(ViewPropertyAnimator::class.java)) {
            whenEver(sidefpsView.animate()).thenReturn(this)
            whenEver(alpha(anyFloat())).thenReturn(this)
            whenEver(setStartDelay(anyLong())).thenReturn(this)
            whenEver(setDuration(anyLong())).thenReturn(this)
            whenEver(setListener(any())).thenAnswer {
                (it.arguments[0] as Animator.AnimatorListener)
                    .onAnimationEnd(mock(Animator::class.java))
                this
            }
        }
    }

    private fun testWithDisplay(
        deviceConfig: DeviceConfig = DeviceConfig.X_ALIGNED,
        initInfo: DisplayInfo.() -> Unit = {},
        windowInsets: WindowInsets = insetsForSmallNavbar(),
        block: () -> Unit
    ) {
        this.deviceConfig = deviceConfig

        when (deviceConfig) {
            DeviceConfig.X_ALIGNED -> {
                displayWidth = 2560
                displayHeight = 1600
                sensorLocation = SensorLocationInternal("", 2325, 0, 0)
                boundsWidth = 160
                boundsHeight = 84
            }
            DeviceConfig.Y_ALIGNED_UNFOLDED -> {
                displayWidth = 2208
                displayHeight = 1840
                sensorLocation = SensorLocationInternal("", 0, 510, 0)
                boundsWidth = 110
                boundsHeight = 210
            }
            DeviceConfig.Y_ALIGNED_FOLDED -> {
                displayWidth = 1080
                displayHeight = 2100
                sensorLocation = SensorLocationInternal("", 0, 590, 0)
                boundsWidth = 110
                boundsHeight = 210
            }
        }
        indicatorBounds = Rect(0, 0, boundsWidth, boundsHeight)
        displayBounds = Rect(0, 0, displayWidth, displayHeight)
        var locations = listOf(sensorLocation)

        whenEver(fingerprintManager.sensorPropertiesInternal).thenReturn(
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
        whenEver(windowManager.maximumWindowMetrics).thenReturn(
                WindowMetrics(displayBounds, WindowInsets.CONSUMED)
        )
        whenEver(windowManager.currentWindowMetrics).thenReturn(
            WindowMetrics(displayBounds, windowInsets)
        )

        sideFpsController = SidefpsController(
            context.createDisplayContext(display), layoutInflater, fingerprintManager,
            windowManager, activityTaskManager, overviewProxyService, displayManager, executor,
            handler
        )

        overlayController = ArgumentCaptor.forClass(ISidefpsController::class.java).apply {
            verify(fingerprintManager).setSidefpsController(capture())
        }.value

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

    @Test
    fun testIgnoredForKeyguard() = testWithDisplay {
        testIgnoredFor(REASON_AUTH_KEYGUARD)
    }

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
    fun showsWithTaskbar() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED,
        { rotation = Surface.ROTATION_0 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbarOnY() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED,
        { rotation = Surface.ROTATION_0 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar90() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED,
        { rotation = Surface.ROTATION_90 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar90OnY() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED,
        { rotation = Surface.ROTATION_90 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar180() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED,
        { rotation = Surface.ROTATION_180 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar270OnY() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED,
        { rotation = Surface.ROTATION_270 }
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbarCollapsedDown() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED,
        { rotation = Surface.ROTATION_270 },
        windowInsets = insetsForSmallNavbar()
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbarCollapsedDownOnY() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED,
        { rotation = Surface.ROTATION_180 },
        windowInsets = insetsForSmallNavbar()
    ) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun hidesWithTaskbarDown() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED,
        { rotation = Surface.ROTATION_180 },
        windowInsets = insetsForLargeNavbar()
    ) {
        hidesWithTaskbar(visible = false)
    }

    @Test
    fun hidesWithTaskbarDownOnY() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED,
        { rotation = Surface.ROTATION_270 },
        windowInsets = insetsForLargeNavbar()
    ) {
        hidesWithTaskbar(visible = true)
    }

    private fun hidesWithTaskbar(visible: Boolean) {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        sideFpsController.overviewProxyListener.onTaskbarStatusUpdated(visible, false)
        executor.runAllReady()

        verify(windowManager).addView(any(), any())
        verify(windowManager, never()).removeView(any())
        verify(sidefpsView).visibility = if (visible) View.VISIBLE else View.GONE
    }

    @Test
    fun testIndicatorPlacementForXAlignedSensor() = testWithDisplay(
        deviceConfig = DeviceConfig.X_ALIGNED
    ) {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        sideFpsController.overlayOffsets = sensorLocation
        sideFpsController.updateOverlayParams(
            windowManager.defaultDisplay,
            indicatorBounds
        )
        executor.runAllReady()

        verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

        assertThat(overlayViewParamsCaptor.value.x).isEqualTo(sensorLocation.sensorLocationX)
        assertThat(overlayViewParamsCaptor.value.y).isEqualTo(0)
    }

    @Test
    fun testIndicatorPlacementForYAlignedSensor() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED
    ) {
        sideFpsController.overlayOffsets = sensorLocation
        sideFpsController.updateOverlayParams(
            windowManager.defaultDisplay,
            indicatorBounds
        )
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
    fun testLayoutParams_hasNoMoveAnimationWindowFlag() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED
    ) {
        sideFpsController.overlayOffsets = sensorLocation
        sideFpsController.updateOverlayParams(
            windowManager.defaultDisplay,
            indicatorBounds
        )
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

        val lpFlags = overlayViewParamsCaptor.value.privateFlags

        assertThat((lpFlags and PRIVATE_FLAG_NO_MOVE_ANIMATION) != 0).isTrue()
    }

    @Test
    fun testLayoutParams_hasTrustedOverlayWindowFlag() = testWithDisplay(
        deviceConfig = DeviceConfig.Y_ALIGNED_UNFOLDED
    ) {
        sideFpsController.overlayOffsets = sensorLocation
        sideFpsController.updateOverlayParams(
            windowManager.defaultDisplay,
            indicatorBounds
        )
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        verify(windowManager).updateViewLayout(any(), overlayViewParamsCaptor.capture())

        val lpFlags = overlayViewParamsCaptor.value.privateFlags

        assertThat((lpFlags and PRIVATE_FLAG_TRUSTED_OVERLAY) != 0).isTrue()
    }
}

private fun insetsForSmallNavbar() = insetsWithBottom(60)
private fun insetsForLargeNavbar() = insetsWithBottom(100)
private fun insetsWithBottom(bottom: Int) = WindowInsets.Builder()
    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
    .build()

private fun fpEnrollTask() = settingsTask(".biometrics.fingerprint.FingerprintEnrollEnrolling")
private fun fpSettingsTask() = settingsTask(".biometrics.fingerprint.FingerprintSettings")
private fun settingsTask(cls: String) = ActivityManager.RunningTaskInfo().apply {
    topActivity = ComponentName.createRelative("com.android.settings", cls)
}
