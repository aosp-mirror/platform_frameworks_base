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
     * Register a {@link BackgroundRestrictedAppListener} to listen for background restricted mode
     * changes that should affect services etc.
     */
    void addBackgroundRestrictedAppListener(@NonNull BackgroundRestrictedAppListener listener);

    /**
     * @return {code true} if the given UID/package has been in background restricted mode,
     * it does NOT include the case where the "force app background restricted" is enabled.
     */
    boolean isAppBackgroundRestricted(int uid, @NonNull String packageName);

    /**
     * A listener to listen to background restricted mode changes that should affect services etc.
     */
    interface BackgroundRestrictedAppListener {
        /**
         * Called when an app goes in/out of background restricted mode.
         */
        void updateBackgroundRestrictedForUidPackage(int uid, String packageName,
                boolean restricted);
    }
}
