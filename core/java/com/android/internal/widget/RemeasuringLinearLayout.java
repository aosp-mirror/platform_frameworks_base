/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import java.util.ArrayList;

/**
 * A LinearLayout that sets it's height again after the last measure pass. This is needed for
 * MessagingLayouts where groups need to be able to snap it's height to.
 */
@RemoteViews.RemoteView
public class RemeasuringLinearLayout extends LinearLayout {

    private ArrayList<View> mMatchParentViews = new ArrayList<>();

    public RemeasuringLinearLayout(Context context) {
        super(context);
    }

    public RemeasuringLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RemeasuringLinearLayout(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public RemeasuringLinearLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int count = getChildCount();
        int height = 0;
        boolean isVertical = getOrientation() == LinearLayout.VERTICAL;
        boolean isWrapContent = getLayoutParams().height == LayoutParams.WRAP_CONTENT;
        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!isWrapContent || lp.height != LayoutParams.MATCH_PARENT || isVertical) {
                int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                height = Math.max(height, isVertical ? height + childHeight : childHeight);
            } else {
                // We have match parent children in a wrap content view, let's measure the
                // view properly
                mMatchParentViews.add(child);
            }
        }
        if (mMatchParentViews.size() > 0) {
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            for (View child : mMatchParentViews) {
                child.measure(getChildMeasureSpec(
                        widthMeasureSpec, getPaddingStart() + getPaddingEnd(),
                        child.getLayoutParams().width),
                        exactHeightSpec);
            }
        }
        mMatchParentViews.clear();
        setMeasuredDimension(getMeasuredWidth(), height);
    }
}
