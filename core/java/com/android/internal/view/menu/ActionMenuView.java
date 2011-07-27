/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.internal.view.menu;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * @hide
 */
public class ActionMenuView extends LinearLayout implements MenuBuilder.ItemInvoker, MenuView {
    private static final String TAG = "ActionMenuView";
    
    static final int MIN_CELL_SIZE = 56; // dips

    private MenuBuilder mMenu;

    private boolean mReserveOverflow;
    private ActionMenuPresenter mPresenter;
    private boolean mFormatItems;
    private int mFormatItemsWidth;
    private int mMinCellSize;
    private int mMeasuredExtraWidth;

    public ActionMenuView(Context context) {
        this(context, null);
    }
    
    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBaselineAligned(false);
        mMinCellSize = (int) (MIN_CELL_SIZE * context.getResources().getDisplayMetrics().density);
    }

    public void setPresenter(ActionMenuPresenter presenter) {
        mPresenter = presenter;
    }

    public boolean isExpandedFormat() {
        return mFormatItems;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPresenter.updateMenuView(false);

        if (mPresenter != null && mPresenter.isOverflowMenuShowing()) {
            mPresenter.hideOverflowMenu();
            mPresenter.showOverflowMenu();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If we've been given an exact size to match, apply special formatting during layout.
        final boolean wasFormatted = mFormatItems;
        mFormatItems = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY;

        if (wasFormatted != mFormatItems) {
            mFormatItemsWidth = 0; // Reset this when switching modes
        }

        // Special formatting can change whether items can fit as action buttons.
        // Kick the menu and update presenters when this changes.
        final int widthSize = MeasureSpec.getMode(widthMeasureSpec);
        if (mFormatItems && mMenu != null && widthSize != mFormatItemsWidth) {
            mFormatItemsWidth = widthSize;
            mMenu.onItemsChanged(true);
        }

        if (mFormatItems) {
            onMeasureExactFormat(widthMeasureSpec, heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void onMeasureExactFormat(int widthMeasureSpec, int heightMeasureSpec) {
        // We already know the width mode is EXACTLY if we're here.
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        final int widthPadding = getPaddingLeft() + getPaddingRight();
        final int heightPadding = getPaddingTop() + getPaddingBottom();

        widthSize -= widthPadding;

        // Divide the view into cells.
        final int cellCount = widthSize / mMinCellSize;
        final int cellSizeRemaining = widthSize % mMinCellSize;
        final int cellSize = mMinCellSize + cellSizeRemaining / cellCount;

        int cellsRemaining = cellCount;
        int maxChildHeight = 0;
        int maxCellsUsed = 0;
        int expandableItemCount = 0;

        if (mReserveOverflow) cellsRemaining--;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.expanded = false;
            lp.extraPixels = 0;
            lp.cellsUsed = 0;
            lp.expandable = false;

            // Overflow always gets 1 cell. No more, no less.
            final int cellsAvailable = lp.isOverflowButton ? 1 : cellsRemaining;

            final int cellsUsed = measureChildForCells(child, cellSize, cellsAvailable,
                    heightMeasureSpec, heightPadding);

            maxCellsUsed = Math.max(maxCellsUsed, cellsUsed);
            if (lp.expandable) expandableItemCount++;

            cellsRemaining -= cellsUsed;
            maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight());
        }

        // Divide space for remaining cells if we have items that can expand.
        // Try distributing whole leftover cells to smaller items first.

        boolean needsExpansion = false;
        long smallestExpandableItemsAt = 0;
        while (expandableItemCount > 0 && cellsRemaining > 0) {
            int minCells = Integer.MAX_VALUE;
            long minCellsAt = 0; // Bit locations are indices of relevant child views
            int minCellsItemCount = 0;
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                // Don't try to expand items that shouldn't.
                if (!lp.expandable) continue;

                // Mark indices of children that can receive an extra cell.
                if (lp.cellsUsed < minCells) {
                    minCells = lp.cellsUsed;
                    minCellsAt = 1 << i;
                    minCellsItemCount = 1;
                } else if (lp.cellsUsed == minCells) {
                    minCellsAt |= 1 << i;
                    minCellsItemCount++;
                }
            }

            if (minCellsItemCount > cellsRemaining) break; // Couldn't expand anything evenly. Stop.

            // Items that get expanded will always be in the set of smallest items when we're done.
            smallestExpandableItemsAt |= minCellsAt;

            for (int i = 0; i < childCount; i++) {
                if ((minCellsAt & (1 << i)) == 0) continue;

                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.cellsUsed++;
                lp.expanded = true;
                cellsRemaining--;
            }

            needsExpansion = true;
        }

        // Divide any space left that wouldn't divide along cell boundaries
        // evenly among the smallest multi-cell (expandable) items.

        if (cellsRemaining > 0 && smallestExpandableItemsAt != 0) {
            final int expandCount = Long.bitCount(smallestExpandableItemsAt);
            final int extraPixels = cellsRemaining * cellSize / expandCount;

            for (int i = 0; i < childCount; i++) {
                if ((smallestExpandableItemsAt & (1 << i)) == 0) continue;

                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.extraPixels = extraPixels;
                lp.expanded = true;
            }

            needsExpansion = true;
            cellsRemaining = 0;
        }

        // Remeasure any items that have had extra space allocated to them.
        if (needsExpansion) {
            int heightSpec = MeasureSpec.makeMeasureSpec(heightSize - heightPadding, heightMode);
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                if (!lp.expanded) continue;

                final int width = lp.cellsUsed * cellSize + lp.extraPixels;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        }

        if (heightMode != MeasureSpec.EXACTLY) {
            heightSize = maxChildHeight;
        }

        setMeasuredDimension(widthSize, heightSize);
        mMeasuredExtraWidth = cellsRemaining * cellSize;
    }

    /**
     * Measure a child view to fit within cell-based formatting. The child's width
     * will be measured to a whole multiple of cellSize.
     *
     * <p>Sets the expandable and cellsUsed fields of LayoutParams.
     *
     * @param child Child to measure
     * @param cellSize Size of one cell
     * @param cellsRemaining Number of cells remaining that this view can expand to fill
     * @param parentHeightMeasureSpec MeasureSpec used by the parent view
     * @param parentHeightPadding Padding present in the parent view
     * @return Number of cells this child was measured to occupy
     */
    static int measureChildForCells(View child, int cellSize, int cellsRemaining,
            int parentHeightMeasureSpec, int parentHeightPadding) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();

        final int childHeightSize = MeasureSpec.getSize(parentHeightMeasureSpec) -
                parentHeightPadding;
        final int childHeightMode = MeasureSpec.getMode(parentHeightMeasureSpec);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(childHeightSize, childHeightMode);

        int cellsUsed = 0;
        if (cellsRemaining > 0) {
            final int childWidthSpec = MeasureSpec.makeMeasureSpec(
                    cellSize * cellsRemaining, MeasureSpec.AT_MOST);
            child.measure(childWidthSpec, childHeightSpec);

            final int measuredWidth = child.getMeasuredWidth();
            cellsUsed = measuredWidth / cellSize;
            if (measuredWidth % cellSize != 0) cellsUsed++;
        }

        final ActionMenuItemView itemView = child instanceof ActionMenuItemView ?
                (ActionMenuItemView) child : null;
        final boolean expandable = !lp.isOverflowButton && itemView != null && itemView.hasText();
        lp.expandable = expandable;

        lp.cellsUsed = cellsUsed;
        final int targetWidth = cellsUsed * cellSize;
        child.measure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                childHeightSpec);
        return cellsUsed;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!mFormatItems) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }

        final int childCount = getChildCount();
        final int midVertical = (top + bottom) / 2;
        final int dividerWidth = getDividerWidth();
        int overflowWidth = 0;
        int nonOverflowWidth = 0;
        int nonOverflowCount = 0;
        int widthRemaining = right - left - getPaddingRight() - getPaddingLeft();
        boolean hasOverflow = false;
        for (int i = 0; i < childCount; i++) {
            final View v = getChildAt(i);
            if (v.getVisibility() == GONE) {
                continue;
            }

            LayoutParams p = (LayoutParams) v.getLayoutParams();
            if (p.isOverflowButton) {
                overflowWidth = v.getMeasuredWidth();
                if (hasDividerBeforeChildAt(i)) {
                    overflowWidth += dividerWidth;
                }

                int height = v.getMeasuredHeight();
                int r = getWidth() - getPaddingRight();
                int l = r - overflowWidth;
                int t = midVertical - (height / 2);
                int b = t + height;
                v.layout(l, t, r, b);

                widthRemaining -= overflowWidth;
                hasOverflow = true;
            } else {
                final int size = v.getMeasuredWidth() + p.leftMargin + p.rightMargin;
                nonOverflowWidth += size;
                widthRemaining -= size;
                if (hasDividerBeforeChildAt(i)) {
                    nonOverflowWidth += dividerWidth;
                }
                nonOverflowCount++;
            }
        }

        final int spacerCount = nonOverflowCount - (hasOverflow ? 0 : 1);
        final int spacerSize = spacerCount > 0 ? widthRemaining / spacerCount : 0;

        int startLeft = getPaddingLeft();
        for (int i = 0; i < childCount; i++) {
            final View v = getChildAt(i);
            final LayoutParams lp = (LayoutParams) v.getLayoutParams();
            if (v.getVisibility() == GONE || lp.isOverflowButton) {
                continue;
            }

            startLeft += lp.leftMargin;
            int width = v.getMeasuredWidth();
            int height = v.getMeasuredHeight();
            int t = midVertical - (height / 2);
            v.layout(startLeft, t, startLeft + width, t + height);
            startLeft += width + lp.rightMargin + spacerSize;
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mPresenter.dismissPopupMenus();
    }

    public boolean isOverflowReserved() {
        return mReserveOverflow;
    }
    
    public void setOverflowReserved(boolean reserveOverflow) {
        mReserveOverflow = reserveOverflow;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }
    
    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) {
            LayoutParams result = new LayoutParams((LayoutParams) p);
            if (result.gravity <= Gravity.NO_GRAVITY) {
                result.gravity = Gravity.CENTER_VERTICAL;
            }
            return result;
        }
        return generateDefaultLayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p != null && p instanceof LayoutParams;
    }

    public LayoutParams generateOverflowButtonLayoutParams() {
        LayoutParams result = generateDefaultLayoutParams();
        result.isOverflowButton = true;
        return result;
    }

    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    public int getWindowAnimations() {
        return 0;
    }

    public void initialize(MenuBuilder menu) {
        mMenu = menu;
    }

    @Override
    protected boolean hasDividerBeforeChildAt(int childIndex) {
        final View childBefore = getChildAt(childIndex - 1);
        final View child = getChildAt(childIndex);
        boolean result = false;
        if (childIndex < getChildCount() && childBefore instanceof ActionMenuChildView) {
            result |= ((ActionMenuChildView) childBefore).needsDividerAfter();
        }
        if (childIndex > 0 && child instanceof ActionMenuChildView) {
            result |= ((ActionMenuChildView) child).needsDividerBefore();
        }
        return result;
    }

    public interface ActionMenuChildView {
        public boolean needsDividerBefore();
        public boolean needsDividerAfter();
    }

    public static class LayoutParams extends LinearLayout.LayoutParams {
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean isOverflowButton;
        @ViewDebug.ExportedProperty(category = "layout")
        public int cellsUsed;
        @ViewDebug.ExportedProperty(category = "layout")
        public int extraPixels;
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean expandable;

        public boolean expanded;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(LayoutParams other) {
            super((LinearLayout.LayoutParams) other);
            isOverflowButton = other.isOverflowButton;
        }

        public LayoutParams(int width, int height) {
            super(width, height);
            isOverflowButton = false;
        }

        public LayoutParams(int width, int height, boolean isOverflowButton) {
            super(width, height);
            this.isOverflowButton = isOverflowButton;
        }
    }
}
