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
import android.location.GnssMeasurementCorrections;
import android.location.GnssRequest;
import android.location.IBatchedLocationCallback;
import android.location.IGeocodeListener;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssStatusListener;
import android.location.IGnssNavigationMessageListener;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Bundle;
import android.os.ICancellationSignal;

import com.android.internal.location.ProviderProperties;

/**
 * System private API for talking with the location service.
 *
 * @hide
 */
interface ILocationManager
{
    Location getLastLocation(in LocationRequest request, String packageName, String attributionTag);
    boolean getCurrentLocation(in LocationRequest request,
            in ICancellationSignal cancellationSignal, in ILocationListener listener,
            String packageName, String attributionTag, String listenerId);

    void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            in PendingIntent intent, String packageName, String attributionTag, String listenerId);
    void removeUpdates(in ILocationListener listener, in PendingIntent intent);

    void requestGeofence(in Geofence geofence, in PendingIntent intent, String packageName, String attributionTag);
    void removeGeofence(in PendingIntent intent);

    boolean geocoderIsPresent();
    void getFromLocation(double latitude, double longitude, int maxResults,
        in GeocoderParams params, in IGeocodeListener listener);
    void getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        in GeocoderParams params, in IGeocodeListener listener);

    long getGnssCapabilities();
    int getGnssYearOfHardware();
    String getGnssHardwareModelName();

    void registerGnssStatusCallback(in IGnssStatusListener callback, String packageName, String attributionTag);
    void unregisterGnssStatusCallback(in IGnssStatusListener callback);

    void addGnssMeasurementsListener(in GnssRequest request, in IGnssMeasurementsListener listener, String packageName, String attributionTag);
    void removeGnssMeasurementsListener(in IGnssMeasurementsListener listener);

    void addGnssAntennaInfoListener(in IGnssAntennaInfoListener listener, String packageName, String attributionTag);
    void removeGnssAntennaInfoListener(in IGnssAntennaInfoListener listener);

    void addGnssNavigationMessageListener(in IGnssNavigationMessageListener listener, String packageName, String attributionTag);
    void removeGnssNavigationMessageListener(in IGnssNavigationMessageListener listener);

    void injectGnssMeasurementCorrections(in GnssMeasurementCorrections corrections, String packageName);

    int getGnssBatchSize(String packageName);
    void setGnssBatchingCallback(in IBatchedLocationCallback callback, String packageName, String attributionTag);
    void removeGnssBatchingCallback();
    void startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName, String attributionTag);
    void flushGnssBatch(String packageName);
    void stopGnssBatch();
    void injectLocation(in Location location);

    List<String> getAllProviders();
    List<String> getProviders(in Criteria criteria, boolean enabledOnly);
    String getBestProvider(in Criteria criteria, boolean enabledOnly);
    ProviderProperties getProviderProperties(String provider);
    boolean isProviderPackage(String provider, String packageName);
    List<String> getProviderPackages(String provider);

    void setExtraLocationControllerPackage(String packageName);
    String getExtraLocationControllerPackage();
    void setExtraLocationControllerPackageEnabled(boolean enabled);
    boolean isExtraLocationControllerPackageEnabled();

    boolean isProviderEnabledForUser(String provider, int userId);
    boolean isLocationEnabledForUser(int userId);
    void setLocationEnabledForUser(boolean enabled, int userId);
    void addTestProvider(String name, in ProviderProperties properties, String packageName, String attributionTag);
    void removeTestProvider(String provider, String packageName, String attributionTag);
    void setTestProviderLocation(String provider, in Location location, String packageName, String attributionTag);
    void setTestProviderEnabled(String provider, boolean enabled, String packageName, String attributionTag);
    List<LocationRequest> getTestProviderCurrentRequests(String provider);
    LocationTime getGnssTimeMillis();

    boolean sendExtraCommand(String provider, String command, inout Bundle extras);

    // --- internal ---

    // for reporting callback completion
    void locationCallbackFinished(ILocationListener listener);

    // used by gts tests to verify whitelists
    String[] getBackgroundThrottlingWhitelist();
    String[] getIgnoreSettingsWhitelist();
}
