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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * A utility class to enable the downward swipe on the lockscreen to go to the full shade and expand
 * the notification where the drag started.
 */
public class DragDownHelper implements Gefingerpoken {

    private static final float RUBBERBAND_FACTOR_EXPANDABLE = 0.5f;
    private static final float RUBBERBAND_FACTOR_STATIC = 0.15f;

    private static final int SPRING_BACK_ANIMATION_LENGTH_MS = 375;

    private int mMinDragDistance;
    private ExpandHelper.Callback mCallback;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private boolean mDraggingDown;
    private float mTouchSlop;
    private DragDownCallback mDragDownCallback;
    private View mHost;
    private final int[] mTemp2 = new int[2];
    private boolean mDraggedFarEnough;
    private ExpandableView mStartingChild;
    private float mLastHeight;
    private FalsingManager mFalsingManager;

    public DragDownHelper(Context context, View host, ExpandHelper.Callback callback,
            DragDownCallback dragDownCallback) {
        mMinDragDistance = context.getResources().getDimensionPixelSize(
                R.dimen.keyguard_drag_down_min_distance);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mCallback = callback;
        mDragDownCallback = dragDownCallback;
        mHost = host;
        mFalsingManager = FalsingManager.getInstance(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDraggedFarEnough = false;
                mDraggingDown = false;
                mStartingChild = null;
                mInitialTouchY = y;
                mInitialTouchX = x;
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                if (h > mTouchSlop && h > Math.abs(x - mInitialTouchX)) {
                    mFalsingManager.onNotificatonStartDraggingDown();
                    mDraggingDown = true;
                    captureStartingChild(mInitialTouchX, mInitialTouchY);
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mDragDownCallback.onTouchSlopExceeded();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mDraggingDown) {
            return false;
        }
        final float x = event.getX();
        final float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mLastHeight = y - mInitialTouchY;
                captureStartingChild(mInitialTouchX, mInitialTouchY);
                if (mStartingChild != null) {
                    handleExpansion(mLastHeight, mStartingChild);
                } else {
                    mDragDownCallback.setEmptyDragAmount(mLastHeight);
                }
                if (mLastHeight > mMinDragDistance) {
                    if (!mDraggedFarEnough) {
                        mDraggedFarEnough = true;
                        mDragDownCallback.onCrossedThreshold(true);
                    }
                } else {
                    if (mDraggedFarEnough) {
                        mDraggedFarEnough = false;
                        mDragDownCallback.onCrossedThreshold(false);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!isFalseTouch() && mDragDownCallback.onDraggedDown(mStartingChild,
                        (int) (y - mInitialTouchY))) {
                    if (mStartingChild == null) {
                        mDragDownCallback.setEmptyDragAmount(0f);
                    } else {
                        mCallback.setUserLockedChild(mStartingChild, false);
                        mStartingChild = null;
                    }
                    mDraggingDown = false;
                } else {
                    stopDragging();
                    return false;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                stopDragging();
                return false;
        }
        return false;
    }

    private boolean isFalseTouch() {
        if (!mDragDownCallback.isFalsingCheckNeeded()) {
            return false;
        }
        return mFalsingManager.isFalseTouch() || !mDraggedFarEnough;
    }

    private void captureStartingChild(float x, float y) {
        if (mStartingChild == null) {
            mStartingChild = findView(x, y);
            if (mStartingChild != null) {
                mCallback.setUserLockedChild(mStartingChild, true);
            }
        }
    }

    private void handleExpansion(float heightDelta, ExpandableView child) {
        if (heightDelta < 0) {
            heightDelta = 0;
        }
        boolean expandable = child.isContentExpandable();
        float rubberbandFactor = expandable
                ? RUBBERBAND_FACTOR_EXPANDABLE
                : RUBBERBAND_FACTOR_STATIC;
        float rubberband = heightDelta * rubberbandFactor;
        if (expandable
                && (rubberband + child.getCollapsedHeight()) > child.getMaxContentHeight()) {
            float overshoot =
                    (rubberband + child.getCollapsedHeight()) - child.getMaxContentHeight();
            overshoot *= (1 - RUBBERBAND_FACTOR_STATIC);
            rubberband -= overshoot;
        }
        child.setActualHeight((int) (child.getCollapsedHeight() + rubberband));
    }

    private void cancelExpansion(final ExpandableView child) {
        if (child.getActualHeight() == child.getCollapsedHeight()) {
            mCallback.setUserLockedChild(child, false);
            return;
        }
        ObjectAnimator anim = ObjectAnimator.ofInt(child, "actualHeight",
                child.getActualHeight(), child.getCollapsedHeight());
        anim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        anim.setDuration(SPRING_BACK_ANIMATION_LENGTH_MS);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mCallback.setUserLockedChild(child, false);
            }
        });
        anim.start();
    }

    private void cancelExpansion() {
        ValueAnimator anim = ValueAnimator.ofFloat(mLastHeight, 0);
        anim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        anim.setDuration(SPRING_BACK_ANIMATION_LENGTH_MS);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mDragDownCallback.setEmptyDragAmount((Float) animation.getAnimatedValue());
            }
        });
        anim.start();
    }

    private void stopDragging() {
        mFalsingManager.onNotificatonStopDraggingDown();
        if (mStartingChild != null) {
            cancelExpansion(mStartingChild);
            mStartingChild = null;
        } else {
            cancelExpansion();
        }
        mDraggingDown = false;
        mDragDownCallback.onDragDownReset();
    }

    private ExpandableView findView(float x, float y) {
        mHost.getLocationOnScreen(mTemp2);
        x += mTemp2[0];
        y += mTemp2[1];
        return mCallback.getChildAtRawPosition(x, y);
    }

    public boolean isDraggingDown() {
        return mDraggingDown;
    }

    public interface DragDownCallback {

        /**
         * @return true if the interaction is accepted, false if it should be cancelled
         */
        boolean onDraggedDown(View startingChild, int dragLengthY);
        void onDragDownReset();

        /**
         * The user has dragged either above or below the threshold
         * @param above whether he dragged above it
         */
        void onCrossedThreshold(boolean above);
        void onTouchSlopExceeded();
        void setEmptyDragAmount(float amount);
        boolean isFalsingCheckNeeded();
    }
}
