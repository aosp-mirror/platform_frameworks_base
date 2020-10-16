/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.fused;

import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static android.location.LocationRequest.QUALITY_LOW_POWER;

import static com.android.location.provider.ProviderRequestUnbundled.INTERVAL_DISABLED;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.WorkSource;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ProviderRequest;
import com.android.location.provider.LocationProviderBase;
import com.android.location.provider.ProviderPropertiesUnbundled;
import com.android.location.provider.ProviderRequestUnbundled;

import java.io.PrintWriter;

/** Basic fused location provider implementation. */
public class FusedLocationProvider extends LocationProviderBase {

    private static final String TAG = "FusedLocationProvider";

    private static final ProviderPropertiesUnbundled PROPERTIES =
            ProviderPropertiesUnbundled.create(
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
            );

    private static final long MAX_LOCATION_COMPARISON_NS = 11 * 1000000000L; // 11 seconds

    final Object mLock = new Object();

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final LocationListener mGpsListener;
    private final LocationListener mNetworkListener;
    private final BroadcastReceiver mUserChangeReceiver;

    @GuardedBy("mLock")
    ProviderRequestUnbundled mRequest;
    @GuardedBy("mLock")
    private WorkSource mWorkSource;
    @GuardedBy("mLock")
    private long mGpsInterval;
    @GuardedBy("mLock")
    private long mNetworkInterval;

    @GuardedBy("mLock")
    @Nullable private Location mFusedLocation;
    @GuardedBy("mLock")
    @Nullable Location mGpsLocation;
    @GuardedBy("mLock")
    @Nullable Location mNetworkLocation;

    public FusedLocationProvider(Context context) {
        super(context, TAG, PROPERTIES);
        mContext = context;
        mLocationManager = context.getSystemService(LocationManager.class);

        mGpsListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                synchronized (mLock) {
                    mGpsLocation = location;
                    reportBestLocationLocked();
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                synchronized (mLock) {
                    // if satisfying a bypass request, don't clear anything
                    if (mRequest.getReportLocation() && mRequest.isLocationSettingsIgnored()) {
                        return;
                    }

                    mGpsLocation = null;
                }
            }
        };

        mNetworkListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                synchronized (mLock) {
                    mNetworkLocation = location;
                    reportBestLocationLocked();
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                synchronized (mLock) {
                    // if satisfying a bypass request, don't clear anything
                    if (mRequest.getReportLocation() && mRequest.isLocationSettingsIgnored()) {
                        return;
                    }

                    mNetworkLocation = null;
                }
            }
        };

        mUserChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    return;
                }

                onUserChanged();
            }
        };

        mRequest = new ProviderRequestUnbundled(ProviderRequest.EMPTY_REQUEST);
        mWorkSource = new WorkSource();
        mGpsInterval = INTERVAL_DISABLED;
        mNetworkInterval = INTERVAL_DISABLED;
    }

    void start() {
        mContext.registerReceiver(mUserChangeReceiver, new IntentFilter(ACTION_USER_SWITCHED));
    }

    void stop() {
        mContext.unregisterReceiver(mUserChangeReceiver);

        synchronized (mLock) {
            mRequest = new ProviderRequestUnbundled(ProviderRequest.EMPTY_REQUEST);
            updateRequirementsLocked();
        }
    }

    @Override
    public void onSetRequest(ProviderRequestUnbundled request, WorkSource workSource) {
        synchronized (mLock) {
            mRequest = request;
            mWorkSource = workSource;
            updateRequirementsLocked();
        }
    }

    @GuardedBy("mLock")
    private void updateRequirementsLocked() {
        long gpsInterval = mRequest.getQuality() < QUALITY_LOW_POWER ? mRequest.getInterval()
                : INTERVAL_DISABLED;
        long networkInterval = mRequest.getInterval();

        if (gpsInterval != mGpsInterval) {
            resetProviderRequestLocked(GPS_PROVIDER, mGpsInterval, gpsInterval, mGpsListener);
            mGpsInterval = gpsInterval;
        }
        if (networkInterval != mNetworkInterval) {
            resetProviderRequestLocked(NETWORK_PROVIDER, mNetworkInterval, networkInterval,
                    mNetworkListener);
            mNetworkInterval = networkInterval;
        }
    }

    @GuardedBy("mLock")
    private void resetProviderRequestLocked(String provider, long oldInterval, long newInterval,
            LocationListener listener) {
        if (oldInterval != INTERVAL_DISABLED && newInterval == INTERVAL_DISABLED) {
            mLocationManager.removeUpdates(listener);
        }
        if (newInterval != INTERVAL_DISABLED) {
            LocationRequest request = new LocationRequest.Builder(newInterval)
                    .setQuality(mRequest.getQuality())
                    .setLocationSettingsIgnored(mRequest.isLocationSettingsIgnored())
                    .setWorkSource(mWorkSource)
                    .build();
            mLocationManager.requestLocationUpdates(provider, request, mContext.getMainExecutor(),
                    listener);
        }
    }

    @GuardedBy("mLock")
    void reportBestLocationLocked() {
        Location bestLocation = chooseBestLocation(mGpsLocation, mNetworkLocation);
        if (bestLocation == mFusedLocation) {
            return;
        }

        mFusedLocation = bestLocation;
        if (mFusedLocation == null) {
            return;
        }

        reportLocation(mFusedLocation);
    }

    void onUserChanged() {
        // clear cached locations when the user changes to prevent leaking user information
        synchronized (mLock) {
            mFusedLocation = null;
            mGpsLocation = null;
            mNetworkLocation = null;
        }
    }

    void dump(PrintWriter writer) {
        synchronized (mLock) {
            writer.println("request: " + mRequest);
            if (mGpsInterval != INTERVAL_DISABLED) {
                writer.println("  gps interval: " + mGpsInterval);
            }
            if (mNetworkInterval != INTERVAL_DISABLED) {
                writer.println("  network interval: " + mNetworkInterval);
            }
            if (mGpsLocation != null) {
                writer.println("  last gps location: " + mGpsLocation);
            }
            if (mNetworkLocation != null) {
                writer.println("  last network location: " + mNetworkLocation);
            }
        }
    }

    @Nullable
    private static Location chooseBestLocation(
            @Nullable Location locationA,
            @Nullable Location locationB) {
        if (locationA == null) {
            return locationB;
        }
        if (locationB == null) {
            return locationA;
        }

        if (locationA.getElapsedRealtimeNanos()
                > locationB.getElapsedRealtimeNanos() + MAX_LOCATION_COMPARISON_NS) {
            return locationA;
        }
        if (locationB.getElapsedRealtimeNanos()
                > locationA.getElapsedRealtimeNanos() + MAX_LOCATION_COMPARISON_NS) {
            return locationB;
        }

        if (!locationA.hasAccuracy()) {
            return locationB;
        }
        if (!locationB.hasAccuracy()) {
            return locationA;
        }
        return locationA.getAccuracy() < locationB.getAccuracy() ? locationA : locationB;
    }
}
