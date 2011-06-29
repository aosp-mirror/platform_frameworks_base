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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.IGpsStatusListener;
import android.location.IGpsStatusProvider;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.util.PrintWriterPrinter;

import com.android.internal.content.PackageMonitor;
import com.android.internal.location.GpsNetInitiatedHandler;

import com.android.server.location.GeocoderProxy;
import com.android.server.location.GpsLocationProvider;
import com.android.server.location.LocationProviderInterface;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.MockProvider;
import com.android.server.location.PassiveProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 *
 * {@hide}
 */
public class LocationManagerService extends ILocationManager.Stub implements Runnable {
    private static final String TAG = "LocationManagerService";
    private static final boolean LOCAL_LOGV = false;

    // The last time a location was written, by provider name.
    private HashMap<String,Long> mLastWriteTime = new HashMap<String,Long>();

    private static final String ACCESS_FINE_LOCATION =
        android.Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String ACCESS_COARSE_LOCATION =
        android.Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String ACCESS_MOCK_LOCATION =
        android.Manifest.permission.ACCESS_MOCK_LOCATION;
    private static final String ACCESS_LOCATION_EXTRA_COMMANDS =
        android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS;
    private static final String INSTALL_LOCATION_PROVIDER =
        android.Manifest.permission.INSTALL_LOCATION_PROVIDER;

    // Set of providers that are explicitly enabled
    private final Set<String> mEnabledProviders = new HashSet<String>();

    // Set of providers that are explicitly disabled
    private final Set<String> mDisabledProviders = new HashSet<String>();

    // Locations, status values, and extras for mock providers
    private final HashMap<String,MockProvider> mMockProviders = new HashMap<String,MockProvider>();

    private static boolean sProvidersLoaded = false;

    private final Context mContext;
    private final String mNetworkLocationProviderPackageName;
    private final String mGeocodeProviderPackageName;
    private GeocoderProxy mGeocodeProvider;
    private IGpsStatusProvider mGpsStatusProvider;
    private INetInitiatedListener mNetInitiatedListener;
    private LocationWorkerHandler mLocationHandler;

    // Cache the real providers for use in addTestProvider() and removeTestProvider()
     LocationProviderProxy mNetworkLocationProvider;
     LocationProviderInterface mGpsLocationProvider;

    // Handler messages
    private static final int MESSAGE_LOCATION_CHANGED = 1;
    private static final int MESSAGE_PACKAGE_UPDATED = 2;

    // wakelock variables
    private final static String WAKELOCK_KEY = "LocationManagerService";
    private PowerManager.WakeLock mWakeLock = null;
    private int mPendingBroadcasts;
    
    /**
     * List of all receivers.
     */
    private final HashMap<Object, Receiver> mReceivers = new HashMap<Object, Receiver>();


    /**
     * List of location providers.
     */
    private final ArrayList<LocationProviderInterface> mProviders =
        new ArrayList<LocationProviderInterface>();
    private final HashMap<String, LocationProviderInterface> mProvidersByName
        = new HashMap<String, LocationProviderInterface>();

    /**
     * Object used internally for synchronization
     */
    private final Object mLock = new Object();

    /**
     * Mapping from provider name to all its UpdateRecords
     */
    private final HashMap<String,ArrayList<UpdateRecord>> mRecordsByProvider =
        new HashMap<String,ArrayList<UpdateRecord>>();

    /**
     * Temporary filled in when computing min time for a provider.  Access is
     * protected by global lock mLock.
     */
    private final WorkSource mTmpWorkSource = new WorkSource();

    // Proximity listeners
    private Receiver mProximityReceiver = null;
    private ILocationListener mProximityListener = null;
    private HashMap<PendingIntent,ProximityAlert> mProximityAlerts =
        new HashMap<PendingIntent,ProximityAlert>();
    private HashSet<ProximityAlert> mProximitiesEntered =
        new HashSet<ProximityAlert>();

    // Last known location for each provider
    private HashMap<String,Location> mLastKnownLocation =
        new HashMap<String,Location>();

    private int mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;

    // for Settings change notification
    private ContentQueryMap mSettings;

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver implements IBinder.DeathRecipient, PendingIntent.OnFinished {
        final ILocationListener mListener;
        final PendingIntent mPendingIntent;
        final Object mKey;
        final HashMap<String,UpdateRecord> mUpdateRecords = new HashMap<String,UpdateRecord>();
        int mPendingBroadcasts;
        String requiredPermissions;

        Receiver(ILocationListener listener) {
            mListener = listener;
            mPendingIntent = null;
            mKey = listener.asBinder();
        }

        Receiver(PendingIntent intent) {
            mPendingIntent = intent;
            mListener = null;
            mKey = intent;
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj instanceof Receiver) {
                return mKey.equals(
                        ((Receiver)otherObj).mKey);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mKey.hashCode();
        }

        @Override
        public String toString() {
            String result;
            if (mListener != null) {
                result = "Receiver{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + " Listener " + mKey + "}";
            } else {
                result = "Receiver{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + " Intent " + mKey + "}";
            }
            result += "mUpdateRecords: " + mUpdateRecords;
            return result;
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

        public PendingIntent getPendingIntent() {
            if (mPendingIntent != null) {
                return mPendingIntent;
            }
            throw new IllegalStateException("Request for non-existent intent");
        }

        public boolean callStatusChangedLocked(String provider, int status, Bundle extras) {
            if (mListener != null) {
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mListener.onStatusChanged(provider, status, extras);
                        if (mListener != mProximityListener) {
                            // call this after broadcasting so we do not increment
                            // if we throw an exeption.
                            incrementPendingBroadcastsLocked();
                        }
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent statusChanged = new Intent();
                statusChanged.putExtras(extras);
                statusChanged.putExtra(LocationManager.KEY_STATUS_CHANGED, status);
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mPendingIntent.send(mContext, 0, statusChanged, this, mLocationHandler,
                                requiredPermissions);
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
                        mListener.onLocationChanged(location);
                        if (mListener != mProximityListener) {
                            // call this after broadcasting so we do not increment
                            // if we throw an exeption.
                            incrementPendingBroadcastsLocked();
                        }
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent locationChanged = new Intent();
                locationChanged.putExtra(LocationManager.KEY_LOCATION_CHANGED, location);
                try {
                    synchronized (this) {
                        // synchronize to ensure incrementPendingBroadcastsLocked()
                        // is called before decrementPendingBroadcasts()
                        mPendingIntent.send(mContext, 0, locationChanged, this, mLocationHandler,
                                requiredPermissions);
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
                        if (mListener != mProximityListener) {
                            // call this after broadcasting so we do not increment
                            // if we throw an exeption.
                            incrementPendingBroadcastsLocked();
                        }
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
                                requiredPermissions);
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

        public void binderDied() {
            if (LOCAL_LOGV) {
                Slog.v(TAG, "Location listener died");
            }
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

    private final class SettingsObserver implements Observer {
        public void update(Observable o, Object arg) {
            synchronized (mLock) {
                updateProvidersLocked();
            }
        }
    }

    private void addProvider(LocationProviderInterface provider) {
        mProviders.add(provider);
        mProvidersByName.put(provider.getName(), provider);
    }

    private void removeProvider(LocationProviderInterface provider) {
        mProviders.remove(provider);
        mProvidersByName.remove(provider.getName());
    }

    private void loadProviders() {
        synchronized (mLock) {
            if (sProvidersLoaded) {
                return;
            }

            // Load providers
            loadProvidersLocked();
            sProvidersLoaded = true;
        }
    }

    private void loadProvidersLocked() {
        try {
            _loadProvidersLocked();
        } catch (Exception e) {
            Slog.e(TAG, "Exception loading providers:", e);
        }
    }

    private void _loadProvidersLocked() {
        // Attempt to load "real" providers first
        if (GpsLocationProvider.isSupported()) {
            // Create a gps location provider
            GpsLocationProvider gpsProvider = new GpsLocationProvider(mContext, this);
            mGpsStatusProvider = gpsProvider.getGpsStatusProvider();
            mNetInitiatedListener = gpsProvider.getNetInitiatedListener();
            addProvider(gpsProvider);
            mGpsLocationProvider = gpsProvider;
        }

        // create a passive location provider, which is always enabled
        PassiveProvider passiveProvider = new PassiveProvider(this);
        addProvider(passiveProvider);
        mEnabledProviders.add(passiveProvider.getName());

        // initialize external network location and geocoder services
        PackageManager pm = mContext.getPackageManager();
        if (mNetworkLocationProviderPackageName != null &&
                pm.resolveService(new Intent(mNetworkLocationProviderPackageName), 0) != null) {
            mNetworkLocationProvider =
                new LocationProviderProxy(mContext, LocationManager.NETWORK_PROVIDER,
                        mNetworkLocationProviderPackageName, mLocationHandler);
            addProvider(mNetworkLocationProvider);
        }

        if (mGeocodeProviderPackageName != null &&
                pm.resolveService(new Intent(mGeocodeProviderPackageName), 0) != null) {
            mGeocodeProvider = new GeocoderProxy(mContext, mGeocodeProviderPackageName);
        }

        updateProvidersLocked();
    }

    /**
     * @param context the context that the LocationManagerService runs in
     */
    public LocationManagerService(Context context) {
        super();
        mContext = context;
        Resources resources = context.getResources();
        mNetworkLocationProviderPackageName = resources.getString(
                com.android.internal.R.string.config_networkLocationProvider);
        mGeocodeProviderPackageName = resources.getString(
                com.android.internal.R.string.config_geocodeProvider);
        mPackageMonitor.register(context, true);

        if (LOCAL_LOGV) {
            Slog.v(TAG, "Constructed LocationManager Service");
        }
    }

    void systemReady() {
        // we defer starting up the service until the system is ready 
        Thread thread = new Thread(null, this, "LocationManagerService");
        thread.start();
    }

    private void initialize() {
        // Create a wake lock, needs to be done before calling loadProviders() below
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);

        // Load providers
        loadProviders();

        // Register for Network (Wifi or Mobile) updates
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        // Register for Package Manager updates
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        intentFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);

        // listen for settings changes
        ContentResolver resolver = mContext.getContentResolver();
        Cursor settingsCursor = resolver.query(Settings.Secure.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=?)",
                new String[]{Settings.Secure.LOCATION_PROVIDERS_ALLOWED},
                null);
        mSettings = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, mLocationHandler);
        SettingsObserver settingsObserver = new SettingsObserver();
        mSettings.addObserver(settingsObserver);
    }

    public void run()
    {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Looper.prepare();
        mLocationHandler = new LocationWorkerHandler();
        initialize();
        Looper.loop();
    }

    private boolean isAllowedBySettingsLocked(String provider) {
        if (mEnabledProviders.contains(provider)) {
            return true;
        }
        if (mDisabledProviders.contains(provider)) {
            return false;
        }
        // Use system settings
        ContentResolver resolver = mContext.getContentResolver();

        return Settings.Secure.isLocationProviderEnabled(resolver, provider);
    }

    private String checkPermissionsSafe(String provider, String lastPermission) {
        if (LocationManager.GPS_PROVIDER.equals(provider)
                 || LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            if (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Provider " + provider
                        + " requires ACCESS_FINE_LOCATION permission");
            }
            return ACCESS_FINE_LOCATION;
        }

        // Assume any other provider requires the coarse or fine permission.
        if (mContext.checkCallingOrSelfPermission(ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return ACCESS_FINE_LOCATION.equals(lastPermission)
                    ? lastPermission : ACCESS_COARSE_LOCATION;
        }
        if (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return ACCESS_FINE_LOCATION;
        }

        throw new SecurityException("Provider " + provider
                + " requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission");
    }

    private boolean isAllowedProviderSafe(String provider) {
        if ((LocationManager.GPS_PROVIDER.equals(provider)
                || LocationManager.PASSIVE_PROVIDER.equals(provider))
            && (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)
            && (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            && (mContext.checkCallingOrSelfPermission(ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }

        return true;
    }

    public List<String> getAllProviders() {
        try {
            synchronized (mLock) {
                return _getAllProvidersLocked();
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Slog.e(TAG, "getAllProviders got exception:", e);
            return null;
        }
    }

    private List<String> _getAllProvidersLocked() {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "getAllProviders");
        }
        ArrayList<String> out = new ArrayList<String>(mProviders.size());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderInterface p = mProviders.get(i);
            out.add(p.getName());
        }
        return out;
    }

    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        try {
            synchronized (mLock) {
                return _getProvidersLocked(criteria, enabledOnly);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Slog.e(TAG, "getProviders got exception:", e);
            return null;
        }
    }

    private List<String> _getProvidersLocked(Criteria criteria, boolean enabledOnly) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "getProviders");
        }
        ArrayList<String> out = new ArrayList<String>(mProviders.size());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderInterface p = mProviders.get(i);
            String name = p.getName();
            if (isAllowedProviderSafe(name)) {
                if (enabledOnly && !isAllowedBySettingsLocked(name)) {
                    continue;
                }
                if (criteria != null && !p.meetsCriteria(criteria)) {
                    continue;
                }
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Returns the next looser power requirement, in the sequence:
     *
     * POWER_LOW -> POWER_MEDIUM -> POWER_HIGH -> NO_REQUIREMENT
     */
    private int nextPower(int power) {
        switch (power) {
        case Criteria.POWER_LOW:
            return Criteria.POWER_MEDIUM;
        case Criteria.POWER_MEDIUM:
            return Criteria.POWER_HIGH;
        case Criteria.POWER_HIGH:
            return Criteria.NO_REQUIREMENT;
        case Criteria.NO_REQUIREMENT:
        default:
            return Criteria.NO_REQUIREMENT;
        }
    }

    /**
     * Returns the next looser accuracy requirement, in the sequence:
     *
     * ACCURACY_FINE -> ACCURACY_APPROXIMATE-> NO_REQUIREMENT
     */
    private int nextAccuracy(int accuracy) {
        if (accuracy == Criteria.ACCURACY_FINE) {
            return Criteria.ACCURACY_COARSE;
        } else {
            return Criteria.NO_REQUIREMENT;
        }
    }

    private class LpPowerComparator implements Comparator<LocationProviderInterface> {
        public int compare(LocationProviderInterface l1, LocationProviderInterface l2) {
            // Smaller is better
            return (l1.getPowerRequirement() - l2.getPowerRequirement());
         }

         public boolean equals(LocationProviderInterface l1, LocationProviderInterface l2) {
             return (l1.getPowerRequirement() == l2.getPowerRequirement());
         }
    }

    private class LpAccuracyComparator implements Comparator<LocationProviderInterface> {
        public int compare(LocationProviderInterface l1, LocationProviderInterface l2) {
            // Smaller is better
            return (l1.getAccuracy() - l2.getAccuracy());
         }

         public boolean equals(LocationProviderInterface l1, LocationProviderInterface l2) {
             return (l1.getAccuracy() == l2.getAccuracy());
         }
    }

    private class LpCapabilityComparator implements Comparator<LocationProviderInterface> {

        private static final int ALTITUDE_SCORE = 4;
        private static final int BEARING_SCORE = 4;
        private static final int SPEED_SCORE = 4;

        private int score(LocationProviderInterface p) {
            return (p.supportsAltitude() ? ALTITUDE_SCORE : 0) +
                (p.supportsBearing() ? BEARING_SCORE : 0) +
                (p.supportsSpeed() ? SPEED_SCORE : 0);
        }

        public int compare(LocationProviderInterface l1, LocationProviderInterface l2) {
            return (score(l2) - score(l1)); // Bigger is better
         }

         public boolean equals(LocationProviderInterface l1, LocationProviderInterface l2) {
             return (score(l1) == score(l2));
         }
    }

    private LocationProviderInterface best(List<String> providerNames) {
        ArrayList<LocationProviderInterface> providers;
        synchronized (mLock) {
            providers = new ArrayList<LocationProviderInterface>(providerNames.size());
            for (String name : providerNames) {
                providers.add(mProvidersByName.get(name));
            }
        }

        if (providers.size() < 2) {
            return providers.get(0);
        }

        // First, sort by power requirement
        Collections.sort(providers, new LpPowerComparator());
        int power = providers.get(0).getPowerRequirement();
        if (power < providers.get(1).getPowerRequirement()) {
            return providers.get(0);
        }

        int idx, size;

        ArrayList<LocationProviderInterface> tmp = new ArrayList<LocationProviderInterface>();
        idx = 0;
        size = providers.size();
        while ((idx < size) && (providers.get(idx).getPowerRequirement() == power)) {
            tmp.add(providers.get(idx));
            idx++;
        }

        // Next, sort by accuracy
        Collections.sort(tmp, new LpAccuracyComparator());
        int acc = tmp.get(0).getAccuracy();
        if (acc < tmp.get(1).getAccuracy()) {
            return tmp.get(0);
        }

        ArrayList<LocationProviderInterface> tmp2 = new ArrayList<LocationProviderInterface>();
        idx = 0;
        size = tmp.size();
        while ((idx < size) && (tmp.get(idx).getAccuracy() == acc)) {
            tmp2.add(tmp.get(idx));
            idx++;
        }

        // Finally, sort by capability "score"
        Collections.sort(tmp2, new LpCapabilityComparator());
        return tmp2.get(0);
    }

    /**
     * Returns the name of the provider that best meets the given criteria. Only providers
     * that are permitted to be accessed by the calling activity will be
     * returned.  If several providers meet the criteria, the one with the best
     * accuracy is returned.  If no provider meets the criteria,
     * the criteria are loosened in the following sequence:
     *
     * <ul>
     * <li> power requirement
     * <li> accuracy
     * <li> bearing
     * <li> speed
     * <li> altitude
     * </ul>
     *
     * <p> Note that the requirement on monetary cost is not removed
     * in this process.
     *
     * @param criteria the criteria that need to be matched
     * @param enabledOnly if true then only a provider that is currently enabled is returned
     * @return name of the provider that best matches the requirements
     */
    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Make a copy of the criteria that we can modify
        criteria = new Criteria(criteria);

        // Loosen power requirement
        int power = criteria.getPowerRequirement();
        while (goodProviders.isEmpty() && (power != Criteria.NO_REQUIREMENT)) {
            power = nextPower(power);
            criteria.setPowerRequirement(power);
            goodProviders = getProviders(criteria, enabledOnly);
        }
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Loosen accuracy requirement
        int accuracy = criteria.getAccuracy();
        while (goodProviders.isEmpty() && (accuracy != Criteria.NO_REQUIREMENT)) {
            accuracy = nextAccuracy(accuracy);
            criteria.setAccuracy(accuracy);
            goodProviders = getProviders(criteria, enabledOnly);
        }
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove bearing requirement
        criteria.setBearingRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove speed requirement
        criteria.setSpeedRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove altitude requirement
        criteria.setAltitudeRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        return null;
    }

    public boolean providerMeetsCriteria(String provider, Criteria criteria) {
        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }
        return p.meetsCriteria(criteria);
    }

    private void updateProvidersLocked() {
        boolean changesMade = false;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderInterface p = mProviders.get(i);
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedBySettingsLocked(name);
            if (isEnabled && !shouldBeEnabled) {
                updateProviderListenersLocked(name, false);
                changesMade = true;
            } else if (!isEnabled && shouldBeEnabled) {
                updateProviderListenersLocked(name, true);
                changesMade = true;
            }
        }
        if (changesMade) {
            mContext.sendBroadcast(new Intent(LocationManager.PROVIDERS_CHANGED_ACTION));
        }
    }

    private void updateProviderListenersLocked(String provider, boolean enabled) {
        int listeners = 0;

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            return;
        }

        ArrayList<Receiver> deadReceivers = null;
        
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records != null) {
            final int N = records.size();
            for (int i=0; i<N; i++) {
                UpdateRecord record = records.get(i);
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

        if (deadReceivers != null) {
            for (int i=deadReceivers.size()-1; i>=0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }
        
        if (enabled) {
            p.enable();
            if (listeners > 0) {
                p.setMinTime(getMinTimeLocked(provider), mTmpWorkSource);
                p.enableLocationTracking(true);
            }
        } else {
            p.enableLocationTracking(false);
            p.disable();
        }
    }

    private long getMinTimeLocked(String provider) {
        long minTime = Long.MAX_VALUE;
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        mTmpWorkSource.clear();
        if (records != null) {
            for (int i=records.size()-1; i>=0; i--) {
                UpdateRecord ur = records.get(i);
                long curTime = ur.mMinTime;
                if (curTime < minTime) {
                    minTime = curTime;
                }
            }
            long inclTime = (minTime*3)/2;
            for (int i=records.size()-1; i>=0; i--) {
                UpdateRecord ur = records.get(i);
                if (ur.mMinTime <= inclTime) {
                    mTmpWorkSource.add(ur.mUid);
                }
            }
        }
        return minTime;
    }

    private class UpdateRecord {
        final String mProvider;
        final Receiver mReceiver;
        final long mMinTime;
        final float mMinDistance;
        final boolean mSingleShot;
        final int mUid;
        Location mLastFixBroadcast;
        long mLastStatusBroadcast;

        /**
         * Note: must be constructed with lock held.
         */
        UpdateRecord(String provider, long minTime, float minDistance, boolean singleShot,
            Receiver receiver, int uid) {
            mProvider = provider;
            mReceiver = receiver;
            mMinTime = minTime;
            mMinDistance = minDistance;
            mSingleShot = singleShot;
            mUid = uid;

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
        void disposeLocked() {
            ArrayList<UpdateRecord> records = mRecordsByProvider.get(this.mProvider);
            if (records != null) {
                records.remove(this);
            }
        }

        @Override
        public String toString() {
            return "UpdateRecord{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " mProvider: " + mProvider + " mUid: " + mUid + "}";
        }
        
        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
            pw.println(prefix + "mProvider=" + mProvider + " mReceiver=" + mReceiver);
            pw.println(prefix + "mMinTime=" + mMinTime + " mMinDistance=" + mMinDistance);
            pw.println(prefix + "mSingleShot=" + mSingleShot);
            pw.println(prefix + "mUid=" + mUid);
            pw.println(prefix + "mLastFixBroadcast:");
            if (mLastFixBroadcast != null) {
                mLastFixBroadcast.dump(new PrintWriterPrinter(pw), prefix + "  ");
            }
            pw.println(prefix + "mLastStatusBroadcast=" + mLastStatusBroadcast);
        }
    }

    private Receiver getReceiver(ILocationListener listener) {
        IBinder binder = listener.asBinder();
        Receiver receiver = mReceivers.get(binder);
        if (receiver == null) {
            receiver = new Receiver(listener);
            mReceivers.put(binder, receiver);

            try {
                if (receiver.isListener()) {
                    receiver.getListener().asBinder().linkToDeath(receiver, 0);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
                return null;
            }
        }
        return receiver;
    }

    private Receiver getReceiver(PendingIntent intent) {
        Receiver receiver = mReceivers.get(intent);
        if (receiver == null) {
            receiver = new Receiver(intent);
            mReceivers.put(intent, receiver);
        }
        return receiver;
    }

    private boolean providerHasListener(String provider, int uid, Receiver excludedReceiver) {
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records != null) {
            for (int i = records.size() - 1; i >= 0; i--) {
                UpdateRecord record = records.get(i);
                if (record.mUid == uid && record.mReceiver != excludedReceiver) {
                    return true;
                }
           }
        }
        for (ProximityAlert alert : mProximityAlerts.values()) {
            if (alert.mUid == uid) {
                return true;
            }
        }
        return false;
    }

    public void requestLocationUpdates(String provider, Criteria criteria,
        long minTime, float minDistance, boolean singleShot, ILocationListener listener) {
        if (criteria != null) {
            // FIXME - should we consider using multiple providers simultaneously
            // rather than only the best one?
            // Should we do anything different for single shot fixes?
            provider = getBestProvider(criteria, true);
            if (provider == null) {
                throw new IllegalArgumentException("no providers found for criteria");
            }
        }
        try {
            synchronized (mLock) {
                requestLocationUpdatesLocked(provider, minTime, minDistance, singleShot,
                        getReceiver(listener));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "requestUpdates got exception:", e);
        }
    }

    void validatePendingIntent(PendingIntent intent) {
        if (intent.isTargetedToPackage()) {
            return;
        }
        Slog.i(TAG, "Given Intent does not require a specific package: "
                + intent);
        // XXX we should really throw a security exception, if the caller's
        // targetSdkVersion is high enough.
        //throw new SecurityException("Given Intent does not require a specific package: "
        //        + intent);
    }

    public void requestLocationUpdatesPI(String provider, Criteria criteria,
            long minTime, float minDistance, boolean singleShot, PendingIntent intent) {
        validatePendingIntent(intent);
        if (criteria != null) {
            // FIXME - should we consider using multiple providers simultaneously
            // rather than only the best one?
            // Should we do anything different for single shot fixes?
            provider = getBestProvider(criteria, true);
            if (provider == null) {
                throw new IllegalArgumentException("no providers found for criteria");
            }
        }
        try {
            synchronized (mLock) {
                requestLocationUpdatesLocked(provider, minTime, minDistance, singleShot,
                        getReceiver(intent));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "requestUpdates got exception:", e);
        }
    }

    private void requestLocationUpdatesLocked(String provider, long minTime, float minDistance,
            boolean singleShot, Receiver receiver) {

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        receiver.requiredPermissions = checkPermissionsSafe(provider,
                receiver.requiredPermissions);

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        boolean newUid = !providerHasListener(provider, callingUid, null);
        long identity = Binder.clearCallingIdentity();
        try {
            UpdateRecord r = new UpdateRecord(provider, minTime, minDistance, singleShot,
                    receiver, callingUid);
            UpdateRecord oldRecord = receiver.mUpdateRecords.put(provider, r);
            if (oldRecord != null) {
                oldRecord.disposeLocked();
            }

            if (newUid) {
                p.addListener(callingUid);
            }

            boolean isProviderEnabled = isAllowedBySettingsLocked(provider);
            if (isProviderEnabled) {
                long minTimeForProvider = getMinTimeLocked(provider);
                p.setMinTime(minTimeForProvider, mTmpWorkSource);
                // try requesting single shot if singleShot is true, and fall back to
                // regular location tracking if requestSingleShotFix() is not supported
                if (!singleShot || !p.requestSingleShotFix()) {
                    p.enableLocationTracking(true);
                }
            } else {
                // Notify the listener that updates are currently disabled
                receiver.callProviderEnabledLocked(provider, false);
            }
            if (LOCAL_LOGV) {
                Slog.v(TAG, "_requestLocationUpdates: provider = " + provider + " listener = " + receiver);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeUpdates(ILocationListener listener) {
        try {
            synchronized (mLock) {
                removeUpdatesLocked(getReceiver(listener));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "removeUpdates got exception:", e);
        }
    }

    public void removeUpdatesPI(PendingIntent intent) {
        try {
            synchronized (mLock) {
                removeUpdatesLocked(getReceiver(intent));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "removeUpdates got exception:", e);
        }
    }

    private void removeUpdatesLocked(Receiver receiver) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "_removeUpdates: listener = " + receiver);
        }

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            if (mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
                receiver.getListener().asBinder().unlinkToDeath(receiver, 0);
                synchronized(receiver) {
                    if(receiver.mPendingBroadcasts > 0) {
                        decrementPendingBroadcasts();
                        receiver.mPendingBroadcasts = 0;
                    }
                }
            }

            // Record which providers were associated with this listener
            HashSet<String> providers = new HashSet<String>();
            HashMap<String,UpdateRecord> oldRecords = receiver.mUpdateRecords;
            if (oldRecords != null) {
                // Call dispose() on the obsolete update records.
                for (UpdateRecord record : oldRecords.values()) {
                    if (!providerHasListener(record.mProvider, callingUid, receiver)) {
                        LocationProviderInterface p = mProvidersByName.get(record.mProvider);
                        if (p != null) {
                            p.removeListener(callingUid);
                        }
                    }
                    record.disposeLocked();
                }
                // Accumulate providers
                providers.addAll(oldRecords.keySet());
            }

            // See if the providers associated with this listener have any
            // other listeners; if one does, inform it of the new smallest minTime
            // value; if one does not, disable location tracking for it
            for (String provider : providers) {
                // If provider is already disabled, don't need to do anything
                if (!isAllowedBySettingsLocked(provider)) {
                    continue;
                }

                boolean hasOtherListener = false;
                ArrayList<UpdateRecord> recordsForProvider = mRecordsByProvider.get(provider);
                if (recordsForProvider != null && recordsForProvider.size() > 0) {
                    hasOtherListener = true;
                }

                LocationProviderInterface p = mProvidersByName.get(provider);
                if (p != null) {
                    if (hasOtherListener) {
                        p.setMinTime(getMinTimeLocked(provider), mTmpWorkSource);
                    } else {
                        p.enableLocationTracking(false);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean addGpsStatusListener(IGpsStatusListener listener) {
        if (mGpsStatusProvider == null) {
            return false;
        }
        if (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }

        try {
            mGpsStatusProvider.addGpsStatusListener(listener);
        } catch (RemoteException e) {
            Slog.e(TAG, "mGpsStatusProvider.addGpsStatusListener failed", e);
            return false;
        }
        return true;
    }

    public void removeGpsStatusListener(IGpsStatusListener listener) {
        synchronized (mLock) {
            try {
                mGpsStatusProvider.removeGpsStatusListener(listener);
            } catch (Exception e) {
                Slog.e(TAG, "mGpsStatusProvider.removeGpsStatusListener failed", e);
            }
        }
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        if (provider == null) {
            // throw NullPointerException to remain compatible with previous implementation
            throw new NullPointerException();
        }

        // first check for permission to the provider
        checkPermissionsSafe(provider, null);
        // and check for ACCESS_LOCATION_EXTRA_COMMANDS
        if ((mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }

        synchronized (mLock) {
            LocationProviderInterface p = mProvidersByName.get(provider);
            if (p == null) {
                return false;
            }
    
            return p.sendExtraCommand(command, extras);
        }
    }

    public boolean sendNiResponse(int notifId, int userResponse)
    {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException(
                    "calling sendNiResponse from outside of the system is not allowed");
        }
        try {
            return mNetInitiatedListener.sendNiResponse(notifId, userResponse);
        }
        catch (RemoteException e)
        {
            Slog.e(TAG, "RemoteException in LocationManagerService.sendNiResponse");
            return false;
        }
    }

    class ProximityAlert {
        final int  mUid;
        final double mLatitude;
        final double mLongitude;
        final float mRadius;
        final long mExpiration;
        final PendingIntent mIntent;
        final Location mLocation;

        public ProximityAlert(int uid, double latitude, double longitude,
            float radius, long expiration, PendingIntent intent) {
            mUid = uid;
            mLatitude = latitude;
            mLongitude = longitude;
            mRadius = radius;
            mExpiration = expiration;
            mIntent = intent;

            mLocation = new Location("");
            mLocation.setLatitude(latitude);
            mLocation.setLongitude(longitude);
        }

        long getExpiration() {
            return mExpiration;
        }

        PendingIntent getIntent() {
            return mIntent;
        }

        boolean isInProximity(double latitude, double longitude, float accuracy) {
            Location loc = new Location("");
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);

            double radius = loc.distanceTo(mLocation);
            return radius <= Math.max(mRadius,accuracy);
        }
        
        @Override
        public String toString() {
            return "ProximityAlert{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " uid " + mUid + mIntent + "}";
        }
        
        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
            pw.println(prefix + "mLatitude=" + mLatitude + " mLongitude=" + mLongitude);
            pw.println(prefix + "mRadius=" + mRadius + " mExpiration=" + mExpiration);
            pw.println(prefix + "mIntent=" + mIntent);
            pw.println(prefix + "mLocation:");
            mLocation.dump(new PrintWriterPrinter(pw), prefix + "  ");
        }
    }

    // Listener for receiving locations to trigger proximity alerts
    class ProximityListener extends ILocationListener.Stub implements PendingIntent.OnFinished {

        boolean isGpsAvailable = false;

        // Note: this is called with the lock held.
        public void onLocationChanged(Location loc) {

            // If Gps is available, then ignore updates from NetworkLocationProvider
            if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)) {
                isGpsAvailable = true;
            }
            if (isGpsAvailable && loc.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {
                return;
            }

            // Process proximity alerts
            long now = System.currentTimeMillis();
            double latitude = loc.getLatitude();
            double longitude = loc.getLongitude();
            float accuracy = loc.getAccuracy();
            ArrayList<PendingIntent> intentsToRemove = null;

            for (ProximityAlert alert : mProximityAlerts.values()) {
                PendingIntent intent = alert.getIntent();
                long expiration = alert.getExpiration();

                if ((expiration == -1) || (now <= expiration)) {
                    boolean entered = mProximitiesEntered.contains(alert);
                    boolean inProximity =
                        alert.isInProximity(latitude, longitude, accuracy);
                    if (!entered && inProximity) {
                        if (LOCAL_LOGV) {
                            Slog.v(TAG, "Entered alert");
                        }
                        mProximitiesEntered.add(alert);
                        Intent enteredIntent = new Intent();
                        enteredIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
                        try {
                            synchronized (this) {
                                // synchronize to ensure incrementPendingBroadcasts()
                                // is called before decrementPendingBroadcasts()
                                intent.send(mContext, 0, enteredIntent, this, mLocationHandler,
                                        ACCESS_FINE_LOCATION);
                                // call this after broadcasting so we do not increment
                                // if we throw an exeption.
                                incrementPendingBroadcasts();
                            }
                        } catch (PendingIntent.CanceledException e) {
                            if (LOCAL_LOGV) {
                                Slog.v(TAG, "Canceled proximity alert: " + alert, e);
                            }
                            if (intentsToRemove == null) {
                                intentsToRemove = new ArrayList<PendingIntent>();
                            }
                            intentsToRemove.add(intent);
                        }
                    } else if (entered && !inProximity) {
                        if (LOCAL_LOGV) {
                            Slog.v(TAG, "Exited alert");
                        }
                        mProximitiesEntered.remove(alert);
                        Intent exitedIntent = new Intent();
                        exitedIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
                        try {
                            synchronized (this) {
                                // synchronize to ensure incrementPendingBroadcasts()
                                // is called before decrementPendingBroadcasts()
                                intent.send(mContext, 0, exitedIntent, this, mLocationHandler,
                                        ACCESS_FINE_LOCATION);
                                // call this after broadcasting so we do not increment
                                // if we throw an exeption.
                                incrementPendingBroadcasts();
                            }
                        } catch (PendingIntent.CanceledException e) {
                            if (LOCAL_LOGV) {
                                Slog.v(TAG, "Canceled proximity alert: " + alert, e);
                            }
                            if (intentsToRemove == null) {
                                intentsToRemove = new ArrayList<PendingIntent>();
                            }
                            intentsToRemove.add(intent);
                        }
                    }
                } else {
                    // Mark alert for expiration
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "Expiring proximity alert: " + alert);
                    }
                    if (intentsToRemove == null) {
                        intentsToRemove = new ArrayList<PendingIntent>();
                    }
                    intentsToRemove.add(alert.getIntent());
                }
            }

            // Remove expired alerts
            if (intentsToRemove != null) {
                for (PendingIntent i : intentsToRemove) {
                    ProximityAlert alert = mProximityAlerts.get(i);
                    mProximitiesEntered.remove(alert);
                    removeProximityAlertLocked(i);
                }
            }
        }

        // Note: this is called with the lock held.
        public void onProviderDisabled(String provider) {
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                isGpsAvailable = false;
            }
        }

        // Note: this is called with the lock held.
        public void onProviderEnabled(String provider) {
            // ignore
        }

        // Note: this is called with the lock held.
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if ((provider.equals(LocationManager.GPS_PROVIDER)) &&
                (status != LocationProvider.AVAILABLE)) {
                isGpsAvailable = false;
            }
        }

        public void onSendFinished(PendingIntent pendingIntent, Intent intent,
                int resultCode, String resultData, Bundle resultExtras) {
            // synchronize to ensure incrementPendingBroadcasts()
            // is called before decrementPendingBroadcasts()
            synchronized (this) {
                decrementPendingBroadcasts();
            }
        }
    }

    public void addProximityAlert(double latitude, double longitude,
        float radius, long expiration, PendingIntent intent) {
        validatePendingIntent(intent);
        try {
            synchronized (mLock) {
                addProximityAlertLocked(latitude, longitude, radius, expiration, intent);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "addProximityAlert got exception:", e);
        }
    }

    private void addProximityAlertLocked(double latitude, double longitude,
        float radius, long expiration, PendingIntent intent) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "addProximityAlert: latitude = " + latitude +
                    ", longitude = " + longitude +
                    ", expiration = " + expiration +
                    ", intent = " + intent);
        }

        // Require ability to access all providers for now
        if (!isAllowedProviderSafe(LocationManager.GPS_PROVIDER) ||
            !isAllowedProviderSafe(LocationManager.NETWORK_PROVIDER)) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }

        if (expiration != -1) {
            expiration += System.currentTimeMillis();
        }
        ProximityAlert alert = new ProximityAlert(Binder.getCallingUid(),
                latitude, longitude, radius, expiration, intent);
        mProximityAlerts.put(intent, alert);

        if (mProximityReceiver == null) {
            mProximityListener = new ProximityListener();
            mProximityReceiver = new Receiver(mProximityListener);

            for (int i = mProviders.size() - 1; i >= 0; i--) {
                LocationProviderInterface provider = mProviders.get(i);
                requestLocationUpdatesLocked(provider.getName(), 1000L, 1.0f,
                        false, mProximityReceiver);
            }
        }
    }

    public void removeProximityAlert(PendingIntent intent) {
        try {
            synchronized (mLock) {
               removeProximityAlertLocked(intent);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "removeProximityAlert got exception:", e);
        }
    }

    private void removeProximityAlertLocked(PendingIntent intent) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "removeProximityAlert: intent = " + intent);
        }

        mProximityAlerts.remove(intent);
        if (mProximityAlerts.size() == 0) {
            removeUpdatesLocked(mProximityReceiver);
            mProximityReceiver = null;
            mProximityListener = null;
        }
     }

    /**
     * @return null if the provider does not exist
     * @throws SecurityException if the provider is not allowed to be
     * accessed by the caller
     */
    public Bundle getProviderInfo(String provider) {
        try {
            synchronized (mLock) {
                return _getProviderInfoLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            Slog.e(TAG, "_getProviderInfo got exception:", e);
            return null;
        }
    }

    private Bundle _getProviderInfoLocked(String provider) {
        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            return null;
        }

        checkPermissionsSafe(provider, null);

        Bundle b = new Bundle();
        b.putBoolean("network", p.requiresNetwork());
        b.putBoolean("satellite", p.requiresSatellite());
        b.putBoolean("cell", p.requiresCell());
        b.putBoolean("cost", p.hasMonetaryCost());
        b.putBoolean("altitude", p.supportsAltitude());
        b.putBoolean("speed", p.supportsSpeed());
        b.putBoolean("bearing", p.supportsBearing());
        b.putInt("power", p.getPowerRequirement());
        b.putInt("accuracy", p.getAccuracy());

        return b;
    }

    public boolean isProviderEnabled(String provider) {
        try {
            synchronized (mLock) {
                return _isProviderEnabledLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Slog.e(TAG, "isProviderEnabled got exception:", e);
            return false;
        }
    }

    public void reportLocation(Location location, boolean passive) {
        if (mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires INSTALL_LOCATION_PROVIDER permission");
        }

        mLocationHandler.removeMessages(MESSAGE_LOCATION_CHANGED, location);
        Message m = Message.obtain(mLocationHandler, MESSAGE_LOCATION_CHANGED, location);
        m.arg1 = (passive ? 1 : 0);
        mLocationHandler.sendMessageAtFrontOfQueue(m);
    }

    private boolean _isProviderEnabledLocked(String provider) {
        checkPermissionsSafe(provider, null);

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            return false;
        }
        return isAllowedBySettingsLocked(provider);
    }

    public Location getLastKnownLocation(String provider) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "getLastKnownLocation: " + provider);
        }
        try {
            synchronized (mLock) {
                return _getLastKnownLocationLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Slog.e(TAG, "getLastKnownLocation got exception:", e);
            return null;
        }
    }

    private Location _getLastKnownLocationLocked(String provider) {
        checkPermissionsSafe(provider, null);

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            return null;
        }

        if (!isAllowedBySettingsLocked(provider)) {
            return null;
        }

        return mLastKnownLocation.get(provider);
    }

    private static boolean shouldBroadcastSafe(Location loc, Location lastLoc, UpdateRecord record) {
        // Always broadcast the first update
        if (lastLoc == null) {
            return true;
        }

        // Don't broadcast same location again regardless of condition
        // TODO - we should probably still rebroadcast if user explicitly sets a minTime > 0
        if (loc.getTime() == lastLoc.getTime()) {
            return false;
        }

        // Check whether sufficient distance has been traveled
        double minDistance = record.mMinDistance;
        if (minDistance > 0.0) {
            if (loc.distanceTo(lastLoc) <= minDistance) {
                return false;
            }
        }

        return true;
    }

    private void handleLocationChangedLocked(Location location, boolean passive) {
        String provider = (passive ? LocationManager.PASSIVE_PROVIDER : location.getProvider());
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records == null || records.size() == 0) {
            return;
        }

        LocationProviderInterface p = mProvidersByName.get(provider);
        if (p == null) {
            return;
        }

        // Update last known location for provider
        Location lastLocation = mLastKnownLocation.get(provider);
        if (lastLocation == null) {
            mLastKnownLocation.put(provider, new Location(location));
        } else {
            lastLocation.set(location);
        }

        // Fetch latest status update time
        long newStatusUpdateTime = p.getStatusUpdateTime();

       // Get latest status
        Bundle extras = new Bundle();
        int status = p.getStatus(extras);

        ArrayList<Receiver> deadReceivers = null;
        
        // Broadcast location or status to all listeners
        final int N = records.size();
        for (int i=0; i<N; i++) {
            UpdateRecord r = records.get(i);
            Receiver receiver = r.mReceiver;
            boolean receiverDead = false;

            Location lastLoc = r.mLastFixBroadcast;
            if ((lastLoc == null) || shouldBroadcastSafe(location, lastLoc, r)) {
                if (lastLoc == null) {
                    lastLoc = new Location(location);
                    r.mLastFixBroadcast = lastLoc;
                } else {
                    lastLoc.set(location);
                }
                if (!receiver.callLocationChangedLocked(location)) {
                    Slog.w(TAG, "RemoteException calling onLocationChanged on " + receiver);
                    receiverDead = true;
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

            // remove receiver if it is dead or we just processed a single shot request
            if (receiverDead || r.mSingleShot) {
                if (deadReceivers == null) {
                    deadReceivers = new ArrayList<Receiver>();
                }
                if (!deadReceivers.contains(receiver)) {
                    deadReceivers.add(receiver);
                }
            }
        }
        
        if (deadReceivers != null) {
            for (int i=deadReceivers.size()-1; i>=0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }
    }

    private class LocationWorkerHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MESSAGE_LOCATION_CHANGED) {
                    // log("LocationWorkerHandler: MESSAGE_LOCATION_CHANGED!");

                    synchronized (mLock) {
                        Location location = (Location) msg.obj;
                        String provider = location.getProvider();
                        boolean passive = (msg.arg1 == 1);

                        if (!passive) {
                            // notify other providers of the new location
                            for (int i = mProviders.size() - 1; i >= 0; i--) {
                                LocationProviderInterface p = mProviders.get(i);
                                if (!provider.equals(p.getName())) {
                                    p.updateLocation(location);
                                }
                            }
                        }

                        if (isAllowedBySettingsLocked(provider)) {
                            handleLocationChangedLocked(location, passive);
                        }
                    }
                } else if (msg.what == MESSAGE_PACKAGE_UPDATED) {
                    String packageName = (String) msg.obj;
                    String packageDot = packageName + ".";

                    // reconnect to external providers after their packages have been updated
                    if (mNetworkLocationProvider != null &&
                        mNetworkLocationProviderPackageName.startsWith(packageDot)) {
                        mNetworkLocationProvider.reconnect();
                    }
                    if (mGeocodeProvider != null &&
                        mGeocodeProviderPackageName.startsWith(packageDot)) {
                        mGeocodeProvider.reconnect();
                    }
                }
            } catch (Exception e) {
                // Log, don't crash!
                Slog.e(TAG, "Exception in LocationWorkerHandler.handleMessage:", e);
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean queryRestart = action.equals(Intent.ACTION_QUERY_PACKAGE_RESTART);
            if (queryRestart
                    || action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                synchronized (mLock) {
                    int uidList[] = null;
                    if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                        uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                    } else {
                        uidList = new int[]{intent.getIntExtra(Intent.EXTRA_UID, -1)};
                    }
                    if (uidList == null || uidList.length == 0) {
                        return;
                    }
                    for (int uid : uidList) {
                        if (uid >= 0) {
                            ArrayList<Receiver> removedRecs = null;
                            for (ArrayList<UpdateRecord> i : mRecordsByProvider.values()) {
                                for (int j=i.size()-1; j>=0; j--) {
                                    UpdateRecord ur = i.get(j);
                                    if (ur.mReceiver.isPendingIntent() && ur.mUid == uid) {
                                        if (queryRestart) {
                                            setResultCode(Activity.RESULT_OK);
                                            return;
                                        }
                                        if (removedRecs == null) {
                                            removedRecs = new ArrayList<Receiver>();
                                        }
                                        if (!removedRecs.contains(ur.mReceiver)) {
                                            removedRecs.add(ur.mReceiver);
                                        }
                                    }
                                }
                            }
                            ArrayList<ProximityAlert> removedAlerts = null;
                            for (ProximityAlert i : mProximityAlerts.values()) {
                                if (i.mUid == uid) {
                                    if (queryRestart) {
                                        setResultCode(Activity.RESULT_OK);
                                        return;
                                    }
                                    if (removedAlerts == null) {
                                        removedAlerts = new ArrayList<ProximityAlert>();
                                    }
                                    if (!removedAlerts.contains(i)) {
                                        removedAlerts.add(i);
                                    }
                                }
                            }
                            if (removedRecs != null) {
                                for (int i=removedRecs.size()-1; i>=0; i--) {
                                    removeUpdatesLocked(removedRecs.get(i));
                                }
                            }
                            if (removedAlerts != null) {
                                for (int i=removedAlerts.size()-1; i>=0; i--) {
                                    removeProximityAlertLocked(removedAlerts.get(i).mIntent);
                                }
                            }
                        }
                    }
                }
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (!noConnectivity) {
                    mNetworkState = LocationProvider.AVAILABLE;
                } else {
                    mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;
                }
                NetworkInfo info =
                    (NetworkInfo)intent.getExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

                // Notify location providers of current network state
                synchronized (mLock) {
                    for (int i = mProviders.size() - 1; i >= 0; i--) {
                        LocationProviderInterface provider = mProviders.get(i);
                        if (provider.requiresNetwork()) {
                            provider.updateNetworkState(mNetworkState, info);
                        }
                    }
                }
            }
        }
    };

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            // Called by main thread; divert work to LocationWorker.
            Message.obtain(mLocationHandler, MESSAGE_PACKAGE_UPDATED, packageName).sendToTarget();
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

    public boolean geocoderIsPresent() {
        return mGeocodeProvider != null;
    }

    public String getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs) {
        if (mGeocodeProvider != null) {
            return mGeocodeProvider.getFromLocation(latitude, longitude, maxResults,
                    params, addrs);
        }
        return null;
    }


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

    public void addTestProvider(String name, boolean requiresNetwork, boolean requiresSatellite,
        boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
        boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
        checkMockPermissionsSafe();

        if (LocationManager.PASSIVE_PROVIDER.equals(name)) {
            throw new IllegalArgumentException("Cannot mock the passive location provider");
        }

        long identity = Binder.clearCallingIdentity();
        synchronized (mLock) {
            MockProvider provider = new MockProvider(name, this,
                requiresNetwork, requiresSatellite,
                requiresCell, hasMonetaryCost, supportsAltitude,
                supportsSpeed, supportsBearing, powerRequirement, accuracy);
            // remove the real provider if we are replacing GPS or network provider
            if (LocationManager.GPS_PROVIDER.equals(name)
                    || LocationManager.NETWORK_PROVIDER.equals(name)) {
                LocationProviderInterface p = mProvidersByName.get(name);
                if (p != null) {
                    p.enableLocationTracking(false);
                    removeProvider(p);
                }
            }
            if (mProvidersByName.get(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }
            addProvider(provider);
            mMockProviders.put(name, provider);
            mLastKnownLocation.put(name, null);
            updateProvidersLocked();
        }
        Binder.restoreCallingIdentity(identity);
    }

    public void removeTestProvider(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            removeProvider(mProvidersByName.get(provider));
            mMockProviders.remove(mockProvider);
            // reinstall real provider if we were mocking GPS or network provider
            if (LocationManager.GPS_PROVIDER.equals(provider) &&
                    mGpsLocationProvider != null) {
                addProvider(mGpsLocationProvider);
            } else if (LocationManager.NETWORK_PROVIDER.equals(provider) &&
                    mNetworkLocationProvider != null) {
                addProvider(mNetworkLocationProvider);
            }
            mLastKnownLocation.put(provider, null);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

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
            pw.println("  sProvidersLoaded=" + sProvidersLoaded);
            pw.println("  Listeners:");
            int N = mReceivers.size();
            for (int i=0; i<N; i++) {
                pw.println("    " + mReceivers.get(i));
            }
            pw.println("  Location Listeners:");
            for (Receiver i : mReceivers.values()) {
                pw.println("    " + i + ":");
                for (Map.Entry<String,UpdateRecord> j : i.mUpdateRecords.entrySet()) {
                    pw.println("      " + j.getKey() + ":");
                    j.getValue().dump(pw, "        ");
                }
            }
            pw.println("  Records by Provider:");
            for (Map.Entry<String, ArrayList<UpdateRecord>> i
                    : mRecordsByProvider.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                for (UpdateRecord j : i.getValue()) {
                    pw.println("      " + j + ":");
                    j.dump(pw, "        ");
                }
            }
            pw.println("  Last Known Locations:");
            for (Map.Entry<String, Location> i
                    : mLastKnownLocation.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                i.getValue().dump(new PrintWriterPrinter(pw), "      ");
            }
            if (mProximityAlerts.size() > 0) {
                pw.println("  Proximity Alerts:");
                for (Map.Entry<PendingIntent, ProximityAlert> i
                        : mProximityAlerts.entrySet()) {
                    pw.println("    " + i.getKey() + ":");
                    i.getValue().dump(pw, "      ");
                }
            }
            if (mProximitiesEntered.size() > 0) {
                pw.println("  Proximities Entered:");
                for (ProximityAlert i : mProximitiesEntered) {
                    pw.println("    " + i + ":");
                    i.dump(pw, "      ");
                }
            }
            pw.println("  mProximityReceiver=" + mProximityReceiver);
            pw.println("  mProximityListener=" + mProximityListener);
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
            if (mMockProviders.size() > 0) {
                pw.println("  Mock Providers:");
                for (Map.Entry<String, MockProvider> i : mMockProviders.entrySet()) {
                    i.getValue().dump(pw, "      ");
                }
            }
            for (LocationProviderInterface provider: mProviders) {
                String state = provider.getInternalState();
                if (state != null) {
                    pw.println(provider.getName() + " Internal State:");
                    pw.write(state);
                }
            }
        }
    }
}
