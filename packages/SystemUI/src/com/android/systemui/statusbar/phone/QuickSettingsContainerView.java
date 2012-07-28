/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 *
 */
class QuickSettingsContainerView extends FrameLayout {

    // The number of columns in the QuickSettings grid
    private int mNumColumns;

    // The gap between tiles in the QuickSettings grid
    private float mCellGap;

    public QuickSettingsContainerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        updateResources();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // TODO: Setup the layout transitions
        LayoutTransition transitions = getLayoutTransition();
    }

    void updateResources() {
        Resources r = getContext().getResources();
        mCellGap = r.getDimension(R.dimen.quick_settings_cell_gap);
        mNumColumns = r.getInteger(R.integer.quick_settings_num_columns);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Calculate the cell width dynamically
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int availableWidth = (int) (width - getPaddingLeft() - getPaddingRight() -
                (mNumColumns - 1) * mCellGap);
        float cellWidth = (float) Math.ceil(((float) availableWidth) / mNumColumns);

        // Update each of the children's widths accordingly to the cell width
        final int N = getChildCount();
        int cellHeight = 0;
        int cursor = 0;
        for (int i = 0; i < N; ++i) {
            // Update the child's width
            QuickSettingsTileView v = (QuickSettingsTileView) getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int colSpan = v.getColumnSpan();
                lp.width = (int) ((colSpan * cellWidth) + (colSpan - 1) * mCellGap);

                // Measure the child
                int newWidthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
                int newHeightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
                v.measure(newWidthSpec, newHeightSpec);

                // Save the cell height
                if (cellHeight <= 0) {
                    cellHeight = v.getMeasuredHeight();
                }
                cursor += colSpan;
            }
        }

        // Set the measured dimensions.  We always fill the tray width, but wrap to the height of
        // all the tiles.
        int numRows = (int) Math.ceil((float) cursor / mNumColumns);
        int newHeight = (int) ((numRows * cellHeight) + ((numRows - 1) * mCellGap)) +
                getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, newHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int N = getChildCount();
        final boolean isLayoutRtl = isLayoutRtl();
        final int width = getWidth();

        int x = getPaddingStart();
        int y = getPaddingTop();
        int cursor = 0;

        for (int i = 0; i < N; ++i) {
            QuickSettingsTileView child = (QuickSettingsTileView) getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (child.getVisibility() != GONE) {
                final int col = cursor % mNumColumns;
                final int colSpan = child.getColumnSpan();

                final int childWidth = lp.width;
                final int childHeight = lp.height;

                int row = (int) (cursor / mNumColumns);

                // Push the item to the next row if it can't fit on this one
                if ((col + colSpan) > mNumColumns) {
                    x = getPaddingStart();
                    y += childHeight + mCellGap;
                    row++;
                }

                final int childLeft = (isLayoutRtl) ? width - x - childWidth : x;
                final int childRight = childLeft + childWidth;

                final int childTop = y;
                final int childBottom = childTop + childHeight;

                // Layout the container
                child.layout(childLeft, childTop, childRight, childBottom);

                // Offset the position by the cell gap or reset the position and cursor when we
                // reach the end of the row
                cursor += child.getColumnSpan();
                if (cursor < (((row + 1) * mNumColumns))) {
                    x += childWidth + mCellGap;
                } else {
                    x = getPaddingStart();
                    y += childHeight + mCellGap;
                }
            }
        }
    }
}