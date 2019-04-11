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
 * limitations under the License.
 */

package com.android.server.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.BoundsAnimationController.BOUNDS;
import static com.android.server.wm.BoundsAnimationController.FADE_IN;
import static com.android.server.wm.BoundsAnimationController.NO_PIP_MODE_CHANGED_CALLBACKS;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_END;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_START;
import static com.android.server.wm.BoundsAnimationController.SchedulePipModeChangedState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.server.wm.BoundsAnimationController.BoundsAnimator;
import com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import org.junit.Before;
import org.junit.Test;

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
 *  atest FrameworksServicesTests:BoundsAnimationControllerTests
 */
@SmallTest
@Presubmit
public class BoundsAnimationControllerTests extends WindowTestsBase {

    /**
     * Mock value animator to simulate updates with.
     */
    private static class MockValueAnimator extends ValueAnimator {

        private float mFraction;

        MockValueAnimator getWithValue(float fraction) {
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
    private static class MockAppTransition extends AppTransition {

        private AppTransitionListener mListener;

        MockAppTransition(Context context, WindowManagerService wm, DisplayContent displayContent) {
            super(context, wm, displayContent);
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
            mListener.onAppTransitionStartingLocked(transit, 0, 0, 0);
        }

        public void notifyTransitionFinished() {
            mListener.onAppTransitionFinishedLocked(null);
        }
    }

    /**
     * A test animate bounds user to track callbacks from the bounds animation.
     */
    private static class TestBoundsAnimationTarget implements BoundsAnimationTarget {

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
        float mAlpha;
        @BoundsAnimationController.AnimationType int mAnimationType;

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
        public boolean onAnimationStart(boolean schedulePipModeChangedCallback,
                boolean forceUpdate, @BoundsAnimationController.AnimationType int animationType) {
            mAwaitingAnimationStart = false;
            mAnimationStarted = true;
            mSchedulePipModeChangedOnStart = schedulePipModeChangedCallback;
            mForcePipModeChangedCallback = forceUpdate;
            mAnimationType = animationType;
            return true;
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

        @Override
        public boolean setPinnedStackAlpha(float alpha) {
            mAlpha = alpha;
            return true;
        }
    }

    /**
     * Drives the animations, makes common assertions along the way.
     */
    private static class BoundsAnimationDriver {

        private final BoundsAnimationController mController;
        private final TestBoundsAnimationTarget mTarget;
        private final MockValueAnimator mMockAnimator;

        private BoundsAnimator mAnimator;
        private Rect mFrom;
        private Rect mTo;
        private Rect mLargerBounds;
        private Rect mExpectedFinalBounds;
        private @BoundsAnimationController.AnimationType int mAnimationType;

        BoundsAnimationDriver(BoundsAnimationController controller,
                TestBoundsAnimationTarget target, MockValueAnimator mockValueAnimator) {
            mController = controller;
            mTarget = target;
            mMockAnimator = mockValueAnimator;
        }

        BoundsAnimationDriver start(Rect from, Rect to,
                @BoundsAnimationController.AnimationType int animationType) {
            if (mAnimator != null) {
                throw new IllegalArgumentException("Call restart() to restart an animation");
            }

            boolean fromFullscreen = from.equals(BOUNDS_FULL);
            boolean toFullscreen = to.equals(BOUNDS_FULL);

            mTarget.initialize(from);

            // Started, not running
            assertTrue(mTarget.mAwaitingAnimationStart);
            assertFalse(mTarget.mAnimationStarted);

            startImpl(from, to, animationType);

            // Ensure that the animator is paused for the all windows drawn signal when animating
            // to/from fullscreen
            if (fromFullscreen || toFullscreen) {
                assertTrue(mAnimator.isPaused());
                mController.onAllWindowsDrawn();
            } else {
                assertTrue(!mAnimator.isPaused());
            }

            // Started and running
            assertFalse(mTarget.mAwaitingAnimationStart);
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
            startImpl(mTarget.mStackBounds, to, BOUNDS);

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
                assertFalse(mTarget.mAnimationStarted);
            }
            mTarget.mAnimationStarted = true;
            return this;
        }

        private BoundsAnimationDriver startImpl(Rect from, Rect to,
                @BoundsAnimationController.AnimationType int animationType) {
            boolean fromFullscreen = from.equals(BOUNDS_FULL);
            boolean toFullscreen = to.equals(BOUNDS_FULL);
            mFrom = new Rect(from);
            mTo = new Rect(to);
            mExpectedFinalBounds = new Rect(to);
            mLargerBounds = getLargerBounds(mFrom, mTo);
            mAnimationType = animationType;

            // Start animation
            final @SchedulePipModeChangedState int schedulePipModeChangedState = toFullscreen
                    ? SCHEDULE_PIP_MODE_CHANGED_ON_START
                    : fromFullscreen
                            ? SCHEDULE_PIP_MODE_CHANGED_ON_END
                            : NO_PIP_MODE_CHANGED_CALLBACKS;
            mAnimator = mController.animateBoundsImpl(mTarget, from, to, DURATION,
                    schedulePipModeChangedState, fromFullscreen, toFullscreen, animationType);

            if (animationType == BOUNDS) {
                // Original stack bounds, frozen task bounds
                assertEquals(mFrom, mTarget.mStackBounds);
                assertEqualSizeAtOffset(mLargerBounds, mTarget.mTaskBounds);

                // Animating to larger size
                if (mFrom.equals(mLargerBounds)) {
                    assertFalse(mAnimator.animatingToLargerSize());
                } else if (mTo.equals(mLargerBounds)) {
                    assertTrue(mAnimator.animatingToLargerSize());
                }
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

            if (mAnimationType == BOUNDS) {
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
            } else {
                assertEquals((float) mMockAnimator.getAnimatedValue(), mTarget.mAlpha, 0.01f);
            }
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
            assertFalse(mTarget.mCancelRequested);

            // Stack/task bounds not updated
            assertFalse(mTarget.mBoundsUpdated);

            // Callback made
            assertTrue(mTarget.mAnimationEnded);
            assertNull(mTarget.mAnimationEndFinalStackBounds);

            return this;
        }

        BoundsAnimationDriver end() {
            mAnimator.end();

            if (mAnimationType == BOUNDS) {
                // Final stack bounds
                assertEquals(mTo, mTarget.mStackBounds);
                assertEquals(mExpectedFinalBounds, mTarget.mAnimationEndFinalStackBounds);
                assertNull(mTarget.mTaskBounds);
            } else {
                assertEquals(mTarget.mAlpha, 1f, 0.01f);
            }

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

        private static Rect getLargerBounds(Rect r1, Rect r2) {
            int r1Area = r1.width() * r1.height();
            int r2Area = r2.width() * r2.height();
            return (r1Area <= r2Area) ? r2 : r1;
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
    private TestBoundsAnimationTarget mTarget;
    private BoundsAnimationController mController;
    private BoundsAnimationDriver mDriver;

    // Temp
    private static final Rect sTmpRect = new Rect();

    @Before
    public void setUp() throws Exception {
        final Context context = getInstrumentation().getTargetContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        mMockAppTransition = new MockAppTransition(context, mWm, mDisplayContent);
        mTarget = new TestBoundsAnimationTarget();
        mController = new BoundsAnimationController(context, mMockAppTransition, handler, null);
        final MockValueAnimator mockValueAnimator = new MockValueAnimator();
        mDriver = new BoundsAnimationDriver(mController, mTarget, mockValueAnimator);
    }

    /** BASE TRANSITIONS **/

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingTransition() {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenTransition() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL, BOUNDS)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToSmallerFloatingTransition() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_SMALLER_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToLargerFloatingTransition() {
        mDriver.start(BOUNDS_SMALLER_FLOATING, BOUNDS_FLOATING, BOUNDS)
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
    public void testFullscreenToFloatingCancelFromTarget() {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToSameBounds() {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FLOATING, false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToFloatingBounds() {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_SMALLER_FLOATING,
                        false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFullscreenToFloatingCancelFromAnimationToFullscreenBounds() {
        // When animating from fullscreen and the animation is interruped, we expect the animation
        // start callback to be made, with a forced pip mode change callback
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FULL, true /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    /** !F->F w/ CANCEL **/

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromTarget() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL, BOUNDS)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromAnimationToSameBounds() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL, BOUNDS)
                .expectStarted(SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .restart(BOUNDS_FULL, false /* expectStartedAndPipModeChangedCallback */)
                .end()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToFullscreenCancelFromAnimationToFloatingBounds() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_FULL, BOUNDS)
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
    public void testFloatingToSmallerFloatingCancelFromTarget() {
        mDriver.start(BOUNDS_FLOATING, BOUNDS_SMALLER_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFloatingToLargerFloatingCancelFromTarget() {
        mDriver.start(BOUNDS_SMALLER_FLOATING, BOUNDS_FLOATING, BOUNDS)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0.25f)
                .cancel()
                .expectEnded(!SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    @UiThreadTest
    @Test
    public void testFadeIn() {
        mDriver.start(BOUNDS_FULL, BOUNDS_FLOATING, FADE_IN)
                .expectStarted(!SCHEDULE_PIP_MODE_CHANGED)
                .update(0f)
                .update(0.5f)
                .update(1f)
                .end()
                .expectEnded(SCHEDULE_PIP_MODE_CHANGED, !MOVE_TO_FULLSCREEN);
    }

    /** MISC **/

    @UiThreadTest
    @Test
    public void testBoundsAreCopied() {
        Rect from = new Rect(0, 0, 100, 100);
        Rect to = new Rect(25, 25, 75, 75);
        mDriver.start(from, to, BOUNDS)
                .update(0.25f)
                .end();
        assertEquals(new Rect(0, 0, 100, 100), from);
        assertEquals(new Rect(25, 25, 75, 75), to);
    }

    /**
     * @return whether the task and stack bounds would be the same if they were at the same offset.
     */
    private static boolean assertEqualSizeAtOffset(Rect stackBounds, Rect taskBounds) {
        sTmpRect.set(taskBounds);
        sTmpRect.offsetTo(stackBounds.left, stackBounds.top);
        return stackBounds.equals(sTmpRect);
    }
}
