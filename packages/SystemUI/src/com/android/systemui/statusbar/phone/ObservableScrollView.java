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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * A scroll view which can be observed for scroll change events.
 */
public class ObservableScrollView extends ScrollView {

    private Listener mListener;
    private int mLastOverscrollAmount;
    private boolean mTouchEnabled = true;
    private boolean mHandlingTouchEvent;
    private float mLastX;
    private float mLastY;
    private boolean mBlockFlinging;
    private boolean mTouchCancelled;

    public ObservableScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setTouchEnabled(boolean touchEnabled) {
        mTouchEnabled = touchEnabled;
    }

    public boolean isScrolledToBottom() {
        return getScrollY() == getMaxScrollY();
    }

    public boolean isHandlingTouchEvent() {
        return mHandlingTouchEvent;
    }

    private int getMaxScrollY() {
        int scrollRange = 0;
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            scrollRange = Math.max(0,
                    child.getHeight() - (getHeight() - mPaddingBottom - mPaddingTop));
        }
        return scrollRange;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        mHandlingTouchEvent = true;
        mLastX = ev.getX();
        mLastY = ev.getY();
        boolean result = super.onTouchEvent(ev);
        mHandlingTouchEvent = false;
        return result;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        mHandlingTouchEvent = true;
        mLastX = ev.getX();
        mLastY = ev.getY();
        boolean result = super.onInterceptTouchEvent(ev);
        mHandlingTouchEvent = false;
        return result;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (!mTouchEnabled) {
                mTouchCancelled = true;
                return false;
            }
            mTouchCancelled = false;
        } else if (mTouchCancelled) {
            return false;
        } else if (!mTouchEnabled) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(ev);
            cancel.recycle();
            mTouchCancelled = true;
            return false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (mListener != null) {
            mListener.onScrollChanged();
        }
    }

    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
            int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY,
            boolean isTouchEvent) {
        mLastOverscrollAmount = Math.max(0, scrollY + deltaY - getMaxScrollY());
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                        maxOverScrollX, maxOverScrollY, isTouchEvent);
    }

    public void setBlockFlinging(boolean blockFlinging) {
        mBlockFlinging = blockFlinging;
    }

    @Override
    public void fling(int velocityY) {
        if (!mBlockFlinging) {
            super.fling(velocityY);
        }
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
        if (mListener != null && mLastOverscrollAmount > 0) {
            mListener.onOverscrolled(mLastX, mLastY, mLastOverscrollAmount);
        }
    }

    public interface Listener {
        void onScrollChanged();
        void onOverscrolled(float lastX, float lastY, int amount);
    }
}
