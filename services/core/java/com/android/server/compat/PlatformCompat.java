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

package com.android.server.compat;

import android.content.pm.ApplicationInfo;
import android.util.Slog;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat {

    private static final String TAG = "Compatibility";

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, ApplicationInfo)},
     * you do not need to call this API directly. The change will be reported for you in the case
     * that {@link #isChangeEnabled(long, ApplicationInfo)} returns {@code true}.
     *
     * @param changeId The ID of the compatibility change taking effect.
     * @param appInfo Representing the affected app.
     */
    public static void reportChange(long changeId, ApplicationInfo appInfo) {
        Slog.d(TAG, "Compat change reported: " + changeId + "; UID " + appInfo.uid);
        // TODO log via StatsLog
    }

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>When this method returns {@code true}, it will also report the change as
     * {@link #reportChange(long, ApplicationInfo)} would, so there is no need to call that method
     * directly.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param appInfo Representing the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    public static boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        if (CompatConfig.get().isChangeEnabled(changeId, appInfo)) {
            reportChange(changeId, appInfo);
            return true;
        }
        return false;
    }
}
