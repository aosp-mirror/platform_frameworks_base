/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ViewAnimator;

import java.util.ArrayList;

/**
 * ViewAnimator with a more reasonable handling of MATCH_PARENT.
 */
public class DialogViewAnimator extends ViewAnimator {
    private final ArrayList<View> mMatchParentChildren = new ArrayList<>(1);

    public DialogViewAnimator(Context context) {
        super(context);
    }

    public DialogViewAnimator(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY ||
                        MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        // First measure all children and record maximum dimensions where the
        // spec isn't MATCH_PARENT.
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (getMeasureAllChildren() || child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                final boolean matchWidth = lp.width == LayoutParams.MATCH_PARENT;
                final boolean matchHeight = lp.height == LayoutParams.MATCH_PARENT;
                if (measureMatchParentChildren && (matchWidth || matchHeight)) {
                    mMatchParentChildren.add(child);
                }

                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);

                // Measured dimensions only count against the maximum
                // dimensions if they're not MATCH_PARENT.
                int state = 0;

                if (measureMatchParentChildren && !matchWidth) {
                    maxWidth = Math.max(maxWidth, child.getMeasuredWidth()
                            + lp.leftMargin + lp.rightMargin);
                    state |= child.getMeasuredWidthAndState() & MEASURED_STATE_MASK;
                }

                if (measureMatchParentChildren && !matchHeight) {
                    maxHeight = Math.max(maxHeight, child.getMeasuredHeight()
                            + lp.topMargin + lp.bottomMargin);
                    state |= (child.getMeasuredHeightAndState() >> MEASURED_HEIGHT_STATE_SHIFT)
                            & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT);
                }

                childState = combineMeasuredStates(childState, state);
            }
        }

        // Account for padding too.
        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();

        // Check against our minimum height and width.
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        // Check against our foreground's minimum height and width.
        final Drawable drawable = getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        // Measure remaining MATCH_PARENT children again using real dimensions.
        final int matchCount = mMatchParentChildren.size();
        for (int i = 0; i < matchCount; i++) {
            final View child = mMatchParentChildren.get(i);
            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

            final int childWidthMeasureSpec;
            if (lp.width == LayoutParams.MATCH_PARENT) {
                childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        getMeasuredWidth() - getPaddingLeft() - getPaddingRight()
                                - lp.leftMargin - lp.rightMargin,
                        MeasureSpec.EXACTLY);
            } else {
                childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                        getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin,
                        lp.width);
            }

            final int childHeightMeasureSpec;
            if (lp.height == LayoutParams.MATCH_PARENT) {
                childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        getMeasuredHeight() - getPaddingTop() - getPaddingBottom()
                                - lp.topMargin - lp.bottomMargin,
                        MeasureSpec.EXACTLY);
            } else {
                childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                        getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin,
                        lp.height);
            }

            child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        }

        mMatchParentChildren.clear();
    }
}
