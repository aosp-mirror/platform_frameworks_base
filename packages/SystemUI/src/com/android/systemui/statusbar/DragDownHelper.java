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

import android.content.Context;
import android.util.ArraySet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;

import java.util.HashSet;

/**
 * A utility class to enable the downward swipe on the lockscreen to go to the full shade and expand
 * the notification where the drag started.
 */
public class DragDownHelper implements Gefingerpoken {

    private int mMinDragDistance;
    private ExpandHelper.Callback mCallback;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private boolean mDraggingDown;
    private float mTouchSlop;
    private OnDragDownListener mOnDragDownListener;
    private View mHost;
    private final int[] mTemp2 = new int[2];
    private final ArraySet<View> mHoveredChildren = new ArraySet<View>();
    private boolean mDraggedFarEnough;
    private View mStartingChild;

    public DragDownHelper(Context context, View host, ExpandHelper.Callback callback,
            OnDragDownListener onDragDownListener) {
        mMinDragDistance = context.getResources().getDimensionPixelSize(
                R.dimen.keyguard_drag_down_min_distance);
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
                mHoveredChildren.clear();
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
                    mInitialTouchY = y;
                    mInitialTouchX = x;
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
                View child = findView(x, y);
                if (child != null) {
                    hoverChild(findView(x, y));
                }
                if (h > mMinDragDistance) {
                    if (!mDraggedFarEnough) {
                        mDraggedFarEnough = true;
                        mOnDragDownListener.onThresholdReached();
                    }
                } else {
                    if (mDraggedFarEnough) {
                        mDraggedFarEnough = false;
                        mOnDragDownListener.onReset();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mDraggedFarEnough) {
                    mOnDragDownListener.onDraggedDown(mStartingChild);
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

    private void stopDragging() {
        mDraggingDown = false;
        mOnDragDownListener.onReset();
    }

    private void hoverChild(View child) {
        if (mHoveredChildren.isEmpty()) {
            mStartingChild = child;
        }
        if (!mHoveredChildren.contains(child)) {
            mOnDragDownListener.onHover(child);
            mHoveredChildren.add(child);
        }
    }

    private View findView(float x, float y) {
        mHost.getLocationOnScreen(mTemp2);
        x += mTemp2[0];
        y += mTemp2[1];
        return mCallback.getChildAtRawPosition(x, y);
    }

    public interface OnDragDownListener {
        void onHover(View child);
        void onDraggedDown(View startingChild);
        void onReset();
        void onThresholdReached();
    }
}
