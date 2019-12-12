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
import android.view.View;

import com.android.internal.widget.ViewPager;

/**
 * A {@link ViewPager} which wraps around its first child's height.
 * <p>Normally {@link ViewPager} instances expand their height to cover all remaining space in
 * the layout.
 * <p>This class is used for the intent resolver picker's tabbed view to maintain
 * consistency with the previous behavior.
 */
public class WrapHeightViewPager extends ViewPager {

    public WrapHeightViewPager(Context context) {
        super(context);
    }

    public WrapHeightViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WrapHeightViewPager(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WrapHeightViewPager(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    // TODO(arangelov): When we have multiple pages, the height should wrap to the currently
    // displayed page. Investigate whether onMeasure is called when changing a page, and instead
    // of getChildAt(0), use the currently displayed one.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.AT_MOST) {
            return;
        }
        widthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int height = getMeasuredHeight();
        if (getChildCount() > 0) {
            View firstChild = getChildAt(0);
            firstChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            height = firstChild.getMeasuredHeight();
        }
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
