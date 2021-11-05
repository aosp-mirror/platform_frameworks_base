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
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricOverlayConstants.REASON_UNKNOWN
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
import android.view.WindowMetrics
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
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
    lateinit var sidefpsView: View
    @Mock
    lateinit var displayManager: DisplayManager
    @Mock
    lateinit var overviewProxyService: OverviewProxyService
    @Mock
    lateinit var handler: Handler
    @Captor
    lateinit var overlayCaptor: ArgumentCaptor<View>

    private val executor = FakeExecutor(FakeSystemClock())
    private lateinit var overlayController: ISidefpsController
    private lateinit var sideFpsController: SidefpsController

    @Before
    fun setup() {
        context.addMockSystemService(DisplayManager::class.java, displayManager)
        context.addMockSystemService(WindowManager::class.java, windowManager)

        `when`(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sidefpsView)
        `when`(sidefpsView.findViewById<LottieAnimationView>(eq(R.id.sidefps_animation)))
            .thenReturn(mock(LottieAnimationView::class.java))
        with(mock(ViewPropertyAnimator::class.java)) {
            `when`(sidefpsView.animate()).thenReturn(this)
            `when`(alpha(anyFloat())).thenReturn(this)
            `when`(setStartDelay(anyLong())).thenReturn(this)
            `when`(setDuration(anyLong())).thenReturn(this)
            `when`(setListener(any())).thenAnswer {
                (it.arguments[0] as Animator.AnimatorListener)
                    .onAnimationEnd(mock(Animator::class.java))
                this
            }
        }
        `when`(fingerprintManager.sensorPropertiesInternal).thenReturn(
            listOf(
                FingerprintSensorPropertiesInternal(
                    SENSOR_ID,
                    SensorProperties.STRENGTH_STRONG,
                    5 /* maxEnrollmentsPerUser */,
                    listOf() /* componentInfo */,
                    FingerprintSensorProperties.TYPE_POWER_BUTTON,
                    true /* resetLockoutRequiresHardwareAuthToken */
                )
            )
        )
        `when`(windowManager.maximumWindowMetrics).thenReturn(
            WindowMetrics(Rect(0, 0, 800, 800), WindowInsets.CONSUMED)
        )
    }

    private fun testWithDisplay(initInfo: DisplayInfo.() -> Unit = {}, block: () -> Unit) {
        val displayInfo = DisplayInfo()
        displayInfo.initInfo()
        val dmGlobal = mock(DisplayManagerGlobal::class.java)
        val display = Display(dmGlobal, DISPLAY_ID, displayInfo, DEFAULT_DISPLAY_ADJUSTMENTS)
        `when`(dmGlobal.getDisplayInfo(eq(DISPLAY_ID))).thenReturn(displayInfo)
        `when`(windowManager.defaultDisplay).thenReturn(display)

        sideFpsController = SidefpsController(
            context.createDisplayContext(display), layoutInflater, fingerprintManager,
            windowManager, overviewProxyService, displayManager, executor, handler
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

        verify(displayManager).registerDisplayListener(any(), eq(handler))

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

    private fun testIgnoredFor(reason: Int) {
        overlayController.show(SENSOR_ID, reason)

        executor.runAllReady()

        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun showsWithTaskbar() = testWithDisplay({ rotation = Surface.ROTATION_0 }) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar90() = testWithDisplay({ rotation = Surface.ROTATION_90 }) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbar180() = testWithDisplay({ rotation = Surface.ROTATION_180 }) {
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun showsWithTaskbarCollapsedDown() = testWithDisplay({ rotation = Surface.ROTATION_270 }) {
        `when`(windowManager.currentWindowMetrics).thenReturn(
            WindowMetrics(Rect(0, 0, 800, 800), insetsForSmallNavbar())
        )
        hidesWithTaskbar(visible = true)
    }

    @Test
    fun hidesWithTaskbarDown() = testWithDisplay({ rotation = Surface.ROTATION_270 }) {
        `when`(windowManager.currentWindowMetrics).thenReturn(
            WindowMetrics(Rect(0, 0, 800, 800), insetsForLargeNavbar())
        )
        hidesWithTaskbar(visible = false)
    }

    private fun hidesWithTaskbar(visible: Boolean) {
        overlayController.show(SENSOR_ID, REASON_UNKNOWN)
        executor.runAllReady()

        sideFpsController.overviewProxyListener.onTaskbarStatusUpdated(true, false)
        executor.runAllReady()

        verify(windowManager).addView(any(), any())
        verify(windowManager, never()).removeView(any())
        verify(sidefpsView).visibility = if (visible) View.VISIBLE else View.GONE
    }
}

private fun insetsForSmallNavbar() = insetsWithBottom(60)
private fun insetsForLargeNavbar() = insetsWithBottom(100)
private fun insetsWithBottom(bottom: Int) = WindowInsets.Builder()
    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
    .build()