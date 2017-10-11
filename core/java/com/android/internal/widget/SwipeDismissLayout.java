/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.widget;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ReceiverCallNotAllowedException;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Special layout that finishes its activity when swiped away.
 */
public class SwipeDismissLayout extends FrameLayout {
    private static final String TAG = "SwipeDismissLayout";

    private static final float MAX_DIST_THRESHOLD = .33f;
    private static final float MIN_DIST_THRESHOLD = .1f;

    public interface OnDismissedListener {
        void onDismissed(SwipeDismissLayout layout);
    }

    public interface OnSwipeProgressChangedListener {
        /**
         * Called when the layout has been swiped and the position of the window should change.
         *
         * @param alpha A number in [0, 1] representing what the alpha transparency of the window
         * should be.
         * @param translate A number in [0, w], where w is the width of the
         * layout. This is equivalent to progress * layout.getWidth().
         */
        void onSwipeProgressChanged(SwipeDismissLayout layout, float alpha, float translate);

        void onSwipeCancelled(SwipeDismissLayout layout);
    }

    private boolean mIsWindowNativelyTranslucent;

    // Cached ViewConfiguration and system-wide constant values
    private int mSlop;
    private int mMinFlingVelocity;

    // Transient properties
    private int mActiveTouchId;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private boolean mSwiping;
    private boolean mDismissed;
    private boolean mDiscardIntercept;
    private VelocityTracker mVelocityTracker;
    private boolean mBlockGesture = false;
    private boolean mActivityTranslucencyConverted = false;

    private final DismissAnimator mDismissAnimator = new DismissAnimator();

    private OnDismissedListener mDismissedListener;
    private OnSwipeProgressChangedListener mProgressListener;
    private BroadcastReceiver mScreenOffReceiver;
    private IntentFilter mScreenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);


    private boolean mDismissable = true;

    public SwipeDismissLayout(Context context) {
        super(context);
        init(context);
    }

    public SwipeDismissLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeDismissLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        ViewConfiguration vc = ViewConfiguration.get(context);
        mSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        TypedArray a = context.getTheme().obtainStyledAttributes(
                com.android.internal.R.styleable.Theme);
        mIsWindowNativelyTranslucent = a.getBoolean(
                com.android.internal.R.styleable.Window_windowIsTranslucent, false);
        a.recycle();
    }

    public void setOnDismissedListener(OnDismissedListener listener) {
        mDismissedListener = listener;
    }

    public void setOnSwipeProgressChangedListener(OnSwipeProgressChangedListener listener) {
        mProgressListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            mScreenOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    post(() -> {
                        if (mDismissed) {
                            dismiss();
                        } else {
                            cancel();
                        }
                        resetMembers();
                    });
                }
            };
            getContext().registerReceiver(mScreenOffReceiver, mScreenOffFilter);
        } catch (ReceiverCallNotAllowedException e) {
            /* Exception is thrown if the context is a ReceiverRestrictedContext object. As
             * ReceiverRestrictedContext is not public, the context type cannot be checked before
             * calling registerReceiver. The most likely scenario in which the exception would be
             * thrown would be when a BroadcastReceiver creates a dialog to show the user. */
            mScreenOffReceiver = null; // clear receiver since it was not used.
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mScreenOffReceiver != null) {
            getContext().unregisterReceiver(mScreenOffReceiver);
            mScreenOffReceiver = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        checkGesture((ev));
        if (mBlockGesture) {
            return true;
        }
        if (!mDismissable) {
            return super.onInterceptTouchEvent(ev);
        }

        // Offset because the view is translated during swipe, match X with raw X. Active touch
        // coordinates are mostly used by the velocity tracker, so offset it to match the raw
        // coordinates which is what is primarily used elsewhere.
        ev.offsetLocation(ev.getRawX() - ev.getX(), 0);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetMembers();
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                mActiveTouchId = ev.getPointerId(0);
                mVelocityTracker = VelocityTracker.obtain("int1");
                mVelocityTracker.addMovement(ev);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                int actionIndex = ev.getActionIndex();
                mActiveTouchId = ev.getPointerId(actionIndex);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                actionIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(actionIndex);
                if (pointerId == mActiveTouchId) {
                    // This was our active pointer going up. Choose a new active pointer.
                    int newActionIndex = actionIndex == 0 ? 1 : 0;
                    mActiveTouchId = ev.getPointerId(newActionIndex);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                resetMembers();
                break;

            case MotionEvent.ACTION_MOVE:
                if (mVelocityTracker == null || mDiscardIntercept) {
                    break;
                }

                int pointerIndex = ev.findPointerIndex(mActiveTouchId);
                if (pointerIndex == -1) {
                    Log.e(TAG, "Invalid pointer index: ignoring.");
                    mDiscardIntercept = true;
                    break;
                }
                float dx = ev.getRawX() - mDownX;
                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                if (dx != 0 && canScroll(this, false, dx, x, y)) {
                    mDiscardIntercept = true;
                    break;
                }
                updateSwiping(ev);
                break;
        }

        return !mDiscardIntercept && mSwiping;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        checkGesture((ev));
        if (mBlockGesture) {
            return true;
        }
        if (mVelocityTracker == null || !mDismissable) {
            return super.onTouchEvent(ev);
        }

        // Offset because the view is translated during swipe, match X with raw X. Active touch
        // coordinates are mostly used by the velocity tracker, so offset it to match the raw
        // coordinates which is what is primarily used elsewhere.
        ev.offsetLocation(ev.getRawX() - ev.getX(), 0);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_UP:
                updateDismiss(ev);
                if (mDismissed) {
                    mDismissAnimator.animateDismissal(ev.getRawX() - mDownX);
                } else if (mSwiping
                        // Only trigger animation if we had a MOVE event that would shift the
                        // underlying view, otherwise the animation would be janky.
                        && mLastX != Integer.MIN_VALUE) {
                    mDismissAnimator.animateRecovery(ev.getRawX() - mDownX);
                }
                resetMembers();
                break;

            case MotionEvent.ACTION_CANCEL:
                cancel();
                resetMembers();
                break;

            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(ev);
                mLastX = ev.getRawX();
                updateSwiping(ev);
                if (mSwiping) {
                    setProgress(ev.getRawX() - mDownX);
                    break;
                }
        }
        return true;
    }

    private void setProgress(float deltaX) {
        if (mProgressListener != null && deltaX >= 0)  {
            mProgressListener.onSwipeProgressChanged(
                    this, progressToAlpha(deltaX / getWidth()), deltaX);
        }
    }

    private void dismiss() {
        if (mDismissedListener != null) {
            mDismissedListener.onDismissed(this);
        }
    }

    protected void cancel() {
        if (!mIsWindowNativelyTranslucent) {
            Activity activity = findActivity();
            if (activity != null && mActivityTranslucencyConverted) {
                activity.convertFromTranslucent();
                mActivityTranslucencyConverted = false;
            }
        }
        if (mProgressListener != null) {
            mProgressListener.onSwipeCancelled(this);
        }
    }

    /**
     * Resets internal members when canceling.
     */
    private void resetMembers() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = null;
        mDownX = 0;
        mLastX = Integer.MIN_VALUE;
        mDownY = 0;
        mSwiping = false;
        mDismissed = false;
        mDiscardIntercept = false;
    }

    private void updateSwiping(MotionEvent ev) {
        boolean oldSwiping = mSwiping;
        if (!mSwiping) {
            float deltaX = ev.getRawX() - mDownX;
            float deltaY = ev.getRawY() - mDownY;
            if ((deltaX * deltaX) + (deltaY * deltaY) > mSlop * mSlop) {
                mSwiping = deltaX > mSlop * 2 && Math.abs(deltaY) < Math.abs(deltaX);
            } else {
                mSwiping = false;
            }
        }

        if (mSwiping && !oldSwiping) {
            // Swiping has started
            if (!mIsWindowNativelyTranslucent) {
                Activity activity = findActivity();
                if (activity != null) {
                    mActivityTranslucencyConverted = activity.convertToTranslucent(null, null);
                }
            }
        }
    }

    private void updateDismiss(MotionEvent ev) {
        float deltaX = ev.getRawX() - mDownX;
        // Don't add the motion event as an UP event would clear the velocity tracker
        mVelocityTracker.computeCurrentVelocity(1000);
        float xVelocity = mVelocityTracker.getXVelocity();
        if (mLastX == Integer.MIN_VALUE) {
            // If there's no changes to mLastX, we have only one point of data, and therefore no
            // velocity. Estimate velocity from just the up and down event in that case.
            xVelocity = deltaX / ((ev.getEventTime() - ev.getDownTime()) / 1000);
        }
        if (!mDismissed) {
            // Adjust the distance threshold linearly between the min and max threshold based on the
            // x-velocity scaled with the the fling threshold speed
            float distanceThreshold = getWidth() * Math.max(
                    Math.min((MIN_DIST_THRESHOLD - MAX_DIST_THRESHOLD)
                            * xVelocity / mMinFlingVelocity // scale x-velocity with fling velocity
                            + MAX_DIST_THRESHOLD, // offset to start at max threshold
                            MAX_DIST_THRESHOLD), // cap at max threshold
                    MIN_DIST_THRESHOLD); // bottom out at min threshold
            if ((deltaX > distanceThreshold && ev.getRawX() >= mLastX)
                    || xVelocity >= mMinFlingVelocity) {
                mDismissed = true;
            }
        }
        // Check if the user tried to undo this.
        if (mDismissed && mSwiping) {
            // Check if the user's finger is actually flinging back to left
            if (xVelocity < -mMinFlingVelocity) {
                mDismissed = false;
            }
        }
    }

    /**
     * Tests scrollability within child views of v in the direction of dx.
     *
     * @param v View to test for horizontal scrollability
     * @param checkV Whether the view v passed should itself be checked for scrollability (true),
     *               or just its children (false).
     * @param dx Delta scrolled in pixels. Only the sign of this is used.
     * @param x X coordinate of the active touch point
     * @param y Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    protected boolean canScroll(View v, boolean checkV, float dx, float x, float y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                final View child = group.getChildAt(i);
                if (x + scrollX >= child.getLeft() && x + scrollX < child.getRight() &&
                        y + scrollY >= child.getTop() && y + scrollY < child.getBottom() &&
                        canScroll(child, true, dx, x + scrollX - child.getLeft(),
                                y + scrollY - child.getTop())) {
                    return true;
                }
            }
        }

        return checkV && v.canScrollHorizontally((int) -dx);
    }

    public void setDismissable(boolean dismissable) {
        if (!dismissable && mDismissable) {
            cancel();
            resetMembers();
        }

        mDismissable = dismissable;
    }

    private void checkGesture(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mBlockGesture = mDismissAnimator.isAnimating();
        }
    }

    private float progressToAlpha(float progress) {
        return 1 - progress * progress * progress;
    }

    private Activity findActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private class DismissAnimator implements AnimatorUpdateListener, Animator.AnimatorListener {
        private final TimeInterpolator DISMISS_INTERPOLATOR = new DecelerateInterpolator(1.5f);
        private final long DISMISS_DURATION = 250;

        private final ValueAnimator mDismissAnimator = new ValueAnimator();
        private boolean mWasCanceled = false;
        private boolean mDismissOnComplete = false;

        /* package */ DismissAnimator() {
            mDismissAnimator.addUpdateListener(this);
            mDismissAnimator.addListener(this);
        }

        /* package */ void animateDismissal(float currentTranslation) {
            animate(
                    currentTranslation / getWidth(),
                    1,
                    DISMISS_DURATION,
                    DISMISS_INTERPOLATOR,
                    true /* dismiss */);
        }

        /* package */ void animateRecovery(float currentTranslation) {
            animate(
                    currentTranslation / getWidth(),
                    0,
                    DISMISS_DURATION,
                    DISMISS_INTERPOLATOR,
                    false /* don't dismiss */);
        }

        /* package */ boolean isAnimating() {
            return mDismissAnimator.isStarted();
        }

        private void animate(float from, float to, long duration, TimeInterpolator interpolator,
                boolean dismissOnComplete) {
            mDismissAnimator.cancel();
            mDismissOnComplete = dismissOnComplete;
            mDismissAnimator.setFloatValues(from, to);
            mDismissAnimator.setDuration(duration);
            mDismissAnimator.setInterpolator(interpolator);
            mDismissAnimator.start();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            float value = (Float) animation.getAnimatedValue();
            setProgress(value * getWidth());
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mWasCanceled = false;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mWasCanceled = true;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!mWasCanceled) {
                if (mDismissOnComplete) {
                    dismiss();
                } else {
                    cancel();
                }
            }
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }
    }
}
