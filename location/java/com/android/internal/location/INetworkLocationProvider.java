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

import java.util.List;

/**
 * Interface for network location provider
 *
 * {@hide}
 */
public interface INetworkLocationProvider {

    /**
     * Updates the current cell lock status.
     *
     * @param acquired true if a cell lock has been acquired
     */
    abstract public void updateCellLockStatus(boolean acquired);

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
