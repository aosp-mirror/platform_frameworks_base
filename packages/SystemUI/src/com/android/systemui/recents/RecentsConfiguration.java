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

    /** Animations */
    public float animationPxMovementPerSecond;

    /** Interpolators */
    public Interpolator fastOutSlowInInterpolator;
    public Interpolator fastOutLinearInInterpolator;
    public Interpolator linearOutSlowInInterpolator;
    public Interpolator quintOutInterpolator;

    /** Filtering */
    public int filteringCurrentViewsMinAnimDuration;
    public int filteringNewViewsMinAnimDuration;

    /** Insets */
    public Rect systemInsets = new Rect();
    public Rect displayRect = new Rect();

    /** Layout */
    boolean isLandscape;
    boolean transposeRecentsLayoutWithOrientation;

    /** Search bar */
    int searchBarAppWidgetId = -1;
    public int searchBarSpaceHeightPx;

    /** Task stack */
    public int taskStackMaxDim;
    public int taskStackTopPaddingPx;
    public float taskStackWidthPaddingPct;

    /** Task view animation and styles */
    public int taskViewEnterFromHomeDuration;
    public int taskViewEnterFromHomeDelay;
    public int taskViewExitToHomeDuration;
    public int taskViewRemoveAnimDuration;
    public int taskViewRemoveAnimTranslationXPx;
    public int taskViewTranslationZMinPx;
    public int taskViewTranslationZIncrementPx;
    public int taskViewShadowOutlineBottomInsetPx;
    public int taskViewRoundedCornerRadiusPx;
    public int taskViewHighlightPx;

    /** Task bar colors */
    public int taskBarViewDefaultBackgroundColor;
    public int taskBarViewDefaultTextColor;
    public int taskBarViewLightTextColor;
    public int taskBarViewDarkTextColor;
    public int taskBarViewHighlightColor;

    /** Task bar animations */
    public int taskBarEnterAnimDuration;
    public int taskBarEnterAnimDelay;
    public int taskBarExitAnimDuration;
    public int taskBarDismissDozeDelaySeconds;

    /** Nav bar scrim */
    public int navBarScrimEnterDuration;

    /** Launch states */
    public boolean launchedWithAltTab;
    public boolean launchedWithNoRecentTasks;
    public boolean launchedFromAppWithThumbnail;
    public boolean launchedFromAppWithScreenshot;
    public boolean launchedFromHome;

    /** Dev options */
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

        // Animations
        animationPxMovementPerSecond =
                res.getDimensionPixelSize(R.dimen.recents_animation_movement_in_dps_per_second);

        // Interpolators
        fastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        fastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
        linearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.linear_out_slow_in);
        quintOutInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_quint);

        // Filtering
        filteringCurrentViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_current_views_min_duration);
        filteringNewViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_new_views_min_duration);

        // Insets
        displayRect.set(0, 0, dm.widthPixels, dm.heightPixels);

        // Layout
        isLandscape = res.getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        transposeRecentsLayoutWithOrientation =
                res.getBoolean(R.bool.recents_transpose_layout_with_orientation);

        // Search bar
        searchBarSpaceHeightPx = res.getDimensionPixelSize(R.dimen.recents_search_bar_space_height);

        // Update the search widget id
        SharedPreferences settings = context.getSharedPreferences(context.getPackageName(), 0);
        searchBarAppWidgetId = settings.getInt(Constants.Values.App.Key_SearchAppWidgetId, -1);

        // Task stack
        TypedValue widthPaddingPctValue = new TypedValue();
        res.getValue(R.dimen.recents_stack_width_padding_percentage, widthPaddingPctValue, true);
        taskStackWidthPaddingPct = widthPaddingPctValue.getFloat();
        taskStackMaxDim = res.getInteger(R.integer.recents_max_task_stack_view_dim);
        taskStackTopPaddingPx = res.getDimensionPixelSize(R.dimen.recents_stack_top_padding);

        // Task view animation and styles
        taskViewEnterFromHomeDuration =
                res.getInteger(R.integer.recents_animate_task_enter_from_home_duration);
        taskViewEnterFromHomeDelay =
                res.getInteger(R.integer.recents_animate_task_enter_from_home_delay);
        taskViewExitToHomeDuration =
                res.getInteger(R.integer.recents_animate_task_exit_to_home_duration);
        taskViewRemoveAnimDuration =
                res.getInteger(R.integer.recents_animate_task_view_remove_duration);
        taskViewRemoveAnimTranslationXPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_remove_anim_translation_x);
        taskViewRoundedCornerRadiusPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        taskViewHighlightPx = res.getDimensionPixelSize(R.dimen.recents_task_view_highlight);
        taskViewTranslationZMinPx = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        taskViewTranslationZIncrementPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_z_increment);
        taskViewShadowOutlineBottomInsetPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_shadow_outline_bottom_inset);

        // Task bar colors
        taskBarViewDefaultBackgroundColor =
                res.getColor(R.color.recents_task_bar_default_background_color);
        taskBarViewDefaultTextColor =
                res.getColor(R.color.recents_task_bar_default_text_color);
        taskBarViewLightTextColor =
                res.getColor(R.color.recents_task_bar_light_text_color);
        taskBarViewDarkTextColor =
                res.getColor(R.color.recents_task_bar_dark_text_color);
        taskBarViewHighlightColor =
                res.getColor(R.color.recents_task_bar_highlight_color);

        // Task bar animations
        taskBarEnterAnimDuration =
                res.getInteger(R.integer.recents_animate_task_bar_enter_duration);
        taskBarEnterAnimDelay =
                res.getInteger(R.integer.recents_animate_task_bar_enter_delay);
        taskBarExitAnimDuration =
                res.getInteger(R.integer.recents_animate_task_bar_exit_duration);
        taskBarDismissDozeDelaySeconds =
                res.getInteger(R.integer.recents_task_bar_dismiss_delay_seconds);

        // Nav bar scrim
        navBarScrimEnterDuration =
                res.getInteger(R.integer.recents_nav_bar_scrim_enter_duration);

        // Check if the developer options are enabled
        ContentResolver cr = context.getContentResolver();
        developerOptionsEnabled = Settings.Global.getInt(cr,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;

        if (Console.Enabled) {
            Console.log(Constants.Log.UI.MeasureAndLayout,
                    "[RecentsConfiguration|orientation]", isLandscape ? "Landscape" : "Portrait",
                    Console.AnsiGreen);
        }
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

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        launchedWithAltTab = false;
        launchedWithNoRecentTasks = false;
        launchedFromAppWithThumbnail = false;
        launchedFromAppWithScreenshot = false;
        launchedFromHome = false;
    }

    /** Returns whether the search bar app widget exists. */
    public boolean hasSearchBarAppWidget() {
        return searchBarAppWidgetId >= 0;
    }

    /** Returns whether the nav bar scrim should be animated when shown for the first time. */
    public boolean shouldAnimateNavBarScrim() {
        return true;
    }

    /** Returns whether the nav bar scrim should be visible. */
    public boolean hasNavBarScrim() {
        // Only show the scrim if we have recent tasks, and if the nav bar is not transposed
        return !launchedWithNoRecentTasks &&
                (!transposeRecentsLayoutWithOrientation || !isLandscape);
    }

    /**
     * Returns the task stack bounds in the current orientation. These bounds do not account for
     * the system insets.
     */
    public void getTaskStackBounds(int width, int height, Rect taskStackBounds) {
        if (hasSearchBarAppWidget()) {
            Rect searchBarBounds = new Rect();
            getSearchBarBounds(width, height, searchBarBounds);
            if (isLandscape && transposeRecentsLayoutWithOrientation) {
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

        if (isLandscape && transposeRecentsLayoutWithOrientation) {
            // In landscape, the search bar appears on the left
            searchBarSpaceBounds.set(0, 0, searchBarSpaceHeightPx, height);
        } else {
            // In portrait, the search bar appears on the top
            searchBarSpaceBounds.set(0, 0, width, searchBarSpaceHeightPx);
        }
    }
}
