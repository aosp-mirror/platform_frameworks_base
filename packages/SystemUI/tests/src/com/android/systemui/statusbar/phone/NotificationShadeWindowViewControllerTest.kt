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

package com.android.systemui.statusbar.phone

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.keyguard.LockIconViewController
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.dock.DockManager
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.lowlightclock.LowLightClockController
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.NotificationShadeDepthController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.NotificationShadeWindowView.InteractionEventHandler
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.tuner.TunerService
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.Optional
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class NotificationShadeWindowViewControllerTest : SysuiTestCase() {
    private lateinit var mController: NotificationShadeWindowViewController

    @Mock
    private lateinit var mView: NotificationShadeWindowView
    @Mock
    private lateinit var mTunerService: TunerService
    @Mock
    private lateinit var mStatusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var mCentralSurfaces: CentralSurfaces
    @Mock
    private lateinit var mDockManager: DockManager
    @Mock
    private lateinit var mNotificationPanelViewController: NotificationPanelViewController
    @Mock
    private lateinit var mNotificationShadeDepthController: NotificationShadeDepthController
    @Mock
    private lateinit var mNotificationShadeWindowController: NotificationShadeWindowController
    @Mock
    private lateinit var mKeyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock
    private lateinit var mAmbientState: AmbientState
    @Mock
    private lateinit var stackScrollLayoutController: NotificationStackScrollLayoutController
    @Mock
    private lateinit var mStatusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock
    private lateinit var mStatusBarWindowStateController: StatusBarWindowStateController
    @Mock
    private lateinit var mLockscreenShadeTransitionController: LockscreenShadeTransitionController
    @Mock
    private lateinit var mLockIconViewController: LockIconViewController
    @Mock
    private lateinit var mPhoneStatusBarViewController: PhoneStatusBarViewController
    @Mock
    private lateinit var mLowLightClockController: LowLightClockController

    private lateinit var mInteractionEventHandlerCaptor: ArgumentCaptor<InteractionEventHandler>
    private lateinit var mInteractionEventHandler: InteractionEventHandler

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(mView.bottom).thenReturn(VIEW_BOTTOM)

        mController = NotificationShadeWindowViewController(
            mLockscreenShadeTransitionController,
            FalsingCollectorFake(),
            mTunerService,
            mStatusBarStateController,
            mDockManager,
            mNotificationShadeDepthController,
            mView,
            mNotificationPanelViewController,
            PanelExpansionStateManager(),
            stackScrollLayoutController,
            mStatusBarKeyguardViewManager,
            mStatusBarWindowStateController,
            mLockIconViewController,
            Optional.of(mLowLightClockController),
            mCentralSurfaces,
            mNotificationShadeWindowController,
            mKeyguardUnlockAnimationController,
            mAmbientState
        )
        mController.setupExpandedStatusBar()

        mInteractionEventHandlerCaptor =
            ArgumentCaptor.forClass(InteractionEventHandler::class.java)
        verify(mView).setInteractionEventHandler(mInteractionEventHandlerCaptor.capture())
            mInteractionEventHandler = mInteractionEventHandlerCaptor.value
    }

    // Note: So far, these tests only cover interactions with the status bar view controller. More
    // tests need to be added to test the rest of handleDispatchTouchEvent.

    @Test
    fun handleDispatchTouchEvent_nullStatusBarViewController_returnsFalse() {
        mController.setStatusBarViewController(null)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        assertThat(returnVal).isFalse()
    }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowView_sendsTouchToSb() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0)
        whenever(mPhoneStatusBarViewController.sendTouchToView(ev)).thenReturn(true)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(ev)

        verify(mPhoneStatusBarViewController).sendTouchToView(ev)
        assertThat(returnVal).isTrue()
    }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowViewThenAnotherTouch_sendsTouchToSb() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        val downEvBelow = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0
        )
        mInteractionEventHandler.handleDispatchTouchEvent(downEvBelow)

        val nextEvent = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_MOVE, 0f, VIEW_BOTTOM + 5f, 0
        )
        whenever(mPhoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(nextEvent)

        verify(mPhoneStatusBarViewController).sendTouchToView(nextEvent)
        assertThat(returnVal).isTrue()
    }

    @Test
    fun handleDispatchTouchEvent_downAndPanelCollapsedAndInSbBoundAndSbWindowShow_sendsTouchToSb() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        whenever(mStatusBarWindowStateController.windowIsShowing()).thenReturn(true)
        whenever(mNotificationPanelViewController.isFullyCollapsed).thenReturn(true)
        whenever(mPhoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
            .thenReturn(true)
        whenever(mPhoneStatusBarViewController.sendTouchToView(downEv)).thenReturn(true)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        verify(mPhoneStatusBarViewController).sendTouchToView(downEv)
        assertThat(returnVal).isTrue()
    }

    @Test
    fun handleDispatchTouchEvent_panelNotCollapsed_returnsNull() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        whenever(mStatusBarWindowStateController.windowIsShowing()).thenReturn(true)
        whenever(mPhoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
            .thenReturn(true)
        // Item we're testing
        whenever(mNotificationPanelViewController.isFullyCollapsed).thenReturn(false)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        verify(mPhoneStatusBarViewController, never()).sendTouchToView(downEv)
        assertThat(returnVal).isNull()
    }

    @Test
    fun handleDispatchTouchEvent_touchNotInSbBounds_returnsNull() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        whenever(mStatusBarWindowStateController.windowIsShowing()).thenReturn(true)
        whenever(mNotificationPanelViewController.isFullyCollapsed).thenReturn(true)
        // Item we're testing
        whenever(mPhoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
            .thenReturn(false)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        verify(mPhoneStatusBarViewController, never()).sendTouchToView(downEv)
        assertThat(returnVal).isNull()
    }

    @Test
    fun handleDispatchTouchEvent_sbWindowNotShowing_noSendTouchToSbAndReturnsTrue() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        whenever(mNotificationPanelViewController.isFullyCollapsed).thenReturn(true)
        whenever(mPhoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
            .thenReturn(true)
        // Item we're testing
        whenever(mStatusBarWindowStateController.windowIsShowing()).thenReturn(false)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        verify(mPhoneStatusBarViewController, never()).sendTouchToView(downEv)
        assertThat(returnVal).isTrue()
    }

    @Test
    fun handleDispatchTouchEvent_downEventSentToSbThenAnotherEvent_sendsTouchToSb() {
        mController.setStatusBarViewController(mPhoneStatusBarViewController)
        whenever(mStatusBarWindowStateController.windowIsShowing()).thenReturn(true)
        whenever(mNotificationPanelViewController.isFullyCollapsed).thenReturn(true)
        whenever(mPhoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
            .thenReturn(true)

        // Down event first
        mInteractionEventHandler.handleDispatchTouchEvent(downEv)

        // Then another event
        val nextEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        whenever(mPhoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

        val returnVal = mInteractionEventHandler.handleDispatchTouchEvent(nextEvent)

        verify(mPhoneStatusBarViewController).sendTouchToView(nextEvent)
        assertThat(returnVal).isTrue()
    }

    @Test
    fun testLowLightClockAttachedWhenExpandedStatusBarSetup() {
        verify(mLowLightClockController).attachLowLightClockView(ArgumentMatchers.any())
    }

    @Test
    fun testLowLightClockShownWhenDozing() {
        mController.setDozing(true)
        verify(mLowLightClockController).showLowLightClock(true)
    }

    @Test
    fun testLowLightClockDozeTimeTickCalled() {
        mController.dozeTimeTick()
        verify(mLowLightClockController).dozeTimeTick()
    }

    @Test
    fun testLowLightClockHiddenWhenNotDozing() {
        mController.setDozing(true)
        verify(mLowLightClockController).showLowLightClock(true)
        mController.setDozing(false)
        verify(mLowLightClockController).showLowLightClock(false)
    }
}

private val downEv = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
private const val VIEW_BOTTOM = 100
