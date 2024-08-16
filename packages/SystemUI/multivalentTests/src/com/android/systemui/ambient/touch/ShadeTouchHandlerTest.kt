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
package com.android.systemui.ambient.touch

import android.app.DreamManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.ambient.touch.TouchHandler.TouchSession
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeTouchHandlerTest : SysuiTestCase() {
    private var kosmos = testKosmos()
    private var mCentralSurfaces = mock<CentralSurfaces>()
    private var mShadeViewController = mock<ShadeViewController>()
    private var mDreamManager = mock<DreamManager>()
    private var mTouchSession = mock<TouchSession>()
    private var communalViewModel = mock<CommunalViewModel>()

    private lateinit var mTouchHandler: ShadeTouchHandler

    private var mGestureListenerCaptor = argumentCaptor<GestureDetector.OnGestureListener>()
    private var mInputListenerCaptor = argumentCaptor<InputChannelCompat.InputEventListener>()

    @Before
    fun setup() {
        mTouchHandler =
            ShadeTouchHandler(
                kosmos.testScope,
                Optional.of(mCentralSurfaces),
                mShadeViewController,
                mDreamManager,
                communalViewModel,
                kosmos.communalSettingsInteractor,
                TOUCH_HEIGHT
            )
    }

    // Verifies that a swipe down in the gesture region is captured by the shade touch handler.
    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testSwipeDown_captured() {
        val captured = swipe(Direction.DOWN)
        Truth.assertThat(captured).isTrue()
    }

    // Verifies that a swipe in the upward direction is not captured.
    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testSwipeUp_notCaptured() {
        val captured = swipe(Direction.UP)

        // Motion events not captured as the swipe is going in the wrong direction.
        Truth.assertThat(captured).isFalse()
    }

    // Verifies that a swipe down forwards captured touches to central surfaces for handling.
    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB)
    fun testSwipeDown_communalEnabled_sentToCentralSurfaces() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

        swipe(Direction.DOWN)

        // Both motion events are sent for central surfaces to process.
        verify(mCentralSurfaces, times(2)).handleExternalShadeWindowTouch(any())
    }

    // Verifies that a swipe down forwards captured touches to the shade view for handling.
    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_HUB, Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testSwipeDown_communalDisabled_sentToShadeView() {
        swipe(Direction.DOWN)

        // Both motion events are sent for the shade view to process.
        verify(mShadeViewController, times(2)).handleExternalTouch(any())
    }

    // Verifies that a swipe down while dreaming forwards captured touches to the shade view for
    // handling.
    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testSwipeDown_dreaming_sentToShadeView() {
        whenever(mDreamManager.isDreaming).thenReturn(true)
        swipe(Direction.DOWN)

        // Both motion events are sent for the shade view to process.
        verify(mShadeViewController, times(2)).handleExternalTouch(any())
    }

    // Verifies that a swipe up is not forwarded to central surfaces.
    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB)
    fun testSwipeUp_communalEnabled_touchesNotSent() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

        swipe(Direction.UP)

        // Motion events are not sent for central surfaces to process as the swipe is going in the
        // wrong direction.
        verify(mCentralSurfaces, never()).handleExternalShadeWindowTouch(any())
    }

    // Verifies that a swipe up is not forwarded to the shade view.
    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_HUB, Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testSwipeUp_communalDisabled_touchesNotSent() {
        swipe(Direction.UP)

        // Motion events are not sent for the shade view to process as the swipe is going in the
        // wrong direction.
        verify(mShadeViewController, never()).handleExternalTouch(any())
    }

    @Test
    @DisableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testCancelMotionEvent_popsTouchSession() {
        swipe(Direction.DOWN)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        mInputListenerCaptor.lastValue.onInputEvent(event)
        verify(mTouchSession).pop()
    }

    @Test
    @EnableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testFullVerticalSwipe_initiatedWhenAvailable() {
        // Indicate touches are available
        mTouchHandler.onGlanceableTouchAvailable(true)

        // Verify swipe is handled
        val captured = swipe(Direction.DOWN)
        Truth.assertThat(captured).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testFullVerticalSwipe_notInitiatedWhenNotAvailable() {
        // Indicate touches aren't available
        mTouchHandler.onGlanceableTouchAvailable(false)

        // Verify swipe is not handled
        val captured = swipe(Direction.DOWN)
        Truth.assertThat(captured).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testFullVerticalSwipe_resetsTouchStateOnUp() {
        // Indicate touches are available
        mTouchHandler.onGlanceableTouchAvailable(true)

        // Verify swipe is handled
        swipe(Direction.DOWN)

        val upEvent: MotionEvent = mock()
        whenever(upEvent.action).thenReturn(MotionEvent.ACTION_UP)
        mInputListenerCaptor.lastValue.onInputEvent(upEvent)

        verify(communalViewModel).onResetTouchState()
    }

    @Test
    @EnableFlags(Flags.FLAG_HUBMODE_FULLSCREEN_VERTICAL_SWIPE_FIX)
    fun testFullVerticalSwipe_resetsTouchStateOnCancel() {
        // Indicate touches are available
        mTouchHandler.onGlanceableTouchAvailable(true)

        // Verify swipe is handled
        swipe(Direction.DOWN)

        val upEvent: MotionEvent = mock()
        whenever(upEvent.action).thenReturn(MotionEvent.ACTION_CANCEL)
        mInputListenerCaptor.lastValue.onInputEvent(upEvent)

        verify(communalViewModel).onResetTouchState()
    }

    /**
     * Simulates a swipe in the given direction and returns true if the touch was intercepted by the
     * touch handler's gesture listener.
     *
     * Swipe down starts from a Y coordinate of 0 and goes downward. Swipe up starts from the edge
     * of the gesture region, [.TOUCH_HEIGHT], and goes upward to 0.
     */
    private fun swipe(direction: Direction): Boolean {
        clearInvocations(mTouchSession)
        mTouchHandler.onSessionStart(mTouchSession)
        verify(mTouchSession).registerGestureListener(mGestureListenerCaptor.capture())
        verify(mTouchSession).registerInputListener(mInputListenerCaptor.capture())
        val startY = (if (direction == Direction.UP) TOUCH_HEIGHT else 0).toFloat()
        val endY = (if (direction == Direction.UP) 0 else TOUCH_HEIGHT).toFloat()

        // Send touches to the input and gesture listener.
        val event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0f, startY, 0)
        val event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0f, endY, 0)
        mInputListenerCaptor.lastValue.onInputEvent(event1)
        mInputListenerCaptor.lastValue.onInputEvent(event2)
        return mGestureListenerCaptor.lastValue.onScroll(event1, event2, 0f, startY - endY)
    }

    private enum class Direction {
        DOWN,
        UP
    }

    companion object {
        private const val TOUCH_HEIGHT = 20
    }
}
