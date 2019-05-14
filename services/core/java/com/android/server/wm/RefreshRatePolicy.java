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

import android.util.ArraySet;
import android.view.Display.Mode;
import android.view.DisplayInfo;

/**
 * Policy to select a lower refresh rate for the display if applicable.
 */
class RefreshRatePolicy {

    private final int mLowRefreshRateId;
    private final ArraySet<String> mNonHighRefreshRatePackages = new ArraySet<>();
    private final HighRefreshRateBlacklist mHighRefreshRateBlacklist;
    private final WindowManagerService mWmService;

    RefreshRatePolicy(WindowManagerService wmService, DisplayInfo displayInfo,
            HighRefreshRateBlacklist blacklist) {
        mLowRefreshRateId = findLowRefreshRateModeId(displayInfo);
        mHighRefreshRateBlacklist = blacklist;
        mWmService = wmService;
    }

    /**
     * Finds the mode id with the lowest refresh rate which is >= 60hz and same resolution as the
     * default mode.
     */
    private int findLowRefreshRateModeId(DisplayInfo displayInfo) {
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
        if (w.isAnimating()) {
            return 0;
        }

        // If app requests a certain refresh rate or mode, don't override it.
        if (w.mAttrs.preferredRefreshRate != 0 || w.mAttrs.preferredDisplayModeId != 0) {
            return w.mAttrs.preferredDisplayModeId;
        }

        final String packageName = w.getOwningPackage();

        // If app is using Camera, force it to default (lower) refresh rate.
        if (mNonHighRefreshRatePackages.contains(packageName)) {
            return mLowRefreshRateId;
        }

        // If app is blacklisted using higher refresh rate, return default (lower) refresh rate
        if (mHighRefreshRateBlacklist.isBlacklisted(packageName)) {
            return mLowRefreshRateId;
        }
        return 0;
    }
}
