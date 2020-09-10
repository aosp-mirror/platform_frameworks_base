/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.widget.ViewPager;

/**
 * A {@link ViewPager} which wraps around its tallest child's height.
 * <p>Normally {@link ViewPager} instances expand their height to cover all remaining space in
 * the layout.
 * <p>This class is used for the intent resolver and share sheet's tabbed view.
 */
public class ResolverViewPager extends ViewPager {

    private boolean mSwipingEnabled = true;

    public ResolverViewPager(Context context) {
        super(context);
    }

    public ResolverViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ResolverViewPager(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ResolverViewPager(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.AT_MOST) {
            return;
        }
        widthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int height = getMeasuredHeight();
        int maxHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            if (maxHeight < child.getMeasuredHeight()) {
                maxHeight = child.getMeasuredHeight();
            }
        }
        if (maxHeight > 0) {
            height = maxHeight;
        }
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Sets whether swiping sideways should happen.
     * <p>Note that swiping is always disabled for RTL layouts (b/159110029 for context).
     */
    void setSwipingEnabled(boolean swipingEnabled) {
        mSwipingEnabled = swipingEnabled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return !isLayoutRtl() && mSwipingEnabled && super.onInterceptTouchEvent(ev);
    }
}
