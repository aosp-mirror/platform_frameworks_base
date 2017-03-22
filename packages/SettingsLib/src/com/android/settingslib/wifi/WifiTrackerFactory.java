/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.wifi;

import android.content.Context;
import android.os.Looper;

/**
 * Factory method used to inject WifiTracker instances.
 */
public class WifiTrackerFactory {
    private static boolean sTestingMode = false;

    private static WifiTracker sTestingWifiTracker;

    public static void enableTestingMode() {
        sTestingMode = true;
    }

    public static void disableTestingMode() {
        sTestingMode = false;
    }

    public static void setTestingWifiTracker(WifiTracker tracker) {
        sTestingWifiTracker = tracker;
    }

    public static WifiTracker create(
            Context context, WifiTracker.WifiListener wifiListener, Looper workerLooper,
            boolean includeSaved, boolean includeScans, boolean includePasspoints) {
        if(sTestingMode) {
            return sTestingWifiTracker;
        }
        return new WifiTracker(
                context, wifiListener, workerLooper, includeSaved, includeScans, includePasspoints);
    }
}
