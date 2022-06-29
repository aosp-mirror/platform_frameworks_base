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
import static android.location.provider.ProviderProperties.ACCURACY_FINE;
import static android.location.provider.ProviderProperties.POWER_USAGE_LOW;

import static com.android.location.provider.ProviderRequestUnbundled.INTERVAL_DISABLED;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Basic fused location provider implementation. */
public class FusedLocationProvider extends LocationProviderBase {

    private static final String TAG = "FusedLocationProvider";

    private static final ProviderProperties PROPERTIES = new ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(POWER_USAGE_LOW)
                .setAccuracy(ACCURACY_FINE)
                .build();

    private static final long MAX_LOCATION_COMPARISON_NS = 11 * 1000000000L; // 11 seconds

    private final Object mLock = new Object();

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final ChildLocationListener mGpsListener;
    private final ChildLocationListener mNetworkListener;
    private final BroadcastReceiver mUserChangeReceiver;

    @GuardedBy("mLock")
    boolean mGpsPresent;

    @GuardedBy("mLock")
    boolean mNlpPresent;

    @GuardedBy("mLock")
    private ProviderRequest mRequest;

    @GuardedBy("mLock")
    private @Nullable Location mFusedLocation;

    public FusedLocationProvider(Context context) {
        super(context, TAG, PROPERTIES);
        mContext = context;
        mLocationManager = Objects.requireNonNull(context.getSystemService(LocationManager.class));

        mGpsListener = new ChildLocationListener(GPS_PROVIDER);
        mNetworkListener = new ChildLocationListener(NETWORK_PROVIDER);

        mUserChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    return;
                }

                onUserChanged();
            }
        };

        mRequest = ProviderRequest.EMPTY_REQUEST;
    }

    void start() {
        mContext.registerReceiver(mUserChangeReceiver, new IntentFilter(ACTION_USER_SWITCHED));
    }

    void stop() {
        mContext.unregisterReceiver(mUserChangeReceiver);

        synchronized (mLock) {
            mRequest = ProviderRequest.EMPTY_REQUEST;
            updateRequirementsLocked();
        }
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        synchronized (mLock) {
            mRequest = request;
            updateRequirementsLocked();
        }
    }

    @Override
    public void onFlush(OnFlushCompleteCallback callback) {
        synchronized (mLock) {
            AtomicInteger flushCount = new AtomicInteger(0);
            if (mGpsPresent) {
                flushCount.incrementAndGet();
            }
            if (mNlpPresent) {
                flushCount.incrementAndGet();
            }

            OnFlushCompleteCallback wrapper = () -> {
                if (flushCount.decrementAndGet() == 0) {
                    callback.onFlushComplete();
                }
            };

            if (mGpsPresent) {
                mGpsListener.flush(wrapper);
            }
            if (mNlpPresent) {
                mNetworkListener.flush(wrapper);
            }
        }
    }

    @Override
    public void onSendExtraCommand(String command, @Nullable Bundle extras) {}

    @GuardedBy("mLock")
    private void updateRequirementsLocked() {
        // it's possible there might be race conditions on device start where a provider doesn't
        // appear to be present yet, but once a provider is present it shouldn't go away.
        if (!mGpsPresent) {
            mGpsPresent = mLocationManager.hasProvider(GPS_PROVIDER);
        }
        if (!mNlpPresent) {
            mNlpPresent = mLocationManager.hasProvider(NETWORK_PROVIDER);
        }

        long gpsInterval =
                mGpsPresent && (!mNlpPresent || mRequest.getQuality() < QUALITY_LOW_POWER)
                        ? mRequest.getIntervalMillis() : INTERVAL_DISABLED;
        long networkInterval = mNlpPresent ? mRequest.getIntervalMillis() : INTERVAL_DISABLED;

        mGpsListener.resetProviderRequest(gpsInterval);
        mNetworkListener.resetProviderRequest(networkInterval);
    }

    @GuardedBy("mLock")
    void reportBestLocationLocked() {
        Location bestLocation = chooseBestLocation(mGpsListener.getLocation(),
                mNetworkListener.getLocation());
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
            mGpsListener.clearLocation();
            mNetworkListener.clearLocation();
        }
    }

    void dump(PrintWriter writer) {
        synchronized (mLock) {
            writer.println("request: " + mRequest);
            if (mGpsListener.getInterval() != INTERVAL_DISABLED) {
                writer.println("  gps interval: " + mGpsListener.getInterval());
            }
            if (mNetworkListener.getInterval() != INTERVAL_DISABLED) {
                writer.println("  network interval: " + mNetworkListener.getInterval());
            }
            if (mGpsListener.getLocation() != null) {
                writer.println("  last gps location: " + mGpsListener.getLocation());
            }
            if (mNetworkListener.getLocation() != null) {
                writer.println("  last network location: " + mNetworkListener.getLocation());
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

    private class ChildLocationListener implements LocationListener {

        private final String mProvider;
        private final SparseArray<OnFlushCompleteCallback> mPendingFlushes;

        @GuardedBy("mLock")
        private int mNextFlushCode = 0;
        @GuardedBy("mLock")
        private @Nullable Location mLocation = null;
        @GuardedBy("mLock")
        private long mInterval = INTERVAL_DISABLED;

        ChildLocationListener(String provider) {
            mProvider = provider;
            mPendingFlushes = new SparseArray<>();
        }

        @Nullable Location getLocation() {
            synchronized (mLock) {
                return mLocation;
            }
        }

        long getInterval() {
            synchronized (mLock) {
                return mInterval;
            }
        }

        void clearLocation() {
            synchronized (mLock) {
                mLocation = null;
            }
        }

        private void resetProviderRequest(long newInterval) {
            synchronized (mLock) {
                if (newInterval == mInterval) {
                    return;
                }

                if (mInterval != INTERVAL_DISABLED && newInterval == INTERVAL_DISABLED) {
                    mLocationManager.removeUpdates(this);
                }

                mInterval = newInterval;

                if (mInterval != INTERVAL_DISABLED) {
                    LocationRequest request = new LocationRequest.Builder(mInterval)
                            .setMaxUpdateDelayMillis(mRequest.getMaxUpdateDelayMillis())
                            .setQuality(mRequest.getQuality())
                            .setLowPower(mRequest.isLowPower())
                            .setLocationSettingsIgnored(mRequest.isLocationSettingsIgnored())
                            .setWorkSource(mRequest.getWorkSource())
                            .setHiddenFromAppOps(true)
                            .build();
                    mLocationManager.requestLocationUpdates(mProvider, request,
                            mContext.getMainExecutor(), this);
                }
            }
        }

        void flush(OnFlushCompleteCallback callback) {
            synchronized (mLock) {
                int requestCode = mNextFlushCode++;
                mPendingFlushes.put(requestCode, callback);
                mLocationManager.requestFlush(mProvider, this, requestCode);
            }
        }

        @Override
        public void onLocationChanged(Location location) {
            synchronized (mLock) {
                mLocation = location;
                reportBestLocationLocked();
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            synchronized (mLock) {
                // if satisfying a bypass request, don't clear anything
                if (mRequest.isActive() && mRequest.isLocationSettingsIgnored()) {
                    return;
                }

                mLocation = null;
            }
        }

        @Override
        public void onFlushComplete(int requestCode) {
            synchronized (mLock) {
                OnFlushCompleteCallback callback = mPendingFlushes.removeReturnOld(requestCode);
                if (callback != null) {
                    callback.onFlushComplete();
                }
            }
        }
    }
}
