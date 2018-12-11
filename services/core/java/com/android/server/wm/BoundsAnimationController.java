/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Debug;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Choreographer;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Enables animating bounds of objects.
 *
 * In multi-window world bounds of both stack and tasks can change. When we need these bounds to
 * change smoothly and not require the app to relaunch (e.g. because it handles resizes and
 * relaunching it would cause poorer experience), these class provides a way to directly animate
 * the bounds of the resized object.
 *
 * The object that is resized needs to implement {@link BoundsAnimationTarget} interface.
 *
 * NOTE: All calls to methods in this class should be done on the Animation thread
 */
public class BoundsAnimationController {
    private static final boolean DEBUG_LOCAL = false;
    private static final boolean DEBUG = DEBUG_LOCAL || DEBUG_ANIM;
    private static final String TAG = TAG_WITH_CLASS_NAME || DEBUG_LOCAL
            ? "BoundsAnimationController" : TAG_WM;
    private static final int DEBUG_ANIMATION_SLOW_DOWN_FACTOR = 1;

    private static final int DEFAULT_TRANSITION_DURATION = 425;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NO_PIP_MODE_CHANGED_CALLBACKS, SCHEDULE_PIP_MODE_CHANGED_ON_START,
        SCHEDULE_PIP_MODE_CHANGED_ON_END})
    public @interface SchedulePipModeChangedState {}
    /** Do not schedule any PiP mode changed callbacks as a part of this animation. */
    public static final int NO_PIP_MODE_CHANGED_CALLBACKS = 0;
    /** Schedule a PiP mode changed callback when this animation starts. */
    public static final int SCHEDULE_PIP_MODE_CHANGED_ON_START = 1;
    /** Schedule a PiP mode changed callback when this animation ends. */
    public static final int SCHEDULE_PIP_MODE_CHANGED_ON_END = 2;

    // Only accessed on UI thread.
    private ArrayMap<BoundsAnimationTarget, BoundsAnimator> mRunningAnimations = new ArrayMap<>();

    private final class AppTransitionNotifier
            extends WindowManagerInternal.AppTransitionListener implements Runnable {

        public void onAppTransitionCancelledLocked() {
            if (DEBUG) Slog.d(TAG, "onAppTransitionCancelledLocked:"
                    + " mFinishAnimationAfterTransition=" + mFinishAnimationAfterTransition);
            animationFinished();
        }
        public void onAppTransitionFinishedLocked(IBinder token) {
            if (DEBUG) Slog.d(TAG, "onAppTransitionFinishedLocked:"
                    + " mFinishAnimationAfterTransition=" + mFinishAnimationAfterTransition);
            animationFinished();
        }
        private void animationFinished() {
            if (mFinishAnimationAfterTransition) {
                mHandler.removeCallbacks(this);
                // This might end up calling into activity manager which will be bad since we have
                // the window manager lock held at this point. Post a message to take care of the
                // processing so we don't deadlock.
                mHandler.post(this);
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < mRunningAnimations.size(); i++) {
                final BoundsAnimator b = mRunningAnimations.valueAt(i);
                b.onAnimationEnd(null);
            }
        }
    }

    private final Handler mHandler;
    private final AppTransition mAppTransition;
    private final AppTransitionNotifier mAppTransitionNotifier = new AppTransitionNotifier();
    private final Interpolator mFastOutSlowInInterpolator;
    private boolean mFinishAnimationAfterTransition = false;
    private final AnimationHandler mAnimationHandler;
    private Choreographer mChoreographer;

    private static final int WAIT_FOR_DRAW_TIMEOUT_MS = 3000;

    BoundsAnimationController(Context context, AppTransition transition, Handler handler,
            AnimationHandler animationHandler) {
        mHandler = handler;
        mAppTransition = transition;
        mAppTransition.registerListenerLocked(mAppTransitionNotifier);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mAnimationHandler = animationHandler;
        if (animationHandler != null) {
            // If an animation handler is provided, then ensure that it runs on the sf vsync tick
            handler.runWithScissors(() -> mChoreographer = Choreographer.getSfInstance(),
                    0 /* timeout */);
            animationHandler.setProvider(new SfVsyncFrameCallbackProvider(mChoreographer));
        }
    }

    @VisibleForTesting
    final class BoundsAnimator extends ValueAnimator
            implements ValueAnimator.AnimatorUpdateListener, ValueAnimator.AnimatorListener {

        private final BoundsAnimationTarget mTarget;
        private final Rect mFrom = new Rect();
        private final Rect mTo = new Rect();
        private final Rect mTmpRect = new Rect();
        private final Rect mTmpTaskBounds = new Rect();

        // True if this this animation was canceled and will be replaced the another animation from
        // the same {@link #BoundsAnimationTarget} target.
        private boolean mSkipFinalResize;
        // True if this animation was canceled by the user, not as a part of a replacing animation
        private boolean mSkipAnimationEnd;

        // True if the animation target is animating from the fullscreen. Only one of
        // {@link mMoveToFullscreen} or {@link mMoveFromFullscreen} can be true at any time in the
        // animation.
        private boolean mMoveFromFullscreen;
        // True if the animation target should be moved to the fullscreen stack at the end of this
        // animation. Only one of {@link mMoveToFullscreen} or {@link mMoveFromFullscreen} can be
        // true at any time in the animation.
        private boolean mMoveToFullscreen;

        // Whether to schedule PiP mode changes on animation start/end
        private @SchedulePipModeChangedState int mSchedulePipModeChangedState;
        private @SchedulePipModeChangedState int mPrevSchedulePipModeChangedState;

        // Depending on whether we are animating from
        // a smaller to a larger size
        private final int mFrozenTaskWidth;
        private final int mFrozenTaskHeight;

        // Timeout callback to ensure we continue the animation if waiting for resuming or app
        // windows drawn fails
        private final Runnable mResumeRunnable = () -> {
            if (DEBUG) Slog.d(TAG, "pause: timed out waiting for windows drawn");
            resume();
        };

        BoundsAnimator(BoundsAnimationTarget target, Rect from, Rect to,
                @SchedulePipModeChangedState int schedulePipModeChangedState,
                @SchedulePipModeChangedState int prevShedulePipModeChangedState,
                boolean moveFromFullscreen, boolean moveToFullscreen) {
            super();
            mTarget = target;
            mFrom.set(from);
            mTo.set(to);
            mSchedulePipModeChangedState = schedulePipModeChangedState;
            mPrevSchedulePipModeChangedState = prevShedulePipModeChangedState;
            mMoveFromFullscreen = moveFromFullscreen;
            mMoveToFullscreen = moveToFullscreen;
            addUpdateListener(this);
            addListener(this);

            // If we are animating from smaller to larger, we want to change the task bounds
            // to their final size immediately so we can use scaling to make the window
            // larger. Likewise if we are going from bigger to smaller, we want to wait until
            // the end so we don't have to upscale from the smaller finished size.
            if (animatingToLargerSize()) {
                mFrozenTaskWidth = mTo.width();
                mFrozenTaskHeight = mTo.height();
            } else {
                mFrozenTaskWidth = mFrom.width();
                mFrozenTaskHeight = mFrom.height();
            }
        }

        @Override
        public void onAnimationStart(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationStart: mTarget=" + mTarget
                    + " mPrevSchedulePipModeChangedState=" + mPrevSchedulePipModeChangedState
                    + " mSchedulePipModeChangedState=" + mSchedulePipModeChangedState);
            mFinishAnimationAfterTransition = false;
            mTmpRect.set(mFrom.left, mFrom.top, mFrom.left + mFrozenTaskWidth,
                    mFrom.top + mFrozenTaskHeight);

            // Boost the thread priority of the animation thread while the bounds animation is
            // running
            updateBooster();

            // Ensure that we have prepared the target for animation before we trigger any size
            // changes, so it can swap surfaces in to appropriate modes, or do as it wishes
            // otherwise.
            if (mPrevSchedulePipModeChangedState == NO_PIP_MODE_CHANGED_CALLBACKS) {
                mTarget.onAnimationStart(mSchedulePipModeChangedState ==
                        SCHEDULE_PIP_MODE_CHANGED_ON_START, false /* forceUpdate */);

                // When starting an animation from fullscreen, pause here and wait for the
                // windows-drawn signal before we start the rest of the transition down into PiP.
                if (mMoveFromFullscreen && mTarget.shouldDeferStartOnMoveToFullscreen()) {
                    pause();
                }
            } else if (mPrevSchedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_END &&
                    mSchedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_START) {
                // We are replacing a running animation into PiP, but since it hasn't completed, the
                // client will not currently receive any picture-in-picture mode change callbacks.
                // However, we still need to report to them that they are leaving PiP, so this will
                // force an update via a mode changed callback.
                mTarget.onAnimationStart(true /* schedulePipModeChangedCallback */,
                        true /* forceUpdate */);
            }

            // Immediately update the task bounds if they have to become larger, but preserve
            // the starting position so we don't jump at the beginning of the animation.
            if (animatingToLargerSize()) {
                mTarget.setPinnedStackSize(mFrom, mTmpRect);

                // We pause the animation until the app has drawn at the new size.
                // The target will notify us via BoundsAnimationController#resume.
                // We do this here and pause the animation, rather than just defer starting it
                // so we can enter the animating state and have WindowStateAnimator apply the
                // correct logic to make this resize seamless.
                if (mMoveToFullscreen) {
                    pause();
                }
            }
        }

        @Override
        public void pause() {
            if (DEBUG) Slog.d(TAG, "pause: waiting for windows drawn");
            super.pause();
            mHandler.postDelayed(mResumeRunnable, WAIT_FOR_DRAW_TIMEOUT_MS);
        }

        @Override
        public void resume() {
            if (DEBUG) Slog.d(TAG, "resume:");
            mHandler.removeCallbacks(mResumeRunnable);
            super.resume();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            final float value = (Float) animation.getAnimatedValue();
            final float remains = 1 - value;
            mTmpRect.left = (int) (mFrom.left * remains + mTo.left * value + 0.5f);
            mTmpRect.top = (int) (mFrom.top * remains + mTo.top * value + 0.5f);
            mTmpRect.right = (int) (mFrom.right * remains + mTo.right * value + 0.5f);
            mTmpRect.bottom = (int) (mFrom.bottom * remains + mTo.bottom * value + 0.5f);
            if (DEBUG) Slog.d(TAG, "animateUpdate: mTarget=" + mTarget + " mBounds="
                    + mTmpRect + " from=" + mFrom + " mTo=" + mTo + " value=" + value
                    + " remains=" + remains);

            mTmpTaskBounds.set(mTmpRect.left, mTmpRect.top,
                    mTmpRect.left + mFrozenTaskWidth, mTmpRect.top + mFrozenTaskHeight);

            if (!mTarget.setPinnedStackSize(mTmpRect, mTmpTaskBounds)) {
                // Whoops, the target doesn't feel like animating anymore. Let's immediately finish
                // any further animation.
                if (DEBUG) Slog.d(TAG, "animateUpdate: cancelled");

                // If we have already scheduled a PiP mode changed at the start of the animation,
                // then we need to clean up and schedule one at the end, since we have canceled the
                // animation to the final state.
                if (mSchedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_START) {
                    mSchedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_END;
                }

                // Since we are cancelling immediately without a replacement animation, send the
                // animation end to maintain callback parity, but also skip any further resizes
                cancelAndCallAnimationEnd();
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationEnd: mTarget=" + mTarget
                    + " mSkipFinalResize=" + mSkipFinalResize
                    + " mFinishAnimationAfterTransition=" + mFinishAnimationAfterTransition
                    + " mAppTransitionIsRunning=" + mAppTransition.isRunning()
                    + " callers=" + Debug.getCallers(2));

            // There could be another animation running. For example in the
            // move to fullscreen case, recents will also be closing while the
            // previous task will be taking its place in the fullscreen stack.
            // we have to ensure this is completed before we finish the animation
            // and take our place in the fullscreen stack.
            if (mAppTransition.isRunning() && !mFinishAnimationAfterTransition) {
                mFinishAnimationAfterTransition = true;
                return;
            }

            if (!mSkipAnimationEnd) {
                // If this animation has already scheduled the picture-in-picture mode on start, and
                // we are not skipping the final resize due to being canceled, then move the PiP to
                // fullscreen once the animation ends
                if (DEBUG) Slog.d(TAG, "onAnimationEnd: mTarget=" + mTarget
                        + " moveToFullscreen=" + mMoveToFullscreen);
                mTarget.onAnimationEnd(mSchedulePipModeChangedState ==
                        SCHEDULE_PIP_MODE_CHANGED_ON_END, !mSkipFinalResize ? mTo : null,
                                mMoveToFullscreen);
            }

            // Clean up this animation
            removeListener(this);
            removeUpdateListener(this);
            mRunningAnimations.remove(mTarget);

            // Reset the thread priority of the animation thread after the bounds animation is done
            updateBooster();
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            // Always skip the final resize when the animation is canceled
            mSkipFinalResize = true;
            mMoveToFullscreen = false;
        }

        private void cancelAndCallAnimationEnd() {
            if (DEBUG) Slog.d(TAG, "cancelAndCallAnimationEnd: mTarget=" + mTarget);
            mSkipAnimationEnd = false;
            super.cancel();
        }

        @Override
        public void cancel() {
            if (DEBUG) Slog.d(TAG, "cancel: mTarget=" + mTarget);
            mSkipAnimationEnd = true;
            super.cancel();
        }

        /**
         * @return true if the animation target is the same as the input bounds.
         */
        boolean isAnimatingTo(Rect bounds) {
            return mTo.equals(bounds);
        }

        /**
         * @return true if we are animating to a larger surface size
         */
        @VisibleForTesting
        boolean animatingToLargerSize() {
            // TODO: Fix this check for aspect ratio changes
            return (mFrom.width() * mFrom.height() <= mTo.width() * mTo.height());
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
            // Do nothing
        }

        @Override
        public AnimationHandler getAnimationHandler() {
            if (mAnimationHandler != null) {
                return mAnimationHandler;
            }
            return super.getAnimationHandler();
        }
    }

    public void animateBounds(final BoundsAnimationTarget target, Rect from, Rect to,
            int animationDuration, @SchedulePipModeChangedState int schedulePipModeChangedState,
            boolean moveFromFullscreen, boolean moveToFullscreen) {
        animateBoundsImpl(target, from, to, animationDuration, schedulePipModeChangedState,
                moveFromFullscreen, moveToFullscreen);
    }

    @VisibleForTesting
    BoundsAnimator animateBoundsImpl(final BoundsAnimationTarget target, Rect from, Rect to,
            int animationDuration, @SchedulePipModeChangedState int schedulePipModeChangedState,
            boolean moveFromFullscreen, boolean moveToFullscreen) {
        final BoundsAnimator existing = mRunningAnimations.get(target);
        final boolean replacing = existing != null;
        @SchedulePipModeChangedState int prevSchedulePipModeChangedState =
                NO_PIP_MODE_CHANGED_CALLBACKS;

        if (DEBUG) Slog.d(TAG, "animateBounds: target=" + target + " from=" + from + " to=" + to
                + " schedulePipModeChangedState=" + schedulePipModeChangedState
                + " replacing=" + replacing);

        if (replacing) {
            if (existing.isAnimatingTo(to) && (!moveToFullscreen || existing.mMoveToFullscreen)
                    && (!moveFromFullscreen || existing.mMoveFromFullscreen)) {
                // Just let the current animation complete if it has the same destination as the
                // one we are trying to start, and, if moveTo/FromFullscreen was requested, already
                // has that flag set.
                if (DEBUG) Slog.d(TAG, "animateBounds: same destination and moveTo/From flags as "
                        + "existing=" + existing + ", ignoring...");
                return existing;
            }

            // Save the previous state
            prevSchedulePipModeChangedState = existing.mSchedulePipModeChangedState;

            // Update the PiP callback states if we are replacing the animation
            if (existing.mSchedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_START) {
                if (schedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_START) {
                    if (DEBUG) Slog.d(TAG, "animateBounds: still animating to fullscreen, keep"
                            + " existing deferred state");
                } else {
                    if (DEBUG) Slog.d(TAG, "animateBounds: fullscreen animation canceled, callback"
                            + " on start already processed, schedule deferred update on end");
                    schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_END;
                }
            } else if (existing.mSchedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_END) {
                if (schedulePipModeChangedState == SCHEDULE_PIP_MODE_CHANGED_ON_START) {
                    if (DEBUG) Slog.d(TAG, "animateBounds: non-fullscreen animation canceled,"
                            + " callback on start will be processed");
                } else {
                    if (DEBUG) Slog.d(TAG, "animateBounds: still animating from fullscreen, keep"
                            + " existing deferred state");
                    schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_END;
                }
            }

            // We need to keep the previous moveTo/FromFullscreen flag, unless the new animation
            // specifies a direction.
            if (!moveFromFullscreen && !moveToFullscreen) {
                moveToFullscreen = existing.mMoveToFullscreen;
                moveFromFullscreen = existing.mMoveFromFullscreen;
            }

            // Since we are replacing, we skip both animation start and end callbacks
            existing.cancel();
        }
        final BoundsAnimator animator = new BoundsAnimator(target, from, to,
                schedulePipModeChangedState, prevSchedulePipModeChangedState,
                moveFromFullscreen, moveToFullscreen);
        mRunningAnimations.put(target, animator);
        animator.setFloatValues(0f, 1f);
        animator.setDuration((animationDuration != -1 ? animationDuration
                : DEFAULT_TRANSITION_DURATION) * DEBUG_ANIMATION_SLOW_DOWN_FACTOR);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.start();
        return animator;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public void onAllWindowsDrawn() {
        if (DEBUG) Slog.d(TAG, "onAllWindowsDrawn:");
        mHandler.post(this::resume);
    }

    private void resume() {
        for (int i = 0; i < mRunningAnimations.size(); i++) {
            final BoundsAnimator b = mRunningAnimations.valueAt(i);
            b.resume();
        }
    }

    private void updateBooster() {
        WindowManagerService.sThreadPriorityBooster.setBoundsAnimationRunning(
                !mRunningAnimations.isEmpty());
    }
}
