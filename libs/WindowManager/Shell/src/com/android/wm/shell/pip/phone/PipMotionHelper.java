/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_LEFT;
import static com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_RIGHT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;

import androidx.annotation.VisibleForTesting;
import androidx.dynamicanimation.animation.AnimationHandler;
import androidx.dynamicanimation.animation.AnimationHandler.FrameCallbackScheduler;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.wm.shell.animation.FloatProperties;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;

import java.util.function.Consumer;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * A helper to animate and manipulate the PiP.
 */
public class PipMotionHelper implements PipAppOpsListener.Callback,
        FloatingContentCoordinator.FloatingContent {

    private static final String TAG = "PipMotionHelper";
    private static final boolean DEBUG = false;

    private static final int SHRINK_STACK_FROM_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_MENU_DURATION = 250;
    private static final int LEAVE_PIP_DURATION = 300;
    private static final int SHIFT_DURATION = 300;

    /** Friction to use for PIP when it moves via physics fling animations. */
    private static final float DEFAULT_FRICTION = 2f;

    private final Context mContext;
    private final PipTaskOrganizer mPipTaskOrganizer;
    private @NonNull PipBoundsState mPipBoundsState;

    private PipMenuActivityController mMenuController;
    private PipSnapAlgorithm mSnapAlgorithm;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** The bounds within which PIP's top-left coordinate is allowed to move. */
    private final Rect mMovementBounds = new Rect();

    /** The region that all of PIP must stay within. */
    private final Rect mFloatingAllowedArea = new Rect();

    /** Coordinator instance for resolving conflicts with other floating content. */
    private FloatingContentCoordinator mFloatingContentCoordinator;

    private ThreadLocal<AnimationHandler> mSfAnimationHandlerThreadLocal =
            ThreadLocal.withInitial(() -> {
                FrameCallbackScheduler scheduler = runnable ->
                        Choreographer.getSfInstance().postFrameCallback(t -> runnable.run());
                AnimationHandler handler = new AnimationHandler(scheduler);
                return handler;
            });

    /**
     * PhysicsAnimator instance for animating {@link PipBoundsState#getAnimatingBoundsState()}
     * using physics animations.
     */
    private final PhysicsAnimator<Rect> mTemporaryBoundsPhysicsAnimator;

    private MagnetizedObject<Rect> mMagnetizedPip;

    /**
     * Update listener that resizes the PIP to {@link PipBoundsState#getAnimatingBoundsState()}.
     */
    private final PhysicsAnimator.UpdateListener<Rect> mResizePipUpdateListener;

    /** FlingConfig instances provided to PhysicsAnimator for fling gestures. */
    private PhysicsAnimator.FlingConfig mFlingConfigX;
    private PhysicsAnimator.FlingConfig mFlingConfigY;
    /** FlingConfig instances proviced to PhysicsAnimator for stashing. */
    private PhysicsAnimator.FlingConfig mStashConfigX;

    /** SpringConfig to use for fling-then-spring animations. */
    private final PhysicsAnimator.SpringConfig mSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    /** SpringConfig to use for springing PIP away from conflicting floating content. */
    private final PhysicsAnimator.SpringConfig mConflictResolutionSpringConfig =
                new PhysicsAnimator.SpringConfig(
                        SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    private final Consumer<Rect> mUpdateBoundsCallback = (Rect newBounds) -> {
        mMainHandler.post(() -> {
            mMenuController.updateMenuLayout(newBounds);
            mPipBoundsState.setBounds(newBounds);
        });
    };

    /**
     * Whether we're springing to the touch event location (vs. moving it to that position
     * instantly). We spring-to-touch after PIP is dragged out of the magnetic target, since it was
     * 'stuck' in the target and needs to catch up to the touch location.
     */
    private boolean mSpringingToTouch = false;

    /**
     * Whether PIP was released in the dismiss target, and will be animated out and dismissed
     * shortly.
     */
    private boolean mDismissalPending = false;

    /**
     * Gets set in {@link #animateToExpandedState(Rect, Rect, Rect, Runnable)}, this callback is
     * used to show menu activity when the expand animation is completed.
     */
    private Runnable mPostPipTransitionCallback;

    private final PipTaskOrganizer.PipTransitionCallback mPipTransitionCallback =
            new PipTaskOrganizer.PipTransitionCallback() {
        @Override
        public void onPipTransitionStarted(ComponentName activity, int direction, Rect pipBounds) {}

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

    public PipMotionHelper(Context context, @NonNull PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer, PipMenuActivityController menuController,
            PipSnapAlgorithm snapAlgorithm, FloatingContentCoordinator floatingContentCoordinator) {
        mContext = context;
        mPipTaskOrganizer = pipTaskOrganizer;
        mPipBoundsState = pipBoundsState;
        mMenuController = menuController;
        mSnapAlgorithm = snapAlgorithm;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mPipTaskOrganizer.registerPipTransitionCallback(mPipTransitionCallback);
        mTemporaryBoundsPhysicsAnimator = PhysicsAnimator.getInstance(
                mPipBoundsState.getAnimatingBoundsState().getTemporaryBounds());
        mTemporaryBoundsPhysicsAnimator.setCustomAnimationHandler(
                mSfAnimationHandlerThreadLocal.get());

        mResizePipUpdateListener = (target, values) -> {
            if (mPipBoundsState.getAnimatingBoundsState().isAnimating()) {
                mPipTaskOrganizer.scheduleUserResizePip(getBounds(),
                        mPipBoundsState.getAnimatingBoundsState().getTemporaryBounds(), null);
            }
        };
    }

    @NonNull
    @Override
    public Rect getFloatingBoundsOnScreen() {
        return !mPipBoundsState.getAnimatingBoundsState().getAnimatingToBounds().isEmpty()
                ? mPipBoundsState.getAnimatingBoundsState().getAnimatingToBounds() : getBounds();
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
        cancelPhysicsAnimation();
        mPipBoundsState.getAnimatingBoundsState().onAllAnimationsEnded();

        if (mPipTaskOrganizer.isInPip()) {
            mFloatingContentCoordinator.onContentMoved(this);
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
            cancelPhysicsAnimation();

            if (!isDragging) {
                resizePipUnchecked(toBounds);
                mPipBoundsState.setBounds(toBounds);
            } else {
                mPipBoundsState.getAnimatingBoundsState().setTemporaryBounds(toBounds);
                mPipTaskOrganizer.scheduleUserResizePip(getBounds(), toBounds,
                        (Rect newBounds) -> {
                            mMainHandler.post(() -> {
                                mMenuController.updateMenuLayout(newBounds);
                            });
                    });
            }
        } else {
            // If PIP is 'catching up' after being stuck in the dismiss target, update the animation
            // to spring towards the new touch location.
            mTemporaryBoundsPhysicsAnimator
                    .spring(FloatProperties.RECT_WIDTH, getBounds().width(), mSpringConfig)
                    .spring(FloatProperties.RECT_HEIGHT, getBounds().height(), mSpringConfig)
                    .spring(FloatProperties.RECT_X, toBounds.left, mSpringConfig)
                    .spring(FloatProperties.RECT_Y, toBounds.top, mSpringConfig);

            startBoundsAnimator(toBounds.left /* toX */, toBounds.top /* toY */,
                    false /* dismiss */);
        }
    }

    /** Animates the PIP into the dismiss target, scaling it down. */
    void animateIntoDismissTarget(
            MagnetizedObject.MagneticTarget target,
            float velX, float velY,
            boolean flung, Function0<Unit> after) {
        final PointF targetCenter = target.getCenterOnScreen();

        final float desiredWidth = getBounds().width() / 2;
        final float desiredHeight = getBounds().height() / 2;

        final float destinationX = targetCenter.x - (desiredWidth / 2f);
        final float destinationY = targetCenter.y - (desiredHeight / 2f);

        // If we're already in the dismiss target area, then there won't be a move to set the
        // temporary bounds, so just initialize it to the current bounds.
        if (!mPipBoundsState.getAnimatingBoundsState().isAnimating()) {
            mPipBoundsState.getAnimatingBoundsState().setTemporaryBounds(getBounds());
        }
        mTemporaryBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, destinationX, velX, mSpringConfig)
                .spring(FloatProperties.RECT_Y, destinationY, velY, mSpringConfig)
                .spring(FloatProperties.RECT_WIDTH, desiredWidth, mSpringConfig)
                .spring(FloatProperties.RECT_HEIGHT, desiredHeight, mSpringConfig)
                .withEndActions(after);

        startBoundsAnimator(destinationX, destinationY, false);
    }

    /** Set whether we're springing-to-touch to catch up after being stuck in the dismiss target. */
    void setSpringingToTouch(boolean springingToTouch) {
        mSpringingToTouch = springingToTouch;
    }

    /**
     * Resizes the pinned stack back to unknown windowing mode, which could be freeform or
     *      * fullscreen depending on the display area's windowing mode.
     */
    void expandLeavePip() {
        expandLeavePip(false /* skipAnimation */);
    }

    /**
     * Resizes the pinned stack back to unknown windowing mode, which could be freeform or
     * fullscreen depending on the display area's windowing mode.
     */
    void expandLeavePip(boolean skipAnimation) {
        if (DEBUG) {
            Log.d(TAG, "exitPip: skipAnimation=" + skipAnimation
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelPhysicsAnimation();
        mMenuController.hideMenuWithoutResize();
        mPipTaskOrganizer.getUpdateHandler().post(() -> {
            mPipTaskOrganizer.exitPip(skipAnimation
                    ? 0
                    : LEAVE_PIP_DURATION);
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
        cancelPhysicsAnimation();
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
        mFloatingAllowedArea.right += getBounds().width();
        mFloatingAllowedArea.bottom += getBounds().height();
    }

    /**
     * @return the PiP bounds.
     *
     * TODO(b/169373982): can be private, outside callers can use PipBoundsState directly.
     */
    Rect getBounds() {
        return mPipBoundsState.getBounds();
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    void flingToSnapTarget(
            float velocityX, float velocityY, @Nullable Runnable endAction) {
        movetoTarget(velocityX, velocityY, endAction, false /* isStash */);
    }

    /**
     * Stash PiP to the closest edge.
     */
    void stashToEdge(
            float velocityX, float velocityY, @Nullable Runnable endAction) {
        mPipBoundsState.setStashed(velocityX < 0 ? STASH_TYPE_LEFT : STASH_TYPE_RIGHT);
        movetoTarget(velocityX, velocityY, endAction, true /* isStash */);
    }

    private void movetoTarget(
            float velocityX, float velocityY, @Nullable Runnable endAction, boolean isStash) {
        // If we're flinging to a snap target now, we're not springing to catch up to the touch
        // location now.
        mSpringingToTouch = false;

        mTemporaryBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_WIDTH, getBounds().width(), mSpringConfig)
                .spring(FloatProperties.RECT_HEIGHT, getBounds().height(), mSpringConfig)
                .flingThenSpring(
                        FloatProperties.RECT_X, velocityX, isStash ? mStashConfigX : mFlingConfigX,
                        mSpringConfig, true /* flingMustReachMinOrMax */)
                .flingThenSpring(
                        FloatProperties.RECT_Y, velocityY, mFlingConfigY, mSpringConfig)
                .withEndActions(endAction);

        final float leftEdge = isStash
                ? mPipBoundsState.getStashOffset() - mPipBoundsState.getBounds().width()
                : mMovementBounds.left;
        final float rightEdge = isStash
                ?  mPipBoundsState.getDisplayBounds().right - mPipBoundsState.getStashOffset()
                : mMovementBounds.right;

        final float xEndValue = velocityX < 0 ? leftEdge : rightEdge;

        final int startValueY = mPipBoundsState.getAnimatingBoundsState().getTemporaryBounds().top;
        final float estimatedFlingYEndValue =
                PhysicsAnimator.estimateFlingEndValue(startValueY, velocityY, mFlingConfigY);

        startBoundsAnimator(xEndValue /* toX */, estimatedFlingYEndValue /* toY */,
                false /* dismiss */);
    }

    /**
     * Animates PIP to the provided bounds, using physics animations and the given spring
     * configuration
     */
    void animateToBounds(Rect bounds, PhysicsAnimator.SpringConfig springConfig) {
        if (!mTemporaryBoundsPhysicsAnimator.isRunning()) {
            // Animate from the current bounds if we're not already animating.
            mPipBoundsState.getAnimatingBoundsState().setTemporaryBounds(getBounds());
        }

        mTemporaryBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, bounds.left, springConfig)
                .spring(FloatProperties.RECT_Y, bounds.top, springConfig);
        startBoundsAnimator(bounds.left /* toX */, bounds.top /* toY */,
                false /* dismiss */);
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    void animateDismiss() {
        // Animate off the bottom of the screen, then dismiss PIP.
        mTemporaryBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_Y,
                        mMovementBounds.bottom + getBounds().height() * 2,
                        0,
                        mSpringConfig)
                .withEndActions(this::dismissPip);

        startBoundsAnimator(
                getBounds().left /* toX */, getBounds().bottom + getBounds().height() /* toY */,
                true /* dismiss */);

        mDismissalPending = false;
    }

    /**
     * Animates the PiP to the expanded state to show the menu.
     */
    float animateToExpandedState(Rect expandedBounds, Rect movementBounds,
            Rect expandedMovementBounds, Runnable callback) {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(getBounds()),
                movementBounds);
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
            savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(getBounds()),
                    currentMovementBounds, mPipBoundsState.getStashedState());
        }

        mSnapAlgorithm.applySnapFraction(normalBounds, normalMovementBounds, savedSnapFraction,
                mPipBoundsState.getStashedState(), mPipBoundsState.getStashOffset(),
                mPipBoundsState.getDisplayBounds());

        if (immediate) {
            movePip(normalBounds);
        } else {
            resizeAndAnimatePipUnchecked(normalBounds, SHRINK_STACK_FROM_MENU_DURATION);
        }
    }

    /**
     * Animates the PiP to offset it from the IME or shelf.
     */
    @VisibleForTesting
    public void animateToOffset(Rect originalBounds, int offset) {
        if (DEBUG) {
            Log.d(TAG, "animateToOffset: originalBounds=" + originalBounds + " offset=" + offset
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelPhysicsAnimation();
        mPipTaskOrganizer.scheduleOffsetPip(originalBounds, offset, SHIFT_DURATION,
                mUpdateBoundsCallback);
    }

    /**
     * Cancels all existing animations.
     */
    private void cancelPhysicsAnimation() {
        mTemporaryBoundsPhysicsAnimator.cancel();
        mPipBoundsState.getAnimatingBoundsState().onPhysicsAnimationEnded();
        mSpringingToTouch = false;
    }

    /** Set new fling configs whose min/max values respect the given movement bounds. */
    private void rebuildFlingConfigs() {
        mFlingConfigX = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, mMovementBounds.left, mMovementBounds.right);
        mFlingConfigY = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION, mMovementBounds.top, mMovementBounds.bottom);
        mStashConfigX = new PhysicsAnimator.FlingConfig(
                DEFAULT_FRICTION,
                mPipBoundsState.getStashOffset() - mPipBoundsState.getBounds().width(),
                mPipBoundsState.getDisplayBounds().right - mPipBoundsState.getStashOffset());
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
            cancelPhysicsAnimation();
        }

        setAnimatingToBounds(new Rect(
                (int) toX,
                (int) toY,
                (int) toX + getBounds().width(),
                (int) toY + getBounds().height()));

        if (!mTemporaryBoundsPhysicsAnimator.isRunning()) {
            mTemporaryBoundsPhysicsAnimator
                    .addUpdateListener(mResizePipUpdateListener)
                    .withEndActions(this::onBoundsPhysicsAnimationEnd);
        }

        mTemporaryBoundsPhysicsAnimator.start();
    }

    /**
     * Notify that PIP was released in the dismiss target and will be animated out and dismissed
     * shortly.
     */
    void notifyDismissalPending() {
        mDismissalPending = true;
    }

    private void onBoundsPhysicsAnimationEnd() {
        // The physics animation ended, though we may not necessarily be done animating, such as
        // when we're still dragging after moving out of the magnetic target.
        if (!mDismissalPending
                && !mSpringingToTouch
                && !mMagnetizedPip.getObjectStuckToTarget()) {
            // All animations (including dragging) have actually finished.
            mPipBoundsState.setBounds(
                    mPipBoundsState.getAnimatingBoundsState().getTemporaryBounds());
            mPipBoundsState.getAnimatingBoundsState().onAllAnimationsEnded();
            if (!mDismissalPending) {
                // do not schedule resize if PiP is dismissing, which may cause app re-open to
                // mBounds instead of it's normal bounds.
                mPipTaskOrganizer.scheduleFinishResizePip(getBounds());
            }
        }
        mPipBoundsState.getAnimatingBoundsState().onPhysicsAnimationEnded();
        mSpringingToTouch = false;
        mDismissalPending = false;
    }

    /**
     * Notifies the floating coordinator that we're moving, and sets the animating to bounds so
     * we return these bounds from
     * {@link FloatingContentCoordinator.FloatingContent#getFloatingBoundsOnScreen()}.
     */
    private void setAnimatingToBounds(Rect bounds) {
        mPipBoundsState.getAnimatingBoundsState().setAnimatingToBounds(bounds);
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
        if (!toBounds.equals(getBounds())) {
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

        // Intentionally resize here even if the current bounds match the destination bounds.
        // This is so all the proper callbacks are performed.
        mPipTaskOrganizer.scheduleAnimateResizePip(toBounds, duration, mUpdateBoundsCallback);
        setAnimatingToBounds(toBounds);
    }

    /**
     * Returns a MagnetizedObject wrapper for PIP's animated bounds. This is provided to the
     * magnetic dismiss target so it can calculate PIP's size and position.
     */
    MagnetizedObject<Rect> getMagnetizedPip() {
        if (mMagnetizedPip == null) {
            mMagnetizedPip = new MagnetizedObject<Rect>(
                    mContext, mPipBoundsState.getAnimatingBoundsState().getTemporaryBounds(),
                    FloatProperties.RECT_X, FloatProperties.RECT_Y) {
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

        return mMagnetizedPip;
    }
}
