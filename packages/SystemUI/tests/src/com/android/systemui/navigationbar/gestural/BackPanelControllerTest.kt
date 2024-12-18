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

package com.android.systemui.navigationbar.gestural

import android.os.Handler
import android.testing.TestableLooper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.internal.jank.Cuj
import com.android.internal.util.LatencyTracker
import com.android.systemui.SysuiTestCase
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.plugins.NavigationEdgeBackPlugin
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BackPanelControllerTest : SysuiTestCase() {
    companion object {
        private const val START_X: Float = 0f
    }

    private val kosmos = testKosmos()
    private lateinit var mBackPanelController: BackPanelController
    private lateinit var systemClock: FakeSystemClock
    private lateinit var testableLooper: TestableLooper
    private var triggerThreshold: Float = 0.0f
    private val touchSlop = ViewConfiguration.get(context).scaledEdgeSlop
    @Mock private lateinit var vibratorHelper: VibratorHelper
    @Mock private lateinit var viewCaptureAwareWindowManager: ViewCaptureAwareWindowManager
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var latencyTracker: LatencyTracker
    private val interactionJankMonitor by lazy { kosmos.interactionJankMonitor }
    @Mock private lateinit var layoutParams: WindowManager.LayoutParams
    @Mock private lateinit var backCallback: NavigationEdgeBackPlugin.BackCallback

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        systemClock = FakeSystemClock()
        mBackPanelController =
            BackPanelController(
                context,
                viewCaptureAwareWindowManager,
                ViewConfiguration.get(context),
                Handler.createAsync(testableLooper.looper),
                systemClock,
                vibratorHelper,
                configurationController,
                latencyTracker,
                interactionJankMonitor,
            )
        mBackPanelController.setLayoutParams(layoutParams)
        mBackPanelController.setBackCallback(backCallback)
        mBackPanelController.setIsLeftPanel(true)
        triggerThreshold = mBackPanelController.params.staticTriggerThreshold
    }

    @Test
    fun handlesActionDown() {
        startTouch()

        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.GONE)
    }

    @Test
    fun staysHiddenBeforeSlopCrossed() {
        startTouch()
        // Move just enough to not cross the touch slop
        continueTouch(START_X + touchSlop - 1)

        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.GONE)
        verify(interactionJankMonitor, never()).begin(any())
    }

    @Test
    fun handlesBackCommitted() {
        startTouch()
        // Move once to cross the touch slop
        continueTouch(START_X + touchSlop.toFloat() + 1)
        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.ENTRY)
        verify(interactionJankMonitor).cancel(Cuj.CUJ_BACK_PANEL_ARROW)
        verify(interactionJankMonitor)
            .begin(mBackPanelController.getBackPanelView(), Cuj.CUJ_BACK_PANEL_ARROW)
        // Move again to cross the back trigger threshold
        continueTouch(START_X + touchSlop + triggerThreshold + 1)
        // Wait threshold duration and hold touch past trigger threshold
        moveTimeForward((MAX_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION + 1).toLong())
        continueTouch(START_X + touchSlop + triggerThreshold + 1)

        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.ACTIVE)
        verify(backCallback).setTriggerBack(true)
        moveTimeForward(100)
        verify(vibratorHelper)
            .performHapticFeedback(any(), eq(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE))
        finishTouchActionUp(START_X + touchSlop + triggerThreshold + 1)
        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.COMMITTED)
        verify(backCallback).triggerBack()

        // Because the Handler that is typically used for transitioning the arrow state from
        // COMMITTED to GONE is used as an animation-end-listener on a SpringAnimation,
        // there is no way to meaningfully test that the state becomes GONE and that the tracked
        // jank interaction is ended. So instead, manually trigger the failsafe, which does
        // the same thing:
        mBackPanelController.failsafeRunnable.run()
        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.GONE)
        verify(interactionJankMonitor).end(Cuj.CUJ_BACK_PANEL_ARROW)
    }

    @Test
    fun handlesBackCancelled() {
        startTouch()
        // Move once to cross the touch slop
        continueTouch(START_X + touchSlop.toFloat() + 1)
        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.ENTRY)
        // Move again to cross the back trigger threshold
        continueTouch(
            START_X + touchSlop + triggerThreshold -
                mBackPanelController.params.deactivationTriggerThreshold
        )
        // Wait threshold duration and hold touch before trigger threshold
        moveTimeForward((MAX_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION + 1).toLong())
        continueTouch(
            START_X + touchSlop + triggerThreshold -
                mBackPanelController.params.deactivationTriggerThreshold
        )
        clearInvocations(backCallback)
        moveTimeForward(MIN_DURATION_ACTIVE_BEFORE_INACTIVE_ANIMATION)

        // Move in the opposite direction to cross the deactivation threshold and cancel back
        continueTouch(START_X)

        assertThat(mBackPanelController.currentState)
            .isEqualTo(BackPanelController.GestureState.INACTIVE)
        verify(backCallback).setTriggerBack(false)
        verify(vibratorHelper)
            .performHapticFeedback(any(), eq(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE))

        finishTouchActionUp(START_X)
        verify(backCallback).cancelBack()
    }

    private fun startTouch() {
        mBackPanelController.onMotionEvent(createMotionEvent(ACTION_DOWN, START_X, 0f))
    }

    private fun continueTouch(x: Float) {
        mBackPanelController.onMotionEvent(createMotionEvent(ACTION_MOVE, x, 0f))
    }

    private fun finishTouchActionUp(x: Float) {
        mBackPanelController.onMotionEvent(createMotionEvent(ACTION_UP, x, 0f))
    }

    private fun createMotionEvent(action: Int, x: Float, y: Float): MotionEvent {
        return MotionEvent.obtain(0L, 0L, action, x, y, 0)
    }

    private fun moveTimeForward(millis: Long) {
        systemClock.advanceTime(millis)
        testableLooper.moveTimeForward(millis)
        testableLooper.processAllMessages()
    }
}
