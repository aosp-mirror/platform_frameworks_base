/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Context;
import android.util.AttributeSet;

import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.RecyclerView;

/**
 * For a11y and per {@link RecyclerView#onInitializeAccessibilityNodeInfo}, override
 * methods to ensure proper row counts.
 */
public class ChooserGridLayoutManager extends GridLayoutManager {

    private boolean mVerticalScrollEnabled = true;

    /**
     * Constructor used when layout manager is set in XML by RecyclerView attribute
     * "layoutManager". If spanCount is not specified in the XML, it defaults to a
     * single column.
     *
     */
    public ChooserGridLayoutManager(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Creates a vertical GridLayoutManager
     *
     * @param context   Current context, will be used to access resources.
     * @param spanCount The number of columns in the grid
     */
    public ChooserGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    /**
     * @param context       Current context, will be used to access resources.
     * @param spanCount     The number of columns or rows in the grid
     * @param orientation   Layout orientation. Should be {@link #HORIZONTAL} or {@link
     *                      #VERTICAL}.
     * @param reverseLayout When set to true, layouts from end to start.
     */
    public ChooserGridLayoutManager(Context context, int spanCount, int orientation,
            boolean reverseLayout) {
        super(context, spanCount, orientation, reverseLayout);
    }

    @Override
    public int getRowCountForAccessibility(RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        // Do not count the footer view in the official count
        return super.getRowCountForAccessibility(recycler, state) - 1;
    }

    void setVerticalScrollEnabled(boolean verticalScrollEnabled) {
        mVerticalScrollEnabled = verticalScrollEnabled;
    }

    @Override
    public boolean canScrollVertically() {
        return mVerticalScrollEnabled && super.canScrollVertically();
    }
}
