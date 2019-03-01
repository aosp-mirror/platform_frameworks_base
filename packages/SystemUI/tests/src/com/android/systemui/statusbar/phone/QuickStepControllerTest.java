/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_DEAD_ZONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_NONE;
import static com.android.systemui.statusbar.phone.NavigationBarView.WINDOW_TARGET_BOTTOM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.IOverviewProxy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;

/** atest QuickStepControllerTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QuickStepControllerTest extends SysuiTestCase {
    private static final int NAVBAR_WIDTH = 1000;
    private static final int NAVBAR_HEIGHT = 300;

    private QuickStepController mController;
    private NavigationBarView mNavigationBarView;
    private StatusBar mStatusBar;
    private OverviewProxyService mProxyService;
    private IOverviewProxy mProxy;
    private Resources mResources;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final ButtonDispatcher backButton = mock(ButtonDispatcher.class);
        mResources = mock(Resources.class);

        mProxyService = mock(OverviewProxyService.class);
        mProxy = mock(IOverviewProxy.Stub.class);
        doReturn(mProxy).when(mProxyService).getProxy();
        doReturn(true).when(mProxyService).shouldShowSwipeUpUI();
        mDependency.injectTestDependency(OverviewProxyService.class, mProxyService);

        mStatusBar = mock(StatusBar.class);
        doReturn(false).when(mStatusBar).isKeyguardShowing();
        mContext.putComponent(StatusBar.class, mStatusBar);

        mNavigationBarView = mock(NavigationBarView.class);
        doReturn(false).when(mNavigationBarView).inScreenPinning();
        doReturn(true).when(mNavigationBarView).isNotificationsFullyCollapsed();
        doReturn(true).when(mNavigationBarView).isQuickScrubEnabled();
        doReturn(HIT_TARGET_NONE).when(mNavigationBarView).getDownHitTarget();
        doReturn(WINDOW_TARGET_BOTTOM).when(mNavigationBarView).getWindowTarget();
        doReturn(backButton).when(mNavigationBarView).getBackButton();
        doReturn(mResources).when(mNavigationBarView).getResources();
        doReturn(mContext).when(mNavigationBarView).getContext();

        mController = new QuickStepController(mContext);
        mController.setComponents(mNavigationBarView);
        mController.setBarState(false /* isRTL */, NAV_BAR_BOTTOM);
    }

    @Test
    public void testNoActionsNoGestures() throws Exception {
        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);
        assertFalse(mController.onInterceptTouchEvent(ev));
        verify(mNavigationBarView, never()).requestUnbufferedDispatch(ev);
        assertNull(mController.getCurrentAction());
    }

    @Test
    public void testNoGesturesWhenSwipeUpDisabled() throws Exception {
        doReturn(false).when(mProxyService).shouldShowSwipeUpUI();
        mController.setGestureActions(mockAction(true), null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */,  null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);
        assertFalse(mController.onInterceptTouchEvent(ev));
        verify(mNavigationBarView, never()).requestUnbufferedDispatch(ev);
        assertNull(mController.getCurrentAction());
    }

    @Test
    public void testHasActionDetectGesturesTouchdown() throws Exception {
        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);

        // Add enabled gesture action
        NavigationGestureAction action = mockAction(true);
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        assertFalse(mController.onInterceptTouchEvent(ev));
        verify(mNavigationBarView, times(1)).requestUnbufferedDispatch(ev);
        verify(action, times(1)).reset();
        verify(mProxy, times(1)).onPreMotionEvent(mNavigationBarView.getDownHitTarget());
        verify(mProxy, times(1)).onMotionEvent(ev);
        assertNull(mController.getCurrentAction());
    }

    @Test
    public void testProxyDisconnectedNoGestures() throws Exception {
        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);

        // Add enabled gesture action
        mController.setGestureActions(mockAction(true), null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Set the gesture on deadzone
        doReturn(null).when(mProxyService).getProxy();

        assertFalse(mController.onInterceptTouchEvent(ev));
        verify(mNavigationBarView, never()).requestUnbufferedDispatch(ev);
        assertNull(mController.getCurrentAction());
    }

    @Test
    public void testNoActionsNoGesturesOverDeadzone() throws Exception {
        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);

        // Touched over deadzone
        doReturn(HIT_TARGET_DEAD_ZONE).when(mNavigationBarView).getDownHitTarget();

        assertTrue(mController.onInterceptTouchEvent(ev));
        verify(mNavigationBarView, never()).requestUnbufferedDispatch(ev);
        assertNull(mController.getCurrentAction());
    }

    @Test
    public void testOnTouchIgnoredDownEventAfterOnIntercept() {
        mController.setGestureActions(mockAction(true), null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        MotionEvent ev = event(MotionEvent.ACTION_DOWN, 1, 1);
        assertFalse(touch(ev));
        verify(mNavigationBarView, times(1)).requestUnbufferedDispatch(ev);

        // OnTouch event for down is ignored, so requestUnbufferedDispatch ran once from before
        assertFalse(mNavigationBarView.onTouchEvent(ev));
        verify(mNavigationBarView, times(1)).requestUnbufferedDispatch(ev);
    }

    @Test
    public void testGesturesCallCorrectAction() throws Exception {
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getHeight();

        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // Swipe Up
        assertGestureTriggersAction(swipeUp, 1, 100, 5, 1);
        // Swipe Down
        assertGestureTriggersAction(swipeDown, 1, 1, 5, 100);
        // Swipe Left
        assertGestureTriggersAction(swipeLeft, NAVBAR_WIDTH / 2, 1, 5, 1);
        // Swipe Right
        assertGestureTriggersAction(swipeRight, NAVBAR_WIDTH / 2, 1, NAVBAR_WIDTH, 5);
    }

    @Test
    public void testGesturesCallCorrectActionLandscape() throws Exception {
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getHeight();

        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // In landscape
        mController.setBarState(false /* isRTL */, NAV_BAR_RIGHT);

        // Swipe Up
        assertGestureTriggersAction(swipeRight, 1, 100, 5, 1);
        // Swipe Down
        assertGestureTriggersAction(swipeLeft, 1, NAVBAR_WIDTH / 2, 5, NAVBAR_WIDTH);
        // Swipe Left
        assertGestureTriggersAction(swipeUp, 100, 1, 5, 1);
        // Swipe Right
        assertGestureTriggersAction(swipeDown, 1, 1, 100, 5);
    }

    @Test
    public void testGesturesCallCorrectActionSeascape() throws Exception {
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getHeight();

        mController.setBarState(false /* isRTL */, NAV_BAR_LEFT);
        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // Swipe Up
        assertGestureTriggersAction(swipeLeft, 1, NAVBAR_WIDTH / 2, 5, 1);
        // Swipe Down
        assertGestureTriggersAction(swipeRight, 1, NAVBAR_WIDTH / 2, 5, NAVBAR_WIDTH);
        // Swipe Left
        assertGestureTriggersAction(swipeDown, 100, 1, 5, 1);
        // Swipe Right
        assertGestureTriggersAction(swipeUp, 1, 1, 100, 5);
    }

    @Test
    public void testGesturesCallCorrectActionRTL() throws Exception {
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getHeight();
        mController.setBarState(true /* isRTL */, NAV_BAR_BOTTOM);

        // The swipe gestures below are for LTR, so RTL in portrait will be swapped
        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // Swipe Up in RTL
        assertGestureTriggersAction(swipeUp, 1, 100, 5, 1);
        // Swipe Down in RTL
        assertGestureTriggersAction(swipeDown, 1, 1, 5, 100);
        // Swipe Left in RTL
        assertGestureTriggersAction(swipeRight, NAVBAR_WIDTH / 2, 1, 5, 1);
        // Swipe Right in RTL
        assertGestureTriggersAction(swipeLeft, NAVBAR_WIDTH / 2, 1, NAVBAR_WIDTH, 0);
    }

    @Test
    public void testGesturesCallCorrectActionLandscapeRTL() throws Exception {
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getHeight();
        mController.setBarState(true /* isRTL */, NAV_BAR_RIGHT);

        // The swipe gestures below are for LTR, so RTL in landscape will be swapped
        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // Swipe Up
        assertGestureTriggersAction(swipeLeft, 1, NAVBAR_WIDTH / 2, 5, 1);
        // Swipe Down
        assertGestureTriggersAction(swipeRight, 1, NAVBAR_WIDTH / 2, 5, NAVBAR_WIDTH);
        // Swipe Left
        assertGestureTriggersAction(swipeUp, 100, 1, 5, 1);
        // Swipe Right
        assertGestureTriggersAction(swipeDown, 1, 1, 100, 5);
    }

    @Test
    public void testGesturesCallCorrectActionSeascapeRTL() throws Exception {
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getHeight();
        mController.setBarState(true /* isRTL */, NAV_BAR_LEFT);

        // The swipe gestures below are for LTR, so RTL in seascape will be swapped
        NavigationGestureAction swipeUp = mockAction(true);
        NavigationGestureAction swipeDown = mockAction(true);
        NavigationGestureAction swipeLeft = mockAction(true);
        NavigationGestureAction swipeRight = mockAction(true);
        mController.setGestureActions(swipeUp, swipeDown, swipeLeft, swipeRight,
                null /* leftEdgeSwipe */, null /* rightEdgeSwipe */);

        // Swipe Up
        assertGestureTriggersAction(swipeRight, 1, NAVBAR_WIDTH / 2, 5, 1);
        // Swipe Down
        assertGestureTriggersAction(swipeLeft, 1, NAVBAR_WIDTH / 2, 5, NAVBAR_WIDTH);
        // Swipe Left
        assertGestureTriggersAction(swipeDown, 100, 1, 5, 1);
        // Swipe Right
        assertGestureTriggersAction(swipeUp, 1, 1, 100, 5);
    }

    @Test
    public void testActionPreventByPinnedState() throws Exception {
        // Screen is pinned
        doReturn(true).when(mNavigationBarView).inScreenPinning();

        // Add enabled gesture action
        NavigationGestureAction action = mockAction(true);
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Touch down to begin swipe
        MotionEvent downEvent = event(MotionEvent.ACTION_DOWN, 1, 100);
        assertFalse(touch(downEvent));
        verify(mProxy, never()).onPreMotionEvent(mNavigationBarView.getDownHitTarget());
        verify(mProxy, never()).onMotionEvent(downEvent);

        // Move to start gesture, but pinned so it should not trigger action
        MotionEvent moveEvent = event(MotionEvent.ACTION_MOVE, 1, 1);
        assertFalse(touch(moveEvent));
        assertNull(mController.getCurrentAction());
        verify(mNavigationBarView, times(1)).showPinningEscapeToast();
        verify(action, never()).onGestureStart(moveEvent);
    }

    @Test
    public void testActionPreventedNotificationsShown() throws Exception {
        NavigationGestureAction action = mockAction(true);
        doReturn(false).when(action).canRunWhenNotificationsShowing();
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Show the notifications
        doReturn(false).when(mNavigationBarView).isNotificationsFullyCollapsed();

        // Swipe up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1, 100));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 1, 1));
        assertNull(mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));

        // Hide the notifications
        doReturn(true).when(mNavigationBarView).isNotificationsFullyCollapsed();

        // Swipe up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1, 100));
        assertTrue(touch(MotionEvent.ACTION_MOVE, 1, 1));
        assertEquals(action, mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));
    }

    @Test
    public void testActionCannotPerform() throws Exception {
        NavigationGestureAction action = mockAction(true);
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Cannot perform action
        doReturn(false).when(action).canPerformAction();

        // Swipe up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1, 100));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 1, 1));
        assertNull(mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));

        // Cannot perform action
        doReturn(true).when(action).canPerformAction();

        // Swipe up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1, 100));
        assertTrue(touch(MotionEvent.ACTION_MOVE, 1, 1));
        assertEquals(action, mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));
    }

    @Test
    public void testQuickScrub() throws Exception {
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getHeight();
        QuickScrubAction action = spy(new QuickScrubAction(mNavigationBarView, mProxyService));
        mController.setGestureActions(null /* swipeUpAction */, null /* swipeDownAction */,
                null /* swipeLeftAction */, action, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);
        int x = NAVBAR_WIDTH / 2;
        int y = 20;

        // Set the layout and other padding to make sure the scrub fraction is calculated correctly
        action.onLayout(true, 0, 0, NAVBAR_WIDTH, NAVBAR_HEIGHT);
        doReturn(0).when(mNavigationBarView).getPaddingLeft();
        doReturn(0).when(mNavigationBarView).getPaddingRight();
        doReturn(0).when(mNavigationBarView).getPaddingStart();
        doReturn(0).when(mResources)
                .getDimensionPixelSize(R.dimen.nav_quick_scrub_track_edge_padding);

        // Quickscrub disabled, so the action should be disabled
        doReturn(false).when(mNavigationBarView).isQuickScrubEnabled();
        assertFalse(action.isEnabled());
        doReturn(true).when(mNavigationBarView).isQuickScrubEnabled();

        // Touch down
        MotionEvent downEvent = event(MotionEvent.ACTION_DOWN, x, y);
        assertFalse(touch(downEvent));
        assertNull(mController.getCurrentAction());
        verify(mProxy, times(1)).onPreMotionEvent(mNavigationBarView.getDownHitTarget());
        verify(mProxy, times(1)).onMotionEvent(downEvent);

        // Move to start trigger action from gesture
        MotionEvent moveEvent1 = event(MotionEvent.ACTION_MOVE, x + 100, y);
        assertTrue(touch(moveEvent1));
        assertEquals(action, mController.getCurrentAction());
        verify(action, times(1)).onGestureStart(moveEvent1);
        verify(mProxy, times(1)).onQuickScrubStart();
        verify(mProxyService, times(1)).notifyQuickScrubStarted();
        verify(mNavigationBarView, times(1)).updateSlippery();
        verify(mProxy, never()).onMotionEvent(moveEvent1);

        // Move again for scrub
        float fraction = 3f / 4;
        x = (int) (NAVBAR_WIDTH * fraction);
        MotionEvent moveEvent2 = event(MotionEvent.ACTION_MOVE, x, y);
        assertTrue(touch(moveEvent2));
        assertEquals(action, mController.getCurrentAction());
        verify(action, times(1)).onGestureMove(x, y);
        verify(mProxy, times(1)).onQuickScrubProgress(fraction);
        verify(mProxy, never()).onMotionEvent(moveEvent2);

        // Action up
        MotionEvent upEvent = event(MotionEvent.ACTION_UP, 1, y);
        assertFalse(touch(upEvent));
        assertNull(mController.getCurrentAction());
        verify(action, times(1)).onGestureEnd();
        verify(mProxy, times(1)).onQuickScrubEnd();
        verify(mProxy, never()).onMotionEvent(upEvent);
    }

    @Test
    public void testQuickStep() throws Exception {
        QuickStepAction action = new QuickStepAction(mNavigationBarView, mProxyService);
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Notifications are up, should prevent quickstep
        doReturn(false).when(mNavigationBarView).isNotificationsFullyCollapsed();

        // Swipe up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1, 100));
        assertNull(mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_MOVE, 1, 1));
        assertNull(mController.getCurrentAction());
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));
        doReturn(true).when(mNavigationBarView).isNotificationsFullyCollapsed();

        // Quickstep disabled, so the action should be disabled
        doReturn(false).when(mNavigationBarView).isQuickStepSwipeUpEnabled();
        assertFalse(action.isEnabled());
        doReturn(true).when(mNavigationBarView).isQuickStepSwipeUpEnabled();

        // Swipe up should call proxy events
        MotionEvent downEvent = event(MotionEvent.ACTION_DOWN, 1, 100);
        assertFalse(touch(downEvent));
        assertNull(mController.getCurrentAction());
        verify(mProxy, times(1)).onPreMotionEvent(mNavigationBarView.getDownHitTarget());
        verify(mProxy, times(1)).onMotionEvent(downEvent);

        MotionEvent moveEvent = event(MotionEvent.ACTION_MOVE, 1, 1);
        assertTrue(touch(moveEvent));
        assertEquals(action, mController.getCurrentAction());
        verify(mProxy, times(1)).onQuickStep(moveEvent);
        verify(mProxyService, times(1)).notifyQuickStepStarted();
    }

    @Test
    public void testLongPressPreventDetection() throws Exception {
        NavigationGestureAction action = mockAction(true);
        mController.setGestureActions(action, null /* swipeDownAction */,
                null /* swipeLeftAction */, null /* swipeRightAction */, null /* leftEdgeSwipe */,
                null /* rightEdgeSwipe */);

        // Start the drag up
        assertFalse(touch(MotionEvent.ACTION_DOWN, 100, 1));
        assertNull(mController.getCurrentAction());

        // Long press something on the navigation bar such as Home button
        mNavigationBarView.onNavigationButtonLongPress(mock(View.class));

        // Swipe right will not start any gestures
        MotionEvent motionMoveEvent = event(MotionEvent.ACTION_MOVE, 1, 1);
        assertFalse(touch(motionMoveEvent));
        assertNull(mController.getCurrentAction());
        verify(action, never()).startGesture(motionMoveEvent);

        // Touch up
        assertFalse(touch(MotionEvent.ACTION_UP, 1, 1));
        verify(action, never()).endGesture();
    }

    @Test
    public void testHitTargetDragged() throws Exception {
        ButtonDispatcher button = mock(ButtonDispatcher.class);
        FakeLocationView buttonView = spy(new FakeLocationView(mContext, NAVBAR_WIDTH / 2,
                NAVBAR_HEIGHT / 2));
        doReturn(buttonView).when(button).getCurrentView();

        NavigationGestureAction action = mockAction(true);
        mController.setGestureActions(action, action, action, action, action, action);

        // Setup getting the hit target
        doReturn(HIT_TARGET_HOME).when(action).requiresTouchDownHitTarget();
        doReturn(true).when(action).allowHitTargetToMoveOverDrag();
        doReturn(HIT_TARGET_HOME).when(mNavigationBarView).getDownHitTarget();
        doReturn(button).when(mNavigationBarView).getHomeButton();
        doReturn(NAVBAR_WIDTH).when(mNavigationBarView).getWidth();
        doReturn(NAVBAR_HEIGHT).when(mNavigationBarView).getHeight();

        // Portrait
        assertGestureDragsHitTargetAllDirections(buttonView, false /* isRTL */, NAV_BAR_BOTTOM);

        // Portrait RTL
        assertGestureDragsHitTargetAllDirections(buttonView, true /* isRTL */, NAV_BAR_BOTTOM);

        // Landscape
        assertGestureDragsHitTargetAllDirections(buttonView, false /* isRTL */, NAV_BAR_RIGHT);

        // Landscape RTL
        assertGestureDragsHitTargetAllDirections(buttonView, true /* isRTL */, NAV_BAR_RIGHT);

        // Seascape
        assertGestureDragsHitTargetAllDirections(buttonView, false /* isRTL */, NAV_BAR_LEFT);

        // Seascape RTL
        assertGestureDragsHitTargetAllDirections(buttonView, true /* isRTL */, NAV_BAR_LEFT);
    }

    private void assertGestureDragsHitTargetAllDirections(View buttonView, boolean isRTL,
            int navPos) {
        mController.setBarState(isRTL, navPos);

        // Swipe up
        assertGestureDragsHitTarget(buttonView, 10 /* x1 */, 200 /* y1 */, 0 /* x2 */, 0 /* y2 */,
                0 /* dx */, -1 /* dy */);
        // Swipe left
        assertGestureDragsHitTarget(buttonView, 200 /* x1 */, 10 /* y1 */, 0 /* x2 */, 0 /* y2 */,
                -1 /* dx */, 0 /* dy */);
        // Swipe right
        assertGestureDragsHitTarget(buttonView, 0 /* x1 */, 0 /* y1 */, 200 /* x2 */, 10 /* y2 */,
                1 /* dx */, 0 /* dy */);
        // Swipe down
        assertGestureDragsHitTarget(buttonView, 0 /* x1 */, 0 /* y1 */, 10 /* x2 */, 200 /* y2 */,
                0 /* dx */, 1 /* dy */);
    }

    /**
     * Asserts the gesture actually moves the hit target
     * @param buttonView button to check if moved, use Mockito.spy on a real object
     * @param x1 start x
     * @param x2 start y
     * @param y1 end x
     * @param y2 end y
     * @param dx diff in x, if not 0, its sign determines direction, value does not matter
     * @param dy diff in y, if not 0, its sign determines direction, value does not matter
     */
    private void assertGestureDragsHitTarget(View buttonView, int x1, int y1, int x2, int y2,
            int dx, int dy) {
        ArgumentCaptor<Float> captor = ArgumentCaptor.forClass(Float.class);
        assertFalse(touch(MotionEvent.ACTION_DOWN, x1, y1));
        assertTrue(touch(MotionEvent.ACTION_MOVE, x2, y2));

        // Verify positions of the button drag
        if (dx == 0) {
            verify(buttonView, never()).setTranslationX(anyFloat());
        } else {
            verify(buttonView).setTranslationX(captor.capture());
            if (dx < 0) {
                assertTrue("Button should have moved left", (float) captor.getValue() < 0);
            } else {
                assertTrue("Button should have moved right", (float) captor.getValue() > 0);
            }
        }
        if (dy == 0) {
            verify(buttonView, never()).setTranslationY(anyFloat());
        } else {
            verify(buttonView).setTranslationY(captor.capture());
            if (dy < 0) {
                assertTrue("Button should have moved up", (float) captor.getValue() < 0);
            } else {
                assertTrue("Button should have moved down", (float) captor.getValue() > 0);
            }
        }

        // Touch up
        assertFalse(touch(MotionEvent.ACTION_UP, x2, y2));
        verify(buttonView, times(1)).animate();

        // Reset button state
        reset(buttonView);
    }

    private MotionEvent event(int action, float x, float y) {
        final MotionEvent event = mock(MotionEvent.class);
        doReturn(x).when(event).getX();
        doReturn(y).when(event).getY();
        doReturn(action & MotionEvent.ACTION_MASK).when(event).getActionMasked();
        doReturn(action).when(event).getAction();
        return event;
    }

    private boolean touch(int action, float x, float y) {
        return touch(event(action, x, y));
    }

    private boolean touch(MotionEvent event) {
        return mController.onInterceptTouchEvent(event);
    }

    private NavigationGestureAction mockAction(boolean enabled) {
        final NavigationGestureAction action = mock(NavigationGestureAction.class);
        doReturn(enabled).when(action).isEnabled();
        doReturn(HIT_TARGET_NONE).when(action).requiresTouchDownHitTarget();
        doReturn(true).when(action).canPerformAction();
        return action;
    }

    private void assertGestureTriggersAction(NavigationGestureAction action, int x1, int y1,
            int x2, int y2) {
        // Start the drag
        assertFalse(touch(MotionEvent.ACTION_DOWN, x1, y1));
        assertNull(mController.getCurrentAction());

        // Swipe
        MotionEvent motionMoveEvent = event(MotionEvent.ACTION_MOVE, x2, y2);
        assertTrue(touch(motionMoveEvent));
        assertEquals(action, mController.getCurrentAction());
        verify(action, times(1)).startGesture(motionMoveEvent);

        // Move again
        assertTrue(touch(MotionEvent.ACTION_MOVE, x2, y2));
        verify(action, times(1)).onGestureMove(x2, y2);

        // Touch up
        assertFalse(touch(MotionEvent.ACTION_UP, x2, y2));
        assertNull(mController.getCurrentAction());
        verify(action, times(1)).endGesture();
    }

    static class FakeLocationView extends View {
        private final int mX;
        private final int mY;

        public FakeLocationView(Context context, int x, int y) {
            super(context);
            mX = x;
            mY = y;
        }

        @Override
        public void getLocationInWindow(int[] outLocation) {
            outLocation[0] = mX;
            outLocation[1] = mY;
        }
    }
}
