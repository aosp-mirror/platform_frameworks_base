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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Debug;
import android.os.RemoteException;
import android.util.Log;

import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.animation.FloatProperties;
import com.android.systemui.util.animation.PhysicsAnimator;

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

    // The fraction of the stack height that the user has to drag offscreen to dismiss the PiP
    private static final float DISMISS_OFFSCREEN_FRACTION = 0.3f;

    private final Context mContext;
    private final IActivityTaskManager mActivityTaskManager;
    private final PipTaskOrganizer mPipTaskOrganizer;

    private PipMenuActivityController mMenuController;
    private PipSnapAlgorithm mSnapAlgorithm;
    private FlingAnimationUtils mFlingAnimationUtils;

    private final Rect mStableInsets = new Rect();

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
    private final PhysicsAnimator.UpdateListener<Rect> mResizePipUpdateListener =
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

    public PipMotionHelper(Context context, IActivityTaskManager activityTaskManager,
            PipTaskOrganizer pipTaskOrganizer, PipMenuActivityController menuController,
            PipSnapAlgorithm snapAlgorithm, FlingAnimationUtils flingAnimationUtils,
            FloatingContentCoordinator floatingContentCoordinator) {
        mContext = context;
        mActivityTaskManager = activityTaskManager;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMenuController = menuController;
        mSnapAlgorithm = snapAlgorithm;
        mFlingAnimationUtils = flingAnimationUtils;
        mFloatingContentCoordinator = floatingContentCoordinator;
        onConfigurationChanged();
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
     * Updates whenever the configuration changes.
     */
    void onConfigurationChanged() {
        mSnapAlgorithm.onConfigurationChanged();
        WindowManagerWrapper.getInstance().getStableInsets(mStableInsets);
    }

    /**
     * Synchronizes the current bounds with the pinned stack, cancelling any ongoing animations.
     */
    void synchronizePinnedStackBounds() {
        cancelAnimations();
        try {
            StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (stackInfo != null) {
                mBounds.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get pinned stack bounds");
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

        cancelAnimations();
        resizePipUnchecked(toBounds);
        mBounds.set(toBounds);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPip() {
        expandPip(false /* skipAnimation */);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPip(boolean skipAnimation) {
        if (DEBUG) {
            Log.d(TAG, "expandPip: skipAnimation=" + skipAnimation
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mPipTaskOrganizer.getUpdateHandler().post(() -> {
            mPipTaskOrganizer.dismissPip(skipAnimation ? 0 : EXPAND_STACK_TO_FULLSCREEN_DURATION);
        });
    }

    /**
     * Dismisses the pinned stack.
     */
    @Override
    public void dismissPip() {
        if (DEBUG) {
            Log.d(TAG, "dismissPip: callers=\n" + Debug.getCallers(5, "    "));
        }
        cancelAnimations();
        mMenuController.hideMenuWithoutResize();
        mPipTaskOrganizer.getUpdateHandler().post(() -> {
            try {
                mActivityTaskManager.removeStacksInWindowingModes(
                        new int[]{ WINDOWING_MODE_PINNED });
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove PiP", e);
            }
        });
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
     * @return whether the PiP at the current bounds should be dismissed.
     */
    boolean shouldDismissPip() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        final int y = displaySize.y - mStableInsets.bottom;
        if (mBounds.bottom > y) {
            float offscreenFraction = (float) (mBounds.bottom - y) / mBounds.height();
            return offscreenFraction >= DISMISS_OFFSCREEN_FRACTION;
        }
        return false;
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    void flingToSnapTarget(
            float velocityX, float velocityY, Runnable updateAction, @Nullable Runnable endAction) {
        mAnimatedBounds.set(mBounds);
        mAnimatedBoundsPhysicsAnimator
                .flingThenSpring(
                        FloatProperties.RECT_X, velocityX, mFlingConfigX, mSpringConfig,
                        true /* flingMustReachMinOrMax */)
                .flingThenSpring(
                        FloatProperties.RECT_Y, velocityY, mFlingConfigY, mSpringConfig)
                .addUpdateListener((target, values) -> updateAction.run())
                .withEndActions(endAction);

        final float xEndValue = velocityX < 0 ? mMovementBounds.left : mMovementBounds.right;
        final float estimatedFlingYEndValue =
                PhysicsAnimator.estimateFlingEndValue(mBounds.top, velocityY, mFlingConfigY);

        startBoundsAnimator(xEndValue /* toX */, estimatedFlingYEndValue /* toY */);
    }

    /**
     * Animates the PiP to the closest snap target.
     */
    void animateToClosestSnapTarget() {
        final Rect newBounds = new Rect();
        mSnapAlgorithm.snapRectToClosestEdge(mBounds, mMovementBounds, newBounds);
        animateToBounds(newBounds, mSpringConfig);
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
        startBoundsAnimator(bounds.left /* toX */, bounds.top /* toY */);
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    void animateDismiss(float velocityX, float velocityY, @Nullable Runnable updateAction) {
        final float velocity = PointF.length(velocityX, velocityY);
        final boolean isFling = velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond();
        final Point dismissEndPoint = getDismissEndPoint(mBounds, velocityX, velocityY, isFling);

        mAnimatedBounds.set(mBounds);

        // Animate to the dismiss end point, and then dismiss PIP.
        mAnimatedBoundsPhysicsAnimator
                .spring(FloatProperties.RECT_X, dismissEndPoint.x, velocityX, mSpringConfig)
                .spring(FloatProperties.RECT_Y, dismissEndPoint.y, velocityY, mSpringConfig)
                .withEndActions(this::dismissPip);

        // If we were provided with an update action, run it whenever there's an update.
        if (updateAction != null) {
            mAnimatedBoundsPhysicsAnimator.addUpdateListener(
                    (target, values) -> updateAction.run());
        }

        startBoundsAnimator(dismissEndPoint.x /* toX */, dismissEndPoint.y /* toY */);
    }

    /**
     * Animates the PiP to the expanded state to show the menu.
     */
    float animateToExpandedState(Rect expandedBounds, Rect movementBounds,
            Rect expandedMovementBounds) {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds), movementBounds);
        mSnapAlgorithm.applySnapFraction(expandedBounds, expandedMovementBounds, savedSnapFraction);
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
    private void startBoundsAnimator(float toX, float toY) {
        cancelAnimations();

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
                    mPipTaskOrganizer.scheduleFinishResizePip(mAnimatedBounds);
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
     * @return the coordinates the PIP should animate to based on the direction of velocity when
     *         dismissing.
     */
    private Point getDismissEndPoint(Rect pipBounds, float velX, float velY, boolean isFling) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        final float bottomBound = displaySize.y + pipBounds.height() * .1f;
        if (isFling && velX != 0 && velY != 0) {
            // Line is defined by: y = mx + b, m = slope, b = y-intercept
            // Find the slope
            final float slope = velY / velX;
            // Sub in slope and PiP position to solve for y-intercept: b = y - mx
            final float yIntercept = pipBounds.top - slope * pipBounds.left;
            // Now find the point on this line when y = bottom bound: x = (y - b) / m
            final float x = (bottomBound - yIntercept) / slope;
            return new Point((int) x, (int) bottomBound);
        } else {
            // If it wasn't a fling the velocity on 'up' is not reliable for direction of movement,
            // just animate downwards.
            return new Point(pipBounds.left, (int) bottomBound);
        }
    }

    /**
     * @return whether the gesture it towards the dismiss area based on the velocity when
     *         dismissing.
     */
    public boolean isGestureToDismissArea(Rect pipBounds, float velX, float velY,
            boolean isFling) {
        Point endpoint = getDismissEndPoint(pipBounds, velX, velY, isFling);
        // Center the point
        endpoint.x += pipBounds.width() / 2;
        endpoint.y += pipBounds.height() / 2;

        // The dismiss area is the middle third of the screen, half the PIP's height from the bottom
        Point size = new Point();
        mContext.getDisplay().getRealSize(size);
        final int left = size.x / 3;
        Rect dismissArea = new Rect(left, size.y - (pipBounds.height() / 2), left * 2,
                size.y + pipBounds.height());
        return dismissArea.contains(endpoint.x, endpoint.y);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mStableInsets=" + mStableInsets);
    }
}
