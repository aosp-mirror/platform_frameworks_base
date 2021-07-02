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

package com.android.server.wm;

import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;

import android.util.ArraySet;
import android.view.Display;
import android.view.Display.Mode;
import android.view.DisplayInfo;

/**
 * Policy to select a lower refresh rate for the display if applicable.
 */
class RefreshRatePolicy {

    private final Mode mLowRefreshRateMode;
    private final ArraySet<String> mNonHighRefreshRatePackages = new ArraySet<>();
    private final HighRefreshRateDenylist mHighRefreshRateDenylist;
    private final WindowManagerService mWmService;

    /**
     * The following constants represent priority of the window. SF uses this information when
     * deciding which window has a priority when deciding about the refresh rate of the screen.
     * Priority 0 is considered the highest priority. -1 means that the priority is unset.
     */
    static final int LAYER_PRIORITY_UNSET = -1;
    /** Windows that are in focus and voted for the preferred mode ID have the highest priority. */
    static final int LAYER_PRIORITY_FOCUSED_WITH_MODE = 0;
    /**
     * This is a default priority for all windows that are in focus, but have not requested a
     * specific mode ID.
     */
    static final int LAYER_PRIORITY_FOCUSED_WITHOUT_MODE = 1;
    /**
     * Windows that are not in focus, but voted for a specific mode ID should be
     * acknowledged by SF. For example, there are two applications in a split screen.
     * One voted for a given mode ID, and the second one doesn't care. Even though the
     * second one might be in focus, we can honor the mode ID of the first one.
     */
    static final int LAYER_PRIORITY_NOT_FOCUSED_WITH_MODE = 2;

    RefreshRatePolicy(WindowManagerService wmService, DisplayInfo displayInfo,
            HighRefreshRateDenylist denylist) {
        mLowRefreshRateMode = findLowRefreshRateMode(displayInfo);
        mHighRefreshRateDenylist = denylist;
        mWmService = wmService;
    }

    /**
     * Finds the mode id with the lowest refresh rate which is >= 60hz and same resolution as the
     * default mode.
     */
    private Mode findLowRefreshRateMode(DisplayInfo displayInfo) {
        Mode mode = displayInfo.getDefaultMode();
        float[] refreshRates = displayInfo.getDefaultRefreshRates();
        float bestRefreshRate = mode.getRefreshRate();
        for (int i = refreshRates.length - 1; i >= 0; i--) {
            if (refreshRates[i] >= 60f && refreshRates[i] < bestRefreshRate) {
                bestRefreshRate = refreshRates[i];
            }
        }
        return displayInfo.findDefaultModeByRefreshRate(bestRefreshRate);
    }

    void addNonHighRefreshRatePackage(String packageName) {
        mNonHighRefreshRatePackages.add(packageName);
        mWmService.requestTraversal();
    }

    void removeNonHighRefreshRatePackage(String packageName) {
        mNonHighRefreshRatePackages.remove(packageName);
        mWmService.requestTraversal();
    }

    int getPreferredModeId(WindowState w) {

        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimating(TRANSITION | PARENTS)) {
            return 0;
        }

        if (w.mAttrs.preferredRefreshRate != 0 || w.mAttrs.preferredDisplayModeId != 0) {
            return w.mAttrs.preferredDisplayModeId;
        }

        return 0;
    }

    /**
     * Calculate the priority based on whether the window is in focus and whether the application
     * voted for a specific refresh rate.
     *
     * TODO(b/144307188): This is a very basic algorithm version. Explore other signals that might
     * be useful in edge cases when we are deciding which layer should get priority when deciding
     * about the refresh rate.
     */
    int calculatePriority(WindowState w) {
        boolean isFocused = w.isFocused();
        int preferredModeId = getPreferredModeId(w);

        if (!isFocused && preferredModeId > 0) {
            return LAYER_PRIORITY_NOT_FOCUSED_WITH_MODE;
        }
        if (isFocused && preferredModeId == 0) {
            return LAYER_PRIORITY_FOCUSED_WITHOUT_MODE;
        }
        if (isFocused && preferredModeId > 0) {
            return LAYER_PRIORITY_FOCUSED_WITH_MODE;
        }
        return LAYER_PRIORITY_UNSET;
    }

    float getPreferredRefreshRate(WindowState w) {
        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimating(TRANSITION | PARENTS)) {
            return 0;
        }

        final String packageName = w.getOwningPackage();
        if (mHighRefreshRateDenylist.isDenylisted(packageName)) {
            return mLowRefreshRateMode.getRefreshRate();
        }

        final int preferredModeId = getPreferredModeId(w);
        if (preferredModeId > 0) {
            DisplayInfo info = w.getDisplayInfo();
            if (info != null) {
                for (Display.Mode mode : info.supportedModes) {
                    if (preferredModeId == mode.getModeId()) {
                        return mode.getRefreshRate();
                    }
                }
            }
        }

        return 0;
    }

    float getPreferredMinRefreshRate(WindowState w) {
        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimating(TRANSITION | PARENTS)) {
            return 0;
        }

        // If app requests a certain refresh rate or mode, don't override it.
        if (w.mAttrs.preferredDisplayModeId != 0) {
            return 0;
        }

        return w.mAttrs.preferredMinDisplayRefreshRate;
    }

    float getPreferredMaxRefreshRate(WindowState w) {
        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimating(TRANSITION | PARENTS)) {
            return 0;
        }

        // If app requests a certain refresh rate or mode, don't override it.
        if (w.mAttrs.preferredDisplayModeId != 0) {
            return 0;
        }

        if (w.mAttrs.preferredMaxDisplayRefreshRate > 0) {
            return w.mAttrs.preferredMaxDisplayRefreshRate;
        }

        final String packageName = w.getOwningPackage();
        // If app is using Camera, force it to default (lower) refresh rate.
        if (mNonHighRefreshRatePackages.contains(packageName)) {
            return mLowRefreshRateMode.getRefreshRate();
        }

        return 0;
    }
}
