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
    
    private MenuBuilder mMenu;

    private boolean mReserveOverflow;
    private ActionMenuPresenter mPresenter;
    private boolean mUpdateContentsBeforeMeasure;
    private boolean mFormatItems;

    public ActionMenuView(Context context) {
        this(context, null);
    }
    
    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBaselineAligned(false);
    }

    public void setPresenter(ActionMenuPresenter presenter) {
        mPresenter = presenter;
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
    public void requestLayout() {
        // Layout can influence how many action items fit.
        mUpdateContentsBeforeMeasure = true;
        super.requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mUpdateContentsBeforeMeasure && mMenu != null) {
            mMenu.onItemsChanged(true);
            mUpdateContentsBeforeMeasure = false;
        }
        // If we've been given an exact size to match, apply special formatting during layout.
        mFormatItems = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
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
        boolean hasOverflow = false;
        int overflowWidth = 0;
        int nonOverflowWidth = 0;
        int nonOverflowCount = 0;
        int widthRemaining = right - left - getPaddingRight() - getPaddingLeft();
        for (int i = 0; i < childCount; i++) {
            final View v = getChildAt(i);
            if (v.getVisibility() == GONE) {
                continue;
            }

            LayoutParams p = (LayoutParams) v.getLayoutParams();
            if (p.isOverflowButton) {
                hasOverflow = true;
                overflowWidth = v.getMeasuredWidth();
                if (hasDividerBeforeChildAt(i)) {
                    overflowWidth += dividerWidth;
                }

                int height = v.getMeasuredHeight();
                int r = getPaddingRight();
                int l = r - overflowWidth;
                int t = midVertical - (height / 2);
                int b = t + height;
                v.layout(l, t, r, b);

                widthRemaining -= overflowWidth;
            } else {
                nonOverflowWidth += v.getMeasuredWidth() + p.leftMargin + p.rightMargin;
                if (hasDividerBeforeChildAt(i)) {
                    nonOverflowWidth += dividerWidth;
                }
                nonOverflowCount++;
            }
        }

        // Try to center non-overflow items with uniformly spaced padding, including on the edges.
        // Overflow will always pin to the right edge. If there isn't enough room for that,
        // center in the remaining space.
        if (nonOverflowWidth <= widthRemaining - overflowWidth) {
            widthRemaining -= overflowWidth;
        }

        final int spacing = (widthRemaining - nonOverflowWidth) / (nonOverflowCount + 1);
        int startLeft = getPaddingLeft() + overflowWidth + spacing;
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
            startLeft += width + lp.rightMargin + spacing;
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
        return p instanceof LayoutParams;
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
