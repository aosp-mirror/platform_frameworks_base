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

import static android.service.chooser.Flags.announceShortcutsAndSuggestedAppsLegacy;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo;
import android.widget.GridView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.ChooserActivity.ChooserGridAdapter;
import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.RecyclerView;

/**
 * For a11y and per {@link RecyclerView#onInitializeAccessibilityNodeInfo}, override
 * methods to ensure proper row counts.
 */
public class ChooserGridLayoutManager extends GridLayoutManager {

    private CharSequence mShortcutGroupTitle = "";
    private CharSequence mSuggestedAppsGroupTitle = "";
    private CharSequence mAllAppListGroupTitle = "";
    @Nullable
    private RecyclerView mRecyclerView;
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
        if (announceShortcutsAndSuggestedAppsLegacy()) {
            readGroupTitles(context);
        }
    }

    /**
     * Creates a vertical GridLayoutManager
     *
     * @param context   Current context, will be used to access resources.
     * @param spanCount The number of columns in the grid
     */
    public ChooserGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
        if (announceShortcutsAndSuggestedAppsLegacy()) {
            readGroupTitles(context);
        }
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
        if (announceShortcutsAndSuggestedAppsLegacy()) {
            readGroupTitles(context);
        }
    }

    private void readGroupTitles(Context context) {
        mShortcutGroupTitle = context.getString(R.string.shortcut_group_a11y_title);
        mSuggestedAppsGroupTitle = context.getString(R.string.suggested_apps_group_a11y_title);
        mAllAppListGroupTitle = context.getString(R.string.all_apps_group_a11y_title);
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        mRecyclerView = view;
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        mRecyclerView = null;
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

    @Override
    public void onInitializeAccessibilityNodeInfoForItem(
            RecyclerView.Recycler recycler,
            RecyclerView.State state,
            View host,
            AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoForItem(recycler, state, host, info);
        if (announceShortcutsAndSuggestedAppsLegacy() && host instanceof ViewGroup) {
            if (host.getId() == R.id.shortcuts_container) {
                info.setClassName(GridView.class.getName());
                info.setContainerTitle(mShortcutGroupTitle);
                info.setCollectionInfo(createShortcutsA11yCollectionInfo((ViewGroup) host));
            } else if (host.getId() == R.id.chooser_row) {
                RecyclerView.Adapter adapter =
                        mRecyclerView == null ? null : mRecyclerView.getAdapter();
                ChooserListAdapter gridAdapter = adapter instanceof ChooserGridAdapter
                        ? ((ChooserGridAdapter) adapter).getListAdapter()
                        : null;
                info.setClassName(GridView.class.getName());
                info.setCollectionInfo(createSuggestedAppsA11yCollectionInfo((ViewGroup) host));
                if (gridAdapter == null || gridAdapter.getAlphaTargetCount() > 0) {
                    info.setContainerTitle(mSuggestedAppsGroupTitle);
                } else {
                    // if all applications fit into one row, they will be put into the suggested
                    // applications group.
                    info.setContainerTitle(mAllAppListGroupTitle);
                }
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(RecyclerView.Recycler recycler,
            RecyclerView.State state, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(recycler, state, info);
        if (announceShortcutsAndSuggestedAppsLegacy()) {
            info.setContainerTitle(mAllAppListGroupTitle);
        }
    }

    @Override
    public boolean isLayoutHierarchical(RecyclerView.Recycler recycler, RecyclerView.State state) {
        return announceShortcutsAndSuggestedAppsLegacy()
                || super.isLayoutHierarchical(recycler, state);
    }

    private CollectionInfo createShortcutsA11yCollectionInfo(ViewGroup container) {
        int rowCount = 0;
        int columnCount = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View row = container.getChildAt(i);
            int rowColumnCount = 0;
            if (row instanceof ViewGroup rowGroup && row.getVisibility() == View.VISIBLE) {
                for (int j = 0; j < rowGroup.getChildCount(); j++) {
                    View v = rowGroup.getChildAt(j);
                    if (v != null && v.getVisibility() == View.VISIBLE) {
                        rowColumnCount++;
                        if (v instanceof TextView) {
                            // A special case of the no-targets message that also contains an
                            // off-screen item (which looks like a bug).
                            rowColumnCount = 1;
                            break;
                        }
                    }
                }
            }
            if (rowColumnCount > 0) {
                rowCount++;
                columnCount = Math.max(columnCount, rowColumnCount);
            }
        }
        return CollectionInfo.obtain(rowCount, columnCount, false);
    }

    private CollectionInfo createSuggestedAppsA11yCollectionInfo(ViewGroup container) {
        int columnCount = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE) {
                columnCount++;
            }
        }
        return CollectionInfo.obtain(1, columnCount, false);
    }
}
