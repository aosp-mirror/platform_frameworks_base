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

import android.app.ActivityManager;
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
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.SystemServicesProxy;


/** A static Recents configuration for the current context
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsConfiguration {
    static RecentsConfiguration sInstance;
    static int sPrevConfigurationHashCode;

    /** Levels of svelte in increasing severity/austerity. */
    // No svelting.
    public static final int SVELTE_NONE = 0;
    // Limit thumbnail cache to number of visible thumbnails when Recents was loaded, disable
    // caching thumbnails as you scroll.
    public static final int SVELTE_LIMIT_CACHE = 1;
    // Disable the thumbnail cache, load thumbnails asynchronously when the activity loads and
    // evict all thumbnails when hidden.
    public static final int SVELTE_DISABLE_CACHE = 2;
    // Disable all thumbnail loading.
    public static final int SVELTE_DISABLE_LOADING = 3;

    /** Animations */
    public float animationPxMovementPerSecond;

    /** Interpolators */
    public Interpolator fastOutSlowInInterpolator;
    public Interpolator fastOutLinearInInterpolator;
    public Interpolator linearOutSlowInInterpolator;
    public Interpolator quintOutInterpolator;

    /** Filtering */
    public int filteringCurrentViewsAnimDuration;
    public int filteringNewViewsAnimDuration;

    /** Insets */
    public Rect systemInsets = new Rect();
    public Rect displayRect = new Rect();

    /** Layout */
    boolean isLandscape;
    boolean hasTransposedSearchBar;
    boolean hasTransposedNavBar;

    /** Loading */
    public int maxNumTasksToLoad;

    /** Search bar */
    int searchBarAppWidgetId = -1;
    public int searchBarSpaceHeightPx;

    /** Task stack */
    public int taskStackScrollDuration;
    public int taskStackMaxDim;
    public int taskStackTopPaddingPx;
    public float taskStackWidthPaddingPct;
    public float taskStackOverscrollPct;

    /** Transitions */
    public int transitionEnterFromAppDelay;
    public int transitionEnterFromHomeDelay;

    /** Task view animation and styles */
    public int taskViewEnterFromAppDuration;
    public int taskViewEnterFromHomeDuration;
    public int taskViewEnterFromHomeStaggerDelay;
    public int taskViewExitToAppDuration;
    public int taskViewExitToHomeDuration;
    public int taskViewRemoveAnimDuration;
    public int taskViewRemoveAnimTranslationXPx;
    public int taskViewTranslationZMinPx;
    public int taskViewTranslationZMaxPx;
    public int taskViewRoundedCornerRadiusPx;
    public int taskViewHighlightPx;
    public int taskViewAffiliateGroupEnterOffsetPx;
    public float taskViewThumbnailAlpha;

    /** Task bar colors */
    public int taskBarViewDefaultBackgroundColor;
    public int taskBarViewLightTextColor;
    public int taskBarViewDarkTextColor;
    public int taskBarViewHighlightColor;
    public float taskBarViewAffiliationColorMinAlpha;

    /** Task bar size & animations */
    public int taskBarHeight;
    public int taskBarDismissDozeDelaySeconds;

    /** Nav bar scrim */
    public int navBarScrimEnterDuration;

    /** Launch states */
    public boolean launchedWithAltTab;
    public boolean launchedWithNoRecentTasks;
    public boolean launchedFromAppWithThumbnail;
    public boolean launchedFromHome;
    public boolean launchedFromSearchHome;
    public boolean launchedReuseTaskStackViews;
    public boolean launchedHasConfigurationChanged;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    /** Misc **/
    public boolean useHardwareLayers;
    public int altTabKeyDelay;
    public boolean fakeShadows;

    /** Dev options and global settings */
    public boolean lockToAppEnabled;
    public boolean developerOptionsEnabled;
    public boolean debugModeEnabled;
    public int svelteLevel;

    /** Private constructor */
    private RecentsConfiguration(Context context) {
        // Properties that don't have to be reloaded with each configuration change can be loaded
        // here.

        // Interpolators
        fastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        fastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
        linearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.linear_out_slow_in);
        quintOutInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_quint);
    }

    /** Updates the configuration to the current context */
    public static RecentsConfiguration reinitialize(Context context, SystemServicesProxy ssp) {
        if (sInstance == null) {
            sInstance = new RecentsConfiguration(context);
        }
        int configHashCode = context.getResources().getConfiguration().hashCode();
        if (sPrevConfigurationHashCode != configHashCode) {
            sInstance.update(context);
            sPrevConfigurationHashCode = configHashCode;
        }
        sInstance.updateOnReinitialize(context, ssp);
        return sInstance;
    }

    /** Returns the current recents configuration */
    public static RecentsConfiguration getInstance() {
        return sInstance;
    }

    /** Updates the state, given the specified context */
    void update(Context context) {
        SharedPreferences settings = context.getSharedPreferences(context.getPackageName(), 0);
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();

        // Debug mode
        debugModeEnabled = settings.getBoolean(Constants.Values.App.Key_DebugModeEnabled, false);
        if (debugModeEnabled) {
            Console.Enabled = true;
        }

        // Layout
        isLandscape = res.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        hasTransposedSearchBar = res.getBoolean(R.bool.recents_has_transposed_search_bar);
        hasTransposedNavBar = res.getBoolean(R.bool.recents_has_transposed_nav_bar);

        // Insets
        displayRect.set(0, 0, dm.widthPixels, dm.heightPixels);

        // Animations
        animationPxMovementPerSecond =
                res.getDimensionPixelSize(R.dimen.recents_animation_movement_in_dps_per_second);

        // Filtering
        filteringCurrentViewsAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_current_views_duration);
        filteringNewViewsAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_new_views_duration);

        // Loading
        maxNumTasksToLoad = ActivityManager.getMaxRecentTasksStatic();

        // Search Bar
        searchBarSpaceHeightPx = res.getDimensionPixelSize(R.dimen.recents_search_bar_space_height);
        searchBarAppWidgetId = settings.getInt(Constants.Values.App.Key_SearchAppWidgetId, -1);

        // Task stack
        taskStackScrollDuration =
                res.getInteger(R.integer.recents_animate_task_stack_scroll_duration);
        TypedValue widthPaddingPctValue = new TypedValue();
        res.getValue(R.dimen.recents_stack_width_padding_percentage, widthPaddingPctValue, true);
        taskStackWidthPaddingPct = widthPaddingPctValue.getFloat();
        TypedValue stackOverscrollPctValue = new TypedValue();
        res.getValue(R.dimen.recents_stack_overscroll_percentage, stackOverscrollPctValue, true);
        taskStackOverscrollPct = stackOverscrollPctValue.getFloat();
        taskStackMaxDim = res.getInteger(R.integer.recents_max_task_stack_view_dim);
        taskStackTopPaddingPx = res.getDimensionPixelSize(R.dimen.recents_stack_top_padding);

        // Transition
        transitionEnterFromAppDelay =
                res.getInteger(R.integer.recents_enter_from_app_transition_duration);
        transitionEnterFromHomeDelay =
                res.getInteger(R.integer.recents_enter_from_home_transition_duration);

        // Task view animation and styles
        taskViewEnterFromAppDuration =
                res.getInteger(R.integer.recents_task_enter_from_app_duration);
        taskViewEnterFromHomeDuration =
                res.getInteger(R.integer.recents_task_enter_from_home_duration);
        taskViewEnterFromHomeStaggerDelay =
                res.getInteger(R.integer.recents_task_enter_from_home_stagger_delay);
        taskViewExitToAppDuration =
                res.getInteger(R.integer.recents_task_exit_to_app_duration);
        taskViewExitToHomeDuration =
                res.getInteger(R.integer.recents_task_exit_to_home_duration);
        taskViewRemoveAnimDuration =
                res.getInteger(R.integer.recents_animate_task_view_remove_duration);
        taskViewRemoveAnimTranslationXPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_remove_anim_translation_x);
        taskViewRoundedCornerRadiusPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        taskViewHighlightPx = res.getDimensionPixelSize(R.dimen.recents_task_view_highlight);
        taskViewTranslationZMinPx = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        taskViewTranslationZMaxPx = res.getDimensionPixelSize(R.dimen.recents_task_view_z_max);
        taskViewAffiliateGroupEnterOffsetPx =
                res.getDimensionPixelSize(R.dimen.recents_task_view_affiliate_group_enter_offset);
        TypedValue thumbnailAlphaValue = new TypedValue();
        res.getValue(R.dimen.recents_task_view_thumbnail_alpha, thumbnailAlphaValue, true);
        taskViewThumbnailAlpha = thumbnailAlphaValue.getFloat();

        // Task bar colors
        taskBarViewDefaultBackgroundColor =
                res.getColor(R.color.recents_task_bar_default_background_color);
        taskBarViewLightTextColor =
                res.getColor(R.color.recents_task_bar_light_text_color);
        taskBarViewDarkTextColor =
                res.getColor(R.color.recents_task_bar_dark_text_color);
        taskBarViewHighlightColor =
                res.getColor(R.color.recents_task_bar_highlight_color);
        TypedValue affMinAlphaPctValue = new TypedValue();
        res.getValue(R.dimen.recents_task_affiliation_color_min_alpha_percentage, affMinAlphaPctValue, true);
        taskBarViewAffiliationColorMinAlpha = affMinAlphaPctValue.getFloat();

        // Task bar size & animations
        taskBarHeight = res.getDimensionPixelSize(R.dimen.recents_task_bar_height);
        taskBarDismissDozeDelaySeconds =
                res.getInteger(R.integer.recents_task_bar_dismiss_delay_seconds);

        // Nav bar scrim
        navBarScrimEnterDuration =
                res.getInteger(R.integer.recents_nav_bar_scrim_enter_duration);

        // Misc
        useHardwareLayers = res.getBoolean(R.bool.config_recents_use_hardware_layers);
        altTabKeyDelay = res.getInteger(R.integer.recents_alt_tab_key_delay);
        fakeShadows = res.getBoolean(R.bool.config_recents_fake_shadows);
        svelteLevel = res.getInteger(R.integer.recents_svelte_level);
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

    /** Updates the states that need to be re-read whenever we re-initialize. */
    void updateOnReinitialize(Context context, SystemServicesProxy ssp) {
        // Check if the developer options are enabled
        developerOptionsEnabled = ssp.getGlobalSetting(context,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) != 0;
        lockToAppEnabled = ssp.getSystemSetting(context,
                Settings.System.LOCK_TO_APP_ENABLED) != 0;
    }

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        // Reset this flag on configuration change to ensure that we recreate new task views
        launchedReuseTaskStackViews = false;
        // Set this flag to indicate that the configuration has changed since Recents last launched
        launchedHasConfigurationChanged = true;
    }

    /** Returns whether the search bar app widget exists. */
    public boolean hasSearchBarAppWidget() {
        return searchBarAppWidgetId >= 0;
    }

    /** Returns whether the status bar scrim should be animated when shown for the first time. */
    public boolean shouldAnimateStatusBarScrim() {
        return launchedFromHome;
    }

    /** Returns whether the status bar scrim should be visible. */
    public boolean hasStatusBarScrim() {
        return !launchedWithNoRecentTasks;
    }

    /** Returns whether the nav bar scrim should be animated when shown for the first time. */
    public boolean shouldAnimateNavBarScrim() {
        return true;
    }

    /** Returns whether the nav bar scrim should be visible. */
    public boolean hasNavBarScrim() {
        // Only show the scrim if we have recent tasks, and if the nav bar is not transposed
        return !launchedWithNoRecentTasks && (!hasTransposedNavBar || !isLandscape);
    }

    /** Returns whether the current layout is horizontal. */
    public boolean hasHorizontalLayout() {
        return isLandscape && hasTransposedSearchBar;
    }

    /**
     * Returns the task stack bounds in the current orientation. These bounds do not account for
     * the system insets.
     */
    public void getTaskStackBounds(int windowWidth, int windowHeight, int topInset, int rightInset,
                                   Rect taskStackBounds) {
        Rect searchBarBounds = new Rect();
        getSearchBarBounds(windowWidth, windowHeight, topInset, searchBarBounds);
        if (isLandscape && hasTransposedSearchBar) {
            // In landscape, the search bar appears on the left, but we overlay it on top
            taskStackBounds.set(0, topInset, windowWidth - rightInset, windowHeight);
        } else {
            // In portrait, the search bar appears on the top (which already has the inset)
            taskStackBounds.set(0, searchBarBounds.bottom, windowWidth, windowHeight);
        }
    }

    /**
     * Returns the search bar bounds in the current orientation.  These bounds do not account for
     * the system insets.
     */
    public void getSearchBarBounds(int windowWidth, int windowHeight, int topInset,
                                   Rect searchBarSpaceBounds) {
        // Return empty rects if search is not enabled
        int searchBarSize = searchBarSpaceHeightPx;
        if (!Constants.DebugFlags.App.EnableSearchLayout || !hasSearchBarAppWidget()) {
            searchBarSize = 0;
        }

        if (isLandscape && hasTransposedSearchBar) {
            // In landscape, the search bar appears on the left
            searchBarSpaceBounds.set(0, topInset, searchBarSize, windowHeight);
        } else {
            // In portrait, the search bar appears on the top
            searchBarSpaceBounds.set(0, topInset, windowWidth, topInset + searchBarSize);
        }
    }
}
