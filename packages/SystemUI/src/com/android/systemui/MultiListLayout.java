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

import java.util.ArrayList;

/**
 * Layout class representing the Global Actions menu which appears when the power button is held.
 */
public abstract class MultiListLayout extends LinearLayout {
    protected boolean mHasOutsideTouch;
    protected MultiListAdapter mAdapter;
    protected boolean mSnapToEdge;

    protected int mRotation;
    protected RotationListener mRotationListener;

    public MultiListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRotation = RotationUtils.getRotation(context);
    }

    protected abstract ViewGroup getSeparatedView();

    protected abstract ViewGroup getListView();

    /**
     * Removes all child items from the separated and list views, if they exist.
     */
    public abstract void removeAllItems();

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
     * Sets whether the GlobalActions view should snap to the edge of the screen.
     */
    public void setSnapToEdge(boolean snap) {
        mSnapToEdge = snap;
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

    protected void onUpdateList() {
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
     * Adapter class for converting items into child views for MultiListLayout and handling
     * callbacks for input events.
     */
    public abstract static class MultiListAdapter extends BaseAdapter {
        /**
         * Creates an ArrayList of items which should be rendered in the separated view.
         * @param useSeparatedView is true if the separated view will be used, false otherwise.
         */
        public abstract ArrayList getSeparatedItems();

        /**
         * Creates an ArrayList of items which should be rendered in the list view.
         * @param useSeparatedView True if the separated view will be used, false otherwise.
         */
        public abstract ArrayList getListItems();

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
            return getSeparatedItems().size() > 0;
        }
    }
}
