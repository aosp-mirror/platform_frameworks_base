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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.provider.Settings;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;

/**
 * Application resources that can be retrieved from the application context and are not specifically
 * tied to the current activity.
 */
public class RecentsConfiguration {
    static RecentsConfiguration sInstance;

    private static final int LARGE_SCREEN_MIN_DP = 600;
    private static final int XLARGE_SCREEN_MIN_DP = 720;

    // Variables that are used for global calculations
    private static final float STACK_SIDE_PADDING_PHONES_PCT = 0.03333f;
    private static final float STACK_SIZE_PADDING_TABLETS_PCT = 0.075f;
    private static final float STACK_SIZE_PADDING_LARGE_TABLETS_PCT = 0.15f;
    private static final int SEARCH_BAR_SPACE_HEIGHT_PHONES_DPS = 64;
    private static final int SEARCH_BAR_SPACE_HEIGHT_TABLETS_DPS = 72;

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

    // Launch states
    public RecentsActivityLaunchState mLaunchState = new RecentsActivityLaunchState(this);

    // TODO: Values determined by the current context, needs to be refactored into something that is
    //       agnostic of the activity context, but still calculable from the Recents component for
    //       the transition into recents
    boolean hasTransposedSearchBar;
    boolean hasTransposedNavBar;
    public float taskStackWidthPaddingPct;

    // Since the positions in Recents has to be calculated globally (before the RecentsActivity
    // starts), we need to calculate some resource values ourselves, instead of relying on framework
    // resources.
    public final boolean isLargeScreen;
    public final boolean isXLargeScreen;
    public final int smallestWidth;

    /** Misc **/
    public boolean hasDockedTasks;
    public boolean useHardwareLayers;
    public boolean fakeShadows;
    public int svelteLevel;
    public int searchBarSpaceHeightPx;

    /** Dev options and global settings */
    public boolean lockToAppEnabled;

    /** Private constructor */
    private RecentsConfiguration(Context context, SystemServicesProxy ssp) {
        // Load only resources that can not change after the first load either through developer
        // settings or via multi window
        Context appContext = context.getApplicationContext();
        Resources res = appContext.getResources();
        useHardwareLayers = res.getBoolean(R.bool.config_recents_use_hardware_layers);
        fakeShadows = res.getBoolean(R.bool.config_recents_fake_shadows);
        svelteLevel = res.getInteger(R.integer.recents_svelte_level);

        float density = context.getResources().getDisplayMetrics().density;
        smallestWidth = ssp.getDeviceSmallestWidth();
        isLargeScreen = smallestWidth >= (int) (density * LARGE_SCREEN_MIN_DP);
        isXLargeScreen = smallestWidth >= (int) (density * XLARGE_SCREEN_MIN_DP);
        searchBarSpaceHeightPx = isLargeScreen ?
                (int) (density * SEARCH_BAR_SPACE_HEIGHT_TABLETS_DPS) :
                (int) (density * SEARCH_BAR_SPACE_HEIGHT_PHONES_DPS);
        if (isLargeScreen) {
            taskStackWidthPaddingPct = STACK_SIZE_PADDING_TABLETS_PCT;
        } else if (isXLargeScreen) {
            taskStackWidthPaddingPct = STACK_SIZE_PADDING_LARGE_TABLETS_PCT;
        } else {
            taskStackWidthPaddingPct = STACK_SIDE_PADDING_PHONES_PCT;
        }
    }

    /**
     * Updates the configuration based on the current state of the system
     */
    void update(Context context, SystemServicesProxy ssp, Rect windowRect) {
        // Only update resources that can change after the first load, either through developer
        // settings or via multi window
        lockToAppEnabled = ssp.getSystemSetting(context,
                Settings.System.LOCK_TO_APP_ENABLED) != 0;
        hasDockedTasks = ssp.hasDockedTask();

        // Recompute some values based on the given state, since we can not rely on the resource
        // system to get certain values.
        boolean isLandscape = windowRect.width() > windowRect.height();
        hasTransposedNavBar = isLandscape && isLargeScreen && !isXLargeScreen;
        hasTransposedSearchBar = isLandscape && isLargeScreen && !isXLargeScreen;
    }

    /** Updates the configuration to the current context */
    public static RecentsConfiguration initialize(Context context, SystemServicesProxy ssp) {
        if (sInstance == null) {
            sInstance = new RecentsConfiguration(context, ssp);
        }
        return sInstance;
    }

    /** Returns the current recents configuration */
    public static RecentsConfiguration getInstance() {
        return sInstance;
    }

    /**
     * Returns the activity launch state.
     * TODO: This will be refactored out of RecentsConfiguration.
     */
    public RecentsActivityLaunchState getLaunchState() {
        return mLaunchState;
    }

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        mLaunchState.updateOnConfigurationChange();
    }

    /**
     * Returns the task stack bounds in the current orientation. These bounds do not account for
     * the system insets.
     */
    public void getAvailableTaskStackBounds(Rect windowBounds, int topInset,
            int rightInset, Rect searchBarBounds, Rect taskStackBounds) {
        if (hasTransposedNavBar) {
            // In landscape phones, the search bar appears on the left, but we overlay it on top
            int swInset = getInsetToSmallestWidth(windowBounds.right - rightInset -
                    windowBounds.left);
            taskStackBounds.set(windowBounds.left + swInset, windowBounds.top + topInset,
                    windowBounds.right - swInset - rightInset, windowBounds.bottom);
        } else {
            // In portrait, the search bar appears on the top (which already has the inset)
            int swInset = getInsetToSmallestWidth(windowBounds.right - windowBounds.left);
            taskStackBounds.set(windowBounds.left + swInset, searchBarBounds.bottom,
                    windowBounds.right - swInset, windowBounds.bottom);
        }
    }

    /**
     * Returns the search bar bounds in the current orientation.  These bounds do not account for
     * the system insets.
     */
    public void getSearchBarBounds(Rect windowBounds, int topInset, Rect searchBarSpaceBounds) {
        // Return empty rects if search is not enabled
        int searchBarSize = searchBarSpaceHeightPx;
        if (hasTransposedSearchBar) {
            // In landscape phones, the search bar appears on the left
            searchBarSpaceBounds.set(windowBounds.left, windowBounds.top + topInset,
                    windowBounds.left + searchBarSize, windowBounds.bottom);
        } else {
            // In portrait, the search bar appears on the top
            searchBarSpaceBounds.set(windowBounds.left, windowBounds.top + topInset,
                    windowBounds.right, windowBounds.top + topInset + searchBarSize);
        }
    }

    /**
     * Constrain the width of the landscape stack to the smallest width of the device.
     */
    private int getInsetToSmallestWidth(int availableWidth) {
        if (availableWidth > smallestWidth) {
            return (availableWidth - smallestWidth) / 2;
        }
        return 0;
    }
}
