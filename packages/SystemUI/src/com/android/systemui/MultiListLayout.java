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

package com.android.systemui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Layout class representing the Global Actions menu which appears when the power button is held.
 */
public abstract class MultiListLayout extends LinearLayout {
    protected boolean mHasOutsideTouch;
    protected boolean mHasSeparatedView;

    protected int mExpectedSeparatedItemCount;
    protected int mExpectedListItemCount;

    public MultiListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected abstract ViewGroup getSeparatedView();

    protected abstract ViewGroup getListView();

    /**
     * Removes all child items from the separated and list views, if they exist.
     */
    public abstract void removeAllItems();

    /**
     * Get the parent view which will be used to contain the item at the specified index.
     * @param separated Whether or not this index refers to a position in the separated or list
     *                  container.
     * @param index The index of the item within the container.
     * @return The parent ViewGroup which will be used to contain the specified item
     * after it has been added to the layout.
     */
    public abstract ViewGroup getParentView(boolean separated, int index);

    /**
     * Sets the divided view, which may have a differently-colored background.
     */
    public abstract void setDivisionView(View v);

    /**
     * Set the view accessibility delegate for the list view container.
     */
    public void setListViewAccessibilityDelegate(View.AccessibilityDelegate delegate) {
        getListView().setAccessibilityDelegate(delegate);
    }

    protected void setSeparatedViewVisibility(boolean visible) {
        getSeparatedView().setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets the number of items expected to be rendered in the separated container. This allows the
     * layout to correctly determine which parent containers will be used for items before they have
     * beenadded to the layout.
     * @param count The number of items expected.
     */
    public void setExpectedSeparatedItemCount(int count) {
        mExpectedSeparatedItemCount = count;
    }

    /**
     * Sets the number of items expected to be rendered in the list container. This allows the
     * layout to correctly determine which parent containers will be used for items before they have
     * beenadded to the layout.
     * @param count The number of items expected.
     */
    public void setExpectedListItemCount(int count) {
        mExpectedListItemCount = count;
    }

    /**
     * Sets whether the separated view should be shown, and handles updating visibility on
     * that view.
     */
    public void setHasSeparatedView(boolean hasSeparatedView) {
        mHasSeparatedView = hasSeparatedView;
        setSeparatedViewVisibility(hasSeparatedView);
    }

    /**
     * Sets this layout to respond to an outside touch listener.
     */
    public void setOutsideTouchListener(OnClickListener onClickListener) {
        mHasOutsideTouch = true;
        requestLayout();
        setOnClickListener(onClickListener);
        setClickable(true);
        setFocusable(true);
    }

    /**
     * Retrieve the MultiListLayout associated with the given view.
     */
    public static MultiListLayout get(View v) {
        if (v instanceof MultiListLayout) return (MultiListLayout) v;
        if (v.getParent() instanceof View) {
            return get((View) v.getParent());
        }
        return null;
    }
}
