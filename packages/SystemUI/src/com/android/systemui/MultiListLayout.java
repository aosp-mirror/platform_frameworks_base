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
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;

import com.android.systemui.util.leak.RotationUtils;

/**
 * Layout class representing the Global Actions menu which appears when the power button is held.
 */
public abstract class MultiListLayout extends LinearLayout {
    protected boolean mHasOutsideTouch;
    protected MultiListAdapter mAdapter;
    protected int mRotation;
    protected RotationListener mRotationListener;

    public MultiListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRotation = RotationUtils.getRotation(context);
    }

    protected abstract ViewGroup getSeparatedView();

    protected abstract ViewGroup getListView();

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
     * Sets the adapter used to inflate items.
     */
    public void setAdapter(MultiListAdapter adapter) {
        mAdapter = adapter;
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

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int newRotation = RotationUtils.getRotation(mContext);
        if (newRotation != mRotation) {
            rotate(mRotation, newRotation);
            mRotation = newRotation;
        }
    }

    protected void rotate(int from, int to) {
        if (mRotationListener != null) {
            mRotationListener.onRotate(from, to);
        }
    }

    /**
     * Update the list of items in both the separated and list views.
     * For this to work, mAdapter must already have been set.
     */
    public void updateList() {
        if (mAdapter == null) {
            throw new IllegalStateException("mAdapter must be set before calling updateList");
        }
        onUpdateList();
    }

    protected void removeAllSeparatedViews() {
        ViewGroup separated = getSeparatedView();
        if (separated != null) {
            separated.removeAllViews();
        }
    }

    protected void removeAllListViews() {
        ViewGroup list = getListView();
        if (list != null) {
            list.removeAllViews();
        }
    }

    protected void removeAllItems() {
        removeAllListViews();
        removeAllSeparatedViews();
    }

    protected void onUpdateList() {
        removeAllItems();
        setSeparatedViewVisibility(mAdapter.hasSeparatedItems());
    }

    public void setRotationListener(RotationListener listener) {
        mRotationListener = listener;
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

    /**
     * Interface to provide callbacks which trigger when this list detects a rotation.
     */
    public interface RotationListener {
        void onRotate(int from, int to);
    }

    /**
     * Get the X offset in pixels for use when animating the view onto or off of the screen.
     */
    public abstract float getAnimationOffsetX();

    /**
     * Get the Y offset in pixels for use when animating the view onto or off of the screen.
     */
    public abstract float getAnimationOffsetY();

    /**
     * Adapter class for converting items into child views for MultiListLayout and handling
     * callbacks for input events.
     */
    public abstract static class MultiListAdapter extends BaseAdapter {
        /**
         * Counts the number of items to be rendered in the separated view.
         */
        public abstract int countSeparatedItems();

        /**
         * Counts the number of items be rendered in the list view.
         */
        public abstract int countListItems();

        /**
         * Callback to run when an individual item is clicked or pressed.
         * @param position The index of the item which was clicked.
         */
        public abstract void onClickItem(int position);

        /**
         * Callback to run when an individual item is long-clicked or long-pressed.
         * @param position The index of the item which was long-clicked.
         * @return True if the long-click was handled, false otherwise.
         */
        public abstract boolean onLongClickItem(int position);

        /**
         * Determines whether the mAdapter contains any separated items, used to determine whether
         * or not to hide the separated list from view.
         */
        public boolean hasSeparatedItems() {
            return countSeparatedItems() > 0;
        }

        /**
         * Determines whether the item at the given index should be rendered in the separarted view.
         * @param position The index of the item.
         */
        public abstract boolean shouldBeSeparated(int position);
    }
}
