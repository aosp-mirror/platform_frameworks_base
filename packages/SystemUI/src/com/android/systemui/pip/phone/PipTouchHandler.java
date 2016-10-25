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

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import static com.android.systemui.Interpolators.FAST_OUT_LINEAR_IN;
import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.systemui.recents.misc.Utilities.RECT_EVALUATOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import com.android.internal.os.BackgroundThread;
import com.android.systemui.statusbar.FlingAnimationUtils;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    private static final String TAG = "PipTouchHandler";
    private static final boolean DEBUG_ALLOW_OUT_OF_BOUNDS_STACK = false;

    private static final int SNAP_STACK_DURATION = 225;
    private static final int DISMISS_STACK_DURATION = 375;
    private static final int EXPAND_STACK_DURATION = 225;

    private static final float SCROLL_FRICTION_MULTIPLIER = 8f;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final ViewConfiguration mViewConfig;
    private final InputChannel mInputChannel = new InputChannel();

    private final PipInputEventReceiver mInputEventReceiver;
    private final PipDismissViewController mDismissViewController;

    private final Rect mPinnedStackBounds = new Rect();
    private final Rect mBoundedPinnedStackBounds = new Rect();
    private ValueAnimator mPinnedStackBoundsAnimator = null;

    private final PointF mDownTouch = new PointF();
    private final PointF mLastTouch = new PointF();
    private boolean mIsDragging;
    private int mActivePointerId;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final Scroller mScroller;
    private VelocityTracker mVelocityTracker;

    /**
     * Input handler used for Pip windows.
     */
    private final class PipInputEventReceiver extends InputEventReceiver {
        public PipInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = true;
            try {
                // To be implemented for input handling over Pip windows
                if (event instanceof MotionEvent) {
                    MotionEvent ev = (MotionEvent) event;
                    handleTouchEvent(ev);
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    public PipTouchHandler(Context context, IActivityManager activityManager,
            IWindowManager windowManager) {

        // Initialize the Pip input consumer
        try {
            windowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
            windowManager.createInputConsumer(INPUT_CONSUMER_PIP, mInputChannel);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to create PIP input consumer", e);
        }
        mContext = context;
        mActivityManager = activityManager;
        mViewConfig = ViewConfiguration.get(context);
        mInputEventReceiver = new PipInputEventReceiver(mInputChannel, Looper.myLooper());
        mDismissViewController = new PipDismissViewController(context);
        mScroller = new Scroller(context);
        mScroller.setFriction(mViewConfig.getScrollFriction() * SCROLL_FRICTION_MULTIPLIER);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 2f);
    }

    public void onConfigurationChanged() {
        updateBoundedPinnedStackBounds();
    }

    private void handleTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // Cancel any existing animations on the pinned stack
                if (mPinnedStackBoundsAnimator != null) {
                    mPinnedStackBoundsAnimator.cancel();
                }

                updateBoundedPinnedStackBounds();
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                mActivePointerId = ev.getPointerId(0);
                mLastTouch.set(ev.getX(), ev.getY());
                mDownTouch.set(mLastTouch);
                mIsDragging = false;
                // TODO: Consider setting a timer such at after X time, we show the dismiss target
                //       if the user hasn't already dragged some distance
                mDismissViewController.createDismissTarget();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                if (!mIsDragging) {
                    // Check if the pointer has moved far enough
                    float movement = PointF.length(mDownTouch.x - ev.getX(activePointerIndex),
                            mDownTouch.y - ev.getY(activePointerIndex));
                    if (movement > mViewConfig.getScaledTouchSlop()) {
                        mIsDragging = true;
                        mDismissViewController.showDismissTarget();
                    }
                }

                if (mIsDragging) {
                    // Move the pinned stack
                    float dx = ev.getX(activePointerIndex) - mLastTouch.x;
                    float dy = ev.getY(activePointerIndex) - mLastTouch.y;
                    float left = Math.max(mBoundedPinnedStackBounds.left, Math.min(
                            mBoundedPinnedStackBounds.right, mPinnedStackBounds.left + dx));
                    float top = Math.max(mBoundedPinnedStackBounds.top, Math.min(
                            mBoundedPinnedStackBounds.bottom, mPinnedStackBounds.top + dy));
                    if (DEBUG_ALLOW_OUT_OF_BOUNDS_STACK) {
                        left = mPinnedStackBounds.left + dx;
                        top = mPinnedStackBounds.top + dy;
                    }
                    movePinnedStack(left, top);
                }
                mLastTouch.set(ev.getX(), ev.getY());
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);

                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the movement state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                    mLastTouch.set(ev.getX(newPointerIndex), ev.getY(newPointerIndex));
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the velocity tracker
                mVelocityTracker.addMovement(ev);

                if (mIsDragging) {
                    mVelocityTracker.computeCurrentVelocity(1000,
                            ViewConfiguration.get(mContext).getScaledMaximumFlingVelocity());
                    float velocityX = mVelocityTracker.getXVelocity();
                    float velocityY = mVelocityTracker.getYVelocity();
                    float velocity = PointF.length(velocityX, velocityY);
                    if (velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                        flingToSnapTarget(velocity, velocityX, velocityY);
                    } else {
                        int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                        int x = (int) ev.getX(activePointerIndex);
                        int y = (int) ev.getY(activePointerIndex);
                        Rect dismissBounds = mDismissViewController.getDismissBounds();
                        if (dismissBounds.contains(x, y)) {
                            animateDismissPinnedStack(dismissBounds);
                        } else {
                            animateToSnapTarget();
                        }
                    }
                } else {
                    expandPinnedStackToFullscreen();
                }
                mDismissViewController.destroyDismissTarget();

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                mIsDragging = false;
                recycleVelocityTracker();
                break;
            }
        }
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * Creates an animation that continues the fling to a snap target.
     */
    private void flingToSnapTarget(float velocity, float velocityX, float velocityY) {
        mScroller.fling(mPinnedStackBounds.left, mPinnedStackBounds.top,
            (int) velocityX, (int) velocityY,
            mBoundedPinnedStackBounds.left, mBoundedPinnedStackBounds.right,
            mBoundedPinnedStackBounds.top, mBoundedPinnedStackBounds.bottom);
        Rect toBounds = findClosestBoundedPinnedStackSnapTarget(
            mScroller.getFinalX(), mScroller.getFinalY());
        mScroller.abortAnimation();
        if (!mPinnedStackBounds.equals(toBounds)) {
            mPinnedStackBoundsAnimator = createResizePinnedStackAnimation(
                toBounds, 0, FAST_OUT_SLOW_IN);
            mFlingAnimationUtils.apply(mPinnedStackBoundsAnimator, 0,
                distanceBetweenRectOffsets(mPinnedStackBounds, toBounds),
                velocity);
            mPinnedStackBoundsAnimator.start();
        }
    }

    /**
     * Animates the pinned stack to the closest snap target.
     */
    private void animateToSnapTarget() {
        Rect toBounds = findClosestBoundedPinnedStackSnapTarget(
            mPinnedStackBounds.left, mPinnedStackBounds.top);
        if (!mPinnedStackBounds.equals(toBounds)) {
            mPinnedStackBoundsAnimator = createResizePinnedStackAnimation(
                toBounds, SNAP_STACK_DURATION, FAST_OUT_SLOW_IN);
            mPinnedStackBoundsAnimator.start();
        }
    }

    /**
     * Animates the dismissal of the pinned stack into the given bounds.
     */
    private void animateDismissPinnedStack(Rect dismissBounds) {
        Rect toBounds = new Rect(dismissBounds.centerX(),
            dismissBounds.centerY(),
            dismissBounds.centerX() + 1,
            dismissBounds.centerY() + 1);
        mPinnedStackBoundsAnimator = createResizePinnedStackAnimation(
            toBounds, DISMISS_STACK_DURATION, FAST_OUT_LINEAR_IN);
        mPinnedStackBoundsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                BackgroundThread.getHandler().post(() -> {
                    try {
                        mActivityManager.removeStack(PINNED_STACK_ID);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to remove PIP", e);
                    }
                });
            }
        });
        mPinnedStackBoundsAnimator.start();
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    private void expandPinnedStackToFullscreen() {
        BackgroundThread.getHandler().post(() -> {
            try {
                mActivityManager.resizeStack(PINNED_STACK_ID, null /* bounds */,
                        true /* allowResizeInDockedMode */, true /* preserveWindows */,
                        true /* animate */, EXPAND_STACK_DURATION);
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu activity", e);
            }
        });
    }

    /**
     * Updates the movement bounds of the pinned stack.
     */
    private void updateBoundedPinnedStackBounds() {
        try {
            StackInfo info = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (info != null) {
                mPinnedStackBounds.set(info.bounds);
                mBoundedPinnedStackBounds.set(mActivityManager.getPictureInPictureMovementBounds(
                        Display.DEFAULT_DISPLAY));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch PIP movement bounds.", e);
        }
    }

    /**
     * Moves the pinned stack to the given {@param left} and {@param top} offsets.
     */
    private void movePinnedStack(float left, float top) {
        if ((int) left != mPinnedStackBounds.left || (int) top != mPinnedStackBounds.top) {
            mPinnedStackBounds.offsetTo((int) left, (int) top);
            BackgroundThread.getHandler().post(() -> {
                try {
                    mActivityManager.resizePinnedStack(mPinnedStackBounds,
                            null /* tempPinnedBounds */);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not move pinned stack to offset: (" + left + ", " + top + ")",
                            e);
                }
            });
        }
    }

    /**
     * Resizes the pinned stack to the given {@param bounds}.
     */
    private void resizePinnedStack(Rect bounds) {
        if (!mPinnedStackBounds.equals(bounds)) {
            mPinnedStackBounds.set(bounds);
            BackgroundThread.getHandler().post(() -> {
                try {
                    mActivityManager.resizePinnedStack(bounds, null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not resize pinned stack to bounds: (" + bounds + ")");
                }
            });
        }
    }

    /**
     * Creates a resize-stack animation.
     */
    private ValueAnimator createResizePinnedStackAnimation(Rect toBounds, int duration,
            Interpolator interpolator) {
        ValueAnimator anim = ValueAnimator.ofObject(RECT_EVALUATOR,
                mPinnedStackBounds, toBounds);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        anim.addUpdateListener(
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        resizePinnedStack((Rect) animation.getAnimatedValue());
                    }
                });
        return anim;
    }

    /**
     * @return the closest absolute bounded stack left/top to the given {@param x} and {@param y}.
     */
    private Rect findClosestBoundedPinnedStackSnapTarget(int x, int y) {
        Point[] snapTargets;
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            snapTargets = new Point[] {
                new Point(mBoundedPinnedStackBounds.left, mBoundedPinnedStackBounds.top),
                new Point(mBoundedPinnedStackBounds.right, mBoundedPinnedStackBounds.top),
                new Point(mBoundedPinnedStackBounds.left, mBoundedPinnedStackBounds.bottom),
                new Point(mBoundedPinnedStackBounds.right, mBoundedPinnedStackBounds.bottom)
            };
        } else {
            snapTargets = new Point[] {
                new Point(mBoundedPinnedStackBounds.left, mBoundedPinnedStackBounds.top),
                new Point(mBoundedPinnedStackBounds.right, mBoundedPinnedStackBounds.top),
                new Point(mBoundedPinnedStackBounds.left, mBoundedPinnedStackBounds.bottom),
                new Point(mBoundedPinnedStackBounds.right, mBoundedPinnedStackBounds.bottom)
            };
        }

        Point closestSnapTarget = null;
        float minDistance = Float.MAX_VALUE;
        for (Point p : snapTargets) {
            float distance = distanceToPoint(p, x, y);
            if (distance < minDistance) {
                closestSnapTarget = p;
                minDistance = distance;
            }
        }

        Rect toBounds = new Rect(mPinnedStackBounds);
        toBounds.offsetTo(closestSnapTarget.x, closestSnapTarget.y);
        return toBounds;
    }

    /**
     * @return the distance between point {@param p} and the given {@param x} and {@param y}.
     */
    private float distanceToPoint(Point p, int x, int y) {
        return PointF.length(p.x - x, p.y - y);
    }


    /**
     * @return the distance between points {@param p1} and {@param p2}.
     */
    private float distanceBetweenRectOffsets(Rect r1, Rect r2) {
        return PointF.length(r1.left - r2.left, r1.top - r2.top);
    }
}
