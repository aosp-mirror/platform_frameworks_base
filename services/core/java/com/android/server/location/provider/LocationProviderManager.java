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

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;
import static android.app.compat.CompatChanges.isChangeEnabled;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.DELIVER_HISTORICAL_LOCATIONS;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.KEY_FLUSH_COMPLETE;
import static android.location.LocationManager.KEY_LOCATIONS;
import static android.location.LocationManager.KEY_LOCATION_CHANGED;
import static android.location.LocationManager.KEY_PROVIDER_ENABLED;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.os.IPowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerExemptionManager.REASON_LOCATION_PROVIDER;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;
import static android.os.UserHandle.USER_CURRENT;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationPermissions.PERMISSION_NONE;
import static com.android.server.location.eventlog.LocationEventLog.EVENT_LOG;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.AlarmManager.OnAlarmListener;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.LocationResult.BadLocationException;
import android.location.altitude.AltitudeConverter;
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
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
import android.provider.DeviceConfig;
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
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.location.LocationPermissions;
import com.android.server.location.LocationPermissions.PermissionLevel;
import com.android.server.location.fudger.LocationFudger;
import com.android.server.location.injector.AlarmHelper;
import com.android.server.location.injector.AppForegroundHelper;
import com.android.server.location.injector.AppForegroundHelper.AppForegroundListener;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationPermissionsHelper.LocationPermissionsListener;
import com.android.server.location.injector.LocationPowerSaveModeHelper;
import com.android.server.location.injector.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.PackageResetHelper;
import com.android.server.location.injector.ScreenInteractiveHelper;
import com.android.server.location.injector.ScreenInteractiveHelper.ScreenInteractiveChangedListener;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener;
import com.android.server.location.injector.SettingsHelper.UserSettingChangedListener;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.injector.UserInfoHelper.UserListener;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.RemovableListenerRegistration;
import com.android.server.location.settings.LocationSettings;
import com.android.server.location.settings.LocationUserSettings;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Manages all aspects of a single location provider.
 */
public class LocationProviderManager extends
        ListenerMultiplexer<Object, LocationProviderManager.LocationTransport,
                LocationProviderManager.Registration, ProviderRequest> implements
        AbstractLocationProvider.Listener {

    private static final String WAKELOCK_TAG = "*location*";
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 1000;

    // duration PI location clients are put on the allowlist to start a fg service
    private static final long TEMPORARY_APP_ALLOWLIST_DURATION_MS = 10 * 1000;

    // fastest interval at which clients may receive coarse locations
    private static final long MIN_COARSE_INTERVAL_MS = 10 * 60 * 1000;

    // max interval to be considered "high power" request
    private static final long MAX_HIGH_POWER_INTERVAL_MS = 5 * 60 * 1000;

    // max age of a location before it is no longer considered "current"
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 30 * 1000;

    // max timeout allowed for getting the current location
    private static final long MAX_GET_CURRENT_LOCATION_TIMEOUT_MS = 30 * 1000;

    // max jitter allowed for min update interval as a percentage of the interval
    private static final float FASTEST_INTERVAL_JITTER_PERCENTAGE = .10f;

    // max absolute jitter allowed for min update interval evaluation
    private static final int MAX_FASTEST_INTERVAL_JITTER_MS = 30 * 1000;

    // minimum amount of request delay in order to respect the delay, below this value the request
    // will just be scheduled immediately
    private static final long MIN_REQUEST_DELAY_MS = 30 * 1000;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_STARTED, STATE_STOPPING, STATE_STOPPED})
    private @interface State {}

    private static final int STATE_STARTED = 0;
    private static final int STATE_STOPPING = 1;
    private static final int STATE_STOPPED = 2;

    public interface StateChangedListener {
        void onStateChanged(String provider, AbstractLocationProvider.State oldState,
                AbstractLocationProvider.State newState);
    }

    protected interface LocationTransport {

        void deliverOnLocationChanged(LocationResult locationResult,
                @Nullable IRemoteCallback onCompleteCallback) throws Exception;
        void deliverOnFlushComplete(int requestCode) throws Exception;
    }

    protected interface ProviderTransport {

        void deliverOnProviderEnabledChanged(String provider, boolean enabled) throws Exception;
    }

    protected static final class LocationListenerTransport implements LocationTransport,
            ProviderTransport {

        private final ILocationListener mListener;

        LocationListenerTransport(ILocationListener listener) {
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void deliverOnLocationChanged(LocationResult locationResult,
                @Nullable IRemoteCallback onCompleteCallback) throws RemoteException {
            try {
                mListener.onLocationChanged(locationResult.asList(), onCompleteCallback);
            } catch (RuntimeException e) {
                // the only way a runtime exception can be thrown here is if the client is in the
                // system server process (so that the binder call is executed directly, rather than
                // asynchronously in another process), and the client is using a direct executor (so
                // any client exceptions bubble directly back to us). we move any exception onto
                // another thread so that it can't cause further problems
                RuntimeException wrapper = new RuntimeException(e);
                FgThread.getExecutor().execute(() -> {
                    throw wrapper;
                });
            }
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) throws RemoteException {
            try {
                mListener.onFlushComplete(requestCode);
            } catch (RuntimeException e) {
                // the only way a runtime exception can be thrown here is if the client is in the
                // system server process (so that the binder call is executed directly, rather than
                // asynchronously in another process), and the client is using a direct executor (so
                // any client exceptions bubble directly back to us). we move any exception onto
                // another thread so that it can't cause further problems
                RuntimeException wrapper = new RuntimeException(e);
                FgThread.getExecutor().execute(() -> {
                    throw wrapper;
                });
            }
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws RemoteException {
            try {
                mListener.onProviderEnabledChanged(provider, enabled);
            } catch (RuntimeException e) {
                // the only way a runtime exception can be thrown here is if the client is in the
                // system server process (so that the binder call is executed directly, rather than
                // asynchronously in another process), and the client is using a direct executor (so
                // any client exceptions bubble directly back to us). we move any exception onto
                // another thread so that it can't cause further problems
                RuntimeException wrapper = new RuntimeException(e);
                FgThread.getExecutor().execute(() -> {
                    throw wrapper;
                });
            }
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
        public void deliverOnLocationChanged(LocationResult locationResult,
                @Nullable IRemoteCallback onCompleteCallback)
                throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);
            // allows apps to start a fg service in response to a location PI
            options.setTemporaryAppAllowlist(TEMPORARY_APP_ALLOWLIST_DURATION_MS,
                    TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                    REASON_LOCATION_PROVIDER,
                    "");

            Intent intent = new Intent().putExtra(KEY_LOCATION_CHANGED,
                    locationResult.getLastLocation());
            if (locationResult.size() > 1) {
                intent.putExtra(KEY_LOCATIONS, locationResult.asList().toArray(new Location[0]));
            }

            Runnable callback = null;
            if (onCompleteCallback != null) {
                callback = () -> {
                    try {
                        onCompleteCallback.sendResult(null);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                };
            }

            PendingIntentSender.send(mPendingIntent, mContext, intent, callback,
                    options.toBundle());
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);

            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_FLUSH_COMPLETE, requestCode),
                    null, null, null, options.toBundle());
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);

            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_PROVIDER_ENABLED, enabled),
                    null, null, null, options.toBundle());
        }
    }

    protected static final class GetCurrentLocationTransport implements LocationTransport {

        private final ILocationCallback mCallback;

        GetCurrentLocationTransport(ILocationCallback callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void deliverOnLocationChanged(@Nullable LocationResult locationResult,
                @Nullable IRemoteCallback onCompleteCallback)
                throws RemoteException {
            // ILocationCallback doesn't currently support completion callbacks
            Preconditions.checkState(onCompleteCallback == null);

            try {
                if (locationResult != null) {
                    mCallback.onLocation(locationResult.getLastLocation());
                } else {
                    mCallback.onLocation(null);
                }
            } catch (RuntimeException e) {
                // the only way a runtime exception can be thrown here is if the client is in the
                // system server process (so that the binder call is executed directly, rather than
                // asynchronously in another process), and the client is using a direct executor (so
                // any client exceptions bubble directly back to us). we move any exception onto
                // another thread so that it can't cause further problems
                RuntimeException wrapper = new RuntimeException(e);
                FgThread.getExecutor().execute(() -> {
                    throw wrapper;
                });
            }
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) {}
    }

    protected abstract class Registration extends RemovableListenerRegistration<Object,
                LocationTransport> {

        private final LocationRequest mBaseRequest;
        private final CallerIdentity mIdentity;
        private final @PermissionLevel int mPermissionLevel;

        // we cache these values because checking/calculating on the fly is more expensive
        @GuardedBy("mMultiplexerLock")
        private boolean mPermitted;
        @GuardedBy("mMultiplexerLock")
        private boolean mForeground;
        @GuardedBy("mMultiplexerLock")
        private LocationRequest mProviderLocationRequest;
        @GuardedBy("mMultiplexerLock")
        private boolean mIsUsingHighPower;

        @Nullable private Location mLastLocation = null;

        protected Registration(LocationRequest request, CallerIdentity identity, Executor executor,
                LocationTransport transport, @PermissionLevel int permissionLevel) {
            super(executor, transport);

            Preconditions.checkArgument(identity.getListenerId() != null);
            Preconditions.checkArgument(permissionLevel > PERMISSION_NONE);
            Preconditions.checkArgument(!request.getWorkSource().isEmpty());

            mBaseRequest = Objects.requireNonNull(request);
            mIdentity = Objects.requireNonNull(identity);
            mPermissionLevel = permissionLevel;
            mProviderLocationRequest = request;
        }

        public final CallerIdentity getIdentity() {
            return mIdentity;
        }

        public final LocationRequest getRequest() {
            synchronized (mMultiplexerLock) {
                return mProviderLocationRequest;
            }
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onRegister() {
            super.onRegister();

            if (D) {
                Log.d(TAG, mName + " provider added registration from " + getIdentity() + " -> "
                        + getRequest());
            }

            EVENT_LOG.logProviderClientRegistered(mName, getIdentity(), mBaseRequest);

            // initialization order is important as there are ordering dependencies
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().getUid());
            mProviderLocationRequest = calculateProviderLocationRequest();
            mIsUsingHighPower = isUsingHighPower();

            if (mForeground) {
                EVENT_LOG.logProviderClientForeground(mName, getIdentity());
            }
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onUnregister() {
            EVENT_LOG.logProviderClientUnregistered(mName, getIdentity());

            if (D) {
                Log.d(TAG, mName + " provider removed registration from " + getIdentity());
            }

            super.onUnregister();
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onActive() {
            EVENT_LOG.logProviderClientActive(mName, getIdentity());

            if (!getRequest().isHiddenFromAppOps()) {
                mAppOpsHelper.startOpNoThrow(OP_MONITOR_LOCATION, getIdentity());
            }
            onHighPowerUsageChanged();
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onInactive() {
            onHighPowerUsageChanged();
            if (!getRequest().isHiddenFromAppOps()) {
                mAppOpsHelper.finishOp(OP_MONITOR_LOCATION, getIdentity());
            }

            EVENT_LOG.logProviderClientInactive(mName, getIdentity());
        }

        @GuardedBy("mMultiplexerLock")
        final void setLastDeliveredLocation(@Nullable Location location) {
            mLastLocation = location;
        }

        public final Location getLastDeliveredLocation() {
            synchronized (mMultiplexerLock) {
                return mLastLocation;
            }
        }

        public @PermissionLevel int getPermissionLevel() {
            synchronized (mMultiplexerLock) {
                return mPermissionLevel;
            }
        }

        public final boolean isForeground() {
            synchronized (mMultiplexerLock) {
                return mForeground;
            }
        }

        public final boolean isPermitted() {
            synchronized (mMultiplexerLock) {
                return mPermitted;
            }
        }

        public final void flush(int requestCode) {
            // when the flush callback is invoked, we are guaranteed that locations have been
            // queued on our executor, so by running the listener callback on the same executor it
            // should be guaranteed that those locations will be delivered before the flush callback
            mProvider.getController().flush(() -> executeOperation(
                    listener -> listener.deliverOnFlushComplete(requestCode)));
        }

        @Override
        protected final LocationProviderManager getOwner() {
            return LocationProviderManager.this;
        }

        final boolean onProviderPropertiesChanged() {
            synchronized (mMultiplexerLock) {
                onHighPowerUsageChanged();
                return false;
            }
        }

        @GuardedBy("mMultiplexerLock")
        private void onHighPowerUsageChanged() {
            boolean isUsingHighPower = isUsingHighPower();
            if (isUsingHighPower != mIsUsingHighPower) {
                mIsUsingHighPower = isUsingHighPower;

                if (!getRequest().isHiddenFromAppOps()) {
                    if (mIsUsingHighPower) {
                        mAppOpsHelper.startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION, getIdentity());
                    } else {
                        mAppOpsHelper.finishOp(OP_MONITOR_HIGH_POWER_LOCATION, getIdentity());
                    }
                }
            }
        }

        private boolean isUsingHighPower() {
            ProviderProperties properties = getProperties();
            if (properties == null) {
                return false;
            }

            return isActive()
                    && getRequest().getIntervalMillis() < MAX_HIGH_POWER_INTERVAL_MS
                    && properties.getPowerUsage() == ProviderProperties.POWER_USAGE_HIGH;
        }

        final boolean onLocationPermissionsChanged(@Nullable String packageName) {
            synchronized (mMultiplexerLock) {
                if (packageName == null || getIdentity().getPackageName().equals(packageName)) {
                    return onLocationPermissionsChanged();
                }

                return false;
            }
        }

        final boolean onLocationPermissionsChanged(int uid) {
            synchronized (mMultiplexerLock) {
                if (getIdentity().getUid() == uid) {
                    return onLocationPermissionsChanged();
                }

                return false;
            }
        }

        @GuardedBy("mMultiplexerLock")
        private boolean onLocationPermissionsChanged() {
            boolean permitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            if (permitted != mPermitted) {
                if (D) {
                    Log.v(TAG, mName + " provider package " + getIdentity().getPackageName()
                            + " permitted = " + permitted);
                }

                mPermitted = permitted;

                if (mPermitted) {
                    EVENT_LOG.logProviderClientPermitted(mName, getIdentity());
                } else {
                    EVENT_LOG.logProviderClientUnpermitted(mName, getIdentity());
                }

                return true;
            }

            return false;
        }

        final boolean onAdasGnssLocationEnabledChanged(int userId) {
            synchronized (mMultiplexerLock) {
                if (getIdentity().getUserId() == userId) {
                    return onProviderLocationRequestChanged();
                }

                return false;
            }
        }

        final boolean onForegroundChanged(int uid, boolean foreground) {
            synchronized (mMultiplexerLock) {
                if (getIdentity().getUid() == uid && foreground != mForeground) {
                    if (D) {
                        Log.v(TAG, mName + " provider uid " + uid + " foreground = " + foreground);
                    }

                    mForeground = foreground;

                    if (mForeground) {
                        EVENT_LOG.logProviderClientForeground(mName, getIdentity());
                    } else {
                        EVENT_LOG.logProviderClientBackground(mName, getIdentity());
                    }

                    // note that onProviderLocationRequestChanged() is always called
                    return onProviderLocationRequestChanged()
                            || mLocationPowerSaveModeHelper.getLocationPowerSaveMode()
                            == LOCATION_MODE_FOREGROUND_ONLY;
                }

                return false;
            }
        }

        final boolean onProviderLocationRequestChanged() {
            synchronized (mMultiplexerLock) {
                LocationRequest newRequest = calculateProviderLocationRequest();
                if (mProviderLocationRequest.equals(newRequest)) {
                    return false;
                }

                LocationRequest oldRequest = mProviderLocationRequest;
                mProviderLocationRequest = newRequest;
                onHighPowerUsageChanged();
                updateService();

                // if bypass state has changed then the active state may have changed
                return oldRequest.isBypass() != newRequest.isBypass();
            }
        }

        @GuardedBy("mMultiplexerLock")
        private LocationRequest calculateProviderLocationRequest() {
            LocationRequest.Builder builder = new LocationRequest.Builder(mBaseRequest);

            if (mPermissionLevel < PERMISSION_FINE) {
                builder.setQuality(LocationRequest.QUALITY_LOW_POWER);
                if (mBaseRequest.getIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.setIntervalMillis(MIN_COARSE_INTERVAL_MS);
                }
                if (mBaseRequest.getMinUpdateIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.setMinUpdateIntervalMillis(MIN_COARSE_INTERVAL_MS);
                }
            }

            boolean locationSettingsIgnored = mBaseRequest.isLocationSettingsIgnored();
            if (locationSettingsIgnored) {
                // if we are not currently allowed use location settings ignored, disable it
                if (!mSettingsHelper.getIgnoreSettingsAllowlist().contains(
                        getIdentity().getPackageName(), getIdentity().getAttributionTag())
                        && !mLocationManagerInternal.isProvider(null, getIdentity())) {
                    locationSettingsIgnored = false;
                }

                builder.setLocationSettingsIgnored(locationSettingsIgnored);
            }

            boolean adasGnssBypass = mBaseRequest.isAdasGnssBypass();
            if (adasGnssBypass) {
                // if we are not currently allowed use adas gnss bypass, disable it
                if (!GPS_PROVIDER.equals(mName)) {
                    Log.e(TAG, "adas gnss bypass request received in non-gps provider");
                    adasGnssBypass = false;
                } else if (!mUserHelper.isCurrentUserId(getIdentity().getUserId())) {
                    adasGnssBypass = false;
                } else if (!mLocationSettings.getUserSettings(
                        getIdentity().getUserId()).isAdasGnssLocationEnabled()) {
                    adasGnssBypass = false;
                } else if (!mSettingsHelper.getAdasAllowlist().contains(
                        getIdentity().getPackageName(), getIdentity().getAttributionTag())) {
                    adasGnssBypass = false;
                }

                builder.setAdasGnssBypass(adasGnssBypass);
            }

            if (!locationSettingsIgnored && !isThrottlingExempt()) {
                // throttle in the background
                if (!mForeground) {
                    builder.setIntervalMillis(max(mBaseRequest.getIntervalMillis(),
                            mSettingsHelper.getBackgroundThrottleIntervalMs()));
                }
            }

            return builder.build();
        }

        private boolean isThrottlingExempt() {
            if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                    getIdentity().getPackageName())) {
                return true;
            }

            return mLocationManagerInternal.isProvider(null, getIdentity());
        }

        @Nullable abstract ListenerOperation<LocationTransport> acceptLocationChange(
                LocationResult fineLocationResult);

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
            OnAlarmListener, ProviderEnabledListener {

        final PowerManager.WakeLock mWakeLock;

        // b/206340085 - if we allocate a new wakelock releaser object for every delivery we
        // increase the risk of resource starvation. if a client stops processing deliveries the
        // system server binder allocation pool will be starved as we continue to queue up
        // deliveries, each with a new allocation. in order to mitigate this, we use a single
        // releaser object per registration rather than per delivery.
        final ExternalWakeLockReleaser mWakeLockReleaser;

        private volatile ProviderTransport mProviderTransport;

        @GuardedBy("mMultiplexerLock")
        private int mNumLocationsDelivered = 0;
        @GuardedBy("mMultiplexerLock")
        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected <TTransport extends LocationTransport & ProviderTransport> LocationRegistration(
                LocationRequest request,
                CallerIdentity identity,
                Executor executor,
                TTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, executor, transport, permissionLevel);
            mProviderTransport = transport;
            mWakeLock = Objects.requireNonNull(mContext.getSystemService(PowerManager.class))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            mWakeLock.setReferenceCounted(true);
            mWakeLock.setWorkSource(request.getWorkSource());
            mWakeLockReleaser = new ExternalWakeLockReleaser(identity, mWakeLock);
        }

        @Override
        protected void onListenerUnregister() {
            mProviderTransport = null;
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onRegister() {
            super.onRegister();

            long registerTimeMs = SystemClock.elapsedRealtime();
            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);

            // add alarm for expiration
            if (mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                // Set WorkSource to null in order to ensure the alarm wakes up the device even when
                // it is idle. Do this when the cost of waking up the device is less than the power
                // cost of not performing the actions set off by the alarm, such as unregistering a
                // location request.
                mAlarmHelper.setDelayedAlarm(mExpirationRealtimeMs - registerTimeMs, this,
                        null);
            }

            // start listening for provider enabled/disabled events
            addEnabledListener(this);

            // if the provider is currently disabled, let the client know immediately
            int userId = getIdentity().getUserId();
            if (!isEnabled(userId)) {
                onProviderEnabledChanged(mName, userId, false);
            }
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onUnregister() {
            // stop listening for provider enabled/disabled events
            removeEnabledListener(this);

            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.cancel(this);
            }

            super.onUnregister();
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onActive() {
            super.onActive();

            // a new registration may not get a location immediately, the provider request may be
            // delayed. therefore we deliver a historical location if available. since delivering an
            // older location could be considered a breaking change for some applications, we only
            // do so for apps targeting S+.
            if (isChangeEnabled(DELIVER_HISTORICAL_LOCATIONS, getIdentity().getUid())) {
                long maxLocationAgeMs = getRequest().getIntervalMillis();
                Location lastDeliveredLocation = getLastDeliveredLocation();
                if (lastDeliveredLocation != null) {
                    // ensure that location is fresher than the last delivered location
                    maxLocationAgeMs = min(maxLocationAgeMs,
                            lastDeliveredLocation.getElapsedRealtimeAgeMillis() - 1);
                }

                // requests are never delayed less than MIN_REQUEST_DELAY_MS, so it only makes sense
                // to deliver historical locations to clients with a last location older than that
                if (maxLocationAgeMs > MIN_REQUEST_DELAY_MS) {
                    Location lastLocation = getLastLocationUnsafe(
                            getIdentity().getUserId(),
                            getPermissionLevel(),
                            getRequest().isBypass(),
                            maxLocationAgeMs);
                    if (lastLocation != null) {
                        executeOperation(acceptLocationChange(LocationResult.wrap(lastLocation)));
                    }
                }
            }
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, mName + " provider registration " + getIdentity()
                        + " expired at " + TimeUtils.formatRealtime(mExpirationRealtimeMs));
            }

            synchronized (mMultiplexerLock) {
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
                remove();
            }
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        @Nullable ListenerOperation<LocationTransport> acceptLocationChange(
                LocationResult fineLocationResult) {
            // check expiration time - alarm is not guaranteed to go off at the right time,
            // especially for short intervals
            if (SystemClock.elapsedRealtime() >= mExpirationRealtimeMs) {
                if (D) {
                    Log.d(TAG, mName + " provider registration " + getIdentity()
                            + " expired at " + TimeUtils.formatRealtime(mExpirationRealtimeMs));
                }
                remove();
                return null;
            }

            LocationResult permittedLocationResult = Objects.requireNonNull(
                    getPermittedLocationResult(fineLocationResult, getPermissionLevel()));

            LocationResult locationResult = permittedLocationResult.filter(
                    new Predicate<Location>() {
                        private Location mPreviousLocation = getLastDeliveredLocation();

                        @Override
                        public boolean test(Location location) {
                            if (Double.isNaN(location.getLatitude()) || location.getLatitude() < -90
                                    || location.getLatitude() > 90
                                    || Double.isNaN(location.getLongitude())
                                    || location.getLongitude() < -180
                                    || location.getLongitude() > 180) {
                                Log.e(TAG, mName + " provider registration " + getIdentity()
                                        + " dropped delivery - invalid latitude or longitude.");
                                return false;
                            }
                            if (mPreviousLocation != null) {
                                // check fastest interval
                                long deltaMs = location.getElapsedRealtimeMillis()
                                        - mPreviousLocation.getElapsedRealtimeMillis();
                                long maxJitterMs = min((long) (FASTEST_INTERVAL_JITTER_PERCENTAGE
                                                * getRequest().getIntervalMillis()),
                                        MAX_FASTEST_INTERVAL_JITTER_MS);
                                if (deltaMs
                                        < getRequest().getMinUpdateIntervalMillis() - maxJitterMs) {
                                    if (D) {
                                        Log.v(TAG, mName + " provider registration " + getIdentity()
                                                + " dropped delivery - too fast (deltaMs="
                                                + deltaMs + ").");
                                    }
                                    return false;
                                }

                                // check smallest displacement
                                double smallestDisplacementM =
                                        getRequest().getMinUpdateDistanceMeters();
                                if (smallestDisplacementM > 0.0 && location.distanceTo(
                                        mPreviousLocation)
                                        <= smallestDisplacementM) {
                                    if (D) {
                                        Log.v(TAG, mName + " provider registration " + getIdentity()
                                                + " dropped delivery - too close");
                                    }
                                    return false;
                                }
                            }

                            mPreviousLocation = location;
                            return true;
                        }
                    });

            if (locationResult == null) {
                return null;
            }

            // note app ops
            if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(getPermissionLevel()),
                    getIdentity())) {
                if (D) {
                    Log.w(TAG,
                            mName + " provider registration " + getIdentity() + " noteOp denied");
                }
                return null;
            }

            // acquire a wakelock for non-passive requests
            boolean useWakeLock =
                    getRequest().getIntervalMillis() != LocationRequest.PASSIVE_INTERVAL;

            // deliver location
            return new ListenerOperation<LocationTransport>() {

                @Override
                public void onPreExecute() {
                    // update last delivered location
                    setLastDeliveredLocation(locationResult.getLastLocation());

                    if (useWakeLock) {
                        mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    }
                }

                @Override
                public void operate(LocationTransport listener) throws Exception {
                    // if delivering to the same process, make a copy of the location first (since
                    // location is mutable)
                    LocationResult deliverLocationResult;
                    if (getIdentity().getPid() == Process.myPid()) {
                        deliverLocationResult = locationResult.deepCopy();
                    } else {
                        deliverLocationResult = locationResult;
                    }

                    listener.deliverOnLocationChanged(deliverLocationResult,
                            useWakeLock ? mWakeLockReleaser : null);
                    EVENT_LOG.logProviderDeliveredLocations(mName, locationResult.size(),
                            getIdentity());
                }

                @Override
                public void onPostExecute(boolean success) {
                    if (!success && useWakeLock) {
                        mWakeLock.release();
                    }

                    if (success) {
                        // check num updates - if successful then this function will always be run
                        // from the same thread, and no additional synchronization is necessary
                        boolean remove = ++mNumLocationsDelivered >= getRequest().getMaxUpdates();
                        if (remove) {
                            if (D) {
                                Log.d(TAG, mName + " provider registration " + getIdentity()
                                        + " finished after " + mNumLocationsDelivered + " updates");
                            }

                            remove();
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
                    listener -> listener.deliverOnProviderEnabledChanged(mName, enabled),
                    this::onProviderOperationFailure);
        }

        protected abstract void onProviderOperationFailure(
                ListenerOperation<ProviderTransport> operation, Exception exception);
    }

    protected final class LocationListenerRegistration extends LocationRegistration implements
            IBinder.DeathRecipient {

        LocationListenerRegistration(LocationRequest request, CallerIdentity identity,
                LocationListenerTransport transport, @PermissionLevel int permissionLevel) {
            super(request, identity,
                    identity.isMyProcess() ? FgThread.getExecutor() : DIRECT_EXECUTOR, transport,
                    permissionLevel);
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onRegister() {
            super.onRegister();

            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onUnregister() {
            try {
                ((IBinder) getKey()).unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // the only way this exception can occur should be if another exception has been
                // thrown prior to registration completing, and that exception is currently
                // unwinding the call stack and causing this cleanup. since that exception should
                // crash us anyways, drop this exception so we're not hiding the original exception.
                Log.w(getTag(), "failed to unregister binder death listener", e);
            }

            super.onUnregister();
        }

        @Override
        protected void onProviderOperationFailure(ListenerOperation<ProviderTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        @Override
        public void onOperationFailure(ListenerOperation<LocationTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception e) {
            if (e instanceof RemoteException) {
                Log.w(TAG, mName + " provider registration " + getIdentity() + " removed", e);
                remove();
            } else {
                throw new AssertionError(e);
            }
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider registration " + getIdentity() + " died");
                }

                remove();
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final class LocationPendingIntentRegistration extends LocationRegistration implements
            PendingIntent.CancelListener {

        LocationPendingIntentRegistration(LocationRequest request,
                CallerIdentity identity, LocationPendingIntentTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, DIRECT_EXECUTOR, transport, permissionLevel);
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onRegister() {
            super.onRegister();
            if (!((PendingIntent) getKey()).addCancelListener(DIRECT_EXECUTOR, this)) {
                remove();
            }
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onUnregister() {
            ((PendingIntent) getKey()).removeCancelListener(this);
            super.onUnregister();
        }

        @Override
        protected void onProviderOperationFailure(ListenerOperation<ProviderTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        @Override
        public void onOperationFailure(ListenerOperation<LocationTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception e) {
            if (e instanceof PendingIntent.CanceledException) {
                Log.w(TAG, mName + " provider registration " + getIdentity() + " removed", e);
                remove();
            } else {
                throw new AssertionError(e);
            }
        }

        @Override
        public void onCanceled(PendingIntent intent) {
            if (D) {
                Log.d(TAG, mName + " provider registration " + getIdentity() + " canceled");
            }

            remove();
        }
    }

    protected final class GetCurrentLocationListenerRegistration extends Registration implements
            IBinder.DeathRecipient, OnAlarmListener {

        @GuardedBy("mMultiplexerLock")
        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        GetCurrentLocationListenerRegistration(LocationRequest request,
                CallerIdentity identity, LocationTransport transport, int permissionLevel) {
            super(request,
                    identity,
                    identity.isMyProcess() ? FgThread.getExecutor() : DIRECT_EXECUTOR,
                    transport,
                    permissionLevel);
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onRegister() {
            super.onRegister();

            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }

            long registerTimeMs = SystemClock.elapsedRealtime();
            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);

            // add alarm for expiration
            if (mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                // Set WorkSource to null in order to ensure the alarm wakes up the device even when
                // it is idle. Do this when the cost of waking up the device is less than the power
                // cost of not performing the actions set off by the alarm, such as unregistering a
                // location request.
                mAlarmHelper.setDelayedAlarm(mExpirationRealtimeMs - registerTimeMs, this,
                        null);
            }
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onUnregister() {
            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.cancel(this);
            }

            try {
                ((IBinder) getKey()).unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // the only way this exception can occur should be if another exception has been
                // thrown prior to registration completing, and that exception is currently
                // unwinding the call stack and causing this cleanup. since that exception should
                // crash us anyways, drop this exception so we're not hiding the original exception.
                Log.w(getTag(), "failed to unregister binder death listener", e);
            }

            super.onUnregister();
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onActive() {
            super.onActive();

            Location lastLocation = getLastLocationUnsafe(
                    getIdentity().getUserId(),
                    getPermissionLevel(),
                    getRequest().isBypass(),
                    MAX_CURRENT_LOCATION_AGE_MS);
            if (lastLocation != null) {
                executeOperation(acceptLocationChange(LocationResult.wrap(lastLocation)));
            }
        }

        // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
        @SuppressWarnings("GuardedBy")
        @GuardedBy("mMultiplexerLock")
        @Override
        protected void onInactive() {
            // if we go inactive for any reason, fail immediately
            executeOperation(acceptLocationChange(null));
            super.onInactive();
        }

        @GuardedBy("mMultiplexerLock")
        void deliverNull() {
            executeOperation(acceptLocationChange(null));
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, mName + " provider registration " + getIdentity()
                        + " expired at " + TimeUtils.formatRealtime(mExpirationRealtimeMs));
            }

            synchronized (mMultiplexerLock) {
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
                executeOperation(acceptLocationChange(null));
            }
        }

        @GuardedBy("mMultiplexerLock")
        @Override
        @Nullable ListenerOperation<LocationTransport> acceptLocationChange(
                @Nullable LocationResult fineLocationResult) {
            // check expiration time - alarm is not guaranteed to go off at the right time,
            // especially for short intervals
            if (SystemClock.elapsedRealtime() >= mExpirationRealtimeMs) {
                if (D) {
                    Log.d(TAG, mName + " provider registration " + getIdentity()
                            + " expired at " + TimeUtils.formatRealtime(mExpirationRealtimeMs));
                }
                fineLocationResult = null;
            }

            // lastly - note app ops
            if (fineLocationResult != null && !mAppOpsHelper.noteOpNoThrow(
                    LocationPermissions.asAppOp(getPermissionLevel()), getIdentity())) {
                if (D) {
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                fineLocationResult = null;
            }

            if (fineLocationResult != null) {
                fineLocationResult = fineLocationResult.asLastLocationResult();
            }

            LocationResult locationResult = getPermittedLocationResult(fineLocationResult,
                    getPermissionLevel());

            // deliver location
            return new ListenerOperation<LocationTransport>() {
                @Override
                public void operate(LocationTransport listener) throws Exception {
                    // if delivering to the same process, make a copy of the location first (since
                    // location is mutable)
                    LocationResult deliverLocationResult;
                    if (getIdentity().getPid() == Process.myPid() && locationResult != null) {
                        deliverLocationResult = locationResult.deepCopy();
                    } else {
                        deliverLocationResult = locationResult;
                    }

                    // we currently don't hold a wakelock for getCurrentLocation deliveries
                    listener.deliverOnLocationChanged(deliverLocationResult, null);
                    EVENT_LOG.logProviderDeliveredLocations(mName,
                            locationResult != null ? locationResult.size() : 0, getIdentity());
                }

                @Override
                public void onPostExecute(boolean success) {
                    // on failure we're automatically removed anyways, no need to attempt removal
                    // again
                    if (success) {
                        remove();
                    }
                }
            };
        }

        @Override
        public void onOperationFailure(ListenerOperation<LocationTransport> operation,
                Exception e) {
            if (e instanceof RemoteException) {
                Log.w(TAG, mName + " provider registration " + getIdentity() + " removed", e);
                remove();
            } else {
                throw new AssertionError(e);
            }
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider registration " + getIdentity() + " died");
                }

                remove();
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final String mName;
    @Nullable private final PassiveLocationProviderManager mPassiveManager;

    protected final Context mContext;

    @GuardedBy("mMultiplexerLock")
    private @State int mState;

    // maps of user id to value
    @GuardedBy("mMultiplexerLock")
    private final SparseBooleanArray mEnabled; // null or not present means unknown
    @GuardedBy("mMultiplexerLock")
    private final SparseArray<LastLocation> mLastLocations;

    @GuardedBy("mMultiplexerLock")
    private final ArrayList<ProviderEnabledListener> mEnabledListeners;

    // Extra permissions required to use this provider (on top of the usual location permissions).
    // Not guarded because it's read only.
    private final Collection<String> mRequiredPermissions;

    private final CopyOnWriteArrayList<IProviderRequestListener> mProviderRequestListeners;

    protected final LocationManagerInternal mLocationManagerInternal;
    protected final LocationSettings mLocationSettings;
    protected final SettingsHelper mSettingsHelper;
    protected final UserInfoHelper mUserHelper;
    protected final AlarmHelper mAlarmHelper;
    protected final AppOpsHelper mAppOpsHelper;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final LocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    protected final ScreenInteractiveHelper mScreenInteractiveHelper;
    protected final LocationUsageLogger mLocationUsageLogger;
    protected final LocationFudger mLocationFudger;
    private final PackageResetHelper mPackageResetHelper;

    private final UserListener mUserChangedListener = this::onUserChanged;
    private final LocationSettings.LocationUserSettingsListener mLocationUserSettingsListener =
            this::onLocationUserSettingsChanged;
    private final UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final GlobalSettingChangedListener mBackgroundThrottlePackageWhitelistChangedListener =
            this::onBackgroundThrottlePackageWhitelistChanged;
    private final UserSettingChangedListener mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final LocationPermissionsListener mLocationPermissionsListener =
            new LocationPermissionsListener() {
                @Override
                public void onLocationPermissionsChanged(@Nullable String packageName) {
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
    private final GlobalSettingChangedListener mAdasPackageAllowlistChangedListener =
            this::onAdasAllowlistChanged;
    private final GlobalSettingChangedListener mIgnoreSettingsPackageWhitelistChangedListener =
            this::onIgnoreSettingsWhitelistChanged;
    private final LocationPowerSaveModeChangedListener mLocationPowerSaveModeChangedListener =
            this::onLocationPowerSaveModeChanged;
    private final ScreenInteractiveChangedListener mScreenInteractiveChangedListener =
            this::onScreenInteractiveChanged;
    private final PackageResetHelper.Responder mPackageResetResponder =
            new PackageResetHelper.Responder() {
                @Override
                public void onPackageReset(String packageName) {
                    LocationProviderManager.this.onPackageReset(packageName);
                }

                @Override
                public boolean isResetableForPackage(String packageName) {
                    return LocationProviderManager.this.isResetableForPackage(packageName);
                }
            };

    // acquiring mMultiplexerLock makes operations on mProvider atomic, but is otherwise unnecessary
    protected final MockableLocationProvider mProvider;

    @GuardedBy("mMultiplexerLock")
    @Nullable private OnAlarmListener mDelayedRegister;

    @GuardedBy("mMultiplexerLock")
    @Nullable private StateChangedListener mStateChangedListener;

    /** Enables missing MSL altitudes to be added on behalf of the provider. */
    private final AltitudeConverter mAltitudeConverter = new AltitudeConverter();
    private volatile boolean mIsAltitudeConverterIdle = true;

    public LocationProviderManager(Context context, Injector injector,
            String name, @Nullable PassiveLocationProviderManager passiveManager) {
        this(context, injector, name, passiveManager, Collections.emptyList());
    }

    /**
     * Creates a manager for a location provider (the two have a 1:1 correspondence).
     *
     * @param context Context in which the manager is running.
     * @param injector Injector to retrieve system components (useful to override in testing)
     * @param name Name of this provider (used in LocationManager APIs by client apps).
     * @param passiveManager The "passive" manager (special case provider that returns locations
     *     from all other providers).
     * @param requiredPermissions Required permissions for accessing this provider. All of the given
     *     permissions are required to access the provider. If a caller doesn't hold the correct
     *     permission, the provider will be invisible to it.
     */
    public LocationProviderManager(
            Context context,
            Injector injector,
            String name,
            @Nullable PassiveLocationProviderManager passiveManager,
            Collection<String> requiredPermissions) {
        mContext = context;
        mName = Objects.requireNonNull(name);
        mPassiveManager = passiveManager;
        mState = STATE_STOPPED;
        mEnabled = new SparseBooleanArray(2);
        mLastLocations = new SparseArray<>(2);
        mRequiredPermissions = requiredPermissions;

        mEnabledListeners = new ArrayList<>();
        mProviderRequestListeners = new CopyOnWriteArrayList<>();

        mLocationManagerInternal = Objects.requireNonNull(
                LocalServices.getService(LocationManagerInternal.class));
        mLocationSettings = injector.getLocationSettings();
        mSettingsHelper = injector.getSettingsHelper();
        mUserHelper = injector.getUserInfoHelper();
        mAlarmHelper = injector.getAlarmHelper();
        mAppOpsHelper = injector.getAppOpsHelper();
        mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        mAppForegroundHelper = injector.getAppForegroundHelper();
        mLocationPowerSaveModeHelper = injector.getLocationPowerSaveModeHelper();
        mScreenInteractiveHelper = injector.getScreenInteractiveHelper();
        mLocationUsageLogger = injector.getLocationUsageLogger();
        mLocationFudger = new LocationFudger(mSettingsHelper.getCoarseLocationAccuracyM());
        mPackageResetHelper = injector.getPackageResetHelper();

        mProvider = new MockableLocationProvider(mMultiplexerLock);

        // set listener last, since this lets our reference escape
        mProvider.getController().setListener(this);
    }

    public void startManager(@Nullable StateChangedListener listener) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState == STATE_STOPPED);
            mState = STATE_STARTED;
            mStateChangedListener = listener;

            mUserHelper.addListener(mUserChangedListener);
            mLocationSettings.registerLocationUserSettingsListener(mLocationUserSettingsListener);
            mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            final long identity = Binder.clearCallingIdentity();
            try {
                mProvider.getController().start();
                onUserStarted(UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void stopManager() {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState == STATE_STARTED);
            mState = STATE_STOPPING;

            final long identity = Binder.clearCallingIdentity();
            try {
                onEnabledChanged(UserHandle.USER_ALL);
                removeRegistrationIf(key -> true);
                mProvider.getController().stop();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            mUserHelper.removeListener(mUserChangedListener);
            mLocationSettings.unregisterLocationUserSettingsListener(mLocationUserSettingsListener);
            mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            // if external entities are registering listeners it's their responsibility to
            // unregister them before stopManager() is called
            Preconditions.checkState(mEnabledListeners.isEmpty());
            mProviderRequestListeners.clear();

            mEnabled.clear();
            mLastLocations.clear();
            mStateChangedListener = null;
            mState = STATE_STOPPED;
        }
    }

    public String getName() {
        return mName;
    }

    public AbstractLocationProvider.State getState() {
        return mProvider.getState();
    }

    @Nullable public CallerIdentity getProviderIdentity() {
        return mProvider.getState().identity;
    }

    @Nullable public ProviderProperties getProperties() {
        return mProvider.getState().properties;
    }

    public boolean hasProvider() {
        return mProvider.getProvider() != null;
    }

    public boolean isEnabled(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return false;
        } else if (userId == USER_CURRENT) {
            return isEnabled(mUserHelper.getCurrentUserId());
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mMultiplexerLock) {
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

    /**
     * Returns true if this provider is visible to the current caller (whether called from a binder
     * thread or not). If a provider isn't visible, then all APIs return the same data they would if
     * the provider didn't exist (i.e. the caller can't see or use the provider).
     *
     * <p>This method doesn't require any permissions, but uses permissions to determine which
     * subset of providers are visible.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean isVisibleToCaller() {
        // Anything sharing the system's UID can view all providers
        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            return true;
        }

        // If an app mocked this provider, anybody can access it (the goal is
        // to behave as if this provider didn't naturally exist).
        if (mProvider.isMock()) {
            return true;
        }

        for (String permission : mRequiredPermissions) {
            if (mContext.checkCallingOrSelfPermission(permission) != PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void addEnabledListener(ProviderEnabledListener listener) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            mEnabledListeners.add(listener);
        }
    }

    public void removeEnabledListener(ProviderEnabledListener listener) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            mEnabledListeners.remove(listener);
        }
    }

    /** Add a {@link IProviderRequestListener}. */
    public void addProviderRequestListener(IProviderRequestListener listener) {
        mProviderRequestListeners.add(listener);
    }

    /** Remove a {@link IProviderRequestListener}. */
    public void removeProviderRequestListener(IProviderRequestListener listener) {
        mProviderRequestListeners.remove(listener);
    }

    public void setRealProvider(@Nullable AbstractLocationProvider provider) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);

            final long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setRealProvider(provider);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProvider(@Nullable MockLocationProvider provider) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);

            EVENT_LOG.logProviderMocked(mName, provider != null);

            final long identity = Binder.clearCallingIdentity();
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
        synchronized (mMultiplexerLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProviderAllowed(enabled);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProviderLocation(Location location) {
        synchronized (mMultiplexerLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            String locationProvider = location.getProvider();
            if (!TextUtils.isEmpty(locationProvider) && !mName.equals(locationProvider)) {
                // The location has an explicit provider that is different from the mock
                // provider name. The caller may be trying to fool us via b/33091107.
                EventLog.writeEvent(0x534e4554, "33091107", Binder.getCallingUid(),
                        mName + "!=" + locationProvider);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProviderLocation(location);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Nullable public Location getLastLocation(LastLocationRequest request,
            CallerIdentity identity, @PermissionLevel int permissionLevel) {
        request = calculateLastLocationRequest(request, identity);

        if (!isActive(request.isBypass(), identity)) {
            return null;
        }

        Location location = getPermittedLocation(
                getLastLocationUnsafe(
                        identity.getUserId(),
                        permissionLevel,
                        request.isBypass(),
                        Long.MAX_VALUE),
                permissionLevel);

        if (location != null) {
            // lastly - note app ops
            if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(permissionLevel),
                    identity)) {
                return null;
            }

            // if delivering to the same process, make a copy of the location first (since
            // location is mutable)
            if (identity.getPid() == Process.myPid()) {
                location = new Location(location);
            }
        }

        return location;
    }

    private LastLocationRequest calculateLastLocationRequest(LastLocationRequest baseRequest,
            CallerIdentity identity) {
        LastLocationRequest.Builder builder = new LastLocationRequest.Builder(baseRequest);

        boolean locationSettingsIgnored = baseRequest.isLocationSettingsIgnored();
        if (locationSettingsIgnored) {
            // if we are not currently allowed use location settings ignored, disable it
            if (!mSettingsHelper.getIgnoreSettingsAllowlist().contains(
                    identity.getPackageName(), identity.getAttributionTag())
                    && !mLocationManagerInternal.isProvider(null, identity)) {
                locationSettingsIgnored = false;
            }

            builder.setLocationSettingsIgnored(locationSettingsIgnored);
        }

        boolean adasGnssBypass = baseRequest.isAdasGnssBypass();
        if (adasGnssBypass) {
            // if we are not currently allowed use adas gnss bypass, disable it
            if (!GPS_PROVIDER.equals(mName)) {
                Log.e(TAG, "adas gnss bypass request received in non-gps provider");
                adasGnssBypass = false;
            } else if (!mUserHelper.isCurrentUserId(identity.getUserId())) {
                adasGnssBypass = false;
            } else if (!mLocationSettings.getUserSettings(
                    identity.getUserId()).isAdasGnssLocationEnabled()) {
                adasGnssBypass = false;
            } else if (!mSettingsHelper.getAdasAllowlist().contains(
                    identity.getPackageName(), identity.getAttributionTag())) {
                adasGnssBypass = false;
            }

            builder.setAdasGnssBypass(adasGnssBypass);
        }

        return builder.build();
    }

    /**
     * This function does not perform any permissions or safety checks, by calling it you are
     * committing to performing all applicable checks yourself. This always returns a "fine"
     * location, even if the permissionLevel is coarse. You are responsible for coarsening the
     * location if necessary.
     */
    @Nullable public Location getLastLocationUnsafe(int userId,
            @PermissionLevel int permissionLevel, boolean isBypass,
            long maximumAgeMs) {
        if (userId == UserHandle.USER_ALL) {
            // find the most recent location across all users
            Location lastLocation = null;
            final int[] runningUserIds = mUserHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                Location next = getLastLocationUnsafe(runningUserIds[i], permissionLevel,
                        isBypass, maximumAgeMs);
                if (lastLocation == null || (next != null && next.getElapsedRealtimeNanos()
                        > lastLocation.getElapsedRealtimeNanos())) {
                    lastLocation = next;
                }
            }
            return lastLocation;
        } else if (userId == USER_CURRENT) {
            return getLastLocationUnsafe(mUserHelper.getCurrentUserId(), permissionLevel,
                    isBypass, maximumAgeMs);
        }

        Preconditions.checkArgument(userId >= 0);

        Location location;
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                location = null;
            } else {
                location = lastLocation.get(permissionLevel, isBypass);
            }
        }

        if (location == null) {
            return null;
        }

        if (location.getElapsedRealtimeAgeMillis() > maximumAgeMs) {
            return null;
        }

        return location;
    }

    public void injectLastLocation(Location location, int userId) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            if (getLastLocationUnsafe(userId, PERMISSION_FINE, false, Long.MAX_VALUE) == null) {
                setLastLocation(location, userId);
            }
        }
    }

    private void setLastLocation(Location location, int userId) {
        if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                setLastLocation(location, runningUserIds[i]);
            }
            return;
        } else if (userId == USER_CURRENT) {
            setLastLocation(location, mUserHelper.getCurrentUserId());
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mMultiplexerLock) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                lastLocation = new LastLocation();
                mLastLocations.put(userId, lastLocation);
            }

            if (isEnabled(userId)) {
                lastLocation.set(location);
            }
            lastLocation.setBypass(location);
        }
    }

    @Nullable public ICancellationSignal getCurrentLocation(LocationRequest request,
            CallerIdentity identity, int permissionLevel, ILocationCallback callback) {
        if (request.getDurationMillis() > MAX_GET_CURRENT_LOCATION_TIMEOUT_MS) {
            request = new LocationRequest.Builder(request)
                    .setDurationMillis(MAX_GET_CURRENT_LOCATION_TIMEOUT_MS)
                    .build();
        }

        GetCurrentLocationListenerRegistration registration =
                new GetCurrentLocationListenerRegistration(
                        request,
                        identity,
                        new GetCurrentLocationTransport(callback),
                        permissionLevel);

        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long ident = Binder.clearCallingIdentity();
            try {
                putRegistration(callback.asBinder(), registration);
                if (!registration.isActive()) {
                    // if the registration never activated, fail it immediately
                    registration.deliverNull();
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal.fromTransport(cancelTransport)
                .setOnCancelListener(
                        () -> {
                            final long ident = Binder.clearCallingIdentity();
                            try {
                                removeRegistration(callback.asBinder(), registration);
                            } catch (RuntimeException e) {
                                // since this is within a oneway binder transaction there is nowhere
                                // for exceptions to go - move onto another thread to crash system
                                // server so we find out about it
                                FgThread.getExecutor().execute(() -> {
                                    throw new AssertionError(e);
                                });
                                throw e;
                            } finally {
                                Binder.restoreCallingIdentity(ident);
                            }

                        });
        return cancelTransport;
    }

    public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mProvider.getController().sendExtraCommand(uid, pid, command, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity identity,
            @PermissionLevel int permissionLevel, ILocationListener listener) {
        LocationListenerRegistration registration = new LocationListenerRegistration(
                request,
                identity,
                new LocationListenerTransport(listener),
                permissionLevel);

        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long ident = Binder.clearCallingIdentity();
            try {
                putRegistration(listener.asBinder(), registration);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel, PendingIntent pendingIntent) {
        LocationPendingIntentRegistration registration = new LocationPendingIntentRegistration(
                request,
                callerIdentity,
                new LocationPendingIntentTransport(mContext, pendingIntent),
                permissionLevel);

        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long identity = Binder.clearCallingIdentity();
            try {
                putRegistration(pendingIntent, registration);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void flush(ILocationListener listener, int requestCode) {
        final long identity = Binder.clearCallingIdentity();
        try {
            boolean flushed = updateRegistration(listener.asBinder(), registration -> {
                registration.flush(requestCode);
                return false;
            });
            if (!flushed) {
                throw new IllegalArgumentException("unregistered listener cannot be flushed");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void flush(PendingIntent pendingIntent, int requestCode) {
        final long identity = Binder.clearCallingIdentity();
        try {
            boolean flushed = updateRegistration(pendingIntent, registration -> {
                registration.flush(requestCode);
                return false;
            });
            if (!flushed) {
                throw new IllegalArgumentException(
                        "unregistered pending intent cannot be flushed");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void unregisterLocationRequest(ILocationListener listener) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(listener.asBinder());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void unregisterLocationRequest(PendingIntent pendingIntent) {
        synchronized (mMultiplexerLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(pendingIntent);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected void onRegister() {
        mSettingsHelper.addOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.addAdasAllowlistChangedListener(
                mAdasPackageAllowlistChangedListener);
        mSettingsHelper.addIgnoreSettingsAllowlistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.addListener(mLocationPermissionsListener);
        mAppForegroundHelper.addListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.addListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.addListener(mScreenInteractiveChangedListener);
        mPackageResetHelper.register(mPackageResetResponder);
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected void onUnregister() {
        mSettingsHelper.removeOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.removeAdasAllowlistChangedListener(mAdasPackageAllowlistChangedListener);
        mSettingsHelper.removeIgnoreSettingsAllowlistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.removeListener(mLocationPermissionsListener);
        mAppForegroundHelper.removeListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.removeListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.removeListener(mScreenInteractiveChangedListener);
        mPackageResetHelper.unregister(mPackageResetResponder);
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected void onRegistrationAdded(Object key, Registration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                mName,
                registration.getRequest(),
                key instanceof PendingIntent,
                /* geofence= */ key instanceof IBinder,
                null, registration.isForeground());
    }

    // TODO: remove suppression when GuardedBy analysis can recognize lock from super class
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mMultiplexerLock")
    @Override
    protected void onRegistrationReplaced(Object oldKey, Registration oldRegistration,
            Object newKey, Registration newRegistration) {
        // by saving the last delivered location state we are able to potentially delay the
        // resulting provider request longer and save additional power
        newRegistration.setLastDeliveredLocation(oldRegistration.getLastDeliveredLocation());
        super.onRegistrationReplaced(oldKey, oldRegistration, newKey, newRegistration);
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected void onRegistrationRemoved(Object key, Registration registration) {
        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                mName,
                registration.getRequest(),
                key instanceof PendingIntent,
                /* geofence= */ key instanceof IBinder,
                null, registration.isForeground());
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected boolean registerWithService(ProviderRequest request,
            Collection<Registration> registrations) {
        if (!request.isActive()) {
            // the default request is already an empty request, no need to register this
            return true;
        }

        return reregisterWithService(ProviderRequest.EMPTY_REQUEST, request, registrations);
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected boolean reregisterWithService(ProviderRequest oldRequest,
            ProviderRequest newRequest, Collection<Registration> registrations) {
        // calculate how long the new request should be delayed before sending it off to the
        // provider, under the assumption that once we send the request off, the provider will
        // immediately attempt to deliver a new location satisfying that request.
        long delayMs;
        if (!oldRequest.isBypass() && newRequest.isBypass()) {
            delayMs = 0;
        } else if (newRequest.getIntervalMillis() > oldRequest.getIntervalMillis()) {
            // if the interval has increased, tell the provider immediately, so it can save power
            // (even though technically this could burn extra power in the short term by producing
            // an extra location - the provider itself is free to detect an increasing interval and
            // delay its own location)
            delayMs = 0;
        } else {
            delayMs = calculateRequestDelayMillis(newRequest.getIntervalMillis(), registrations);
        }

        // the delay should never exceed the new interval
        Preconditions.checkState(delayMs >= 0 && delayMs <= newRequest.getIntervalMillis());

        if (delayMs < MIN_REQUEST_DELAY_MS) {
            setProviderRequest(newRequest);
        } else {
            if (D) {
                Log.d(TAG, mName + " provider delaying request update " + newRequest + " by "
                        + TimeUtils.formatDuration(delayMs));
            }

            if (mDelayedRegister != null) {
                mAlarmHelper.cancel(mDelayedRegister);
                mDelayedRegister = null;
            }

            mDelayedRegister = new OnAlarmListener() {
                @Override
                public void onAlarm() {
                    synchronized (mMultiplexerLock) {
                        if (mDelayedRegister == this) {
                            mDelayedRegister = null;
                            setProviderRequest(newRequest);
                        }
                    }
                }
            };
            // Set WorkSource to null in order to ensure the alarm wakes up the device even when it
            // is idle. Do this when the cost of waking up the device is less than the power cost of
            // not performing the actions set off by the alarm, such as unregistering a location
            // request.
            mAlarmHelper.setDelayedAlarm(delayMs, mDelayedRegister, null);
        }

        return true;
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected void unregisterWithService() {
        setProviderRequest(ProviderRequest.EMPTY_REQUEST);
    }

    @GuardedBy("mMultiplexerLock")
    void setProviderRequest(ProviderRequest request) {
        if (mDelayedRegister != null) {
            mAlarmHelper.cancel(mDelayedRegister);
            mDelayedRegister = null;
        }

        EVENT_LOG.logProviderUpdateRequest(mName, request);
        if (D) {
            Log.d(TAG, mName + " provider request changed to " + request);
        }
        mProvider.getController().setRequest(request);

        FgThread.getHandler().post(() -> {
            for (IProviderRequestListener listener : mProviderRequestListeners) {
                try {
                    listener.onProviderRequestChanged(mName, request);
                } catch (RemoteException e) {
                    mProviderRequestListeners.remove(listener);
                }
            }
        });
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected boolean isActive(Registration registration) {
        if (!registration.isPermitted()) {
            return false;
        }

        boolean isBypass = registration.getRequest().isBypass();
        if (!isActive(isBypass, registration.getIdentity())) {
            return false;
        }

        if (!isBypass) {
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

        return true;
    }

    private boolean isActive(boolean isBypass, CallerIdentity identity) {
        if (identity.isSystemServer()) {
            if (!isBypass) {
                if (!isEnabled(mUserHelper.getCurrentUserId())) {
                    return false;
                }
            }
        } else {
            if (!isBypass) {
                if (!isEnabled(identity.getUserId())) {
                    return false;
                }
                if (!mUserHelper.isVisibleUserId(identity.getUserId())) {
                    return false;
                }
            }
            if (mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                    identity.getPackageName())) {
                return false;
            }
        }

        return true;
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        long intervalMs = ProviderRequest.INTERVAL_DISABLED;
        int quality = LocationRequest.QUALITY_LOW_POWER;
        long maxUpdateDelayMs = Long.MAX_VALUE;
        boolean adasGnssBypass = false;
        boolean locationSettingsIgnored = false;
        boolean lowPower = true;

        for (Registration registration : registrations) {
            LocationRequest request = registration.getRequest();

            // passive requests do not contribute to the provider request, and passive requests
            // must handle the batching parameters of non-passive requests
            if (request.getIntervalMillis() == LocationRequest.PASSIVE_INTERVAL) {
                continue;
            }

            intervalMs = min(request.getIntervalMillis(), intervalMs);
            quality = min(request.getQuality(), quality);
            maxUpdateDelayMs = min(request.getMaxUpdateDelayMillis(), maxUpdateDelayMs);
            adasGnssBypass |= request.isAdasGnssBypass();
            locationSettingsIgnored |= request.isLocationSettingsIgnored();
            lowPower &= request.isLowPower();
        }

        if (intervalMs == ProviderRequest.INTERVAL_DISABLED) {
            return ProviderRequest.EMPTY_REQUEST;
        }

        if (maxUpdateDelayMs / 2 < intervalMs) {
            // reduces churn if only the batching parameter has changed
            maxUpdateDelayMs = 0;
        }

        // calculate who to blame for power in a somewhat arbitrary fashion. we pick a threshold
        // interval slightly higher that the minimum interval, and spread the blame across all
        // contributing registrations under that threshold (since worksource does not allow us to
        // represent differing power blame ratios).
        long thresholdIntervalMs;
        try {
            thresholdIntervalMs = Math.multiplyExact(Math.addExact(intervalMs, 1000) / 2, 3);
        } catch (ArithmeticException e) {
            // check for and handle overflow by setting to one below the passive interval so passive
            // requests are automatically skipped
            thresholdIntervalMs = LocationRequest.PASSIVE_INTERVAL - 1;
        }

        WorkSource workSource = new WorkSource();
        for (Registration registration : registrations) {
            if (registration.getRequest().getIntervalMillis() <= thresholdIntervalMs) {
                workSource.add(registration.getRequest().getWorkSource());
            }
        }

        return new ProviderRequest.Builder()
                .setIntervalMillis(intervalMs)
                .setQuality(quality)
                .setMaxUpdateDelayMillis(maxUpdateDelayMs)
                .setAdasGnssBypass(adasGnssBypass)
                .setLocationSettingsIgnored(locationSettingsIgnored)
                .setLowPower(lowPower)
                .setWorkSource(workSource)
                .build();
    }

    @GuardedBy("mMultiplexerLock")
    protected long calculateRequestDelayMillis(long newIntervalMs,
            Collection<Registration> registrations) {
        // calculate the minimum delay across all registrations, ensuring that it is not more than
        // the requested interval
        long delayMs = newIntervalMs;
        for (Registration registration : registrations) {
            if (delayMs == 0) {
                break;
            }

            LocationRequest locationRequest = registration.getRequest();
            Location last = registration.getLastDeliveredLocation();

            if (last == null && !locationRequest.isLocationSettingsIgnored()) {
                // if this request has never gotten any location and it's not ignoring location
                // settings, then we pretend that this request has gotten the last applicable cached
                // location for our calculations instead. this prevents spammy add/remove behavior
                last = getLastLocationUnsafe(
                        registration.getIdentity().getUserId(),
                        registration.getPermissionLevel(),
                        false,
                        locationRequest.getIntervalMillis());
            }

            long registrationDelayMs;
            if (last == null) {
                // if this request has never gotten any location then there's no delay
                registrationDelayMs = 0;
            } else {
                // otherwise the delay is the amount of time until the next location is expected
                registrationDelayMs = max(0,
                        locationRequest.getIntervalMillis() - last.getElapsedRealtimeAgeMillis());
            }

            delayMs = min(delayMs, registrationDelayMs);
        }

        return delayMs;
    }

    private void onUserChanged(int userId, int change) {
        synchronized (mMultiplexerLock) {
            if (mState == STATE_STOPPED) {
                return;
            }

            switch (change) {
                case UserListener.CURRENT_USER_CHANGED:
                    // current user changes affect whether system server location requests are
                    // allowed to access location, and visibility changes affect whether any given
                    // user may access location.
                case UserListener.USER_VISIBILITY_CHANGED:
                    updateRegistrations(
                            registration -> registration.getIdentity().getUserId() == userId);
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

    private void onLocationUserSettingsChanged(int userId, LocationUserSettings oldSettings,
            LocationUserSettings newSettings) {
        if (oldSettings.isAdasGnssLocationEnabled() != newSettings.isAdasGnssLocationEnabled()) {
            updateRegistrations(
                    registration -> registration.onAdasGnssLocationEnabledChanged(userId));
        }
    }

    private void onLocationEnabledChanged(int userId) {
        synchronized (mMultiplexerLock) {
            if (mState == STATE_STOPPED) {
                return;
            }

            onEnabledChanged(userId);
        }
    }

    private void onScreenInteractiveChanged(boolean screenInteractive) {
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

    private void onBackgroundThrottlePackageWhitelistChanged() {
        updateRegistrations(Registration::onProviderLocationRequestChanged);
    }

    private void onBackgroundThrottleIntervalChanged() {
        updateRegistrations(Registration::onProviderLocationRequestChanged);
    }

    private void onLocationPowerSaveModeChanged(@LocationPowerSaveMode int locationPowerSaveMode) {
        // this is rare, just assume everything has changed to keep it simple
        updateRegistrations(registration -> true);
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        updateRegistrations(registration -> registration.onForegroundChanged(uid, foreground));
    }

    private void onAdasAllowlistChanged() {
        updateRegistrations(Registration::onProviderLocationRequestChanged);
    }

    private void onIgnoreSettingsWhitelistChanged() {
        updateRegistrations(Registration::onProviderLocationRequestChanged);
    }

    private void onLocationPackageBlacklistChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    private void onLocationPermissionsChanged(@Nullable String packageName) {
        updateRegistrations(
                registration -> registration.onLocationPermissionsChanged(packageName));
    }

    private void onLocationPermissionsChanged(int uid) {
        updateRegistrations(registration -> registration.onLocationPermissionsChanged(uid));
    }

    private void onPackageReset(String packageName) {
        updateRegistrations(
                registration -> {
                    if (registration.getIdentity().getPackageName().equals(
                            packageName)) {
                        registration.remove();
                    }

                    return false;
                });
    }

    private boolean isResetableForPackage(String packageName) {
        // invoked to find out if the given package has any state that can be "force quit"
        return findRegistration(
                registration -> registration.getIdentity().getPackageName().equals(packageName));
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    public void onStateChanged(
            AbstractLocationProvider.State oldState, AbstractLocationProvider.State newState) {
        if (oldState.allowed != newState.allowed) {
            onEnabledChanged(UserHandle.USER_ALL);
        }

        if (!Objects.equals(oldState.properties, newState.properties)) {
            updateRegistrations(Registration::onProviderPropertiesChanged);
        }

        if (mStateChangedListener != null) {
            StateChangedListener listener = mStateChangedListener;
            FgThread.getExecutor().execute(
                    () -> listener.onStateChanged(mName, oldState, newState));
        }
    }

    @GuardedBy("mMultiplexerLock")
    @Override
    public void onReportLocation(LocationResult locationResult) {
        LocationResult processed;
        if (mPassiveManager != null) {
            processed = processReportedLocation(locationResult);
            if (processed == null) {
                return;
            }

            // don't log location received for passive provider because it's spammy
            EVENT_LOG.logProviderReceivedLocations(mName, processed.size());
        } else {
            // passive provider should get already processed results as input
            processed = locationResult;
        }

        // check for non-monotonic locations if we're not the passive manager. the passive manager
        // is much more likely to see non-monotonic locations since it gets locations from all
        // providers, so this error log is not very useful there.
        if (mPassiveManager != null) {
            Location last = getLastLocationUnsafe(USER_CURRENT, PERMISSION_FINE, true,
                    Long.MAX_VALUE);
            if (last != null && locationResult.get(0).getElapsedRealtimeNanos()
                    < last.getElapsedRealtimeNanos()) {
                Log.e(TAG, "non-monotonic location received from " + mName + " provider");
            }
        }

        // update last location
        setLastLocation(processed.getLastLocation(), UserHandle.USER_ALL);

        // attempt listener delivery
        deliverToListeners(registration -> {
            return registration.acceptLocationChange(processed);
        });

        // notify passive provider
        if (mPassiveManager != null) {
            mPassiveManager.updateLocation(processed);
        }
    }

    @GuardedBy("mMultiplexerLock")
    @Nullable
    private LocationResult processReportedLocation(LocationResult locationResult) {
        try {
            locationResult.validate();
        } catch (BadLocationException e) {
            Log.e(TAG, "Dropping invalid locations: " + e);
            return null;
        }

        // Attempt to add a missing MSL altitude on behalf of the provider.
        if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", true)) {
            return locationResult.map(location -> {
                if (!location.hasMslAltitude() && location.hasAltitude()) {
                    try {
                        Location locationCopy = new Location(location);
                        if (mAltitudeConverter.tryAddMslAltitudeToLocation(locationCopy)) {
                            return locationCopy;
                        }
                        // Only queue up one IO thread runnable.
                        if (mIsAltitudeConverterIdle) {
                            mIsAltitudeConverterIdle = false;
                            IoThread.getExecutor().execute(() -> {
                                try {
                                    // Results added to the location copy are essentially discarded.
                                    // We only rely on the side effect of loading altitude assets
                                    // into the converter's memory cache.
                                    mAltitudeConverter.addMslAltitudeToLocation(mContext,
                                            locationCopy);
                                } catch (IOException e) {
                                    Log.e(TAG, "not loading MSL altitude assets: " + e);
                                }
                                mIsAltitudeConverterIdle = true;
                            });
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "not adding MSL altitude to location: " + e);
                    }
                }
                return location;
            });
        }
        return locationResult;
    }

    @GuardedBy("mMultiplexerLock")
    private void onUserStarted(int userId) {
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

    @GuardedBy("mMultiplexerLock")
    private void onUserStopped(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            mEnabled.clear();
            mLastLocations.clear();
        } else {
            Preconditions.checkArgument(userId >= 0);
            mEnabled.delete(userId);
            mLastLocations.remove(userId);
        }
    }

    @GuardedBy("mMultiplexerLock")
    private void onEnabledChanged(int userId) {
        if (userId == UserHandle.USER_NULL) {
            // used during initialization - ignore since many lower level operations (checking
            // settings for instance) do not support the null user
            return;
        } else if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                onEnabledChanged(runningUserIds[i]);
            }
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        boolean enabled = mState == STATE_STARTED
                && mProvider.getState().allowed
                && mSettingsHelper.isLocationEnabled(userId);

        int index = mEnabled.indexOfKey(userId);
        Boolean wasEnabled = index < 0 ? null : mEnabled.valueAt(index);
        if (wasEnabled != null && wasEnabled == enabled) {
            return;
        }

        mEnabled.put(userId, enabled);

        // don't log unknown -> false transitions for brevity
        if (wasEnabled != null || enabled) {
            if (D) {
                Log.d(TAG, "[u" + userId + "] " + mName + " provider enabled = " + enabled);
            }
            EVENT_LOG.logProviderEnabled(mName, userId, enabled);
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
            // passive provider never get public updates for legacy reasons
            if (!PASSIVE_PROVIDER.equals(mName)) {
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

    @Nullable Location getPermittedLocation(@Nullable Location fineLocation,
            @PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_FINE:
                return fineLocation;
            case PERMISSION_COARSE:
                return fineLocation != null ? mLocationFudger.createCoarse(fineLocation) : null;
            default:
                // shouldn't be possible to have a client added without location permissions
                throw new AssertionError();
        }
    }

    @Nullable LocationResult getPermittedLocationResult(
            @Nullable LocationResult fineLocationResult, @PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_FINE:
                return fineLocationResult;
            case PERMISSION_COARSE:
                return fineLocationResult != null ? mLocationFudger.createCoarse(fineLocationResult)
                        : null;
            default:
                // shouldn't be possible to have a client added without location permissions
                throw new AssertionError();
        }
    }

    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        synchronized (mMultiplexerLock) {
            ipw.print(mName);
            ipw.print(" provider");
            if (mProvider.isMock()) {
                ipw.print(" [mock]");
            }
            ipw.println(":");
            ipw.increaseIndent();

            super.dump(fd, ipw, args);

            int[] userIds = mUserHelper.getRunningUserIds();
            for (int userId : userIds) {
                if (userIds.length != 1) {
                    ipw.print("user ");
                    ipw.print(userId);
                    ipw.println(":");
                    ipw.increaseIndent();
                }
                ipw.print("last location=");
                ipw.println(getLastLocationUnsafe(userId, PERMISSION_FINE, false, Long.MAX_VALUE));
                ipw.print("enabled=");
                ipw.println(isEnabled(userId));
                if (userIds.length != 1) {
                    ipw.decreaseIndent();
                }
            }
        }

        mProvider.dump(fd, ipw, args);

        ipw.decreaseIndent();
    }

    @Override
    protected String getServiceState() {
        return mProvider.getCurrentRequest().toString();
    }

    private static class LastLocation {

        @Nullable private Location mFineLocation;
        @Nullable private Location mCoarseLocation;
        @Nullable private Location mFineBypassLocation;
        @Nullable private Location mCoarseBypassLocation;

        LastLocation() {}

        public void clearMock() {
            if (mFineLocation != null && mFineLocation.isMock()) {
                mFineLocation = null;
            }
            if (mCoarseLocation != null && mCoarseLocation.isMock()) {
                mCoarseLocation = null;
            }
            if (mFineBypassLocation != null && mFineBypassLocation.isMock()) {
                mFineBypassLocation = null;
            }
            if (mCoarseBypassLocation != null && mCoarseBypassLocation.isMock()) {
                mCoarseBypassLocation = null;
            }
        }

        public void clearLocations() {
            mFineLocation = null;
            mCoarseLocation = null;
        }

        @Nullable public Location get(@PermissionLevel int permissionLevel,
                boolean isBypass) {
            switch (permissionLevel) {
                case PERMISSION_FINE:
                    if (isBypass) {
                        return mFineBypassLocation;
                    } else {
                        return mFineLocation;
                    }
                case PERMISSION_COARSE:
                    if (isBypass) {
                        return mCoarseBypassLocation;
                    } else {
                        return mCoarseLocation;
                    }
                default:
                    // shouldn't be possible to have a client added without location permissions
                    throw new AssertionError();
            }
        }

        public void set(Location location) {
            mFineLocation = calculateNextFine(mFineLocation, location);
            mCoarseLocation = calculateNextCoarse(mCoarseLocation, location);
        }

        public void setBypass(Location location) {
            mFineBypassLocation = calculateNextFine(mFineBypassLocation, location);
            mCoarseBypassLocation = calculateNextCoarse(mCoarseBypassLocation, location);
        }

        private Location calculateNextFine(@Nullable Location oldFine, Location newFine) {
            if (oldFine == null) {
                return newFine;
            }

            // update last fine interval only if more recent
            if (newFine.getElapsedRealtimeNanos() > oldFine.getElapsedRealtimeNanos()) {
                return newFine;
            } else {
                return oldFine;
            }
        }

        private Location calculateNextCoarse(@Nullable Location oldCoarse, Location newCoarse) {
            if (oldCoarse == null) {
                return newCoarse;
            }

            // update last coarse interval only if enough time has passed
            if (newCoarse.getElapsedRealtimeMillis() - MIN_COARSE_INTERVAL_MS
                    > oldCoarse.getElapsedRealtimeMillis()) {
                return newCoarse;
            } else {
                return oldCoarse;
            }
        }
    }

    private static class PendingIntentSender {

        // send() SHOULD only run the OnFinished callback if it completes successfully. however,
        // b/201299281 (which could not be fixed in the S timeframe) means that it's possible
        // for send() to throw an exception AND run the completion callback which breaks the
        // guarantee we rely on. we take matters into our own hands to ensure that the OnFinished
        // callback can only be run if send() completes successfully. this means the OnFinished
        // callback may be run inline, so there is no longer any guarantee about what thread the
        // callback will be run on.
        public static void send(PendingIntent pendingIntent, Context context, Intent intent,
                @Nullable final Runnable callback, Bundle options)
                throws PendingIntent.CanceledException {
            GatedCallback gatedCallback;
            PendingIntent.OnFinished onFinished;
            if (callback != null) {
                gatedCallback = new GatedCallback(callback);
                onFinished = (pI, i, rC, rD, rE) -> gatedCallback.run();
            } else {
                gatedCallback = null;
                onFinished = null;
            }

            pendingIntent.send(
                    context,
                    0,
                    intent,
                    onFinished,
                    null,
                    null,
                    options);
            if (gatedCallback != null) {
                gatedCallback.allow();
            }
        }

        private static class GatedCallback implements Runnable {

            @GuardedBy("this")
            @Nullable private Runnable mCallback;

            @GuardedBy("this")
            private boolean mGate;
            @GuardedBy("this")
            private boolean mRun;

            private GatedCallback(@Nullable Runnable callback) {
                mCallback = callback;
            }

            public void allow() {
                Runnable callback = null;
                synchronized (this) {
                    mGate = true;
                    if (mRun && mCallback != null) {
                        callback = mCallback;
                        mCallback = null;
                    }
                }

                if (callback != null) {
                    callback.run();
                }
            }

            @Override
            public void run() {
                Runnable callback = null;
                synchronized (this) {
                    mRun = true;
                    if (mGate && mCallback != null) {
                        callback = mCallback;
                        mCallback = null;
                    }
                }

                if (callback != null) {
                    callback.run();
                }
            }
        }
    }

    private static class ExternalWakeLockReleaser extends IRemoteCallback.Stub {

        private final CallerIdentity mIdentity;
        private final PowerManager.WakeLock mWakeLock;

        ExternalWakeLockReleaser(CallerIdentity identity, PowerManager.WakeLock wakeLock) {
            mIdentity = identity;
            mWakeLock = Objects.requireNonNull(wakeLock);
        }

        @Override
        public void sendResult(Bundle data) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mWakeLock.release();
            } catch (RuntimeException e) {
                // wakelock throws a RuntimeException instead of some more specific exception, so
                // attempt to capture only actual RuntimeExceptions
                if (e.getClass() == RuntimeException.class) {
                    Log.e(TAG, "wakelock over-released by " + mIdentity, e);
                } else {
                    // since this is within a oneway binder transaction there is nowhere for
                    // exceptions to go - move onto another thread to crash system server so we find
                    // out about it
                    FgThread.getExecutor().execute(() -> {
                        throw new AssertionError(e);
                    });
                    throw e;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
