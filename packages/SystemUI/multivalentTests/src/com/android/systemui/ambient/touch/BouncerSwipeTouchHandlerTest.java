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

package com.android.systemui.ambient.touch;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.ambient.touch.scrim.ScrimController;
import com.android.systemui.ambient.touch.scrim.ScrimManager;
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants;
import com.android.systemui.settings.FakeUserTracker;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BouncerSwipeTouchHandlerTest extends SysuiTestCase {
    @Mock
    CentralSurfaces mCentralSurfaces;

    @Mock
    ScrimManager mScrimManager;

    @Mock
    ScrimController mScrimController;

    @Mock
    NotificationShadeWindowController mNotificationShadeWindowController;

    @Mock
    FlingAnimationUtils mFlingAnimationUtils;

    @Mock
    FlingAnimationUtils mFlingAnimationUtilsClosing;

    @Mock
    TouchHandler.TouchSession mTouchSession;

    BouncerSwipeTouchHandler mTouchHandler;

    @Mock
    BouncerSwipeTouchHandler.ValueAnimatorCreator mValueAnimatorCreator;

    @Mock
    ValueAnimator mValueAnimator;

    @Mock
    BouncerSwipeTouchHandler.VelocityTrackerFactory mVelocityTrackerFactory;

    @Mock
    VelocityTracker mVelocityTracker;

    @Mock
    UiEventLogger mUiEventLogger;

    @Mock
    LockPatternUtils mLockPatternUtils;

    @Mock
    Region mRegion;

    @Captor
    ArgumentCaptor<Rect> mRectCaptor;

    FakeUserTracker mUserTracker;

    private static final float TOUCH_REGION = .3f;
    private static final int SCREEN_WIDTH_PX = 1024;
    private static final int SCREEN_HEIGHT_PX = 100;
    private static final float MIN_BOUNCER_HEIGHT = .05f;

    private static final Rect SCREEN_BOUNDS = new Rect(0, 0, 1024, 100);
    private static final UserInfo CURRENT_USER_INFO = new UserInfo(
            10,
            /* name= */ "user10",
            /* flags= */ 0
    );

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mUserTracker = new FakeUserTracker();
        mTouchHandler = new BouncerSwipeTouchHandler(
                mScrimManager,
                Optional.of(mCentralSurfaces),
                mNotificationShadeWindowController,
                mValueAnimatorCreator,
                mVelocityTrackerFactory,
                mLockPatternUtils,
                mUserTracker,
                mFlingAnimationUtils,
                mFlingAnimationUtilsClosing,
                TOUCH_REGION,
                MIN_BOUNCER_HEIGHT,
                mUiEventLogger);

        when(mScrimManager.getCurrentController()).thenReturn(mScrimController);
        when(mValueAnimatorCreator.create(anyFloat(), anyFloat())).thenReturn(mValueAnimator);
        when(mVelocityTrackerFactory.obtain()).thenReturn(mVelocityTracker);
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn(Float.MAX_VALUE);
        when(mTouchSession.getBounds()).thenReturn(SCREEN_BOUNDS);
        when(mLockPatternUtils.isSecure(CURRENT_USER_INFO.id)).thenReturn(true);

        mUserTracker.set(Collections.singletonList(CURRENT_USER_INFO), 0);
    }

    /**
     * Ensures expansion only happens when touch down happens in valid part of the screen.
     */
    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
    public void testSessionStart() {
        mTouchHandler.getTouchInitiationRegion(SCREEN_BOUNDS, mRegion, null);

        verify(mRegion).union(mRectCaptor.capture());
        final Rect bounds = mRectCaptor.getValue();

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
     * Ensures expansion only happens when touch down happens in valid part of the screen.
     */
    @Test
    @EnableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
    public void testSessionStart_doesNotModifyNotificationShadeWindow() {
        mTouchHandler.getTouchInitiationRegion(SCREEN_BOUNDS, mRegion, null);

        verify(mRegion).union(mRectCaptor.capture());
        final Rect bounds = mRectCaptor.getValue();

        final Rect expected = new Rect();

        expected.set(0, Math.round(SCREEN_HEIGHT_PX * (1 - TOUCH_REGION)), SCREEN_WIDTH_PX,
                SCREEN_HEIGHT_PX);

        assertThat(bounds).isEqualTo(expected);

        mTouchHandler.onSessionStart(mTouchSession);
        verifyNoMoreInteractions(mNotificationShadeWindowController);
    }

    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
    public void testSwipeUp_whenBouncerInitiallyShowing_reduceHeightWithExclusionRects() {
        mTouchHandler.getTouchInitiationRegion(SCREEN_BOUNDS, mRegion,
                new Rect(0, 0, SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX));
        verify(mRegion).union(mRectCaptor.capture());
        final Rect bounds = mRectCaptor.getValue();

        final Rect expected = new Rect();
        final float minBouncerHeight =
                SCREEN_HEIGHT_PX * MIN_BOUNCER_HEIGHT;
        final int minAllowableBottom = SCREEN_HEIGHT_PX - Math.round(minBouncerHeight);

        expected.set(0, minAllowableBottom, SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX);

        assertThat(bounds).isEqualTo(expected);

        onSessionStartHelper(mTouchHandler, mTouchSession, mNotificationShadeWindowController);
    }

    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
    public void testSwipeUp_exclusionRectAtTop_doesNotIntersectGestureArea() {
        mTouchHandler.getTouchInitiationRegion(SCREEN_BOUNDS, mRegion,
                new Rect(0, 0, SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX / 4));
        verify(mRegion).union(mRectCaptor.capture());
        final Rect bounds = mRectCaptor.getValue();

        final Rect expected = new Rect();
        final int gestureAreaTop = SCREEN_HEIGHT_PX - Math.round(SCREEN_HEIGHT_PX * TOUCH_REGION);
        expected.set(0, gestureAreaTop, SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX);

        assertThat(bounds).isEqualTo(expected);
        onSessionStartHelper(mTouchHandler, mTouchSession, mNotificationShadeWindowController);
    }

    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_BOUNCER_DO_NOT_MODIFY_PLUGIN_OPEN)
    public void testSwipeUp_exclusionRectBetweenNormalAndMinimumSwipeArea() {
        final int normalSwipeAreaTop = SCREEN_HEIGHT_PX
                - Math.round(SCREEN_HEIGHT_PX * TOUCH_REGION);
        final int minimumSwipeAreaTop = SCREEN_HEIGHT_PX
                - Math.round(SCREEN_HEIGHT_PX * MIN_BOUNCER_HEIGHT);

        Rect exclusionRect = new Rect(0, 0, SCREEN_WIDTH_PX,
                (normalSwipeAreaTop + minimumSwipeAreaTop) / 2);

        mTouchHandler.getTouchInitiationRegion(SCREEN_BOUNDS, mRegion, exclusionRect);

        verify(mRegion).union(mRectCaptor.capture());

        final Rect bounds = mRectCaptor.getValue();
        final Rect expected = new Rect();

        final int expectedSwipeAreaBottom = exclusionRect.bottom;
        expected.set(0, expectedSwipeAreaBottom, SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX);

        assertThat(bounds).isEqualTo(expected);

        onSessionStartHelper(mTouchHandler, mTouchSession, mNotificationShadeWindowController);
    }

    private static void onSessionStartHelper(BouncerSwipeTouchHandler touchHandler,
            TouchHandler.TouchSession touchSession,
            NotificationShadeWindowController notificationShadeWindowController) {
        touchHandler.onSessionStart(touchSession);
        verify(notificationShadeWindowController).setForcePluginOpen(eq(true), any());
        ArgumentCaptor<InputChannelCompat.InputEventListener> eventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(touchSession).registerGestureListener(gestureListenerCaptor.capture());
        verify(touchSession).registerInputListener(eventListenerCaptor.capture());

        // A touch within range at the bottom of the screen should trigger listening
        assertThat(gestureListenerCaptor.getValue()
                .onScroll(Mockito.mock(MotionEvent.class),
                        Mockito.mock(MotionEvent.class),
                        1,
                        2)).isTrue();
    }

    /**
     * Makes sure swiping down doesn't change the expansion amount.
     */
    @Test
    @DisableFlags(Flags.FLAG_DREAM_OVERLAY_BOUNCER_SWIPE_DIRECTION_FILTERING)
    public void testSwipeDown_doesNotSetExpansion() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();

        final float percent = .15f;
        final float distanceY = SCREEN_HEIGHT_PX * percent;

        // Swiping down near the bottom of the screen where the touch initiation region is.
        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);

        assertThat(gestureListener.onScroll(event1, event2, 0, -distanceY)).isTrue();

        verify(mScrimController, never()).expand(any());
    }

    /**
     * Makes sure swiping down when bouncer initially hidden doesn't change the expansion amount.
     */
    @Test
    @EnableFlags(Flags.FLAG_DREAM_OVERLAY_BOUNCER_SWIPE_DIRECTION_FILTERING)
    public void testSwipeDown_whenBouncerInitiallyHidden_doesNotSetExpansion_directionFiltering() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();

        final float percent = .15f;
        final float distanceY = SCREEN_HEIGHT_PX * percent;

        // Swiping down near the bottom of the screen where the touch initiation region is.
        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);

        assertThat(gestureListener.onScroll(event1, event2, 0, -distanceY)).isFalse();

        verify(mScrimController, never()).expand(any());
    }

    /**
     * Makes sure the expansion amount is proportional to (1 - scroll).
     */
    @Test
    public void testSwipeUp_setsCorrectExpansionAmount() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();

        verifyScroll(.3f, gestureListener);
        verifyScroll(.7f, gestureListener);
    }

    /**
     * Verifies that swiping up when the lock pattern is not secure dismissed dream and consumes
     * the gesture.
     */
    @Test
    public void testSwipeUp_keyguardNotSecure_doesNotExpand() {
        when(mLockPatternUtils.isSecure(CURRENT_USER_INFO.id)).thenReturn(false);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();

        final float distanceY = SCREEN_HEIGHT_PX * 0.3f;
        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);

        reset(mScrimController);

        // Scroll gesture is consumed.
        assertThat(gestureListener.onScroll(event1, event2, 0, distanceY))
                .isTrue();
        // We should not expand since the keyguard is not secure
        verify(mScrimController, never()).expand(any());
        // Since we are swiping up, we should wake from dreams.
        verify(mCentralSurfaces).awakenDreams();
    }

    /**
     * Verifies that swiping down when the lock pattern is not secure does not dismiss the dream.
     */
    @Test
    public void testSwipeDown_keyguardNotSecure_doesNotExpand() {
        when(mLockPatternUtils.isSecure(CURRENT_USER_INFO.id)).thenReturn(false);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());

        final OnGestureListener gestureListener = gestureListenerCaptor.getValue();

        final float distanceY = SCREEN_HEIGHT_PX * 0.3f;
        // Swiping down near the bottom of the screen where the touch initiation region is.
        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);

        reset(mScrimController);

        // Scroll gesture is not consumed.
        assertThat(gestureListener.onScroll(event1, event2, 0, distanceY))
                .isTrue();
        // We should not expand since the keyguard is not secure
        verify(mScrimController, never()).expand(any());
        // Since we are swiping down, we should not dismiss the dream.
        verify(mCentralSurfaces, never()).awakenDreams();
    }

    private void verifyScroll(float percent,
            OnGestureListener gestureListener) {
        final float distanceY = SCREEN_HEIGHT_PX * percent;

        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);

        reset(mScrimController);
        assertThat(gestureListener.onScroll(event1, event2, 0,
                distanceY))
                .isTrue();

        // Ensure only called once
        verify(mScrimController).expand(any());

        final float expansion = 1 - percent;

        // Ensure correct expansion passed in.
        ShadeExpansionChangeEvent event =
                new ShadeExpansionChangeEvent(
                        expansion, /* expanded= */ false, /* tracking= */ true);
        verify(mScrimController).expand(event);
    }

    /**
     * Tests that ending an upward swipe before the set threshold leads to bouncer collapsing down.
     */
    @Test
    public void testSwipeUpPositionBelowThreshold_collapsesBouncer() {
        final float swipeUpPercentage = .3f;
        final float expansion = 1 - swipeUpPercentage;
        // The upward velocity is ignored.
        final float velocityY = -1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion),
                eq(KeyguardBouncerConstants.EXPANSION_HIDDEN));
        verify(mValueAnimator, never()).addListener(any());

        verify(mFlingAnimationUtilsClosing).apply(eq(mValueAnimator),
                eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncerConstants.EXPANSION_HIDDEN),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
        verify(mUiEventLogger, never()).log(any());
    }

    /**
     * Tests that ending an upward swipe above the set threshold will continue the expansion.
     */
    @Test
    public void testSwipeUpPositionAboveThreshold_expandsBouncer() {
        final float swipeUpPercentage = .7f;
        final float expansion = 1 - swipeUpPercentage;
        // The downward velocity is ignored.
        final float velocityY = 1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion),
                eq(KeyguardBouncerConstants.EXPANSION_VISIBLE));

        ArgumentCaptor<AnimatorListenerAdapter> endAnimationListenerCaptor =
                ArgumentCaptor.forClass(AnimatorListenerAdapter.class);
        verify(mValueAnimator).addListener(endAnimationListenerCaptor.capture());
        AnimatorListenerAdapter endAnimationListener = endAnimationListenerCaptor.getValue();

        verify(mFlingAnimationUtils).apply(eq(mValueAnimator), eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncerConstants.EXPANSION_VISIBLE),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
        verify(mUiEventLogger).log(BouncerSwipeTouchHandler.DreamEvent.DREAM_SWIPED);

        endAnimationListener.onAnimationEnd(mValueAnimator);
        verify(mUiEventLogger).log(BouncerSwipeTouchHandler.DreamEvent.DREAM_BOUNCER_FULLY_VISIBLE);
    }

    /**
     * Tests that swiping up with a speed above the set threshold will continue the expansion.
     */
    @Test
    public void testSwipeUpVelocityAboveMin_expandsBouncer() {
        when(mFlingAnimationUtils.getMinVelocityPxPerSecond()).thenReturn((float) 0);

        // The ending position below the set threshold is ignored.
        final float swipeUpPercentage = .3f;
        final float expansion = 1 - swipeUpPercentage;
        final float velocityY = -1;
        swipeToPosition(swipeUpPercentage, velocityY);

        verify(mValueAnimatorCreator).create(eq(expansion),
                eq(KeyguardBouncerConstants.EXPANSION_VISIBLE));

        ArgumentCaptor<AnimatorListenerAdapter> endAnimationListenerCaptor =
                ArgumentCaptor.forClass(AnimatorListenerAdapter.class);
        verify(mValueAnimator).addListener(endAnimationListenerCaptor.capture());
        AnimatorListenerAdapter endAnimationListener = endAnimationListenerCaptor.getValue();

        verify(mFlingAnimationUtils).apply(eq(mValueAnimator), eq(SCREEN_HEIGHT_PX * expansion),
                eq(SCREEN_HEIGHT_PX * KeyguardBouncerConstants.EXPANSION_VISIBLE),
                eq(velocityY), eq((float) SCREEN_HEIGHT_PX));
        verify(mValueAnimator).start();
        verify(mUiEventLogger).log(BouncerSwipeTouchHandler.DreamEvent.DREAM_SWIPED);

        endAnimationListener.onAnimationEnd(mValueAnimator);
        verify(mUiEventLogger).log(BouncerSwipeTouchHandler.DreamEvent.DREAM_BOUNCER_FULLY_VISIBLE);
    }

    @Test
    public void testTouchSessionOnRemovedCalledTwice() {
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<TouchHandler.TouchSession.Callback> onRemovedCallbackCaptor =
                ArgumentCaptor.forClass(TouchHandler.TouchSession.Callback.class);
        verify(mTouchSession).registerCallback(onRemovedCallbackCaptor.capture());
        onRemovedCallbackCaptor.getValue().onRemoved();
        onRemovedCallbackCaptor.getValue().onRemoved();
    }

    private void swipeToPosition(float percent, float velocityY) {
        Mockito.clearInvocations(mTouchSession);
        mTouchHandler.onSessionStart(mTouchSession);
        ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);
        verify(mTouchSession).registerGestureListener(gestureListenerCaptor.capture());
        ArgumentCaptor<InputChannelCompat.InputEventListener> inputEventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mTouchSession).registerInputListener(inputEventListenerCaptor.capture());

        when(mVelocityTracker.getYVelocity()).thenReturn(velocityY);

        final float distanceY = SCREEN_HEIGHT_PX * percent;

        final MotionEvent event1 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX, 0);
        final MotionEvent event2 = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                0, SCREEN_HEIGHT_PX - distanceY, 0);

        assertThat(gestureListenerCaptor.getValue().onScroll(event1, event2, 0,
                distanceY))
                .isTrue();

        final MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP,
                0, 0, 0);

        inputEventListenerCaptor.getValue().onInputEvent(upEvent);
    }
}
