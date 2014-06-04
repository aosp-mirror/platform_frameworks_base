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
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;

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
    private OnDragDownListener mOnDragDownListener;
    private View mHost;
    private final int[] mTemp2 = new int[2];
    private boolean mDraggedFarEnough;
    private ExpandableView mStartingChild;
    private Interpolator mInterpolator;

    public DragDownHelper(Context context, View host, ExpandHelper.Callback callback,
            OnDragDownListener onDragDownListener) {
        mMinDragDistance = context.getResources().getDimensionPixelSize(
                R.dimen.keyguard_drag_down_min_distance);
        mInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mCallback = callback;
        mOnDragDownListener = onDragDownListener;
        mHost = host;
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
                    mDraggingDown = true;
                    captureStartingChild(mInitialTouchX, mInitialTouchY);
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mOnDragDownListener.onTouchSlopExceeded();
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
                final float h = y - mInitialTouchY;
                captureStartingChild(mInitialTouchX, mInitialTouchY);
                if (mStartingChild != null) {
                    handleExpansion(h, mStartingChild);
                }
                if (h > mMinDragDistance) {
                    if (!mDraggedFarEnough) {
                        mDraggedFarEnough = true;
                        mOnDragDownListener.onThresholdReached();
                    }
                } else {
                    if (mDraggedFarEnough) {
                        mDraggedFarEnough = false;
                        mOnDragDownListener.onDragDownReset();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mDraggedFarEnough) {
                    if (mStartingChild != null) {
                        mCallback.setUserLockedChild(mStartingChild, false);
                    }
                    mOnDragDownListener.onDraggedDown(mStartingChild);
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
        if (expandable && (rubberband + child.getMinHeight()) > child.getMaxHeight()) {
            float overshoot = (rubberband + child.getMinHeight()) - child.getMaxHeight();
            overshoot *= (1 - RUBBERBAND_FACTOR_STATIC);
            rubberband -= overshoot;
        }
        child.setActualHeight((int) (child.getMinHeight() + rubberband));
    }

    private void cancelExpansion(final ExpandableView child) {
        if (child.getActualHeight() == child.getMinHeight()) {
            return;
        }
        ObjectAnimator anim = ObjectAnimator.ofInt(child, "actualHeight",
                child.getActualHeight(), child.getMinHeight());
        anim.setInterpolator(mInterpolator);
        anim.setDuration(SPRING_BACK_ANIMATION_LENGTH_MS);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mCallback.setUserLockedChild(child, false);
            }
        });
        anim.start();
    }

    private void stopDragging() {
        if (mStartingChild != null) {
            cancelExpansion(mStartingChild);
        }
        mDraggingDown = false;
        mOnDragDownListener.onDragDownReset();
    }

    private ExpandableView findView(float x, float y) {
        mHost.getLocationOnScreen(mTemp2);
        x += mTemp2[0];
        y += mTemp2[1];
        return mCallback.getChildAtRawPosition(x, y);
    }

    public interface OnDragDownListener {
        void onDraggedDown(View startingChild);
        void onDragDownReset();
        void onThresholdReached();
        void onTouchSlopExceeded();
    }
}
