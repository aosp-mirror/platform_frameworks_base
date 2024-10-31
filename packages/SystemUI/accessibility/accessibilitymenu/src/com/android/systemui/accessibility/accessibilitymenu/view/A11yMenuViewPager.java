/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.view;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.GridView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService;
import com.android.systemui.accessibility.accessibilitymenu.R;
import com.android.systemui.accessibility.accessibilitymenu.activity.A11yMenuSettingsActivity.A11yMenuPreferenceFragment;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut;
import com.android.systemui.accessibility.accessibilitymenu.view.A11yMenuFooter.A11yMenuFooterCallBack;

import java.util.ArrayList;
import java.util.List;

/**
 * This class handles UI for viewPager and footer.
 * It displays grid pages containing all shortcuts in viewPager,
 * and handles the click events from footer to switch between pages.
 */
public class A11yMenuViewPager {

    /** The default index of the ViewPager. */
    public static final int DEFAULT_PAGE_INDEX = 0;

    /**
     * The class holds the static parameters for grid view when large button settings is on/off.
     */
    public static final class GridViewParams {
        /** Total shortcuts count in the grid view when large button settings is off. */
        public static final int GRID_ITEM_COUNT = 9;

        /** The number of columns in the grid view when large button settings is off. */
        public static final int GRID_COLUMN_COUNT = 3;

        /** Total shortcuts count in the grid view when large button settings is on. */
        public static final int LARGE_GRID_ITEM_COUNT = 4;

        /** The number of columns in the grid view when large button settings is on. */
        public static final int LARGE_GRID_COLUMN_COUNT = 2;

        /**
         * Returns the number of items in the grid view.
         *
         * @param context The parent context
         * @return Grid item count
         */
        public static int getGridItemCount(Context context) {
            return A11yMenuPreferenceFragment.isLargeButtonsEnabled(context)
                   ? LARGE_GRID_ITEM_COUNT
                   : GRID_ITEM_COUNT;
        }

        /**
         * Returns the number of columns in the grid view.
         *
         * @param context The parent context
         * @return Grid column count
         */
        public static int getGridColumnCount(Context context) {
            return A11yMenuPreferenceFragment.isLargeButtonsEnabled(context)
                   ? LARGE_GRID_COLUMN_COUNT
                   : GRID_COLUMN_COUNT;
        }

        /**
         * Returns the number of rows in the grid view.
         *
         * @param context The parent context
         * @return Grid row count
         */
        public static int getGridRowCount(Context context) {
            return A11yMenuPreferenceFragment.isLargeButtonsEnabled(context)
                   ? (LARGE_GRID_ITEM_COUNT / LARGE_GRID_COLUMN_COUNT)
                   : (GRID_ITEM_COUNT / GRID_COLUMN_COUNT);
        }

        /**
         * Separates a provided list of accessibility shortcuts into multiple sub-lists.
         * Does not modify the original list.
         *
         * @param pageItemCount The maximum size of an individual sub-list.
         * @param shortcutList The list of shortcuts to be separated into sub-lists.
         * @return A list of shortcut sub-lists.
         */
        public static List<List<A11yMenuShortcut>> generateShortcutSubLists(
                int pageItemCount, List<A11yMenuShortcut> shortcutList) {
            int start = 0;
            int end;
            int shortcutListSize = shortcutList.size();
            List<List<A11yMenuShortcut>> subLists = new ArrayList<>();
            while (start < shortcutListSize) {
                end = Math.min(start + pageItemCount, shortcutListSize);
                subLists.add(shortcutList.subList(start, end));
                start = end;
            }
            return subLists;
        }

        private GridViewParams() {}
    }

    private final AccessibilityMenuService mService;

    /**
     * The pager widget, which handles animation and allows swiping horizontally to access previous
     * and next gridView pages.
     */
    protected ViewPager2 mViewPager;

    private ViewPagerAdapter mViewPagerAdapter;
    private final List<GridView> mGridPageList = new ArrayList<>();

    /** The footer, which provides buttons to switch between pages */
    protected A11yMenuFooter mA11yMenuFooter;

    /** The shortcut list intended to show in grid pages of viewPager */
    private List<A11yMenuShortcut> mA11yMenuShortcutList;

    /** The container layout for a11y menu. */
    private ViewGroup mA11yMenuLayout;

    public A11yMenuViewPager(AccessibilityMenuService service) {
        this.mService = service;
    }

    /**
     * Configures UI for view pager and footer.
     *
     * @param a11yMenuLayout the container layout for a11y menu
     * @param shortcutDataList the data list need to show in view pager
     * @param pageIndex the index of ViewPager to show
     */
    public void configureViewPagerAndFooter(
            ViewGroup a11yMenuLayout, List<A11yMenuShortcut> shortcutDataList, int pageIndex) {
        this.mA11yMenuLayout = a11yMenuLayout;
        mA11yMenuShortcutList = shortcutDataList;
        initViewPager();
        initChildPage();
        if (mA11yMenuFooter == null) {
            mA11yMenuFooter = new A11yMenuFooter(a11yMenuLayout, mFooterCallbacks);
        }
        mA11yMenuFooter.updateRightToLeftDirection(
                a11yMenuLayout.getResources().getConfiguration());
        updateFooterState();
        registerOnGlobalLayoutListener();
        goToPage(pageIndex);
    }

    /** Initializes viewPager and its adapter. */
    private void initViewPager() {
        mViewPager = mA11yMenuLayout.findViewById(R.id.view_pager);
        mViewPagerAdapter = new ViewPagerAdapter(mService);
        mViewPager.setOffscreenPageLimit(2);
        mViewPager.setAdapter(mViewPagerAdapter);
        mViewPager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mViewPager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        updateFooterState();
                    }
                });
    }

    /** Creates child pages of viewPager by the length of shortcuts and initializes them. */
    private void initChildPage() {
        if (mA11yMenuShortcutList == null || mA11yMenuShortcutList.isEmpty()) {
            return;
        }

        if (!mGridPageList.isEmpty()) {
            mGridPageList.clear();
        }

        mViewPagerAdapter.set(GridViewParams.generateShortcutSubLists(
                GridViewParams.getGridItemCount(mService), mA11yMenuShortcutList));
    }

    /** Updates footer's state by index of current page in view pager. */
    public void updateFooterState() {
        int currentPage = mViewPager.getCurrentItem();
        int lastPage = mViewPager.getAdapter().getItemCount() - 1;
        mA11yMenuFooter.getPreviousPageBtn().setEnabled(currentPage > 0);
        mA11yMenuFooter.getNextPageBtn().setEnabled(currentPage < lastPage);
    }

    private void goToPage(int pageIndex) {
        if (mViewPager == null) {
            return;
        }
        if ((pageIndex >= 0) && (pageIndex < mViewPager.getAdapter().getItemCount())) {
            mViewPager.setCurrentItem(pageIndex);
        }
    }

    /** Registers OnGlobalLayoutListener to adjust menu UI by running callback at first time. */
    private void registerOnGlobalLayoutListener() {
        mA11yMenuLayout
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new OnGlobalLayoutListener() {

                            boolean mIsFirstTime = true;

                            @Override
                            public void onGlobalLayout() {
                                if (!mIsFirstTime) {
                                    return;
                                }

                                if (mViewPagerAdapter.getItemCount() == 0) {
                                    return;
                                }

                                RecyclerView.ViewHolder viewHolder =
                                        ((RecyclerView) mViewPager.getChildAt(0))
                                                .findViewHolderForAdapterPosition(0);
                                if (viewHolder == null) {
                                    return;
                                }
                                GridView firstGridView = (GridView) viewHolder.itemView;
                                if (firstGridView == null
                                        || firstGridView.getChildAt(0) == null) {
                                    return;
                                }

                                mIsFirstTime = false;

                                int gridItemHeight = firstGridView.getChildAt(0)
                                                .getMeasuredHeight();
                                adjustMenuUISize(gridItemHeight);
                            }
                        });
    }

    /**
     * Adjusts menu UI to fit both landscape and portrait mode.
     *
     * <ol>
     *   <li>Adjust view pager's height.
     *   <li>Adjust vertical interval between grid items.
     *   <li>Adjust padding in view pager.
     * </ol>
     */
    private void adjustMenuUISize(int gridItemHeight) {
        final int rowsInGridView = GridViewParams.getGridRowCount(mService);
        final int defaultMargin =
                (int) mService.getResources().getDimension(R.dimen.a11ymenu_layout_margin);
        final int topMargin = (int) mService.getResources().getDimension(R.dimen.table_margin_top);
        final int displayMode = mService.getResources().getConfiguration().orientation;
        int viewPagerHeight = mViewPager.getMeasuredHeight();

        if (displayMode == Configuration.ORIENTATION_PORTRAIT) {
            // In portrait mode, we only need to adjust view pager's height to match its
            // child's height.
            viewPagerHeight = gridItemHeight * rowsInGridView + defaultMargin + topMargin;
        } else if (displayMode == Configuration.ORIENTATION_LANDSCAPE) {
            // In landscape mode, we need to adjust view pager's height to match screen height
            // and adjust its child too,
            // because a11y menu layout height is limited by the screen height.
            DisplayMetrics displayMetrics = mService.getResources().getDisplayMetrics();
            float densityScale = (float) displayMetrics.densityDpi
                    / DisplayMetrics.DENSITY_DEVICE_STABLE;
            // Keeps footer window height unchanged no matter the density is changed.
            mA11yMenuFooter.adjustFooterToDensityScale(densityScale);
            // Adjust the view pager height for system bar and display cutout insets.
            WindowManager windowManager = mA11yMenuLayout.getContext()
                    .getSystemService(WindowManager.class);
            WindowMetrics windowMetric = windowManager.getCurrentWindowMetrics();
            Insets windowInsets = windowMetric.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            viewPagerHeight =
                    windowMetric.getBounds().height()
                            - mA11yMenuFooter.getHeight()
                            - windowInsets.bottom;
            // Sets vertical interval between grid items.
            int interval =
                    (viewPagerHeight - topMargin - defaultMargin
                            - (rowsInGridView * gridItemHeight))
                            / (rowsInGridView + 1);
            // The interval is negative number when the viewPagerHeight is not able to fit
            // the grid items, which result in text overlapping.
            // Adjust the interval to 0 could solve the issue.
            interval = Math.max(interval, 0);
            mViewPagerAdapter.setVerticalSpacing(interval);

            // Sets padding to view pager.
            final int finalMarginTop = interval + topMargin;
            mViewPager.setPadding(0, finalMarginTop, 0, defaultMargin);
        }
        final ViewGroup.LayoutParams layoutParams = mViewPager.getLayoutParams();
        layoutParams.height = viewPagerHeight;
        mViewPager.setLayoutParams(layoutParams);
    }

    /** Callback object to handle click events from A11yMenuFooter */
    protected A11yMenuFooterCallBack mFooterCallbacks =
            new A11yMenuFooterCallBack() {
                @Override
                public void onPreviousButtonClicked() {
                    // Moves to previous page.
                    int targetPage = mViewPager.getCurrentItem() - 1;
                    goToPage(targetPage);
                    updateFooterState();
                }

                @Override
                public void onNextButtonClicked() {
                    // Moves to next page.
                    int targetPage = mViewPager.getCurrentItem() + 1;
                    goToPage(targetPage);
                    updateFooterState();
                }
            };
}
