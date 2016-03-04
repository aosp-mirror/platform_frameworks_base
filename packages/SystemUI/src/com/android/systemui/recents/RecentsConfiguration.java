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

import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;

/**
 * Application resources that can be retrieved from the application context and are not specifically
 * tied to the current activity.
 */
public class RecentsConfiguration {

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
    public RecentsActivityLaunchState mLaunchState = new RecentsActivityLaunchState();

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
    public boolean fakeShadows;
    public int svelteLevel;
    public int searchBarSpaceHeightPx;

    public RecentsConfiguration(Context context) {
        // Load only resources that can not change after the first load either through developer
        // settings or via multi window
        SystemServicesProxy ssp = Recents.getSystemServices();
        Context appContext = context.getApplicationContext();
        Resources res = appContext.getResources();
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
    void update(Rect systemInsets) {
        hasTransposedNavBar = systemInsets.right > 0;
        hasTransposedSearchBar = systemInsets.right > 0;
    }

    /**
     * Returns the activity launch state.
     * TODO: This will be refactored out of RecentsConfiguration.
     */
    public RecentsActivityLaunchState getLaunchState() {
        return mLaunchState;
    }

    /**
     * Called when the configuration has changed, and we want to reset any configuration specific
     * members.
     */
    public void updateOnConfigurationChange() {
        mLaunchState.updateOnConfigurationChange();
    }

    /**
     * Returns the task stack bounds in the current orientation. These bounds do not account for
     * the system insets.
     */
    public void getTaskStackBounds(Rect windowBounds, int topInset,
            int rightInset, Rect searchBarBounds, Rect taskStackBounds) {
        if (hasTransposedNavBar) {
            // In landscape phones, the search bar appears on the left, but we overlay it on top
            taskStackBounds.set(windowBounds.left, windowBounds.top + topInset,
                    windowBounds.right - rightInset, windowBounds.bottom);
        } else {
            // In portrait, the search bar appears on the top (which already has the inset)
            int top = searchBarBounds.isEmpty() ? topInset : 0;
            taskStackBounds.set(windowBounds.left, windowBounds.top + searchBarBounds.bottom + top,
                    windowBounds.right - rightInset, windowBounds.bottom);
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
}
