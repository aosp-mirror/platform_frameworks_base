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

package com.android.systemui.pip.phone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Debug;
import android.util.Log;

import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.animation.FloatProperties;
import com.android.systemui.util.animation.PhysicsAnimator;
import com.android.systemui.util.magnetictarget.MagnetizedObject;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * A helper to animate and manipulate the PiP.
 */
public class PipMotionHelper implements PipAppOpsListener.Callback,
        FloatingContentCoordinator.FloatingContent {

    private static final String TAG = "PipMotionHelper";
    private static final boolean DEBUG = false;

    private static final int SHRINK_STACK_FROM_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_FULLSCREEN_DURATION = 300;
    private static final int SHIFT_DURATION = 300;

    /** Friction to use for PIP when it moves via physics fling animations. */
    private static final float DEFAULT_FRICTION = 2f;

    private final Context mContext;
    private final PipTaskOrganizer mPipTaskOrganizer;

    private PipMenuActivityController mMenuController;
    private PipSnapAlgorithm mSnapAlgorithm;

    /** PIP's current bounds on the screen. */
    private final Rect mBounds = new Rect();

    /** The bounds within which PIP's top-left coordinate is allowed to move. */
    private final Rect mMovementBounds = new Rect();

    /** The region that all of PIP must stay within. */
    private final Rect mFloatingAllowedArea = new Rect();

    /**
     * Bounds that are animated using the physics animator.
     */
    private final Rect mAnimatedBounds = new Rect();

    /** The destination bounds to which PIP is animating. */
    private final Rect mAnimatingToBounds = new Rect();

    /** Coordinator instance for resolving conflicts with other floating content. */
    private FloatingContentCoordinator mFloatingContentCoordinator;

    /**
     * PhysicsAnimator instance for animating {@link #mAnimatedBounds} using physics animations.
     */
    private PhysicsAnimator<Rect> mAnimatedBoundsPhysicsAnimator = PhysicsAnimator.getInstance(
            mAnimatedBounds);

    /**
     * Update listener that resizes the PIP to {@link #mAnimatedBounds}.
     */
    final PhysicsAnimator.UpdateListener<Rect> mResizePipUpdateListener =
            (target, values) -> resizePipUnchecked(mAnimatedBounds);

    /** FlingConfig instances provided to PhysicsAnimator for fling gestures. */
    private PhysicsAnimator.FlingConfig mFlingConfigX;
    private PhysicsAnimator.FlingConfig mFlingConfigY;

    /** SpringConfig to use for fling-then-spring animations. */
    private final PhysicsAnimator.SpringConfig mSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    /** SpringConfig to use for springing PIP away from conflicting floating content. */
    private final PhysicsAnimator.SpringConfig mConflictResolutionSpringConfig =
                new PhysicsAnimator.SpringConfig(
                        SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    private final Consumer<Rect> mUpdateBoundsCallback = mBounds::set;

    /**
     * Whether we're springing to the touch event location (vs. moving it to that position
     * instantly). We spring-to-touch after PIP is dragged out of the magnetic target, since it was
     * 'stuck' in the target and needs to catch up to the touch location.
     */
    private boolean mSpringingToTouch = false;

    /**
     * Gets set in {@link #animateToExpandedState(Rect, Rect, Rect, Runnable)}, this callback is
     * used to show menu activity when the expand animation is completed.
     */
    private Runnable mPostPipTransitionCallback;

    private final PipTaskOrganizer.PipTransitionCallback mPipTransitionCallback =
            new PipTaskOrganizer.PipTransitionCallback() {
        @Override
        public void onPipTransitionStarted(ComponentName activity, int direction) {}

        @Override
        public void onPipTransitionFinished(ComponentName activity, int direction) {
            if (mPostPipTransitionCallback != null) {
                mPostPipTransitionCallback.run();
                mPostPipTransitionCallback = null;
            }
        }

        @Override
        public void onPipTransitionCanceled(ComponentName activity, int direction) {}
    };

    public PipMotionHelper(Context context, PipTaskOrganizer pipTaskOrganizer,
            PipMenuActivityController menuController, PipSnapAlgorithm snapAlgorithm,
            FloatingContentCoordinator floatingContentCoordinator) {
        mContext = context;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMenuController = menuController;
        mSnapAlgorithm = snapAlgorithm;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mPipTaskOrganizer.registerPipTransitionCallback(mPipTransitionCallback);
    }

    @NonNull
    @Override
    public Rect getFloatingBoundsOnScreen() {
        return !mAnimatingToBounds.isEmpty() ? mAnimatingToBounds : mBounds;
    }

    @NonNull
    @Override
    public Rect getAllowedFloatingBoundsRegion() {
        return mFloatingAllowedArea;
    }

    @Override
    public void moveToBounds(@NonNull Rect bounds) {
        animateToBounds(bounds, mConflictResolutionSpringConfig);
    }

    /**
     * Synchronizes the current bounds with the pinned stack, cancelling any ongoing animations.
     */
    void synchronizePinnedStackBounds() {
        cancelAnimations();
        mBounds.set(mPipTaskOrganizer.getLastReportedBounds());

        if (mPipTaskOrganizer.isInPip()) {
            mFloatingContentCoordinator.onContentMoved(this);
        }
    }

    /**
     * Synchronizes the current bounds with either the pinned stack, or the ongoing animation. This
     * is done to prepare for a touch gesture.
     */
    void synchronizePinnedStackBoundsForTouchGesture() {
        if (mAnimatingToBounds.isEmpty()) {
            // If we're not animating anywhere, sync normally.
            synchronizePinnedStackBounds();
        } else {
            // If we're animating, set the current bounds to the animated bounds. That way, the
            // touch gesture will begin at the most recent animated location of the bounds.
            mBounds.set(mAnimatedBounds);
        }
    }

    /**
     * Tries to move the pinned stack to the given {@param bounds}.
     */
    void movePip(Rect toBounds) {
        movePip(toBounds, false /* isDragging */);
    }

    /**
     * Tries to move the pinned stack to the given {@param bounds}.
     *
     * @param isDragging Whether this movement is the result of a drag touch gesture. If so, we
     *                   won't notify the floating content coordinator of this move, since that will
     *                   happen when the gesture ends.
     */
    void movePip(Rect toBounds, boolean isDragging) {
        if (!isDragging) {
            mFloatingContentCoordinator.onContentMoved(this);
        }

        if (!mSpringingToTouch) {
            // If we are moving PIP directly to the touch event locations, cancel any animations and
            // move PIP to the given bounds.
            cancelAnimations();
            resizePipUnchecked(toBounds);
            mBounds.set(toBounds);
        } else {
            // If PIP is 'catching up' after being stuck in the dismiss target, update the animation
            // to spring towards the new touch location.
            mAnimatedBoundsPhysicsAnimator
                    .spring(FloatProperties.RECT_X, toBounds.left, mSpringConfig)
                    .spring(FloatProperties.RECT_Y, toBounds.top, mSpringConfig)
                    .withEndActions(() -> mSpringingToTouch = false);

            startBoundsAnimator(toBounds.left /* toX */, toBounds.top /* toY */,
                    false /* dismiss */);
        }
    }

    /** Set whether we're springing-to-touch to catch up after being stuck in the dismiss target. */
    void setSpringingToTouch(boolean springingToTouch) {
        if (springingToTouch) {
            mAnimatedBounds.set(mBounds);
        }

        mSpringingToTouch = springingToTouch;
    }

    void prepareForAnimation() {
        mAnimatedBounds.set(mBounds);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPipToFullscreen() {
        expandPipToFullscreen(false /* skipAnimation */);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPipToFullscreen(boolean skipAnimation) {
        if (DEBUG) {
            Log.d(TAG, "exitPip: skipAnimation=" + skipAnimation
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mPipTaskOrganizer.getUpdateHandler().post(() -> {
            mPipTaskOrganizer.exitPip(skipAnimation
                    ? 0
                    : EXPAND_STACK_TO_FULLSCREEN_DURATION);
        });
    }

    /**
     * Dismisses the pinned stack.
     */
    @Override
    public void dismissPip() {
        if (DEBUG) {
            Log.d(TAG, "removePip: callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mPipTaskOrganizer.removePip();
    }

    /** Sets the movement bounds to use to constrain PIP position animations. */
    void setCurrentMovementBounds(Rect movementBounds) {
        mMovementBounds.set(movementBounds);
        rebuildFlingConfigs();

        // The movement bounds represent the area within which we can move PIP's top-left position.
        // The allowed area for all of PIP is those bounds plus PIP's width and height.
        mFloatingAllowedArea.set(mMovementBounds);
        mFloatingAllowedArea.right += mBounds.width();
        mFloatingAllowedArea.bottom += mBounds.height();
    }

    /**
     * @return the PiP bounds.
     */
    Rect getBounds() {
        return mBounds;
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    void flingToSnapTarget(
            float velocityX, float velocityY,
            @Nullable Runnable updateAction, @Nullable Runnable endAction) {
        mAnimatedBounds.set(mBounds);
        mAnimatedBoundsPhysicsAnimator
                .flingThenSpring(
                        FloatProperties.RECT_X, velocityX, mFlingConfigX, mSpringConfig,
                        true /* flingMustReachMinOrMax */)
                .flingThenSpring(
                        FloatProperties.RECT_Y, velocityY, mFlingConfigY, mSpringConfig)
                .withEndActions(endAction);

        if (updateAction != null) {
            mAnimatedBoundsPhysicsAnimator.addUpdateListener(
                    (target, values) -> updateAction.run());
        }

        final float xEndValue = velocityX < 0 ? mMovementBounds.left : mMovementBounds.right;
        final float estimatedFlingYEndValue =
                PhysicsAnimator.estimateFlingEndValue(mBounds.top, velocityY, mFlingConfigY);

        startBoundsAnimator(xEndValue /* toX */, estimatedFlingYEndValue /* toY */,
                false /* dismiss */);
    }

    /**
     * Animates PIP to the provided bounds, using physics animations and the given spring
     * configuration
     */
    void animateToBounds(Rect bounds, PhysicsAnimator.SpringConfig springConfig) {
        mAnimatedBounds.set(mBounds);
        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, bounds.left, springConfig)
                .spring(FloatProperties.RECT_Y, bounds.top, springConfig);
        startBoundsAnimator(bounds.left /* toX */, bounds.top /* toY */,
                false /* dismiss */);
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    void animateDismiss() {
        mAnimatedBounds.set(mBounds);

        // Animate off the bottom of the screen, then dismiss PIP.
        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_Y,
                        mBounds.bottom + mBounds.height(),
                        0,
                        mSpringConfig)
                .withEndActions(this::dismissPip);

        startBoundsAnimator(mBounds.left /* toX */, mBounds.bottom + mBounds.height() /* toY */,
                true /* dismiss */);
    }

    /**
     * Animates the PiP to the expanded state to show the menu.
     */
    float animateToExpandedState(Rect expandedBounds, Rect movementBounds,
            Rect expandedMovementBounds, Runnable callback) {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds), movementBounds);
        mSnapAlgorithm.applySnapFraction(expandedBounds, expandedMovementBounds, savedSnapFraction);
        mPostPipTransitionCallback = callback;
        resizeAndAnimatePipUnchecked(expandedBounds, EXPAND_STACK_TO_MENU_DURATION);
        return savedSnapFraction;
    }

    /**
     * Animates the PiP from the expanded state to the normal state after the menu is hidden.
     */
    void animateToUnexpandedState(Rect normalBounds, float savedSnapFraction,
            Rect normalMovementBounds, Rect currentMovementBounds, boolean immediate) {
        if (savedSnapFraction < 0f) {
            // If there are no saved snap fractions, then just use the current bounds
            savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds),
                    currentMovementBounds);
        }
        mSnapAlgorithm.applySnapFraction(normalBounds, normalMovementBounds, savedSnapFraction);

        if (immediate) {
            movePip(normalBounds);
        } else {
            resizeAndAnimatePipUnchecked(normalBounds, SHRINK_STACK_FROM_MENU_DURATION);
        }
    }

    /**
     * Animates the PiP to offset it from the IME or shelf.
     */
    void animateToOffset(Rect originalBounds, int offset) {
        if (DEBUG) {
            Log.d(TAG, "animateToOffset: originalBounds=" + originalBounds + " offset=" + offset
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mPipTaskOrganizer.scheduleOffsetPip(originalBounds, offset, SHIFT_DURATION,
                mUpdateBoundsCallback);
    }

    /**
     * Cancels all existing animations.
     */
    private void cancelAnimations() {
        mAnimatedBoundsPhysicsAnimator.cancel();
        mAnimatingToBounds.setEmpty();
        mSpringingToTouch = false;
    }

    /** Set new fling configs whose min/max values respect the given movement bounds. */
    private void rebuildFlingConfigs() {
        mFlingConfigX = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, mMovementBounds.left, mMovementBounds.right);
        mFlingConfigY = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, mMovementBounds.top, mMovementBounds.bottom);
    }

    /**
     * Starts the physics animator which will update the animated PIP bounds using physics
     * animations, as well as the TimeAnimator which will apply those bounds to PIP.
     *
     * This will also add end actions to the bounds animator that cancel the TimeAnimator and update
     * the 'real' bounds to equal the final animated bounds.
     */
    private void startBoundsAnimator(float toX, float toY, boolean dismiss) {
        if (!mSpringingToTouch) {
            cancelAnimations();
        }

        // Set animatingToBounds directly to avoid allocating a new Rect, but then call
        // setAnimatingToBounds to run the normal logic for changing animatingToBounds.
        mAnimatingToBounds.set(
                (int) toX,
                (int) toY,
                (int) toX + mBounds.width(),
                (int) toY + mBounds.height());
        setAnimatingToBounds(mAnimatingToBounds);

        mAnimatedBoundsPhysicsAnimator
                .withEndActions(() -> {
                    if (!dismiss) {
                        mPipTaskOrganizer.scheduleFinishResizePip(mAnimatedBounds);
                    }
                    mAnimatingToBounds.setEmpty();
                })
                .addUpdateListener(mResizePipUpdateListener)
                .start();
    }

    /**
     * Notifies the floating coordinator that we're moving, and sets {@link #mAnimatingToBounds} so
     * we return these bounds from
     * {@link FloatingContentCoordinator.FloatingContent#getFloatingBoundsOnScreen()}.
     */
    private void setAnimatingToBounds(Rect bounds) {
        mAnimatingToBounds.set(bounds);
        mFloatingContentCoordinator.onContentMoved(this);
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizePipUnchecked(Rect toBounds) {
        if (DEBUG) {
            Log.d(TAG, "resizePipUnchecked: toBounds=" + toBounds
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (!toBounds.equals(mBounds)) {
            mPipTaskOrganizer.scheduleResizePip(toBounds, mUpdateBoundsCallback);
        }
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizeAndAnimatePipUnchecked(Rect toBounds, int duration) {
        if (DEBUG) {
            Log.d(TAG, "resizeAndAnimatePipUnchecked: toBounds=" + toBounds
                    + " duration=" + duration + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (!toBounds.equals(mBounds)) {
            mPipTaskOrganizer.scheduleAnimateResizePip(toBounds, duration, mUpdateBoundsCallback);
            setAnimatingToBounds(toBounds);
        }
    }

    /**
     * Returns a MagnetizedObject wrapper for PIP's animated bounds. This is provided to the
     * magnetic dismiss target so it can calculate PIP's size and position.
     */
    MagnetizedObject<Rect> getMagnetizedPip() {
        return new MagnetizedObject<Rect>(
                mContext, mAnimatedBounds, FloatProperties.RECT_X, FloatProperties.RECT_Y) {
            @Override
            public float getWidth(@NonNull Rect animatedPipBounds) {
                return animatedPipBounds.width();
            }

            @Override
            public float getHeight(@NonNull Rect animatedPipBounds) {
                return animatedPipBounds.height();
            }

            @Override
            public void getLocationOnScreen(
                    @NonNull Rect animatedPipBounds, @NonNull int[] loc) {
                loc[0] = animatedPipBounds.left;
                loc[1] = animatedPipBounds.top;
            }
        };
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
    }
}
