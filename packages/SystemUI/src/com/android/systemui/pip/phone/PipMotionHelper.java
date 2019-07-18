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

import static com.android.systemui.Interpolators.FAST_OUT_LINEAR_IN;
import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.systemui.Interpolators.LINEAR_OUT_SLOW_IN;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.animation.Interpolator;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.internal.os.SomeArgs;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.PrintWriter;

/**
 * A helper to animate and manipulate the PiP.
 */
public class PipMotionHelper implements Handler.Callback, PipAppOpsListener.Callback {

    private static final String TAG = "PipMotionHelper";
    private static final boolean DEBUG = false;

    private static final RectEvaluator RECT_EVALUATOR = new RectEvaluator(new Rect());

    private static final int DEFAULT_MOVE_STACK_DURATION = 225;
    private static final int SNAP_STACK_DURATION = 225;
    private static final int DRAG_TO_TARGET_DISMISS_STACK_DURATION = 375;
    private static final int DRAG_TO_DISMISS_STACK_DURATION = 175;
    private static final int SHRINK_STACK_FROM_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_FULLSCREEN_DURATION = 300;
    private static final int MINIMIZE_STACK_MAX_DURATION = 200;
    private static final int SHIFT_DURATION = 300;

    // The fraction of the stack width that the user has to drag offscreen to minimize the PiP
    private static final float MINIMIZE_OFFSCREEN_FRACTION = 0.3f;
    // The fraction of the stack height that the user has to drag offscreen to dismiss the PiP
    private static final float DISMISS_OFFSCREEN_FRACTION = 0.3f;

    private static final int MSG_RESIZE_IMMEDIATE = 1;
    private static final int MSG_RESIZE_ANIMATE = 2;
    private static final int MSG_OFFSET_ANIMATE = 3;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IActivityTaskManager mActivityTaskManager;
    private Handler mHandler;

    private PipMenuActivityController mMenuController;
    private PipSnapAlgorithm mSnapAlgorithm;
    private FlingAnimationUtils mFlingAnimationUtils;
    private AnimationHandler mAnimationHandler;

    private final Rect mBounds = new Rect();
    private final Rect mStableInsets = new Rect();

    private ValueAnimator mBoundsAnimator = null;

    public PipMotionHelper(Context context, IActivityManager activityManager,
            IActivityTaskManager activityTaskManager, PipMenuActivityController menuController,
            PipSnapAlgorithm snapAlgorithm, FlingAnimationUtils flingAnimationUtils) {
        mContext = context;
        mHandler = new Handler(ForegroundThread.get().getLooper(), this);
        mActivityManager = activityManager;
        mActivityTaskManager = activityTaskManager;
        mMenuController = menuController;
        mSnapAlgorithm = snapAlgorithm;
        mFlingAnimationUtils = flingAnimationUtils;
        mAnimationHandler = new AnimationHandler();
        mAnimationHandler.setProvider(new SfVsyncFrameCallbackProvider());
        onConfigurationChanged();
    }

    /**
     * Updates whenever the configuration changes.
     */
    void onConfigurationChanged() {
        mSnapAlgorithm.onConfigurationChanged();
        WindowManagerWrapper.getInstance().getStableInsets(mStableInsets);
    }

    /**
     * Synchronizes the current bounds with the pinned stack.
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
     * Tries to the move the pinned stack to the given {@param bounds}.
     */
    void movePip(Rect toBounds) {
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
        mHandler.post(() -> {
            try {
                mActivityTaskManager.dismissPip(!skipAnimation, EXPAND_STACK_TO_FULLSCREEN_DURATION);
            } catch (RemoteException e) {
                Log.e(TAG, "Error expanding PiP activity", e);
            }
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
        mHandler.post(() -> {
            try {
                mActivityTaskManager.removeStacksInWindowingModes(
                        new int[]{ WINDOWING_MODE_PINNED });
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove PiP", e);
            }
        });
    }

    /**
     * @return the PiP bounds.
     */
    Rect getBounds() {
        return mBounds;
    }

    /**
     * @return the closest minimized PiP bounds.
     */
    Rect getClosestMinimizedBounds(Rect stackBounds, Rect movementBounds) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, stackBounds);
        mSnapAlgorithm.applyMinimizedOffset(toBounds, movementBounds, displaySize, mStableInsets);
        return toBounds;
    }

    /**
     * @return whether the PiP at the current bounds should be minimized.
     */
    boolean shouldMinimizePip() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        if (mBounds.left < 0) {
            float offscreenFraction = (float) -mBounds.left / mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else if (mBounds.right > displaySize.x) {
            float offscreenFraction = (float) (mBounds.right - displaySize.x) /
                    mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else {
            return false;
        }
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
     * Flings the minimized PiP to the closest minimized snap target.
     */
    Rect flingToMinimizedState(float velocityY, Rect movementBounds, Point dragStartPosition) {
        cancelAnimations();
        // We currently only allow flinging the minimized stack up and down, so just lock the
        // movement bounds to the current stack bounds horizontally
        movementBounds = new Rect(mBounds.left, movementBounds.top, mBounds.left,
                movementBounds.bottom);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds,
                0 /* velocityX */, velocityY, dragStartPosition);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, 0, FAST_OUT_SLOW_IN);
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds),
                    velocityY);
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Animates the PiP to the minimized state, slightly offscreen.
     */
    Rect animateToClosestMinimizedState(Rect movementBounds,
            AnimatorUpdateListener updateListener) {
        cancelAnimations();
        Rect toBounds = getClosestMinimizedBounds(mBounds, movementBounds);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds,
                    MINIMIZE_STACK_MAX_DURATION, LINEAR_OUT_SLOW_IN);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    Rect flingToSnapTarget(float velocity, float velocityX, float velocityY, Rect movementBounds,
            AnimatorUpdateListener updateListener, AnimatorListener listener,
            Point startPosition) {
        cancelAnimations();
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds,
                velocityX, velocityY, startPosition);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, 0, FAST_OUT_SLOW_IN);
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds),
                    velocity);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            if (listener != null){
                mBoundsAnimator.addListener(listener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Animates the PiP to the closest snap target.
     */
    Rect animateToClosestSnapTarget(Rect movementBounds, AnimatorUpdateListener updateListener,
            AnimatorListener listener) {
        cancelAnimations();
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, SNAP_STACK_DURATION,
                    FAST_OUT_SLOW_IN);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            if (listener != null){
                mBoundsAnimator.addListener(listener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
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
            Rect normalMovementBounds, Rect currentMovementBounds, boolean minimized,
            boolean immediate) {
        if (savedSnapFraction < 0f) {
            // If there are no saved snap fractions, then just use the current bounds
            savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds),
                    currentMovementBounds);
        }
        mSnapAlgorithm.applySnapFraction(normalBounds, normalMovementBounds, savedSnapFraction);
        if (minimized) {
            normalBounds = getClosestMinimizedBounds(normalBounds, normalMovementBounds);
        }
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
        cancelAnimations();
        adjustAndAnimatePipOffset(originalBounds, offset, SHIFT_DURATION);
    }

    private void adjustAndAnimatePipOffset(Rect originalBounds, int offset, int duration) {
        if (offset == 0) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = originalBounds;
        args.argi1 = offset;
        args.argi2 = duration;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_OFFSET_ANIMATE, args));
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    Rect animateDismiss(Rect pipBounds, float velocityX, float velocityY,
            AnimatorUpdateListener listener) {
        cancelAnimations();
        final float velocity = PointF.length(velocityX, velocityY);
        final boolean isFling = velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond();
        Point p = getDismissEndPoint(pipBounds, velocityX, velocityY, isFling);
        Rect toBounds = new Rect(pipBounds);
        toBounds.offsetTo(p.x, p.y);
        mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, DRAG_TO_DISMISS_STACK_DURATION,
                FAST_OUT_LINEAR_IN);
        mBoundsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dismissPip();
            }
        });
        if (isFling) {
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds), velocity);
        }
        if (listener != null) {
            mBoundsAnimator.addUpdateListener(listener);
        }
        mBoundsAnimator.start();
        return toBounds;
    }

    /**
     * Cancels all existing animations.
     */
    void cancelAnimations() {
        if (mBoundsAnimator != null) {
            mBoundsAnimator.cancel();
            mBoundsAnimator = null;
        }
    }

    /**
     * Creates an animation to move the PiP to give given {@param toBounds}.
     */
    private ValueAnimator createAnimationToBounds(Rect fromBounds, Rect toBounds, int duration,
            Interpolator interpolator) {
        ValueAnimator anim = new ValueAnimator() {
            @Override
            public AnimationHandler getAnimationHandler() {
                return mAnimationHandler;
            }
        };
        anim.setObjectValues(fromBounds, toBounds);
        anim.setEvaluator(RECT_EVALUATOR);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        anim.addUpdateListener((ValueAnimator animation) -> {
            resizePipUnchecked((Rect) animation.getAnimatedValue());
        });
        return anim;
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
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = toBounds;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RESIZE_IMMEDIATE, args));
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
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = toBounds;
            args.argi1 = duration;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RESIZE_ANIMATE, args));
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

    /**
     * @return the distance between points {@param p1} and {@param p2}.
     */
    private float distanceBetweenRectOffsets(Rect r1, Rect r2) {
        return PointF.length(r1.left - r2.left, r1.top - r2.top);
    }

    /**
     * Handles messages to be processed on the background thread.
     */
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RESIZE_IMMEDIATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect toBounds = (Rect) args.arg1;
                try {
                    mActivityTaskManager.resizePinnedStack(
                            toBounds, null /* tempPinnedTaskBounds */);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not resize pinned stack to bounds: " + toBounds, e);
                }
                return true;
            }

            case MSG_RESIZE_ANIMATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect toBounds = (Rect) args.arg1;
                int duration = args.argi1;
                try {
                    StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                            WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                    if (stackInfo == null) {
                        // In the case where we've already re-expanded or dismissed the PiP, then
                        // just skip the resize
                        return true;
                    }

                    mActivityTaskManager.animateResizePinnedStack(stackInfo.stackId, toBounds,
                            duration);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not animate resize pinned stack to bounds: " + toBounds, e);
                }
                return true;
            }

            case MSG_OFFSET_ANIMATE: {
                SomeArgs args = (SomeArgs) msg.obj;
                Rect originalBounds = (Rect) args.arg1;
                final int offset = args.argi1;
                final int duration = args.argi2;
                try {
                    StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                            WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                    if (stackInfo == null) {
                        // In the case where we've already re-expanded or dismissed the PiP, then
                        // just skip the resize
                        return true;
                    }

                    mActivityTaskManager.offsetPinnedStackBounds(stackInfo.stackId, originalBounds,
                            0/* xOffset */, offset, duration);
                    Rect toBounds = new Rect(originalBounds);
                    toBounds.offset(0, offset);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not animate offset pinned stack with offset: " + offset, e);
                }
                return true;
            }

            default:
                return false;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mStableInsets=" + mStableInsets);
    }
}
