package com.android.settingslib.wifi;

import android.content.Context;
import android.os.Looper;

/**
 * Factory method used to inject WifiTracker instances.
 */
public class WifiTrackerFactory {
    private static boolean sTestingMode = false;

    private static WifiTracker sTestingWifiTracker;

    public void enableTestingMode() {
        sTestingMode = true;
    }

    public void disableTestingMode() {
        sTestingMode = false;
    }

    public void setTestingWifiTracker(WifiTracker tracker) {
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
