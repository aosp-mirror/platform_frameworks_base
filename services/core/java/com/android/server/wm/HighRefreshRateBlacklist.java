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

import android.annotation.NonNull;
import android.os.SystemProperties;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A Blacklist for packages that should force the display out of high refresh rate.
 */
class HighRefreshRateBlacklist {

    private static final String SYSPROP_KEY = "ro.window_manager.high_refresh_rate_blacklist";
    private static final String SYSPROP_KEY_LENGTH_SUFFIX = "_length";
    private static final String SYSPROP_KEY_ENTRY_SUFFIX = "_entry";
    private static final int MAX_ENTRIES = 50;

    private ArraySet<String> mBlacklistedPackages = new ArraySet<>();

    static HighRefreshRateBlacklist create() {
        return new HighRefreshRateBlacklist(new SystemPropertyGetter() {
            @Override
            public int getInt(String key, int def) {
                return SystemProperties.getInt(key, def);
            }

            @Override
            public String get(String key) {
                return SystemProperties.get(key);
            }
        });
    }

    @VisibleForTesting
    HighRefreshRateBlacklist(SystemPropertyGetter propertyGetter) {

        // Read and populate the blacklist
        final int length = Math.min(
                propertyGetter.getInt(SYSPROP_KEY + SYSPROP_KEY_LENGTH_SUFFIX, 0),
                MAX_ENTRIES);
        for (int i = 1; i <= length; i++) {
            final String packageName = propertyGetter.get(
                    SYSPROP_KEY + SYSPROP_KEY_ENTRY_SUFFIX + i);
            if (!packageName.isEmpty()) {
                mBlacklistedPackages.add(packageName);
            }
        }
    }

    boolean isBlacklisted(String packageName) {
        return mBlacklistedPackages.contains(packageName);
    }

    interface SystemPropertyGetter {
        int getInt(String key, int def);
        @NonNull String get(String key);
    }
}
