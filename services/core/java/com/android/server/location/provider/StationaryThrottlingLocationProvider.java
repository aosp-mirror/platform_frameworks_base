/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.provider;

import static android.location.provider.ProviderRequest.INTERVAL_DISABLED;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.eventlog.LocationEventLog.EVENT_LOG;

import static java.lang.Math.max;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.provider.ProviderRequest;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.DeviceIdleInternal;
import com.android.server.FgThread;
import com.android.server.location.injector.DeviceIdleHelper;
import com.android.server.location.injector.DeviceStationaryHelper;
import com.android.server.location.injector.Injector;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Throttles location providers completely while the device is in doze and stationary, and returns
 * the last known location as a new location at the appropriate interval instead. Hypothetically,
 * this throttling could be applied only when the device is stationary - however we don't trust the
 * accuracy of the on-device SMD (which could allow for 100s of feet of movement without triggering
 * in some use cases) enough to rely just on this. Instead we require the device to be in doze mode
 * and stationary to narrow down the effect of false positives/negatives.
 */
public final class StationaryThrottlingLocationProvider extends DelegateLocationProvider
        implements DeviceIdleHelper.DeviceIdleListener, DeviceIdleInternal.StationaryListener {

    private static final long MAX_STATIONARY_LOCATION_AGE_MS = 30000;
    private static final long MIN_INTERVAL_MS = 1000;

    final Object mLock = new Object();

    private final String mName;
    private final DeviceIdleHelper mDeviceIdleHelper;
    private final DeviceStationaryHelper mDeviceStationaryHelper;

    @GuardedBy("mLock")
    private boolean mDeviceIdle = false;
    @GuardedBy("mLock")
    private boolean mDeviceStationary = false;
    @GuardedBy("mLock")
    private long mDeviceStationaryRealtimeMs = Long.MIN_VALUE;
    @GuardedBy("mLock")
    private ProviderRequest mIncomingRequest = ProviderRequest.EMPTY_REQUEST;
    @GuardedBy("mLock")
    private ProviderRequest mOutgoingRequest = ProviderRequest.EMPTY_REQUEST;
    @GuardedBy("mLock")
    long mThrottlingIntervalMs = INTERVAL_DISABLED;
    @GuardedBy("mLock")
    @Nullable DeliverLastLocationRunnable mDeliverLastLocationCallback = null;
    @GuardedBy("mLock")
    @Nullable Location mLastLocation;

    public StationaryThrottlingLocationProvider(String name, Injector injector,
            AbstractLocationProvider delegate) {
        super(DIRECT_EXECUTOR, delegate);

        mName = name;
        mDeviceIdleHelper = injector.getDeviceIdleHelper();
        mDeviceStationaryHelper = injector.getDeviceStationaryHelper();

        // must be last statement in the constructor because reference is escaping
        initializeDelegate();
    }

    @Override
    public void onReportLocation(LocationResult locationResult) {
        super.onReportLocation(locationResult);

        synchronized (mLock) {
            mLastLocation = locationResult.getLastLocation();
            onThrottlingChangedLocked(false);
        }
    }

    @Override
    protected void onStart() {
        mDelegate.getController().start();

        synchronized (mLock) {
            mDeviceIdleHelper.addListener(this);
            onDeviceIdleChanged(mDeviceIdleHelper.isDeviceIdle());
        }
    }

    @Override
    protected void onStop() {
        synchronized (mLock) {
            mDeviceIdleHelper.removeListener(this);
            onDeviceIdleChanged(false);

            mIncomingRequest = ProviderRequest.EMPTY_REQUEST;
            mOutgoingRequest = ProviderRequest.EMPTY_REQUEST;
            mThrottlingIntervalMs = INTERVAL_DISABLED;

            if (mDeliverLastLocationCallback != null) {
                FgThread.getHandler().removeCallbacks(mDeliverLastLocationCallback);
                mDeliverLastLocationCallback = null;
            }

            mLastLocation = null;
        }

        mDelegate.getController().stop();
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {
        synchronized (mLock) {
            mIncomingRequest = request;
            onThrottlingChangedLocked(true);
        }
    }

    @Override
    public void onDeviceIdleChanged(boolean deviceIdle) {
        synchronized (mLock) {
            if (deviceIdle == mDeviceIdle) {
                return;
            }

            mDeviceIdle = deviceIdle;
            if (deviceIdle) {
                // device stationary helper will deliver an immediate listener update
                mDeviceStationaryHelper.addListener(this);
            } else {
                mDeviceStationaryHelper.removeListener(this);
                mDeviceStationary = false;
                mDeviceStationaryRealtimeMs = Long.MIN_VALUE;
                onThrottlingChangedLocked(false);
            }
        }
    }

    @Override
    public void onDeviceStationaryChanged(boolean deviceStationary) {
        synchronized (mLock) {
            if (!mDeviceIdle) {
                // stationary detection is only registered while idle - ignore late notifications
                return;
            }

            if (mDeviceStationary == deviceStationary) {
                return;
            }

            mDeviceStationary = deviceStationary;
            if (mDeviceStationary) {
                mDeviceStationaryRealtimeMs = SystemClock.elapsedRealtime();
            } else {
                mDeviceStationaryRealtimeMs = Long.MIN_VALUE;
            }
            onThrottlingChangedLocked(false);
        }
    }

    @GuardedBy("mLock")
    private void onThrottlingChangedLocked(boolean deliverImmediate) {
        long throttlingIntervalMs = INTERVAL_DISABLED;
        if (mDeviceStationary && mDeviceIdle && !mIncomingRequest.isLocationSettingsIgnored()
                && mIncomingRequest.getQuality() != LocationRequest.QUALITY_HIGH_ACCURACY
                && mLastLocation != null
                && mLastLocation.getElapsedRealtimeAgeMillis(mDeviceStationaryRealtimeMs)
                <= MAX_STATIONARY_LOCATION_AGE_MS) {
            throttlingIntervalMs = max(mIncomingRequest.getIntervalMillis(), MIN_INTERVAL_MS);
        }

        ProviderRequest newRequest;
        if (throttlingIntervalMs != INTERVAL_DISABLED) {
            newRequest = ProviderRequest.EMPTY_REQUEST;
        } else {
            newRequest = mIncomingRequest;
        }

        if (!newRequest.equals(mOutgoingRequest)) {
            mOutgoingRequest = newRequest;
            mDelegate.getController().setRequest(mOutgoingRequest);
        }

        if (throttlingIntervalMs == mThrottlingIntervalMs) {
            return;
        }

        long oldThrottlingIntervalMs = mThrottlingIntervalMs;
        mThrottlingIntervalMs = throttlingIntervalMs;

        if (mThrottlingIntervalMs != INTERVAL_DISABLED) {
            if (oldThrottlingIntervalMs == INTERVAL_DISABLED) {
                if (D) {
                    Log.d(TAG, mName + " provider stationary throttled");
                }
                EVENT_LOG.logProviderStationaryThrottled(mName, true, mOutgoingRequest);
            }

            if (mDeliverLastLocationCallback != null) {
                FgThread.getHandler().removeCallbacks(mDeliverLastLocationCallback);
            }
            mDeliverLastLocationCallback = new DeliverLastLocationRunnable();

            Preconditions.checkState(mLastLocation != null);

            if (deliverImmediate) {
                FgThread.getHandler().post(mDeliverLastLocationCallback);
            } else {
                long delayMs = mThrottlingIntervalMs - mLastLocation.getElapsedRealtimeAgeMillis();
                FgThread.getHandler().postDelayed(mDeliverLastLocationCallback, delayMs);
            }
        } else {
            if (oldThrottlingIntervalMs != INTERVAL_DISABLED) {
                EVENT_LOG.logProviderStationaryThrottled(mName, false, mOutgoingRequest);
                if (D) {
                    Log.d(TAG, mName + " provider stationary unthrottled");
                }
            }

            FgThread.getHandler().removeCallbacks(mDeliverLastLocationCallback);
            mDeliverLastLocationCallback = null;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mThrottlingIntervalMs != INTERVAL_DISABLED) {
            pw.println("stationary throttled=" + mLastLocation);
        } else {
            pw.print("stationary throttled=false");
            if (!mDeviceIdle) {
                pw.print(" (not idle)");
            }
            if (!mDeviceStationary) {
                pw.print(" (not stationary)");
            }
            pw.println();
        }

        mDelegate.dump(fd, pw, args);
    }

    private class DeliverLastLocationRunnable implements Runnable {

        DeliverLastLocationRunnable() {}

        @Override
        public void run() {
            Location location;
            synchronized (mLock) {
                if (mDeliverLastLocationCallback != this) {
                    return;
                }
                if (mLastLocation == null) {
                    return;
                }

                location = new Location(mLastLocation);
                location.setTime(System.currentTimeMillis());
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                if (location.hasSpeed()) {
                    location.removeSpeed();
                    if (location.hasSpeedAccuracy()) {
                        location.removeSpeedAccuracy();
                    }
                }
                if (location.hasBearing()) {
                    location.removeBearing();
                    if (location.hasBearingAccuracy()) {
                        location.removeBearingAccuracy();
                    }
                }

                mLastLocation = location;
                FgThread.getHandler().postDelayed(this, mThrottlingIntervalMs);
            }

            reportLocation(LocationResult.wrap(location));
        }
    }
}
