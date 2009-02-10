/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.location;

import android.location.Location;
import android.net.wifi.ScanResult;

import com.android.internal.location.CellState;

import java.util.List;

/**
 * Listens for GPS and cell/wifi changes and anonymously uploads to server for
 * improving quality of service of NetworkLocationProvider. This service is only enabled when
 * the user has enabled the network location provider.
 *
 * {@hide}
 */
public interface ILocationCollector {
    /**
     * Updates GPS location if collection is enabled
     *
     * @param location location object
     */
    abstract public void updateLocation(Location location);

    /**
     * Updates wifi scan results if collection is enabled
     *
     * @param currentScanResults scan results
     */
    abstract public void updateWifiScanResults(List<ScanResult> currentScanResults);

    /**
     * Updates the status of the network location provider.
     *
     * @param enabled true if user has enabled network location based on Google's database
     * of wifi points and cell towers.
     */
    abstract public void updateNetworkProviderStatus(boolean enabled);

    /**
     * Updates cell tower state. This is usually always up to date so should be uploaded
     * each time a new location is available.
     *
     * @param newState cell state
     */
    abstract public void updateCellState(CellState newState);

    /**
     * Updates the battery health. Battery level is healthy if there is greater than
     * {@link #MIN_BATTERY_LEVEL} percentage left or if the device is plugged in
     *
     * @param scale maximum scale for battery
     * @param level current level
     * @param plugged true if device is plugged in
     */
    abstract public void updateBatteryState(int scale, int level, boolean plugged);
}
