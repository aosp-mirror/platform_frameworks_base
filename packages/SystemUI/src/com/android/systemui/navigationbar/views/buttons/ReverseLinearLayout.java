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
 * limitations under the License.
 */

package com.android.systemui.navigationbar.views.buttons;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.util.ArrayList;

/**
 * Automatically reverses the order of children as they are added.
 * Also reverse the width and height values of layout params
 */
public class ReverseLinearLayout extends LinearLayout {

    /** If true, the layout is reversed vs. a regular linear layout */
    private boolean mIsLayoutReverse;

    /** If true, the layout is opposite to it's natural reversity from the layout direction */
    private boolean mIsAlternativeOrder;

    public ReverseLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        updateOrder();
    }

    @Override
    public void addView(View child) {
        reverseParams(child.getLayoutParams(), child, mIsLayoutReverse);
        if (mIsLayoutReverse) {
            super.addView(child, 0);
        } else {
            super.addView(child);
        }
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        reverseParams(params, child, mIsLayoutReverse);
        if (mIsLayoutReverse) {
            super.addView(child, 0, params);
        } else {
            super.addView(child, params);
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateOrder();
    }

    public void setAlternativeOrder(boolean alternative) {
        mIsAlternativeOrder = alternative;
        updateOrder();
    }

    /**
     * In landscape, the LinearLayout is not auto mirrored since it is vertical. Therefore we
     * have to do it manually
     */
    private void updateOrder() {
        boolean isLayoutRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean isLayoutReverse = isLayoutRtl ^ mIsAlternativeOrder;

        if (mIsLayoutReverse != isLayoutReverse) {
            // reversity changed, swap the order of all views.
            int childCount = getChildCount();
            ArrayList<View> childList = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                childList.add(getChildAt(i));
            }
            removeAllViews();
            for (int i = childCount - 1; i >= 0; i--) {
                final View child = childList.get(i);
                super.addView(child);
            }
            mIsLayoutReverse = isLayoutReverse;
        }
    }

    private static void reverseParams(ViewGroup.LayoutParams params, View child,
            boolean isLayoutReverse) {
        if (child instanceof Reversable) {
            ((Reversable) child).reverse(isLayoutReverse);
        }
        if (child.getPaddingLeft() == child.getPaddingRight()
                && child.getPaddingTop() == child.getPaddingBottom()) {
            child.setPadding(child.getPaddingTop(), child.getPaddingLeft(),
                    child.getPaddingTop(), child.getPaddingLeft());
        }
        if (params == null) {
            return;
        }
        int width = params.width;
        params.width = params.height;
        params.height = width;
    }

    public interface Reversable {
        void reverse(boolean isLayoutReverse);
    }

    public static class ReverseRelativeLayout extends RelativeLayout implements Reversable {

        public ReverseRelativeLayout(Context context) {
            super(context);
        }

        @Override
        public void reverse(boolean isLayoutReverse) {
            updateGravity(isLayoutReverse);
            reverseGroup(this, isLayoutReverse);
        }

        private int mDefaultGravity = Gravity.NO_GRAVITY;
        public void setDefaultGravity(int gravity) {
            mDefaultGravity = gravity;
        }

        public void updateGravity(boolean isLayoutReverse) {
            // Flip gravity if top of bottom is used
            if (mDefaultGravity != Gravity.TOP && mDefaultGravity != Gravity.BOTTOM) return;

            // Use the default (intended for 270 LTR and 90 RTL) unless layout is otherwise
            int gravityToApply = mDefaultGravity;
            if (isLayoutReverse) {
                gravityToApply = mDefaultGravity == Gravity.TOP ? Gravity.BOTTOM : Gravity.TOP;
            }

            if (getGravity() != gravityToApply) setGravity(gravityToApply);
        }
    }
    
    private static void reverseGroup(ViewGroup group, boolean isLayoutReverse) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            reverseParams(child.getLayoutParams(), child, isLayoutReverse);

            // Recursively reverse all children
            if (child instanceof ViewGroup) {
                reverseGroup((ViewGroup) child, isLayoutReverse);
            }
        }
    }

}
