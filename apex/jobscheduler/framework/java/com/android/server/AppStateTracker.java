/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;

/**
 * Tracks the forced-app-standby state for apps.
 */
public interface AppStateTracker {
    String TAG = "AppStateTracker";

    /**
     * Register a {@link ForcedAppStandbyListener} to listen for forced-app-standby changes that
     * should affect services etc.
     */
    void addForcedAppStandbyListener(@NonNull ForcedAppStandbyListener listener);

    /**
     * @return {code true} if the given UID/package has been in forced app standby mode.
     */
    boolean isAppInForcedAppStandby(int uid, @NonNull String packageName);

    /**
     * A listener to listen to forced-app-standby changes that should affect services etc.
     */
    interface ForcedAppStandbyListener {
        /**
         * Called when an app goes in/out of forced app standby.
         */
        void updateForceAppStandbyForUidPackage(int uid, String packageName, boolean standby);

        /**
         * Called when all apps' forced-app-standby states need to be re-evaluated, due to
         * enable/disable certain feature flags.
         */
        void updateForcedAppStandbyForAllApps();
    }
}
