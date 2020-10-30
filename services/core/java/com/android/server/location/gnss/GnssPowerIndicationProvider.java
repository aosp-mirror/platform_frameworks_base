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

package com.android.server.location.gnss;

import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_MULTIBAND_ACQUISITION;
import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_MULTIBAND_TRACKING;
import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_OTHER_MODES;
import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_SINGLEBAND_ACQUISITION;
import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_SINGLEBAND_TRACKING;
import static android.hardware.gnss.IGnssPowerIndicationCallback.CAPABILITY_TOTAL;

import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manages GNSS Power Indication operations.
 */
class GnssPowerIndicationProvider {
    private static final String TAG = "GnssPowerIndPdr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private volatile int mCapabilities;
    private GnssPowerStats mGnssPowerStats;

    /**
     * Handles GNSS Power Indication capabilities update from the GNSS HAL callback.
     */
    public void onCapabilitiesUpdated(int capabilities) {
        mCapabilities = capabilities;
    }

    public void onGnssPowerStatsAvailable(GnssPowerStats powerStats) {
        if (DEBUG) {
            Log.d(TAG, "onGnssPowerStatsAvailable: " + powerStats.toString());
        }
        powerStats.validate();
        mGnssPowerStats = powerStats;
    }

    /**
     * Returns the GNSS Power Indication specific capabilities.
     */
    public int getCapabilities() {
        return mCapabilities;
    }

    /**
     * Requests the GNSS HAL to report {@link GnssPowerStats}.
     */
    public static void requestPowerStats() {
        native_request_power_stats();
    }

    private boolean hasCapability(int capability) {
        return (mCapabilities & capability) != 0;
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mGnssPowerStats == null) {
            return;
        }
        pw.print("GnssPowerStats[");
        if (mGnssPowerStats.hasElapsedRealtimeNanos()) {
            pw.print("ElapsedRealtime=" + mGnssPowerStats.getElapsedRealtimeNanos());
        }
        if (mGnssPowerStats.hasElapsedRealtimeUncertaintyNanos()) {
            pw.print(", ElapsedRealtimeUncertaintyNanos="
                    + mGnssPowerStats.getElapsedRealtimeUncertaintyNanos());
        }
        if (hasCapability(CAPABILITY_TOTAL)) {
            pw.print(", TotalEnergyMilliJoule=" + mGnssPowerStats.getTotalEnergyMilliJoule());
        }
        if (hasCapability(CAPABILITY_SINGLEBAND_TRACKING)) {
            pw.print(", SinglebandTrackingModeEnergyMilliJoule="
                    + mGnssPowerStats.getSinglebandTrackingModeEnergyMilliJoule());
        }
        if (hasCapability(CAPABILITY_MULTIBAND_TRACKING)) {
            pw.print(", MultibandTrackingModeEnergyMilliJoule="
                    + mGnssPowerStats.getMultibandTrackingModeEnergyMilliJoule());
        }
        if (hasCapability(CAPABILITY_SINGLEBAND_ACQUISITION)) {
            pw.print(", SinglebandAcquisitionModeEnergyMilliJoule="
                    + mGnssPowerStats.getSinglebandAcquisitionModeEnergyMilliJoule());
        }
        if (hasCapability(CAPABILITY_MULTIBAND_ACQUISITION)) {
            pw.print(", MultibandAcquisitionModeEnergyMilliJoule="
                    + mGnssPowerStats.getMultibandAcquisitionModeEnergyMilliJoule());
        }
        if (hasCapability(CAPABILITY_OTHER_MODES)) {
            pw.print(", OtherModesEnergyMilliJoule=[");
            double[] otherModes = mGnssPowerStats.getOtherModesEnergyMilliJoule();
            for (int i = 0; i < otherModes.length; i++) {
                pw.print(otherModes[i]);
                if (i < otherModes.length - 1) {
                    pw.print(", ");
                }
            }
            pw.print("] ");
        }
        pw.println(']');
    }

    private static native void native_request_power_stats();
}
