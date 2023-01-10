/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.HashMap;

/** Utils class to report hearing aid metrics to statsd */
public final class HearingAidStatsLogUtils {

    private static final String TAG = "HearingAidStatsLogUtils";
    private static final HashMap<String, Integer> sDeviceAddressToBondEntryMap = new HashMap<>();

    /**
     * Sets the mapping from hearing aid device to the bond entry where this device starts it's
     * bonding(connecting) process.
     *
     * @param bondEntry The entry page id where the bonding process starts
     * @param device The bonding(connecting) hearing aid device
     */
    public static void setBondEntryForDevice(int bondEntry, CachedBluetoothDevice device) {
        sDeviceAddressToBondEntryMap.put(device.getAddress(), bondEntry);
    }

    /**
     * Logs hearing aid device information to westworld, including device mode, device side, and
     * entry page id where the binding(connecting) process starts.
     *
     * Only logs the info once after hearing aid is bonded(connected). Clears the map entry of this
     * device when logging is completed.
     *
     * @param device The bonded(connected) hearing aid device
     */
    public static void logHearingAidInfo(CachedBluetoothDevice device) {
        final String deviceAddress = device.getAddress();
        if (sDeviceAddressToBondEntryMap.containsKey(deviceAddress)) {
            final int bondEntry = sDeviceAddressToBondEntryMap.getOrDefault(deviceAddress, -1);
            final int deviceMode = device.getDeviceMode();
            final int deviceSide = device.getDeviceSide();
            FrameworkStatsLog.write(FrameworkStatsLog.HEARING_AID_INFO_REPORTED, deviceMode,
                    deviceSide, bondEntry);

            sDeviceAddressToBondEntryMap.remove(deviceAddress);
        } else {
            Log.w(TAG, "The device address was not found. Hearing aid device info is not logged.");
        }
    }

    @VisibleForTesting
    static HashMap<String, Integer> getDeviceAddressToBondEntryMap() {
        return sDeviceAddressToBondEntryMap;
    }

    private HearingAidStatsLogUtils() {}
}
