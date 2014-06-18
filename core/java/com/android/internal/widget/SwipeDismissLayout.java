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

import android.animation.TimeInterpolator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Special layout that finishes its activity when swiped away.
 */
public class SwipeDismissLayout extends FrameLayout {
    private static final String TAG = "SwipeDismissLayout";

    private static final float DISMISS_MIN_DRAG_WIDTH_RATIO = .33f;

    public interface OnDismissedListener {
        void onDismissed(SwipeDismissLayout layout);
    }

    public interface OnSwipeProgressChangedListener {
        /**
         * Called when the layout has been swiped and the position of the window should change.
         *
         * @param progress A number in [0, 1] representing how far to the
         * right the window has been swiped
         * @param translate A number in [0, w], where w is the width of the
         * layout. This is equivalent to progress * layout.getWidth().
         */
        void onSwipeProgressChanged(SwipeDismissLayout layout, float progress, float translate);

        void onSwipeCancelled(SwipeDismissLayout layout);
    }

    // Cached ViewConfiguration and system-wide constant values
    private int mSlop;
    private int mMinFlingVelocity;
    private int mMaxFlingVelocity;
    private long mAnimationTime;
    private TimeInterpolator mCancelInterpolator;
    private TimeInterpolator mDismissInterpolator;

    // Transient properties
    private int mActiveTouchId;
    private float mDownX;
    private float mDownY;
    private boolean mSwiping;
    private boolean mDismissed;
    private boolean mDiscardIntercept;
    private VelocityTracker mVelocityTracker;
    private float mTranslationX;

    private OnDismissedListener mDismissedListener;
    private OnSwipeProgressChangedListener mProgressListener;

    private float mLastX;

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
        ViewConfiguration vc = ViewConfiguration.get(getContext());
        mSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        mAnimationTime = getContext().getResources().getInteger(
                android.R.integer.config_shortAnimTime);
        mCancelInterpolator = new DecelerateInterpolator(1.5f);
        mDismissInterpolator = new AccelerateInterpolator(1.5f);
    }

    public void setOnDismissedListener(OnDismissedListener listener) {
        mDismissedListener = listener;
    }

    public void setOnSwipeProgressChangedListener(OnSwipeProgressChangedListener listener) {
        mProgressListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // offset because the view is translated during swipe
        ev.offsetLocation(mTranslationX, 0);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetMembers();
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                mActiveTouchId = ev.getPointerId(0);
                mVelocityTracker = VelocityTracker.obtain();
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
        if (mVelocityTracker == null) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_UP:
                updateDismiss(ev);
                if (mDismissed) {
                    dismiss();
                } else if (mSwiping) {
                    cancel();
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
        mTranslationX = deltaX;
        if (mProgressListener != null && deltaX >= 0)  {
            mProgressListener.onSwipeProgressChanged(this, deltaX / getWidth(), deltaX);
        }
    }

    private void dismiss() {
        if (mDismissedListener != null) {
            mDismissedListener.onDismissed(this);
        }
    }

    protected void cancel() {
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
        mTranslationX = 0;
        mDownX = 0;
        mDownY = 0;
        mSwiping = false;
        mDismissed = false;
        mDiscardIntercept = false;
    }

    private void updateSwiping(MotionEvent ev) {
        if (!mSwiping) {
            float deltaX = ev.getRawX() - mDownX;
            float deltaY = ev.getRawY() - mDownY;
            if ((deltaX * deltaX) + (deltaY * deltaY) > mSlop * mSlop) {
                mSwiping = deltaX > mSlop * 2 && Math.abs(deltaY) < mSlop * 2;
            } else {
                mSwiping = false;
            }
        }
    }

    private void updateDismiss(MotionEvent ev) {
        float deltaX = ev.getRawX() - mDownX;
        if (!mDismissed) {
            mVelocityTracker.addMovement(ev);
            mVelocityTracker.computeCurrentVelocity(1000);

            if (deltaX > (getWidth() * DISMISS_MIN_DRAG_WIDTH_RATIO) &&
                    ev.getRawX() >= mLastX) {
                mDismissed = true;
            }
        }
        // Check if the user tried to undo this.
        if (mDismissed && mSwiping) {
            // Check if the user's finger is actually back
            if (deltaX < (getWidth() * DISMISS_MIN_DRAG_WIDTH_RATIO)) {
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
}
