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

import static com.android.server.wm.BoundsAnimationController.NO_PIP_MODE_CHANGED_CALLBACKS;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_END;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_START;
import static com.android.server.wm.BoundsAnimationController.SchedulePipModeChangedState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.wm.BoundsAnimationController.BoundsAnimator;
import com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link BoundsAnimationController} to ensure that it sends the right callbacks
 * depending on the various interactions.
 *
 * We are really concerned about only three of the transition states [F = fullscreen, !F = floating]
 * F->!F, !F->!F, and !F->F. Each animation can only be cancelled from the target mid-transition,
 * or if a new animation starts on the same target.  The tests below verifies that the target is
 * notified of all the cases where it is animating and cancelled so that it can respond
 * appropriately.
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
            mListener.onAppTransitionStartingLocked(transit, null, null, 0, 0, 0);
        }

        public void notifyTransitionFinished() {
            mListener.onAppTransitionFinishedLocked(null);
        }
    }

    /**
     * A test animate bounds user to track callbacks from the bounds animation.
     */
    private class TestBoundsAnimationTarget implements BoundsAnimationTarget {

        boolean mAwaitingAnimationStart;
        boolean mMovedToFullscreen;
        boolean mAnimationStarted;
        boolean mSchedulePipModeChangedOnStart;
        boolean mForcePipModeChangedCallback;
        boolean mAnimationEnded;
        Rect mAnimationEndFinalStackBounds;
        boolean mSchedulePipModeChangedOnEnd;
        boolean mBoundsUpdated;
        boolean mCancelRequested;
        Rect mStackBounds;
        Rect mTaskBounds;

        void initialize(Rect from) {
            mAwaitingAnimationStart = true;
            mMovedToFullscreen = false;
            mAnimationStarted = false;
            mAnimationEnded = false;
            mAnimationEndFinalStackBounds = null;
            mForcePipModeChangedCallback = false;
            mSchedulePipModeChangedOnStart = false;
            mSchedulePipModeChangedOnEnd = false;
            mStackBounds = from;
            mTaskBounds = null;
            mBoundsUpdated = false;
        }

        @Override
        public void onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate) {
            mAwaitingAnimationStart = false;
            mAnimationStarted = true;
            mSchedulePipModeChangedOnStart = schedulePipModeChangedCallback;
            mForcePipModeChangedCallback = forceUpdate;
        }

        @Override
        public boolean shouldDeferStartOnMoveToFullscreen() {
            return true;
        }

        @Override
        public boolean setPinnedStackSize(Rect stackBounds, Rect taskBounds) {
            // TODO: Once we break the runs apart, we should fail() here if this is called outside
            //       of onAnimationStart() and onAnimationEnd()
            if (mCancelRequested) {
                mCancelRequested = false;
                return false;
            } else {
                mBoundsUpdated = true;
                mStackBounds = stackBounds;
                mTaskBounds = taskBounds;
                return true;
            }
        }

        @Override
        public void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackBounds,
                boolean moveToFullscreen) {
            mAnimationEnded = true;
            mAnimationEndFinalStackBounds = finalStackBounds;
            mSchedulePipModeChangedOnEnd = schedulePipModeChangedCallback;
            mMovedToFullscreen = moveToFullscreen;
            mTaskBounds = null;
        }
    }

    /**
     * Drives the animations, makes common assertions along the way.
     */
    private class BoundsAnimationDriver {

        private BoundsAnimationController mController;
        private TestBoundsAnimationTarget mTarget;
        private BoundsAnimator mAnimator;

        private Rect mFrom;
        private Rect mTo;
        private Rect mLargerBounds;
        private Rect mExpectedFinalBounds;

        BoundsAnimationDriver(BoundsAnimationController controller,
                TestBoundsAnimationTarget target) {
            mController = controller;
            mTarget = target;
        }

        BoundsAnimationDriver start(Rect from, Rect to) {
            if (mAnimator != null) {
                throw new IllegalArgumentException("Call restart() to restart an animation");
            }

            boolean fromFullscreen = from.equals(BOUNDS_FULL);
            boolean toFullscreen = to.equals(BOUNDS_FULL);

            mTarget.initialize(from);

            // Started, not running
            assertTrue(mTarget.mAwaitingAnimationStart);
            assertTrue(!mTarget.mAnimationStarted);

            startImpl(from, to);

            // Ensure that the animator is paused for the all windows drawn signal when animating
            // to/from fullscreen
            if (fromFullscreen || toFullscreen) {
                assertTrue(mAnimator.isPaused());
                mController.onAllWindowsDrawn();
            } else {
                assertTrue(!mAnimator.isPaused());
            }

            // Started and running
            assertTrue(!mTarget.mAwaitingAnimationStart);
            assertTrue(mTarget.mAnimationStarted);

            return this;
        }

        BoundsAnimationDriver restart(Rect to, boolean expectStartedAndPipModeChangedCallback) {
            if (mAnimator == null) {
                throw new IllegalArgumentException("Call start() to start a new animation");
            }

            BoundsAnimator oldAnimator = mAnimator;
            boolean toSameBounds = mAnimator.isStarted() && to.equals(mTo);

            // Reset the animation start state
            mTarget.mAnimationStarted = false;

            // Start animation
            startImpl(mTarget.mStackBounds, to);

            if (toSameBounds) {
                // Same animator if same final bounds
                assertSame(oldAnimator, mAnimator);
            }

            if (expectStartedAndPipModeChangedCallback) {
                // Replacing animation with pending pip mode changed callback, ensure we update
                assertTrue(mTarget.mAnimationStarted);
                assertTrue(mTarget.mSchedulePipModeChangedOnStart);
                assertTrue(mTarget.mForcePipModeChangedCallback);
            } else {
                // No animation start for replacing animation
                assertTrue(!mTarget.mAnimationStarted);
            }
            mTarget.mAnimationStarted = true;
            return this;
        }

        private BoundsAnimationDriver startImpl(Rect from, Rect to) {
            boolean fromFullscreen = from.equals(BOUNDS_FULL);
            boolean toFullscreen = to.equals(BOUNDS_FULL);
            mFrom = new Rect(from);
            mTo = new Rect(to);
            mExpectedFinalBounds = new Rect(to);
            mLargerBounds = getLargerBounds(mFrom, mTo);

            // Start animation
            final @SchedulePipModeChangedState int schedulePipModeChangedState = toFullscreen
                    ? SCHEDULE_PIP_MODE_CHANGED_ON_START
                    : fromFullscreen
                            ? SCHEDULE_PIP_MODE_CHANGED_ON_END
                            : NO_PIP_MODE_CHANGED_CALLBACKS;
            mAnimator = mController.animateBoundsImpl(mTarget, from, to, DURATION,
                    schedulePipModeChangedState, fromFullscreen, toFullscreen);

            // Original stack bounds, frozen task bounds
            assertEquals(mFrom, mTarget.mStackBounds);
            assertEqualSizeAtOffset(mLargerBounds, mTarget.mTaskBounds);

            // Animating to larger size
            if (mFrom.equals(mLargerBounds)) {
                assertTrue(!mAnimator.animatingToLargerSize());
            } else if (mTo.equals(mLargerBounds)) {
                assertTrue(mAnimator.animatingToLargerSize());
            }

            return this;
        }

        BoundsAnimationDriver expectStarted(boolean schedulePipModeChanged) {
            // Callback made
            assertTrue(mTarget.mAnimationStarted);

            assertEquals(schedulePipModeChanged, mTarget.mSchedulePipModeChangedOnStart);
            return this;
        }

        BoundsAnimationDriver update(float t) {
            mAnimator.onAnimationUpdate(mMockAnimator.getWithValue(t));

            // Temporary stack bounds, frozen task bounds
            if (t == 0f) {
                assertEquals(mFrom, mTarget.mStackBounds);
            } else if (t == 1f) {
                assertEquals(mTo, mTarget.mStackBounds);
            } else {
                assertNotEquals(mFrom, mTarget.mStackBounds);
                assertNotEquals(mTo, mTarget.mStackBounds);
            }
            assertEqualSizeAtOffset(mLargerBounds, mTarget.mTaskBounds);
            return this;
        }

        BoundsAnimationDriver cancel() {
            // Cancel
            mTarget.mCancelRequested = true;
            mTarget.mBoundsUpdated = false;
            mExpectedFinalBounds = null;

            // Update
            mAnimator.onAnimationUpdate(mMockAnimator.getWithValue(0.5f));

            // Not started, not running, cancel reset
            assertTrue(!mTarget.mCancelRequested);

            // Stack/task bounds not updated
            assertTrue(!mTarget.mBoundsUpdated);

            // Callback made
            assertTrue(mTarget.mAnimationEnded);
            assertNull(mTarget.mAnimationEndFinalStackBounds);

            return this;
        }

        BoundsAnimationDriver end() {
            mAnimator.end();

            // Final stack bounds
            assertEquals(mTo, mTarget.mStackBounds);
            assertEquals(mExpectedFinalBounds, mTarget.mAnimationEndFinalStackBounds);
            assertNull(mTarget.mTaskBounds);

            return this;
        }

        BoundsAnimationDriver expectEnded(boolean schedulePipModeChanged,
                boolean moveToFullscreen) {
            // Callback made
            assertTrue(mTarget.mAnimationEnded);

            assertEquals(schedulePipModeChanged, mTarget.mSchedulePipModeChangedOnEnd);
            assertEquals(moveToFullscreen, mTarget.mMovedToFullscreen);
            return this;
        }

        private Rect getLargerBounds(Rect r1, Rect r2) {
            int r1Area = r1.width() * r1.height();
            int r2Area = r2.width() * r2.height();
            if (r1Area <= r2Area) {
                return r2;
            } else {
                return r1;
            }
        }
    }

    // Constants
    private static final boolean SCHEDULE_PIP_MODE_CHANGED = true;
    private static final boolean MOVE_TO_FULLSCREEN = true;
    private static final int DURATION = 100;

    // Some dummy bounds to represent fullscreen and floating bounds
    private static final Rect BOUNDS_FULL = new Rect(0, 0, 100, 100);
    private static final Rect BOUNDS_FLOATING = new Rect(60, 60, 95, 95);
    private static final Rect BOUNDS_SMALLER_FLOATING = new Rect(80, 80, 95, 95);

    // Common
    private MockAppTransition mMockAppTransition;
    private MockValueAnimator mMockAnimator;
    private TestBoundsAnimationTarget mTarget;
    private BoundsAnimationController mController;
    private BoundsAnimationDriver mDriver;

    // Temp
    private Rect mTmpRect = new Rect();

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Context context = InstrumentationRegistry.getTargetContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        mMockAppTransition = new MockAppTransition(context);
        mMockAnimator = new MockValueAnimator();
        mTarget = new TestBoundsAnimationTarget();
        mController = new BoundsAnimationController(context, mMockAppTransition, handler, null);
        mDriver = new BoundsAnimationDriver(mController, mTarget);
    }

    /** BASE TRANSITIONS **/

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingTransition() throws Exception {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenTransition() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToSmallerFloatingTransition() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_SMALLER_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToLargerFloatingTransition() throws Exception {
        mDriver.start(BOUNDS_SMALLER_FLOATING, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    /** F->!F w/ CANCEL **/

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromTarget() throws Exception {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToSameBounds() throws Exception {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FLOATING, false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToFloatingBounds() throws Exception {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_SMALLER_FLOATING,
                        false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToFullscreenBounds() throws Exception {
        // When animating from fullscreen and the animation is interruped, we expect the animation
        // start callback to be made, with a forced pip mode change callback
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FULL, true /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    /** !F->F w/ CANCEL **/

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromTarget() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromAnimationToSameBounds() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FULL, false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromAnimationToFloatingBounds() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_SMALLER_FLOATING,
                        false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    /** !F->!F w/ CANCEL **/

    @UiThreadTest
    @Test
    public void testFloatingToSmallerFloatingCancelFromTarget() throws Exception {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_SMALLER_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToLargerFloatingCancelFromTarget() throws Exception {
        mDriver.start(BOUNDS_SMALLER_FLOATING, BOUNDS_FLOATING)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    /** MISC **/

    @UiThreadTest
    @Test
    public void testBoundsAreCopied() throws Exception {
        Rect from = new Rect(0, 0, 100, 100);
        Rect to = new Rect(25, 25, 75, 75);
        mDriver.start(from, to)
                .update(0.25f)
                .end();
        assertEquals(new Rect(0, 0, 100, 100), from);
        assertEquals(new Rect(25, 25, 75, 75), to);
    }

    /**
     * @return whether the task and stack bounds would be the same if they were at the same offset.
     */
    private boolean assertEqualSizeAtOffset(Rect stackBounds, Rect taskBounds) {
        mTmpRect.set(taskBounds);
        mTmpRect.offsetTo(stackBounds.left, stackBounds.top);
        return stackBounds.equals(mTmpRect);
    }
}
