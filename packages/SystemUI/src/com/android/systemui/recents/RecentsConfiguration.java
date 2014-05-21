/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.R;


/** A static Recents configuration for the current context
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsConfiguration {
    static RecentsConfiguration sInstance;

    DisplayMetrics mDisplayMetrics;

    public Rect systemInsets = new Rect();
    public Rect displayRect = new Rect();

    boolean isLandscape;
    boolean transposeSearchLayoutWithOrientation;
    int searchBarAppWidgetId = -1;

    public float animationPxMovementPerSecond;

    public Interpolator defaultBezierInterpolator;

    public int filteringCurrentViewsMinAnimDuration;
    public int filteringNewViewsMinAnimDuration;
    public int taskBarEnterAnimDuration;
    public int taskBarExitAnimDuration;
    public int taskStackScrollDismissInfoPaneDistance;
    public int taskStackMaxDim;
    public int taskViewInfoPaneAnimDuration;
    public int taskViewRemoveAnimDuration;
    public int taskViewRemoveAnimTranslationXPx;
    public int taskViewTranslationZMinPx;
    public int taskViewTranslationZIncrementPx;
    public int taskViewRoundedCornerRadiusPx;
    public int searchBarSpaceHeightPx;

    public int taskBarViewDefaultBackgroundColor;
    public int taskBarViewDefaultTextColor;
    public int taskBarViewLightTextColor;
    public int taskBarViewDarkTextColor;

    public boolean launchedFromAltTab;
    public boolean launchedWithThumbnailAnimation;

    public boolean developerOptionsEnabled;

    /** Private constructor */
    private RecentsConfiguration() {}

    /** Updates the configuration to the current context */
    public static RecentsConfiguration reinitialize(Context context) {
        if (sInstance == null) {
            sInstance = new RecentsConfiguration();
        }
        sInstance.update(context);
        return sInstance;
    }

    /** Returns the current recents configuration */
    public static RecentsConfiguration getInstance() {
        return sInstance;
    }

    /** Updates the state, given the specified context */
    void update(Context context) {
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        mDisplayMetrics = dm;

        isLandscape = res.getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        transposeSearchLayoutWithOrientation =
                res.getBoolean(R.bool.recents_transpose_search_layout_with_orientation);
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.MeasureAndLayout,
                    "[RecentsConfiguration|orientation]", isLandscape ? "Landscape" : "Portrait",
                    Console.AnsiGreen);
        }

        displayRect.set(0, 0, dm.widthPixels, dm.heightPixels);
        animationPxMovementPerSecond =
                res.getDimensionPixelSize(R.dimen.recents_animation_movement_in_dps_per_second);
        filteringCurrentViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_current_views_min_duration);
        filteringNewViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_new_views_min_duration);
        taskBarEnterAnimDuration =
                res.getInteger(R.integer.recents_animate_task_bar_enter_duration);
        taskBarExitAnimDuration =
                res.getInteger(R.integer.recents_animate_task_bar_exit_duration);
        taskStackScrollDismissInfoPaneDistance = res.getDimensionPixelSize(
                R.dimen.recents_task_stack_scroll_dismiss_info_pane_distance);
        taskStackMaxDim = res.getInteger(R.integer.recents_max_task_stack_view_dim);
        taskViewInfoPaneAnimDuration =
                res.getInteger(R.integer.recents_animate_task_view_info_pane_duration);
        taskViewRemoveAnimDuration =
                res.getInteger(R.integer.recents_animate_task_view_remove_duration);
        taskViewRemoveAnimTranslationXPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_remove_anim_translation_x);
        taskViewRoundedCornerRadiusPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        taskViewTranslationZMinPx = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        taskViewTranslationZIncrementPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_z_increment);
        searchBarSpaceHeightPx = res.getDimensionPixelSize(R.dimen.recents_search_bar_space_height);

        taskBarViewDefaultBackgroundColor =
                res.getColor(R.color.recents_task_bar_default_background_color);
        taskBarViewDefaultTextColor =
                res.getColor(R.color.recents_task_bar_default_text_color);
        taskBarViewLightTextColor =
                res.getColor(R.color.recents_task_bar_light_text_color);
        taskBarViewDarkTextColor =
                res.getColor(R.color.recents_task_bar_dark_text_color);

        defaultBezierInterpolator = AnimationUtils.loadInterpolator(context,
                        com.android.internal.R.interpolator.fast_out_slow_in);

        // Check if the developer options are enabled
        ContentResolver cr = context.getContentResolver();
        developerOptionsEnabled = Settings.Global.getInt(cr,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;

        // Update the search widget id
        SharedPreferences settings = context.getSharedPreferences(context.getPackageName(), 0);
        searchBarAppWidgetId = settings.getInt(Constants.Values.App.Key_SearchAppWidgetId, -1);
    }

    /** Updates the system insets */
    public void updateSystemInsets(Rect insets) {
        systemInsets.set(insets);
    }

    /** Updates the search bar app widget */
    public void updateSearchBarAppWidgetId(Context context, int appWidgetId) {
        searchBarAppWidgetId = appWidgetId;
        SharedPreferences settings = context.getSharedPreferences(context.getPackageName(), 0);
        settings.edit().putInt(Constants.Values.App.Key_SearchAppWidgetId,
                appWidgetId).apply();
    }

    /** Returns whether the search bar app widget exists */
    public boolean hasSearchBarAppWidget() {
        return searchBarAppWidgetId >= 0;
    }

    /**
     * Returns the task stack bounds in the current orientation. These bounds do not account for
     * the system insets.
     */
    public void getTaskStackBounds(int width, int height, Rect taskStackBounds) {
        if (hasSearchBarAppWidget()) {
            Rect searchBarBounds = new Rect();
            getSearchBarBounds(width, height, searchBarBounds);
            if (isLandscape && transposeSearchLayoutWithOrientation) {
                // In landscape, the search bar appears on the left, so shift the task rect right
                taskStackBounds.set(searchBarBounds.width(), 0, width, height);
            } else {
                // In portrait, the search bar appears on the top, so shift the task rect below
                taskStackBounds.set(0, searchBarBounds.height(), width, height);
            }
        } else {
            taskStackBounds.set(0, 0, width, height);
        }
    }

    /**
     * Returns the search bar bounds in the current orientation.  These bounds do not account for
     * the system insets.
     */
    public void getSearchBarBounds(int width, int height, Rect searchBarSpaceBounds) {
        // Return empty rects if search is not enabled
        if (!Constants.DebugFlags.App.EnableSearchLayout) {
            searchBarSpaceBounds.set(0, 0, 0, 0);
            return;
        }

        if (isLandscape && transposeSearchLayoutWithOrientation) {
            // In landscape, the search bar appears on the left
            searchBarSpaceBounds.set(0, 0, searchBarSpaceHeightPx, height);
        } else {
            // In portrait, the search bar appears on the top
            searchBarSpaceBounds.set(0, 0, width, searchBarSpaceHeightPx);
        }
    }

    /** Converts from DPs to PXs */
    public int pxFromDp(float size) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                size, mDisplayMetrics));
    }
    /** Converts from SPs to PXs */
    public int pxFromSp(float size) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                size, mDisplayMetrics));
    }
}
