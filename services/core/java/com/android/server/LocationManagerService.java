/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.os.PowerManager.locationPowerSaveModeToString;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssMeasurementCorrections;
import android.location.GnssRequest;
import android.location.IBatchedLocationCallback;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.stats.location.LocationStatsEnums;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.location.AbstractLocationProvider;
import com.android.server.location.AbstractLocationProvider.State;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.CallerIdentity;
import com.android.server.location.GeocoderProxy;
import com.android.server.location.GeofenceManager;
import com.android.server.location.GeofenceProxy;
import com.android.server.location.HardwareActivityRecognitionProxy;
import com.android.server.location.LocationFudger;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.LocationRequestStatistics;
import com.android.server.location.LocationRequestStatistics.PackageProviderKey;
import com.android.server.location.LocationRequestStatistics.PackageStatistics;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.MockProvider;
import com.android.server.location.MockableLocationProvider;
import com.android.server.location.PassiveProvider;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;
import com.android.server.location.UserInfoHelper.UserListener;
import com.android.server.location.gnss.GnssManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 */
public class LocationManagerService extends ILocationManager.Stub {

    /**
     * Controls lifecycle of LocationManagerService.
     */
    public static class Lifecycle extends SystemService {

        private final LocationManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new LocationManagerService(context);
        }

        @Override
        public void onStart() {
            // enable client caches by doing the first invalidate
            LocationManager.invalidateLocalLocationEnabledCaches();

            publishBinderService(Context.LOCATION_SERVICE, mService);
            // disable caching for whatever process contains LocationManagerService
            ((LocationManager) mService.mContext.getSystemService(LocationManager.class))
                    .disableLocalLocationEnabledCaches();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                // the location service must be functioning after this boot phase
                mService.onSystemReady();
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                // some providers rely on third party code, so we wait to initialize
                // providers until third party code is allowed to run
                mService.onSystemThirdPartyAppsCanStart();
            }
        }
    }

    public static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String WAKELOCK_KEY = "*location*";

    private static final int RESOLUTION_LEVEL_NONE = 0;
    private static final int RESOLUTION_LEVEL_COARSE = 1;
    private static final int RESOLUTION_LEVEL_FINE = 2;

    private static final String NETWORK_LOCATION_SERVICE_ACTION =
            "com.android.location.service.v3.NetworkLocationProvider";
    private static final String FUSED_LOCATION_SERVICE_ACTION =
            "com.android.location.service.FusedLocationProvider";

    // The maximum interval a location request can have and still be considered "high power".
    private static final long HIGH_POWER_INTERVAL_MS = 5 * 60 * 1000;

    // The fastest interval that applications can receive coarse locations
    private static final long FASTEST_COARSE_INTERVAL_MS = 10 * 60 * 1000;

    // maximum age of a location before it is no longer considered "current"
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 10 * 1000;

    // Location Providers may sometimes deliver location updates
    // slightly faster that requested - provide grace period so
    // we don't unnecessarily filter events that are otherwise on
    // time
    private static final int MAX_PROVIDER_SCHEDULING_JITTER_MS = 100;

    private static final String ATTRIBUTION_TAG = "LocationService";

    private static final LocationRequest DEFAULT_LOCATION_REQUEST = new LocationRequest();

    private final Object mLock = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final LocalService mLocalService;
    private final UserInfoHelper mUserInfoHelper;
    private final SettingsHelper mSettingsHelper;
    private final AppForegroundHelper mAppForegroundHelper;
    private final LocationUsageLogger mLocationUsageLogger;

    @Nullable private GnssManagerService mGnssManagerService = null;

    private final PassiveLocationProviderManager mPassiveManager;

    private AppOpsManager mAppOps;
    private PackageManager mPackageManager;
    private PowerManager mPowerManager;

    private GeofenceManager mGeofenceManager;
    private GeocoderProxy mGeocodeProvider;

    @GuardedBy("mLock")
    private String mExtraLocationControllerPackage;
    @GuardedBy("mLock")
    private boolean mExtraLocationControllerPackageEnabled;

    // @GuardedBy("mLock")
    // hold lock for write or to prevent write, no lock for read
    private final CopyOnWriteArrayList<LocationProviderManager> mProviderManagers =
            new CopyOnWriteArrayList<>();

    @GuardedBy("mLock")
    private final HashMap<Object, Receiver> mReceivers = new HashMap<>();
    private final HashMap<String, ArrayList<UpdateRecord>> mRecordsByProvider =
            new HashMap<>();

    private final LocationRequestStatistics mRequestStatistics = new LocationRequestStatistics();

    @GuardedBy("mLock")
    @PowerManager.LocationPowerSaveMode
    private int mBatterySaverMode;

    private LocationManagerService(Context context) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mHandler = FgThread.getHandler();
        mLocalService = new LocalService();

        LocalServices.addService(LocationManagerInternal.class, mLocalService);

        mUserInfoHelper = new UserInfoHelper(mContext);
        mSettingsHelper = new SettingsHelper(mContext, mHandler);
        mAppForegroundHelper = new AppForegroundHelper(mContext);
        mLocationUsageLogger = new LocationUsageLogger();

        // set up passive provider - we do this early because it has no dependencies on system
        // services or external code that isn't ready yet, and because this allows the variable to
        // be final. other more complex providers are initialized later, when system services are
        // ready
        mPassiveManager = new PassiveLocationProviderManager();
        mProviderManagers.add(mPassiveManager);
        mPassiveManager.setRealProvider(new PassiveProvider(mContext));

        // Let the package manager query which are the default location
        // providers as they get certain permissions granted by default.
        PermissionManagerServiceInternal permissionManagerInternal = LocalServices.getService(
                PermissionManagerServiceInternal.class);
        permissionManagerInternal.setLocationPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_locationProviderPackageNames));
        permissionManagerInternal.setLocationExtraPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_locationExtraPackageNames));

        // most startup is deferred until systemReady()
    }

    private void onSystemReady() {
        mUserInfoHelper.onSystemReady();
        mSettingsHelper.onSystemReady();
        mAppForegroundHelper.onSystemReady();

        synchronized (mLock) {
            mPackageManager = mContext.getPackageManager();
            mAppOps = mContext.getSystemService(AppOpsManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);
            mGeofenceManager = new GeofenceManager(mContext, mSettingsHelper);

            PowerManagerInternal localPowerManager =
                    LocalServices.getService(PowerManagerInternal.class);

            // add listeners
            mAppOps.startWatchingMode(
                    AppOpsManager.OP_COARSE_LOCATION,
                    null,
                    AppOpsManager.WATCH_FOREGROUND_CHANGES,
                    new AppOpsManager.OnOpChangedInternalListener() {
                        public void onOpChanged(int op, String packageName) {
                            // onOpChanged invoked on ui thread, move to our thread to reduce risk
                            // of blocking ui thread
                            mHandler.post(() -> onAppOpChanged(packageName));
                        }
                    });
            mPackageManager.addOnPermissionsChangeListener(
                    uid -> {
                        // listener invoked on ui thread, move to our thread to reduce risk of
                        // blocking ui thread
                        mHandler.post(() -> {
                            synchronized (mLock) {
                                onPermissionsChangedLocked();
                            }
                        });
                    });

            localPowerManager.registerLowPowerModeObserver(ServiceType.LOCATION,
                    state -> {
                        // listener invoked on ui thread, move to our thread to reduce risk of
                        // blocking ui thread
                        mHandler.post(() -> {
                            synchronized (mLock) {
                                onBatterySaverModeChangedLocked(state.locationMode);
                            }
                        });
                    });
            mBatterySaverMode = mPowerManager.getLocationPowerSaveMode();

            mSettingsHelper.addOnLocationEnabledChangedListener(this::onLocationModeChanged);
            mSettingsHelper.addOnBackgroundThrottleIntervalChangedListener(
                    this::onBackgroundThrottleIntervalChanged);
            mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                    this::onBackgroundThrottleWhitelistChanged);
            mSettingsHelper.addOnIgnoreSettingsPackageWhitelistChangedListener(
                    this::onIgnoreSettingsWhitelistChanged);

            new PackageMonitor() {
                @Override
                public void onPackageDisappeared(String packageName, int reason) {
                    synchronized (mLock) {
                        LocationManagerService.this.onPackageDisappearedLocked(packageName);
                    }
                }
            }.register(mContext, mHandler.getLooper(), true);

            mUserInfoHelper.addListener(this::onUserChanged);

            mAppForegroundHelper.addListener(this::onAppForegroundChanged);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);

            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if (action == null) {
                        return;
                    }
                    synchronized (mLock) {
                        switch (action) {
                            case Intent.ACTION_SCREEN_ON:
                            case Intent.ACTION_SCREEN_OFF:
                                onScreenStateChangedLocked();
                                break;
                        }
                    }
                }
            }, UserHandle.ALL, intentFilter, null, mHandler);

            // initialize the current users. we would get the user started notifications for these
            // users eventually anyways, but this takes care of it as early as possible.
            for (int userId: mUserInfoHelper.getCurrentUserIds()) {
                onUserChanged(userId, UserListener.USER_STARTED);
            }
        }
    }

    private void onSystemThirdPartyAppsCanStart() {
        synchronized (mLock) {
            // prepare providers
            initializeProvidersLocked();
        }
    }

    private void onAppOpChanged(String packageName) {
        synchronized (mLock) {
            for (Receiver receiver : mReceivers.values()) {
                if (receiver.mCallerIdentity.mPackageName.equals(packageName)) {
                    receiver.updateMonitoring(true);
                }
            }

            HashSet<String> affectedProviders = new HashSet<>(mRecordsByProvider.size());
            for (Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
                String provider = entry.getKey();
                for (UpdateRecord record : entry.getValue()) {
                    if (record.mReceiver.mCallerIdentity.mPackageName.equals(packageName)) {
                        affectedProviders.add(provider);
                    }
                }
            }
            for (String provider : affectedProviders) {
                applyRequirementsLocked(provider);
            }
        }
    }

    @GuardedBy("mLock")
    private void onPermissionsChangedLocked() {
        for (LocationProviderManager manager : mProviderManagers) {
            applyRequirementsLocked(manager);
        }
    }

    @GuardedBy("mLock")
    private void onBatterySaverModeChangedLocked(int newLocationMode) {
        if (mBatterySaverMode == newLocationMode) {
            return;
        }

        if (D) {
            Log.d(TAG,
                    "Battery Saver location mode changed from "
                            + locationPowerSaveModeToString(mBatterySaverMode) + " to "
                            + locationPowerSaveModeToString(newLocationMode));
        }

        mBatterySaverMode = newLocationMode;

        for (LocationProviderManager manager : mProviderManagers) {
            applyRequirementsLocked(manager);
        }
    }

    @GuardedBy("mLock")
    private void onScreenStateChangedLocked() {
        if (mBatterySaverMode == PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF) {
            for (LocationProviderManager manager : mProviderManagers) {
                applyRequirementsLocked(manager);
            }
        }
    }

    private void onLocationModeChanged(int userId) {
        boolean enabled = mSettingsHelper.isLocationEnabled(userId);
        LocationManager.invalidateLocalLocationEnabledCaches();

        if (D) {
            Log.d(TAG, "[u" + userId + "] location enabled = " + enabled);
        }

        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION)
                .putExtra(LocationManager.EXTRA_LOCATION_ENABLED, enabled)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));

        synchronized (mLock) {
            for (LocationProviderManager manager : mProviderManagers) {
                manager.onEnabledChangedLocked(userId);
            }
        }
    }

    @GuardedBy("mLock")
    private void onPackageDisappearedLocked(String packageName) {
        ArrayList<Receiver> deadReceivers = null;

        for (Receiver receiver : mReceivers.values()) {
            if (receiver.mCallerIdentity.mPackageName.equals(packageName)) {
                if (deadReceivers == null) {
                    deadReceivers = new ArrayList<>();
                }
                deadReceivers.add(receiver);
            }
        }

        // perform removal outside of mReceivers loop
        if (deadReceivers != null) {
            for (Receiver receiver : deadReceivers) {
                removeUpdatesLocked(receiver);
            }
        }
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        synchronized (mLock) {
            HashSet<String> affectedProviders = new HashSet<>(mRecordsByProvider.size());
            for (Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
                String provider = entry.getKey();
                for (UpdateRecord record : entry.getValue()) {
                    if (record.mReceiver.mCallerIdentity.mUid == uid
                            && record.mIsForegroundUid != foreground) {
                        record.updateForeground(foreground);

                        if (!isThrottlingExempt(record.mReceiver.mCallerIdentity)) {
                            affectedProviders.add(provider);
                        }
                    }
                }
            }
            for (String provider : affectedProviders) {
                applyRequirementsLocked(provider);
            }
        }
    }

    private void onBackgroundThrottleIntervalChanged() {
        synchronized (mLock) {
            for (LocationProviderManager manager : mProviderManagers) {
                applyRequirementsLocked(manager);
            }
        }
    }

    private void onBackgroundThrottleWhitelistChanged() {
        synchronized (mLock) {
            for (LocationProviderManager manager : mProviderManagers) {
                applyRequirementsLocked(manager);
            }
        }
    }

    private void onIgnoreSettingsWhitelistChanged() {
        synchronized (mLock) {
            for (LocationProviderManager manager : mProviderManagers) {
                applyRequirementsLocked(manager);
            }
        }
    }

    @GuardedBy("mLock")
    private void initializeProvidersLocked() {
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndRegister(
                mContext,
                NETWORK_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableNetworkLocationOverlay,
                com.android.internal.R.string.config_networkLocationProviderPackageName);
        if (networkProvider != null) {
            LocationProviderManager networkManager = new LocationProviderManager(NETWORK_PROVIDER);
            mProviderManagers.add(networkManager);
            networkManager.setRealProvider(networkProvider);
        } else {
            Log.w(TAG, "no network location provider found");
        }

        // ensure that a fused provider exists which will work in direct boot
        Preconditions.checkState(!mContext.getPackageManager().queryIntentServicesAsUser(
                new Intent(FUSED_LOCATION_SERVICE_ACTION),
                MATCH_DIRECT_BOOT_AWARE | MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM).isEmpty(),
                "Unable to find a direct boot aware fused location provider");

        // bind to fused provider
        LocationProviderProxy fusedProvider = LocationProviderProxy.createAndRegister(
                mContext,
                FUSED_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableFusedLocationOverlay,
                com.android.internal.R.string.config_fusedLocationProviderPackageName);
        if (fusedProvider != null) {
            LocationProviderManager fusedManager = new LocationProviderManager(FUSED_PROVIDER);
            mProviderManagers.add(fusedManager);
            fusedManager.setRealProvider(fusedProvider);
        } else {
            Log.e(TAG, "no fused location provider found");
        }

        // bind to geocoder provider
        mGeocodeProvider = GeocoderProxy.createAndRegister(mContext);
        if (mGeocodeProvider == null) {
            Log.e(TAG, "no geocoder provider found");
        }

        // bind to geofence proxy
        if (mGnssManagerService != null) {
            IGpsGeofenceHardware gpsGeofenceHardware = mGnssManagerService.getGpsGeofenceProxy();
            if (gpsGeofenceHardware != null) {
                GeofenceProxy provider = GeofenceProxy.createAndBind(mContext, gpsGeofenceHardware);
                if (provider == null) {
                    Log.e(TAG, "unable to bind to GeofenceProxy");
                }
            }
        }

        // bind to hardware activity recognition
        HardwareActivityRecognitionProxy hardwareActivityRecognitionProxy =
                HardwareActivityRecognitionProxy.createAndRegister(mContext);
        if (hardwareActivityRecognitionProxy == null) {
            Log.e(TAG, "unable to bind ActivityRecognitionProxy");
        }

        String[] testProviderStrings = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_testLocationProviders);
        for (String testProviderString : testProviderStrings) {
            String[] fragments = testProviderString.split(",");
            String name = fragments[0].trim();
            ProviderProperties properties = new ProviderProperties(
                    Boolean.parseBoolean(fragments[1]) /* requiresNetwork */,
                    Boolean.parseBoolean(fragments[2]) /* requiresSatellite */,
                    Boolean.parseBoolean(fragments[3]) /* requiresCell */,
                    Boolean.parseBoolean(fragments[4]) /* hasMonetaryCost */,
                    Boolean.parseBoolean(fragments[5]) /* supportsAltitude */,
                    Boolean.parseBoolean(fragments[6]) /* supportsSpeed */,
                    Boolean.parseBoolean(fragments[7]) /* supportsBearing */,
                    Integer.parseInt(fragments[8]) /* powerRequirement */,
                    Integer.parseInt(fragments[9]) /* accuracy */);
            addTestProvider(name, properties, mContext.getOpPackageName());
        }

        // initialize gnss last because it has no awareness of boot phases and blindly assumes that
        // all other location providers are loaded at initialization
        if (GnssManagerService.isGnssSupported()) {
            mGnssManagerService = new GnssManagerService(mContext, mSettingsHelper,
                    mAppForegroundHelper, mLocationUsageLogger);
            mGnssManagerService.onSystemReady();

            LocationProviderManager gnssManager = new LocationProviderManager(GPS_PROVIDER);
            mProviderManagers.add(gnssManager);
            gnssManager.setRealProvider(mGnssManagerService.getGnssLocationProvider());
        }
    }

    private void onUserChanged(@UserIdInt int userId, @UserListener.UserChange int change) {
        switch (change) {
            case UserListener.USER_SWITCHED:
                if (D) {
                    Log.d(TAG, "user " + userId + " current status changed");
                }
                synchronized (mLock) {
                    for (LocationProviderManager manager : mProviderManagers) {
                        manager.onEnabledChangedLocked(userId);
                    }
                }
                break;
            case UserListener.USER_STARTED:
                if (D) {
                    Log.d(TAG, "user " + userId + " started");
                }
                synchronized (mLock) {
                    for (LocationProviderManager manager : mProviderManagers) {
                        manager.onUserStarted(userId);
                    }
                }
                break;
            case UserListener.USER_STOPPED:
                if (D) {
                    Log.d(TAG, "user " + userId + " stopped");
                }
                synchronized (mLock) {
                    for (LocationProviderManager manager : mProviderManagers) {
                        manager.onUserStopped(userId);
                    }
                }
                break;
        }
    }

    /**
     * Location provider manager, manages a LocationProvider.
     */
    class LocationProviderManager implements MockableLocationProvider.Listener {

        private final String mName;

        private final LocationFudger mLocationFudger;

        // if the provider is enabled for a given user id - null or not present means unknown
        @GuardedBy("mLock")
        private final SparseArray<Boolean> mEnabled;

        // last location for a given user
        @GuardedBy("mLock")
        private final SparseArray<Location> mLastLocation;

        // last coarse location for a given user
        @GuardedBy("mLock")
        private final SparseArray<Location> mLastCoarseLocation;

        // acquiring mLock makes operations on mProvider atomic, but is otherwise unnecessary
        protected final MockableLocationProvider mProvider;

        private LocationProviderManager(String name) {
            mName = name;
            mLocationFudger = new LocationFudger(mSettingsHelper.getCoarseLocationAccuracyM());
            mEnabled = new SparseArray<>(2);
            mLastLocation = new SparseArray<>(2);
            mLastCoarseLocation = new SparseArray<>(2);

            // initialize last since this lets our reference escape
            mProvider = new MockableLocationProvider(mLock, this);
        }

        public String getName() {
            return mName;
        }

        public boolean hasProvider() {
            return mProvider.getProvider() != null;
        }

        public void setRealProvider(AbstractLocationProvider provider) {
            mProvider.setRealProvider(provider);
        }

        public void setMockProvider(@Nullable MockProvider provider) {
            synchronized (mLock) {
                mProvider.setMockProvider(provider);

                // when removing a mock provider, also clear any mock last locations and reset the
                // location fudger. the mock provider could have been used to infer the current
                // location fudger offsets.
                if (provider == null) {
                    for (int i = 0; i < mLastLocation.size(); i++) {
                        Location lastLocation = mLastLocation.valueAt(i);
                        if (lastLocation != null && lastLocation.isFromMockProvider()) {
                            mLastLocation.setValueAt(i, null);
                        }
                    }

                    for (int i = 0; i < mLastCoarseLocation.size(); i++) {
                        Location lastCoarseLocation = mLastCoarseLocation.valueAt(i);
                        if (lastCoarseLocation != null && lastCoarseLocation.isFromMockProvider()) {
                            mLastCoarseLocation.setValueAt(i, null);
                        }
                    }

                    mLocationFudger.resetOffsets();
                }
            }
        }

        public Set<String> getPackages() {
            return mProvider.getState().providerPackageNames;
        }

        @Nullable
        public ProviderProperties getProperties() {
            return mProvider.getState().properties;
        }

        @Nullable
        public Location getLastFineLocation(int userId) {
            synchronized (mLock) {
                return mLastLocation.get(userId);
            }
        }

        @Nullable
        public Location getLastCoarseLocation(int userId) {
            synchronized (mLock) {
                return mLastCoarseLocation.get(userId);
            }
        }

        public void injectLastLocation(Location location, int userId) {
            synchronized (mLock) {
                if (mLastLocation.get(userId) == null) {
                    setLastLocation(location, userId);
                }
            }
        }

        private void setLastLocation(Location location, int userId) {
            synchronized (mLock) {
                mLastLocation.put(userId, location);

                // update last coarse interval only if enough time has passed
                long timeDeltaMs = Long.MAX_VALUE;
                Location coarseLocation = mLastCoarseLocation.get(userId);
                if (coarseLocation != null) {
                    timeDeltaMs = NANOSECONDS.toMillis(location.getElapsedRealtimeNanos())
                            - NANOSECONDS.toMillis(coarseLocation.getElapsedRealtimeNanos());
                }
                if (timeDeltaMs > FASTEST_COARSE_INTERVAL_MS) {
                    mLastCoarseLocation.put(userId, mLocationFudger.createCoarse(location));
                }
            }
        }

        public void setMockProviderAllowed(boolean enabled) {
            synchronized (mLock) {
                if (!mProvider.isMock()) {
                    throw new IllegalArgumentException(mName + " provider is not a test provider");
                }

                mProvider.setMockProviderAllowed(enabled);
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

                mProvider.setMockProviderLocation(location);
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

        public void setRequest(ProviderRequest request) {
            mProvider.setRequest(request);
        }

        public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
            mProvider.sendExtraCommand(uid, pid, command, extras);
        }

        @GuardedBy("mLock")
        @Override
        public void onReportLocation(Location location) {
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

            // update last location if the provider is enabled or if servicing a bypass request
            boolean locationSettingsIgnored = mProvider.getCurrentRequest().locationSettingsIgnored;
            for (int userId : mUserInfoHelper.getCurrentUserIds()) {
                if (locationSettingsIgnored || isEnabled(userId)) {
                    setLastLocation(location, userId);
                }
            }

            handleLocationChangedLocked(this, location, mLocationFudger.createCoarse(location));
        }

        @GuardedBy("mLock")
        @Override
        public void onReportLocation(List<Location> locations) {
            if (mGnssManagerService == null || !GPS_PROVIDER.equals(mName)) {
                return;
            }

            mGnssManagerService.onReportLocation(locations);
        }

        @GuardedBy("mLock")
        @Override
        public void onStateChanged(State oldState, State newState) {
            if (oldState.allowed != newState.allowed) {
                onEnabledChangedLocked(UserHandle.USER_ALL);
            }
        }

        public void onUserStarted(int userId) {
            synchronized (mLock) {
                // clear the user's enabled state in order to force a reevalution of whether the
                // provider is enabled or disabled for the given user. we clear the user's state
                // first to ensure that a user starting never causes any change notifications. it's
                // possible for us to observe a user before we observe it's been started (for
                // example, another component gets a user started notification before us and
                // registers a location request immediately), which would cause us to already have
                // some state in place. when we eventually do get the user started notification
                // ourselves we don't want to send a change notification based on the prior state
                mEnabled.put(userId, null);
                onEnabledChangedLocked(userId);
            }
        }

        public void onUserStopped(int userId) {
            synchronized (mLock) {
                mEnabled.remove(userId);
                mLastLocation.remove(userId);
                mLastCoarseLocation.remove(userId);
            }
        }

        public boolean isEnabled(int userId) {
            if (userId == UserHandle.USER_NULL) {
                // used during initialization - ignore since many lower level operations (checking
                // settings for instance) do not support the null user
                return false;
            }

            synchronized (mLock) {
                Boolean enabled = mEnabled.get(userId);
                if (enabled == null) {
                    // this generally shouldn't occur, but might be possible due to race conditions
                    // on when we are notified of new users
                    Log.w(TAG, mName + " provider saw user " + userId + " unexpectedly");
                    onEnabledChangedLocked(userId);
                    enabled = Objects.requireNonNull(mEnabled.get(userId));
                }

                return enabled;
            }
        }

        @GuardedBy("mLock")
        public void onEnabledChangedLocked(int userId) {
            if (userId == UserHandle.USER_NULL) {
                // used during initialization - ignore since many lower level operations (checking
                // settings for instance) do not support the null user
                return;
            } else if (userId == UserHandle.USER_ALL) {
                // we know enabled changes can only happen for current users since providers are
                // always disabled for all non-current users
                for (int currentUserId : mUserInfoHelper.getCurrentUserIds()) {
                    onEnabledChangedLocked(currentUserId);
                }
                return;
            }

            // if any property that contributes to "enabled" here changes state, it MUST result
            // in a direct or indrect call to onEnabledChangedLocked. this allows the provider to
            // guarantee that it will always eventually reach the correct state.
            boolean enabled = mProvider.getState().allowed
                    && mUserInfoHelper.isCurrentUserId(userId)
                    && mSettingsHelper.isLocationEnabled(userId);

            Boolean wasEnabled = mEnabled.get(userId);
            if (wasEnabled != null && wasEnabled == enabled) {
                return;
            }

            mEnabled.put(userId, enabled);

            if (D) {
                Log.d(TAG, "[u" + userId + "] " + mName + " provider enabled = " + enabled);
            }

            // clear last locations if we become disabled and if not servicing a bypass request
            if (!enabled && !mProvider.getCurrentRequest().locationSettingsIgnored) {
                mLastLocation.put(userId, null);
                mLastCoarseLocation.put(userId, null);
            }

            // update LOCATION_PROVIDERS_ALLOWED for best effort backwards compatibility
            mSettingsHelper.setLocationProviderAllowed(mName, enabled, userId);

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
            }

            updateProviderEnabledLocked(this, enabled);
        }

        public void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
            synchronized (mLock) {
                pw.print(mName + " provider");
                if (mProvider.isMock()) {
                    pw.print(" [mock]");
                }
                pw.println(":");

                pw.increaseIndent();

                // for now we only dump for the parent user
                int userId = mUserInfoHelper.getCurrentUserIds()[0];
                pw.println("last location=" + mLastLocation.get(userId));
                pw.println("last coarse location=" + mLastCoarseLocation.get(userId));
                pw.println("enabled=" + isEnabled(userId));
            }

            mProvider.dump(fd, pw, args);

            pw.decreaseIndent();
        }
    }

    class PassiveLocationProviderManager extends LocationProviderManager {

        private PassiveLocationProviderManager() {
            super(PASSIVE_PROVIDER);
        }

        @Override
        public void setRealProvider(AbstractLocationProvider provider) {
            Preconditions.checkArgument(provider instanceof PassiveProvider);
            super.setRealProvider(provider);
        }

        @Override
        public void setMockProvider(@Nullable MockProvider provider) {
            if (provider != null) {
                throw new IllegalArgumentException("Cannot mock the passive provider");
            }
        }

        public void updateLocation(Location location) {
            synchronized (mLock) {
                PassiveProvider passiveProvider = (PassiveProvider) mProvider.getProvider();
                Preconditions.checkState(passiveProvider != null);

                long identity = Binder.clearCallingIdentity();
                try {
                    passiveProvider.updateLocation(location);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver extends LocationManagerServiceUtils.LinkedListenerBase implements
            PendingIntent.OnFinished {
        private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
        private final int mAllowedResolutionLevel;  // resolution level allowed to receiver

        private final ILocationListener mListener;
        final PendingIntent mPendingIntent;
        final WorkSource mWorkSource; // WorkSource for battery blame, or null to assign to caller.
        private final boolean mHideFromAppOps; // True if AppOps should not monitor this receiver.
        private final Object mKey;

        final HashMap<String, UpdateRecord> mUpdateRecords = new HashMap<>();

        // True if app ops has started monitoring this receiver for locations.
        private boolean mOpMonitoring;
        // True if app ops has started monitoring this receiver for high power (gps) locations.
        private boolean mOpHighPowerMonitoring;
        private int mPendingBroadcasts;
        PowerManager.WakeLock mWakeLock;

        private Receiver(ILocationListener listener, PendingIntent intent, int pid, int uid,
                String packageName, @Nullable String featureId, WorkSource workSource,
                boolean hideFromAppOps, @NonNull String listenerIdentifier) {
            super(new CallerIdentity(uid, pid, packageName, featureId, listenerIdentifier),
                    "LocationListener");
            mListener = listener;
            mPendingIntent = intent;
            if (listener != null) {
                mKey = listener.asBinder();
            } else {
                mKey = intent;
            }
            mAllowedResolutionLevel = getAllowedResolutionLevel(pid, uid);
            if (workSource != null && workSource.isEmpty()) {
                workSource = null;
            }
            mWorkSource = workSource;
            mHideFromAppOps = hideFromAppOps;

            updateMonitoring(true);

            // construct/configure wakelock
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
            if (workSource == null) {
                workSource = new WorkSource(mCallerIdentity.mUid, mCallerIdentity.mPackageName);
            }
            mWakeLock.setWorkSource(workSource);

            // For a non-reference counted wakelock, each acquire will reset the timeout, and we
            // only need to release it once.
            mWakeLock.setReferenceCounted(false);
        }

        @Override
        public boolean equals(Object otherObj) {
            return (otherObj instanceof Receiver) && mKey.equals(((Receiver) otherObj).mKey);
        }

        @Override
        public int hashCode() {
            return mKey.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append("Reciever[");
            s.append(Integer.toHexString(System.identityHashCode(this)));
            if (mListener != null) {
                s.append(" listener");
            } else {
                s.append(" intent");
            }
            for (String p : mUpdateRecords.keySet()) {
                s.append(" ").append(mUpdateRecords.get(p).toString());
            }
            s.append(" monitoring location: ").append(mOpMonitoring);
            s.append("]");
            return s.toString();
        }

        /**
         * Update AppOp monitoring for this receiver.
         *
         * @param allow If true receiver is currently active, if false it's been removed.
         */
        public void updateMonitoring(boolean allow) {
            if (mHideFromAppOps) {
                return;
            }

            boolean requestingLocation = false;
            boolean requestingHighPowerLocation = false;
            if (allow) {
                // See if receiver has any enabled update records.  Also note if any update records
                // are high power (has a high power provider with an interval under a threshold).
                for (UpdateRecord updateRecord : mUpdateRecords.values()) {
                    LocationProviderManager manager = getLocationProviderManager(
                            updateRecord.mProvider);
                    if (manager == null) {
                        continue;
                    }
                    if (!manager.isEnabled(UserHandle.getUserId(mCallerIdentity.mUid))
                            && !isSettingsExempt(updateRecord)) {
                        continue;
                    }

                    requestingLocation = true;
                    ProviderProperties properties = manager.getProperties();
                    if (properties != null
                            && properties.mPowerRequirement == Criteria.POWER_HIGH
                            && updateRecord.mRequest.getInterval() < HIGH_POWER_INTERVAL_MS) {
                        requestingHighPowerLocation = true;
                        break;
                    }
                }
            }

            // First update monitoring of any location request (including high power).
            mOpMonitoring = updateMonitoring(
                    requestingLocation,
                    mOpMonitoring,
                    AppOpsManager.OP_MONITOR_LOCATION);

            // Now update monitoring of high power requests only.
            boolean wasHighPowerMonitoring = mOpHighPowerMonitoring;
            mOpHighPowerMonitoring = updateMonitoring(
                    requestingHighPowerLocation,
                    mOpHighPowerMonitoring,
                    AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION);
            if (mOpHighPowerMonitoring != wasHighPowerMonitoring) {
                // Send an intent to notify that a high power request has been added/removed.
                Intent intent = new Intent(LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        }

        /**
         * Update AppOps monitoring for a single location request and op type.
         *
         * @param allowMonitoring     True if monitoring is allowed for this request/op.
         * @param currentlyMonitoring True if AppOps is currently monitoring this request/op.
         * @param op                  AppOps code for the op to update.
         * @return True if monitoring is on for this request/op after updating.
         */
        private boolean updateMonitoring(boolean allowMonitoring, boolean currentlyMonitoring,
                int op) {
            if (!currentlyMonitoring) {
                if (allowMonitoring) {
                    return mAppOps.startOpNoThrow(op, mCallerIdentity.mUid,
                            mCallerIdentity.mPackageName, false, mCallerIdentity.mFeatureId, null)
                            == AppOpsManager.MODE_ALLOWED;
                }
            } else {
                if (!allowMonitoring
                        || mAppOps.checkOpNoThrow(op, mCallerIdentity.mUid,
                        mCallerIdentity.mPackageName) != AppOpsManager.MODE_ALLOWED) {
                    mAppOps.finishOp(op, mCallerIdentity.mUid, mCallerIdentity.mPackageName);
                    return false;
                }
            }

            return currentlyMonitoring;
        }

        public boolean isListener() {
            return mListener != null;
        }

        public boolean isPendingIntent() {
            return mPendingIntent != null;
        }

        public ILocationListener getListener() {
            if (mListener != null) {
                return mListener;
            }
            throw new IllegalStateException("Request for non-existent listener");
        }

        public boolean callLocationChangedLocked(Location location) {
            if (mListener != null) {
                try {
                    mListener.onLocationChanged(new Location(location));
                    // call this after broadcasting so we do not increment
                    // if we throw an exception.
                    incrementPendingBroadcastsLocked();
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent locationChanged = new Intent();
                locationChanged.putExtra(LocationManager.KEY_LOCATION_CHANGED,
                        new Location(location));
                try {
                    mPendingIntent.send(mContext, 0, locationChanged, this, mHandler,
                            getResolutionPermission(mAllowedResolutionLevel),
                            PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                    // call this after broadcasting so we do not increment
                    // if we throw an exception.
                    incrementPendingBroadcastsLocked();
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        private boolean callProviderEnabledLocked(String provider, boolean enabled) {
            // First update AppOp monitoring.
            // An app may get/lose location access as providers are enabled/disabled.
            updateMonitoring(true);

            if (mListener != null) {
                try {
                    if (enabled) {
                        mListener.onProviderEnabled(provider);
                    } else {
                        mListener.onProviderDisabled(provider);
                    }
                    // call this after broadcasting so we do not increment
                    // if we throw an exception.
                    incrementPendingBroadcastsLocked();
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent providerIntent = new Intent();
                providerIntent.putExtra(LocationManager.KEY_PROVIDER_ENABLED, enabled);
                try {
                    mPendingIntent.send(mContext, 0, providerIntent, this, mHandler,
                            getResolutionPermission(mAllowedResolutionLevel),
                            PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                    // call this after broadcasting so we do not increment
                    // if we throw an exception.
                    incrementPendingBroadcastsLocked();
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        public void callRemovedLocked() {
            if (mListener != null) {
                try {
                    mListener.onRemoved();
                } catch (RemoteException e) {
                    // doesn't matter
                }
            }
        }

        @Override
        public void binderDied() {
            if (D) Log.d(TAG, "Remote " + mListenerName + " died.");

            synchronized (mLock) {
                removeUpdatesLocked(this);
                clearPendingBroadcastsLocked();
            }
        }

        @Override
        public void onSendFinished(PendingIntent pendingIntent, Intent intent,
                int resultCode, String resultData, Bundle resultExtras) {
            synchronized (mLock) {
                decrementPendingBroadcastsLocked();
            }
        }

        // this must be called while synchronized by caller in a synchronized block
        // containing the sending of the broadcaset
        private void incrementPendingBroadcastsLocked() {
            mPendingBroadcasts++;
            // so wakelock calls will succeed
            long identity = Binder.clearCallingIdentity();
            try {
                mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void decrementPendingBroadcastsLocked() {
            if (--mPendingBroadcasts == 0) {
                // so wakelock calls will succeed
                long identity = Binder.clearCallingIdentity();
                try {
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        public void clearPendingBroadcastsLocked() {
            if (mPendingBroadcasts > 0) {
                mPendingBroadcasts = 0;
                // so wakelock calls will succeed
                long identity = Binder.clearCallingIdentity();
                try {
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    @Override
    public void locationCallbackFinished(ILocationListener listener) {
        //Do not use getReceiverLocked here as that will add the ILocationListener to
        //the receiver list if it is not found.  If it is not found then the
        //LocationListener was removed when it had a pending broadcast and should
        //not be added back.
        synchronized (mLock) {
            Receiver receiver = mReceivers.get(listener.asBinder());
            if (receiver != null) {
                receiver.decrementPendingBroadcastsLocked();
            }
        }
    }

    @Override
    public int getGnssYearOfHardware() {
        return mGnssManagerService == null ? 0 : mGnssManagerService.getGnssYearOfHardware();
    }

    @Override
    @Nullable
    public String getGnssHardwareModelName() {
        return mGnssManagerService == null ? "" : mGnssManagerService.getGnssHardwareModelName();
    }

    @Override
    public int getGnssBatchSize(String packageName) {
        return mGnssManagerService == null ? 0 : mGnssManagerService.getGnssBatchSize(packageName);
    }

    @Override
    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName,
            String featureId, String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        return mGnssManagerService != null && mGnssManagerService.addGnssBatchingCallback(
                callback, packageName, featureId, listenerIdentifier);
    }

    @Override
    public void removeGnssBatchingCallback() {
        if (mGnssManagerService != null) mGnssManagerService.removeGnssBatchingCallback();
    }

    @Override
    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        return mGnssManagerService != null && mGnssManagerService.startGnssBatch(periodNanos,
                wakeOnFifoFull, packageName);
    }

    @Override
    public void flushGnssBatch(String packageName) {
        if (mGnssManagerService != null) mGnssManagerService.flushGnssBatch(packageName);
    }

    @Override
    public boolean stopGnssBatch() {
        return mGnssManagerService != null && mGnssManagerService.stopGnssBatch();
    }

    @Nullable
    private LocationProviderManager getLocationProviderManager(String providerName) {
        for (LocationProviderManager manager : mProviderManagers) {
            if (providerName.equals(manager.getName())) {
                return manager;
            }
        }

        return null;
    }

    private String getResolutionPermission(int resolutionLevel) {
        switch (resolutionLevel) {
            case RESOLUTION_LEVEL_FINE:
                return ACCESS_FINE_LOCATION;
            case RESOLUTION_LEVEL_COARSE:
                return ACCESS_COARSE_LOCATION;
            default:
                return null;
        }
    }

    private int getAllowedResolutionLevel(int pid, int uid) {
        if (mContext.checkPermission(ACCESS_FINE_LOCATION, pid, uid) == PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_FINE;
        } else if (mContext.checkPermission(ACCESS_COARSE_LOCATION, pid, uid)
                == PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_COARSE;
        } else {
            return RESOLUTION_LEVEL_NONE;
        }
    }

    private int getCallerAllowedResolutionLevel() {
        return getAllowedResolutionLevel(Binder.getCallingPid(), Binder.getCallingUid());
    }

    private boolean checkCallingOrSelfLocationPermission() {
        return mContext.checkCallingOrSelfPermission(ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED
                || mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                == PERMISSION_GRANTED;
    }

    private void enforceCallingOrSelfLocationPermission() {
        if (checkCallingOrSelfLocationPermission()) {
            return;
        }

        throw new SecurityException("uid " + Binder.getCallingUid() + " does not have "
                + ACCESS_COARSE_LOCATION + " or " + ACCESS_FINE_LOCATION + ".");
    }

    private void enforceCallingOrSelfPackageName(String packageName) {
        int uid = Binder.getCallingUid();
        if (ArrayUtils.contains(mPackageManager.getPackagesForUid(uid), packageName)) {
            return;
        }

        throw new SecurityException("invalid package \"" + packageName + "\" for uid " + uid);
    }

    public static int resolutionLevelToOp(int allowedResolutionLevel) {
        if (allowedResolutionLevel != RESOLUTION_LEVEL_NONE) {
            if (allowedResolutionLevel == RESOLUTION_LEVEL_COARSE) {
                return AppOpsManager.OP_COARSE_LOCATION;
            } else {
                return AppOpsManager.OP_FINE_LOCATION;
            }
        }
        return -1;
    }

    private static String resolutionLevelToOpStr(int allowedResolutionLevel) {
        switch (allowedResolutionLevel) {
            case RESOLUTION_LEVEL_COARSE:
                return AppOpsManager.OPSTR_COARSE_LOCATION;
            case RESOLUTION_LEVEL_FINE:
                // fall through
            case RESOLUTION_LEVEL_NONE:
                // fall through
            default:
                // Use the most restrictive ops if not sure.
                return AppOpsManager.OPSTR_FINE_LOCATION;
        }
    }

    private boolean reportLocationAccessNoThrow(int pid, int uid, String packageName,
            @Nullable String featureId, int allowedResolutionLevel, @Nullable String message) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        if (op >= 0) {
            if (mAppOps.noteOpNoThrow(op, uid, packageName, featureId, message)
                    != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
        }

        return getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    private boolean checkLocationAccess(int pid, int uid, String packageName,
            int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        if (op >= 0) {
            if (mAppOps.checkOp(op, uid, packageName) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
        }

        return getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    /**
     * Returns all providers by name, including passive and the ones that are not permitted to
     * be accessed by the calling activity or are currently disabled, but excluding fused.
     */
    @Override
    public List<String> getAllProviders() {
        ArrayList<String> providers = new ArrayList<>(mProviderManagers.size());
        for (LocationProviderManager manager : mProviderManagers) {
            if (FUSED_PROVIDER.equals(manager.getName())) {
                continue;
            }
            providers.add(manager.getName());
        }
        return providers;
    }

    /**
     * Return all providers by name, that match criteria and are optionally
     * enabled.
     * Can return passive provider, but never returns fused provider.
     */
    @Override
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        if (!checkCallingOrSelfLocationPermission()) {
            return Collections.emptyList();
        }

        synchronized (mLock) {
            ArrayList<String> providers = new ArrayList<>(mProviderManagers.size());
            for (LocationProviderManager manager : mProviderManagers) {
                String name = manager.getName();
                if (FUSED_PROVIDER.equals(name)) {
                    continue;
                }
                if (enabledOnly && !manager.isEnabled(UserHandle.getCallingUserId())) {
                    continue;
                }
                if (criteria != null
                        && !android.location.LocationProvider.propertiesMeetCriteria(
                        name, manager.getProperties(), criteria)) {
                    continue;
                }
                providers.add(name);
            }
            return providers;
        }
    }

    /**
     * Return the name of the best provider given a Criteria object.
     * This method has been deprecated from the public API,
     * and the whole LocationProvider (including #meetsCriteria)
     * has been deprecated as well. So this method now uses
     * some simplified logic.
     */
    @Override
    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers = getProviders(criteria, enabledOnly);
        if (providers.isEmpty()) {
            providers = getProviders(null, enabledOnly);
        }

        if (!providers.isEmpty()) {
            if (providers.contains(GPS_PROVIDER)) {
                return GPS_PROVIDER;
            } else if (providers.contains(NETWORK_PROVIDER)) {
                return NETWORK_PROVIDER;
            } else {
                return providers.get(0);
            }
        }

        return null;
    }

    @GuardedBy("mLock")
    private void updateProviderEnabledLocked(LocationProviderManager manager, boolean enabled) {
        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(manager.getName());
        if (records != null) {
            for (UpdateRecord record : records) {
                if (!mUserInfoHelper.isCurrentUserId(
                        UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                    continue;
                }

                // requests that ignore location settings will never provide notifications
                if (isSettingsExempt(record)) {
                    continue;
                }

                // Sends a notification message to the receiver
                if (!record.mReceiver.callProviderEnabledLocked(manager.getName(), enabled)) {
                    if (deadReceivers == null) {
                        deadReceivers = new ArrayList<>();
                    }
                    deadReceivers.add(record.mReceiver);
                }
            }
        }

        if (deadReceivers != null) {
            for (int i = deadReceivers.size() - 1; i >= 0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }

        applyRequirementsLocked(manager);
    }

    @GuardedBy("mLock")
    private void applyRequirementsLocked(String providerName) {
        LocationProviderManager manager = getLocationProviderManager(providerName);
        if (manager != null) {
            applyRequirementsLocked(manager);
        }
    }

    @GuardedBy("mLock")
    private void applyRequirementsLocked(LocationProviderManager manager) {
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(manager.getName());
        ProviderRequest.Builder providerRequest = new ProviderRequest.Builder();

        // if provider is not active, it should not respond to requests

        if (mProviderManagers.contains(manager) && records != null && !records.isEmpty()) {
            long backgroundThrottleInterval = mSettingsHelper.getBackgroundThrottleIntervalMs();

            ArrayList<LocationRequest> requests = new ArrayList<>(records.size());

            final boolean isForegroundOnlyMode =
                    mBatterySaverMode == PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
            final boolean shouldThrottleRequests =
                    mBatterySaverMode
                            == PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF
                            && !mPowerManager.isInteractive();
            // initialize the low power mode to true and set to false if any of the records requires
            providerRequest.setLowPowerMode(true);
            for (UpdateRecord record : records) {
                int userId = UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid);
                if (!mUserInfoHelper.isCurrentUserId(userId)) {
                    continue;
                }
                if (!checkLocationAccess(
                        record.mReceiver.mCallerIdentity.mPid,
                        record.mReceiver.mCallerIdentity.mUid,
                        record.mReceiver.mCallerIdentity.mPackageName,
                        record.mReceiver.mAllowedResolutionLevel)) {
                    continue;
                }
                final boolean isBatterySaverDisablingLocation = shouldThrottleRequests
                        || (isForegroundOnlyMode && !record.mIsForegroundUid);
                if (!manager.isEnabled(userId) || isBatterySaverDisablingLocation) {
                    if (isSettingsExempt(record)) {
                        providerRequest.setLocationSettingsIgnored(true);
                        providerRequest.setLowPowerMode(false);
                    } else {
                        continue;
                    }
                }

                LocationRequest locationRequest = record.mRealRequest;
                long interval = locationRequest.getInterval();


                // if we're forcing location, don't apply any throttling
                if (!providerRequest.isLocationSettingsIgnored() && !isThrottlingExempt(
                        record.mReceiver.mCallerIdentity)) {
                    if (!record.mIsForegroundUid) {
                        interval = Math.max(interval, backgroundThrottleInterval);
                    }
                    if (interval != locationRequest.getInterval()) {
                        locationRequest = new LocationRequest(locationRequest);
                        locationRequest.setInterval(interval);
                    }
                }

                record.mRequest = locationRequest;
                requests.add(locationRequest);
                if (!locationRequest.isLowPowerMode()) {
                    providerRequest.setLowPowerMode(false);
                }
                if (interval < providerRequest.getInterval()) {
                    providerRequest.setInterval(interval);
                }
            }

            providerRequest.setLocationRequests(requests);

            if (providerRequest.getInterval() < Long.MAX_VALUE) {
                // calculate who to blame for power
                // This is somewhat arbitrary. We pick a threshold interval
                // that is slightly higher that the minimum interval, and
                // spread the blame across all applications with a request
                // under that threshold.
                // TODO: overflow
                long thresholdInterval = (providerRequest.getInterval() + 1000) * 3 / 2;
                for (UpdateRecord record : records) {
                    if (mUserInfoHelper.isCurrentUserId(
                            UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                        LocationRequest locationRequest = record.mRequest;

                        // Don't assign battery blame for update records whose
                        // client has no permission to receive location data.
                        if (!providerRequest.getLocationRequests().contains(locationRequest)) {
                            continue;
                        }

                        if (locationRequest.getInterval() <= thresholdInterval) {
                            if (record.mReceiver.mWorkSource != null
                                    && isValidWorkSource(record.mReceiver.mWorkSource)) {
                                providerRequest.getWorkSource().add(record.mReceiver.mWorkSource);
                            } else {
                                // Assign blame to caller if there's no WorkSource associated with
                                // the request or if it's invalid.
                                providerRequest.getWorkSource().add(
                                        record.mReceiver.mCallerIdentity.mUid,
                                        record.mReceiver.mCallerIdentity.mPackageName);
                            }
                        }
                    }
                }
            }
        }

        manager.setRequest(providerRequest.build());
    }

    /**
     * Whether a given {@code WorkSource} associated with a Location request is valid.
     */
    private static boolean isValidWorkSource(WorkSource workSource) {
        if (workSource.size() > 0) {
            // If the WorkSource has one or more non-chained UIDs, make sure they're accompanied
            // by tags.
            return workSource.getPackageName(0) != null;
        } else {
            // For now, make sure callers have supplied an attribution tag for use with
            // AppOpsManager. This might be relaxed in the future.
            final List<WorkChain> workChains = workSource.getWorkChains();
            return workChains != null && !workChains.isEmpty() &&
                    workChains.get(0).getAttributionTag() != null;
        }
    }

    @Override
    public String[] getBackgroundThrottlingWhitelist() {
        return mSettingsHelper.getBackgroundThrottlePackageWhitelist().toArray(new String[0]);
    }

    @Override
    public String[] getIgnoreSettingsWhitelist() {
        return mSettingsHelper.getIgnoreSettingsPackageWhitelist().toArray(new String[0]);
    }

    private boolean isThrottlingExempt(CallerIdentity callerIdentity) {
        if (callerIdentity.mUid == Process.SYSTEM_UID) {
            return true;
        }

        if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                callerIdentity.mPackageName)) {
            return true;
        }

        return mLocalService.isProviderPackage(callerIdentity.mPackageName);

    }

    private boolean isSettingsExempt(UpdateRecord record) {
        if (!record.mRealRequest.isLocationSettingsIgnored()) {
            return false;
        }

        if (mSettingsHelper.getIgnoreSettingsPackageWhitelist().contains(
                record.mReceiver.mCallerIdentity.mPackageName)) {
            return true;
        }

        return mLocalService.isProviderPackage(record.mReceiver.mCallerIdentity.mPackageName);

    }

    private class UpdateRecord {
        final String mProvider;
        private final LocationRequest mRealRequest;  // original request from client
        LocationRequest mRequest;  // possibly throttled version of the request
        private final Receiver mReceiver;
        private boolean mIsForegroundUid;
        private Location mLastFixBroadcast;
        private Throwable mStackTrace;  // for debugging only
        private long mExpirationRealtimeMs;

        /**
         * Note: must be constructed with lock held.
         */
        private UpdateRecord(String provider, LocationRequest request, Receiver receiver) {
            mExpirationRealtimeMs = request.getExpirationRealtimeMs(SystemClock.elapsedRealtime());
            mProvider = provider;
            mRealRequest = request;
            mRequest = request;
            mReceiver = receiver;
            mIsForegroundUid = mAppForegroundHelper.isAppForeground(mReceiver.mCallerIdentity.mUid);

            if (D && receiver.mCallerIdentity.mPid == Process.myPid()) {
                mStackTrace = new Throwable();
            }

            ArrayList<UpdateRecord> records = mRecordsByProvider.computeIfAbsent(provider,
                    k -> new ArrayList<>());
            if (!records.contains(this)) {
                records.add(this);
            }

            // Update statistics for historical location requests by package/provider
            mRequestStatistics.startRequesting(
                    mReceiver.mCallerIdentity.mPackageName, mReceiver.mCallerIdentity.mFeatureId,
                    provider, request.getInterval(), mIsForegroundUid);
        }

        /**
         * Method to be called when record changes foreground/background
         */
        private void updateForeground(boolean isForeground) {
            mIsForegroundUid = isForeground;
            mRequestStatistics.updateForeground(
                    mReceiver.mCallerIdentity.mPackageName, mReceiver.mCallerIdentity.mFeatureId,
                    mProvider, isForeground);
        }

        /**
         * Method to be called when a record will no longer be used.
         */
        private void disposeLocked(boolean removeReceiver) {
            String packageName = mReceiver.mCallerIdentity.mPackageName;
            mRequestStatistics.stopRequesting(packageName, mReceiver.mCallerIdentity.mFeatureId,
                    mProvider);

            mLocationUsageLogger.logLocationApiUsage(
                    LocationStatsEnums.USAGE_ENDED,
                    LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                    packageName,
                    mRealRequest,
                    mReceiver.isListener(),
                    mReceiver.isPendingIntent(),
                    /* geofence= */ null,
                    mAppForegroundHelper.getImportance(mReceiver.mCallerIdentity.mUid));

            // remove from mRecordsByProvider
            ArrayList<UpdateRecord> globalRecords = mRecordsByProvider.get(this.mProvider);
            if (globalRecords != null) {
                globalRecords.remove(this);
            }

            if (!removeReceiver) return;  // the caller will handle the rest

            // remove from Receiver#mUpdateRecords
            HashMap<String, UpdateRecord> receiverRecords = mReceiver.mUpdateRecords;
            receiverRecords.remove(this.mProvider);

            // and also remove the Receiver if it has no more update records
            if (receiverRecords.size() == 0) {
                removeUpdatesLocked(mReceiver);
            }
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder("UpdateRecord[");
            b.append(mProvider).append(" ");
            b.append(mReceiver.mCallerIdentity.mPackageName);
            String featureId = mReceiver.mCallerIdentity.mFeatureId;
            if (featureId != null) {
                b.append(" ").append(featureId).append(" ");
            }
            b.append("(").append(mReceiver.mCallerIdentity.mUid);
            if (mIsForegroundUid) {
                b.append(" foreground");
            } else {
                b.append(" background");
            }
            b.append(") ");
            b.append(mRealRequest).append(" ").append(mReceiver.mWorkSource);

            if (mStackTrace != null) {
                ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                mStackTrace.printStackTrace(new PrintStream(tmp));
                b.append("\n\n").append(tmp.toString()).append("\n");
            }

            b.append("]");
            return b.toString();
        }
    }

    @GuardedBy("mLock")
    private Receiver getReceiverLocked(ILocationListener listener, int pid, int uid,
            String packageName, @Nullable String featureId, WorkSource workSource,
            boolean hideFromAppOps, @NonNull String listenerIdentifier) {
        IBinder binder = listener.asBinder();
        Receiver receiver = mReceivers.get(binder);
        if (receiver == null) {
            receiver = new Receiver(listener, null, pid, uid, packageName, featureId, workSource,
                    hideFromAppOps, listenerIdentifier);
            if (!receiver.linkToListenerDeathNotificationLocked(
                    receiver.getListener().asBinder())) {
                return null;
            }
            mReceivers.put(binder, receiver);
        }
        return receiver;
    }

    @GuardedBy("mLock")
    private Receiver getReceiverLocked(PendingIntent intent, int pid, int uid, String packageName,
            @Nullable String featureId, WorkSource workSource, boolean hideFromAppOps,
            @NonNull String listenerIdentifier) {
        Receiver receiver = mReceivers.get(intent);
        if (receiver == null) {
            receiver = new Receiver(null, intent, pid, uid, packageName, featureId, workSource,
                    hideFromAppOps, listenerIdentifier);
            mReceivers.put(intent, receiver);
        }
        return receiver;
    }

    /**
     * Creates a LocationRequest based upon the supplied LocationRequest that to meets resolution
     * and consistency requirements.
     *
     * @param request the LocationRequest from which to create a sanitized version
     * @return a version of request that meets the given resolution and consistency requirements
     * @hide
     */
    private LocationRequest createSanitizedRequest(LocationRequest request, int resolutionLevel,
            boolean callerHasLocationHardwarePermission) {
        LocationRequest sanitizedRequest = new LocationRequest(request);
        if (!callerHasLocationHardwarePermission) {
            // allow setting low power mode only for callers with location hardware permission
            sanitizedRequest.setLowPowerMode(false);
        }
        if (resolutionLevel < RESOLUTION_LEVEL_FINE) {
            switch (sanitizedRequest.getQuality()) {
                case LocationRequest.ACCURACY_FINE:
                    sanitizedRequest.setQuality(LocationRequest.ACCURACY_BLOCK);
                    break;
                case LocationRequest.POWER_HIGH:
                    sanitizedRequest.setQuality(LocationRequest.POWER_LOW);
                    break;
            }
            // throttle
            if (sanitizedRequest.getInterval() < FASTEST_COARSE_INTERVAL_MS) {
                sanitizedRequest.setInterval(FASTEST_COARSE_INTERVAL_MS);
            }
            if (sanitizedRequest.getFastestInterval() < FASTEST_COARSE_INTERVAL_MS) {
                sanitizedRequest.setFastestInterval(FASTEST_COARSE_INTERVAL_MS);
            }
        }
        // make getFastestInterval() the minimum of interval and fastest interval
        if (sanitizedRequest.getFastestInterval() > sanitizedRequest.getInterval()) {
            sanitizedRequest.setFastestInterval(request.getInterval());
        }
        return sanitizedRequest;
    }

    @Override
    public void requestLocationUpdates(LocationRequest request, ILocationListener listener,
            PendingIntent intent, String packageName, String featureId,
            String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        enforceCallingOrSelfLocationPermission();
        enforceCallingOrSelfPackageName(packageName);

        synchronized (mLock) {
            if (request == null) request = DEFAULT_LOCATION_REQUEST;
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            WorkSource workSource = request.getWorkSource();
            if (workSource != null && !workSource.isEmpty()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.UPDATE_DEVICE_STATS, null);
            }
            boolean hideFromAppOps = request.getHideFromAppOps();
            if (hideFromAppOps) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.UPDATE_APP_OPS_STATS, null);
            }
            if (request.isLocationSettingsIgnored()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.WRITE_SECURE_SETTINGS, null);
            }
            boolean callerHasLocationHardwarePermission =
                    mContext.checkCallingPermission(android.Manifest.permission.LOCATION_HARDWARE)
                            == PERMISSION_GRANTED;
            LocationRequest sanitizedRequest = createSanitizedRequest(request,
                    allowedResolutionLevel,
                    callerHasLocationHardwarePermission);

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();

            long identity = Binder.clearCallingIdentity();
            try {

                // We don't check for MODE_IGNORED here; we will do that when we go to deliver
                // a location.
                checkLocationAccess(pid, uid, packageName, allowedResolutionLevel);

                if (intent == null && listener == null) {
                    throw new IllegalArgumentException("need either listener or intent");
                } else if (intent != null && listener != null) {
                    throw new IllegalArgumentException(
                            "cannot register both listener and intent");
                }

                mLocationUsageLogger.logLocationApiUsage(
                        LocationStatsEnums.USAGE_STARTED,
                        LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                        packageName, request, listener != null, intent != null,
                        /* geofence= */ null,
                        mAppForegroundHelper.getImportance(uid));

                Receiver receiver;
                if (intent != null) {
                    receiver = getReceiverLocked(intent, pid, uid, packageName, featureId,
                            workSource, hideFromAppOps, listenerIdentifier);
                } else {
                    receiver = getReceiverLocked(listener, pid, uid, packageName, featureId,
                            workSource, hideFromAppOps, listenerIdentifier);
                }
                requestLocationUpdatesLocked(sanitizedRequest, receiver);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    private void requestLocationUpdatesLocked(LocationRequest request, Receiver receiver) {
        // Figure out the provider. Either its explicitly request (legacy use cases), or
        // use the fused provider
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        String name = request.getProvider();
        if (name == null) {
            throw new IllegalArgumentException("provider name must not be null");
        }

        LocationProviderManager manager = getLocationProviderManager(name);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + name);
        }

        UpdateRecord record = new UpdateRecord(name, request, receiver);

        UpdateRecord oldRecord = receiver.mUpdateRecords.put(name, record);
        if (oldRecord != null) {
            oldRecord.disposeLocked(false);
        }

        int userId = UserHandle.getUserId(receiver.mCallerIdentity.mUid);
        if (!manager.isEnabled(userId) && !isSettingsExempt(record)) {
            // Notify the listener that updates are currently disabled - but only if the request
            // does not ignore location settings
            receiver.callProviderEnabledLocked(name, false);
        }

        applyRequirementsLocked(name);

        // Update the monitoring here just in case multiple location requests were added to the
        // same receiver (this request may be high power and the initial might not have been).
        receiver.updateMonitoring(true);
    }

    @Override
    public void removeUpdates(ILocationListener listener, PendingIntent intent,
            String packageName) {
        enforceCallingOrSelfPackageName(packageName);

        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();

        if (intent == null && listener == null) {
            throw new IllegalArgumentException("need either listener or intent");
        } else if (intent != null && listener != null) {
            throw new IllegalArgumentException("cannot register both listener and intent");
        }

        synchronized (mLock) {
            Receiver receiver;
            if (intent != null) {
                receiver = getReceiverLocked(intent, pid, uid, packageName, null, null, false, "");
            } else {
                receiver = getReceiverLocked(listener, pid, uid, packageName, null, null, false,
                        "");
            }

            long identity = Binder.clearCallingIdentity();
            try {
                removeUpdatesLocked(receiver);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    private void removeUpdatesLocked(Receiver receiver) {
        if (D) Log.i(TAG, "remove " + Integer.toHexString(System.identityHashCode(receiver)));

        if (mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
            receiver.unlinkFromListenerDeathNotificationLocked(
                    receiver.getListener().asBinder());
            receiver.clearPendingBroadcastsLocked();
        }

        receiver.updateMonitoring(false);

        // Record which providers were associated with this listener
        HashSet<String> providers = new HashSet<>();
        HashMap<String, UpdateRecord> oldRecords = receiver.mUpdateRecords;
        if (oldRecords != null) {
            // Call dispose() on the obsolete update records.
            for (UpdateRecord record : oldRecords.values()) {
                // Update statistics for historical location requests by package/provider
                record.disposeLocked(false);
            }
            // Accumulate providers
            providers.addAll(oldRecords.keySet());
        }

        // update provider
        for (String provider : providers) {
            applyRequirementsLocked(provider);
        }
    }

    @Override
    public Location getLastLocation(LocationRequest request, String packageName, String featureId) {
        if (request == null) {
            request = DEFAULT_LOCATION_REQUEST;
        }

        enforceCallingOrSelfLocationPermission();
        enforceCallingOrSelfPackageName(packageName);

        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        if (!reportLocationAccessNoThrow(Binder.getCallingPid(), Binder.getCallingUid(),
                packageName, featureId, allowedResolutionLevel, null)) {
            if (D) {
                Log.d(TAG, "not returning last loc for no op app: " + packageName);
            }
            return null;
        }

        int userId = UserHandle.getCallingUserId();

        if (mSettingsHelper.isLocationPackageBlacklisted(userId, packageName)) {
            return null;
        }

        if (!mUserInfoHelper.isCurrentUserId(userId)) {
            return null;
        }

        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(request.getProvider());
            if (manager == null) {
                return null;
            }

            if (!manager.isEnabled(userId) && !request.isLocationSettingsIgnored()) {
                return null;
            }

            Location location;
            if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                location = manager.getLastCoarseLocation(userId);
            } else {
                location = manager.getLastFineLocation(userId);
            }
            if (location == null) {
                return null;
            }

            // Don't return stale location to apps with foreground-only location permission.
            String op = resolutionLevelToOpStr(allowedResolutionLevel);
            long locationAgeMs = NANOSECONDS.toMillis(
                    SystemClock.elapsedRealtime() - location.getElapsedRealtimeNanos());
            if (locationAgeMs > mSettingsHelper.getMaxLastLocationAgeMs()
                    && (mAppOps.unsafeCheckOp(op, Binder.getCallingUid(), packageName)
                    == AppOpsManager.MODE_FOREGROUND)) {
                return null;
            }

            // make a defensive copy - the client could be in the same process as us
            return new Location(location);
        }
    }

    @Override
    public boolean getCurrentLocation(LocationRequest locationRequest,
            ICancellationSignal remoteCancellationSignal, ILocationListener listener,
            String packageName, String featureId, String listenerIdentifier) {
        // side effect of validating locationRequest and packageName
        Location lastLocation = getLastLocation(locationRequest, packageName, featureId);
        if (lastLocation != null) {
            long locationAgeMs = NANOSECONDS.toMillis(
                    SystemClock.elapsedRealtimeNanos() - lastLocation.getElapsedRealtimeNanos());

            if (locationAgeMs < MAX_CURRENT_LOCATION_AGE_MS) {
                try {
                    listener.onLocationChanged(lastLocation);
                    return true;
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                    return false;
                }
            }

            if (!mAppForegroundHelper.isAppForeground(Binder.getCallingUid())) {
                if (locationAgeMs < mSettingsHelper.getBackgroundThrottleIntervalMs()) {
                    // not allowed to request new locations, so we can't return anything
                    return false;
                }
            }
        }

        requestLocationUpdates(locationRequest, listener, null, packageName, featureId,
                listenerIdentifier);
        CancellationSignal cancellationSignal = CancellationSignal.fromTransport(
                remoteCancellationSignal);
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(
                    () -> removeUpdates(listener, null, packageName));
        }
        return true;
    }

    @Override
    public LocationTime getGnssTimeMillis() {
        synchronized (mLock) {
            LocationProviderManager gpsManager = getLocationProviderManager(GPS_PROVIDER);
            if (gpsManager == null) {
                return null;
            }

            Location location = gpsManager.getLastFineLocation(UserHandle.getCallingUserId());
            if (location == null) {
                return null;
            }

            long currentNanos = SystemClock.elapsedRealtimeNanos();
            long deltaMs = NANOSECONDS.toMillis(
                    currentNanos - location.getElapsedRealtimeNanos());
            return new LocationTime(location.getTime() + deltaMs, currentNanos);
        }
    }

    @Override
    public void injectLocation(Location location) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(ACCESS_FINE_LOCATION, null);

        Preconditions.checkArgument(location.isComplete());

        int userId = UserHandle.getCallingUserId();
        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(location.getProvider());
            if (manager != null && manager.isEnabled(userId)) {
                manager.injectLastLocation(Objects.requireNonNull(location), userId);
            }
        }
    }

    @Override
    public void requestGeofence(LocationRequest request, Geofence geofence, PendingIntent intent,
            String packageName, String featureId, String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        mContext.enforceCallingOrSelfPermission(ACCESS_FINE_LOCATION, null);
        enforceCallingOrSelfPackageName(packageName);

        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + null);
        }
        // Require that caller can manage given document
        boolean callerHasLocationHardwarePermission =
                mContext.checkCallingPermission(android.Manifest.permission.LOCATION_HARDWARE)
                        == PERMISSION_GRANTED;
        LocationRequest sanitizedRequest = createSanitizedRequest(request,
                allowedResolutionLevel,
                callerHasLocationHardwarePermission);

        if (D) {
            Log.d(TAG, "requestGeofence: " + sanitizedRequest + " " + geofence + " " + intent);
        }

        // geo-fence manager uses the public location API, need to clear identity
        int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM) {
            // temporary measure until geofences work for secondary users
            Log.w(TAG, "proximity alerts are currently available only to the primary user");
            return;
        }

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                packageName,
                request,
                /* hasListener= */ false,
                true,
                geofence,
                mAppForegroundHelper.getImportance(uid));

        long identity = Binder.clearCallingIdentity();
        try {
            mGeofenceManager.addFence(sanitizedRequest, geofence, intent, allowedResolutionLevel,
                    uid, packageName, featureId, listenerIdentifier);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void removeGeofence(Geofence geofence, PendingIntent intent, String packageName) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + null);
        }
        enforceCallingOrSelfPackageName(packageName);

        if (D) Log.d(TAG, "removeGeofence: " + geofence + " " + intent);

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_GEOFENCE,
                packageName,
                /* LocationRequest= */ null,
                /* hasListener= */ false,
                true,
                geofence,
                mAppForegroundHelper.getImportance(Binder.getCallingUid()));

        // geo-fence manager uses the public location API, need to clear identity
        long identity = Binder.clearCallingIdentity();
        try {
            mGeofenceManager.removeFence(geofence, intent);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            String featureId) {
        return mGnssManagerService != null && mGnssManagerService.registerGnssStatusCallback(
                listener, packageName, featureId);
    }

    @Override
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        if (mGnssManagerService != null) mGnssManagerService.unregisterGnssStatusCallback(listener);
    }

    @Override
    public boolean addGnssMeasurementsListener(@Nullable GnssRequest request,
            IGnssMeasurementsListener listener,
            String packageName, String featureId,
            String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        return mGnssManagerService != null && mGnssManagerService.addGnssMeasurementsListener(
                request, listener, packageName, featureId, listenerIdentifier);
    }

    @Override
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssMeasurementsListener(
                    listener);
        }
    }

    @Override
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections, String packageName) {
        if (mGnssManagerService != null) {
            mGnssManagerService.injectGnssMeasurementCorrections(measurementCorrections,
                    packageName);
        }
    }

    @Override
    public long getGnssCapabilities(String packageName) {
        return mGnssManagerService == null ? 0L : mGnssManagerService.getGnssCapabilities(
                packageName);
    }

    @Override
    public boolean addGnssAntennaInfoListener(IGnssAntennaInfoListener listener,
            String packageName, String featureId, String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        return mGnssManagerService != null && mGnssManagerService.addGnssAntennaInfoListener(
                listener, packageName, featureId, listenerIdentifier);
    }

    @Override
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssAntennaInfoListener(listener);
        }
    }

    @Override
    public boolean addGnssNavigationMessageListener(IGnssNavigationMessageListener listener,
            String packageName, String featureId, String listenerIdentifier) {
        Objects.requireNonNull(listenerIdentifier);

        return mGnssManagerService != null && mGnssManagerService.addGnssNavigationMessageListener(
                listener, packageName, featureId, listenerIdentifier);
    }

    @Override
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssNavigationMessageListener(
                    listener);
        }
    }

    @Override
    public boolean sendExtraCommand(String providerName, String command, Bundle extras) {
        Objects.requireNonNull(providerName);
        Objects.requireNonNull(command);

        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS, null);
        enforceCallingOrSelfLocationPermission();

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                providerName);

        LocationProviderManager manager = getLocationProviderManager(providerName);
        if (manager != null) {
            manager.sendExtraCommand(Binder.getCallingUid(), Binder.getCallingPid(), command,
                    extras);
        }

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                providerName);

        return true;
    }

    @Override
    public ProviderProperties getProviderProperties(String providerName) {
        LocationProviderManager manager = getLocationProviderManager(providerName);
        if (manager == null) {
            return null;
        }
        return manager.getProperties();
    }

    @Override
    public boolean isProviderPackage(String packageName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_DEVICE_CONFIG, null);
        return mLocalService.isProviderPackage(packageName);
    }

    @Override
    public List<String> getProviderPackages(String providerName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_DEVICE_CONFIG, null);
        LocationProviderManager manager = getLocationProviderManager(providerName);
        return manager == null ? Collections.emptyList() : new ArrayList<>(manager.getPackages());
    }

    @Override
    public void setExtraLocationControllerPackage(String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                Manifest.permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mExtraLocationControllerPackage = packageName;
        }
    }

    @Override
    public String getExtraLocationControllerPackage() {
        synchronized (mLock) {
            return mExtraLocationControllerPackage;
        }
    }

    @Override
    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                Manifest.permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mExtraLocationControllerPackageEnabled = enabled;
        }
    }

    @Override
    public boolean isExtraLocationControllerPackageEnabled() {
        synchronized (mLock) {
            return mExtraLocationControllerPackageEnabled
                    && (mExtraLocationControllerPackage != null);
        }
    }

    @Override
    public void setLocationEnabledForUser(boolean enabled, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                    null);
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS,
                "Requires WRITE_SECURE_SETTINGS permission");

        LocationManager.invalidateLocalLocationEnabledCaches();
        mSettingsHelper.setLocationEnabled(enabled, userId);
    }

    @Override
    public boolean isLocationEnabledForUser(int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "isLocationEnabledForUser", null);
        return mSettingsHelper.isLocationEnabled(userId);
    }

    @Override
    public boolean isProviderEnabledForUser(String provider, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "isProviderEnabledForUser", null);

        // Fused provider is accessed indirectly via criteria rather than the provider-based APIs,
        // so we discourage its use
        if (FUSED_PROVIDER.equals(provider)) return false;

        return mLocalService.isProviderEnabledForUser(provider, userId);
    }

    @GuardedBy("mLock")
    private static boolean shouldBroadcastSafeLocked(
            Location loc, Location lastLoc, UpdateRecord record, long now) {
        // Always broadcast the first update
        if (lastLoc == null) {
            return true;
        }

        // Check whether sufficient time has passed
        long minTime = record.mRealRequest.getFastestInterval();
        long deltaMs = NANOSECONDS.toMillis(
                loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos());
        if (deltaMs < minTime - MAX_PROVIDER_SCHEDULING_JITTER_MS) {
            return false;
        }

        // Check whether sufficient distance has been traveled
        double minDistance = record.mRealRequest.getSmallestDisplacement();
        if (minDistance > 0.0) {
            if (loc.distanceTo(lastLoc) <= minDistance) {
                return false;
            }
        }

        // Check whether sufficient number of udpates is left
        if (record.mRealRequest.getNumUpdates() <= 0) {
            return false;
        }

        // Check whether the expiry date has passed
        return record.mExpirationRealtimeMs >= now;
    }

    @GuardedBy("mLock")
    private void handleLocationChangedLocked(LocationProviderManager manager, Location location,
            Location coarseLocation) {
        if (!mProviderManagers.contains(manager)) {
            Log.w(TAG, "received location from unknown provider: " + manager.getName());
            return;
        }

        // notify passive provider
        if (manager != mPassiveManager) {
            mPassiveManager.updateLocation(location);
        }

        long now = SystemClock.elapsedRealtime();

        ArrayList<UpdateRecord> records = mRecordsByProvider.get(manager.getName());
        if (records == null || records.size() == 0) return;

        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> deadUpdateRecords = null;

        // Broadcast location to all listeners
        for (UpdateRecord r : records) {
            Receiver receiver = r.mReceiver;
            boolean receiverDead = false;
            int userId = UserHandle.getUserId(receiver.mCallerIdentity.mUid);


            if (!manager.isEnabled(userId) && !isSettingsExempt(r)) {
                continue;
            }

            if (!mUserInfoHelper.isCurrentUserId(userId)
                    && !isProviderPackage(receiver.mCallerIdentity.mPackageName)) {
                if (D) {
                    Log.d(TAG, "skipping loc update for background user " + userId
                            + " (app: " + receiver.mCallerIdentity.mPackageName + ")");
                }
                continue;
            }

            if (mSettingsHelper.isLocationPackageBlacklisted(userId,
                    receiver.mCallerIdentity.mPackageName)) {
                if (D) {
                    Log.d(TAG, "skipping loc update for blacklisted app: " +
                            receiver.mCallerIdentity.mPackageName);
                }
                continue;
            }

            Location notifyLocation;
            if (receiver.mAllowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                notifyLocation = coarseLocation;  // use coarse location
            } else {
                notifyLocation = location;  // use fine location
            }
            if (shouldBroadcastSafeLocked(notifyLocation, r.mLastFixBroadcast, r, now)) {
                r.mLastFixBroadcast = notifyLocation;
                // Report location access before delivering location to the client. This will
                // note location delivery to appOps, so it should be called only when a
                // location is really being delivered to the client.
                if (!reportLocationAccessNoThrow(
                        receiver.mCallerIdentity.mPid,
                        receiver.mCallerIdentity.mUid,
                        receiver.mCallerIdentity.mPackageName,
                        receiver.mCallerIdentity.mFeatureId,
                        receiver.mAllowedResolutionLevel,
                        "Location sent to " + receiver.mCallerIdentity.mListenerIdentifier)) {
                    if (D) {
                        Log.d(TAG, "skipping loc update for no op app: "
                                + receiver.mCallerIdentity.mPackageName);
                    }
                    continue;
                }
                if (!receiver.callLocationChangedLocked(notifyLocation)) {
                    Log.w(TAG, "RemoteException calling onLocationChanged on "
                            + receiver);
                    receiverDead = true;
                }
                r.mRealRequest.decrementNumUpdates();
            }

            // track expired records
            if (r.mRealRequest.getNumUpdates() <= 0 || r.mExpirationRealtimeMs < now) {
                // notify the client it can remove this listener
                r.mReceiver.callRemovedLocked();
                if (deadUpdateRecords == null) {
                    deadUpdateRecords = new ArrayList<>();
                }
                deadUpdateRecords.add(r);
            }
            // track dead receivers
            if (receiverDead) {
                if (deadReceivers == null) {
                    deadReceivers = new ArrayList<>();
                }
                if (!deadReceivers.contains(receiver)) {
                    deadReceivers.add(receiver);
                }
            }
        }

        // remove dead records and receivers outside the loop
        if (deadReceivers != null) {
            for (Receiver receiver : deadReceivers) {
                removeUpdatesLocked(receiver);
            }
        }
        if (deadUpdateRecords != null) {
            for (UpdateRecord r : deadUpdateRecords) {
                r.disposeLocked(true);
            }
            applyRequirementsLocked(manager);
        }
    }

    // Geocoder

    @Override
    public boolean geocoderIsPresent() {
        return mGeocodeProvider != null;
    }

    @Override
    public String getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        if (mGeocodeProvider != null) {
            return mGeocodeProvider.getFromLocation(latitude, longitude, maxResults,
                    params, addrs);
        }
        return null;
    }


    @Override
    public String getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {

        if (mGeocodeProvider != null) {
            return mGeocodeProvider.getFromLocationName(locationName, lowerLeftLatitude,
                    lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                    maxResults, params, addrs);
        }
        return null;
    }

    // Mock Providers

    @Override
    public void addTestProvider(String provider, ProviderProperties properties,
            String packageName) {
        if (mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                manager = new LocationProviderManager(provider);
                mProviderManagers.add(manager);
            }

            manager.setMockProvider(new MockProvider(properties));
        }
    }

    @Override
    public void removeTestProvider(String provider, String packageName) {
        if (mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return;
            }

            manager.setMockProvider(null);
            if (!manager.hasProvider()) {
                mProviderManagers.remove(manager);
            }
        }
    }

    @Override
    public void setTestProviderLocation(String provider, Location location, String packageName) {
        Preconditions.checkArgument(location.isComplete(),
                "incomplete location object, missing timestamp or accuracy?");

        if (mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        manager.setMockProviderLocation(location);
    }

    @Override
    public void setTestProviderEnabled(String provider, boolean enabled, String packageName) {
        if (mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        manager.setMockProviderAllowed(enabled);
    }

    @Override
    @NonNull
    public List<LocationRequest> getTestProviderCurrentRequests(String provider,
            String packageName) {
        if (mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(), packageName)
                != AppOpsManager.MODE_ALLOWED) {
            return Collections.emptyList();
        }

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        return manager.getMockProviderRequests();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        synchronized (mLock) {
            if (mGnssManagerService != null && args.length > 0 && args[0].equals("--gnssmetrics")) {
                mGnssManagerService.dump(fd, pw, args);
                return;
            }

            ipw.println("Location Manager State:");
            ipw.increaseIndent();
            ipw.print("Current System Time: "
                    + TimeUtils.logTimeOfDay(System.currentTimeMillis()));
            ipw.println(", Current Elapsed Time: "
                    + TimeUtils.formatDuration(SystemClock.elapsedRealtime()));

            ipw.println("User Info:");
            ipw.increaseIndent();
            mUserInfoHelper.dump(fd, ipw, args);
            ipw.decreaseIndent();

            ipw.println("Location Settings:");
            ipw.increaseIndent();
            mSettingsHelper.dump(fd, ipw, args);
            ipw.decreaseIndent();

            ipw.println("Battery Saver Location Mode: "
                    + locationPowerSaveModeToString(mBatterySaverMode));

            ipw.println("Location Listeners:");
            ipw.increaseIndent();
            for (Receiver receiver : mReceivers.values()) {
                ipw.println(receiver);
            }
            ipw.decreaseIndent();

            ipw.println("Active Records by Provider:");
            ipw.increaseIndent();
            for (Map.Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
                ipw.println(entry.getKey() + ":");
                ipw.increaseIndent();
                for (UpdateRecord record : entry.getValue()) {
                    ipw.println(record);
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();

            ipw.println("Historical Records by Provider:");
            ipw.increaseIndent();
            TreeMap<PackageProviderKey, PackageStatistics> sorted = new TreeMap<>(
                    mRequestStatistics.statistics);
            for (Map.Entry<PackageProviderKey, PackageStatistics> entry
                    : sorted.entrySet()) {
                PackageProviderKey key = entry.getKey();
                ipw.println(key.mPackageName + ": " + key.mProviderName + ": " + entry.getValue());
            }
            ipw.decreaseIndent();

            mRequestStatistics.history.dump(ipw);

            if (mGeofenceManager != null) {
                ipw.println("Geofences:");
                ipw.increaseIndent();
                mGeofenceManager.dump(ipw);
                ipw.decreaseIndent();
            }

            if (mExtraLocationControllerPackage != null) {
                ipw.println("Location Controller Extra Package: " + mExtraLocationControllerPackage
                        + (mExtraLocationControllerPackageEnabled ? " [enabled]" : "[disabled]"));
            }
        }

        ipw.println("Location Providers:");
        ipw.increaseIndent();
        for (LocationProviderManager manager : mProviderManagers) {
            manager.dump(fd, ipw, args);
        }
        ipw.decreaseIndent();

        synchronized (mLock) {
            if (mGnssManagerService != null) {
                ipw.println("GNSS:");
                ipw.increaseIndent();
                mGnssManagerService.dump(fd, ipw, args);
                ipw.decreaseIndent();
            }
        }
    }

    private class LocalService extends LocationManagerInternal {

        @Override
        public boolean isProviderEnabledForUser(@NonNull String provider, int userId) {
            synchronized (mLock) {
                LocationProviderManager manager = getLocationProviderManager(provider);
                if (manager == null) {
                    return false;
                }

                return manager.isEnabled(userId);
            }
        }

        @Override
        public boolean isProviderPackage(String packageName) {
            for (LocationProviderManager manager : mProviderManagers) {
                if (manager.getPackages().contains(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void sendNiResponse(int notifId, int userResponse) {
            if (mGnssManagerService != null) {
                mGnssManagerService.sendNiResponse(notifId, userResponse);
            }
        }
    }
}
