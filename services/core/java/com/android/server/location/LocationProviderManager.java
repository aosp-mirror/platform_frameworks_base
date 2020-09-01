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

package com.android.server.location;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.WINDOW_EXACT;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.KEY_LOCATION_CHANGED;
import static android.location.LocationManager.KEY_PROVIDER_ENABLED;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.os.IPowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationPermissions.PERMISSION_NONE;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.LocationRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.PowerManager.LocationPowerSaveMode;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.stats.location.LocationStatsEnums;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.PendingIntentUtils;
import com.android.server.location.LocationPermissions.PermissionLevel;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.RemoteListenerRegistration;
import com.android.server.location.util.AppForegroundHelper;
import com.android.server.location.util.AppForegroundHelper.AppForegroundListener;
import com.android.server.location.util.AppOpsHelper;
import com.android.server.location.util.Injector;
import com.android.server.location.util.LocationAttributionHelper;
import com.android.server.location.util.LocationPermissionsHelper;
import com.android.server.location.util.LocationPermissionsHelper.LocationPermissionsListener;
import com.android.server.location.util.LocationPowerSaveModeHelper;
import com.android.server.location.util.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener;
import com.android.server.location.util.LocationUsageLogger;
import com.android.server.location.util.ScreenInteractiveHelper;
import com.android.server.location.util.ScreenInteractiveHelper.ScreenInteractiveChangedListener;
import com.android.server.location.util.SettingsHelper;
import com.android.server.location.util.SettingsHelper.GlobalSettingChangedListener;
import com.android.server.location.util.SettingsHelper.UserSettingChangedListener;
import com.android.server.location.util.UserInfoHelper;
import com.android.server.location.util.UserInfoHelper.UserListener;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

class LocationProviderManager extends
        ListenerMultiplexer<Object, LocationRequest, LocationProviderManager.LocationTransport,
                LocationProviderManager.Registration, ProviderRequest> implements
        AbstractLocationProvider.Listener {

    // fastest interval at which clients may receive coarse locations
    public static final long FASTEST_COARSE_INTERVAL_MS = 10 * 60 * 1000;

    private static final String WAKELOCK_TAG = "*location*";
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 1000;

    // maximum interval to be considered "high power" request
    private static final long MAX_HIGH_POWER_INTERVAL_MS = 5 * 60 * 1000;

    // maximum age of a location before it is no longer considered "current"
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 10 * 1000;

    // max timeout allowed for getting the current location
    private static final long GET_CURRENT_LOCATION_MAX_TIMEOUT_MS = 30 * 1000;

    // maximum jitter allowed for fastest interval evaluation
    private static final int MAX_FASTEST_INTERVAL_JITTER_MS = 100;

    protected interface LocationTransport {

        void deliverOnLocationChanged(Location location, @Nullable Runnable onCompleteCallback)
                throws Exception;
    }

    protected interface ProviderTransport {

        void deliverOnProviderEnabledChanged(String provider, boolean enabled) throws Exception;
    }

    protected static final class LocationListenerTransport implements LocationTransport,
            ProviderTransport {

        private final ILocationListener mListener;

        protected LocationListenerTransport(ILocationListener listener) {
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback) throws RemoteException {
            mListener.onLocationChanged(location, SingleUseCallback.wrap(onCompleteCallback));
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws RemoteException {
            mListener.onProviderEnabledChanged(provider, enabled);
        }
    }

    protected static final class LocationPendingIntentTransport implements LocationTransport,
            ProviderTransport {

        private final Context mContext;
        private final PendingIntent mPendingIntent;

        public LocationPendingIntentTransport(Context context, PendingIntent pendingIntent) {
            mContext = context;
            mPendingIntent = pendingIntent;
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback)
                throws PendingIntent.CanceledException {
            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_LOCATION_CHANGED, location),
                    onCompleteCallback != null ? (pI, i, rC, rD, rE) -> onCompleteCallback.run()
                            : null, null, null,
                    PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws PendingIntent.CanceledException {
            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_PROVIDER_ENABLED, enabled),
                    null, null, null,
                    PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
        }
    }

    protected static final class GetCurrentLocationTransport implements LocationTransport {

        private final ILocationCallback mCallback;

        protected GetCurrentLocationTransport(ILocationCallback callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback)
                throws RemoteException {
            // ILocationCallback doesn't currently support completion callbacks
            Preconditions.checkState(onCompleteCallback == null);
            mCallback.onLocation(location);
        }
    }

    protected abstract class Registration extends
            RemoteListenerRegistration<LocationRequest, LocationTransport> {

        @PermissionLevel protected final int mPermissionLevel;
        private final WorkSource mWorkSource;

        // we cache these values because checking/calculating on the fly is more expensive
        private boolean mPermitted;
        private boolean mForeground;
        private LocationRequest mProviderLocationRequest;
        private boolean mIsUsingHighPower;

        protected Registration(LocationRequest request, CallerIdentity identity,
                LocationTransport transport, @PermissionLevel int permissionLevel) {
            super(TAG, Objects.requireNonNull(request), identity, transport);

            Preconditions.checkArgument(permissionLevel > PERMISSION_NONE);
            mPermissionLevel = permissionLevel;

            if (request.getWorkSource() != null && !request.getWorkSource().isEmpty()) {
                mWorkSource = request.getWorkSource();
            } else {
                mWorkSource = identity.addToWorkSource(null);
            }

            mProviderLocationRequest = super.getRequest();
        }

        @GuardedBy("mLock")
        @Override
        protected final void onRemovableListenerRegister() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (D) {
                Log.d(TAG, mName + " provider added registration from " + getIdentity() + " -> "
                        + getRequest());
            }

            // initialization order is important as there are ordering dependencies
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().getUid());
            mProviderLocationRequest = calculateProviderLocationRequest();
            mIsUsingHighPower = isUsingHighPower();

            onProviderListenerRegister();
        }

        @GuardedBy("mLock")
        @Override
        protected final void onRemovableListenerUnregister() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            onProviderListenerUnregister();

            if (D) {
                Log.d(TAG, mName + " provider removed registration from " + getIdentity());
            }
        }

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerRegister()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerRegister() {}

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerUnregister()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerUnregister() {}

        @Override
        protected final ListenerOperation<LocationTransport> onActive() {
            if (!getRequest().getHideFromAppOps()) {
                mLocationAttributionHelper.reportLocationStart(getIdentity(), getName(), getKey());
            }
            onHighPowerUsageChanged();
            return null;
        }

        @Override
        protected final ListenerOperation<LocationTransport> onInactive() {
            onHighPowerUsageChanged();
            if (!getRequest().getHideFromAppOps()) {
                mLocationAttributionHelper.reportLocationStop(getIdentity(), getName(), getKey());
            }
            return null;
        }

        @Override
        public final <Listener> void onOperationFailure(ListenerOperation<Listener> operation,
                Exception e) {
            synchronized (mLock) {
                super.onOperationFailure(operation, e);
            }
        }

        @Override
        public final LocationRequest getRequest() {
            return mProviderLocationRequest;
        }

        public final boolean isForeground() {
            return mForeground;
        }

        public final boolean isPermitted() {
            return mPermitted;
        }

        @Override
        protected final LocationProviderManager getOwner() {
            return LocationProviderManager.this;
        }

        protected final WorkSource getWorkSource() {
            return mWorkSource;
        }

        @GuardedBy("mLock")
        private void onHighPowerUsageChanged() {
            boolean isUsingHighPower = isUsingHighPower();
            if (isUsingHighPower != mIsUsingHighPower) {
                mIsUsingHighPower = isUsingHighPower;

                if (!getRequest().getHideFromAppOps()) {
                    if (mIsUsingHighPower) {
                        mLocationAttributionHelper.reportHighPowerLocationStart(
                                getIdentity(), getName(), getKey());
                    } else {
                        mLocationAttributionHelper.reportHighPowerLocationStop(
                                getIdentity(), getName(), getKey());
                    }
                }
            }
        }

        @GuardedBy("mLock")
        private boolean isUsingHighPower() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            return isActive()
                    && getRequest().getInterval() < MAX_HIGH_POWER_INTERVAL_MS
                    && getProperties().mPowerRequirement == Criteria.POWER_HIGH;
        }

        @GuardedBy("mLock")
        final boolean onLocationPermissionsChanged(String packageName) {
            if (getIdentity().getPackageName().equals(packageName)) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onLocationPermissionsChanged(int uid) {
            if (getIdentity().getUid() == uid) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        @GuardedBy("mLock")
        private boolean onLocationPermissionsChanged() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            boolean permitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            if (permitted != mPermitted) {
                if (D) {
                    Log.v(TAG, mName + " provider package " + getIdentity().getPackageName()
                            + " permitted = " + permitted);
                }

                mPermitted = permitted;
                return true;
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onForegroundChanged(int uid, boolean foreground) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (getIdentity().getUid() == uid && foreground != mForeground) {
                if (D) {
                    Log.v(TAG, mName + " provider uid " + uid + " foreground = " + foreground);
                }

                mForeground = foreground;

                // note that onProviderLocationRequestChanged() is always called
                return onProviderLocationRequestChanged()
                        || mLocationPowerSaveModeHelper.getLocationPowerSaveMode()
                        == LOCATION_MODE_FOREGROUND_ONLY;
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onProviderLocationRequestChanged() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            LocationRequest newRequest = calculateProviderLocationRequest();
            if (mProviderLocationRequest.equals(newRequest)) {
                return false;
            }

            LocationRequest oldRequest = mProviderLocationRequest;
            mProviderLocationRequest = newRequest;
            onHighPowerUsageChanged();
            updateService();

            // if location settings ignored has changed then the active state may have changed
            return oldRequest.isLocationSettingsIgnored() != newRequest.isLocationSettingsIgnored();
        }

        private LocationRequest calculateProviderLocationRequest() {
            LocationRequest newRequest = new LocationRequest(super.getRequest());

            if (newRequest.isLocationSettingsIgnored()) {
                // if we are not currently allowed use location settings ignored, disable it
                if (!mSettingsHelper.getIgnoreSettingsPackageWhitelist().contains(
                        getIdentity().getPackageName()) && !mLocationManagerInternal.isProvider(
                        null, getIdentity())) {
                    newRequest.setLocationSettingsIgnored(false);
                }
            }

            if (!newRequest.isLocationSettingsIgnored() && !isThrottlingExempt()) {
                // throttle in the background
                if (!mForeground) {
                    newRequest.setInterval(Math.max(newRequest.getInterval(),
                            mSettingsHelper.getBackgroundThrottleIntervalMs()));
                }
            }

            return newRequest;
        }

        private boolean isThrottlingExempt() {
            if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                    getIdentity().getPackageName())) {
                return true;
            }

            return mLocationManagerInternal.isProvider(null, getIdentity());
        }

        @Nullable
        abstract ListenerOperation<LocationTransport> acceptLocationChange(Location fineLocation);

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());

            ArraySet<String> flags = new ArraySet<>(2);
            if (!isForeground()) {
                flags.add("bg");
            }
            if (!isPermitted()) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }

            if (mPermissionLevel == PERMISSION_COARSE) {
                builder.append(" (COARSE)");
            }

            builder.append(" ").append(getRequest());
            return builder.toString();
        }
    }

    protected abstract class LocationRegistration extends Registration implements
            AlarmManager.OnAlarmListener, ProviderEnabledListener {

        private final PowerManager.WakeLock mWakeLock;

        private volatile ProviderTransport mProviderTransport;
        @Nullable private Location mLastLocation = null;
        private int mNumLocationsDelivered = 0;
        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected <TTransport extends LocationTransport & ProviderTransport> LocationRegistration(
                LocationRequest request, CallerIdentity identity, TTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
            mProviderTransport = transport;
            mWakeLock = Objects.requireNonNull(mContext.getSystemService(PowerManager.class))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            mWakeLock.setReferenceCounted(true);
            mWakeLock.setWorkSource(getWorkSource());
        }

        @Override
        protected void onListenerUnregister() {
            mProviderTransport = null;
        }

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerRegister() {
            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(
                    SystemClock.elapsedRealtime());

            // add alarm for expiration
            if (mExpirationRealtimeMs < SystemClock.elapsedRealtime()) {
                remove();
            } else if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                AlarmManager alarmManager = Objects.requireNonNull(
                        mContext.getSystemService(AlarmManager.class));
                alarmManager.set(ELAPSED_REALTIME_WAKEUP, mExpirationRealtimeMs, WINDOW_EXACT,
                        0, this, FgThread.getHandler(), getWorkSource());
            }

            // start listening for provider enabled/disabled events
            addEnabledListener(this);

            onLocationListenerRegister();

            // if the provider is currently disabled, let the client know immediately
            int userId = getIdentity().getUserId();
            if (!isEnabled(userId)) {
                onProviderEnabledChanged(mName, userId, false);
            }
        }

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerUnregister() {
            // stop listening for provider enabled/disabled events
            removeEnabledListener(this);

            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                AlarmManager alarmManager = Objects.requireNonNull(
                        mContext.getSystemService(AlarmManager.class));
                alarmManager.cancel(this);
            }

            onLocationListenerUnregister();
        }

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerRegister()}.
         */
        @GuardedBy("mLock")
        protected void onLocationListenerRegister() {}

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerUnregister()}.
         */
        @GuardedBy("mLock")
        protected void onLocationListenerUnregister() {}

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                        + " provider due to expiration at " + TimeUtils.formatRealtime(
                        mExpirationRealtimeMs));
            }

            synchronized (mLock) {
                remove();
            }
        }

        @GuardedBy("mLock")
        @Nullable
        @Override
        ListenerOperation<LocationTransport> acceptLocationChange(Location fineLocation) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            // check expiration time - alarm is not guaranteed to go off at the right time,
            // especially for short intervals
            if (SystemClock.elapsedRealtime() >= mExpirationRealtimeMs) {
                remove();
                return null;
            }

            Location location;
            switch (mPermissionLevel) {
                case PERMISSION_FINE:
                    location = fineLocation;
                    break;
                case PERMISSION_COARSE:
                    location = mLocationFudger.createCoarse(fineLocation);
                    break;
                default:
                    // shouldn't be possible to have a client added without location permissions
                    throw new AssertionError();
            }

            if (mLastLocation != null) {
                // check fastest interval
                long deltaMs = NANOSECONDS.toMillis(
                        location.getElapsedRealtimeNanos()
                                - mLastLocation.getElapsedRealtimeNanos());
                if (deltaMs < getRequest().getFastestInterval() - MAX_FASTEST_INTERVAL_JITTER_MS) {
                    return null;
                }

                // check smallest displacement
                double smallestDisplacement = getRequest().getSmallestDisplacement();
                if (smallestDisplacement > 0.0 && location.distanceTo(mLastLocation)
                        <= smallestDisplacement) {
                    return null;
                }
            }

            // note app ops
            if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(mPermissionLevel),
                    getIdentity())) {
                if (D) {
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                return null;
            }

            // update last location
            mLastLocation = location;

            return new ListenerOperation<LocationTransport>() {
                @Override
                public void onPreExecute() {
                    // don't acquire a wakelock for mock locations to prevent abuse
                    if (!location.isFromMockProvider()) {
                        mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    }
                }

                @Override
                public void operate(LocationTransport listener) throws Exception {
                    // if delivering to the same process, make a copy of the location first (since
                    // location is mutable)
                    Location deliveryLocation;
                    if (getIdentity().getPid() == Process.myPid()) {
                        deliveryLocation = new Location(location);
                    } else {
                        deliveryLocation = location;
                    }

                    listener.deliverOnLocationChanged(deliveryLocation,
                            location.isFromMockProvider() ? null : mWakeLock::release);
                }

                @Override
                public void onPostExecute(boolean success) {
                    if (!success && !location.isFromMockProvider()) {
                        mWakeLock.release();
                    }

                    if (success) {
                        // check num updates - if successful then this function will always be run
                        // from the same thread, and no additional synchronization is necessary
                        boolean remove = ++mNumLocationsDelivered >= getRequest().getNumUpdates();
                        if (remove) {
                            if (D) {
                                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                                        + " provider due to number of updates");
                            }

                            synchronized (mLock) {
                                remove();
                            }
                        }
                    }
                }
            };
        }

        @Override
        public void onProviderEnabledChanged(String provider, int userId, boolean enabled) {
            Preconditions.checkState(mName.equals(provider));

            if (userId != getIdentity().getUserId()) {
                return;
            }

            // we choose not to hold a wakelock for provider enabled changed events
            executeSafely(getExecutor(), () -> mProviderTransport,
                    listener -> listener.deliverOnProviderEnabledChanged(mName, enabled));
        }
    }

    protected final class LocationListenerRegistration extends LocationRegistration implements
            IBinder.DeathRecipient {

        protected LocationListenerRegistration(LocationRequest request, CallerIdentity identity,
                LocationListenerTransport transport, @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerRegister() {
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerUnregister() {
            ((IBinder) getKey()).unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider client died: " + getIdentity());
                }

                synchronized (mLock) {
                    remove();
                }
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final class LocationPendingIntentRegistration extends LocationRegistration implements
            PendingIntent.CancelListener {

        protected LocationPendingIntentRegistration(LocationRequest request,
                CallerIdentity identity, LocationPendingIntentTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerRegister() {
            ((PendingIntent) getKey()).registerCancelListener(this);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerUnregister() {
            ((PendingIntent) getKey()).unregisterCancelListener(this);
        }

        @Override
        public void onCancelled(PendingIntent intent) {
            synchronized (mLock) {
                remove();
            }
        }
    }

    protected final class GetCurrentLocationListenerRegistration extends Registration implements
            IBinder.DeathRecipient, ProviderEnabledListener, AlarmManager.OnAlarmListener {

        private volatile LocationTransport mTransport;

        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected GetCurrentLocationListenerRegistration(LocationRequest request,
                CallerIdentity identity, LocationTransport transport, int permissionLevel) {
            super(request, identity, transport, permissionLevel);
            mTransport = transport;
        }

        @GuardedBy("mLock")
        void deliverLocation(@Nullable Location location) {
            executeSafely(getExecutor(), () -> mTransport, acceptLocationChange(location));
        }

        @Override
        protected void onListenerUnregister() {
            mTransport = null;
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerRegister() {
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }

            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(
                    SystemClock.elapsedRealtime());

            // add alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                AlarmManager alarmManager = Objects.requireNonNull(
                        mContext.getSystemService(AlarmManager.class));
                alarmManager.set(ELAPSED_REALTIME_WAKEUP, mExpirationRealtimeMs, WINDOW_EXACT,
                        0, this, FgThread.getHandler(), getWorkSource());
            }

            // start listening for provider enabled/disabled events
            addEnabledListener(this);

            // if the provider is currently disabled fail immediately
            int userId = getIdentity().getUserId();
            if (!getRequest().isLocationSettingsIgnored() && !isEnabled(userId)) {
                deliverLocation(null);
            }
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerUnregister() {
            // stop listening for provider enabled/disabled events
            removeEnabledListener(this);

            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                AlarmManager alarmManager = Objects.requireNonNull(
                        mContext.getSystemService(AlarmManager.class));
                alarmManager.cancel(this);
            }

            ((IBinder) getKey()).unlinkToDeath(this, 0);
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                        + " provider due to expiration at " + TimeUtils.formatRealtime(
                        mExpirationRealtimeMs));
            }

            synchronized (mLock) {
                deliverLocation(null);
            }
        }

        @GuardedBy("mLock")
        @Nullable
        @Override
        ListenerOperation<LocationTransport> acceptLocationChange(@Nullable Location fineLocation) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            // lastly - note app ops
            Location location;
            if (fineLocation == null) {
                location = null;
            } else if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(mPermissionLevel),
                    getIdentity())) {
                if (D) {
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                location = null;
            } else {
                switch (mPermissionLevel) {
                    case PERMISSION_FINE:
                        location = fineLocation;
                        break;
                    case PERMISSION_COARSE:
                        location = mLocationFudger.createCoarse(fineLocation);
                        break;
                    default:
                        // shouldn't be possible to have a client added without location permissions
                        throw new AssertionError();
                }
            }

            return listener -> {
                // if delivering to the same process, make a copy of the location first (since
                // location is mutable)
                Location deliveryLocation = location;
                if (getIdentity().getPid() == Process.myPid() && location != null) {
                    deliveryLocation = new Location(location);
                }

                // we currently don't hold a wakelock for getCurrentLocation deliveries
                listener.deliverOnLocationChanged(deliveryLocation, null);

                synchronized (mLock) {
                    remove();
                }
            };
        }

        @Override
        public void onProviderEnabledChanged(String provider, int userId, boolean enabled) {
            Preconditions.checkState(mName.equals(provider));

            if (userId != getIdentity().getUserId()) {
                return;
            }

            // if the provider is disabled we give up on current location immediately
            if (!getRequest().isLocationSettingsIgnored() && !enabled) {
                synchronized (mLock) {
                    deliverLocation(null);
                }
            }
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider client died: " + getIdentity());
                }

                synchronized (mLock) {
                    remove();
                }
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final Object mLock = new Object();

    protected final String mName;
    @Nullable private final PassiveLocationProviderManager mPassiveManager;

    protected final Context mContext;

    @GuardedBy("mLock")
    private boolean mStarted;

    // maps of user id to value
    @GuardedBy("mLock")
    private final SparseBooleanArray mEnabled; // null or not present means unknown
    @GuardedBy("mLock")
    private final SparseArray<LastLocation> mLastLocations;

    @GuardedBy("mLock")
    private final ArrayList<ProviderEnabledListener> mEnabledListeners;

    protected final LocationManagerInternal mLocationManagerInternal;
    protected final SettingsHelper mSettingsHelper;
    protected final UserInfoHelper mUserInfoHelper;
    protected final AppOpsHelper mAppOpsHelper;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final LocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    protected final ScreenInteractiveHelper mScreenInteractiveHelper;
    protected final LocationAttributionHelper mLocationAttributionHelper;
    protected final LocationUsageLogger mLocationUsageLogger;
    protected final LocationFudger mLocationFudger;
    protected final LocationRequestStatistics mLocationRequestStatistics;

    private final UserListener mUserChangedListener = this::onUserChanged;
    private final UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final GlobalSettingChangedListener mBackgroundThrottlePackageWhitelistChangedListener =
            this::onBackgroundThrottlePackageWhitelistChanged;
    private final UserSettingChangedListener mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final LocationPermissionsListener mLocationPermissionsListener =
            new LocationPermissionsListener() {
                @Override
                public void onLocationPermissionsChanged(String packageName) {
                    LocationProviderManager.this.onLocationPermissionsChanged(packageName);
                }

                @Override
                public void onLocationPermissionsChanged(int uid) {
                    LocationProviderManager.this.onLocationPermissionsChanged(uid);
                }
            };
    private final AppForegroundListener mAppForegroundChangedListener =
            this::onAppForegroundChanged;
    private final GlobalSettingChangedListener mBackgroundThrottleIntervalChangedListener =
            this::onBackgroundThrottleIntervalChanged;
    private final GlobalSettingChangedListener mIgnoreSettingsPackageWhitelistChangedListener =
            this::onIgnoreSettingsWhitelistChanged;
    private final LocationPowerSaveModeChangedListener mLocationPowerSaveModeChangedListener =
            this::onLocationPowerSaveModeChanged;
    private final ScreenInteractiveChangedListener mScreenInteractiveChangedListener =
            this::onScreenInteractiveChanged;

    // acquiring mLock makes operations on mProvider atomic, but is otherwise unnecessary
    protected final MockableLocationProvider mProvider;

    LocationProviderManager(Context context, Injector injector, String name,
            @Nullable PassiveLocationProviderManager passiveManager) {
        mContext = context;
        mName = Objects.requireNonNull(name);
        mPassiveManager = passiveManager;
        mStarted = false;
        mEnabled = new SparseBooleanArray(2);
        mLastLocations = new SparseArray<>(2);

        mEnabledListeners = new ArrayList<>();

        mLocationManagerInternal = Objects.requireNonNull(
                LocalServices.getService(LocationManagerInternal.class));
        mSettingsHelper = injector.getSettingsHelper();
        mUserInfoHelper = injector.getUserInfoHelper();
        mAppOpsHelper = injector.getAppOpsHelper();
        mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        mAppForegroundHelper = injector.getAppForegroundHelper();
        mLocationPowerSaveModeHelper = injector.getLocationPowerSaveModeHelper();
        mScreenInteractiveHelper = injector.getScreenInteractiveHelper();
        mLocationAttributionHelper = injector.getLocationAttributionHelper();
        mLocationUsageLogger = injector.getLocationUsageLogger();
        mLocationRequestStatistics = injector.getLocationRequestStatistics();
        mLocationFudger = new LocationFudger(mSettingsHelper.getCoarseLocationAccuracyM());

        // initialize last since this lets our reference escape
        mProvider = new MockableLocationProvider(mLock, this);
    }

    public void startManager() {
        synchronized (mLock) {
            mStarted = true;

            mUserInfoHelper.addListener(mUserChangedListener);
            mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            long identity = Binder.clearCallingIdentity();
            try {
                // initialize enabled state
                onUserStarted(UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void stopManager() {
        synchronized (mLock) {
            mUserInfoHelper.removeListener(mUserChangedListener);
            mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            // notify and remove all listeners
            long identity = Binder.clearCallingIdentity();
            try {
                onUserStopped(UserHandle.USER_ALL);
                removeRegistrationIf(key -> true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            mEnabledListeners.clear();
            mStarted = false;
        }
    }

    public String getName() {
        return mName;
    }

    @Nullable
    public CallerIdentity getIdentity() {
        return mProvider.getState().identity;
    }

    @Nullable
    public ProviderProperties getProperties() {
        return mProvider.getState().properties;
    }

    public boolean hasProvider() {
        return mProvider.getProvider() != null;
    }

    public boolean isEnabled(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return false;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
            int index = mEnabled.indexOfKey(userId);
            if (index < 0) {
                // this generally shouldn't occur, but might be possible due to race conditions
                // on when we are notified of new users
                Log.w(TAG, mName + " provider saw user " + userId + " unexpectedly");
                onEnabledChanged(userId);
                index = mEnabled.indexOfKey(userId);
            }

            return mEnabled.valueAt(index);
        }
    }

    public void addEnabledListener(ProviderEnabledListener listener) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);
            mEnabledListeners.add(listener);
        }
    }

    public void removeEnabledListener(ProviderEnabledListener listener) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);
            mEnabledListeners.remove(listener);
        }
    }

    public void setRealProvider(AbstractLocationProvider provider) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setRealProvider(provider);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProvider(@Nullable MockProvider provider) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProvider(provider);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            // when removing a mock provider, also clear any mock last locations and reset the
            // location fudger. the mock provider could have been used to infer the current
            // location fudger offsets.
            if (provider == null) {
                final int lastLocationSize = mLastLocations.size();
                for (int i = 0; i < lastLocationSize; i++) {
                    mLastLocations.valueAt(i).clearMock();
                }

                mLocationFudger.resetOffsets();
            }
        }
    }

    public void setMockProviderAllowed(boolean enabled) {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProviderAllowed(enabled);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProviderLocation(Location location) {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            long identity = Binder.clearCallingIdentity();
            try {
                String locationProvider = location.getProvider();
                if (!TextUtils.isEmpty(locationProvider) && !mName.equals(locationProvider)) {
                    // The location has an explicit provider that is different from the mock
                    // provider name. The caller may be trying to fool us via b/33091107.
                    EventLog.writeEvent(0x534e4554, "33091107", Binder.getCallingUid(),
                            mName + "!=" + locationProvider);
                }

                mProvider.setMockProviderLocation(location);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public List<LocationRequest> getMockProviderRequests() {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            return mProvider.getCurrentRequest().locationRequests;
        }
    }

    @Nullable
    public Location getLastLocation(CallerIdentity identity, @PermissionLevel int permissionLevel,
            boolean ignoreLocationSettings) {
        if (mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                identity.getPackageName())) {
            return null;
        }
        if (!mUserInfoHelper.isCurrentUserId(identity.getUserId())) {
            return null;
        }
        if (!ignoreLocationSettings && !isEnabled(identity.getUserId())) {
            return null;
        }

        Location location = getLastLocationUnsafe(identity.getUserId(), permissionLevel,
                ignoreLocationSettings);

        // we don't note op here because we don't know what the client intends to do with the
        // location, the client is responsible for noting if necessary

        if (identity.getPid() == Process.myPid() && location != null) {
            // if delivering to the same process, make a copy of the location first (since
            // location is mutable)
            return new Location(location);
        } else {
            return location;
        }
    }

    /**
     * This function does not perform any permissions or safety checks, by calling it you are
     * committing to performing all applicable checks yourself. Prefer
     * {@link #getLastLocation(CallerIdentity, int, boolean)} where possible.
     */
    @Nullable
    public Location getLastLocationUnsafe(int userId, @PermissionLevel int permissionLevel,
            boolean ignoreLocationSettings) {
        if (userId == UserHandle.USER_ALL) {
            Location lastLocation = null;
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                Location next = getLastLocationUnsafe(runningUserIds[i], permissionLevel,
                        ignoreLocationSettings);
                if (lastLocation == null || (next != null && next.getElapsedRealtimeNanos()
                        > lastLocation.getElapsedRealtimeNanos())) {
                    lastLocation = next;
                }
            }
            return lastLocation;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                return null;
            }
            return lastLocation.get(permissionLevel, ignoreLocationSettings);
        }
    }

    public void injectLastLocation(Location location, int userId) {
        synchronized (mLock) {
            if (getLastLocationUnsafe(userId, PERMISSION_FINE, false) == null) {
                setLastLocation(location, userId);
            }
        }
    }

    private void setLastLocation(Location location, int userId) {
        if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                setLastLocation(location, runningUserIds[i]);
            }
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                lastLocation = new LastLocation();
                mLastLocations.put(userId, lastLocation);
            }

            Location coarseLocation = mLocationFudger.createCoarse(location);
            if (isEnabled(userId)) {
                lastLocation.set(location, coarseLocation);
            }
            lastLocation.setBypass(location, coarseLocation);
        }
    }

    public void getCurrentLocation(LocationRequest request, CallerIdentity callerIdentity,
            int permissionLevel, ICancellationSignal cancellationTransport,
            ILocationCallback callback) {
        Preconditions.checkArgument(mName.equals(request.getProvider()));

        if (request.getExpireIn() > GET_CURRENT_LOCATION_MAX_TIMEOUT_MS) {
            request.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        }

        GetCurrentLocationListenerRegistration registration =
                new GetCurrentLocationListenerRegistration(
                        request,
                        callerIdentity,
                        new GetCurrentLocationTransport(callback),
                        permissionLevel);

        synchronized (mLock) {
            if (mSettingsHelper.isLocationPackageBlacklisted(callerIdentity.getUserId(),
                    callerIdentity.getPackageName())) {
                registration.deliverLocation(null);
                return;
            }
            if (!mUserInfoHelper.isCurrentUserId(callerIdentity.getUserId())) {
                registration.deliverLocation(null);
                return;
            }
            if (!request.isLocationSettingsIgnored() && !isEnabled(callerIdentity.getUserId())) {
                registration.deliverLocation(null);
                return;
            }

            Location lastLocation = getLastLocationUnsafe(callerIdentity.getUserId(),
                    permissionLevel, request.isLocationSettingsIgnored());
            if (lastLocation != null) {
                long locationAgeMs = NANOSECONDS.toMillis(
                        SystemClock.elapsedRealtimeNanos()
                                - lastLocation.getElapsedRealtimeNanos());
                if (locationAgeMs < MAX_CURRENT_LOCATION_AGE_MS) {
                    registration.deliverLocation(lastLocation);
                    return;
                }

                if (!mAppForegroundHelper.isAppForeground(Binder.getCallingUid())
                        && locationAgeMs < mSettingsHelper.getBackgroundThrottleIntervalMs()) {
                    registration.deliverLocation(null);
                    return;
                }
            }

            // if last location isn't good enough then we add a location request
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(callback.asBinder(), registration);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        CancellationSignal cancellationSignal = CancellationSignal.fromTransport(
                cancellationTransport);
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(SingleUseCallback.wrap(
                    () -> {
                        synchronized (mLock) {
                            removeRegistration(callback.asBinder(), registration);
                        }
                    }));
        }
    }

    public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            mProvider.sendExtraCommand(uid, pid, command, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel, ILocationListener listener) {
        Preconditions.checkArgument(mName.equals(request.getProvider()));

        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(
                        listener.asBinder(),
                        new LocationListenerRegistration(
                                request,
                                callerIdentity,
                                new LocationListenerTransport(listener),
                                permissionLevel));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel, PendingIntent pendingIntent) {
        Preconditions.checkArgument(mName.equals(request.getProvider()));

        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(
                        pendingIntent,
                        new LocationPendingIntentRegistration(
                                request,
                                callerIdentity,
                                new LocationPendingIntentTransport(mContext, pendingIntent),
                                permissionLevel));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void unregisterLocationRequest(ILocationListener listener) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(listener.asBinder());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void unregisterLocationRequest(PendingIntent pendingIntent) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(pendingIntent);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegister() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mSettingsHelper.addOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.addOnIgnoreSettingsPackageWhitelistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.addListener(mLocationPermissionsListener);
        mAppForegroundHelper.addListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.addListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.addListener(mScreenInteractiveChangedListener);
    }

    @GuardedBy("mLock")
    @Override
    protected void onUnregister() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mSettingsHelper.removeOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.removeOnIgnoreSettingsPackageWhitelistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.removeListener(mLocationPermissionsListener);
        mAppForegroundHelper.removeListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.removeListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.removeListener(mScreenInteractiveChangedListener);
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationAdded(Object key, Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                registration.getRequest(),
                key instanceof PendingIntent,
                key instanceof IBinder,
                /* geofence= */ null,
                registration.isForeground());

        mLocationRequestStatistics.startRequesting(
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                mName,
                registration.getRequest().getInterval(),
                registration.isForeground());
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationRemoved(Object key, Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mLocationRequestStatistics.stopRequesting(
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                mName);

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                registration.getRequest(),
                key instanceof PendingIntent,
                key instanceof IBinder,
                /* geofence= */ null,
                registration.isForeground());
    }

    @GuardedBy("mLock")
    @Override
    protected boolean registerWithService(ProviderRequest mergedRequest) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mProvider.setRequest(mergedRequest);
        return true;
    }

    @GuardedBy("mLock")
    @Override
    protected boolean reregisterWithService(ProviderRequest oldRequest,
            ProviderRequest newRequest) {
        return registerWithService(newRequest);
    }

    @GuardedBy("mLock")
    @Override
    protected void unregisterWithService() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }
        mProvider.setRequest(ProviderRequest.EMPTY_REQUEST);
    }

    @GuardedBy("mLock")
    @Override
    protected boolean isActive(Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        CallerIdentity identity = registration.getIdentity();

        if (!registration.isPermitted()) {
            return false;
        }

        if (!registration.getRequest().isLocationSettingsIgnored()) {
            if (!isEnabled(identity.getUserId())) {
                return false;
            }

            switch (mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
                case LOCATION_MODE_FOREGROUND_ONLY:
                    if (!registration.isForeground()) {
                        return false;
                    }
                    break;
                case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                    if (!GPS_PROVIDER.equals(mName)) {
                        break;
                    }
                    // fall through
                case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                    // fall through
                case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                    if (!mScreenInteractiveHelper.isInteractive()) {
                        return false;
                    }
                    break;
                case LOCATION_MODE_NO_CHANGE:
                    // fall through
                default:
                    break;
            }
        }

        return !mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                identity.getPackageName());
    }

    @GuardedBy("mLock")
    @Override
    protected ProviderRequest mergeRequests(Collection<Registration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        ProviderRequest.Builder providerRequest = new ProviderRequest.Builder();
        // initialize the low power mode to true and set to false if any of the records requires
        providerRequest.setLowPowerMode(true);

        ArrayList<Registration> providerRegistrations = new ArrayList<>(registrations.size());
        for (Registration registration : registrations) {
            LocationRequest locationRequest = registration.getRequest();

            if (locationRequest.isLocationSettingsIgnored()) {
                providerRequest.setLocationSettingsIgnored(true);
            }
            if (!locationRequest.isLowPowerMode()) {
                providerRequest.setLowPowerMode(false);
            }

            providerRequest.setInterval(
                    Math.min(locationRequest.getInterval(), providerRequest.getInterval()));
            providerRegistrations.add(registration);
        }

        // collect contributing location requests
        ArrayList<LocationRequest> providerRequests = new ArrayList<>(providerRegistrations.size());
        final int registrationsSize = providerRegistrations.size();
        for (int i = 0; i < registrationsSize; i++) {
            providerRequests.add(providerRegistrations.get(i).getRequest());
        }

        providerRequest.setLocationRequests(providerRequests);

        // calculate who to blame for power in a somewhat arbitrary fashion. we pick a threshold
        // interval slightly higher that the minimum interval, and spread the blame across all
        // contributing registrations under that threshold.
        long thresholdIntervalMs = (providerRequest.getInterval() + 1000) * 3 / 2;
        if (thresholdIntervalMs < 0) {
            // handle overflow
            thresholdIntervalMs = Long.MAX_VALUE;
        }
        for (int i = 0; i < registrationsSize; i++) {
            LocationRequest request = providerRegistrations.get(i).getRequest();
            if (request.getInterval() <= thresholdIntervalMs) {
                providerRequest.getWorkSource().add(providerRegistrations.get(i).getWorkSource());
            }
        }

        return providerRequest.build();
    }

    private void onUserChanged(int userId, int change) {
        synchronized (mLock) {
            switch (change) {
                case UserListener.CURRENT_USER_CHANGED:
                    onEnabledChanged(userId);
                    break;
                case UserListener.USER_STARTED:
                    onUserStarted(userId);
                    break;
                case UserListener.USER_STOPPED:
                    onUserStopped(userId);
                    break;
            }
        }
    }

    private void onLocationEnabledChanged(int userId) {
        synchronized (mLock) {
            onEnabledChanged(userId);
        }
    }

    private void onScreenInteractiveChanged(boolean screenInteractive) {
        synchronized (mLock) {
            switch (mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
                case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                    if (!GPS_PROVIDER.equals(mName)) {
                        break;
                    }
                    // fall through
                case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                    // fall through
                case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                    updateRegistrations(registration -> true);
                    break;
                default:
                    break;
            }
        }
    }

    private void onBackgroundThrottlePackageWhitelistChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onBackgroundThrottleIntervalChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onLocationPowerSaveModeChanged(@LocationPowerSaveMode int locationPowerSaveMode) {
        synchronized (mLock) {
            // this is rare, just assume everything has changed to keep it simple
            updateRegistrations(registration -> true);
        }
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.onForegroundChanged(uid, foreground));
        }
    }

    private void onIgnoreSettingsWhitelistChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onLocationPackageBlacklistChanged(int userId) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
        }
    }

    private void onLocationPermissionsChanged(String packageName) {
        synchronized (mLock) {
            updateRegistrations(
                    registration -> registration.onLocationPermissionsChanged(packageName));
        }
    }

    private void onLocationPermissionsChanged(int uid) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.onLocationPermissionsChanged(uid));
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onStateChanged(
            AbstractLocationProvider.State oldState, AbstractLocationProvider.State newState) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (oldState.allowed != newState.allowed) {
            onEnabledChanged(UserHandle.USER_ALL);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onReportLocation(Location location) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        // don't validate mock locations
        if (!location.isFromMockProvider()) {
            if (location.getLatitude() == 0 && location.getLongitude() == 0) {
                Log.w(TAG, "blocking 0,0 location from " + mName + " provider");
                return;
            }
        }

        if (!location.isComplete()) {
            Log.w(TAG, "blocking incomplete location from " + mName + " provider");
            return;
        }

        // update last location
        setLastLocation(location, UserHandle.USER_ALL);

        // notify passive provider
        if (mPassiveManager != null) {
            mPassiveManager.updateLocation(location);
        }

        // attempt listener delivery
        deliverToListeners(registration -> {
            return registration.acceptLocationChange(location);
        });
    }

    @GuardedBy("mLock")
    @Override
    public void onReportLocation(List<Location> locations) {
        if (!GPS_PROVIDER.equals(mName)) {
            return;
        }

        mLocationManagerInternal.reportGnssBatchLocations(locations);
    }

    @GuardedBy("mLock")
    private void onUserStarted(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            // clear the user's prior enabled state to prevent broadcast of enabled state change
            mEnabled.clear();
            onEnabledChanged(UserHandle.USER_ALL);
        } else {
            Preconditions.checkArgument(userId >= 0);

            // clear the user's prior enabled state to prevent broadcast of enabled state change
            mEnabled.delete(userId);
            onEnabledChanged(userId);
        }
    }

    @GuardedBy("mLock")
    private void onUserStopped(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            onEnabledChanged(UserHandle.USER_ALL);
            mEnabled.clear();
            mLastLocations.clear();
        } else {
            Preconditions.checkArgument(userId >= 0);

            onEnabledChanged(userId);
            mEnabled.delete(userId);
            mLastLocations.remove(userId);
        }
    }

    @GuardedBy("mLock")
    private void onEnabledChanged(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            // used during initialization - ignore since many lower level operations (checking
            // settings for instance) do not support the null user
            return;
        } else if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                onEnabledChanged(runningUserIds[i]);
            }
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        boolean enabled = mStarted
                && mProvider.getState().allowed
                && mUserInfoHelper.isCurrentUserId(userId)
                && mSettingsHelper.isLocationEnabled(userId);

        int index = mEnabled.indexOfKey(userId);
        Boolean wasEnabled = index < 0 ? null : mEnabled.valueAt(index);
        if (wasEnabled != null && wasEnabled == enabled) {
            return;
        }

        mEnabled.put(userId, enabled);

        if (D) {
            Log.d(TAG, "[u" + userId + "] " + mName + " provider enabled = " + enabled);
        }

        // clear last locations if we become disabled
        if (!enabled) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation != null) {
                lastLocation.clearLocations();
            }
        }

        // do not send change notifications if we just saw this user for the first time
        if (wasEnabled != null) {
            // fused and passive provider never get public updates for legacy reasons
            if (!FUSED_PROVIDER.equals(mName) && !PASSIVE_PROVIDER.equals(mName)) {
                Intent intent = new Intent(LocationManager.PROVIDERS_CHANGED_ACTION)
                        .putExtra(LocationManager.EXTRA_PROVIDER_NAME, mName)
                        .putExtra(LocationManager.EXTRA_PROVIDER_ENABLED, enabled)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            }

            // send updates to internal listeners - since we expect listener changes to be more
            // frequent than enabled changes, we use copy-on-read instead of copy-on-write
            if (!mEnabledListeners.isEmpty()) {
                ProviderEnabledListener[] listeners = mEnabledListeners.toArray(
                        new ProviderEnabledListener[0]);
                FgThread.getHandler().post(() -> {
                    for (int i = 0; i < listeners.length; i++) {
                        listeners[i].onProviderEnabledChanged(mName, userId, enabled);
                    }
                });
            }
        }

        // update active state of affected registrations
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        synchronized (mLock) {
            ipw.print(mName + " provider");
            if (mProvider.isMock()) {
                ipw.print(" [mock]");
            }
            ipw.println(":");
            ipw.increaseIndent();

            super.dump(fd, ipw, args);

            int[] userIds = mUserInfoHelper.getRunningUserIds();
            for (int userId : userIds) {
                if (userIds.length != 1) {
                    ipw.println("user " + userId + ":");
                    ipw.increaseIndent();
                }
                ipw.println(
                        "last location=" + getLastLocationUnsafe(userId, PERMISSION_FINE, false));
                ipw.println("enabled=" + isEnabled(userId));
                if (userIds.length != 1) {
                    ipw.decreaseIndent();
                }
            }
        }

        mProvider.dump(fd, ipw, args);

        ipw.decreaseIndent();
    }

    private static class LastLocation {

        @Nullable private Location mFineLocation;
        @Nullable private Location mCoarseLocation;
        @Nullable private Location mFineBypassLocation;
        @Nullable private Location mCoarseBypassLocation;

        public void clearMock() {
            if (mFineLocation != null && mFineLocation.isFromMockProvider()) {
                mFineLocation = null;
                mCoarseLocation = null;
            }
            if (mFineBypassLocation != null && mFineBypassLocation.isFromMockProvider()) {
                mFineBypassLocation = null;
                mCoarseBypassLocation = null;
            }
        }

        public void clearLocations() {
            mFineLocation = null;
            mCoarseLocation = null;
        }

        @Nullable
        public Location get(@PermissionLevel int permissionLevel, boolean ignoreLocationSettings) {
            switch (permissionLevel) {
                case PERMISSION_FINE:
                    if (ignoreLocationSettings) {
                        return mFineBypassLocation;
                    } else {
                        return mFineLocation;
                    }
                case PERMISSION_COARSE:
                    if (ignoreLocationSettings) {
                        return mCoarseBypassLocation;
                    } else {
                        return mCoarseLocation;
                    }
                default:
                    // shouldn't be possible to have a client added without location permissions
                    throw new AssertionError();
            }
        }

        public void set(Location location, Location coarseLocation) {
            mFineLocation = location;
            mCoarseLocation = calculateNextCoarse(mCoarseLocation, coarseLocation);
        }

        public void setBypass(Location location, Location coarseLocation) {
            mFineBypassLocation = location;
            mCoarseBypassLocation = calculateNextCoarse(mCoarseBypassLocation, coarseLocation);
        }

        private Location calculateNextCoarse(@Nullable Location oldCoarse, Location newCoarse) {
            if (oldCoarse == null) {
                return newCoarse;
            }
            // update last coarse interval only if enough time has passed
            long timeDeltaMs = NANOSECONDS.toMillis(newCoarse.getElapsedRealtimeNanos())
                    - NANOSECONDS.toMillis(oldCoarse.getElapsedRealtimeNanos());
            if (timeDeltaMs > FASTEST_COARSE_INTERVAL_MS) {
                return newCoarse;
            } else {
                return oldCoarse;
            }
        }
    }

    private static class SingleUseCallback extends IRemoteCallback.Stub implements Runnable,
            CancellationSignal.OnCancelListener {

        @Nullable
        public static SingleUseCallback wrap(@Nullable Runnable callback) {
            return callback == null ? null : new SingleUseCallback(callback);
        }

        @GuardedBy("this")
        @Nullable private Runnable mCallback;

        private SingleUseCallback(Runnable callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void sendResult(Bundle data) {
            run();
        }

        @Override
        public void onCancel() {
            run();
        }

        @Override
        public void run() {
            Runnable callback;
            synchronized (this) {
                callback = mCallback;
                mCallback = null;
            }

            // prevent this callback from being run more than once - otherwise this could provide an
            // attack vector for a malicious app to break assumptions on how many times a callback
            // may be invoked, and thus crash system server.
            if (callback == null) {
                return;
            }

            long identity = Binder.clearCallingIdentity();
            try {
                callback.run();
            } catch (RuntimeException e) {
                // since this is within a oneway binder transaction there is nowhere
                // for exceptions to go - move onto another thread to crash system
                // server so we find out about it
                FgThread.getExecutor().execute(() -> {
                    throw new AssertionError(e);
                });
                throw e;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
