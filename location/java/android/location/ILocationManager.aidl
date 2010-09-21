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
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;
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
    List<String> getAllProviders();
    List<String> getProviders(in Criteria criteria, boolean enabledOnly);
    String getBestProvider(in Criteria criteria, boolean enabledOnly);
    boolean providerMeetsCriteria(String provider, in Criteria criteria);

    void requestLocationUpdates(String provider, in Criteria criteria, long minTime, float minDistance,
        boolean singleShot, in ILocationListener listener);
    void requestLocationUpdatesPI(String provider, in Criteria criteria, long minTime, float minDistance,
        boolean singleShot, in PendingIntent intent);
    void removeUpdates(in ILocationListener listener);
    void removeUpdatesPI(in PendingIntent intent);

    boolean addGpsStatusListener(IGpsStatusListener listener);
    void removeGpsStatusListener(IGpsStatusListener listener);

    // for reporting callback completion
    void locationCallbackFinished(ILocationListener listener);

    boolean sendExtraCommand(String provider, String command, inout Bundle extras);

    void addProximityAlert(double latitude, double longitude, float distance,
        long expiration, in PendingIntent intent);
    void removeProximityAlert(in PendingIntent intent);

    Bundle getProviderInfo(String provider);
    boolean isProviderEnabled(String provider);

    Location getLastKnownLocation(String provider);

    // Used by location providers to tell the location manager when it has a new location.
    // Passive is true if the location is coming from the passive provider, in which case
    // it need not be shared with other providers.
    void reportLocation(in Location location, boolean passive);

    boolean geocoderIsPresent();
    String getFromLocation(double latitude, double longitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);
    String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);

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

    // for NI support
    boolean sendNiResponse(int notifId, int userResponse);
}
