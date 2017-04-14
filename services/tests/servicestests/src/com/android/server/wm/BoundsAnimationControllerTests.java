/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.WindowManagerInternal.AppTransitionListener;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.server.wm.BoundsAnimationController.BoundsAnimator;

/**
 * Test class for {@link BoundsAnimationController} to ensure that it sends the right callbacks
 * depending on the various interactions.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.BoundsAnimationControllerTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BoundsAnimationControllerTests extends WindowTestsBase {

    /**
     * Mock value animator to simulate updates with.
     */
    private class MockValueAnimator extends ValueAnimator {

        private float mFraction;

        public MockValueAnimator getWithValue(float fraction) {
            mFraction = fraction;
            return this;
        }

        @Override
        public Object getAnimatedValue() {
            return mFraction;
        }
    }

    /**
     * Mock app transition to fire notifications to the bounds animator.
     */
    private class MockAppTransition extends AppTransition {

        private AppTransitionListener mListener;

        MockAppTransition(Context context) {
            super(context, null);
        }

        @Override
        void registerListenerLocked(AppTransitionListener listener) {
            mListener = listener;
        }

        public void notifyTransitionPending() {
            mListener.onAppTransitionPendingLocked();
        }

        public void notifyTransitionCancelled(int transit) {
            mListener.onAppTransitionCancelledLocked(transit);
        }

        public void notifyTransitionStarting(int transit) {
            mListener.onAppTransitionStartingLocked(transit, null, null, null, null);
        }

        public void notifyTransitionFinished() {
            mListener.onAppTransitionFinishedLocked(null);
        }
    }

    /**
     * A test animate bounds user to track callbacks from the bounds animation.
     */
    private class TestAnimateBoundsUser implements BoundsAnimationController.AnimateBoundsUser {

        boolean mMovedToFullscreen;
        boolean mAnimationStarted;
        boolean mAnimationStartedToFullscreen;
        boolean mAnimationEnded;
        boolean mUpdatedPictureInPictureModeWithBounds;
        boolean mBoundsUpdated;
        Rect mStackBounds;
        Rect mTaskBounds;

        boolean mRequestCancelAnimation = false;

        void reinitialize(Rect stackBounds, Rect taskBounds) {
            mMovedToFullscreen = false;
            mAnimationStarted = false;
            mAnimationStartedToFullscreen = false;
            mAnimationEnded = false;
            mUpdatedPictureInPictureModeWithBounds = false;
            mStackBounds = stackBounds;
            mTaskBounds = taskBounds;
            mBoundsUpdated = false;
            mRequestCancelAnimation = false;
        }

        @Override
        public void onAnimationStart(boolean toFullscreen) {
            mAnimationStarted = true;
            mAnimationStartedToFullscreen = toFullscreen;
        }

        @Override
        public void updatePictureInPictureMode(Rect targetStackBounds) {
            mUpdatedPictureInPictureModeWithBounds = true;
        }

        @Override
        public boolean setPinnedStackSize(Rect stackBounds, Rect taskBounds) {
            // TODO: Once we break the runs apart, we should fail() here if this is called outside
            //       of onAnimationStart() and onAnimationEnd()
            if (mRequestCancelAnimation) {
                return false;
            } else {
                mBoundsUpdated = true;
                mStackBounds = stackBounds;
                mTaskBounds = taskBounds;
                return true;
            }
        }

        @Override
        public void onAnimationEnd() {
            mAnimationEnded = true;
        }

        @Override
        public void moveToFullscreen() {
            mMovedToFullscreen = true;
        }
    }

    // Constants
    private static final boolean MOVE_TO_FULLSCREEN = true;

    // Some dummy bounds to represent fullscreen and floating bounds
    private static final Rect BOUNDS_FULL = new Rect(0, 0, 100, 100);
    private static final Rect BOUNDS_FLOATING = new Rect(80, 80, 95, 95);
    private static final Rect BOUNDS_ALT_FLOATING = new Rect(60, 60, 95, 95);

    // Some dummy duration
    private static final int DURATION = 100;

    // Common
    private MockAppTransition mAppTransition;
    private MockValueAnimator mAnimator;
    private TestAnimateBoundsUser mTarget;
    private BoundsAnimationController mController;

    // Temp
    private Rect mTmpRect = new Rect();

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Context context = InstrumentationRegistry.getTargetContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        mAppTransition = new MockAppTransition(context);
        mAnimator = new MockValueAnimator();
        mTarget = new TestAnimateBoundsUser();
        mController = new BoundsAnimationController(context, mAppTransition, handler);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingTransition() throws Exception {
        // Create and start the animation
        mTarget.reinitialize(BOUNDS_FULL, null);
        final BoundsAnimator boundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);

        // Assert that when we are started, and that we are not going to fullscreen
        assertTrue(mTarget.mAnimationStarted);
        assertFalse(mTarget.mAnimationStartedToFullscreen);
        // Ensure we are not triggering a PiP mode change
        assertFalse(mTarget.mUpdatedPictureInPictureModeWithBounds);
        // Ensure that the task stack bounds are already frozen to the larger source stack bounds
        assertEquals(BOUNDS_FULL, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));

        // Drive some animation updates, ensure that only the stack bounds change and the task
        // bounds are frozen to the original stack bounds (adjusted for the offset)
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(0.5f));
        assertNotEquals(BOUNDS_FULL, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(1f));
        assertNotEquals(BOUNDS_FULL, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));

        // Finish the animation, ensure that it reaches the final bounds with the given state
        boundsAnimator.end();
        assertTrue(mTarget.mAnimationEnded);
        assertEquals(BOUNDS_FLOATING, mTarget.mStackBounds);
        assertNull(mTarget.mTaskBounds);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenTransition() throws Exception {
        // Create and start the animation
        mTarget.reinitialize(BOUNDS_FULL, null);
        final BoundsAnimator boundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FLOATING,
                BOUNDS_FULL, DURATION, MOVE_TO_FULLSCREEN);

        // Assert that when we are started, and that we are going to fullscreen
        assertTrue(mTarget.mAnimationStarted);
        assertTrue(mTarget.mAnimationStartedToFullscreen);
        // Ensure that we update the PiP mode change with the new fullscreen bounds
        assertTrue(mTarget.mUpdatedPictureInPictureModeWithBounds);
        // Ensure that the task stack bounds are already frozen to the larger target stack bounds
        assertEquals(BOUNDS_FLOATING, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));

        // Drive some animation updates, ensure that only the stack bounds change and the task
        // bounds are frozen to the original stack bounds (adjusted for the offset)
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(0.5f));
        assertNotEquals(BOUNDS_FLOATING, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(1f));
        assertNotEquals(BOUNDS_FLOATING, mTarget.mStackBounds);
        assertEquals(BOUNDS_FULL, offsetToZero(mTarget.mTaskBounds));

        // Finish the animation, ensure that it reaches the final bounds with the given state
        boundsAnimator.end();
        assertTrue(mTarget.mAnimationEnded);
        assertEquals(BOUNDS_FULL, mTarget.mStackBounds);
        assertNull(mTarget.mTaskBounds);
    }

    @UiThreadTest
    @Test
    public void testInterruptAnimationFromUser() throws Exception {
        // Create and start the animation
        mTarget.reinitialize(BOUNDS_FULL, null);
        final BoundsAnimator boundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);

        // Cancel the animation on the next update from the user
        mTarget.mRequestCancelAnimation = true;
        mTarget.mBoundsUpdated = false;
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(0.5f));
        // Ensure that we got no more updates after returning false and the bounds are not updated
        // to the end value
        assertFalse(mTarget.mBoundsUpdated);
        assertNotEquals(BOUNDS_FLOATING, mTarget.mStackBounds);
        assertNotEquals(BOUNDS_FLOATING, mTarget.mTaskBounds);
        // Ensure that we received the animation end call
        assertTrue(mTarget.mAnimationEnded);
    }

    @UiThreadTest
    @Test
    public void testCancelAnimationFromNewAnimationToExistingBounds() throws Exception {
        // Create and start the animation
        mTarget.reinitialize(BOUNDS_FULL, null);
        final BoundsAnimator boundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);

        // Drive some animation updates
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(0.5f));

        // Cancel the animation as a restart to the same bounds
        mTarget.reinitialize(null, null);
        final BoundsAnimator altBoundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);
        // Ensure the animator is the same
        assertSame(boundsAnimator, altBoundsAnimator);
        // Ensure we haven't restarted or finished the animation
        assertFalse(mTarget.mAnimationStarted);
        assertFalse(mTarget.mAnimationEnded);
        // Ensure that we haven't tried to update the PiP mode
        assertFalse(mTarget.mUpdatedPictureInPictureModeWithBounds);
    }

    @UiThreadTest
    @Test
    public void testCancelAnimationFromNewAnimationToNewBounds() throws Exception {
        // Create and start the animation
        mTarget.reinitialize(BOUNDS_FULL, null);
        final BoundsAnimator boundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);

        // Drive some animation updates
        boundsAnimator.onAnimationUpdate(mAnimator.getWithValue(0.5f));

        // Cancel the animation as a restart to new bounds
        mTarget.reinitialize(null, null);
        final BoundsAnimator altBoundsAnimator = mController.animateBoundsImpl(mTarget, BOUNDS_FULL,
                BOUNDS_ALT_FLOATING, DURATION, !MOVE_TO_FULLSCREEN);
        // Ensure the animator is not the same
        assertNotSame(boundsAnimator, altBoundsAnimator);
        // Ensure that we did not get an animation start/end callback
        assertFalse(mTarget.mAnimationStarted);
        assertFalse(mTarget.mAnimationEnded);
        // Ensure that we haven't tried to update the PiP mode
        assertFalse(mTarget.mUpdatedPictureInPictureModeWithBounds);
    }

    /**
     * @return the bounds offset to zero/zero.
     */
    private Rect offsetToZero(Rect bounds) {
        mTmpRect.set(bounds);
        mTmpRect.offsetTo(0, 0);
        return mTmpRect;
    }
}
