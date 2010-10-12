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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class ButtonGroup extends LinearLayout {
    private Drawable mDivider;
    private Drawable mButtonBackground;
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
        
        mDivider = a.getDrawable(com.android.internal.R.styleable.ButtonGroup_divider);
        mButtonBackground = a.getDrawable(
                com.android.internal.R.styleable.ButtonGroup_buttonBackground);
        mShowDividers = a.getInt(com.android.internal.R.styleable.ButtonGroup_showDividers,
                SHOW_DIVIDER_MIDDLE);
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
        mShowDividers = showDividers;
    }

    /**
     * @return A flag set indicating how dividers should be shown around items.
     * @see #setShowDividers(int)
     */
    public int getShowDividers() {
        return mShowDividers;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!hasDividerBefore(index)) {
            if (((getChildCount() > 0
                    && (mShowDividers & SHOW_DIVIDER_MIDDLE) == SHOW_DIVIDER_MIDDLE)
                    || (mShowDividers & SHOW_DIVIDER_BEGINNING) == SHOW_DIVIDER_BEGINNING)) {
                super.addView(new DividerView(mContext), index, makeDividerLayoutParams());
                if (index >= 0) {
                    index++;
                }
            }
        }

        // Preserve original padding as we change the background
        final int paddingLeft = child.getPaddingLeft();
        final int paddingRight = child.getPaddingRight();
        final int paddingTop = child.getPaddingTop();
        final int paddingBottom = child.getPaddingBottom();
        child.setBackgroundDrawable(mButtonBackground);
        child.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);

        final boolean isLast = index < 0 || index == getChildCount();
        super.addView(child, index, params);

        if (index >= 0) {
            index++;
        }
        if ((isLast && (mShowDividers & SHOW_DIVIDER_END) == SHOW_DIVIDER_END) ||
                ((mShowDividers & SHOW_DIVIDER_MIDDLE) == SHOW_DIVIDER_MIDDLE &&
                        !(getChildAt(index) instanceof DividerView))) {
            super.addView(new DividerView(mContext), index, makeDividerLayoutParams());
        }
    }
    
    private boolean hasDividerBefore(int index) {
        if (index == -1) {
            index = getChildCount();
        }
        index--;
        if (index < 0) {
            return false;
        }
        return getChildAt(index) instanceof DividerView;
    }

    private LayoutParams makeDividerLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    }

    private class DividerView extends ImageView {
        public DividerView(Context context) {
            super(context);
            setImageDrawable(mDivider);
            setScaleType(ImageView.ScaleType.FIT_XY);
        }
    }
}
