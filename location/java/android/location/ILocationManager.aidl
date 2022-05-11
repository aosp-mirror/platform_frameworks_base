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
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementRequest;
import android.location.IGeocodeListener;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssStatusListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssNmeaListener;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.PackageTagsList;

/**
 * System private API for talking with the location service.
 *
 * @hide
 */
interface ILocationManager
{
    @nullable Location getLastLocation(String provider, in LastLocationRequest request, String packageName, @nullable String attributionTag);
    @nullable ICancellationSignal getCurrentLocation(String provider, in LocationRequest request, in ILocationCallback callback, String packageName, @nullable String attributionTag, String listenerId);

    void registerLocationListener(String provider, in LocationRequest request, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
    void unregisterLocationListener(in ILocationListener listener);

    void registerLocationPendingIntent(String provider, in LocationRequest request, in PendingIntent pendingIntent, String packageName, @nullable String attributionTag);
    void unregisterLocationPendingIntent(in PendingIntent pendingIntent);

    void injectLocation(in Location location);

    void requestListenerFlush(String provider, in ILocationListener listener, int requestCode);
    void requestPendingIntentFlush(String provider, in PendingIntent pendingIntent, int requestCode);

    void requestGeofence(in Geofence geofence, in PendingIntent intent, String packageName, String attributionTag);
    void removeGeofence(in PendingIntent intent);

    boolean geocoderIsPresent();
    void getFromLocation(double latitude, double longitude, int maxResults,
        in GeocoderParams params, in IGeocodeListener listener);
    void getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        in GeocoderParams params, in IGeocodeListener listener);

    GnssCapabilities getGnssCapabilities();
    int getGnssYearOfHardware();
    String getGnssHardwareModelName();

    @nullable List<GnssAntennaInfo> getGnssAntennaInfos();

    void registerGnssStatusCallback(in IGnssStatusListener callback, String packageName, @nullable String attributionTag, String listenerId);
    void unregisterGnssStatusCallback(in IGnssStatusListener callback);

    void registerGnssNmeaCallback(in IGnssNmeaListener callback, String packageName, @nullable String attributionTag, String listenerId);
    void unregisterGnssNmeaCallback(in IGnssNmeaListener callback);

    void addGnssMeasurementsListener(in GnssMeasurementRequest request, in IGnssMeasurementsListener listener, String packageName, @nullable String attributionTag, String listenerId);
    void removeGnssMeasurementsListener(in IGnssMeasurementsListener listener);
    void injectGnssMeasurementCorrections(in GnssMeasurementCorrections corrections);

    void addGnssNavigationMessageListener(in IGnssNavigationMessageListener listener, String packageName, @nullable String attributionTag, String listenerId);
    void removeGnssNavigationMessageListener(in IGnssNavigationMessageListener listener);

    void addGnssAntennaInfoListener(in IGnssAntennaInfoListener listener, String packageName, @nullable String attributionTag, String listenerId);
    void removeGnssAntennaInfoListener(in IGnssAntennaInfoListener listener);

    void addProviderRequestListener(in IProviderRequestListener listener);
    void removeProviderRequestListener(in IProviderRequestListener listener);

    int getGnssBatchSize();
    void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
    void flushGnssBatch();
    void stopGnssBatch();

    boolean hasProvider(String provider);
    List<String> getAllProviders();
    List<String> getProviders(in Criteria criteria, boolean enabledOnly);
    String getBestProvider(in Criteria criteria, boolean enabledOnly);
    ProviderProperties getProviderProperties(String provider);
    boolean isProviderPackage(@nullable String provider, String packageName, @nullable String attributionTag);
    List<String> getProviderPackages(String provider);

    void setExtraLocationControllerPackage(String packageName);
    String getExtraLocationControllerPackage();
    void setExtraLocationControllerPackageEnabled(boolean enabled);
    boolean isExtraLocationControllerPackageEnabled();

    boolean isProviderEnabledForUser(String provider, int userId);
    boolean isLocationEnabledForUser(int userId);
    void setLocationEnabledForUser(boolean enabled, int userId);

    boolean isAdasGnssLocationEnabledForUser(int userId);
    void setAdasGnssLocationEnabledForUser(boolean enabled, int userId);

    boolean isAutomotiveGnssSuspended();
    void setAutomotiveGnssSuspended(boolean suspended);

    void addTestProvider(String name, in ProviderProperties properties,
        in List<String> locationTags, String packageName, @nullable String attributionTag);
    void removeTestProvider(String provider, String packageName, @nullable String attributionTag);
    void setTestProviderLocation(String provider, in Location location, String packageName, @nullable String attributionTag);
    void setTestProviderEnabled(String provider, boolean enabled, String packageName, @nullable String attributionTag);

    LocationTime getGnssTimeMillis();

    void sendExtraCommand(String provider, String command, inout Bundle extras);

    // used by gts tests to verify whitelists
    String[] getBackgroundThrottlingWhitelist();
    PackageTagsList getIgnoreSettingsAllowlist();
}
