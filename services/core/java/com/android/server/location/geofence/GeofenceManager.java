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

package com.android.server.location.geofence;

import static android.Manifest.permission;
import static android.location.LocationManager.KEY_PROXIMITY_ENTERING;
import static android.location.util.identity.CallerIdentity.PERMISSION_FINE;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Geofence;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.util.identity.CallerIdentity;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.WorkSource;
import android.stats.location.LocationStatsEnums;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.PendingIntentUtils;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.PendingIntentListenerRegistration;

import java.util.Collection;

/**
 * Manages all geofences.
 */
public class GeofenceManager extends
        ListenerMultiplexer<GeofenceKey, Geofence, PendingIntent,
                        GeofenceManager.GeofenceRegistration, LocationRequest> implements
        LocationListener {

    private static final String TAG = "GeofenceManager";

    private static final String ATTRIBUTION_TAG = "GeofencingService";

    private static final long WAKELOCK_TIMEOUT_MS = 30000;

    private static final int MAX_SPEED_M_S = 100;  // 360 km/hr (high speed train)
    private static final long MAX_LOCATION_AGE_NANOS = 5 * 60 * 1000000000L; // five minutes
    private static final long MAX_LOCATION_INTERVAL_MS = 2 * 60 * 60 * 1000; // two hours

    protected final class GeofenceRegistration extends
            PendingIntentListenerRegistration<Geofence, PendingIntent> {

        private static final int STATE_UNKNOWN = 0;
        private static final int STATE_INSIDE = 1;
        private static final int STATE_OUTSIDE = 2;

        private final Location mCenter;
        private final PowerManager.WakeLock mWakeLock;

        private int mGeofenceState;

        // we store these values because we don't trust the listeners not to give us dupes, not to
        // spam us, and because checking the values may be more expensive
        private boolean mAppOpsAllowed;

        private @Nullable Location mCachedLocation;
        private float mCachedLocationDistanceM;

        protected GeofenceRegistration(Geofence geofence, CallerIdentity callerIdentity,
                PendingIntent pendingIntent) {
            super(geofence, callerIdentity, pendingIntent);

            mCenter = new Location("");
            mCenter.setLatitude(geofence.getLatitude());
            mCenter.setLongitude(geofence.getLongitude());

            synchronized (mLock) {
                // don't register geofences before the system is ready
                Preconditions.checkState(mPowerManager != null);
            }

            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    TAG + ":" + callerIdentity.packageName);
            mWakeLock.setReferenceCounted(true);
            mWakeLock.setWorkSource(new WorkSource(callerIdentity.uid, callerIdentity.packageName));
        }

        @Override
        protected GeofenceManager getOwner() {
            return GeofenceManager.this;
        }

        @Override
        protected boolean onPendingIntentRegister(Object key) {
            mGeofenceState = STATE_UNKNOWN;
            mAppOpsAllowed = mAppOpsHelper.checkLocationAccess(getIdentity());
            return true;
        }

        @Override
        protected ListenerOperation<PendingIntent> onActive() {
            return onLocationChanged(getLastLocation());
        }

        boolean isAppOpsAllowed() {
            return mAppOpsAllowed;
        }

        boolean onAppOpsChanged(String packageName) {
            if (getIdentity().packageName.equals(packageName)) {
                boolean appOpsAllowed = mAppOpsHelper.checkLocationAccess(getIdentity());
                if (appOpsAllowed != mAppOpsAllowed) {
                    mAppOpsAllowed = appOpsAllowed;
                    return true;
                }
            }

            return false;
        }

        double getDistanceToBoundary(Location location) {
            if (!location.equals(mCachedLocation)) {
                mCachedLocation = location;
                mCachedLocationDistanceM = mCenter.distanceTo(mCachedLocation);
            }

            return Math.abs(getRequest().getRadius() - mCachedLocationDistanceM);
        }

        ListenerOperation<PendingIntent> onLocationChanged(Location location) {
            // remove expired fences
            if (getRequest().isExpired()) {
                remove();
                return null;
            }

            mCachedLocation = location;
            mCachedLocationDistanceM = mCenter.distanceTo(mCachedLocation);

            int oldState = mGeofenceState;
            float radius = Math.max(getRequest().getRadius(), location.getAccuracy());
            if (mCachedLocationDistanceM <= radius) {
                mGeofenceState = STATE_INSIDE;
                if (oldState != STATE_INSIDE) {
                    return pendingIntent -> sendIntent(pendingIntent, true);
                }
            } else {
                mGeofenceState = STATE_OUTSIDE;
                if (oldState == STATE_INSIDE) {
                    // return exit only if previously entered
                    return pendingIntent -> sendIntent(pendingIntent, false);
                }
            }

            return null;
        }

        private void sendIntent(PendingIntent pendingIntent, boolean entering) {
            Intent intent = new Intent().putExtra(KEY_PROXIMITY_ENTERING, entering);

            mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            try {
                pendingIntent.send(mContext, 0, intent,
                        (pI, i, rC, rD, rE) -> mWakeLock.release(),
                        null, permission.ACCESS_FINE_LOCATION,
                        PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
            } catch (PendingIntent.CanceledException e) {
                mWakeLock.release();
                removeRegistration(new GeofenceKey(pendingIntent, getRequest()), this);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());

            ArraySet<String> flags = new ArraySet<>(1);
            if (!mAppOpsAllowed) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }

            builder.append(" ").append(getRequest());
            return builder.toString();
        }
    }

    final Object mLock = new Object();

    protected final Context mContext;

    private final UserInfoHelper.UserListener mUserChangedListener = this::onUserChanged;
    private final SettingsHelper.UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final SettingsHelper.UserSettingChangedListener
            mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final AppOpsHelper.LocationAppOpListener mAppOpsChangedListener = this::onAppOpsChanged;

    protected final UserInfoHelper mUserInfoHelper;
    protected final AppOpsHelper mAppOpsHelper;
    protected final SettingsHelper mSettingsHelper;
    protected final LocationUsageLogger mLocationUsageLogger;

    protected PowerManager mPowerManager;
    protected LocationManager mLocationManager;

    @GuardedBy("mLock")
    private @Nullable Location mLastLocation;

    public GeofenceManager(Context context, UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            LocationUsageLogger locationUsageLogger) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mUserInfoHelper = userInfoHelper;
        mSettingsHelper = settingsHelper;
        mAppOpsHelper = appOpsHelper;
        mLocationUsageLogger = locationUsageLogger;
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        synchronized (mLock) {
            if (mLocationManager != null) {
                return;
            }

            mUserInfoHelper.onSystemReady();
            mSettingsHelper.onSystemReady();
            mAppOpsHelper.onSystemReady();

            mPowerManager = mContext.getSystemService(PowerManager.class);
            mLocationManager = mContext.getSystemService(LocationManager.class);
        }
    }

    /**
     * Adds a new geofence, replacing any geofence already associated with the PendingIntent. It
     * doesn't make any real sense to register multiple geofences with the same pending intent, but
     * we continue to allow this for backwards compatibility.
     */
    public void addGeofence(Geofence geofence, PendingIntent pendingIntent, String packageName,
            @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                AppOpsManager.toReceiverId(pendingIntent));
        identity.enforceLocationPermission(PERMISSION_FINE);

        addRegistration(new GeofenceKey(pendingIntent, geofence),
                new GeofenceRegistration(geofence, identity, pendingIntent));
    }

    /**
     * Removes the geofence associated with the PendingIntent.
     */
    public void removeGeofence(PendingIntent pendingIntent) {
        removeRegistrationIf(key -> key.getPendingIntent().equals(pendingIntent));
    }

    @Override
    protected boolean isActive(GeofenceRegistration registration) {
        CallerIdentity identity = registration.getIdentity();
        return registration.isAppOpsAllowed()
                && mUserInfoHelper.isCurrentUserId(identity.userId)
                && mSettingsHelper.isLocationEnabled(identity.userId)
                && !mSettingsHelper.isLocationPackageBlacklisted(identity.userId,
                identity.packageName);
    }

    @Override
    protected void onRegister() {
        mUserInfoHelper.addListener(mUserChangedListener);
        mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mAppOpsHelper.addListener(mAppOpsChangedListener);
    }

    @Override
    protected void onUnregister() {
        mUserInfoHelper.removeListener(mUserChangedListener);
        mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mAppOpsHelper.removeListener(mAppOpsChangedListener);
    }

    @Override
    protected void onRegistrationAdded(GeofenceKey key, GeofenceRegistration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                registration.getIdentity().packageName,
                /* LocationRequest= */ null,
                /* hasListener= */ false,
                true,
                registration.getRequest(),
                true);
    }

    @Override
    protected void onRegistrationRemoved(GeofenceKey key, GeofenceRegistration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                registration.getIdentity().packageName,
                /* LocationRequest= */ null,
                /* hasListener= */ false,
                true,
                registration.getRequest(),
                true);
    }

    @Override
    protected boolean registerWithService(LocationRequest locationRequest) {
        synchronized (mLock) {
            Preconditions.checkState(mLocationManager != null);
        }

        mLocationManager.requestLocationUpdates(locationRequest, DIRECT_EXECUTOR, this);
        return true;
    }

    @Override
    protected void unregisterWithService() {
        synchronized (mLock) {
            Preconditions.checkState(mLocationManager != null);
            mLocationManager.removeUpdates(this);
            mLastLocation = null;
        }
    }

    @Override
    protected LocationRequest mergeRequests(Collection<GeofenceRegistration> registrations) {
        Location location = getLastLocation();

        long realtimeMs = SystemClock.elapsedRealtime();

        WorkSource workSource = null;
        double minFenceDistanceM = Double.MAX_VALUE;
        for (GeofenceRegistration registration : registrations) {
            if (registration.getRequest().isExpired(realtimeMs)) {
                continue;
            }

            CallerIdentity identity = registration.getIdentity();
            if (workSource == null) {
                workSource = new WorkSource(identity.uid, identity.packageName);
            } else {
                workSource.add(identity.uid, identity.packageName);
            }

            if (location != null) {
                double fenceDistanceM = registration.getDistanceToBoundary(location);
                if (fenceDistanceM < minFenceDistanceM) {
                    minFenceDistanceM = fenceDistanceM;
                }
            }
        }

        long intervalMs;
        if (Double.compare(minFenceDistanceM, Double.MAX_VALUE) < 0) {
            intervalMs = (long) Math.min(MAX_LOCATION_INTERVAL_MS,
                    Math.max(
                            mSettingsHelper.getBackgroundThrottleProximityAlertIntervalMs(),
                            minFenceDistanceM * 1000 / MAX_SPEED_M_S));
        } else {
            intervalMs = mSettingsHelper.getBackgroundThrottleProximityAlertIntervalMs();
        }

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                LocationManager.FUSED_PROVIDER, intervalMs, 0, false);
        request.setFastestInterval(0);
        request.setHideFromAppOps(true);
        request.setWorkSource(workSource);

        return request;
    }


    @Override
    public void onLocationChanged(Location location) {
        synchronized (mLock) {
            mLastLocation = location;
        }

        deliverToListeners(registration -> {
            return registration.onLocationChanged(location);
        });
        updateService();
    }

    @Nullable Location getLastLocation() {
        Location location;
        synchronized (mLock) {
            location = mLastLocation;
        }

        if (location == null) {
            synchronized (mLock) {
                Preconditions.checkState(mLocationManager != null);
            }

            location = mLocationManager.getLastLocation();
        }

        if (location != null) {
            if (location.getElapsedRealtimeAgeNanos() > MAX_LOCATION_AGE_NANOS) {
                location = null;
            }
        }

        return location;
    }

    private void onUserChanged(int userId, int change) {
        if (change == UserInfoHelper.UserListener.USER_SWITCHED) {
            updateRegistrations(registration -> registration.getIdentity().userId == userId);
        }
    }

    private void onLocationEnabledChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().userId == userId);
    }

    private void onLocationPackageBlacklistChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().userId == userId);
    }

    private void onAppOpsChanged(String packageName) {
        updateRegistrations(registration -> registration.onAppOpsChanged(packageName));
    }
}
