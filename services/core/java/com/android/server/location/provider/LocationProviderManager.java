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

import static android.app.compat.CompatChanges.isChangeEnabled;
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
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
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
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.location.LocationPermissions;
import com.android.server.location.LocationPermissions.PermissionLevel;
import com.android.server.location.fudger.LocationFudger;
import com.android.server.location.injector.AlarmHelper;
import com.android.server.location.injector.AppForegroundHelper;
import com.android.server.location.injector.AppForegroundHelper.AppForegroundListener;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationAttributionHelper;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationPermissionsHelper.LocationPermissionsListener;
import com.android.server.location.injector.LocationPowerSaveModeHelper;
import com.android.server.location.injector.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.ScreenInteractiveHelper;
import com.android.server.location.injector.ScreenInteractiveHelper.ScreenInteractiveChangedListener;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener;
import com.android.server.location.injector.SettingsHelper.UserSettingChangedListener;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.injector.UserInfoHelper.UserListener;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.RemoteListenerRegistration;
import com.android.server.location.settings.LocationSettings;
import com.android.server.location.settings.LocationUserSettings;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
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
                @Nullable Runnable onCompleteCallback) throws Exception;
        void deliverOnFlushComplete(int requestCode) throws Exception;
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
        public void deliverOnLocationChanged(LocationResult locationResult,
                @Nullable Runnable onCompleteCallback) throws RemoteException {
            mListener.onLocationChanged(locationResult.asList(),
                    SingleUseCallback.wrap(onCompleteCallback));
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) throws RemoteException {
            mListener.onFlushComplete(requestCode);
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
        public void deliverOnLocationChanged(LocationResult locationResult,
                @Nullable Runnable onCompleteCallback)
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

            mPendingIntent.send(
                    mContext,
                    0,
                    intent,
                    onCompleteCallback != null ? (pI, i, rC, rD, rE) -> onCompleteCallback.run()
                            : null,
                    null,
                    null,
                    options.toBundle());
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);

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

        protected GetCurrentLocationTransport(ILocationCallback callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void deliverOnLocationChanged(@Nullable LocationResult locationResult,
                @Nullable Runnable onCompleteCallback)
                throws RemoteException {
            // ILocationCallback doesn't currently support completion callbacks
            Preconditions.checkState(onCompleteCallback == null);
            if (locationResult != null) {
                mCallback.onLocation(locationResult.getLastLocation());
            } else {
                mCallback.onLocation(null);
            }
        }

        @Override
        public void deliverOnFlushComplete(int requestCode) {}
    }

    protected abstract class Registration extends RemoteListenerRegistration<LocationRequest,
            LocationTransport> {

        private final @PermissionLevel int mPermissionLevel;

        // we cache these values because checking/calculating on the fly is more expensive
        private boolean mPermitted;
        private boolean mForeground;
        private LocationRequest mProviderLocationRequest;
        private boolean mIsUsingHighPower;

        private @Nullable Location mLastLocation = null;

        protected Registration(LocationRequest request, CallerIdentity identity,
                LocationTransport transport, @PermissionLevel int permissionLevel) {
            super(Objects.requireNonNull(request), identity, transport);

            Preconditions.checkArgument(identity.getListenerId() != null);
            Preconditions.checkArgument(permissionLevel > PERMISSION_NONE);
            Preconditions.checkArgument(!request.getWorkSource().isEmpty());

            mPermissionLevel = permissionLevel;
            mProviderLocationRequest = request;
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

            EVENT_LOG.logProviderClientRegistered(mName, getIdentity(), super.getRequest());

            // initialization order is important as there are ordering dependencies
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().getUid());
            mProviderLocationRequest = calculateProviderLocationRequest();
            mIsUsingHighPower = isUsingHighPower();

            onProviderListenerRegister();

            if (mForeground) {
                EVENT_LOG.logProviderClientForeground(mName, getIdentity());
            }
        }

        @GuardedBy("mLock")
        @Override
        protected final void onRemovableListenerUnregister() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            onProviderListenerUnregister();

            EVENT_LOG.logProviderClientUnregistered(mName, getIdentity());

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
        protected final void onActive() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            EVENT_LOG.logProviderClientActive(mName, getIdentity());

            if (!getRequest().isHiddenFromAppOps()) {
                mLocationAttributionHelper.reportLocationStart(getIdentity(), getName(), getKey());
            }
            onHighPowerUsageChanged();

            onProviderListenerActive();
        }

        @Override
        protected final void onInactive() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            onHighPowerUsageChanged();
            if (!getRequest().isHiddenFromAppOps()) {
                mLocationAttributionHelper.reportLocationStop(getIdentity(), getName(), getKey());
            }

            onProviderListenerInactive();

            EVENT_LOG.logProviderClientInactive(mName, getIdentity());
        }

        /**
         * Subclasses may override this instead of {@link #onActive()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerActive() {}

        /**
         * Subclasses may override this instead of {@link #onInactive()} ()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerInactive() {}

        @Override
        public final LocationRequest getRequest() {
            return mProviderLocationRequest;
        }

        @GuardedBy("mLock")
        final void setLastDeliveredLocation(@Nullable Location location) {
            mLastLocation = location;
        }

        @GuardedBy("mLock")
        public final Location getLastDeliveredLocation() {
            return mLastLocation;
        }

        public @PermissionLevel int getPermissionLevel() {
            return mPermissionLevel;
        }

        public final boolean isForeground() {
            return mForeground;
        }

        public final boolean isPermitted() {
            return mPermitted;
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

        @GuardedBy("mLock")
        final boolean onProviderPropertiesChanged() {
            onHighPowerUsageChanged();
            return false;
        }

        @GuardedBy("mLock")
        private void onHighPowerUsageChanged() {
            boolean isUsingHighPower = isUsingHighPower();
            if (isUsingHighPower != mIsUsingHighPower) {
                mIsUsingHighPower = isUsingHighPower;

                if (!getRequest().isHiddenFromAppOps()) {
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

            ProviderProperties properties = getProperties();
            if (properties == null) {
                return false;
            }

            return isActive()
                    && getRequest().getIntervalMillis() < MAX_HIGH_POWER_INTERVAL_MS
                    && properties.getPowerUsage() == ProviderProperties.POWER_USAGE_HIGH;
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

                if (mPermitted) {
                    EVENT_LOG.logProviderClientPermitted(mName, getIdentity());
                } else {
                    EVENT_LOG.logProviderClientUnpermitted(mName, getIdentity());
                }

                return true;
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onAdasGnssLocationEnabledChanged(int userId) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (getIdentity().getUserId() == userId) {
                return onProviderLocationRequestChanged();
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

            // if bypass state has changed then the active state may have changed
            return oldRequest.isBypass() != newRequest.isBypass();
        }

        private LocationRequest calculateProviderLocationRequest() {
            LocationRequest baseRequest = super.getRequest();
            LocationRequest.Builder builder = new LocationRequest.Builder(baseRequest);

            if (mPermissionLevel < PERMISSION_FINE) {
                builder.setQuality(LocationRequest.QUALITY_LOW_POWER);
                if (baseRequest.getIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.setIntervalMillis(MIN_COARSE_INTERVAL_MS);
                }
                if (baseRequest.getMinUpdateIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.setMinUpdateIntervalMillis(MIN_COARSE_INTERVAL_MS);
                }
            }

            boolean locationSettingsIgnored = baseRequest.isLocationSettingsIgnored();
            if (locationSettingsIgnored) {
                // if we are not currently allowed use location settings ignored, disable it
                if (!mSettingsHelper.getIgnoreSettingsAllowlist().contains(
                        getIdentity().getPackageName(), getIdentity().getAttributionTag())
                        && !mLocationManagerInternal.isProvider(null, getIdentity())) {
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
                } else if (!mLocationSettings.getUserSettings(
                        getIdentity().getUserId()).isAdasGnssLocationEnabled()) {
                    adasGnssBypass = false;
                }

                builder.setAdasGnssBypass(adasGnssBypass);
            }

            if (!locationSettingsIgnored && !isThrottlingExempt()) {
                // throttle in the background
                if (!mForeground) {
                    builder.setIntervalMillis(max(baseRequest.getIntervalMillis(),
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

        @GuardedBy("mLock")
        abstract @Nullable ListenerOperation<LocationTransport> acceptLocationChange(
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

        private volatile ProviderTransport mProviderTransport;
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
            mWakeLock.setWorkSource(request.getWorkSource());
        }

        @Override
        protected void onListenerUnregister() {
            mProviderTransport = null;
        }

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerRegister() {
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
                mAlarmHelper.cancel(this);
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

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerActive() {
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

            synchronized (mLock) {
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
                remove();
            }
        }

        @GuardedBy("mLock")
        @Override
        @Nullable ListenerOperation<LocationTransport> acceptLocationChange(
                LocationResult fineLocationResult) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

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
                            if (mPreviousLocation != null) {
                                // check fastest interval
                                long deltaMs = location.getElapsedRealtimeMillis()
                                        - mPreviousLocation.getElapsedRealtimeMillis();
                                long maxJitterMs = min((long) (FASTEST_INTERVAL_JITTER_PERCENTAGE
                                                * getRequest().getIntervalMillis()),
                                        MAX_FASTEST_INTERVAL_JITTER_MS);
                                if (deltaMs
                                        < getRequest().getMinUpdateIntervalMillis() - maxJitterMs) {
                                    return false;
                                }

                                // check smallest displacement
                                double smallestDisplacementM =
                                        getRequest().getMinUpdateDistanceMeters();
                                if (smallestDisplacementM > 0.0 && location.distanceTo(
                                        mPreviousLocation)
                                        <= smallestDisplacementM) {
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
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                return null;
            }

            // deliver location
            return new ListenerOperation<LocationTransport>() {

                private boolean mUseWakeLock;

                @Override
                public void onPreExecute() {
                    mUseWakeLock = false;
                    final int size = locationResult.size();
                    for (int i = 0; i < size; ++i) {
                        if (!locationResult.get(i).isMock()) {
                            mUseWakeLock = true;
                            break;
                        }
                    }

                    // update last delivered location
                    setLastDeliveredLocation(locationResult.getLastLocation());

                    // don't acquire a wakelock for mock locations to prevent abuse
                    if (mUseWakeLock) {
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
                            mUseWakeLock ? mWakeLock::release : null);
                    EVENT_LOG.logProviderDeliveredLocations(mName, locationResult.size(),
                            getIdentity());
                }

                @Override
                public void onPostExecute(boolean success) {
                    if (!success && mUseWakeLock) {
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
                    listener -> listener.deliverOnProviderEnabledChanged(mName, enabled),
                    this::onProviderOperationFailure);
        }

        protected abstract void onProviderOperationFailure(
                ListenerOperation<ProviderTransport> operation, Exception exception);
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
                synchronized (mLock) {
                    remove();
                }
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
                synchronized (mLock) {
                    remove();
                }
            } else {
                throw new AssertionError(e);
            }
        }

        @Override
        public void onCancelled(PendingIntent intent) {
            if (D) {
                Log.d(TAG, mName + " provider registration " + getIdentity() + " cancelled");
            }

            synchronized (mLock) {
                remove();
            }
        }
    }

    protected final class GetCurrentLocationListenerRegistration extends Registration implements
            IBinder.DeathRecipient, OnAlarmListener {

        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected GetCurrentLocationListenerRegistration(LocationRequest request,
                CallerIdentity identity, LocationTransport transport, int permissionLevel) {
            super(request, identity, transport, permissionLevel);
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerRegister() {
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

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerUnregister() {
            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.cancel(this);
            }

            ((IBinder) getKey()).unlinkToDeath(this, 0);
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerActive() {
            Location lastLocation = getLastLocationUnsafe(
                    getIdentity().getUserId(),
                    getPermissionLevel(),
                    getRequest().isBypass(),
                    MAX_CURRENT_LOCATION_AGE_MS);
            if (lastLocation != null) {
                executeOperation(acceptLocationChange(LocationResult.wrap(lastLocation)));
            }
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerInactive() {
            // if we go inactive for any reason, fail immediately
            executeOperation(acceptLocationChange(null));
        }

        void deliverNull() {
            synchronized (mLock) {
                executeOperation(acceptLocationChange(null));
            }
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, mName + " provider registration " + getIdentity()
                        + " expired at " + TimeUtils.formatRealtime(mExpirationRealtimeMs));
            }

            synchronized (mLock) {
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
                executeOperation(acceptLocationChange(null));
            }
        }

        @GuardedBy("mLock")
        @Override
        @Nullable ListenerOperation<LocationTransport> acceptLocationChange(
                @Nullable LocationResult fineLocationResult) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

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
                        synchronized (mLock) {
                            remove();
                        }
                    }
                }
            };
        }

        @Override
        public void onOperationFailure(ListenerOperation<LocationTransport> operation,
                Exception e) {
            if (e instanceof RemoteException) {
                Log.w(TAG, mName + " provider registration " + getIdentity() + " removed", e);
                synchronized (mLock) {
                    remove();
                }
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
    private final @Nullable PassiveLocationProviderManager mPassiveManager;

    protected final Context mContext;

    @GuardedBy("mLock")
    private @State int mState;

    // maps of user id to value
    @GuardedBy("mLock")
    private final SparseBooleanArray mEnabled; // null or not present means unknown
    @GuardedBy("mLock")
    private final SparseArray<LastLocation> mLastLocations;

    @GuardedBy("mLock")
    private final ArrayList<ProviderEnabledListener> mEnabledListeners;

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
    protected final LocationAttributionHelper mLocationAttributionHelper;
    protected final LocationUsageLogger mLocationUsageLogger;
    protected final LocationFudger mLocationFudger;

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

    @GuardedBy("mLock")
    private @Nullable OnAlarmListener mDelayedRegister;

    @GuardedBy("mLock")
    private @Nullable StateChangedListener mStateChangedListener;

    public LocationProviderManager(Context context, Injector injector,
            String name, @Nullable PassiveLocationProviderManager passiveManager) {
        mContext = context;
        mName = Objects.requireNonNull(name);
        mPassiveManager = passiveManager;
        mState = STATE_STOPPED;
        mEnabled = new SparseBooleanArray(2);
        mLastLocations = new SparseArray<>(2);

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
        mLocationAttributionHelper = injector.getLocationAttributionHelper();
        mLocationUsageLogger = injector.getLocationUsageLogger();
        mLocationFudger = new LocationFudger(mSettingsHelper.getCoarseLocationAccuracyM());

        mProvider = new MockableLocationProvider(mLock);

        // set listener last, since this lets our reference escape
        mProvider.getController().setListener(this);
    }

    @Override
    public String getTag() {
        return TAG;
    }

    public void startManager(@Nullable StateChangedListener listener) {
        synchronized (mLock) {
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
        synchronized (mLock) {
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

    public @Nullable CallerIdentity getIdentity() {
        return mProvider.getState().identity;
    }

    public @Nullable ProviderProperties getProperties() {
        return mProvider.getState().properties;
    }

    public boolean hasProvider() {
        return mProvider.getProvider() != null;
    }

    public boolean isEnabled(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return false;
        } else if (userId == UserHandle.USER_CURRENT) {
            return isEnabled(mUserHelper.getCurrentUserId());
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
            Preconditions.checkState(mState != STATE_STOPPED);
            mEnabledListeners.add(listener);
        }
    }

    public void removeEnabledListener(ProviderEnabledListener listener) {
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        synchronized (mLock) {
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

    public @Nullable Location getLastLocation(LastLocationRequest request,
            CallerIdentity identity, @PermissionLevel int permissionLevel) {
        if (!isActive(request.isBypass(), identity)) {
            return null;
        }

        // lastly - note app ops
        if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(permissionLevel),
                identity)) {
            return null;
        }

        Location location = getPermittedLocation(
                getLastLocationUnsafe(
                        identity.getUserId(),
                        permissionLevel,
                        request.isBypass(),
                        Long.MAX_VALUE),
                permissionLevel);

        if (location != null && identity.getPid() == Process.myPid()) {
            // if delivering to the same process, make a copy of the location first (since
            // location is mutable)
            location = new Location(location);
        }

        return location;
    }

    /**
     * This function does not perform any permissions or safety checks, by calling it you are
     * committing to performing all applicable checks yourself. This always returns a "fine"
     * location, even if the permissionLevel is coarse. You are responsible for coarsening the
     * location if necessary.
     */
    public @Nullable Location getLastLocationUnsafe(int userId,
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
        } else if (userId == UserHandle.USER_CURRENT) {
            return getLastLocationUnsafe(mUserHelper.getCurrentUserId(), permissionLevel,
                    isBypass, maximumAgeMs);
        }

        Preconditions.checkArgument(userId >= 0);

        Location location;
        synchronized (mLock) {
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
        synchronized (mLock) {
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
        } else if (userId == UserHandle.USER_CURRENT) {
            setLastLocation(location, mUserHelper.getCurrentUserId());
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
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

    public @Nullable ICancellationSignal getCurrentLocation(LocationRequest request,
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

        synchronized (mLock) {
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
                .setOnCancelListener(SingleUseCallback.wrap(
                        () -> {
                            synchronized (mLock) {
                                removeRegistration(callback.asBinder(), registration);
                            }
                        }));
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

        synchronized (mLock) {
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

        synchronized (mLock) {
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
        synchronized (mLock) {
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
    }

    public void flush(PendingIntent pendingIntent, int requestCode) {
        synchronized (mLock) {
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
    }

    public void unregisterLocationRequest(ILocationListener listener) {
        synchronized (mLock) {
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
        synchronized (mLock) {
            Preconditions.checkState(mState != STATE_STOPPED);
            final long identity = Binder.clearCallingIdentity();
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
        mSettingsHelper.addIgnoreSettingsAllowlistChangedListener(
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
        mSettingsHelper.removeIgnoreSettingsAllowlistChangedListener(
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
                registration.getIdentity().getAttributionTag(),
                mName,
                registration.getRequest(),
                key instanceof PendingIntent,
                /* geofence= */ key instanceof IBinder,
                null, registration.isForeground());
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationReplaced(Object key, Registration oldRegistration,
            Registration newRegistration) {
        // by saving the last delivered location state we are able to potentially delay the
        // resulting provider request longer and save additional power
        newRegistration.setLastDeliveredLocation(oldRegistration.getLastDeliveredLocation());
        super.onRegistrationReplaced(key, oldRegistration, newRegistration);
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationRemoved(Object key, Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

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

    @GuardedBy("mLock")
    @Override
    protected boolean registerWithService(ProviderRequest request,
            Collection<Registration> registrations) {
        return reregisterWithService(ProviderRequest.EMPTY_REQUEST, request, registrations);
    }

    @GuardedBy("mLock")
    @Override
    protected boolean reregisterWithService(ProviderRequest oldRequest,
            ProviderRequest newRequest, Collection<Registration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (mDelayedRegister != null) {
            mAlarmHelper.cancel(mDelayedRegister);
            mDelayedRegister = null;
        }

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

            mDelayedRegister = new OnAlarmListener() {
                @Override
                public void onAlarm() {
                    synchronized (mLock) {
                        if (mDelayedRegister == this) {
                            setProviderRequest(newRequest);
                            mDelayedRegister = null;
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

    @GuardedBy("mLock")
    @Override
    protected void unregisterWithService() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        setProviderRequest(ProviderRequest.EMPTY_REQUEST);
    }

    @GuardedBy("mLock")
    void setProviderRequest(ProviderRequest request) {
        EVENT_LOG.logProviderUpdateRequest(mName, request);
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

    @GuardedBy("mLock")
    @Override
    protected boolean isActive(Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

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
                if (!mUserHelper.isCurrentUserId(identity.getUserId())) {
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

    @GuardedBy("mLock")
    @Override
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

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

    @GuardedBy("mLock")
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
        synchronized (mLock) {
            if (mState == STATE_STOPPED) {
                return;
            }

            switch (change) {
                case UserListener.CURRENT_USER_CHANGED:
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
            synchronized (mLock) {
                updateRegistrations(
                        registration -> registration.onAdasGnssLocationEnabledChanged(userId));
            }
        }
    }

    private void onLocationEnabledChanged(int userId) {
        synchronized (mLock) {
            if (mState == STATE_STOPPED) {
                return;
            }

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

        if (!Objects.equals(oldState.properties, newState.properties)) {
            updateRegistrations(Registration::onProviderPropertiesChanged);
        }

        if (mStateChangedListener != null) {
            StateChangedListener listener = mStateChangedListener;
            FgThread.getExecutor().execute(
                    () -> listener.onStateChanged(mName, oldState, newState));
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onReportLocation(LocationResult locationResult) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        LocationResult filtered;
        if (mPassiveManager != null) {
            filtered = locationResult.filter(location -> {
                if (!location.isMock()) {
                    if (location.getLatitude() == 0 && location.getLongitude() == 0) {
                        Log.w(TAG, "blocking 0,0 location from " + mName + " provider");
                        return false;
                    }
                }

                if (!location.isComplete()) {
                    Log.w(TAG, "blocking incomplete location from " + mName + " provider");
                    return false;
                }

                return true;
            });

            if (filtered == null) {
                return;
            }

            // don't log location received for passive provider because it's spammy
            EVENT_LOG.logProviderReceivedLocations(mName, filtered.size());
        } else {
            // passive provider should get already filtered results as input
            filtered = locationResult;
        }

        // update last location
        setLastLocation(filtered.getLastLocation(), UserHandle.USER_ALL);

        // attempt listener delivery
        deliverToListeners(registration -> {
            return registration.acceptLocationChange(filtered);
        });

        // notify passive provider
        if (mPassiveManager != null) {
            mPassiveManager.updateLocation(filtered);
        }
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
            mEnabled.clear();
            mLastLocations.clear();
        } else {
            Preconditions.checkArgument(userId >= 0);
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
        synchronized (mLock) {
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

        private @Nullable Location mFineLocation;
        private @Nullable Location mCoarseLocation;
        private @Nullable Location mFineBypassLocation;
        private @Nullable Location mCoarseBypassLocation;

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

        public @Nullable Location get(@PermissionLevel int permissionLevel,
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

    private static class SingleUseCallback extends IRemoteCallback.Stub implements Runnable,
            CancellationSignal.OnCancelListener {

        public static @Nullable SingleUseCallback wrap(@Nullable Runnable callback) {
            return callback == null ? null : new SingleUseCallback(callback);
        }

        @GuardedBy("this")
        private @Nullable Runnable mCallback;

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

            final long identity = Binder.clearCallingIdentity();
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
