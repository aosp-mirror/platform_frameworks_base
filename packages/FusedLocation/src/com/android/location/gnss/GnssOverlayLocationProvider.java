/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.location.gnss;

import static android.location.provider.ProviderProperties.ACCURACY_FINE;
import static android.location.provider.ProviderProperties.POWER_USAGE_HIGH;

import android.annotation.Nullable;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.provider.LocationProviderBase;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.os.Bundle;
import android.util.SparseArray;


import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ConcurrentUtils;

import java.util.List;

/** Basic pass-through GNSS location provider implementation. */
public class GnssOverlayLocationProvider extends LocationProviderBase {

    private static final String TAG = "GnssOverlay";

    private static final ProviderProperties PROPERTIES = new ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(POWER_USAGE_HIGH)
                .setAccuracy(ACCURACY_FINE)
                .build();

    @GuardedBy("mPendingFlushes")
    private final SparseArray<OnFlushCompleteCallback> mPendingFlushes = new SparseArray<>();

    private final LocationManager mLocationManager;

    private final GnssLocationListener mGnssLocationListener = new GnssLocationListener();

    @GuardedBy("mPendingFlushes")
    private int mFlushCode = 0;

    /** Location listener for receiving locations from LocationManager. */
    private class GnssLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            reportLocation(location);
        }

        @Override
        public void onLocationChanged(List<Location> locations) {
            reportLocations(locations);
        }

        @Override
        public void onFlushComplete(int requestCode) {
            OnFlushCompleteCallback flushCompleteCallback;
            synchronized (mPendingFlushes) {
                flushCompleteCallback = mPendingFlushes.get(requestCode);
                mPendingFlushes.remove(requestCode);
            }
            if (flushCompleteCallback != null) {
                flushCompleteCallback.onFlushComplete();
            }
        }
    }

    public GnssOverlayLocationProvider(Context context) {
        super(context, TAG, PROPERTIES);
        mLocationManager = context.getSystemService(LocationManager.class);
    }

    void start() {
    }

    void stop() {
        mLocationManager.removeUpdates(mGnssLocationListener);
    }

    @Override
    public void onSendExtraCommand(String command, @Nullable Bundle extras) {
        mLocationManager.sendExtraCommand(LocationManager.GPS_HARDWARE_PROVIDER, command, extras);
    }

    @Override
    public void onFlush(OnFlushCompleteCallback callback) {
        int flushCodeCopy;
        synchronized (mPendingFlushes) {
            flushCodeCopy = mFlushCode++;
            mPendingFlushes.put(flushCodeCopy, callback);
        }
        mLocationManager.requestFlush(
                LocationManager.GPS_HARDWARE_PROVIDER, mGnssLocationListener, flushCodeCopy);
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        if (request.isActive()) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_HARDWARE_PROVIDER,
                    new LocationRequest.Builder(request.getIntervalMillis())
                            .setMaxUpdateDelayMillis(request.getMaxUpdateDelayMillis())
                            .setLowPower(request.isLowPower())
                            .setLocationSettingsIgnored(request.isLocationSettingsIgnored())
                            .setWorkSource(request.getWorkSource())
                            .setQuality(request.getQuality())
                            .build(),
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    mGnssLocationListener);
        } else {
            mLocationManager.removeUpdates(mGnssLocationListener);
        }
    }
}
