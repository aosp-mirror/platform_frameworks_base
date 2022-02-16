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
     * Register a {@link ServiceStateListener} to listen for forced-app-standby changes that should
     * affect services.
     */
    void addServiceStateListener(@NonNull ServiceStateListener listener);

    /**
     * A listener to listen to forced-app-standby changes that should affect services.
     */
    interface ServiceStateListener {
        /**
         * Called when an app goes into forced app standby and its foreground
         * services need to be removed from that state.
         */
        void stopForegroundServicesForUidPackage(int uid, String packageName);
    }
}
