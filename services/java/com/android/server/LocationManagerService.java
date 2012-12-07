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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.IGpsStatusListener;
import android.location.IGpsStatusProvider;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.location.GeocoderProxy;
import com.android.server.location.GeofenceManager;
import com.android.server.location.GpsLocationProvider;
import com.android.server.location.LocationBlacklist;
import com.android.server.location.LocationFudger;
import com.android.server.location.LocationProviderInterface;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.MockProvider;
import com.android.server.location.PassiveProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 */
public class LocationManagerService extends ILocationManager.Stub implements Runnable {
    private static final String TAG = "LocationManagerService";
    public static final boolean D = false;

    private static final String WAKELOCK_KEY = TAG;
    private static final String THREAD_NAME = TAG;

    // Location resolution level: no location data whatsoever
    private static final int RESOLUTION_LEVEL_NONE = 0;
    // Location resolution level: coarse location data only
    private static final int RESOLUTION_LEVEL_COARSE = 1;
    // Location resolution level: fine location data
    private static final int RESOLUTION_LEVEL_FINE = 2;

    private static final String ACCESS_MOCK_LOCATION =
            android.Manifest.permission.ACCESS_MOCK_LOCATION;
    private static final String ACCESS_LOCATION_EXTRA_COMMANDS =
            android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS;
    private static final String INSTALL_LOCATION_PROVIDER =
            android.Manifest.permission.INSTALL_LOCATION_PROVIDER;

    private static final String NETWORK_LOCATION_SERVICE_ACTION =
            "com.android.location.service.v2.NetworkLocationProvider";
    private static final String FUSED_LOCATION_SERVICE_ACTION =
            "com.android.location.service.FusedLocationProvider";

    private static final int MSG_LOCATION_CHANGED = 1;

    // Location Providers may sometimes deliver location updates
    // slightly faster that requested - provide grace period so
    // we don't unnecessarily filter events that are otherwise on
    // time
    private static final int MAX_PROVIDER_SCHEDULING_JITTER_MS = 100;

    private static final LocationRequest DEFAULT_LOCATION_REQUEST = new LocationRequest();

    private final Context mContext;

    // used internally for synchronization
    private final Object mLock = new Object();

    // --- fields below are final after init() ---
    private LocationFudger mLocationFudger;
    private GeofenceManager mGeofenceManager;
    private PowerManager.WakeLock mWakeLock;
    private PackageManager mPackageManager;
    private GeocoderProxy mGeocodeProvider;
    private IGpsStatusProvider mGpsStatusProvider;
    private INetInitiatedListener mNetInitiatedListener;
    private LocationWorkerHandler mLocationHandler;
    private PassiveProvider mPassiveProvider;  // track passive provider for special cases
    private LocationBlacklist mBlacklist;

    // --- fields below are protected by mWakeLock ---
    private int mPendingBroadcasts;

    // --- fields below are protected by mLock ---
    // Set of providers that are explicitly enabled
    private final Set<String> mEnabledProviders = new HashSet<String>();

    // Set of providers that are explicitly disabled
    private final Set<String> mDisabledProviders = new HashSet<String>();

    // Mock (test) providers
    private final HashMap<String, MockProvider> mMockProviders =
            new HashMap<String, MockProvider>();

    // all receivers
    private final HashMap<Object, Receiver> mReceivers = new HashMap<Object, Receiver>();

    // currently installed providers (with mocks replacing real providers)
    private final ArrayList<LocationProviderInterface> mProviders =
            new ArrayList<LocationProviderInterface>();

    // real providers, saved here when mocked out
    private final HashMap<String, LocationProviderInterface> mRealProviders =
            new HashMap<String, LocationProviderInterface>();

    // mapping from provider name to provider
    private final HashMap<String, LocationProviderInterface> mProvidersByName =
            new HashMap<String, LocationProviderInterface>();

    // mapping from provider name to all its UpdateRecords
    private final HashMap<String, ArrayList<UpdateRecord>> mRecordsByProvider =
            new HashMap<String, ArrayList<UpdateRecord>>();

    // mapping from provider name to last known location
    private final HashMap<String, Location> mLastLocation = new HashMap<String, Location>();

    // all providers that operate over proxy, for authorizing incoming location
    private final ArrayList<LocationProviderProxy> mProxyProviders =
            new ArrayList<LocationProviderProxy>();

    // current active user on the device - other users are denied location data
    private int mCurrentUserId = UserHandle.USER_OWNER;

    public LocationManagerService(Context context) {
        super();
        mContext = context;

        if (D) Log.d(TAG, "Constructed");

        // most startup is deferred until systemReady()
    }

    public void systemReady() {
        Thread thread = new Thread(null, this, THREAD_NAME);
        thread.start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Looper.prepare();
        mLocationHandler = new LocationWorkerHandler();
        init();
        Looper.loop();
    }

    private void init() {
        if (D) Log.d(TAG, "init()");

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mPackageManager = mContext.getPackageManager();

        mBlacklist = new LocationBlacklist(mContext, mLocationHandler);
        mBlacklist.init();
        mLocationFudger = new LocationFudger(mContext, mLocationHandler);

        synchronized (mLock) {
            loadProvidersLocked();
        }

        mGeofenceManager = new GeofenceManager(mContext, mBlacklist);

        // listen for settings changes
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_PROVIDERS_ALLOWED), true,
                new ContentObserver(mLocationHandler) {
           @Override
            public void onChange(boolean selfChange) {
               synchronized (mLock) {
                   updateProvidersLocked();
               }
            }
        }, UserHandle.USER_ALL);
        mPackageMonitor.register(mContext, Looper.myLooper(), true);

        // listen for user change
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);

        updateProvidersLocked();
    }

    private void ensureFallbackFusedProviderPresentLocked(ArrayList<String> pkgs) {
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
                    if (D) Log.d(TAG, "Fallback candidate not signed the same as system: "
                            + packageName);
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

    private void loadProvidersLocked() {
        // create a passive location provider, which is always enabled
        PassiveProvider passiveProvider = new PassiveProvider(this);
        addProviderLocked(passiveProvider);
        mEnabledProviders.add(passiveProvider.getName());
        mPassiveProvider = passiveProvider;

        if (GpsLocationProvider.isSupported()) {
            // Create a gps location provider
            GpsLocationProvider gpsProvider = new GpsLocationProvider(mContext, this);
            mGpsStatusProvider = gpsProvider.getGpsStatusProvider();
            mNetInitiatedListener = gpsProvider.getNetInitiatedListener();
            addProviderLocked(gpsProvider);
            mRealProviders.put(LocationManager.GPS_PROVIDER, gpsProvider);
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
        ArrayList<String> providerPackageNames = new ArrayList<String>();
        String[] pkgs = resources.getStringArray(
                com.android.internal.R.array.config_locationProviderPackageNames);
        if (D) Log.d(TAG, "certificates for location providers pulled from: " +
                Arrays.toString(pkgs));
        if (pkgs != null) providerPackageNames.addAll(Arrays.asList(pkgs));

        ensureFallbackFusedProviderPresentLocked(providerPackageNames);

        // bind to network provider
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndBind(
                mContext,
                LocationManager.NETWORK_PROVIDER,
                NETWORK_LOCATION_SERVICE_ACTION,
                providerPackageNames, mLocationHandler, mCurrentUserId);
        if (networkProvider != null) {
            mRealProviders.put(LocationManager.NETWORK_PROVIDER, networkProvider);
            mProxyProviders.add(networkProvider);
            addProviderLocked(networkProvider);
        } else {
            Slog.w(TAG,  "no network location provider found");
        }

        // bind to fused provider
        LocationProviderProxy fusedLocationProvider = LocationProviderProxy.createAndBind(
                mContext,
                LocationManager.FUSED_PROVIDER,
                FUSED_LOCATION_SERVICE_ACTION,
                providerPackageNames, mLocationHandler, mCurrentUserId);
        if (fusedLocationProvider != null) {
            addProviderLocked(fusedLocationProvider);
            mProxyProviders.add(fusedLocationProvider);
            mEnabledProviders.add(fusedLocationProvider.getName());
            mRealProviders.put(LocationManager.FUSED_PROVIDER, fusedLocationProvider);
        } else {
            Slog.e(TAG, "no fused location provider found",
                    new IllegalStateException("Location service needs a fused location provider"));
        }

        // bind to geocoder provider
        mGeocodeProvider = GeocoderProxy.createAndBind(mContext, providerPackageNames,
                mCurrentUserId);
        if (mGeocodeProvider == null) {
            Slog.e(TAG,  "no geocoder provider found");
        }
    }

    /**
     * Called when the device's active user changes.
     * @param userId the new active user's UserId
     */
    private void switchUser(int userId) {
        mBlacklist.switchUser(userId);
        synchronized (mLock) {
            mLastLocation.clear();
            for (LocationProviderInterface p : mProviders) {
                updateProviderListenersLocked(p.getName(), false, mCurrentUserId);
                p.switchUser(userId);
            }
            mCurrentUserId = userId;
            updateProvidersLocked();
        }
    }

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver implements IBinder.DeathRecipient, PendingIntent.OnFinished {
        final int mUid;  // uid of receiver
        final int mPid;  // pid of receiver
        final String mPackageName;  // package name of receiver
        final int mAllowedResolutionLevel;  // resolution level allowed to receiver

        final ILocationListener mListener;
        final PendingIntent mPendingIntent;
        final Object mKey;

        final HashMap<String,UpdateRecord> mUpdateRecords = new HashMap<String,UpdateRecord>();

        int mPendingBroadcasts;

        Receiver(ILocationListener listener, PendingIntent intent, int pid, int uid,
                String packageName) {
            mListener = listener;
            mPendingIntent = intent;
            if (listener != null) {
                mKey = listener.asBinder();
            } else {
                mKey = intent;
            }
            mAllowedResolutionLevel = getAllowedResolutionLevel(pid, uid);
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj instanceof Receiver) {
                return mKey.equals(((Receiver)otherObj).mKey);
            }
            return false;
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
            s.append("]");
            return s.toString();
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
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mListener.onStatusChanged(provider, status, extras);
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent statusChanged = new Intent();
                statusChanged.putExtras(new Bundle(extras));
                statusChanged.putExtra(LocationManager.KEY_STATUS_CHANGED, status);
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mPendingIntent.send(mContext, 0, statusChanged, this, mLocationHandler,
                                getResolutionPermission(mAllowedResolutionLevel));
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        public boolean callLocationChangedLocked(Location location) {
            if (mListener != null) {
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mListener.onLocationChanged(new Location(location));
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent locationChanged = new Intent();
                locationChanged.putExtra(LocationManager.KEY_LOCATION_CHANGED, new Location(location));
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mPendingIntent.send(mContext, 0, locationChanged, this, mLocationHandler,
                                getResolutionPermission(mAllowedResolutionLevel));
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        public boolean callProviderEnabledLocked(String provider, boolean enabled) {
            if (mListener != null) {
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        if (enabled) {
                            mListener.onProviderEnabled(provider);
                        } else {
                            mListener.onProviderDisabled(provider);
                        }
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent providerIntent = new Intent();
                providerIntent.putExtra(LocationManager.KEY_PROVIDER_ENABLED, enabled);
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mPendingIntent.send(mContext, 0, providerIntent, this, mLocationHandler,
                                getResolutionPermission(mAllowedResolutionLevel));
                        // call this after broadcasting so we do not increment
                        // if we throw an exeption.
                        incrementPendingBroadcastsLocked();
                    }
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void binderDied() {
            if (D) Log.d(TAG, "Location listener died");

            synchronized (mLock) {
                removeUpdatesLocked(this);
            }
            synchronized (this) {
                if (mPendingBroadcasts > 0) {
                    LocationManagerService.this.decrementPendingBroadcasts();
                    mPendingBroadcasts = 0;
                }
            }
        }

        @Override
        public void onSendFinished(PendingIntent pendingIntent, Intent intent,
                int resultCode, String resultData, Bundle resultExtras) {
            synchronized (this) {
                decrementPendingBroadcastsLocked();
            }
        }

        // this must be called while synchronized by caller in a synchronized block
        // containing the sending of the broadcaset
        private void incrementPendingBroadcastsLocked() {
            if (mPendingBroadcasts++ == 0) {
                LocationManagerService.this.incrementPendingBroadcasts();
            }
        }

        private void decrementPendingBroadcastsLocked() {
            if (--mPendingBroadcasts == 0) {
                LocationManagerService.this.decrementPendingBroadcasts();
            }
        }
    }

    @Override
    public void locationCallbackFinished(ILocationListener listener) {
        //Do not use getReceiver here as that will add the ILocationListener to
        //the receiver list if it is not found.  If it is not found then the
        //LocationListener was removed when it had a pending broadcast and should
        //not be added back.
        IBinder binder = listener.asBinder();
        Receiver receiver = mReceivers.get(binder);
        if (receiver != null) {
            synchronized (receiver) {
                // so wakelock calls will succeed
                long identity = Binder.clearCallingIdentity();
                receiver.decrementPendingBroadcastsLocked();
                Binder.restoreCallingIdentity(identity);
           }
        }
    }

    private void addProviderLocked(LocationProviderInterface provider) {
        mProviders.add(provider);
        mProvidersByName.put(provider.getName(), provider);
    }

    private void removeProviderLocked(LocationProviderInterface provider) {
        provider.disable();
        mProviders.remove(provider);
        mProvidersByName.remove(provider.getName());
    }


    private boolean isAllowedBySettingsLocked(String provider, int userId) {
        if (userId != mCurrentUserId) {
            return false;
        }
        if (mEnabledProviders.contains(provider)) {
            return true;
        }
        if (mDisabledProviders.contains(provider)) {
            return false;
        }
        // Use system settings
        ContentResolver resolver = mContext.getContentResolver();

        return Settings.Secure.isLocationProviderEnabledForUser(resolver, provider, mCurrentUserId);
    }

    /**
     * Returns the permission string associated with the specified resolution level.
     *
     * @param resolutionLevel the resolution level
     * @return the permission string
     */
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

    /**
     * Returns the resolution level allowed to the given PID/UID pair.
     *
     * @param pid the PID
     * @param uid the UID
     * @return resolution level allowed to the pid/uid pair
     */
    private int getAllowedResolutionLevel(int pid, int uid) {
        if (mContext.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_FINE;
        } else if (mContext.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_COARSE;
        } else {
            return RESOLUTION_LEVEL_NONE;
        }
    }

    /**
     * Returns the resolution level allowed to the caller
     *
     * @return resolution level allowed to caller
     */
    private int getCallerAllowedResolutionLevel() {
        return getAllowedResolutionLevel(Binder.getCallingPid(), Binder.getCallingUid());
    }

    /**
     * Throw SecurityException if specified resolution level is insufficient to use geofences.
     *
     * @param allowedResolutionLevel resolution level allowed to caller
     */
    private void checkResolutionLevelIsSufficientForGeofenceUse(int allowedResolutionLevel) {
        if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
            throw new SecurityException("Geofence usage requires ACCESS_FINE_LOCATION permission");
        }
    }

    /**
     * Return the minimum resolution level required to use the specified location provider.
     *
     * @param provider the name of the location provider
     * @return minimum resolution level required for provider
     */
    private int getMinimumResolutionLevelForProviderUse(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider) ||
                LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            // gps and passive providers require FINE permission
            return RESOLUTION_LEVEL_FINE;
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider) ||
                LocationManager.FUSED_PROVIDER.equals(provider)) {
            // network and fused providers are ok with COARSE or FINE
            return RESOLUTION_LEVEL_COARSE;
        } else {
            // mock providers
            LocationProviderInterface lp = mMockProviders.get(provider);
            if (lp != null) {
                ProviderProperties properties = lp.getProperties();
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

    /**
     * Throw SecurityException if specified resolution level is insufficient to use the named
     * location provider.
     *
     * @param allowedResolutionLevel resolution level allowed to caller
     * @param providerName the name of the location provider
     */
    private void checkResolutionLevelIsSufficientForProviderUse(int allowedResolutionLevel,
            String providerName) {
        int requiredResolutionLevel = getMinimumResolutionLevelForProviderUse(providerName);
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

    /**
     * Returns all providers by name, including passive, but excluding
     * fused, also including ones that are not permitted to
     * be accessed by the calling activity or are currently disabled.
     */
    @Override
    public List<String> getAllProviders() {
        ArrayList<String> out;
        synchronized (mLock) {
            out = new ArrayList<String>(mProviders.size());
            for (LocationProviderInterface provider : mProviders) {
                String name = provider.getName();
                if (LocationManager.FUSED_PROVIDER.equals(name)) {
                    continue;
                }
                out.add(name);
            }
        }

        if (D) Log.d(TAG, "getAllProviders()=" + out);
        return out;
    }

    /**
     * Return all providers by name, that match criteria and are optionally
     * enabled.
     * Can return passive provider, but never returns fused provider.
     */
    @Override
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        ArrayList<String> out;
        int callingUserId = UserHandle.getCallingUserId();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                out = new ArrayList<String>(mProviders.size());
                for (LocationProviderInterface provider : mProviders) {
                    String name = provider.getName();
                    if (LocationManager.FUSED_PROVIDER.equals(name)) {
                        continue;
                    }
                    if (allowedResolutionLevel >= getMinimumResolutionLevelForProviderUse(name)) {
                        if (enabledOnly && !isAllowedBySettingsLocked(name, callingUserId)) {
                            continue;
                        }
                        if (criteria != null && !LocationProvider.propertiesMeetCriteria(
                                name, provider.getProperties(), criteria)) {
                            continue;
                        }
                        out.add(name);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (D) Log.d(TAG, "getProviders()=" + out);
        return out;
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
        String result = null;

        List<String> providers = getProviders(criteria, enabledOnly);
        if (!providers.isEmpty()) {
            result = pickBest(providers);
            if (D) Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + result);
            return result;
        }
        providers = getProviders(null, enabledOnly);
        if (!providers.isEmpty()) {
            result = pickBest(providers);
            if (D) Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + result);
            return result;
        }

        if (D) Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + result);
        return null;
    }

    private String pickBest(List<String> providers) {
        if (providers.contains(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        } else if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        } else {
            return providers.get(0);
        }
    }

    @Override
    public boolean providerMeetsCriteria(String provider, Criteria criteria) {
        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        boolean result = LocationProvider.propertiesMeetCriteria(
                p.getName(), p.getProperties(), criteria);
        if (D) Log.d(TAG, "providerMeetsCriteria(" + provider + ", " + criteria + ")=" + result);
        return result;
    }

    private void updateProvidersLocked() {
        boolean changesMade = false;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderInterface p = mProviders.get(i);
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedBySettingsLocked(name, mCurrentUserId);
            if (isEnabled && !shouldBeEnabled) {
                updateProviderListenersLocked(name, false, mCurrentUserId);
                changesMade = true;
            } else if (!isEnabled && shouldBeEnabled) {
                updateProviderListenersLocked(name, true, mCurrentUserId);
                changesMade = true;
            }
        }
        if (changesMade) {
            mContext.sendBroadcastAsUser(new Intent(LocationManager.PROVIDERS_CHANGED_ACTION),
                    UserHandle.ALL);
        }
    }

    private void updateProviderListenersLocked(String provider, boolean enabled, int userId) {
        int listeners = 0;

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) return;

        ArrayList<Receiver> deadReceivers = null;

        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records != null) {
            final int N = records.size();
            for (int i = 0; i < N; i++) {
                UpdateRecord record = records.get(i);
                if (UserHandle.getUserId(record.mReceiver.mUid) == userId) {
                    // Sends a notification message to the receiver
                    if (!record.mReceiver.callProviderEnabledLocked(provider, enabled)) {
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<Receiver>();
                        }
                        deadReceivers.add(record.mReceiver);
                    }
                    listeners++;
                }
            }
        }

        if (deadReceivers != null) {
            for (int i = deadReceivers.size() - 1; i >= 0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }

        if (enabled) {
            p.enable();
            if (listeners > 0) {
                applyRequirementsLocked(provider);
            }
        } else {
            p.disable();
        }
    }

    private void applyRequirementsLocked(String provider) {
        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) return;

        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        WorkSource worksource = new WorkSource();
        ProviderRequest providerRequest = new ProviderRequest();

        if (records != null) {
            for (UpdateRecord record : records) {
                if (UserHandle.getUserId(record.mReceiver.mUid) == mCurrentUserId) {
                    LocationRequest locationRequest = record.mRequest;
                    providerRequest.locationRequests.add(locationRequest);
                    if (locationRequest.getInterval() < providerRequest.interval) {
                        providerRequest.reportLocation = true;
                        providerRequest.interval = locationRequest.getInterval();
                    }
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
                    if (UserHandle.getUserId(record.mReceiver.mUid) == mCurrentUserId) {
                        LocationRequest locationRequest = record.mRequest;
                        if (locationRequest.getInterval() <= thresholdInterval) {
                            worksource.add(record.mReceiver.mUid);
                        }
                    }
                }
            }
        }

        if (D) Log.d(TAG, "provider request: " + provider + " " + providerRequest);
        p.setRequest(providerRequest, worksource);
    }

    private class UpdateRecord {
        final String mProvider;
        final LocationRequest mRequest;
        final Receiver mReceiver;
        Location mLastFixBroadcast;
        long mLastStatusBroadcast;

        /**
         * Note: must be constructed with lock held.
         */
        UpdateRecord(String provider, LocationRequest request, Receiver receiver) {
            mProvider = provider;
            mRequest = request;
            mReceiver = receiver;

            ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
            if (records == null) {
                records = new ArrayList<UpdateRecord>();
                mRecordsByProvider.put(provider, records);
            }
            if (!records.contains(this)) {
                records.add(this);
            }
        }

        /**
         * Method to be called when a record will no longer be used.  Calling this multiple times
         * must have the same effect as calling it once.
         */
        void disposeLocked(boolean removeReceiver) {
            // remove from mRecordsByProvider
            ArrayList<UpdateRecord> globalRecords = mRecordsByProvider.get(this.mProvider);
            if (globalRecords != null) {
                globalRecords.remove(this);
            }

            if (!removeReceiver) return;  // the caller will handle the rest

            // remove from Receiver#mUpdateRecords
            HashMap<String, UpdateRecord> receiverRecords = mReceiver.mUpdateRecords;
            if (receiverRecords != null) {
                receiverRecords.remove(this.mProvider);

                // and also remove the Receiver if it has no more update records
                if (removeReceiver && receiverRecords.size() == 0) {
                    removeUpdatesLocked(mReceiver);
                }
            }
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append("UpdateRecord[");
            s.append(mProvider);
            s.append(' ').append(mReceiver.mPackageName).append('(');
            s.append(mReceiver.mUid).append(')');
            s.append(' ').append(mRequest);
            s.append(']');
            return s.toString();
        }
    }

    private Receiver getReceiver(ILocationListener listener, int pid, int uid, String packageName) {
        IBinder binder = listener.asBinder();
        Receiver receiver = mReceivers.get(binder);
        if (receiver == null) {
            receiver = new Receiver(listener, null, pid, uid, packageName);
            mReceivers.put(binder, receiver);

            try {
                receiver.getListener().asBinder().linkToDeath(receiver, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
                return null;
            }
        }
        return receiver;
    }

    private Receiver getReceiver(PendingIntent intent, int pid, int uid, String packageName) {
        Receiver receiver = mReceivers.get(intent);
        if (receiver == null) {
            receiver = new Receiver(null, intent, pid, uid, packageName);
            mReceivers.put(intent, receiver);
        }
        return receiver;
    }

    /**
     * Creates a LocationRequest based upon the supplied LocationRequest that to meets resolution
     * and consistency requirements.
     *
     * @param request the LocationRequest from which to create a sanitized version
     * @param shouldBeCoarse whether the sanitized version should be held to coarse resolution
     * constraints
     * @param fastestCoarseIntervalMS minimum interval allowed for coarse resolution
     * @return a version of request that meets the given resolution and consistency requirements
     * @hide
     */
    private LocationRequest createSanitizedRequest(LocationRequest request, int resolutionLevel) {
        LocationRequest sanitizedRequest = new LocationRequest(request);
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
            request.setFastestInterval(request.getInterval());
        }
        return sanitizedRequest;
    }

    private void checkPackageName(String packageName) {
        if (packageName == null) {
            throw new SecurityException("invalid package name: " + packageName);
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

    private void checkPendingIntent(PendingIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + intent);
        }
    }

    private Receiver checkListenerOrIntent(ILocationListener listener, PendingIntent intent,
            int pid, int uid, String packageName) {
        if (intent == null && listener == null) {
            throw new IllegalArgumentException("need eiter listener or intent");
        } else if (intent != null && listener != null) {
            throw new IllegalArgumentException("cannot register both listener and intent");
        } else if (intent != null) {
            checkPendingIntent(intent);
            return getReceiver(intent, pid, uid, packageName);
        } else {
            return getReceiver(listener, pid, uid, packageName);
        }
    }

    @Override
    public void requestLocationUpdates(LocationRequest request, ILocationListener listener,
            PendingIntent intent, String packageName) {
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        checkPackageName(packageName);
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel,
                request.getProvider());
        LocationRequest sanitizedRequest = createSanitizedRequest(request, allowedResolutionLevel);

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        Receiver recevier = checkListenerOrIntent(listener, intent, pid, uid, packageName);

        // providers may use public location API's, need to clear identity
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestLocationUpdatesLocked(sanitizedRequest, recevier, pid, uid, packageName);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void requestLocationUpdatesLocked(LocationRequest request, Receiver receiver,
            int pid, int uid, String packageName) {
        // Figure out the provider. Either its explicitly request (legacy use cases), or
        // use the fused provider
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        String name = request.getProvider();
        if (name == null) {
            throw new IllegalArgumentException("provider name must not be null");
        }
        LocationProviderInterface provider = mProvidersByName.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("provider doesn't exisit: " + provider);
        }

        if (D) Log.d(TAG, "request " + Integer.toHexString(System.identityHashCode(receiver))
                + " " + name + " " + request + " from " + packageName + "(" + uid + ")");

        UpdateRecord record = new UpdateRecord(name, request, receiver);
        UpdateRecord oldRecord = receiver.mUpdateRecords.put(name, record);
        if (oldRecord != null) {
            oldRecord.disposeLocked(false);
        }

        boolean isProviderEnabled = isAllowedBySettingsLocked(name, UserHandle.getUserId(uid));
        if (isProviderEnabled) {
            applyRequirementsLocked(name);
        } else {
            // Notify the listener that updates are currently disabled
            receiver.callProviderEnabledLocked(name, false);
        }
    }

    @Override
    public void removeUpdates(ILocationListener listener, PendingIntent intent,
            String packageName) {
        checkPackageName(packageName);

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        Receiver receiver = checkListenerOrIntent(listener, intent, pid, uid, packageName);

        // providers may use public location API's, need to clear identity
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                removeUpdatesLocked(receiver);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void removeUpdatesLocked(Receiver receiver) {
        if (D) Log.i(TAG, "remove " + Integer.toHexString(System.identityHashCode(receiver)));

        if (mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
            receiver.getListener().asBinder().unlinkToDeath(receiver, 0);
            synchronized (receiver) {
                if (receiver.mPendingBroadcasts > 0) {
                    decrementPendingBroadcasts();
                    receiver.mPendingBroadcasts = 0;
                }
            }
        }

        // Record which providers were associated with this listener
        HashSet<String> providers = new HashSet<String>();
        HashMap<String, UpdateRecord> oldRecords = receiver.mUpdateRecords;
        if (oldRecords != null) {
            // Call dispose() on the obsolete update records.
            for (UpdateRecord record : oldRecords.values()) {
                record.disposeLocked(false);
            }
            // Accumulate providers
            providers.addAll(oldRecords.keySet());
        }

        // update provider
        for (String provider : providers) {
            // If provider is already disabled, don't need to do anything
            if (!isAllowedBySettingsLocked(provider, mCurrentUserId)) {
                continue;
            }

            applyRequirementsLocked(provider);
        }
    }

    @Override
    public Location getLastLocation(LocationRequest request, String packageName) {
        if (D) Log.d(TAG, "getLastLocation: " + request);
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkPackageName(packageName);
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel,
                request.getProvider());
        // no need to sanitize this request, as only the provider name is used

        long identity = Binder.clearCallingIdentity();
        try {
            if (mBlacklist.isBlacklisted(packageName)) {
                if (D) Log.d(TAG, "not returning last loc for blacklisted app: " +
                        packageName);
                return null;
            }

            synchronized (mLock) {
                // Figure out the provider. Either its explicitly request (deprecated API's),
                // or use the fused provider
                String name = request.getProvider();
                if (name == null) name = LocationManager.FUSED_PROVIDER;
                LocationProviderInterface provider = mProvidersByName.get(name);
                if (provider == null) return null;

                if (!isAllowedBySettingsLocked(name, mCurrentUserId)) return null;

                Location location = mLastLocation.get(name);
                if (location == null) {
                    return null;
                }
                if (allowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                    Location noGPSLocation = location.getExtraLocation(Location.EXTRA_NO_GPS_LOCATION);
                    if (noGPSLocation != null) {
                        return new Location(mLocationFudger.getOrCreate(noGPSLocation));
                    }
                } else {
                    return new Location(location);
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void requestGeofence(LocationRequest request, Geofence geofence, PendingIntent intent,
            String packageName) {
        if (request == null) request = DEFAULT_LOCATION_REQUEST;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForGeofenceUse(allowedResolutionLevel);
        checkPendingIntent(intent);
        checkPackageName(packageName);
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel,
                request.getProvider());
        LocationRequest sanitizedRequest = createSanitizedRequest(request, allowedResolutionLevel);

        if (D) Log.d(TAG, "requestGeofence: " + sanitizedRequest + " " + geofence + " " + intent);

        // geo-fence manager uses the public location API, need to clear identity
        int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != UserHandle.USER_OWNER) {
            // temporary measure until geofences work for secondary users
            Log.w(TAG, "proximity alerts are currently available only to the primary user");
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            mGeofenceManager.addFence(sanitizedRequest, geofence, intent, uid, packageName);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void removeGeofence(Geofence geofence, PendingIntent intent, String packageName) {
        checkResolutionLevelIsSufficientForGeofenceUse(getCallerAllowedResolutionLevel());
        checkPendingIntent(intent);
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
    public boolean addGpsStatusListener(IGpsStatusListener listener) {
        if (mGpsStatusProvider == null) {
            return false;
        }
        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(),
                LocationManager.GPS_PROVIDER);

        try {
            mGpsStatusProvider.addGpsStatusListener(listener);
        } catch (RemoteException e) {
            Slog.e(TAG, "mGpsStatusProvider.addGpsStatusListener failed", e);
            return false;
        }
        return true;
    }

    @Override
    public void removeGpsStatusListener(IGpsStatusListener listener) {
        synchronized (mLock) {
            try {
                mGpsStatusProvider.removeGpsStatusListener(listener);
            } catch (Exception e) {
                Slog.e(TAG, "mGpsStatusProvider.removeGpsStatusListener failed", e);
            }
        }
    }

    @Override
    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        if (provider == null) {
            // throw NullPointerException to remain compatible with previous implementation
            throw new NullPointerException();
        }
        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(),
                provider);

        // and check for ACCESS_LOCATION_EXTRA_COMMANDS
        if ((mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }

        synchronized (mLock) {
            LocationProviderInterface p = mProvidersByName.get(provider);
            if (p == null) return false;

            return p.sendExtraCommand(command, extras);
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

    /**
     * @return null if the provider does not exist
     * @throws SecurityException if the provider is not allowed to be
     * accessed by the caller
     */
    @Override
    public ProviderProperties getProviderProperties(String provider) {
        if (mProvidersByName.get(provider) == null) {
          return null;
        }

        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(),
                provider);

        LocationProviderInterface p;
        synchronized (mLock) {
            p = mProvidersByName.get(provider);
        }

        if (p == null) return null;
        return p.getProperties();
    }

    @Override
    public boolean isProviderEnabled(String provider) {
        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(),
                provider);
        if (LocationManager.FUSED_PROVIDER.equals(provider)) return false;

        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                LocationProviderInterface p = mProvidersByName.get(provider);
                if (p == null) return false;

                return isAllowedBySettingsLocked(provider, mCurrentUserId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void checkCallerIsProvider() {
        if (mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Previously we only used the INSTALL_LOCATION_PROVIDER
        // check. But that is system or signature
        // protection level which is not flexible enough for
        // providers installed oustide the system image. So
        // also allow providers with a UID matching the
        // currently bound package name

        int uid = Binder.getCallingUid();

        if (mGeocodeProvider != null) {
            if (doesPackageHaveUid(uid, mGeocodeProvider.getConnectedPackageName())) return;
        }
        for (LocationProviderProxy proxy : mProxyProviders) {
            if (doesPackageHaveUid(uid, proxy.getConnectedPackageName())) return;
        }
        throw new SecurityException("need INSTALL_LOCATION_PROVIDER permission, " +
                "or UID of a currently bound location provider");
    }

    private boolean doesPackageHaveUid(int uid, String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName, 0);
            if (appInfo.uid != uid) {
                return false;
            }
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    @Override
    public void reportLocation(Location location, boolean passive) {
        checkCallerIsProvider();

        if (!location.isComplete()) {
            Log.w(TAG, "Dropping incomplete location: " + location);
            return;
        }

        mLocationHandler.removeMessages(MSG_LOCATION_CHANGED, location);
        Message m = Message.obtain(mLocationHandler, MSG_LOCATION_CHANGED, location);
        m.arg1 = (passive ? 1 : 0);
        mLocationHandler.sendMessageAtFrontOfQueue(m);
    }


    private static boolean shouldBroadcastSafe(
            Location loc, Location lastLoc, UpdateRecord record, long now) {
        // Always broadcast the first update
        if (lastLoc == null) {
            return true;
        }

        // Check whether sufficient time has passed
        long minTime = record.mRequest.getFastestInterval();
        long delta = (loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos()) / 1000000L;
        if (delta < minTime - MAX_PROVIDER_SCHEDULING_JITTER_MS) {
            return false;
        }

        // Check whether sufficient distance has been traveled
        double minDistance = record.mRequest.getSmallestDisplacement();
        if (minDistance > 0.0) {
            if (loc.distanceTo(lastLoc) <= minDistance) {
                return false;
            }
        }

        // Check whether sufficient number of udpates is left
        if (record.mRequest.getNumUpdates() <= 0) {
            return false;
        }

        // Check whether the expiry date has passed
        if (record.mRequest.getExpireAt() < now) {
            return false;
        }

        return true;
    }

    private void handleLocationChangedLocked(Location location, boolean passive) {
        if (D) Log.d(TAG, "incoming location: " + location);

        long now = SystemClock.elapsedRealtime();
        String provider = (passive ? LocationManager.PASSIVE_PROVIDER : location.getProvider());

        // Skip if the provider is unknown.
        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) return;

        // Update last known locations
        Location noGPSLocation = location.getExtraLocation(Location.EXTRA_NO_GPS_LOCATION);
        Location lastNoGPSLocation = null;
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

        // Skip if there are no UpdateRecords for this provider.
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records == null || records.size() == 0) return;

        // Fetch coarse location
        Location coarseLocation = null;
        if (noGPSLocation != null && !noGPSLocation.equals(lastNoGPSLocation)) {
            coarseLocation = mLocationFudger.getOrCreate(noGPSLocation);
        }

        // Fetch latest status update time
        long newStatusUpdateTime = p.getStatusUpdateTime();

       // Get latest status
        Bundle extras = new Bundle();
        int status = p.getStatus(extras);

        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> deadUpdateRecords = null;

        // Broadcast location or status to all listeners
        for (UpdateRecord r : records) {
            Receiver receiver = r.mReceiver;
            boolean receiverDead = false;

            int receiverUserId = UserHandle.getUserId(receiver.mUid);
            if (receiverUserId != mCurrentUserId) {
                if (D) {
                    Log.d(TAG, "skipping loc update for background user " + receiverUserId +
                            " (current user: " + mCurrentUserId + ", app: " +
                            receiver.mPackageName + ")");
                }
                continue;
            }

            if (mBlacklist.isBlacklisted(receiver.mPackageName)) {
                if (D) Log.d(TAG, "skipping loc update for blacklisted app: " +
                        receiver.mPackageName);
                continue;
            }

            Location notifyLocation = null;
            if (receiver.mAllowedResolutionLevel < RESOLUTION_LEVEL_FINE) {
                notifyLocation = coarseLocation;  // use coarse location
            } else {
                notifyLocation = lastLocation;  // use fine location
            }
            if (notifyLocation != null) {
                Location lastLoc = r.mLastFixBroadcast;
                if ((lastLoc == null) || shouldBroadcastSafe(notifyLocation, lastLoc, r, now)) {
                    if (lastLoc == null) {
                        lastLoc = new Location(notifyLocation);
                        r.mLastFixBroadcast = lastLoc;
                    } else {
                        lastLoc.set(notifyLocation);
                    }
                    if (!receiver.callLocationChangedLocked(notifyLocation)) {
                        Slog.w(TAG, "RemoteException calling onLocationChanged on " + receiver);
                        receiverDead = true;
                    }
                    r.mRequest.decrementNumUpdates();
                }
            }

            long prevStatusUpdateTime = r.mLastStatusBroadcast;
            if ((newStatusUpdateTime > prevStatusUpdateTime) &&
                    (prevStatusUpdateTime != 0 || status != LocationProvider.AVAILABLE)) {

                r.mLastStatusBroadcast = newStatusUpdateTime;
                if (!receiver.callStatusChangedLocked(provider, status, extras)) {
                    receiverDead = true;
                    Slog.w(TAG, "RemoteException calling onStatusChanged on " + receiver);
                }
            }

            // track expired records
            if (r.mRequest.getNumUpdates() <= 0 || r.mRequest.getExpireAt() < now) {
                if (deadUpdateRecords == null) {
                    deadUpdateRecords = new ArrayList<UpdateRecord>();
                }
                deadUpdateRecords.add(r);
            }
            // track dead receivers
            if (receiverDead) {
                if (deadReceivers == null) {
                    deadReceivers = new ArrayList<Receiver>();
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

    private class LocationWorkerHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOCATION_CHANGED:
                    handleLocationChanged((Location) msg.obj, msg.arg1 == 1);
                    break;
            }
        }
    }

    private void handleLocationChanged(Location location, boolean passive) {
        String provider = location.getProvider();

        if (!passive) {
            // notify passive provider of the new location
            mPassiveProvider.updateLocation(location);
        }

        synchronized (mLock) {
            if (isAllowedBySettingsLocked(provider, mCurrentUserId)) {
                handleLocationChangedLocked(location, passive);
            }
        }
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageDisappeared(String packageName, int reason) {
            // remove all receivers associated with this package name
            synchronized (mLock) {
                ArrayList<Receiver> deadReceivers = null;

                for (Receiver receiver : mReceivers.values()) {
                    if (receiver.mPackageName.equals(packageName)) {
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<Receiver>();
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
        }
    };

    // Wake locks

    private void incrementPendingBroadcasts() {
        synchronized (mWakeLock) {
            if (mPendingBroadcasts++ == 0) {
                try {
                    mWakeLock.acquire();
                    log("Acquired wakelock");
                } catch (Exception e) {
                    // This is to catch a runtime exception thrown when we try to release an
                    // already released lock.
                    Slog.e(TAG, "exception in acquireWakeLock()", e);
                }
            }
        }
    }

    private void decrementPendingBroadcasts() {
        synchronized (mWakeLock) {
            if (--mPendingBroadcasts == 0) {
                try {
                    // Release wake lock
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                        log("Released wakelock");
                    } else {
                        log("Can't release wakelock again!");
                    }
                } catch (Exception e) {
                    // This is to catch a runtime exception thrown when we try to release an
                    // already released lock.
                    Slog.e(TAG, "exception in releaseWakeLock()", e);
                }
            }
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

    private void checkMockPermissionsSafe() {
        boolean allowMocks = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION, 0) == 1;
        if (!allowMocks) {
            throw new SecurityException("Requires ACCESS_MOCK_LOCATION secure setting");
        }

        if (mContext.checkCallingPermission(ACCESS_MOCK_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires ACCESS_MOCK_LOCATION permission");
        }
    }

    @Override
    public void addTestProvider(String name, ProviderProperties properties) {
        checkMockPermissionsSafe();

        if (LocationManager.PASSIVE_PROVIDER.equals(name)) {
            throw new IllegalArgumentException("Cannot mock the passive location provider");
        }

        long identity = Binder.clearCallingIdentity();
        synchronized (mLock) {
            MockProvider provider = new MockProvider(name, this, properties);
            // remove the real provider if we are replacing GPS or network provider
            if (LocationManager.GPS_PROVIDER.equals(name)
                    || LocationManager.NETWORK_PROVIDER.equals(name)
                    || LocationManager.FUSED_PROVIDER.equals(name)) {
                LocationProviderInterface p = mProvidersByName.get(name);
                if (p != null) {
                    removeProviderLocked(p);
                }
            }
            if (mProvidersByName.get(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }
            addProviderLocked(provider);
            mMockProviders.put(name, provider);
            mLastLocation.put(name, null);
            updateProvidersLocked();
        }
        Binder.restoreCallingIdentity(identity);
    }

    @Override
    public void removeTestProvider(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.remove(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            removeProviderLocked(mProvidersByName.get(provider));

            // reinstate real provider if available
            LocationProviderInterface realProvider = mRealProviders.get(provider);
            if (realProvider != null) {
                addProviderLocked(realProvider);
            }
            mLastLocation.put(provider, null);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void setTestProviderLocation(String provider, Location loc) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            // clear calling identity so INSTALL_LOCATION_PROVIDER permission is not required
            long identity = Binder.clearCallingIdentity();
            mockProvider.setLocation(loc);
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearTestProviderLocation(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.clearLocation();
        }
    }

    @Override
    public void setTestProviderEnabled(String provider, boolean enabled) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            if (enabled) {
                mockProvider.enable();
                mEnabledProviders.add(provider);
                mDisabledProviders.remove(provider);
            } else {
                mockProvider.disable();
                mEnabledProviders.remove(provider);
                mDisabledProviders.add(provider);
            }
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearTestProviderEnabled(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            mEnabledProviders.remove(provider);
            mDisabledProviders.remove(provider);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.setStatus(status, extras, updateTime);
        }
    }

    @Override
    public void clearTestProviderStatus(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.clearStatus();
        }
    }

    private void log(String log) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.d(TAG, log);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump LocationManagerService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mLock) {
            pw.println("Current Location Manager state:");
            pw.println("  Location Listeners:");
            for (Receiver receiver : mReceivers.values()) {
                pw.println("    " + receiver);
            }
            pw.println("  Records by Provider:");
            for (Map.Entry<String, ArrayList<UpdateRecord>> entry : mRecordsByProvider.entrySet()) {
                pw.println("    " + entry.getKey() + ":");
                for (UpdateRecord record : entry.getValue()) {
                    pw.println("      " + record);
                }
            }
            pw.println("  Last Known Locations:");
            for (Map.Entry<String, Location> entry : mLastLocation.entrySet()) {
                String provider = entry.getKey();
                Location location = entry.getValue();
                pw.println("    " + provider + ": " + location);
            }

            mGeofenceManager.dump(pw);

            if (mEnabledProviders.size() > 0) {
                pw.println("  Enabled Providers:");
                for (String i : mEnabledProviders) {
                    pw.println("    " + i);
                }

            }
            if (mDisabledProviders.size() > 0) {
                pw.println("  Disabled Providers:");
                for (String i : mDisabledProviders) {
                    pw.println("    " + i);
                }
            }
            pw.append("  ");
            mBlacklist.dump(pw);
            if (mMockProviders.size() > 0) {
                pw.println("  Mock Providers:");
                for (Map.Entry<String, MockProvider> i : mMockProviders.entrySet()) {
                    i.getValue().dump(pw, "      ");
                }
            }

            pw.append("  fudger: ");
            mLocationFudger.dump(fd, pw,  args);

            if (args.length > 0 && "short".equals(args[0])) {
                return;
            }
            for (LocationProviderInterface provider: mProviders) {
                pw.print(provider.getName() + " Internal State");
                if (provider instanceof LocationProviderProxy) {
                    LocationProviderProxy proxy = (LocationProviderProxy) provider;
                    pw.print(" (" + proxy.getConnectedPackageName() + ")");
                }
                pw.println(":");
                provider.dump(fd, pw, args);
            }
        }
    }
}
