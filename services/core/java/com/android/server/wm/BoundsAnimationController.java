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

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Debug;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.WindowManagerInternal;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Enables animating bounds of objects.
 *
 * In multi-window world bounds of both stack and tasks can change. When we need these bounds to
 * change smoothly and not require the app to relaunch (e.g. because it handles resizes and
 * relaunching it would cause poorer experience), these class provides a way to directly animate
 * the bounds of the resized object.
 *
 * The object that is resized needs to implement {@link AnimateBoundsUser} interface.
 *
 * NOTE: All calls to methods in this class should be done on the UI thread
 */
public class BoundsAnimationController {
    private static final boolean DEBUG_LOCAL = false;
    private static final boolean DEBUG = DEBUG_LOCAL || DEBUG_ANIM;
    private static final String TAG = TAG_WITH_CLASS_NAME || DEBUG_LOCAL
            ? "BoundsAnimationController" : TAG_WM;
    private static final int DEBUG_ANIMATION_SLOW_DOWN_FACTOR = 1;

    private static final int DEFAULT_TRANSITION_DURATION = 425;

    // Only accessed on UI thread.
    private ArrayMap<AnimateBoundsUser, BoundsAnimator> mRunningAnimations = new ArrayMap<>();

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

    BoundsAnimationController(Context context, AppTransition transition, Handler handler) {
        mHandler = handler;
        mAppTransition = transition;
        mAppTransition.registerListenerLocked(mAppTransitionNotifier);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
    }

    @VisibleForTesting
    final class BoundsAnimator extends ValueAnimator
            implements ValueAnimator.AnimatorUpdateListener, ValueAnimator.AnimatorListener {
        private final AnimateBoundsUser mTarget;
        private final Rect mFrom = new Rect();
        private final Rect mTo = new Rect();
        private final Rect mTmpRect = new Rect();
        private final Rect mTmpTaskBounds = new Rect();
        private final boolean mMoveToFullScreen;
        // True if this this animation was cancelled and will be replaced the another animation from
        // the same {@link #AnimateBoundsUser} target.
        private boolean mSkipFinalResize;
        // True if this animation replaced a previous animation of the same
        // {@link #AnimateBoundsUser} target.
        private final boolean mSkipAnimationStart;
        // True if this animation was cancelled by the user, not as a part of a replacing animation
        private boolean mSkipAnimationEnd;
        // True if this animation is not replacing a previous animation, or if the previous
        // animation is animating to a different fullscreen state than the current animation.
        // We use this to ensure that we always provide a consistent set/order of callbacks when we
        // transition to/from PiP.
        private final boolean mAnimatingToNewFullscreenState;

        // Depending on whether we are animating from
        // a smaller to a larger size
        private final int mFrozenTaskWidth;
        private final int mFrozenTaskHeight;

        BoundsAnimator(AnimateBoundsUser target, Rect from, Rect to, boolean moveToFullScreen,
                boolean replacingExistingAnimation, boolean animatingToNewFullscreenState) {
            super();
            mTarget = target;
            mFrom.set(from);
            mTo.set(to);
            mMoveToFullScreen = moveToFullScreen;
            mSkipAnimationStart = replacingExistingAnimation;
            mAnimatingToNewFullscreenState = animatingToNewFullscreenState;
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
                    + " mSkipAnimationStart=" + mSkipAnimationStart);
            mFinishAnimationAfterTransition = false;
            mTmpRect.set(mFrom.left, mFrom.top, mFrom.left + mFrozenTaskWidth,
                    mFrom.top + mFrozenTaskHeight);

            // Ensure that we have prepared the target for animation before
            // we trigger any size changes, so it can swap surfaces
            // in to appropriate modes, or do as it wishes otherwise.
            if (!mSkipAnimationStart) {
                mTarget.onAnimationStart(mMoveToFullScreen);
            }

            // If we are animating to a new fullscreen state (either to/from fullscreen), then
            // notify the target of the change with the new frozen task bounds
            if (mAnimatingToNewFullscreenState && mMoveToFullScreen) {
                mTarget.updatePictureInPictureMode(null);
            }

            // Immediately update the task bounds if they have to become larger, but preserve
            // the starting position so we don't jump at the beginning of the animation.
            if (animatingToLargerSize()) {
                mTarget.setPinnedStackSize(mFrom, mTmpRect);
            }
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

                // Since we are cancelling immediately without a replacement animation, send the
                // animation end to maintain callback parity, but also skip any further resizes
                prepareCancel(false /* skipAnimationEnd */, true /* skipFinalResize */);
                cancel();
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationEnd: mTarget=" + mTarget
                    + " mMoveToFullScreen=" + mMoveToFullScreen
                    + " mSkipFinalResize=" + mSkipFinalResize
                    + " mFinishAnimationAfterTransition=" + mFinishAnimationAfterTransition
                    + " mAppTransitionIsRunning=" + mAppTransition.isRunning());

            // There could be another animation running. For example in the
            // move to fullscreen case, recents will also be closing while the
            // previous task will be taking its place in the fullscreen stack.
            // we have to ensure this is completed before we finish the animation
            // and take our place in the fullscreen stack.
            if (mAppTransition.isRunning() && !mFinishAnimationAfterTransition) {
                mFinishAnimationAfterTransition = true;
                return;
            }

            if (!mSkipFinalResize) {
                // If not cancelled, resize the pinned stack to the final size. All calls to
                // setPinnedStackSize() must be done between onAnimationStart() and onAnimationEnd()
                mTarget.setPinnedStackSize(mTo, null);
            }

            finishAnimation();

            if (mMoveToFullScreen && !mSkipFinalResize) {
                mTarget.moveToFullscreen();
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            finishAnimation();
        }

        public void prepareCancel(boolean skipAnimationEnd, boolean skipFinalResize) {
            if (DEBUG) Slog.d(TAG, "prepareCancel: skipAnimationEnd=" + skipAnimationEnd
                    + " skipFinalResize=" + skipFinalResize);
            mSkipAnimationEnd = skipAnimationEnd;
            mSkipFinalResize = skipFinalResize;
        }

        @Override
        public void cancel() {
            if (DEBUG) Slog.d(TAG, "cancel: mTarget=" + mTarget);
            super.cancel();
        }

        /** Returns true if the animation target is the same as the input bounds. */
        boolean isAnimatingTo(Rect bounds) {
            return mTo.equals(bounds);
        }

        private boolean animatingToLargerSize() {
            if (mFrom.width() * mFrom.height() > mTo.width() * mTo.height()) {
                return false;
            }
            return true;
        }

        private void finishAnimation() {
            if (DEBUG) Slog.d(TAG, "finishAnimation: mTarget=" + mTarget
                    + " callers" + Debug.getCallers(2));
            if (!mSkipAnimationEnd) {
                mTarget.onAnimationEnd();
            }
            removeListener(this);
            removeUpdateListener(this);
            mRunningAnimations.remove(mTarget);
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
            // Do nothing
        }
    }

    public interface AnimateBoundsUser {
        /**
         * Sets the size of the target (without any intermediate steps, like scheduling animation)
         * but freezes the bounds of any tasks in the target at taskBounds,
         * to allow for more flexibility during resizing. Only works for the pinned stack at the
         * moment.
         *
         * @return Whether the target should continue to be animated and this call was successful.
         * If false, the animation will be cancelled because the user has determined that the
         * animation is now invalid and not required. In such a case, the cancel will trigger the
         * animation end callback as well, but will not send any further size changes.
         */
        boolean setPinnedStackSize(Rect bounds, Rect taskBounds);

        /**
         * Callback for the target to inform it that the animation has started, so it can do some
         * necessary preparation.
         */
        void onAnimationStart(boolean toFullscreen);

        /**
         * Callback for the target to inform it that the animation is going to a new fullscreen
         * state and should update the picture-in-picture mode accordingly.
         *
         * @param targetStackBounds the target stack bounds we are animating to, can be null if
         *                          we are animating to fullscreen
         */
        void updatePictureInPictureMode(Rect targetStackBounds);

        /**
         * Callback for the target to inform it that the animation has ended, so it can do some
         * necessary cleanup.
         */
        void onAnimationEnd();

        /**
         * Callback for the target to inform it to reparent to the fullscreen stack.
         */
        void moveToFullscreen();
    }

    public void animateBounds(final AnimateBoundsUser target, Rect from, Rect to,
            int animationDuration, boolean moveToFullscreen) {
        animateBoundsImpl(target, from, to, animationDuration, moveToFullscreen);
    }

    @VisibleForTesting
    BoundsAnimator animateBoundsImpl(final AnimateBoundsUser target, Rect from, Rect to,
            int animationDuration, boolean moveToFullscreen) {
        final BoundsAnimator existing = mRunningAnimations.get(target);
        final boolean replacing = existing != null;
        final boolean animatingToNewFullscreenState = (existing == null) ||
                (existing.mMoveToFullScreen != moveToFullscreen);

        if (DEBUG) Slog.d(TAG, "animateBounds: target=" + target + " from=" + from + " to=" + to
                + " moveToFullscreen=" + moveToFullscreen + " replacing=" + replacing
                + " animatingToNewFullscreenState=" + animatingToNewFullscreenState);

        if (replacing) {
            if (existing.isAnimatingTo(to)) {
                // Just let the current animation complete if it has the same destination as the
                // one we are trying to start.
                if (DEBUG) Slog.d(TAG, "animateBounds: same destination as existing=" + existing
                        + " ignoring...");
                return existing;
            }
            // Since we are replacing, we skip both animation start and end callbacks, and don't
            // animate to the final bounds when cancelling
            existing.prepareCancel(true /* skipAnimationEnd */, true /* skipFinalResize */);
            existing.cancel();
        }
        final BoundsAnimator animator = new BoundsAnimator(target, from, to, moveToFullscreen,
                replacing, animatingToNewFullscreenState);
        mRunningAnimations.put(target, animator);
        animator.setFloatValues(0f, 1f);
        animator.setDuration((animationDuration != -1 ? animationDuration
                : DEFAULT_TRANSITION_DURATION) * DEBUG_ANIMATION_SLOW_DOWN_FACTOR);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.start();
        return animator;
    }
}
