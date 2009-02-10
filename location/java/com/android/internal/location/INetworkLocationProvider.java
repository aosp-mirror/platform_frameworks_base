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

import android.location.Address;
import android.location.Location;
import android.net.wifi.ScanResult;

import com.google.common.io.protocol.ProtoBuf;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Interface for network location provider
 *
 * {@hide}
 */
public interface INetworkLocationProvider {

    public interface Callback {

        /**
         * Callback function to notify of a received network location
         *
         * @param location location object that is received. may be null if not a valid location
         * @param successful true if network query was successful, even if no location was found
         */
        void locationReceived(Location location, boolean successful);
    }

    /**
     * Updates the current cell lock status.
     *
     * @param acquired true if a cell lock has been acquired
     */
    abstract public void updateCellLockStatus(boolean acquired);

    /**
     * Notifies the provider if Wifi has been enabled or disabled
     * by the user
     *
     * @param enabled true if wifi is enabled; false otherwise
     */
    abstract public void updateWifiEnabledState(boolean enabled);

    /**
     * Notifies the provider that there are scan results available.
     *
     * @param scanResults list of wifi scan results
     */
    abstract public void updateWifiScanResults(List<ScanResult> scanResults);

    /**
     * Adds a list of application clients
     * Only used by the NetworkLocationProvider
     *
     * @param applications list of package names
     */
    abstract public void addListener(String[] applications);

    /**
     * Removes a list of application clients
     * Only used by the NetworkLocationProvider
     *
     * @param applications list of package names
     */
    abstract public void removeListener(String[] applications);


    abstract public String getFromLocation(double latitude, double longitude, int maxResults,
        String language, String country, String variant, String appName, List<Address> addrs);

    abstract public String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        String language, String country, String variant, String appName, List<Address> addrs);

}
