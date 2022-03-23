/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.Region;
import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BouncerSwipeTouchHandlerTest extends SysuiTestCase {
    @Mock
    StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Mock
    CentralSurfaces mCentralSurfaces;

    @Mock
    NotificationShadeWindowController mNotificationShadeWindowController;

    @Mock
    FlingAnimationUtils mFlingAnimationUtils;


    @Mock
    FlingAnimationUtils mFlingAnimationUtilsClosing;

    @Mock
    DreamTouchHandler.TouchSession mTouchSession;

    BouncerSwipeTouchHandler mTouchHandler;

    @Mock
    BouncerSwipeTouchHandler.ValueAnimatorCreator mValueAnimatorCreator;

    @Mock
    ValueAnimator mValueAnimator;

    @Mock
    BouncerSwipeTouchHandler.VelocityTrackerFactory mVelocityTrackerFactory;

    @Mock
    VelocityTracker mVelocityTracker;

    final DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    private static final float TOUCH_REGION = .3f;
    private static final int SCREEN_WIDTH_PX = 1024;
    private static final int SCREEN_HEIGHT_PX = 100;

    @Before
    public void setup() {
        mDisplayMetrics.widthPixels = SCREEN_WIDTH_PX;
        mDisplayMetrics.heightPixels = SCREEN_HEIGHT_PX;

        MockitoAnnotations.initMocks(this);
        mTouchHandler = new BouncerSwipeTouchHandler(
                mDisplayMetrics,
                mStatusBarKeyguardViewManager,
                mCentralSurfaces,
                mNotificationShadeWindowController,
                mValueAnimatorCreator,
                mVelocityTrackerFactory,
                mFlingAnimationUtils,
                mFlingAnimationUtilsClosing,
                TOUCH_REGION);

        when(mCentralSurfaces.isBouncerShowing()).thenReturn(false);
        when(mCentralSurfaces.getDisplayHeight()).thenReturn((float) SCREEN_HEIGHT_PX);
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(false);
        when(mValueAnimatorCreator.create(anyFloat(), anyFloat())).thenReturn(mValueAnimator);
        when(mVelocityTrackerFactory.obtain()).thenReturn(mVelocityTracker);
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn(Float.MAX_VALUE);
    }

    /**
     * Ensures expansion only happens when touch down happens in valid part of the screen.
     */
    @Test
    public void testSessionStart() {
        final Region region = Region.obtain();
        mTouchHandler.getTouchInitiationRegion(region);

        final Rect bounds = region.getBounds();

        final Rect expected = new Rect();

        expected.set(0, Math.round(SCREEN_HEIGHT_PX * (1 - TOUCH_REGION)), SCREEN_WIDTH_PX,
                SCREEN_HEIGHT_PX);

        assertThat(bounds).isEqualTo(expected);

        mTouchHandler.onSessionStart(mTouchSession);
        verify(mNotificationShadeWindowController).setForcePluginOpen(eq(true), any());
        ArgumentCaptor<InputChannelCompat.InputEventListener> eventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());
        verify(mTouchSession).registerInputListener(eventListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isTrue();
    }

    /**
     * Makes sure expansion amount is proportional to scroll.
     */
    @Test
    public void testExpansionAmount_whenBouncerHidden_setsCorrectValue() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(false);
        verifyScroll(.3f, Direction.UP, true, gestureListener);

        // Ensure that subsequent gestures are treated as expanding even if the bouncer state
        // changes.
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(true);
        verifyScroll(.7f, Direction.UP, true, gestureListener);
    }

    /**
     * Makes sure collapse amount is proportional to scroll.
     */
    @Test
    public void testCollapseAmount() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(true);
        verifyScroll(.3f, Direction.DOWN, false, gestureListener);

        // Ensure that subsequent gestures are treated as collapsing even if the bouncer state
        // changes.
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(false);
        verifyScroll(.7f, Direction.DOWN, false, gestureListener);
    }

    private enum Direction {
        DOWN,
        UP,
    }

    private void verifyScroll(float percent, Direction direction, boolean expanding,
            android.view.GestureDetector.OnGestureListener gestureListener) {

        final float distanceY = SCREEN_HEIGHT_PX * percent;

        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, direction == Direction.UP ? SCREEN_HEIGHT_PX : 0, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, direction == Direction.UP ? SCREEN_HEIGHT_PX - distanceY : distanceY, 0);

        assertThat(gestureListener.onScroll(event1, event2, 0, distanceY))
                .isTrue();

        // Ensure correct expansion passed in.
        verify(mStatusBarKeyguardViewManager)
                .onPanelExpansionChanged(
                        eq(expanding ? 1 - percent : percent), eq(false), eq(true));
    }

    /**
     * Makes sure expansion amount is proportional to scroll.
     */
    @Test
    public void testExpansionAmount_whenBouncerShown_setsCorrectValue() {
        when(mCentralSurfaces.isBouncerShowing()).thenReturn(true);

        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final float scrollAmount = .3f;
        final float distanceY = SCREEN_HEIGHT_PX * scrollAmount;

        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);

        assertThat(gestureListenerCaptor.getValue().onScroll(event1, event2, 0, distanceY))
                .isTrue();

        // Ensure only called once
        verify(mStatusBarKeyguardViewManager)
                .onPanelExpansionChanged(anyFloat(), anyBoolean(), anyBoolean());

        // Ensure correct expansion passed in.
        verify(mStatusBarKeyguardViewManager)
                .onPanelExpansionChanged(eq(scrollAmount), eq(false), eq(true));
    }

    private void swipeToPosition(float position, float velocityY) {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        ArgumentCaptor<InputChannelCompat.InputEventListener> inputEventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());
        verify(mTouchSession).registerInputListener(inputEventListenerCaptor.capture());

        when(mVelocityTracker.getYVelocity()).thenReturn(velocityY);

        final float distanceY = SCREEN_HEIGHT_PX * position;

        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);

        assertThat(gestureListenerCaptor.getValue().onScroll(event1, event2, 0, distanceY))
                .isTrue();

        final MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP,
                0, 0, 0);

        inputEventListenerCaptor.getValue().onInputEvent(upEvent);
    }

    /**
     * Tests that ending a swipe before the set expansion threshold leads to bouncer collapsing
     * down.
     */
    @Test
    public void testSwipeBelowThreshold_collapsesBouncer() {
        final float swipeUpPercentage = .3f;
        final float expansion = 1 - swipeUpPercentage;
        // The upward velocity is ignored.
        final float velocityY = -1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion), eq(KeyguardBouncer.EXPANSION_HIDDEN));
        verify(mFlingAnimationUtilsClosing).apply(eq(mValueAnimator),
                eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncer.EXPANSION_HIDDEN),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
    }

    /**
     * Tests that ending a swipe above the set expansion threshold will continue the expansion.
     */
    @Test
    public void testSwipeAboveThreshold_expandsBouncer() {
        final float swipeUpPercentage = .7f;
        final float expansion = 1 - swipeUpPercentage;
        // The downward velocity is ignored.
        final float velocityY = 1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion), eq(KeyguardBouncer.EXPANSION_VISIBLE));
        verify(mFlingAnimationUtils).apply(eq(mValueAnimator), eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncer.EXPANSION_VISIBLE),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
    }

    /**
     * Tests that swiping down with a speed above the set threshold leads to bouncer collapsing
     * down.
     */
    @Test
    public void testSwipeDownVelocityAboveMin_collapsesBouncer() {
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn((float) 0);

        // The swipe amount above the set expansion threshold is ignored.
        final float swipeUpPercentage = .7f;
        final float expansion = 1 - swipeUpPercentage;
        final float velocityY = 1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion), eq(KeyguardBouncer.EXPANSION_HIDDEN));
        verify(mFlingAnimationUtilsClosing).apply(eq(mValueAnimator),
                eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncer.EXPANSION_HIDDEN),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
    }

    /**
     * Tests that swiping up with a speed above the set threshold will continue the expansion.
     */
    @Test
    public void testSwipeUpVelocityAboveMin_expandsBouncer() {
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn((float) 0);

        // The swipe amount below the set expansion threshold is ignored.
        final float swipeUpPercentage = .3f;
        final float expansion = 1 - swipeUpPercentage;
        final float velocityY = -1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion), eq(KeyguardBouncer.EXPANSION_VISIBLE));
        verify(mFlingAnimationUtils).apply(eq(mValueAnimator), eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncer.EXPANSION_VISIBLE),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
    }
}
