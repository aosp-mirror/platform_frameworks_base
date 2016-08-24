/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * Layout used as a container for keyboard shortcut keys. It's children are wrapped and right
 * aligned.
 */
public final class KeyboardShortcutKeysLayout extends ViewGroup {
    private int mLineHeight;
    private final Context mContext;

    public KeyboardShortcutKeysLayout(Context context) {
        super(context);
        this.mContext = context;
    }

    public KeyboardShortcutKeysLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mContext = context;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int childCount = getChildCount();
        int height = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
        int lineHeight = 0;
        int xPos = getPaddingLeft();
        int yPos = getPaddingTop();

        int childHeightMeasureSpec;
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
        } else {
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        }

        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        childHeightMeasureSpec);
                int childWidth = child.getMeasuredWidth();
                lineHeight = Math.max(lineHeight,
                        child.getMeasuredHeight() + layoutParams.mVerticalSpacing);

                if (xPos + childWidth > width) {
                    xPos = getPaddingLeft();
                    yPos += lineHeight;
                }
                xPos += childWidth + layoutParams.mHorizontalSpacing;
            }
        }
        this.mLineHeight = lineHeight;

        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            height = yPos + lineHeight;
        } else if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            if (yPos + lineHeight < height) {
                height = yPos + lineHeight;
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        int spacing = getHorizontalVerticalSpacing();
        return new LayoutParams(spacing, spacing);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams layoutParams) {
        int spacing = getHorizontalVerticalSpacing();
        return new LayoutParams(spacing, spacing, layoutParams);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return (p instanceof LayoutParams);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = getChildCount();
        int fullRowWidth = r - l;
        int xPos = isRTL()
                ? fullRowWidth - getPaddingRight()
                : getPaddingLeft();
        int yPos = getPaddingTop();
        int lastHorizontalSpacing = 0;
        // The index of the child which starts the current row.
        int rowStartIdx = 0;

        // Go through all the children.
        for (int i = 0; i < childCount; i++) {
            View currentChild = getChildAt(i);
            if (currentChild.getVisibility() != GONE) {
                int currentChildWidth = currentChild.getMeasuredWidth();
                LayoutParams lp = (LayoutParams) currentChild.getLayoutParams();

                boolean childDoesNotFitOnRow = isRTL()
                        ? xPos - getPaddingLeft() - currentChildWidth < 0
                        : xPos + currentChildWidth > fullRowWidth;

                if (childDoesNotFitOnRow) {
                    // Layout all the children on this row but the current one.
                    layoutChildrenOnRow(rowStartIdx, i, fullRowWidth, xPos, yPos,
                            lastHorizontalSpacing);
                    // Update the positions for starting on the new row.
                    xPos = isRTL()
                            ? fullRowWidth - getPaddingRight()
                            : getPaddingLeft();
                    yPos += mLineHeight;
                    rowStartIdx = i;
                }

                xPos = isRTL()
                        ? xPos - currentChildWidth - lp.mHorizontalSpacing
                        : xPos + currentChildWidth + lp.mHorizontalSpacing;
                lastHorizontalSpacing = lp.mHorizontalSpacing;
            }
        }

        // Lay out the children on the last row.
        if (rowStartIdx < childCount) {
            layoutChildrenOnRow(rowStartIdx, childCount, fullRowWidth, xPos, yPos,
                    lastHorizontalSpacing);
        }
    }

    private int getHorizontalVerticalSpacing() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4, displayMetrics);
    }

    private void layoutChildrenOnRow(int startIndex, int endIndex, int fullRowWidth, int xPos,
            int yPos, int lastHorizontalSpacing) {
        if (!isRTL()) {
            xPos = getPaddingLeft() + fullRowWidth - xPos + lastHorizontalSpacing;
        }

        for (int j = startIndex; j < endIndex; ++j) {
            View currentChild = getChildAt(j);
            int currentChildWidth = currentChild.getMeasuredWidth();
            LayoutParams lp = (LayoutParams) currentChild.getLayoutParams();
            if (isRTL() && j == startIndex) {
                xPos = fullRowWidth - xPos - getPaddingRight() - currentChildWidth
                        - lp.mHorizontalSpacing;
            }

            currentChild.layout(
                    xPos,
                    yPos,
                    xPos + currentChildWidth,
                    yPos + currentChild.getMeasuredHeight());

            if (isRTL()) {
                int nextChildWidth = j < endIndex - 1
                        ? getChildAt(j + 1).getMeasuredWidth()
                        : 0;
                xPos -= nextChildWidth + lp.mHorizontalSpacing;
            } else {
                xPos += currentChildWidth + lp.mHorizontalSpacing;
            }
        }
    }

    private boolean isRTL() {
        return mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public final int mHorizontalSpacing;
        public final int mVerticalSpacing;

        public LayoutParams(int horizontalSpacing, int verticalSpacing,
                ViewGroup.LayoutParams viewGroupLayout) {
            super(viewGroupLayout);
            this.mHorizontalSpacing = horizontalSpacing;
            this.mVerticalSpacing = verticalSpacing;
        }

        public LayoutParams(int mHorizontalSpacing, int verticalSpacing) {
            super(0, 0);
            this.mHorizontalSpacing = mHorizontalSpacing;
            this.mVerticalSpacing = verticalSpacing;
        }
    }
}
