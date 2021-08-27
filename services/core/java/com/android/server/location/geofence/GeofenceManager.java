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

import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.KEY_PROXIMITY_ENTERING;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;

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
import android.os.Binder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.WorkSource;
import android.stats.location.LocationStatsEnums;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.server.PendingIntentUtils;
import com.android.server.location.LocationPermissions;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.injector.UserInfoHelper.UserListener;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.PendingIntentListenerRegistration;

import java.util.Collection;
import java.util.Objects;

/**
 * Manages all geofences.
 */
public class GeofenceManager extends
        ListenerMultiplexer<GeofenceKey, PendingIntent, GeofenceManager.GeofenceRegistration,
                LocationRequest> implements
        LocationListener {

    private static final String TAG = "GeofenceManager";

    private static final String ATTRIBUTION_TAG = "GeofencingService";

    private static final long WAKELOCK_TIMEOUT_MS = 30000;

    private static final int MAX_SPEED_M_S = 100;  // 360 km/hr (high speed train)
    private static final long MAX_LOCATION_AGE_MS = 5 * 60 * 1000L; // five minutes
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
        private boolean mPermitted;

        private @Nullable Location mCachedLocation;
        private float mCachedLocationDistanceM;

        protected GeofenceRegistration(Geofence geofence, CallerIdentity identity,
                PendingIntent pendingIntent) {
            super(geofence, identity, pendingIntent);

            mCenter = new Location("");
            mCenter.setLatitude(geofence.getLatitude());
            mCenter.setLongitude(geofence.getLongitude());

            mWakeLock = Objects.requireNonNull(mContext.getSystemService(PowerManager.class))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            TAG + ":" + identity.getPackageName());
            mWakeLock.setReferenceCounted(true);
            mWakeLock.setWorkSource(identity.addToWorkSource(null));
        }

        @Override
        protected GeofenceManager getOwner() {
            return GeofenceManager.this;
        }

        @Override
        protected void onPendingIntentListenerRegister() {
            mGeofenceState = STATE_UNKNOWN;
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(PERMISSION_FINE,
                    getIdentity());
        }

        @Override
        protected void onActive() {
            Location location = getLastLocation();
            if (location != null) {
                executeOperation(onLocationChanged(location));
            }
        }

        boolean isPermitted() {
            return mPermitted;
        }

        boolean onLocationPermissionsChanged(String packageName) {
            if (getIdentity().getPackageName().equals(packageName)) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        boolean onLocationPermissionsChanged(int uid) {
            if (getIdentity().getUid() == uid) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        private boolean onLocationPermissionsChanged() {
            boolean permitted = mLocationPermissionsHelper.hasLocationPermissions(PERMISSION_FINE,
                    getIdentity());
            if (permitted != mPermitted) {
                mPermitted = permitted;
                return true;
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
                // send() only enforces permissions for broadcast intents, but since clients can
                // select any kind of pending intent we do not rely on send() to enforce permissions
                pendingIntent.send(mContext, 0, intent, (pI, i, rC, rD, rE) -> mWakeLock.release(),
                        null, null, PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
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
            if (!mPermitted) {
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

    private final UserListener mUserChangedListener = this::onUserChanged;
    private final SettingsHelper.UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final SettingsHelper.UserSettingChangedListener
            mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final LocationPermissionsHelper.LocationPermissionsListener
            mLocationPermissionsListener =
            new LocationPermissionsHelper.LocationPermissionsListener() {
                @Override
                public void onLocationPermissionsChanged(String packageName) {
                    GeofenceManager.this.onLocationPermissionsChanged(packageName);
                }

                @Override
                public void onLocationPermissionsChanged(int uid) {
                    GeofenceManager.this.onLocationPermissionsChanged(uid);
                }
            };

    protected final UserInfoHelper mUserInfoHelper;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    protected final SettingsHelper mSettingsHelper;
    protected final LocationUsageLogger mLocationUsageLogger;

    @GuardedBy("mLock")
    private @Nullable LocationManager mLocationManager;

    @GuardedBy("mLock")
    private @Nullable Location mLastLocation;

    public GeofenceManager(Context context, Injector injector) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mUserInfoHelper = injector.getUserInfoHelper();
        mSettingsHelper = injector.getSettingsHelper();
        mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        mLocationUsageLogger = injector.getLocationUsageLogger();
    }

    @Override
    public String getTag() {
        return TAG;
    }

    private LocationManager getLocationManager() {
        synchronized (mLock) {
            if (mLocationManager == null) {
                mLocationManager = Objects.requireNonNull(
                        mContext.getSystemService(LocationManager.class));
            }

            return mLocationManager;
        }
    }

    /**
     * Adds a new geofence, replacing any geofence already associated with the PendingIntent. It
     * doesn't make any real sense to register multiple geofences with the same pending intent, but
     * we continue to allow this for backwards compatibility.
     */
    public void addGeofence(Geofence geofence, PendingIntent pendingIntent, String packageName,
            @Nullable String attributionTag) {
        LocationPermissions.enforceCallingOrSelfLocationPermission(mContext, PERMISSION_FINE);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName,
                attributionTag, AppOpsManager.toReceiverId(pendingIntent));

        final long ident = Binder.clearCallingIdentity();
        try {
            putRegistration(new GeofenceKey(pendingIntent, geofence),
                    new GeofenceRegistration(geofence, identity, pendingIntent));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Removes the geofence associated with the PendingIntent.
     */
    public void removeGeofence(PendingIntent pendingIntent) {
        final long identity = Binder.clearCallingIdentity();
        try {
            removeRegistrationIf(key -> key.getPendingIntent().equals(pendingIntent));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    protected boolean isActive(GeofenceRegistration registration) {
        return registration.isPermitted() && isActive(registration.getIdentity());
    }

    private boolean isActive(CallerIdentity identity) {
        if (identity.isSystemServer()) {
            if (!mSettingsHelper.isLocationEnabled(mUserInfoHelper.getCurrentUserId())) {
                return false;
            }
        } else {
            if (!mSettingsHelper.isLocationEnabled(identity.getUserId())) {
                return false;
            }
            if (!mUserInfoHelper.isCurrentUserId(identity.getUserId())) {
                return false;
            }
            if (mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                    identity.getPackageName())) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void onRegister() {
        mUserInfoHelper.addListener(mUserChangedListener);
        mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mLocationPermissionsHelper.addListener(mLocationPermissionsListener);
    }

    @Override
    protected void onUnregister() {
        mUserInfoHelper.removeListener(mUserChangedListener);
        mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mLocationPermissionsHelper.removeListener(mLocationPermissionsListener);
    }

    @Override
    protected void onRegistrationAdded(GeofenceKey key, GeofenceRegistration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                null,
                /* LocationRequest= */ null,
                /* hasListener= */ false,
                true,
                registration.getRequest(), true);
    }

    @Override
    protected void onRegistrationRemoved(GeofenceKey key, GeofenceRegistration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                null,
                /* LocationRequest= */ null,
                /* hasListener= */ false,
                true,
                registration.getRequest(), true);
    }

    @Override
    protected boolean registerWithService(LocationRequest locationRequest,
            Collection<GeofenceRegistration> registrations) {
        getLocationManager().requestLocationUpdates(FUSED_PROVIDER, locationRequest,
                DIRECT_EXECUTOR, this);
        return true;
    }

    @Override
    protected void unregisterWithService() {
        synchronized (mLock) {
            getLocationManager().removeUpdates(this);
            mLastLocation = null;
        }
    }

    @Override
    protected LocationRequest mergeRegistrations(Collection<GeofenceRegistration> registrations) {
        Location location = getLastLocation();

        long realtimeMs = SystemClock.elapsedRealtime();

        WorkSource workSource = null;
        double minFenceDistanceM = Double.MAX_VALUE;
        for (GeofenceRegistration registration : registrations) {
            if (registration.getRequest().isExpired(realtimeMs)) {
                continue;
            }

            workSource = registration.getIdentity().addToWorkSource(workSource);

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

        return new LocationRequest.Builder(intervalMs)
                .setMinUpdateIntervalMillis(0)
                .setHiddenFromAppOps(true)
                .setWorkSource(workSource)
                .build();
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
            location = getLocationManager().getLastLocation();
        }

        if (location != null) {
            if (location.getElapsedRealtimeAgeMillis() > MAX_LOCATION_AGE_MS) {
                location = null;
            }
        }

        return location;
    }

    void onUserChanged(int userId, int change) {
        if (change == UserListener.CURRENT_USER_CHANGED) {
            updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
        }
    }

    void onLocationEnabledChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    void onLocationPackageBlacklistChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    void onLocationPermissionsChanged(String packageName) {
        updateRegistrations(registration -> registration.onLocationPermissionsChanged(packageName));
    }

    void onLocationPermissionsChanged(int uid) {
        updateRegistrations(registration -> registration.onLocationPermissionsChanged(uid));
    }
}
