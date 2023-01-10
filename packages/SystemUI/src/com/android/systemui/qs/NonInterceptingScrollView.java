/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * ScrollView that disallows intercepting for touches that can cause scrolling.
 */
public class NonInterceptingScrollView extends ScrollView {

    private final int mTouchSlop;

    private float mDownY;
    private boolean mScrollEnabled = true;
    private boolean mPreventingIntercept;

    public NonInterceptingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public boolean isPreventingIntercept() {
        return mPreventingIntercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mPreventingIntercept = false;
                if (canScrollVertically(1)) {
                    // If we can scroll down, make sure we're not intercepted by the parent
                    mPreventingIntercept = true;
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                } else if (!canScrollVertically(-1)) {
                    // Don't pass on the touch to the view, because scrolling will unconditionally
                    // disallow interception even if we can't scroll.
                    // if a user can't scroll at all, we should never listen to the touch.
                    return false;
                }
                break;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // If there's a touch on this view and we can scroll down, we don't want to be intercepted
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mPreventingIntercept = false;
                // If we can scroll down, make sure none of our parents intercepts us.
                if (canScrollVertically(1)) {
                    mPreventingIntercept = true;
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                mDownY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE: {
                final int y = (int) ev.getY();
                final float yDiff = y - mDownY;
                if (yDiff < -mTouchSlop && !canScrollVertically(1)) {
                    // Don't intercept touches that are overscrolling.
                    return false;
                }
                break;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return mScrollEnabled && super.canScrollVertically(direction);
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        return mScrollEnabled && super.canScrollHorizontally(direction);
    }

    public int getScrollRange() {
        int scrollRange = 0;
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            scrollRange = Math.max(0,
                    child.getHeight() - (getHeight() - mPaddingBottom - mPaddingTop));
        }
        return scrollRange;
    }

    /**
     * Enable scrolling for this view. Needed because the view might be clipped but still intercepts
     * touches on the lockscreen.
     */
    public void setScrollingEnabled(boolean enabled) {
        mScrollEnabled = enabled;
    }
}
