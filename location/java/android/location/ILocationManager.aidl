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
import android.location.Geofence;
import android.location.IGpsMeasurementsListener;
import android.location.IGpsNavigationMessageListener;
import android.location.IGpsStatusListener;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationRequest;
import android.os.Bundle;

import com.android.internal.location.ProviderProperties;

/**
 * System private API for talking with the location service.
 *
 * @hide
 */
interface ILocationManager
{
    void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            in PendingIntent intent, String packageName);
    void removeUpdates(in ILocationListener listener, in PendingIntent intent, String packageName);

    void requestGeofence(in LocationRequest request, in Geofence geofence,
            in PendingIntent intent, String packageName);
    void removeGeofence(in Geofence fence, in PendingIntent intent, String packageName);

    Location getLastLocation(in LocationRequest request, String packageName);

    boolean addGpsStatusListener(IGpsStatusListener listener, String packageName);
    void removeGpsStatusListener(IGpsStatusListener listener);

    boolean geocoderIsPresent();
    String getFromLocation(double latitude, double longitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);
    String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        in GeocoderParams params, out List<Address> addrs);

    boolean sendNiResponse(int notifId, int userResponse);

    boolean addGpsMeasurementsListener(in IGpsMeasurementsListener listener, in String packageName);
    void removeGpsMeasurementsListener(in IGpsMeasurementsListener listener);

    boolean addGpsNavigationMessageListener(
            in IGpsNavigationMessageListener listener,
            in String packageName);
    void removeGpsNavigationMessageListener(in IGpsNavigationMessageListener listener);

    // --- deprecated ---
    List<String> getAllProviders();
    List<String> getProviders(in Criteria criteria, boolean enabledOnly);
    String getBestProvider(in Criteria criteria, boolean enabledOnly);
    boolean providerMeetsCriteria(String provider, in Criteria criteria);
    ProviderProperties getProviderProperties(String provider);
    boolean isProviderEnabled(String provider);

    void addTestProvider(String name, in ProviderProperties properties);
    void removeTestProvider(String provider);
    void setTestProviderLocation(String provider, in Location loc);
    void clearTestProviderLocation(String provider);
    void setTestProviderEnabled(String provider, boolean enabled);
    void clearTestProviderEnabled(String provider);
    void setTestProviderStatus(String provider, int status, in Bundle extras, long updateTime);
    void clearTestProviderStatus(String provider);

    boolean sendExtraCommand(String provider, String command, inout Bundle extras);

    // --- internal ---

    // Used by location providers to tell the location manager when it has a new location.
    // Passive is true if the location is coming from the passive provider, in which case
    // it need not be shared with other providers.
    void reportLocation(in Location location, boolean passive);

    // for reporting callback completion
    void locationCallbackFinished(ILocationListener listener);


}
