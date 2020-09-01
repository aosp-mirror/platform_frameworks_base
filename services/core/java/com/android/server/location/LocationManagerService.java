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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;

import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationProviderManager.FASTEST_COARSE_INTERVAL_MS;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssRequest;
import android.location.IBatchedLocationCallback;
import android.location.IGeocodeListener;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.stats.location.LocationStatsEnums;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ProviderProperties;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.location.LocationPermissions.PermissionLevel;
import com.android.server.location.LocationRequestStatistics.PackageProviderKey;
import com.android.server.location.LocationRequestStatistics.PackageStatistics;
import com.android.server.location.geofence.GeofenceManager;
import com.android.server.location.geofence.GeofenceProxy;
import com.android.server.location.gnss.GnssManagerService;
import com.android.server.location.util.AppForegroundHelper;
import com.android.server.location.util.AppOpsHelper;
import com.android.server.location.util.Injector;
import com.android.server.location.util.LocationAttributionHelper;
import com.android.server.location.util.LocationPermissionsHelper;
import com.android.server.location.util.LocationPowerSaveModeHelper;
import com.android.server.location.util.LocationUsageLogger;
import com.android.server.location.util.ScreenInteractiveHelper;
import com.android.server.location.util.SettingsHelper;
import com.android.server.location.util.SystemAppForegroundHelper;
import com.android.server.location.util.SystemAppOpsHelper;
import com.android.server.location.util.SystemLocationPermissionsHelper;
import com.android.server.location.util.SystemLocationPowerSaveModeHelper;
import com.android.server.location.util.SystemScreenInteractiveHelper;
import com.android.server.location.util.SystemSettingsHelper;
import com.android.server.location.util.SystemUserInfoHelper;
import com.android.server.location.util.UserInfoHelper;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        private final LifecycleUserInfoHelper mUserInfoHelper;
        private final SystemInjector mSystemInjector;
        private final LocationManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mUserInfoHelper = new LifecycleUserInfoHelper(context);
            mSystemInjector = new SystemInjector(context, mUserInfoHelper);
            mService = new LocationManagerService(context, mSystemInjector);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.LOCATION_SERVICE, mService);

            // client caching behavior is only enabled after seeing the first invalidate
            LocationManager.invalidateLocalLocationEnabledCaches();
            // disable caching for our own process
            Objects.requireNonNull(mService.mContext.getSystemService(LocationManager.class))
                    .disableLocalLocationEnabledCaches();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                // the location service must be functioning after this boot phase
                mSystemInjector.onSystemReady();
                mService.onSystemReady();
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                // some providers rely on third party code, so we wait to initialize
                // providers until third party code is allowed to run
                mService.onSystemThirdPartyAppsCanStart();
            }
        }

        @Override
        public void onUserStarting(TargetUser user) {
            mUserInfoHelper.onUserStarted(user.getUserIdentifier());
        }

        @Override
        public void onUserSwitching(TargetUser from, TargetUser to) {
            mUserInfoHelper.onCurrentUserChanged(from.getUserIdentifier(),
                    to.getUserIdentifier());
        }

        @Override
        public void onUserStopped(TargetUser user) {
            mUserInfoHelper.onUserStopped(user.getUserIdentifier());
        }

        private static class LifecycleUserInfoHelper extends SystemUserInfoHelper {

            LifecycleUserInfoHelper(Context context) {
                super(context);
            }

            void onUserStarted(int userId) {
                dispatchOnUserStarted(userId);
            }

            void onUserStopped(int userId) {
                dispatchOnUserStopped(userId);
            }

            void onCurrentUserChanged(int fromUserId, int toUserId) {
                dispatchOnCurrentUserChanged(fromUserId, toUserId);
            }
        }
    }

    public static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETWORK_LOCATION_SERVICE_ACTION =
            "com.android.location.service.v3.NetworkLocationProvider";
    private static final String FUSED_LOCATION_SERVICE_ACTION =
            "com.android.location.service.FusedLocationProvider";

    private static final String ATTRIBUTION_TAG = "LocationService";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Injector mInjector;
    private final LocalService mLocalService;

    private final GeofenceManager mGeofenceManager;
    @Nullable private volatile GnssManagerService mGnssManagerService = null;
    private GeocoderProxy mGeocodeProvider;

    @GuardedBy("mLock")
    private String mExtraLocationControllerPackage;
    @GuardedBy("mLock")
    private boolean mExtraLocationControllerPackageEnabled;

    // location provider managers

    private final PassiveLocationProviderManager mPassiveManager;

    // @GuardedBy("mProviderManagers")
    // hold lock for writes, no lock necessary for simple reads
    private final CopyOnWriteArrayList<LocationProviderManager> mProviderManagers =
            new CopyOnWriteArrayList<>();

    LocationManagerService(Context context, Injector injector) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mInjector = injector;

        mLocalService = new LocalService();
        LocalServices.addService(LocationManagerInternal.class, mLocalService);

        mGeofenceManager = new GeofenceManager(mContext, injector);

        // set up passive provider first since it will be required for all other location providers,
        // which are loaded later once the system is ready.
        mPassiveManager = new PassiveLocationProviderManager(mContext, injector);
        addLocationProviderManager(mPassiveManager, new PassiveProvider(mContext));

        // TODO: load the gps provider here as well, which will require refactoring

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
    }

    @Nullable
    private LocationProviderManager getLocationProviderManager(String providerName) {
        if (providerName == null) {
            return null;
        }

        for (LocationProviderManager manager : mProviderManagers) {
            if (providerName.equals(manager.getName())) {
                return manager;
            }
        }

        return null;
    }

    private LocationProviderManager getOrAddLocationProviderManager(String providerName) {
        synchronized (mProviderManagers) {
            for (LocationProviderManager manager : mProviderManagers) {
                if (providerName.equals(manager.getName())) {
                    return manager;
                }
            }

            LocationProviderManager manager = new LocationProviderManager(mContext, mInjector,
                    providerName, mPassiveManager);
            addLocationProviderManager(manager, null);
            return manager;
        }
    }

    private void addLocationProviderManager(LocationProviderManager manager,
            @Nullable AbstractLocationProvider realProvider) {
        synchronized (mProviderManagers) {
            Preconditions.checkState(getLocationProviderManager(manager.getName()) == null);

            manager.startManager();
            if (realProvider != null) {
                manager.setRealProvider(realProvider);
            }
            mProviderManagers.add(manager);
        }
    }

    private void removeLocationProviderManager(LocationProviderManager manager) {
        synchronized (mProviderManagers) {
            Preconditions.checkState(getLocationProviderManager(manager.getName()) == manager);

            mProviderManagers.remove(manager);
            manager.setMockProvider(null);
            manager.setRealProvider(null);
            manager.stopManager();
        }
    }

    void onSystemReady() {
        mInjector.getSettingsHelper().addOnLocationEnabledChangedListener(
                this::onLocationModeChanged);
    }

    void onSystemThirdPartyAppsCanStart() {
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndRegister(
                mContext,
                NETWORK_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableNetworkLocationOverlay,
                com.android.internal.R.string.config_networkLocationProviderPackageName);
        if (networkProvider != null) {
            LocationProviderManager networkManager = new LocationProviderManager(mContext,
                    mInjector, NETWORK_PROVIDER, mPassiveManager);
            addLocationProviderManager(networkManager, networkProvider);
        } else {
            Log.w(TAG, "no network location provider found");
        }

        // ensure that a fused provider exists which will work in direct boot
        Preconditions.checkState(!mContext.getPackageManager().queryIntentServicesAsUser(
                new Intent(FUSED_LOCATION_SERVICE_ACTION),
                MATCH_DIRECT_BOOT_AWARE | MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM).isEmpty(),
                "Unable to find a direct boot aware fused location provider");

        LocationProviderProxy fusedProvider = LocationProviderProxy.createAndRegister(
                mContext,
                FUSED_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableFusedLocationOverlay,
                com.android.internal.R.string.config_fusedLocationProviderPackageName);
        if (fusedProvider != null) {
            LocationProviderManager fusedManager = new LocationProviderManager(mContext, mInjector,
                    FUSED_PROVIDER, mPassiveManager);
            addLocationProviderManager(fusedManager, fusedProvider);
        } else {
            Log.wtf(TAG, "no fused location provider found");
        }

        // initialize gnss last because it has no awareness of boot phases and blindly assumes that
        // all other location providers are loaded at initialization
        if (GnssManagerService.isGnssSupported()) {
            mGnssManagerService = new GnssManagerService(mContext, mInjector);
            mGnssManagerService.onSystemReady();

            LocationProviderManager gnssManager = new LocationProviderManager(mContext, mInjector,
                    GPS_PROVIDER, mPassiveManager);
            addLocationProviderManager(gnssManager, mGnssManagerService.getGnssLocationProvider());
        }

        // bind to geocoder provider
        mGeocodeProvider = GeocoderProxy.createAndRegister(mContext);
        if (mGeocodeProvider == null) {
            Log.e(TAG, "no geocoder provider found");
        }

        // bind to hardware activity recognition
        HardwareActivityRecognitionProxy hardwareActivityRecognitionProxy =
                HardwareActivityRecognitionProxy.createAndRegister(mContext);
        if (hardwareActivityRecognitionProxy == null) {
            Log.e(TAG, "unable to bind ActivityRecognitionProxy");
        }

        // bind to gnss geofence proxy
        if (GnssManagerService.isGnssSupported()) {
            IGpsGeofenceHardware gpsGeofenceHardware = mGnssManagerService.getGpsGeofenceProxy();
            if (gpsGeofenceHardware != null) {
                GeofenceProxy provider = GeofenceProxy.createAndBind(mContext, gpsGeofenceHardware);
                if (provider == null) {
                    Log.e(TAG, "unable to bind to GeofenceProxy");
                }
            }
        }

        // create any predefined test providers
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
            getOrAddLocationProviderManager(name).setMockProvider(
                    new MockProvider(properties, CallerIdentity.fromContext(mContext)));
        }
    }

    private void onLocationModeChanged(int userId) {
        boolean enabled = mInjector.getSettingsHelper().isLocationEnabled(userId);
        LocationManager.invalidateLocalLocationEnabledCaches();

        if (D) {
            Log.d(TAG, "[u" + userId + "] location enabled = " + enabled);
        }

        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION)
                .putExtra(LocationManager.EXTRA_LOCATION_ENABLED, enabled)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
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
    public int getGnssBatchSize() {
        return mGnssManagerService == null ? 0 : mGnssManagerService.getGnssBatchSize();
    }

    @Override
    public void setGnssBatchingCallback(IBatchedLocationCallback callback, String packageName,
            String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.setGnssBatchingCallback(callback, packageName, attributionTag);
        }
    }

    @Override
    public void removeGnssBatchingCallback() {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssBatchingCallback();
        }
    }

    @Override
    public void startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName,
            String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.startGnssBatch(periodNanos, wakeOnFifoFull, packageName,
                    attributionTag);
        }
    }

    @Override
    public void flushGnssBatch() {
        if (mGnssManagerService != null) {
            mGnssManagerService.flushGnssBatch();
        }
    }

    @Override
    public void stopGnssBatch() {
        if (mGnssManagerService != null) {
            mGnssManagerService.stopGnssBatch();
        }
    }

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

    @Override
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        if (!LocationPermissions.checkCallingOrSelfLocationPermission(mContext,
                PERMISSION_COARSE)) {
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
                if (criteria != null && !LocationProvider.propertiesMeetCriteria(name,
                        manager.getProperties(), criteria)) {
                    continue;
                }
                providers.add(name);
            }
            return providers;
        }
    }

    @Override
    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers;
        synchronized (mLock) {
            providers = getProviders(criteria, enabledOnly);
            if (providers.isEmpty()) {
                providers = getProviders(null, enabledOnly);
            }
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

    @Override
    public String[] getBackgroundThrottlingWhitelist() {
        return mInjector.getSettingsHelper().getBackgroundThrottlePackageWhitelist().toArray(
                new String[0]);
    }

    @Override
    public String[] getIgnoreSettingsWhitelist() {
        return mInjector.getSettingsHelper().getIgnoreSettingsPackageWhitelist().toArray(
                new String[0]);
    }

    @Override
    public void registerLocationListener(LocationRequest request, ILocationListener listener,
            String packageName, String attributionTag, String listenerId) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process should have an attribution tag set
        if (identity.getPid() == Process.myPid() && attributionTag == null) {
            Log.w(TAG, "system location request with no attribution tag",
                    new IllegalArgumentException());
        }

        request = validateAndSanitizeLocationRequest(request, permissionLevel);

        LocationProviderManager manager = getLocationProviderManager(request.getProvider());
        Preconditions.checkArgument(manager != null,
                "provider \"" + request.getProvider() + "\" does not exist");

        manager.registerLocationRequest(request, identity, permissionLevel, listener);
    }

    @Override
    public void registerLocationPendingIntent(LocationRequest request, PendingIntent pendingIntent,
            String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                AppOpsManager.toReceiverId(pendingIntent));
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkArgument(identity.getPid() != Process.myPid() || attributionTag != null);

        request = validateAndSanitizeLocationRequest(request, permissionLevel);

        LocationProviderManager manager = getLocationProviderManager(request.getProvider());
        Preconditions.checkArgument(manager != null,
                "provider \"" + request.getProvider() + "\" does not exist");

        manager.registerLocationRequest(request, identity, permissionLevel, pendingIntent);
    }

    private LocationRequest validateAndSanitizeLocationRequest(LocationRequest request,
            @PermissionLevel int permissionLevel) {
        Objects.requireNonNull(request.getProvider());

        WorkSource workSource = request.getWorkSource();
        if (workSource != null && !workSource.isEmpty()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.UPDATE_DEVICE_STATS,
                    "setting a work source requires " + permission.UPDATE_DEVICE_STATS);
        }
        if (request.getHideFromAppOps()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.UPDATE_APP_OPS_STATS,
                    "hiding from app ops requires " + permission.UPDATE_APP_OPS_STATS);
        }
        if (request.isLocationSettingsIgnored()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.WRITE_SECURE_SETTINGS,
                    "ignoring location settings requires " + permission.WRITE_SECURE_SETTINGS);
        }

        LocationRequest sanitized = new LocationRequest(request);
        if (mContext.checkCallingPermission(permission.LOCATION_HARDWARE) != PERMISSION_GRANTED) {
            sanitized.setLowPowerMode(false);
        }
        if (permissionLevel < PERMISSION_FINE) {
            switch (sanitized.getQuality()) {
                case LocationRequest.ACCURACY_FINE:
                    sanitized.setQuality(LocationRequest.ACCURACY_BLOCK);
                    break;
                case LocationRequest.POWER_HIGH:
                    sanitized.setQuality(LocationRequest.POWER_LOW);
                    break;
            }

            if (sanitized.getInterval() < FASTEST_COARSE_INTERVAL_MS) {
                sanitized.setInterval(FASTEST_COARSE_INTERVAL_MS);
            }
            if (sanitized.getFastestInterval() < FASTEST_COARSE_INTERVAL_MS) {
                sanitized.setFastestInterval(FASTEST_COARSE_INTERVAL_MS);
            }
        }
        if (sanitized.getFastestInterval() > sanitized.getInterval()) {
            sanitized.setFastestInterval(request.getInterval());
        }
        if (sanitized.getWorkSource() != null) {
            if (sanitized.getWorkSource().isEmpty()) {
                sanitized.setWorkSource(null);
            } else if (sanitized.getWorkSource().getPackageName(0) == null) {
                Log.w(TAG, "received (and ignoring) illegal worksource with no package name");
                sanitized.setWorkSource(null);
            } else {
                List<WorkChain> workChains = sanitized.getWorkSource().getWorkChains();
                if (workChains != null && !workChains.isEmpty() && workChains.get(
                        0).getAttributionTag() == null) {
                    Log.w(TAG,
                            "received (and ignoring) illegal worksource with no attribution tag");
                    sanitized.setWorkSource(null);
                }
            }
        }

        return sanitized;
    }

    @Override
    public void unregisterLocationListener(ILocationListener listener) {
        for (LocationProviderManager manager : mProviderManagers) {
            manager.unregisterLocationRequest(listener);
        }
    }

    @Override
    public void unregisterLocationPendingIntent(PendingIntent pendingIntent) {
        for (LocationProviderManager manager : mProviderManagers) {
            manager.unregisterLocationRequest(pendingIntent);
        }
    }

    @Override
    public Location getLastLocation(LocationRequest request, String packageName,
            String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkArgument(identity.getPid() != Process.myPid() || attributionTag != null);

        request = validateAndSanitizeLocationRequest(request, permissionLevel);

        LocationProviderManager manager = getLocationProviderManager(request.getProvider());
        if (manager == null) {
            return null;
        }

        Location location = manager.getLastLocation(identity, permissionLevel,
                request.isLocationSettingsIgnored());

        // lastly - note app ops
        if (!mInjector.getAppOpsHelper().noteOpNoThrow(LocationPermissions.asAppOp(permissionLevel),
                identity)) {
            return null;
        }

        return location;
    }

    @Override
    public void getCurrentLocation(LocationRequest request,
            ICancellationSignal cancellationTransport, ILocationCallback consumer,
            String packageName, String attributionTag, String listenerId) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkState(identity.getPid() != Process.myPid() || attributionTag != null);

        request = validateAndSanitizeLocationRequest(request, permissionLevel);

        LocationProviderManager manager = getLocationProviderManager(request.getProvider());
        Preconditions.checkArgument(manager != null,
                "provider \"" + request.getProvider() + "\" does not exist");

        manager.getCurrentLocation(request, identity, permissionLevel, cancellationTransport,
                consumer);
    }

    @Override
    public LocationTime getGnssTimeMillis() {
        synchronized (mLock) {
            LocationProviderManager gpsManager = getLocationProviderManager(GPS_PROVIDER);
            if (gpsManager == null) {
                return null;
            }

            // use fine permission level to avoid creating unnecessary coarse locations
            Location location = gpsManager.getLastLocationUnsafe(UserHandle.USER_ALL,
                    PERMISSION_FINE, false);
            if (location == null) {
                return null;
            }

            long currentNanos = SystemClock.elapsedRealtimeNanos();
            long deltaMs = NANOSECONDS.toMillis(location.getElapsedRealtimeAgeNanos(currentNanos));
            return new LocationTime(location.getTime() + deltaMs, currentNanos);
        }
    }

    @Override
    public void injectLocation(Location location) {
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(ACCESS_FINE_LOCATION, null);

        Preconditions.checkArgument(location.isComplete());

        int userId = UserHandle.getCallingUserId();
        LocationProviderManager manager = getLocationProviderManager(location.getProvider());
        if (manager != null && manager.isEnabled(userId)) {
            manager.injectLastLocation(Objects.requireNonNull(location), userId);
        }
    }

    @Override
    public void requestGeofence(Geofence geofence, PendingIntent intent, String packageName,
            String attributionTag) {
        mGeofenceManager.addGeofence(geofence, intent, packageName, attributionTag);
    }

    @Override
    public void removeGeofence(PendingIntent pendingIntent) {
        mGeofenceManager.removeGeofence(pendingIntent);
    }

    @Override
    public void registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.registerGnssStatusCallback(listener, packageName, attributionTag);
        }
    }

    @Override
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.unregisterGnssStatusCallback(listener);
        }
    }

    @Override
    public void addGnssMeasurementsListener(@Nullable GnssRequest request,
            IGnssMeasurementsListener listener, String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssMeasurementsListener(request, listener, packageName,
                    attributionTag);
        }
    }

    @Override
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssMeasurementsListener(
                    listener);
        }
    }

    @Override
    public void injectGnssMeasurementCorrections(GnssMeasurementCorrections corrections) {
        if (mGnssManagerService != null) {
            mGnssManagerService.injectGnssMeasurementCorrections(corrections);
        }
    }

    @Override
    public long getGnssCapabilities() {
        return mGnssManagerService == null ? GnssCapabilities.INVALID_CAPABILITIES
                : mGnssManagerService.getGnssCapabilities();
    }

    @Override
    public void addGnssAntennaInfoListener(IGnssAntennaInfoListener listener,
            String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssAntennaInfoListener(listener, packageName, attributionTag);
        }
    }

    @Override
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssAntennaInfoListener(listener);
        }
    }

    @Override
    public void addGnssNavigationMessageListener(IGnssNavigationMessageListener listener,
            String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssNavigationMessageListener(listener, packageName,
                    attributionTag);
        }
    }

    @Override
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssNavigationMessageListener(
                    listener);
        }
    }

    @Override
    public void sendExtraCommand(String provider, String command, Bundle extras) {
        LocationPermissions.enforceCallingOrSelfLocationPermission(mContext, PERMISSION_COARSE);
        mContext.enforceCallingOrSelfPermission(
                permission.ACCESS_LOCATION_EXTRA_COMMANDS, null);

        LocationProviderManager manager = getLocationProviderManager(
                Objects.requireNonNull(provider));
        if (manager != null) {
            manager.sendExtraCommand(Binder.getCallingUid(), Binder.getCallingPid(),
                    Objects.requireNonNull(command), extras);
        }

        mInjector.getLocationUsageLogger().logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                provider);
        mInjector.getLocationUsageLogger().logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                provider);
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
    public boolean isProviderPackage(String provider, String packageName) {
        mContext.enforceCallingOrSelfPermission(permission.READ_DEVICE_CONFIG, null);

        for (LocationProviderManager manager : mProviderManagers) {
            if (provider != null && !provider.equals(manager.getName())) {
                continue;
            }
            CallerIdentity identity = manager.getIdentity();
            if (identity == null) {
                continue;
            }
            if (identity.getPackageName().equals(packageName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> getProviderPackages(String provider) {
        mContext.enforceCallingOrSelfPermission(permission.READ_DEVICE_CONFIG, null);

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            return Collections.emptyList();
        }

        CallerIdentity identity = manager.getIdentity();
        if (identity == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(identity.getPackageName());
    }

    @Override
    public void setExtraLocationControllerPackage(String packageName) {
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE,
                permission.LOCATION_HARDWARE + " permission required");
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
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE,
                permission.LOCATION_HARDWARE + " permission required");
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
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "setLocationEnabledForUser", null);

        mContext.enforceCallingOrSelfPermission(permission.WRITE_SECURE_SETTINGS, null);

        LocationManager.invalidateLocalLocationEnabledCaches();
        mInjector.getSettingsHelper().setLocationEnabled(enabled, userId);
    }

    @Override
    public boolean isLocationEnabledForUser(int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "isLocationEnabledForUser", null);
        return mInjector.getSettingsHelper().isLocationEnabled(userId);
    }

    @Override
    public boolean isProviderEnabledForUser(String provider, int userId) {
        // fused provider is accessed indirectly via criteria rather than the provider-based APIs,
        // so we discourage its use
        if (FUSED_PROVIDER.equals(provider)) return false;

        return mLocalService.isProviderEnabledForUser(provider, userId);
    }

    @Override
    public boolean geocoderIsPresent() {
        return mGeocodeProvider != null;
    }

    @Override
    public void getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        if (mGeocodeProvider != null) {
            mGeocodeProvider.getFromLocation(latitude, longitude, maxResults,
                    params, listener);
        } else {
            try {
                listener.onResults(null, Collections.emptyList());
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    @Override
    public void getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        if (mGeocodeProvider != null) {
            mGeocodeProvider.getFromLocationName(locationName, lowerLeftLatitude,
                    lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                    maxResults, params, listener);
        } else {
            try {
                listener.onResults(null, Collections.emptyList());
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    @Override
    public void addTestProvider(String provider, ProviderProperties properties,
            String packageName, String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        getOrAddLocationProviderManager(provider).setMockProvider(
                new MockProvider(properties, identity));
    }

    @Override
    public void removeTestProvider(String provider, String packageName, String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return;
            }

            manager.setMockProvider(null);
            if (!manager.hasProvider()) {
                removeLocationProviderManager(manager);
            }
        }
    }

    @Override
    public void setTestProviderLocation(String provider, Location location, String packageName,
            String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName,
                attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        Preconditions.checkArgument(location.isComplete(),
                "incomplete location object, missing timestamp or accuracy?");

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        manager.setMockProviderLocation(location);
    }

    @Override
    public void setTestProviderEnabled(String provider, boolean enabled, String packageName,
            String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName,
                attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
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
    public List<LocationRequest> getTestProviderCurrentRequests(String provider) {
        mContext.enforceCallingOrSelfPermission(permission.READ_DEVICE_CONFIG, null);

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        return manager.getMockProviderRequests();
    }

    @Override
    public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
            ParcelFileDescriptor err, String[] args) {
        return new LocationShellCommand(this).exec(
                this, in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(),
                args);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        if (mGnssManagerService != null && args.length > 0 && args[0].equals("--gnssmetrics")) {
            mGnssManagerService.dump(fd, ipw, args);
            return;
        }

        ipw.print("Location Manager State:");
        ipw.increaseIndent();
        ipw.println("Elapsed Realtime: " + TimeUtils.formatDuration(SystemClock.elapsedRealtime()));

        ipw.println("User Info:");
        ipw.increaseIndent();
        mInjector.getUserInfoHelper().dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Location Settings:");
        ipw.increaseIndent();
        mInjector.getSettingsHelper().dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Historical Records by Provider:");
        ipw.increaseIndent();
        TreeMap<PackageProviderKey, PackageStatistics> sorted = new TreeMap<>(
                mInjector.getLocationRequestStatistics().statistics);
        for (Map.Entry<PackageProviderKey, PackageStatistics> entry
                : sorted.entrySet()) {
            ipw.println(entry.getKey() + ": " + entry.getValue());
        }
        ipw.decreaseIndent();

        mInjector.getLocationRequestStatistics().history.dump(ipw);

        synchronized (mLock) {
            if (mExtraLocationControllerPackage != null) {
                ipw.println(
                        "Location Controller Extra Package: " + mExtraLocationControllerPackage
                                + (mExtraLocationControllerPackageEnabled ? " [enabled]"
                                : " [disabled]"));
            }
        }

        ipw.println("Location Providers:");
        ipw.increaseIndent();
        for (LocationProviderManager manager : mProviderManagers) {
            manager.dump(fd, ipw, args);
        }
        ipw.decreaseIndent();

        if (mGnssManagerService != null) {
            ipw.println("GNSS Manager:");
            ipw.increaseIndent();
            mGnssManagerService.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

        ipw.println("Geofence Manager:");
        ipw.increaseIndent();
        mGeofenceManager.dump(fd, ipw, args);
        ipw.decreaseIndent();
    }

    private class LocalService extends LocationManagerInternal {

        LocalService() {}

        @Override
        public boolean isProviderEnabledForUser(@NonNull String provider, int userId) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, false, "isProviderEnabledForUser", null);

            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return false;
            }

            return manager.isEnabled(userId);
        }

        @Override
        public void addProviderEnabledListener(String provider, ProviderEnabledListener listener) {
            LocationProviderManager manager = Objects.requireNonNull(
                    getLocationProviderManager(provider));
            manager.addEnabledListener(listener);
        }

        @Override
        public void removeProviderEnabledListener(String provider,
                ProviderEnabledListener listener) {
            LocationProviderManager manager = Objects.requireNonNull(
                    getLocationProviderManager(provider));
            manager.removeEnabledListener(listener);
        }

        @Override
        public boolean isProvider(String provider, CallerIdentity identity) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return false;
            } else {
                return identity.equals(manager.getIdentity());
            }
        }

        @Override
        public void sendNiResponse(int notifId, int userResponse) {
            if (mGnssManagerService != null) {
                mGnssManagerService.sendNiResponse(notifId, userResponse);
            }
        }

        @Override
        public void reportGnssBatchLocations(List<Location> locations) {
            if (mGnssManagerService != null) {
                mGnssManagerService.onReportLocation(locations);
            }
        }
    }

    private static class SystemInjector implements Injector {

        private final UserInfoHelper mUserInfoHelper;
        private final SystemAppOpsHelper mAppOpsHelper;
        private final SystemLocationPermissionsHelper mLocationPermissionsHelper;
        private final SystemSettingsHelper mSettingsHelper;
        private final SystemAppForegroundHelper mAppForegroundHelper;
        private final SystemLocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
        private final SystemScreenInteractiveHelper mScreenInteractiveHelper;
        private final LocationAttributionHelper mLocationAttributionHelper;
        private final LocationUsageLogger mLocationUsageLogger;
        private final LocationRequestStatistics mLocationRequestStatistics;

        SystemInjector(Context context, UserInfoHelper userInfoHelper) {
            mUserInfoHelper = userInfoHelper;
            mAppOpsHelper = new SystemAppOpsHelper(context);
            mLocationPermissionsHelper = new SystemLocationPermissionsHelper(context,
                    mAppOpsHelper);
            mSettingsHelper = new SystemSettingsHelper(context);
            mAppForegroundHelper = new SystemAppForegroundHelper(context);
            mLocationPowerSaveModeHelper = new SystemLocationPowerSaveModeHelper(context);
            mScreenInteractiveHelper = new SystemScreenInteractiveHelper(context);
            mLocationAttributionHelper = new LocationAttributionHelper(mAppOpsHelper);
            mLocationUsageLogger = new LocationUsageLogger();
            mLocationRequestStatistics = new LocationRequestStatistics();
        }

        void onSystemReady() {
            mAppOpsHelper.onSystemReady();
            mLocationPermissionsHelper.onSystemReady();
            mSettingsHelper.onSystemReady();
            mAppForegroundHelper.onSystemReady();
            mLocationPowerSaveModeHelper.onSystemReady();
            mScreenInteractiveHelper.onSystemReady();
        }

        @Override
        public UserInfoHelper getUserInfoHelper() {
            return mUserInfoHelper;
        }

        @Override
        public AppOpsHelper getAppOpsHelper() {
            return mAppOpsHelper;
        }

        @Override
        public LocationPermissionsHelper getLocationPermissionsHelper() {
            return mLocationPermissionsHelper;
        }

        @Override
        public SettingsHelper getSettingsHelper() {
            return mSettingsHelper;
        }

        @Override
        public AppForegroundHelper getAppForegroundHelper() {
            return mAppForegroundHelper;
        }

        @Override
        public LocationUsageLogger getLocationUsageLogger() {
            return mLocationUsageLogger;
        }

        @Override
        public LocationPowerSaveModeHelper getLocationPowerSaveModeHelper() {
            return mLocationPowerSaveModeHelper;
        }

        @Override
        public ScreenInteractiveHelper getScreenInteractiveHelper() {
            return mScreenInteractiveHelper;
        }

        @Override
        public LocationAttributionHelper getLocationAttributionHelper() {
            return mLocationAttributionHelper;
        }

        @Override
        public LocationRequestStatistics getLocationRequestStatistics() {
            return mLocationRequestStatistics;
        }
    }
}
