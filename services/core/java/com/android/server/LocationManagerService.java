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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.location.LocationProvider.AVAILABLE;
import static android.provider.Settings.Global.LOCATION_DISABLE_STATUS_CALLBACKS;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.location.ActivityRecognitionHardware;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssMeasurementCorrections;
import android.location.IBatchedLocationCallback;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.location.AbstractLocationProvider;
import com.android.server.location.ActivityRecognitionProxy;
import com.android.server.location.CallerIdentity;
import com.android.server.location.GeocoderProxy;
import com.android.server.location.GeofenceManager;
import com.android.server.location.GeofenceProxy;
import com.android.server.location.GnssBatchingProvider;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssMeasurementsProvider;
import com.android.server.location.GnssNavigationMessageProvider;
import com.android.server.location.GnssStatusListenerHelper;
import com.android.server.location.LocationBlacklist;
import com.android.server.location.LocationFudger;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.LocationRequestStatistics;
import com.android.server.location.LocationRequestStatistics.PackageProviderKey;
import com.android.server.location.LocationRequestStatistics.PackageStatistics;
import com.android.server.location.MockProvider;
import com.android.server.location.PassiveProvider;
import com.android.server.location.RemoteListenerHelper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 */
public class LocationManagerService extends ILocationManager.Stub {
    private static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String WAKELOCK_KEY = "*location*";

    // Location resolution level: no location data whatsoever
    private static final int RESOLUTION_LEVEL_NONE = 0;
    // Location resolution level: coarse location data only
    private static final int RESOLUTION_LEVEL_COARSE = 1;
    // Location resolution level: fine location data
    private static final int RESOLUTION_LEVEL_FINE = 2;

    private static final String ACCESS_LOCATION_EXTRA_COMMANDS =
            android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS;

    private static final String NETWORK_LOCATION_SERVICE_ACTION =
            "com.android.location.service.v3.NetworkLocationProvider";
    private static final String FUSED_LOCATION_SERVICE_ACTION =
            "com.android.location.service.FusedLocationProvider";

    private static final long NANOS_PER_MILLI = 1000000L;

    // The maximum interval a location request can have and still be considered "high power".
    private static final long HIGH_POWER_INTERVAL_MS = 5 * 60 * 1000;

    private static final int FOREGROUND_IMPORTANCE_CUTOFF
            = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

    // default background throttling interval if not overriden in settings
    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;

    // Default value for maximum age of last location returned to applications with foreground-only
    // location permissions.
    private static final long DEFAULT_LAST_LOCATION_MAX_AGE_MS = 20 * 60 * 1000;

    // Location Providers may sometimes deliver location updates
    // slightly faster that requested - provide grace period so
    // we don't unnecessarily filter events that are otherwise on
    // time
    private static final int MAX_PROVIDER_SCHEDULING_JITTER_MS = 100;

    private static final LocationRequest DEFAULT_LOCATION_REQUEST = new LocationRequest();

    private final Object mLock = new Object();
    private final Context mContext;
    private final Handler mHandler;

    private AppOpsManager mAppOps;
    private PackageManager mPackageManager;
    private PowerManager mPowerManager;
    private ActivityManager mActivityManager;
    private UserManager mUserManager;

    private GeofenceManager mGeofenceManager;
    private LocationFudger mLocationFudger;
    private GeocoderProxy mGeocodeProvider;
    private GnssStatusListenerHelper mGnssStatusProvider;
    private INetInitiatedListener mNetInitiatedListener;
    private PassiveProvider mPassiveProvider;  // track passive provider for special cases
    private LocationBlacklist mBlacklist;
    private GnssMeasurementsProvider mGnssMeasurementsProvider;
    private GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    @GuardedBy("mLock")
    private String mLocationControllerExtraPackage;
    private boolean mLocationControllerExtraPackageEnabled;
    private IGpsGeofenceHardware mGpsGeofenceProxy;

    // list of currently active providers
    @GuardedBy("mLock")
    private final ArrayList<LocationProvider> mProviders = new ArrayList<>();

    // list of non-mock providers, so that when mock providers replace real providers, they can be
    // later re-replaced
    @GuardedBy("mLock")
    private final ArrayList<LocationProvider> mRealProviders = new ArrayList<>();

    @GuardedBy("mLock")
    private final HashMap<Object, Receiver> mReceivers = new HashMap<>();
    private final HashMap<String, ArrayList<UpdateRecord>> mRecordsByProvider =
            new HashMap<>();

    private final LocationRequestStatistics mRequestStatistics = new LocationRequestStatistics();

    // mapping from provider name to last known location
    @GuardedBy("mLock")
    private final HashMap<String, Location> mLastLocation = new HashMap<>();

    // same as mLastLocation, but is not updated faster than LocationFudger.FASTEST_INTERVAL_MS.
    // locations stored here are not fudged for coarse permissions.
    @GuardedBy("mLock")
    private final HashMap<String, Location> mLastLocationCoarseInterval =
            new HashMap<>();

    private final ArraySet<String> mBackgroundThrottlePackageWhitelist = new ArraySet<>();

    private final ArraySet<String> mIgnoreSettingsPackageWhitelist = new ArraySet<>();

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, LinkedListener<IGnssMeasurementsListener>>
            mGnssMeasurementsListeners = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, LinkedListener<IGnssNavigationMessageListener>>
            mGnssNavigationMessageListeners = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, LinkedListener<IGnssStatusListener>>
            mGnssStatusListeners = new ArrayMap<>();

    // current active user on the device - other users are denied location data
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private int[] mCurrentUserProfiles = new int[]{UserHandle.USER_SYSTEM};

    private GnssLocationProvider.GnssSystemInfoProvider mGnssSystemInfoProvider;

    private GnssLocationProvider.GnssMetricsProvider mGnssMetricsProvider;

    private GnssBatchingProvider mGnssBatchingProvider;
    @GuardedBy("mLock")
    private IBatchedLocationCallback mGnssBatchingCallback;
    @GuardedBy("mLock")
    private LinkedListener<IBatchedLocationCallback> mGnssBatchingDeathCallback;
    @GuardedBy("mLock")
    private boolean mGnssBatchingInProgress = false;

    public LocationManagerService(Context context) {
        super();
        mContext = context;
        mHandler = FgThread.getHandler();

        // Let the package manager query which are the default location
        // providers as they get certain permissions granted by default.
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setLocationPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_locationProviderPackageNames));
        packageManagerInternal.setLocationExtraPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                      com.android.internal.R.array.config_locationExtraPackageNames));

        // most startup is deferred until systemRunning()
    }

    public void systemRunning() {
        synchronized (mLock) {
            initializeLocked();
        }
    }

    @GuardedBy("mLock")
    private void initializeLocked() {
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        mLocationFudger = new LocationFudger(mContext, mHandler);
        mBlacklist = new LocationBlacklist(mContext, mHandler);
        mBlacklist.init();
        mGeofenceManager = new GeofenceManager(mContext, mBlacklist);

        // prepare providers
        initializeProvidersLocked();

        // add listeners
        mAppOps.startWatchingMode(
                AppOpsManager.OP_COARSE_LOCATION,
                null,
                AppOpsManager.WATCH_FOREGROUND_CHANGES,
                new AppOpsManager.OnOpChangedInternalListener() {
                    public void onOpChanged(int op, String packageName) {
                        synchronized (mLock) {
                            onAppOpChangedLocked();
                        }
                    }
                });
        mPackageManager.addOnPermissionsChangeListener(
                uid -> {
                    synchronized (mLock) {
                        onPermissionsChangedLocked();
                    }
                });

        mActivityManager.addOnUidImportanceListener(
                (uid, importance) -> {
                    synchronized (mLock) {
                        onUidImportanceChangedLocked(uid, importance);
                    }
                },
                FOREGROUND_IMPORTANCE_CUTOFF);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            onLocationModeChangedLocked(true);
                        }
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_PROVIDERS_ALLOWED), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            onProviderAllowedChangedLocked(true);
                        }
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            onBackgroundThrottleIntervalChangedLocked();
                        }
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            onBackgroundThrottleWhitelistChangedLocked();
                        }
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            onIgnoreSettingsWhitelistChangedLocked();
                        }
                    }
                }, UserHandle.USER_ALL);

        new PackageMonitor() {
            @Override
            public void onPackageDisappeared(String packageName, int reason) {
                synchronized (mLock) {
                    LocationManagerService.this.onPackageDisappearedLocked(packageName);
                }
            }
        }.register(mContext, mHandler.getLooper(), true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (mLock) {
                    String action = intent.getAction();
                    if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                        onUserChangedLocked(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                    } else if (Intent.ACTION_MANAGED_PROFILE_ADDED.equals(action)
                            || Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(action)) {
                        onUserProfilesChangedLocked();
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);

        // switching the user from null to system here performs the bulk of the initialization work.
        // the user being changed will cause a reload of all user specific settings, which causes
        // provider initialization, and propagates changes until a steady state is reached
        mCurrentUserId = UserHandle.USER_NULL;
        onUserChangedLocked(UserHandle.USER_SYSTEM);

        // initialize in-memory settings values
        onBackgroundThrottleWhitelistChangedLocked();
        onIgnoreSettingsWhitelistChangedLocked();
    }

    @GuardedBy("mLock")
    private void onAppOpChangedLocked() {
        for (Receiver receiver : mReceivers.values()) {
            receiver.updateMonitoring(true);
        }
        for (LocationProvider p : mProviders) {
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy("mLock")
    private void onPermissionsChangedLocked() {
        for (LocationProvider p : mProviders) {
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy("mLock")
    private void onLocationModeChangedLocked(boolean broadcast) {
        for (LocationProvider p : mProviders) {
            p.onLocationModeChangedLocked();
        }

        if (broadcast) {
            mContext.sendBroadcastAsUser(
                    new Intent(LocationManager.MODE_CHANGED_ACTION),
                    UserHandle.ALL);
        }
    }

    @GuardedBy("mLock")
    private void onProviderAllowedChangedLocked(boolean broadcast) {
        for (LocationProvider p : mProviders) {
            p.onAllowedChangedLocked();
        }

        if (broadcast) {
            mContext.sendBroadcastAsUser(
                    new Intent(LocationManager.PROVIDERS_CHANGED_ACTION),
                    UserHandle.ALL);
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

    @GuardedBy("mLock")
    private void onUidImportanceChangedLocked(int uid, int importance) {
        boolean foreground = isImportanceForeground(importance);
        HashSet<String> affectedProviders = new HashSet<>(mRecordsByProvider.size());
        for (Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
            String provider = entry.getKey();
            for (UpdateRecord record : entry.getValue()) {
                if (record.mReceiver.mCallerIdentity.mUid == uid
                        && record.mIsForegroundUid != foreground) {
                    if (D) {
                        Log.d(TAG, "request from uid " + uid + " is now "
                                + foregroundAsString(foreground));
                    }
                    record.updateForeground(foreground);

                    if (!isThrottlingExemptLocked(record.mReceiver.mCallerIdentity)) {
                        affectedProviders.add(provider);
                    }
                }
            }
        }
        for (String provider : affectedProviders) {
            applyRequirementsLocked(provider);
        }

        updateGnssDataProviderOnUidImportanceChangedLocked(mGnssMeasurementsListeners,
                mGnssMeasurementsProvider, IGnssMeasurementsListener.Stub::asInterface,
                uid, foreground);

        updateGnssDataProviderOnUidImportanceChangedLocked(mGnssNavigationMessageListeners,
                mGnssNavigationMessageProvider, IGnssNavigationMessageListener.Stub::asInterface,
                uid, foreground);

        updateGnssDataProviderOnUidImportanceChangedLocked(mGnssStatusListeners,
                mGnssStatusProvider, IGnssStatusListener.Stub::asInterface, uid, foreground);
    }

    @GuardedBy("mLock")
    private <TListener extends IInterface> void updateGnssDataProviderOnUidImportanceChangedLocked(
            ArrayMap<IBinder, ? extends LinkedListenerBase> gnssDataListeners,
            RemoteListenerHelper<TListener> gnssDataProvider,
            Function<IBinder, TListener> mapBinderToListener, int uid, boolean foreground) {
        for (Entry<IBinder, ? extends LinkedListenerBase> entry : gnssDataListeners.entrySet()) {
            LinkedListenerBase linkedListener = entry.getValue();
            CallerIdentity callerIdentity = linkedListener.mCallerIdentity;
            if (callerIdentity.mUid != uid) {
                continue;
            }

            if (D) {
                Log.d(TAG, linkedListener.mListenerName + " from uid "
                        + uid + " is now " + foregroundAsString(foreground));
            }

            TListener listener = mapBinderToListener.apply(entry.getKey());
            if (foreground || isThrottlingExemptLocked(callerIdentity)) {
                gnssDataProvider.addListener(listener, callerIdentity);
            } else {
                gnssDataProvider.removeListener(listener);
            }
        }
    }

    private static String foregroundAsString(boolean foreground) {
        return foreground ? "foreground" : "background";
    }

    private static boolean isImportanceForeground(int importance) {
        return importance <= FOREGROUND_IMPORTANCE_CUTOFF;
    }

    @GuardedBy("mLock")
    private void onBackgroundThrottleIntervalChangedLocked() {
        for (LocationProvider provider : mProviders) {
            applyRequirementsLocked(provider);
        }
    }

    @GuardedBy("mLock")
    private void onBackgroundThrottleWhitelistChangedLocked() {
        mBackgroundThrottlePackageWhitelist.clear();
        mBackgroundThrottlePackageWhitelist.addAll(
                SystemConfig.getInstance().getAllowUnthrottledLocation());

        String setting = Settings.Global.getString(
                mContext.getContentResolver(),
                Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST);
        if (!TextUtils.isEmpty(setting)) {
            mBackgroundThrottlePackageWhitelist.addAll(Arrays.asList(setting.split(",")));
        }

        for (LocationProvider p : mProviders) {
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy("lock")
    private void onIgnoreSettingsWhitelistChangedLocked() {
        mIgnoreSettingsPackageWhitelist.clear();
        mIgnoreSettingsPackageWhitelist.addAll(
                SystemConfig.getInstance().getAllowIgnoreLocationSettings());

        String setting = Settings.Global.getString(
                mContext.getContentResolver(),
                Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST);
        if (!TextUtils.isEmpty(setting)) {
            mIgnoreSettingsPackageWhitelist.addAll(Arrays.asList(setting.split(",")));
        }

        for (LocationProvider p : mProviders) {
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy("mLock")
    private void onUserProfilesChangedLocked() {
        mCurrentUserProfiles = mUserManager.getProfileIdsWithDisabled(mCurrentUserId);
    }

    @GuardedBy("mLock")
    private boolean isCurrentProfileLocked(int userId) {
        return ArrayUtils.contains(mCurrentUserProfiles, userId);
    }

    @GuardedBy("mLock")
    private void ensureFallbackFusedProviderPresentLocked(String[] pkgs) {
        PackageManager pm = mContext.getPackageManager();
        String systemPackageName = mContext.getPackageName();
        ArrayList<HashSet<Signature>> sigSets = ServiceWatcher.getSignatureSets(mContext, pkgs);

        List<ResolveInfo> rInfos = pm.queryIntentServicesAsUser(
                new Intent(FUSED_LOCATION_SERVICE_ACTION),
                PackageManager.GET_META_DATA, mCurrentUserId);
        for (ResolveInfo rInfo : rInfos) {
            String packageName = rInfo.serviceInfo.packageName;

            // Check that the signature is in the list of supported sigs. If it's not in
            // this list the standard provider binding logic won't bind to it.
            try {
                PackageInfo pInfo;
                pInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                if (!ServiceWatcher.isSignatureMatch(pInfo.signatures, sigSets)) {
                    Log.w(TAG, packageName + " resolves service " + FUSED_LOCATION_SERVICE_ACTION +
                            ", but has wrong signature, ignoring");
                    continue;
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "missing package: " + packageName);
                continue;
            }

            // Get the version info
            if (rInfo.serviceInfo.metaData == null) {
                Log.w(TAG, "Found fused provider without metadata: " + packageName);
                continue;
            }

            int version = rInfo.serviceInfo.metaData.getInt(
                    ServiceWatcher.EXTRA_SERVICE_VERSION, -1);
            if (version == 0) {
                // This should be the fallback fused location provider.

                // Make sure it's in the system partition.
                if ((rInfo.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    if (D) Log.d(TAG, "Fallback candidate not in /system: " + packageName);
                    continue;
                }

                // Check that the fallback is signed the same as the OS
                // as a proxy for coreApp="true"
                if (pm.checkSignatures(systemPackageName, packageName)
                        != PackageManager.SIGNATURE_MATCH) {
                    if (D) {
                        Log.d(TAG, "Fallback candidate not signed the same as system: "
                                + packageName);
                    }
                    continue;
                }

                // Found a valid fallback.
                if (D) Log.d(TAG, "Found fallback provider: " + packageName);
                return;
            } else {
                if (D) Log.d(TAG, "Fallback candidate not version 0: " + packageName);
            }
        }

        throw new IllegalStateException("Unable to find a fused location provider that is in the "
                + "system partition with version 0 and signed with the platform certificate. "
                + "Such a package is needed to provide a default fused location provider in the "
                + "event that no other fused location provider has been installed or is currently "
                + "available. For example, coreOnly boot mode when decrypting the data "
                + "partition. The fallback must also be marked coreApp=\"true\" in the manifest");
    }

    @GuardedBy("mLock")
    private void initializeProvidersLocked() {
        // create a passive location provider, which is always enabled
        LocationProvider passiveProviderManager = new LocationProvider(PASSIVE_PROVIDER);
        addProviderLocked(passiveProviderManager);
        mPassiveProvider = new PassiveProvider(mContext, passiveProviderManager);
        passiveProviderManager.attachLocked(mPassiveProvider);

        if (GnssLocationProvider.isSupported()) {
            // Create a gps location provider
            LocationProvider gnssProviderManager = new LocationProvider(GPS_PROVIDER, true);
            mRealProviders.add(gnssProviderManager);
            addProviderLocked(gnssProviderManager);

            GnssLocationProvider gnssProvider = new GnssLocationProvider(mContext,
                    gnssProviderManager,
                    mHandler.getLooper());
            gnssProviderManager.attachLocked(gnssProvider);

            mGnssSystemInfoProvider = gnssProvider.getGnssSystemInfoProvider();
            mGnssBatchingProvider = gnssProvider.getGnssBatchingProvider();
            mGnssMetricsProvider = gnssProvider.getGnssMetricsProvider();
            mGnssStatusProvider = gnssProvider.getGnssStatusProvider();
            mNetInitiatedListener = gnssProvider.getNetInitiatedListener();
            mGnssMeasurementsProvider = gnssProvider.getGnssMeasurementsProvider();
            mGnssNavigationMessageProvider = gnssProvider.getGnssNavigationMessageProvider();
            mGpsGeofenceProxy = gnssProvider.getGpsGeofenceProxy();
        }

        /*
        Load package name(s) containing location provider support.
        These packages can contain services implementing location providers:
        Geocoder Provider, Network Location Provider, and
        Fused Location Provider. They will each be searched for
        service components implementing these providers.
        The location framework also has support for installation
        of new location providers at run-time. The new package does not
        have to be explicitly listed here, however it must have a signature
        that matches the signature of at least one package on this list.
        */
        Resources resources = mContext.getResources();
        String[] pkgs = resources.getStringArray(
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (D) {
            Log.d(TAG, "certificates for location providers pulled from: " +
                    Arrays.toString(pkgs));
        }

        ensureFallbackFusedProviderPresentLocked(pkgs);

        // bind to network provider
        LocationProvider networkProviderManager = new LocationProvider(NETWORK_PROVIDER, true);
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndBind(
                mContext,
                networkProviderManager,
                NETWORK_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableNetworkLocationOverlay,
                com.android.internal.R.string.config_networkLocationProviderPackageName,
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (networkProvider != null) {
            mRealProviders.add(networkProviderManager);
            addProviderLocked(networkProviderManager);
            networkProviderManager.attachLocked(networkProvider);
        } else {
            Slog.w(TAG, "no network location provider found");
        }

        // bind to fused provider
        LocationProvider fusedProviderManager = new LocationProvider(FUSED_PROVIDER);
        LocationProviderProxy fusedProvider = LocationProviderProxy.createAndBind(
                mContext,
                fusedProviderManager,
                FUSED_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableFusedLocationOverlay,
                com.android.internal.R.string.config_fusedLocationProviderPackageName,
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (fusedProvider != null) {
            mRealProviders.add(fusedProviderManager);
            addProviderLocked(fusedProviderManager);
            fusedProviderManager.attachLocked(fusedProvider);
        } else {
            Slog.e(TAG, "no fused location provider found",
                    new IllegalStateException("Location service needs a fused location provider"));
        }

        // bind to geocoder provider
        mGeocodeProvider = GeocoderProxy.createAndBind(mContext,
                com.android.internal.R.bool.config_enableGeocoderOverlay,
                com.android.internal.R.string.config_geocoderProviderPackageName,
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (mGeocodeProvider == null) {
            Slog.e(TAG, "no geocoder provider found");
        }

        // bind to geofence provider
        GeofenceProxy provider = GeofenceProxy.createAndBind(
                mContext, com.android.internal.R.bool.config_enableGeofenceOverlay,
                com.android.internal.R.string.config_geofenceProviderPackageName,
                com.android.internal.R.array.config_locationProviderPackageNames,
                mGpsGeofenceProxy,
                null);
        if (provider == null) {
            Slog.d(TAG, "Unable to bind FLP Geofence proxy.");
        }

        // bind to hardware activity recognition
        boolean activityRecognitionHardwareIsSupported = ActivityRecognitionHardware.isSupported();
        ActivityRecognitionHardware activityRecognitionHardware = null;
        if (activityRecognitionHardwareIsSupported) {
            activityRecognitionHardware = ActivityRecognitionHardware.getInstance(mContext);
        } else {
            Slog.d(TAG, "Hardware Activity-Recognition not supported.");
        }
        ActivityRecognitionProxy proxy = ActivityRecognitionProxy.createAndBind(
                mContext,
                activityRecognitionHardwareIsSupported,
                activityRecognitionHardware,
                com.android.internal.R.bool.config_enableActivityRecognitionHardwareOverlay,
                com.android.internal.R.string.config_activityRecognitionHardwarePackageName,
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (proxy == null) {
            Slog.d(TAG, "Unable to bind ActivityRecognitionProxy.");
        }

        String[] testProviderStrings = resources.getStringArray(
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
            LocationProvider testProviderManager = new LocationProvider(name);
            addProviderLocked(testProviderManager);
            new MockProvider(mContext, testProviderManager, properties);
        }
    }

    @GuardedBy("mLock")
    private void onUserChangedLocked(int userId) {
        if (mCurrentUserId == userId) {
            return;
        }

        // let providers know the current user is on the way out before changing the user
        for (LocationProvider p : mProviders) {
            p.onUserChangingLocked();
        }

        mCurrentUserId = userId;
        onUserProfilesChangedLocked();

        mBlacklist.switchUser(userId);

        // if the user changes, per-user settings may also have changed
        onLocationModeChangedLocked(false);
        onProviderAllowedChangedLocked(false);
    }

    private class LocationProvider implements AbstractLocationProvider.LocationProviderManager {

        private final String mName;

        // whether this provider should respect LOCATION_PROVIDERS_ALLOWED (ie gps and network)
        private final boolean mIsManagedBySettings;

        // remember to clear binder identity before invoking any provider operation
        @GuardedBy("mLock")
        @Nullable protected AbstractLocationProvider mProvider;

        @GuardedBy("mLock")
        private boolean mUseable;  // combined state
        @GuardedBy("mLock")
        private boolean mAllowed;  // state of LOCATION_PROVIDERS_ALLOWED
        @GuardedBy("mLock")
        private boolean mEnabled;  // state of provider

        @GuardedBy("mLock")
        @Nullable private ProviderProperties mProperties;

        private LocationProvider(String name) {
            this(name, false);
        }

        private LocationProvider(String name, boolean isManagedBySettings) {
            mName = name;
            mIsManagedBySettings = isManagedBySettings;

            mProvider = null;
            mUseable = false;
            mAllowed = !mIsManagedBySettings;
            mEnabled = false;
            mProperties = null;

            if (mIsManagedBySettings) {
                // since we assume providers are disabled by default
                Settings.Secure.putStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                        "-" + mName,
                        mCurrentUserId);
            }
        }

        @GuardedBy("mLock")
        public void attachLocked(AbstractLocationProvider provider) {
            checkNotNull(provider);
            checkState(mProvider == null);
            mProvider = provider;

            onUseableChangedLocked();
        }

        public String getName() {
            return mName;
        }

        @GuardedBy("mLock")
        public List<String> getPackagesLocked() {
            if (mProvider == null) {
                return Collections.emptyList();
            } else {
                // safe to not clear binder context since this doesn't call into the real provider
                return mProvider.getProviderPackages();
            }
        }

        public boolean isMock() {
            return false;
        }

        @GuardedBy("mLock")
        public boolean isPassiveLocked() {
            return mProvider == mPassiveProvider;
        }

        @GuardedBy("mLock")
        @Nullable
        public ProviderProperties getPropertiesLocked() {
            return mProperties;
        }

        @GuardedBy("mLock")
        public void setRequestLocked(ProviderRequest request, WorkSource workSource) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    mProvider.setRequest(request, workSource);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy("mLock")
        public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.print("  " + mName + " provider");
            if (isMock()) {
                pw.print(" [mock]");
            }
            pw.println(":");

            pw.println("    useable=" + mUseable);
            if (!mUseable) {
                pw.println("    attached=" + (mProvider != null));
                if (mIsManagedBySettings) {
                    pw.println("    allowed=" + mAllowed);
                }
                pw.println("    enabled=" + mEnabled);
            }

            pw.println("    properties=" + mProperties);

            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    mProvider.dump(fd, pw, args);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy("mLock")
        public long getStatusUpdateTimeLocked() {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return mProvider.getStatusUpdateTime();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                return 0;
            }
        }

        @GuardedBy("mLock")
        public int getStatusLocked(Bundle extras) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return mProvider.getStatus(extras);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                return AVAILABLE;
            }
        }

        @GuardedBy("mLock")
        public void sendExtraCommandLocked(String command, Bundle extras) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    mProvider.sendExtraCommand(command, extras);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        // called from any thread
        @Override
        public void onReportLocation(Location location) {
            // no security check necessary because this is coming from an internal-only interface
            // move calls coming from below LMS onto a different thread to avoid deadlock
            mHandler.post(() -> {
                synchronized (mLock) {
                    handleLocationChangedLocked(location, this);
                }
            });
        }

        // called from any thread
        @Override
        public void onReportLocation(List<Location> locations) {
            // move calls coming from below LMS onto a different thread to avoid deadlock
            mHandler.post(() -> {
                synchronized (mLock) {
                    LocationProvider gpsProvider = getLocationProviderLocked(GPS_PROVIDER);
                    if (gpsProvider == null || !gpsProvider.isUseableLocked()) {
                        Slog.w(TAG, "reportLocationBatch() called without user permission");
                        return;
                    }

                    if (mGnssBatchingCallback == null) {
                        Slog.e(TAG, "reportLocationBatch() called without active Callback");
                        return;
                    }

                    try {
                        mGnssBatchingCallback.onLocationBatch(locations);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "mGnssBatchingCallback.onLocationBatch failed", e);
                    }
                }
            });
        }

        // called from any thread
        @Override
        public void onSetEnabled(boolean enabled) {
            // move calls coming from below LMS onto a different thread to avoid deadlock
            mHandler.post(() -> {
                synchronized (mLock) {
                    if (enabled == mEnabled) {
                        return;
                    }

                    mEnabled = enabled;

                    // update provider allowed settings to reflect enabled status
                    if (mIsManagedBySettings) {
                        if (mEnabled && !mAllowed) {
                            Settings.Secure.putStringForUser(
                                    mContext.getContentResolver(),
                                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                                    "+" + mName,
                                    mCurrentUserId);
                        } else if (!mEnabled && mAllowed) {
                            Settings.Secure.putStringForUser(
                                    mContext.getContentResolver(),
                                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                                    "-" + mName,
                                    mCurrentUserId);
                        }
                    }

                    onUseableChangedLocked();
                }
            });
        }

        @Override
        public void onSetProperties(ProviderProperties properties) {
            // because this does not invoke any other methods which might result in calling back
            // into the location provider, it is safe to run this on the calling thread. it is also
            // currently necessary to run this on the calling thread to ensure that property changes
            // are publicly visibly immediately, ie for mock providers which are created.
            synchronized (mLock) {
                mProperties = properties;
            }
        }

        @GuardedBy("mLock")
        public void onLocationModeChangedLocked() {
            onUseableChangedLocked();
        }

        private boolean isAllowed() {
            return isAllowedForUser(mCurrentUserId);
        }

        private boolean isAllowedForUser(int userId) {
            String allowedProviders = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                    userId);
            return TextUtils.delimitedStringContains(allowedProviders, ',', mName);
        }

        @GuardedBy("mLock")
        public void onAllowedChangedLocked() {
            if (mIsManagedBySettings) {
                boolean allowed = isAllowed();
                if (allowed == mAllowed) {
                    return;
                }
                mAllowed = allowed;

                // make a best effort to keep the setting matching the real enabled state of the
                // provider so that legacy applications aren't broken.
                if (mAllowed && !mEnabled) {
                    Settings.Secure.putStringForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                            "-" + mName,
                            mCurrentUserId);
                }

                onUseableChangedLocked();
            }
        }

        @GuardedBy("mLock")
        public boolean isUseableLocked() {
            return isUseableForUserLocked(mCurrentUserId);
        }

        @GuardedBy("mLock")
        public boolean isUseableForUserLocked(int userId) {
            return userId == mCurrentUserId && mUseable;
        }

        @GuardedBy("mLock")
        public void onUseableChangedLocked() {
            // if any property that contributes to "useability" here changes state, it MUST result
            // in a direct or indrect call to onUseableChangedLocked. this allows the provider to
            // guarantee that it will always eventually reach the correct state.
            boolean useable = mProvider != null
                    && mProviders.contains(this) && isLocationEnabled() && mAllowed && mEnabled;
            if (useable == mUseable) {
                return;
            }
            mUseable = useable;

            if (!mUseable) {
                // If any provider has been disabled, clear all last locations for all
                // providers. This is to be on the safe side in case a provider has location
                // derived from this disabled provider.
                mLastLocation.clear();
                mLastLocationCoarseInterval.clear();
            }

            updateProviderUseableLocked(this);
        }

        @GuardedBy("mLock")
        public void onUserChangingLocked() {
            // when the user is about to change, we set this provider to un-useable, and notify all
            // of the current user clients. when the user is finished changing, useability will be
            // updated back via onLocationModeChanged() and onAllowedChanged().
            mUseable = false;
            updateProviderUseableLocked(this);
        }
    }

    private class MockLocationProvider extends LocationProvider {

        private MockLocationProvider(String name) {
            super(name);
        }

        @Override
        public void attachLocked(AbstractLocationProvider provider) {
            checkState(provider instanceof MockProvider);
            super.attachLocked(provider);
        }

        public boolean isMock() {
            return true;
        }

        @GuardedBy("mLock")
        public void setEnabledLocked(boolean enabled) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) mProvider).setEnabled(enabled);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy("mLock")
        public void setLocationLocked(Location location) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) mProvider).setLocation(location);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy("mLock")
        public void setStatusLocked(int status, Bundle extras, long updateTime) {
            if (mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) mProvider).setStatus(status, extras, updateTime);
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
    private final class Receiver extends LinkedListenerBase implements PendingIntent.OnFinished {
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
                String packageName, WorkSource workSource, boolean hideFromAppOps) {
            super(new CallerIdentity(uid, pid, packageName), "LocationListener");
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
                    LocationProvider provider = getLocationProviderLocked(updateRecord.mProvider);
                    if (provider == null) {
                        continue;
                    }
                    if (!provider.isUseableLocked() && !isSettingsExemptLocked(updateRecord)) {
                        continue;
                    }

                    requestingLocation = true;
                    ProviderProperties properties = provider.getPropertiesLocked();
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
                            mCallerIdentity.mPackageName) == AppOpsManager.MODE_ALLOWED;
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

        public boolean callStatusChangedLocked(String provider, int status, Bundle extras) {
            if (mListener != null) {
                try {
                    mListener.onStatusChanged(provider, status, extras);
                    // call this after broadcasting so we do not increment
                    // if we throw an exception.
                    incrementPendingBroadcastsLocked();
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent statusChanged = new Intent();
                statusChanged.putExtras(new Bundle(extras));
                statusChanged.putExtra(LocationManager.KEY_STATUS_CHANGED, status);
                try {
                    mPendingIntent.send(mContext, 0, statusChanged, this, mHandler,
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
        if (mGnssSystemInfoProvider != null) {
            return mGnssSystemInfoProvider.getGnssYearOfHardware();
        } else {
            return 0;
        }
    }

    @Override
    @Nullable
    public String getGnssHardwareModelName() {
        if (mGnssSystemInfoProvider != null) {
            return mGnssSystemInfoProvider.getGnssHardwareModelName();
        } else {
            return null;
        }
    }

    private boolean hasGnssPermissions(String packageName) {
        synchronized (mLock) {
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            checkResolutionLevelIsSufficientForProviderUseLocked(
                    allowedResolutionLevel,
                    GPS_PROVIDER);

            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try {
                return checkLocationAccess(pid, uid, packageName, allowedResolutionLevel);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public int getGnssBatchSize(String packageName) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (hasGnssPermissions(packageName) && mGnssBatchingProvider != null) {
            return mGnssBatchingProvider.getBatchSize();
        } else {
            return 0;
        }
    }

    @Override
    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName) || mGnssBatchingProvider == null) {
            return false;
        }

        CallerIdentity callerIdentity = new CallerIdentity(Binder.getCallingUid(),
                Binder.getCallingPid(), packageName);
        synchronized (mLock) {
            mGnssBatchingCallback = callback;
            mGnssBatchingDeathCallback =  new LinkedListener<>(callback,
                    "BatchedLocationCallback", callerIdentity,
                    (IBatchedLocationCallback listener) -> {
                        stopGnssBatch();
                        removeGnssBatchingCallback();
                    });
            if (!linkToListenerDeathNotificationLocked(callback.asBinder(),
                    mGnssBatchingDeathCallback)) {
                return false;
            }
            return true;
        }
    }

    @Override
    public void removeGnssBatchingCallback() {
        synchronized (mLock) {
            unlinkFromListenerDeathNotificationLocked(mGnssBatchingCallback.asBinder(),
                    mGnssBatchingDeathCallback);
            mGnssBatchingCallback = null;
            mGnssBatchingDeathCallback = null;
        }
    }

    @Override
    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName) || mGnssBatchingProvider == null) {
            return false;
        }

        synchronized (mLock) {
            if (mGnssBatchingInProgress) {
                // Current design does not expect multiple starts to be called repeatedly
                Log.e(TAG, "startGnssBatch unexpectedly called w/o stopping prior batch");
                // Try to clean up anyway, and continue
                stopGnssBatch();
            }

            mGnssBatchingInProgress = true;
            return mGnssBatchingProvider.start(periodNanos, wakeOnFifoFull);
        }
    }

    @Override
    public void flushGnssBatch(String packageName) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "flushGnssBatch called without GNSS permissions");
            return;
        }

        synchronized (mLock) {
            if (!mGnssBatchingInProgress) {
                Log.w(TAG, "flushGnssBatch called with no batch in progress");
            }

            if (mGnssBatchingProvider != null) {
                mGnssBatchingProvider.flush();
            }
        }
    }

    @Override
    public boolean stopGnssBatch() {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        synchronized (mLock) {
            if (mGnssBatchingProvider != null) {
                mGnssBatchingInProgress = false;
                return mGnssBatchingProvider.stop();
            } else {
                return false;
            }
        }
    }

    @GuardedBy("mLock")
    private void addProviderLocked(LocationProvider provider) {
        Preconditions.checkState(getLocationProviderLocked(provider.getName()) == null);

        mProviders.add(provider);

        provider.onAllowedChangedLocked();  // allowed state may change while provider was inactive
        provider.onUseableChangedLocked();
    }

    @GuardedBy("mLock")
    private void removeProviderLocked(LocationProvider provider) {
        if (mProviders.remove(provider)) {
            long identity = Binder.clearCallingIdentity();
            try {
                provider.onUseableChangedLocked();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private LocationProvider getLocationProviderLocked(String providerName) {
        for (LocationProvider provider : mProviders) {
            if (providerName.equals(provider.getName())) {
                return provider;
            }
        }

        return null;
    }

    private String getResolutionPermission(int resolutionLevel) {
        switch (resolutionLevel) {
            case RESOLUTION_LEVEL_FINE:
                return android.Manifest.permission.ACCESS_FINE_LOCATION;
            case RESOLUTION_LEVEL_COARSE:
                return android.Manifest.permission.ACCESS_COARSE_LOCATION;
            default:
                return null;
        }
    }

    private int getAllowedResolutionLevel(int pid, int uid) {
        if (mContext.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                pid, uid) == PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_FINE;
        } else if (mContext.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                pid, uid) == PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_COARSE;
        } else {
            return RESOLUTION_LEVEL_NONE;
        }
    }

    private int getCallerAllowedResolutionLevel() {
        return getAllowedResolutionLevel(Binder.getCallingPid(), Binder.getCallingUid());
    }

    private void checkResolutionLevelIsSufficientForGeofenceUse(int allowedResolutionLevel) {
        if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
            throw new SecurityException("Geofence usage requires ACCESS_FINE_LOCATION permission");
        }
    }

    @GuardedBy("mLock")
    private int getMinimumResolutionLevelForProviderUseLocked(String provider) {
        if (GPS_PROVIDER.equals(provider) || PASSIVE_PROVIDER.equals(provider)) {
            // gps and passive providers require FINE permission
            return RESOLUTION_LEVEL_FINE;
        } else if (NETWORK_PROVIDER.equals(provider) || FUSED_PROVIDER.equals(provider)) {
            // network and fused providers are ok with COARSE or FINE
            return RESOLUTION_LEVEL_COARSE;
        } else {
            for (LocationProvider lp : mProviders) {
                if (!lp.getName().equals(provider)) {
                    continue;
                }

                ProviderProperties properties = lp.getPropertiesLocked();
                if (properties != null) {
                    if (properties.mRequiresSatellite) {
                        // provider requiring satellites require FINE permission
                        return RESOLUTION_LEVEL_FINE;
                    } else if (properties.mRequiresNetwork || properties.mRequiresCell) {
                        // provider requiring network and or cell require COARSE or FINE
                        return RESOLUTION_LEVEL_COARSE;
                    }
                }
            }
        }

        return RESOLUTION_LEVEL_FINE; // if in doubt, require FINE
    }

    @GuardedBy("mLock")
    private void checkResolutionLevelIsSufficientForProviderUseLocked(int allowedResolutionLevel,
            String providerName) {
        int requiredResolutionLevel = getMinimumResolutionLevelForProviderUseLocked(providerName);
        if (allowedResolutionLevel < requiredResolutionLevel) {
            switch (requiredResolutionLevel) {
                case RESOLUTION_LEVEL_FINE:
                    throw new SecurityException("\"" + providerName + "\" location provider " +
                            "requires ACCESS_FINE_LOCATION permission.");
                case RESOLUTION_LEVEL_COARSE:
                    throw new SecurityException("\"" + providerName + "\" location provider " +
                            "requires ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission.");
                default:
                    throw new SecurityException("Insufficient permission for \"" + providerName +
                            "\" location provider.");
            }
        }
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
                return AppOpsManager.OPSTR_FINE_LOCATION;
            case RESOLUTION_LEVEL_NONE:
                // The client is not allowed to get any location, so both FINE and COARSE ops will
                // be denied. Pick the most restrictive one to be safe.
                return AppOpsManager.OPSTR_FINE_LOCATION;
            default:
                // Use the most restrictive ops if not sure.
                return AppOpsManager.OPSTR_FINE_LOCATION;
        }
    }

    private boolean reportLocationAccessNoThrow(
            int pid, int uid, String packageName, int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        if (op >= 0) {
            if (mAppOps.noteOpNoThrow(op, uid, packageName) != AppOpsManager.MODE_ALLOWED) {
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
        synchronized (mLock) {
            ArrayList<String> providers = new ArrayList<>(mProviders.size());
            for (LocationProvider provider : mProviders) {
                String name = provider.getName();
                if (FUSED_PROVIDER.equals(name)) {
                    continue;
                }
                providers.add(name);
            }
            return providers;
        }
    }

    /**
     * Return all providers by name, that match criteria and are optionally
     * enabled.
     * Can return passive provider, but never returns fused provider.
     */
    @Override
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        synchronized (mLock) {
            ArrayList<String> providers = new ArrayList<>(mProviders.size());
            for (LocationProvider provider : mProviders) {
                String name = provider.getName();
                if (FUSED_PROVIDER.equals(name)) {
                    continue;
                }
                if (allowedResolutionLevel < getMinimumResolutionLevelForProviderUseLocked(name)) {
                    continue;
                }
                if (enabledOnly && !provider.isUseableLocked()) {
                    continue;
                }
                if (criteria != null
                        && !android.location.LocationProvider.propertiesMeetCriteria(
                        name, provider.getPropertiesLocked(), criteria)) {
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
    private void updateProviderUseableLocked(LocationProvider provider) {
        boolean useable = provider.isUseableLocked();

        ArrayList<Receiver> deadReceivers = null;

        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider.getName());
        if (records != null) {
            for (UpdateRecord record : records) {
                if (!isCurrentProfileLocked(
                        UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                    continue;
                }

                // requests that ignore location settings will never provide notifications
                if (isSettingsExemptLocked(record)) {
                    continue;
                }

                // Sends a notification message to the receiver
                if (!record.mReceiver.callProviderEnabledLocked(provider.getName(), useable)) {
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

        applyRequirementsLocked(provider);
    }

    @GuardedBy("mLock")
    private void applyRequirementsLocked(String providerName) {
        LocationProvider provider = getLocationProviderLocked(providerName);
        if (provider != null) {
            applyRequirementsLocked(provider);
        }
    }

    @GuardedBy("mLock")
    private void applyRequirementsLocked(LocationProvider provider) {
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider.getName());
        WorkSource worksource = new WorkSource();
        ProviderRequest providerRequest = new ProviderRequest();

        if (records != null && !records.isEmpty()) {
            long backgroundThrottleInterval;

            long identity = Binder.clearCallingIdentity();
            try {
                backgroundThrottleInterval = Settings.Global.getLong(
                        mContext.getContentResolver(),
                        Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS,
                        DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            final boolean isForegroundOnlyMode =
                    mPowerManager.getLocationPowerSaveMode()
                            == PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
            // initialize the low power mode to true and set to false if any of the records requires
            providerRequest.lowPowerMode = true;
            for (UpdateRecord record : records) {
                if (!isCurrentProfileLocked(
                        UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                    continue;
                }
                if (!checkLocationAccess(
                        record.mReceiver.mCallerIdentity.mPid,
                        record.mReceiver.mCallerIdentity.mUid,
                        record.mReceiver.mCallerIdentity.mPackageName,
                        record.mReceiver.mAllowedResolutionLevel)) {
                    continue;
                }
                final boolean isBatterySaverDisablingLocation =
                        isForegroundOnlyMode && !record.mIsForegroundUid;
                if (!provider.isUseableLocked() || isBatterySaverDisablingLocation) {
                    if (isSettingsExemptLocked(record)) {
                        providerRequest.locationSettingsIgnored = true;
                        providerRequest.lowPowerMode = false;
                    } else {
                        continue;
                    }
                }

                LocationRequest locationRequest = record.mRealRequest;
                long interval = locationRequest.getInterval();


                // if we're forcing location, don't apply any throttling
                if (!providerRequest.locationSettingsIgnored && !isThrottlingExemptLocked(
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
                providerRequest.locationRequests.add(locationRequest);
                if (!locationRequest.isLowPowerMode()) {
                    providerRequest.lowPowerMode = false;
                }
                if (interval < providerRequest.interval) {
                    providerRequest.reportLocation = true;
                    providerRequest.interval = interval;
                }
            }

            if (providerRequest.reportLocation) {
                // calculate who to blame for power
                // This is somewhat arbitrary. We pick a threshold interval
                // that is slightly higher that the minimum interval, and
                // spread the blame across all applications with a request
                // under that threshold.
                long thresholdInterval = (providerRequest.interval + 1000) * 3 / 2;
                for (UpdateRecord record : records) {
                    if (isCurrentProfileLocked(
                            UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                        LocationRequest locationRequest = record.mRequest;

                        // Don't assign battery blame for update records whose
                        // client has no permission to receive location data.
                        if (!providerRequest.locationRequests.contains(locationRequest)) {
                            continue;
                        }

                        if (locationRequest.getInterval() <= thresholdInterval) {
                            if (record.mReceiver.mWorkSource != null
                                    && isValidWorkSource(record.mReceiver.mWorkSource)) {
                                worksource.add(record.mReceiver.mWorkSource);
                            } else {
                                // Assign blame to caller if there's no WorkSource associated with
                                // the request or if it's invalid.
                                worksource.add(
                                        record.mReceiver.mCallerIdentity.mUid,
                                        record.mReceiver.mCallerIdentity.mPackageName);
                            }
                        }
                    }
                }
            }
        }

        if (D) Log.d(TAG, "provider request: " + provider + " " + providerRequest);
        provider.setRequestLocked(providerRequest, worksource);
    }

    /**
     * Whether a given {@code WorkSource} associated with a Location request is valid.
     */
    private static boolean isValidWorkSource(WorkSource workSource) {
        if (workSource.size() > 0) {
            // If the WorkSource has one or more non-chained UIDs, make sure they're accompanied
            // by tags.
            return workSource.getName(0) != null;
        } else {
            // For now, make sure callers have supplied an attribution tag for use with
            // AppOpsManager. This might be relaxed in the future.
            final ArrayList<WorkChain> workChains = workSource.getWorkChains();
            return workChains != null && !workChains.isEmpty() &&
                    workChains.get(0).getAttributionTag() != null;
        }
    }

    @Override
    public String[] getBackgroundThrottlingWhitelist() {
        synchronized (mLock) {
            return mBackgroundThrottlePackageWhitelist.toArray(new String[0]);
        }
    }

    @Override
    public String[] getIgnoreSettingsWhitelist() {
        synchronized (mLock) {
            return mIgnoreSettingsPackageWhitelist.toArray(new String[0]);
        }
    }

    @GuardedBy("mLock")
    private boolean isThrottlingExemptLocked(CallerIdentity callerIdentity) {
        if (callerIdentity.mUid == Process.SYSTEM_UID) {
            return true;
        }

        if (mBackgroundThrottlePackageWhitelist.contains(callerIdentity.mPackageName)) {
            return true;
        }

        return isProviderPackage(callerIdentity.mPackageName);

    }

    @GuardedBy("mLock")
    private boolean isSettingsExemptLocked(UpdateRecord record) {
        if (!record.mRealRequest.isLocationSettingsIgnored()) {
            return false;
        }

        if (mIgnoreSettingsPackageWhitelist.contains(
                record.mReceiver.mCallerIdentity.mPackageName)) {
            return true;
        }

        return isProviderPackage(record.mReceiver.mCallerIdentity.mPackageName);

    }

    private class UpdateRecord {
        final String mProvider;
        private final LocationRequest mRealRequest;  // original request from client
        LocationRequest mRequest;  // possibly throttled version of the request
        private final Receiver mReceiver;
        private boolean mIsForegroundUid;
        private Location mLastFixBroadcast;
        private long mLastStatusBroadcast;

        /**
         * Note: must be constructed with lock held.
         */
        private UpdateRecord(String provider, LocationRequest request, Receiver receiver) {
            mProvider = provider;
            mRealRequest = request;
            mRequest = request;
            mReceiver = receiver;
            mIsForegroundUid = isImportanceForeground(
                    mActivityManager.getPackageImportance(mReceiver.mCallerIdentity.mPackageName));

            ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
            if (records == null) {
                records = new ArrayList<>();
                mRecordsByProvider.put(provider, records);
            }
            if (!records.contains(this)) {
                records.add(this);
            }

            // Update statistics for historical location requests by package/provider
            mRequestStatistics.startRequesting(
                    mReceiver.mCallerIdentity.mPackageName, provider, request.getInterval(),
                    mIsForegroundUid);
        }

        /**
         * Method to be called when record changes foreground/background
         */
        private void updateForeground(boolean isForeground) {
            mIsForegroundUid = isForeground;
            mRequestStatistics.updateForeground(
                    mReceiver.mCallerIdentity.mPackageName, mProvider, isForeground);
        }

        /**
         * Method to be called when a record will no longer be used.
         */
        private void disposeLocked(boolean removeReceiver) {
            mRequestStatistics.stopRequesting(mReceiver.mCallerIdentity.mPackageName, mProvider);

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
            return "UpdateRecord[" + mProvider + " " + mReceiver.mCallerIdentity.mPackageName
                    + "(" + mReceiver.mCallerIdentity.mUid + (mIsForegroundUid ? " foreground"
                    : " background")
                    + ")" + " " + mRealRequest + " "
                    + mReceiver.mWorkSource + "]";
        }
    }

    @GuardedBy("mLock")
    private Receiver getReceiverLocked(ILocationListener listener, int pid, int uid,
            String packageName, WorkSource workSource, boolean hideFromAppOps) {
        IBinder binder = listener.asBinder();
        Receiver receiver = mReceivers.get(binder);
        if (receiver == null) {
            receiver = new Receiver(listener, null, pid, uid, packageName, workSource,
                    hideFromAppOps);
            if (!linkToListenerDeathNotificationLocked(receiver.getListener().asBinder(),
                    receiver)) {
                return null;
            }
            mReceivers.put(binder, receiver);
        }
        return receiver;
    }

    @GuardedBy("mLock")
    private Receiver getReceiverLocked(PendingIntent intent, int pid, int uid, String packageName,
            WorkSource workSource, boolean hideFromAppOps) {
        Receiver receiver = mReceivers.get(intent);
        if (receiver == null) {
            receiver = new Receiver(null, intent, pid, uid, packageName, workSource,
                    hideFromAppOps);
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
            if (sanitizedRequest.getInterval() < LocationFudger.FASTEST_INTERVAL_MS) {
                sanitizedRequest.setInterval(LocationFudger.FASTEST_INTERVAL_MS);
            }
            if (sanitizedRequest.getFastestInterval() < LocationFudger.FASTEST_INTERVAL_MS) {
                sanitizedRequest.setFastestInterval(LocationFudger.FASTEST_INTERVAL_MS);
            }
        }
        // make getFastestInterval() the minimum of interval and fastest interval
        if (sanitizedRequest.getFastestInterval() > sanitizedRequest.getInterval()) {
            sanitizedRequest.setFastestInterval(request.getInterval());
        }
        return sanitizedRequest;
    }

    private void checkPackageName(String packageName) {
        if (packageName == null) {
            throw new SecurityException("invalid package name: " + null);
        }
        int uid = Binder.getCallingUid();
        String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages == null) {
            throw new SecurityException("invalid UID " + uid);
        }
        for (String pkg : packages) {
            if (packageName.equals(pkg)) return;
        }
        throw new SecurityException("invalid package name: " + packageName);
    }

    @Override
    public void requestLocationUpdates(LocationRequest request, ILocationListener listener,
            PendingIntent intent, String packageName) {
        synchronized (mLock) {
            if (request == null) request = DEFAULT_LOCATION_REQUEST;
            checkPackageName(packageName);
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel,
                    request.getProvider());
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

                Receiver receiver;
                if (intent != null) {
                    receiver = getReceiverLocked(intent, pid, uid, packageName, workSource,
                            hideFromAppOps);
                } else {
                    receiver = getReceiverLocked(listener, pid, uid, packageName, workSource,
                            hideFromAppOps);
                }
                requestLocationUpdatesLocked(sanitizedRequest, receiver, uid, packageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    private void requestLocationUpdatesLocked(LocationRequest request, Receiver receiver,
            int uid, String packageName) {
        // Figure out the provider. Either its explicitly request (legacy use cases), or
        // use the fused provider
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        String name = request.getProvider();
        if (name == null) {
            throw new IllegalArgumentException("provider name must not be null");
        }

        LocationProvider provider = getLocationProviderLocked(name);
        if (provider == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + name);
        }

        UpdateRecord record = new UpdateRecord(name, request, receiver);
        if (D) {
            Log.d(TAG, "request " + Integer.toHexString(System.identityHashCode(receiver))
                    + " " + name + " " + request + " from " + packageName + "(" + uid + " "
                    + (record.mIsForegroundUid ? "foreground" : "background")
                    + (isThrottlingExemptLocked(receiver.mCallerIdentity)
                    ? " [whitelisted]" : "") + ")");
        }

        UpdateRecord oldRecord = receiver.mUpdateRecords.put(name, record);
        if (oldRecord != null) {
            oldRecord.disposeLocked(false);
        }

        if (!provider.isUseableLocked() && !isSettingsExemptLocked(record)) {
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
        checkPackageName(packageName);

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
                receiver = getReceiverLocked(intent, pid, uid, packageName, null, false);
            } else {
                receiver = getReceiverLocked(listener, pid, uid, packageName, null, false);
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
            unlinkFromListenerDeathNotificationLocked(receiver.getListener().asBinder(),
                    receiver);
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
    public Location getLastLocation(LocationRequest r, String packageName) {
        if (D) Log.d(TAG, "getLastLocation: " + r);
        synchronized (mLock) {
            LocationRequest request = r != null ? r : DEFAULT_LOCATION_REQUEST;
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            checkPackageName(packageName);
            checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel,
                    request.getProvider());
            // no need to sanitize this request, as only the provider name is used

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                if (mBlacklist.isBlacklisted(packageName)) {
                    if (D) {
                        Log.d(TAG, "not returning last loc for blacklisted app: "
                                + packageName);
                    }
                    return null;
                }


                // Figure out the provider. Either its explicitly request (deprecated API's),
                // or use the fused provider
                String name = request.getProvider();
                if (name == null) name = LocationManager.FUSED_PROVIDER;
                LocationProvider provider = getLocationProviderLocked(name);
                if (provider == null) return null;

                // only the current user or location providers may get location this way
                if (!isCurrentProfileLocked(UserHandle.getUserId(uid)) && !isProviderPackage(
                        packageName)) {
                    return null;
                }

                if (!provider.isUseableLocked()) {
                    return null;
                }

                Location location;
                if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                    // Make sure that an app with coarse permissions can't get frequent location
                    // updates by calling LocationManager.getLastKnownLocation repeatedly.
                    location = mLastLocationCoarseInterval.get(name);
                } else {
                    location = mLastLocation.get(name);
                }
                if (location == null) {
                    return null;
                }

                // Don't return stale location to apps with foreground-only location permission.
                String op = resolutionLevelToOpStr(allowedResolutionLevel);
                long locationAgeMs = SystemClock.elapsedRealtime()
                        - location.getElapsedRealtimeNanos() / NANOS_PER_MILLI;
                if ((locationAgeMs > Settings.Global.getLong(
                        mContext.getContentResolver(),
                        Settings.Global.LOCATION_LAST_LOCATION_MAX_AGE_MILLIS,
                        DEFAULT_LAST_LOCATION_MAX_AGE_MS))
                        && (mAppOps.unsafeCheckOp(op, uid, packageName)
                        == AppOpsManager.MODE_FOREGROUND)) {
                    return null;
                }

                Location lastLocation = null;
                if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                    Location noGPSLocation = location.getExtraLocation(
                            Location.EXTRA_NO_GPS_LOCATION);
                    if (noGPSLocation != null) {
                        lastLocation = new Location(mLocationFudger.getOrCreate(noGPSLocation));
                    }
                } else {
                    lastLocation = new Location(location);
                }
                // Don't report location access if there is no last location to deliver.
                if (lastLocation != null) {
                    if (!reportLocationAccessNoThrow(
                            pid, uid, packageName, allowedResolutionLevel)) {
                        if (D) {
                            Log.d(TAG, "not returning last loc for no op app: " + packageName);
                        }
                        lastLocation =  null;
                    }
                }
                return lastLocation;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public boolean injectLocation(Location location) {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to inject location");
        mContext.enforceCallingPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                "Access Fine Location permission not granted to inject Location");

        if (location == null) {
            if (D) {
                Log.d(TAG, "injectLocation(): called with null location");
            }
            return false;
        }

        synchronized (mLock) {
            LocationProvider provider = getLocationProviderLocked(location.getProvider());
            if (provider == null || !provider.isUseableLocked()) {
                return false;
            }

            // NOTE: If last location is already available, location is not injected.  If
            // provider's normal source (like a GPS chipset) have already provided an output
            // there is no need to inject this location.
            if (mLastLocation.get(provider.getName()) != null) {
                return false;
            }

            updateLastLocationLocked(location, provider.getName());
            return true;
        }
    }

    @Override
    public void requestGeofence(LocationRequest request, Geofence geofence, PendingIntent intent,
            String packageName) {
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForGeofenceUse(allowedResolutionLevel);
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + null);
        }
        checkPackageName(packageName);
        synchronized (mLock) {
            checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel,
                    request.getProvider());
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
            long identity = Binder.clearCallingIdentity();
            try {
                mGeofenceManager.addFence(sanitizedRequest, geofence, intent,
                        allowedResolutionLevel,
                        uid, packageName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void removeGeofence(Geofence geofence, PendingIntent intent, String packageName) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + null);
        }
        checkPackageName(packageName);

        if (D) Log.d(TAG, "removeGeofence: " + geofence + " " + intent);

        // geo-fence manager uses the public location API, need to clear identity
        long identity = Binder.clearCallingIdentity();
        try {
            mGeofenceManager.removeFence(geofence, intent);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean registerGnssStatusCallback(IGnssStatusListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssStatusListener",
                mGnssStatusProvider, mGnssStatusListeners,
                this::unregisterGnssStatusCallback);
    }

    @Override
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        removeGnssDataListener(listener, mGnssStatusProvider, mGnssStatusListeners);
    }

    @Override
    public boolean addGnssMeasurementsListener(
            IGnssMeasurementsListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssMeasurementsListener",
                mGnssMeasurementsProvider, mGnssMeasurementsListeners,
                this::removeGnssMeasurementsListener);
    }

    @Override
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        removeGnssDataListener(listener, mGnssMeasurementsProvider, mGnssMeasurementsListeners);
    }

    private abstract static class LinkedListenerBase implements IBinder.DeathRecipient {
        protected final CallerIdentity mCallerIdentity;
        protected final String mListenerName;

        private LinkedListenerBase(@NonNull CallerIdentity callerIdentity,
                @NonNull String listenerName) {
            mCallerIdentity = callerIdentity;
            mListenerName = listenerName;
        }
    }

    private static class LinkedListener<TListener> extends LinkedListenerBase {
        private final TListener mListener;
        private final Consumer<TListener> mBinderDeathCallback;

        private LinkedListener(@NonNull TListener listener, String listenerName,
                @NonNull CallerIdentity callerIdentity,
                @NonNull Consumer<TListener> binderDeathCallback) {
            super(callerIdentity, listenerName);
            mListener = listener;
            mBinderDeathCallback = binderDeathCallback;
        }

        @Override
        public void binderDied() {
            if (D) Log.d(TAG, "Remote " + mListenerName + " died.");
            mBinderDeathCallback.accept(mListener);
        }
    }

    private <TListener extends IInterface> boolean addGnssDataListener(
            TListener listener, String packageName, String listenerName,
            RemoteListenerHelper<TListener> gnssDataProvider,
            ArrayMap<IBinder, LinkedListener<TListener>> gnssDataListeners,
            Consumer<TListener> binderDeathCallback) {
        if (!hasGnssPermissions(packageName) || gnssDataProvider == null) {
            return false;
        }

        CallerIdentity callerIdentity = new CallerIdentity(Binder.getCallingUid(),
                Binder.getCallingPid(), packageName);
        LinkedListener<TListener> linkedListener = new LinkedListener<>(listener,
                listenerName, callerIdentity, binderDeathCallback);
        IBinder binder = listener.asBinder();
        synchronized (mLock) {
            if (!linkToListenerDeathNotificationLocked(binder, linkedListener)) {
                return false;
            }

            gnssDataListeners.put(binder, linkedListener);
            long identity = Binder.clearCallingIdentity();
            try {
                if (isThrottlingExemptLocked(callerIdentity)
                        || isImportanceForeground(
                        mActivityManager.getPackageImportance(packageName))) {
                    gnssDataProvider.addListener(listener, callerIdentity);
                }
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private <TListener extends IInterface> void removeGnssDataListener(
            TListener listener, RemoteListenerHelper<TListener> gnssDataProvider,
            ArrayMap<IBinder, LinkedListener<TListener>> gnssDataListeners) {
        if (gnssDataProvider == null) {
            return;
        }

        IBinder binder = listener.asBinder();
        synchronized (mLock) {
            LinkedListener<TListener> linkedListener = gnssDataListeners.remove(binder);
            if (linkedListener == null) {
                return;
            }
            unlinkFromListenerDeathNotificationLocked(binder, linkedListener);
            gnssDataProvider.removeListener(listener);
        }
    }

    private boolean linkToListenerDeathNotificationLocked(IBinder binder,
            LinkedListenerBase linkedListener) {
        try {
            binder.linkToDeath(linkedListener, 0 /* flags */);
            return true;
        } catch (RemoteException e) {
            // if the remote process registering the listener is already dead, just swallow the
            // exception and return
            Log.w(TAG, "Could not link " + linkedListener.mListenerName + " death callback.", e);
            return false;
        }
    }

    private boolean unlinkFromListenerDeathNotificationLocked(IBinder binder,
            LinkedListenerBase linkedListener) {
        try {
            binder.unlinkToDeath(linkedListener, 0 /* flags */);
            return true;
        } catch (NoSuchElementException e) {
            // if the death callback isn't connected (it should be...), log error,
            // swallow the exception and return
            Log.w(TAG, "Could not unlink " + linkedListener.mListenerName + " death callback.", e);
            return false;
        }
    }

    @Override
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections, String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to inject GNSS measurement corrections.");
        if (!hasGnssPermissions(packageName) || mGnssMeasurementsProvider == null) {
            Slog.e(TAG, "Can not inject GNSS corrections due to no permission.");
        } else {
            mGnssMeasurementsProvider.injectGnssMeasurementCorrections(measurementCorrections);
        }
    }

    @Override
    public int getGnssCapabilities(String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to obrain GNSS chipset capabilities.");
        if (!hasGnssPermissions(packageName) || mGnssMeasurementsProvider == null) {
            return -1;
        }
        return mGnssMeasurementsProvider.getGnssCapabilities();
    }

    @Override
    public boolean addGnssNavigationMessageListener(
            IGnssNavigationMessageListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssNavigationMessageListener",
                mGnssNavigationMessageProvider, mGnssNavigationMessageListeners,
                this::removeGnssNavigationMessageListener);
    }

    @Override
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        removeGnssDataListener(listener, mGnssNavigationMessageProvider,
                mGnssNavigationMessageListeners);
    }

    @Override
    public boolean sendExtraCommand(String providerName, String command, Bundle extras) {
        if (providerName == null) {
            // throw NullPointerException to remain compatible with previous implementation
            throw new NullPointerException();
        }
        synchronized (mLock) {
            checkResolutionLevelIsSufficientForProviderUseLocked(getCallerAllowedResolutionLevel(),
                    providerName);

            // and check for ACCESS_LOCATION_EXTRA_COMMANDS
            if ((mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                    != PERMISSION_GRANTED)) {
                throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
            }

            LocationProvider provider = getLocationProviderLocked(providerName);
            if (provider != null) {
                provider.sendExtraCommandLocked(command, extras);
            }

            return true;
        }
    }

    @Override
    public boolean sendNiResponse(int notifId, int userResponse) {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException(
                    "calling sendNiResponse from outside of the system is not allowed");
        }
        try {
            return mNetInitiatedListener.sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in LocationManagerService.sendNiResponse");
            return false;
        }
    }

    @Override
    public ProviderProperties getProviderProperties(String providerName) {
        synchronized (mLock) {
            checkResolutionLevelIsSufficientForProviderUseLocked(getCallerAllowedResolutionLevel(),
                    providerName);

            LocationProvider provider = getLocationProviderLocked(providerName);
            if (provider == null) {
                return null;
            }
            return provider.getPropertiesLocked();
        }
    }

    @Override
    public boolean isProviderPackage(String packageName) {
        synchronized (mLock) {
            for (LocationProvider provider : mProviders) {
                if (provider.getPackagesLocked().contains(packageName)) {
                    return true;
                }
            }

            return false;
        }
    }

    @Override
    public void setLocationControllerExtraPackage(String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                Manifest.permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mLocationControllerExtraPackage = packageName;
        }
    }

    @Override
    public String getLocationControllerExtraPackage() {
        synchronized (mLock) {
            return mLocationControllerExtraPackage;
        }
    }

    @Override
    public void setLocationControllerExtraPackageEnabled(boolean enabled) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE,
                Manifest.permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mLocationControllerExtraPackageEnabled = enabled;
        }
    }

    @Override
    public boolean isLocationControllerExtraPackageEnabled() {
        synchronized (mLock) {
            return mLocationControllerExtraPackageEnabled
                    && (mLocationControllerExtraPackage != null);
        }
    }

    private boolean isLocationEnabled() {
        return isLocationEnabledForUser(mCurrentUserId);
    }

    @Override
    public boolean isLocationEnabledForUser(int userId) {
        // Check INTERACT_ACROSS_USERS permission if userId is not current user id.
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    "Requires INTERACT_ACROSS_USERS permission");
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF,
                        userId) != Settings.Secure.LOCATION_MODE_OFF;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean isProviderEnabledForUser(String providerName, int userId) {
        // Check INTERACT_ACROSS_USERS permission if userId is not current user id.
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    "Requires INTERACT_ACROSS_USERS permission");
        }

        // Fused provider is accessed indirectly via criteria rather than the provider-based APIs,
        // so we discourage its use
        if (FUSED_PROVIDER.equals(providerName)) return false;

        synchronized (mLock) {
            LocationProvider provider = getLocationProviderLocked(providerName);
            return provider != null && provider.isUseableForUserLocked(userId);
        }
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
        long delta = (loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos())
                / NANOS_PER_MILLI;
        if (delta < minTime - MAX_PROVIDER_SCHEDULING_JITTER_MS) {
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
        return record.mRealRequest.getExpireAt() >= now;
    }

    @GuardedBy("mLock")
    private void handleLocationChangedLocked(Location location, LocationProvider provider) {
        if (!mProviders.contains(provider)) {
            return;
        }
        if (!location.isComplete()) {
            Log.w(TAG, "Dropping incomplete location: " + location);
            return;
        }

        // only notify passive provider and update last location for locations that come from
        // useable providers
        if (provider.isUseableLocked()) {
            if (!provider.isPassiveLocked()) {
                mPassiveProvider.updateLocation(location);
            }
        }

        if (D) Log.d(TAG, "incoming location: " + location);
        long now = SystemClock.elapsedRealtime();
        if (provider.isUseableLocked()) {
            updateLastLocationLocked(location, provider.getName());
        }

        // Update last known coarse interval location if enough time has passed.
        Location lastLocationCoarseInterval = mLastLocationCoarseInterval.get(
                provider.getName());
        if (lastLocationCoarseInterval == null) {
            lastLocationCoarseInterval = new Location(location);

            if (provider.isUseableLocked()) {
                mLastLocationCoarseInterval.put(provider.getName(), lastLocationCoarseInterval);
            }
        }
        long timeDiffNanos = location.getElapsedRealtimeNanos()
                - lastLocationCoarseInterval.getElapsedRealtimeNanos();
        if (timeDiffNanos > LocationFudger.FASTEST_INTERVAL_MS * NANOS_PER_MILLI) {
            lastLocationCoarseInterval.set(location);
        }
        // Don't ever return a coarse location that is more recent than the allowed update
        // interval (i.e. don't allow an app to keep registering and unregistering for
        // location updates to overcome the minimum interval).
        Location noGPSLocation =
                lastLocationCoarseInterval.getExtraLocation(Location.EXTRA_NO_GPS_LOCATION);

        // Skip if there are no UpdateRecords for this provider.
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider.getName());
        if (records == null || records.size() == 0) return;

        // Fetch coarse location
        Location coarseLocation = null;
        if (noGPSLocation != null) {
            coarseLocation = mLocationFudger.getOrCreate(noGPSLocation);
        }

        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> deadUpdateRecords = null;

        // Broadcast location to all listeners
        for (UpdateRecord r : records) {
            Receiver receiver = r.mReceiver;
            boolean receiverDead = false;

            if (!provider.isUseableLocked() && !isSettingsExemptLocked(r)) {
                continue;
            }

            int receiverUserId = UserHandle.getUserId(receiver.mCallerIdentity.mUid);
            if (!isCurrentProfileLocked(receiverUserId)
                    && !isProviderPackage(receiver.mCallerIdentity.mPackageName)) {
                if (D) {
                    Log.d(TAG, "skipping loc update for background user " + receiverUserId +
                            " (current user: " + mCurrentUserId + ", app: " +
                            receiver.mCallerIdentity.mPackageName + ")");
                }
                continue;
            }

            if (mBlacklist.isBlacklisted(receiver.mCallerIdentity.mPackageName)) {
                if (D) {
                    Log.d(TAG, "skipping loc update for blacklisted app: " +
                            receiver.mCallerIdentity.mPackageName);
                }
                continue;
            }

            if (!reportLocationAccessNoThrow(
                    receiver.mCallerIdentity.mPid,
                    receiver.mCallerIdentity.mUid,
                    receiver.mCallerIdentity.mPackageName,
                    receiver.mAllowedResolutionLevel)) {
                if (D) {
                    Log.d(TAG, "skipping loc update for no op app: " +
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
            if (notifyLocation != null) {
                Location lastLoc = r.mLastFixBroadcast;
                if ((lastLoc == null)
                        || shouldBroadcastSafeLocked(notifyLocation, lastLoc, r, now)) {
                    if (lastLoc == null) {
                        lastLoc = new Location(notifyLocation);
                        r.mLastFixBroadcast = lastLoc;
                    } else {
                        lastLoc.set(notifyLocation);
                    }
                    if (!receiver.callLocationChangedLocked(notifyLocation)) {
                        Slog.w(TAG, "RemoteException calling onLocationChanged on "
                                + receiver);
                        receiverDead = true;
                    }
                    r.mRealRequest.decrementNumUpdates();
                }
            }

            // TODO: location provider status callbacks have been disabled and deprecated, and are
            // guarded behind this setting now. should be removed completely post-Q
            if (Settings.Global.getInt(mContext.getContentResolver(),
                    LOCATION_DISABLE_STATUS_CALLBACKS, 1) == 0) {
                long newStatusUpdateTime = provider.getStatusUpdateTimeLocked();
                Bundle extras = new Bundle();
                int status = provider.getStatusLocked(extras);

                long prevStatusUpdateTime = r.mLastStatusBroadcast;
                if ((newStatusUpdateTime > prevStatusUpdateTime)
                        && (prevStatusUpdateTime != 0 || status != AVAILABLE)) {

                    r.mLastStatusBroadcast = newStatusUpdateTime;
                    if (!receiver.callStatusChangedLocked(provider.getName(), status, extras)) {
                        receiverDead = true;
                        Slog.w(TAG, "RemoteException calling onStatusChanged on " + receiver);
                    }
                }
            }

            // track expired records
            if (r.mRealRequest.getNumUpdates() <= 0 || r.mRealRequest.getExpireAt() < now) {
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
            applyRequirementsLocked(provider);
        }
    }

    @GuardedBy("mLock")
    private void updateLastLocationLocked(Location location, String provider) {
        Location noGPSLocation = location.getExtraLocation(Location.EXTRA_NO_GPS_LOCATION);
        Location lastNoGPSLocation;
        Location lastLocation = mLastLocation.get(provider);
        if (lastLocation == null) {
            lastLocation = new Location(provider);
            mLastLocation.put(provider, lastLocation);
        } else {
            lastNoGPSLocation = lastLocation.getExtraLocation(Location.EXTRA_NO_GPS_LOCATION);
            if (noGPSLocation == null && lastNoGPSLocation != null) {
                // New location has no no-GPS location: adopt last no-GPS location. This is set
                // directly into location because we do not want to notify COARSE clients.
                location.setExtraLocation(Location.EXTRA_NO_GPS_LOCATION, lastNoGPSLocation);
            }
        }
        lastLocation.set(location);
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

    private boolean canCallerAccessMockLocation(String opPackageName) {
        return mAppOps.checkOp(AppOpsManager.OP_MOCK_LOCATION, Binder.getCallingUid(),
                opPackageName) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void addTestProvider(String name, ProviderProperties properties, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }

        if (PASSIVE_PROVIDER.equals(name)) {
            throw new IllegalArgumentException("Cannot mock the passive location provider");
        }

        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                LocationProvider oldProvider = getLocationProviderLocked(name);
                if (oldProvider != null) {
                    if (oldProvider.isMock()) {
                        throw new IllegalArgumentException(
                                "Provider \"" + name + "\" already exists");
                    }

                    removeProviderLocked(oldProvider);
                }

                MockLocationProvider mockProviderManager = new MockLocationProvider(name);
                addProviderLocked(mockProviderManager);
                mockProviderManager.attachLocked(
                        new MockProvider(mContext, mockProviderManager, properties));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void removeTestProvider(String name, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }

        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                LocationProvider testProvider = getLocationProviderLocked(name);
                if (testProvider == null || !testProvider.isMock()) {
                    throw new IllegalArgumentException("Provider \"" + name + "\" unknown");
                }

                removeProviderLocked(testProvider);

                // reinstate real provider if available
                LocationProvider realProvider = null;
                for (LocationProvider provider : mRealProviders) {
                    if (name.equals(provider.getName())) {
                        realProvider = provider;
                        break;
                    }
                }

                if (realProvider != null) {
                    addProviderLocked(realProvider);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void setTestProviderLocation(String providerName, Location location,
            String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }

        synchronized (mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }

            String locationProvider = location.getProvider();
            if (!TextUtils.isEmpty(locationProvider) && !providerName.equals(locationProvider)) {
                // The location has an explicit provider that is different from the mock
                // provider name. The caller may be trying to fool us via b/33091107.
                EventLog.writeEvent(0x534e4554, "33091107", Binder.getCallingUid(),
                        providerName + "!=" + location.getProvider());
            }

            ((MockLocationProvider) testProvider).setLocationLocked(location);
        }
    }

    @Override
    public void setTestProviderEnabled(String providerName, boolean enabled, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }

        synchronized (mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }

            ((MockLocationProvider) testProvider).setEnabledLocked(enabled);
        }
    }

    @Override
    public void setTestProviderStatus(String providerName, int status, Bundle extras,
            long updateTime, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }

        synchronized (mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }

            ((MockLocationProvider) testProvider).setStatusLocked(status, extras, updateTime);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            if (args.length > 0 && args[0].equals("--gnssmetrics")) {
                if (mGnssMetricsProvider != null) {
                    pw.append(mGnssMetricsProvider.getGnssMetricsAsProtoString());
                }
                return;
            }
            pw.println("Current Location Manager state:");
            pw.println("  Current user: " + mCurrentUserId + " " + Arrays.toString(
                    mCurrentUserProfiles));
            pw.println("  Location mode: " + isLocationEnabled());
            pw.println("  Location Listeners:");
            for (Receiver receiver : mReceivers.values()) {
                pw.println("    " + receiver);
            }
            pw.println("  Active Records by Provider:");
            for (Map.Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
                pw.println("    " + entry.getKey() + ":");
                for (UpdateRecord record : entry.getValue()) {
                    pw.println("      " + record);
                }
            }

            pw.println("  Active GnssMeasurement Listeners:");
            dumpGnssDataListenersLocked(pw, mGnssMeasurementsListeners);
            pw.println("  Active GnssNavigationMessage Listeners:");
            dumpGnssDataListenersLocked(pw, mGnssNavigationMessageListeners);
            pw.println("  Active GnssStatus Listeners:");
            dumpGnssDataListenersLocked(pw, mGnssStatusListeners);

            pw.println("  Historical Records by Provider:");
            for (Map.Entry<PackageProviderKey, PackageStatistics> entry
                    : mRequestStatistics.statistics.entrySet()) {
                PackageProviderKey key = entry.getKey();
                PackageStatistics stats = entry.getValue();
                pw.println("    " + key.packageName + ": " + key.providerName + ": " + stats);
            }
            pw.println("  Last Known Locations:");
            for (Map.Entry<String, Location> entry : mLastLocation.entrySet()) {
                String provider = entry.getKey();
                Location location = entry.getValue();
                pw.println("    " + provider + ": " + location);
            }

            pw.println("  Last Known Locations Coarse Intervals:");
            for (Map.Entry<String, Location> entry : mLastLocationCoarseInterval.entrySet()) {
                String provider = entry.getKey();
                Location location = entry.getValue();
                pw.println("    " + provider + ": " + location);
            }

            mGeofenceManager.dump(pw);

            pw.append("  ");
            mBlacklist.dump(pw);

            if (mLocationControllerExtraPackage != null) {
                pw.println(" Location controller extra package: " + mLocationControllerExtraPackage
                        + " enabled: " + mLocationControllerExtraPackageEnabled);
            }

            if (!mBackgroundThrottlePackageWhitelist.isEmpty()) {
                pw.println("  Throttling Whitelisted Packages:");
                for (String packageName : mBackgroundThrottlePackageWhitelist) {
                    pw.println("    " + packageName);
                }
            }

            pw.append("  fudger: ");
            mLocationFudger.dump(fd, pw, args);

            if (args.length > 0 && "short".equals(args[0])) {
                return;
            }
            for (LocationProvider provider : mProviders) {
                provider.dumpLocked(fd, pw, args);
            }
            if (mGnssBatchingInProgress) {
                pw.println("  GNSS batching in progress");
            }
        }
    }

    @GuardedBy("mLock")
    private void dumpGnssDataListenersLocked(PrintWriter pw,
            ArrayMap<IBinder, ? extends LinkedListenerBase> gnssDataListeners) {
        for (LinkedListenerBase listener : gnssDataListeners.values()) {
            CallerIdentity callerIdentity = listener.mCallerIdentity;
            pw.println("    " + callerIdentity.mPid + " " + callerIdentity.mUid + " "
                    + callerIdentity.mPackageName + ": "
                    + isThrottlingExemptLocked(callerIdentity));
        }
    }
}
