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

package android.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

/**
 * @hide
 */
public class ButtonGroup extends LinearLayout {
    private static final String LOG = "ButtonGroup";

    private Drawable mDivider;
    private int mDividerWidth;
    private int mDividerHeight;
    private int mButtonBackgroundRes;
    private int mShowDividers;

    /**
     * Don't show any dividers.
     */
    public static final int SHOW_DIVIDER_NONE = 0;
    /**
     * Show a divider at the beginning of the group.
     */
    public static final int SHOW_DIVIDER_BEGINNING = 1;
    /**
     * Show dividers between each item in the group.
     */
    public static final int SHOW_DIVIDER_MIDDLE = 2;
    /**
     * Show a divider at the end of the group.
     */
    public static final int SHOW_DIVIDER_END = 4;

    private final Rect mTempRect = new Rect();

    public ButtonGroup(Context context) {
        this(context, null);
    }
    
    public ButtonGroup(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.buttonGroupStyle);
    }
    
    public ButtonGroup(Context context, AttributeSet attrs, int defStyleRes) {
        super(context, attrs, defStyleRes);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ButtonGroup, defStyleRes, 0);
        
        setDividerDrawable(a.getDrawable(com.android.internal.R.styleable.ButtonGroup_divider));
        mButtonBackgroundRes = a.getResourceId(
                com.android.internal.R.styleable.ButtonGroup_buttonBackground, 0);
        setShowDividers(a.getInt(com.android.internal.R.styleable.ButtonGroup_showDividers,
                SHOW_DIVIDER_MIDDLE));
        a.recycle();
    }

    /**
     * Set how dividers should be shown between items in this button group.
     *
     * @param showDividers One or more of {@link #SHOW_DIVIDER_BEGINNING},
     *                     {@link #SHOW_DIVIDER_MIDDLE}, or {@link #SHOW_DIVIDER_END},
     *                     or {@link #SHOW_DIVIDER_NONE} to show no dividers.
     */
    public void setShowDividers(int showDividers) {
        if (showDividers != mShowDividers) {
            requestLayout();
        }
        mShowDividers = showDividers;
    }

    /**
     * @return A flag set indicating how dividers should be shown around items.
     * @see #setShowDividers(int)
     */
    public int getShowDividers() {
        return mShowDividers;
    }

    /**
     * Set a drawable to be used as a divider between items.
     * @param divider Drawable that will divide each item.
     */
    public void setDividerDrawable(Drawable divider) {
        if (divider == mDivider) {
            return;
        }
        mDivider = divider;
        if (divider != null) {
            mDividerWidth = divider.getIntrinsicWidth();
            mDividerHeight = divider.getIntrinsicHeight();
        } else {
            mDividerWidth = 0;
            mDividerHeight = 0;
        }
        requestLayout();
    }

    /**
     * Retrieve the drawable used to draw dividers between items.
     * @return The divider drawable
     */
    public Drawable getDividerDrawable() {
        return mDivider;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (child instanceof Button && mButtonBackgroundRes != 0) {
            // Preserve original padding as we change the background
            final int paddingLeft = child.getPaddingLeft();
            final int paddingRight = child.getPaddingRight();
            final int paddingTop = child.getPaddingTop();
            final int paddingBottom = child.getPaddingBottom();
            child.setBackgroundResource(mButtonBackgroundRes);
            child.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }

        super.addView(child, index, params);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Add the extra size that dividers contribute.
        int dividerCount = 0;
        if ((mShowDividers & SHOW_DIVIDER_MIDDLE) == SHOW_DIVIDER_MIDDLE) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (getChildAt(i).getVisibility() != GONE) {
                    dividerCount++;
                }
            }
            dividerCount = Math.max(0, dividerCount);
        }
        if ((mShowDividers & SHOW_DIVIDER_BEGINNING) == SHOW_DIVIDER_BEGINNING) {
            dividerCount++;
        }
        if ((mShowDividers & SHOW_DIVIDER_END) == SHOW_DIVIDER_END) {
            dividerCount++;
        }

        if (getOrientation() == VERTICAL) {
            final int dividerSize = mDividerHeight * dividerCount;
            setMeasuredDimension(getMeasuredWidthAndState(),
                    resolveSizeAndState(getMeasuredHeight() + dividerSize, heightMeasureSpec,
                            getMeasuredHeightAndState()));
        } else {
            final int dividerSize = mDividerWidth * dividerCount;
            setMeasuredDimension(resolveSizeAndState(getMeasuredWidth() + dividerSize,
                            widthMeasureSpec, getMeasuredWidthAndState()),
                    getMeasuredHeightAndState());
        }
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        final boolean begin = (mShowDividers & SHOW_DIVIDER_BEGINNING) == SHOW_DIVIDER_BEGINNING;
        final boolean middle = (mShowDividers & SHOW_DIVIDER_MIDDLE) == SHOW_DIVIDER_MIDDLE;

        // Offset children to leave space for dividers.
        if (getOrientation() == VERTICAL) {
            int offset = begin ? mDividerHeight : 0;
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                child.offsetTopAndBottom(offset);
                if (middle && child.getVisibility() != GONE) {
                    offset += mDividerHeight;
                }
            }
        } else {
            int offset = begin ? mDividerWidth : 0;
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                child.offsetLeftAndRight(offset);
                if (middle && child.getVisibility() != GONE) {
                    offset += mDividerWidth;
                }
            }
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        if (mDivider == null) {
            super.dispatchDraw(canvas);
            return;
        }

        final boolean begin = (mShowDividers & SHOW_DIVIDER_BEGINNING) == SHOW_DIVIDER_BEGINNING;
        final boolean middle = (mShowDividers & SHOW_DIVIDER_MIDDLE) == SHOW_DIVIDER_MIDDLE;
        final boolean end = (mShowDividers & SHOW_DIVIDER_END) == SHOW_DIVIDER_END;
        final boolean vertical = getOrientation() == VERTICAL;

        final Rect bounds = mTempRect;
        bounds.left = mPaddingLeft;
        bounds.right = mRight - mLeft - mPaddingRight;
        bounds.top = mPaddingTop;
        bounds.bottom = mBottom - mTop - mPaddingBottom;

        if (begin) {
            if (vertical) {
                bounds.bottom = bounds.top + mDividerHeight;
            } else {
                bounds.right = bounds.left + mDividerWidth;
            }
            mDivider.setBounds(bounds);
            mDivider.draw(canvas);
        }

        final int childCount = getChildCount();
        int i = 0;
        while (i < childCount) {
            final View child = getChildAt(i);
            i++;
            if ((middle && i < childCount && child.getVisibility() != GONE) || end) {
                if (vertical) {
                    bounds.top = child.getBottom();
                    bounds.bottom = bounds.top + mDividerHeight;
                } else {
                    bounds.left = child.getRight();
                    bounds.right = bounds.left + mDividerWidth;
                }
                mDivider.setBounds(bounds);
                mDivider.draw(canvas);
            }
        }

        super.dispatchDraw(canvas);
    }
}
