/*
 * Copyright (C) 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.app.PendingIntent;
import android.location.Address;
import android.location.IGpsStatusListener;
import android.location.ILocationListener;
import android.location.Location;
import android.os.Bundle;

/**
 * System private API for talking with the location service.
 *
 * {@hide}
 */
interface ILocationManager
{
    List getAllProviders();
    List getProviders(boolean enabledOnly);

    void updateProviders();

    void requestLocationUpdates(String provider, long minTime, float minDistance,
        in ILocationListener listener);
    void requestLocationUpdatesPI(String provider, long minTime, float minDistance,
        in PendingIntent intent);
    void removeUpdates(in ILocationListener listener);
    void removeUpdatesPI(in PendingIntent intent);

    boolean addGpsStatusListener(IGpsStatusListener listener);
    void removeGpsStatusListener(IGpsStatusListener listener);
    
    boolean sendExtraCommand(String provider, String command, inout Bundle extras);

    void addProximityAlert(double latitude, double longitude, float distance,
        long expiration, in PendingIntent intent);
    void removeProximityAlert(in PendingIntent intent);

    Bundle getProviderInfo(String provider);
    boolean isProviderEnabled(String provider);

    Location getLastKnownLocation(String provider);

    String getFromLocation(double latitude, double longitude, int maxResults,
        String language, String country, String variant, String appName, out List<Address> addrs);
    String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        String language, String country, String variant, String appName, out List<Address> addrs);

    void addTestProvider(String name, boolean requiresNetwork, boolean requiresSatellite,
        boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
        boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy);
    void removeTestProvider(String provider);
    void setTestProviderLocation(String provider, in Location loc);
    void clearTestProviderLocation(String provider);
    void setTestProviderEnabled(String provider, boolean enabled);
    void clearTestProviderEnabled(String provider);
    void setTestProviderStatus(String provider, int status, in Bundle extras, long updateTime);
    void clearTestProviderStatus(String provider);
}
