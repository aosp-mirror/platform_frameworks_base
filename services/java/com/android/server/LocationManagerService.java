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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.regex.Pattern;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.IGeocodeProvider;
import android.location.IGpsStatusListener;
import android.location.IGpsStatusProvider;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.ILocationProvider;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.location.GpsLocationProvider;
import com.android.internal.location.LocationProviderProxy;
import com.android.internal.location.MockProvider;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 *
 * {@hide}
 */
public class LocationManagerService extends ILocationManager.Stub implements Runnable {
    private static final String TAG = "LocationManagerService";
    private static final boolean LOCAL_LOGV = false;

    // Minimum time interval between last known location writes, in milliseconds.
    private static final long MIN_LAST_KNOWN_LOCATION_TIME = 60L * 1000L;

    // Max time to hold wake lock for, in milliseconds.
    private static final long MAX_TIME_FOR_WAKE_LOCK = 60 * 1000L;

    // The last time a location was written, by provider name.
    private HashMap<String,Long> mLastWriteTime = new HashMap<String,Long>();

    private static final Pattern PATTERN_COMMA = Pattern.compile(",");

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
    private IGeocodeProvider mGeocodeProvider;
    private IGpsStatusProvider mGpsStatusProvider;
    private LocationWorkerHandler mLocationHandler;

    // Handler messages
    private static final int MESSAGE_LOCATION_CHANGED = 1;

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
    private final ArrayList<LocationProviderProxy> mProviders =
        new ArrayList<LocationProviderProxy>();
    private final HashMap<String, LocationProviderProxy> mProvidersByName
        = new HashMap<String, LocationProviderProxy>();

    /**
     * Object used internally for synchronization
     */
    private final Object mLock = new Object();

    /**
     * Mapping from provider name to all its UpdateRecords
     */
    private final HashMap<String,ArrayList<UpdateRecord>> mRecordsByProvider =
        new HashMap<String,ArrayList<UpdateRecord>>();

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
            if (mListener != null) {
                return "Receiver{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + " Listener " + mKey + "}";
            } else {
                return "Receiver{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + " Intent " + mKey + "}";
            }
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
                        mPendingIntent.send(mContext, 0, statusChanged, this, mLocationHandler);
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
                        mPendingIntent.send(mContext, 0, locationChanged, this, mLocationHandler);
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
                        mPendingIntent.send(mContext, 0, providerIntent, this, mLocationHandler);
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
                Log.v(TAG, "Location listener died");
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
        Receiver receiver = getReceiver(listener);
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

    private Location readLastKnownLocationLocked(String provider) {
        Location location = null;
        String s = null;
        try {
            File f = new File(LocationManager.SYSTEM_DIR + "/location."
                    + provider);
            if (!f.exists()) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new FileReader(f), 256);
            s = reader.readLine();
        } catch (IOException e) {
            Log.w(TAG, "Unable to read last known location", e);
        }

        if (s == null) {
            return null;
        }
        try {
            String[] tokens = PATTERN_COMMA.split(s);
            int idx = 0;
            long time = Long.parseLong(tokens[idx++]);
            double latitude = Double.parseDouble(tokens[idx++]);
            double longitude = Double.parseDouble(tokens[idx++]);
            double altitude = Double.parseDouble(tokens[idx++]);
            float bearing = Float.parseFloat(tokens[idx++]);
            float speed = Float.parseFloat(tokens[idx++]);

            location = new Location(provider);
            location.setTime(time);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setAltitude(altitude);
            location.setBearing(bearing);
            location.setSpeed(speed);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "NumberFormatException reading last known location", nfe);
            return null;
        }

        return location;
    }

    private void writeLastKnownLocationLocked(String provider,
        Location location) {
        long now = SystemClock.elapsedRealtime();
        Long last = mLastWriteTime.get(provider);
        if ((last != null)
            && (now - last.longValue() < MIN_LAST_KNOWN_LOCATION_TIME)) {
            return;
        }
        mLastWriteTime.put(provider, now);

        StringBuilder sb = new StringBuilder(100);
        sb.append(location.getTime());
        sb.append(',');
        sb.append(location.getLatitude());
        sb.append(',');
        sb.append(location.getLongitude());
        sb.append(',');
        sb.append(location.getAltitude());
        sb.append(',');
        sb.append(location.getBearing());
        sb.append(',');
        sb.append(location.getSpeed());

        FileWriter writer = null;
        try {
            File d = new File(LocationManager.SYSTEM_DIR);
            if (!d.exists()) {
                if (!d.mkdirs()) {
                    Log.w(TAG, "Unable to create directory to write location");
                    return;
                }
            }
            File f = new File(LocationManager.SYSTEM_DIR + "/location." + provider);
            writer = new FileWriter(f);
            writer.write(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "Unable to write location", e);
        } finally {
            if (writer != null) {
                try {
                writer.close();
                } catch (IOException e) {
                    Log.w(TAG, "Exception closing file", e);
                }
            }
        }
    }

    private void addProvider(LocationProviderProxy provider) {
        mProviders.add(provider);
        mProvidersByName.put(provider.getName(), provider);
    }

    private void removeProvider(LocationProviderProxy provider) {
        mProviders.remove(provider);
        provider.unlinkProvider();
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
            Log.e(TAG, "Exception loading providers:", e);
        }
    }

    private void _loadProvidersLocked() {
        // Attempt to load "real" providers first
        if (GpsLocationProvider.isSupported()) {
            // Create a gps location provider
            GpsLocationProvider provider = new GpsLocationProvider(mContext, this);
            mGpsStatusProvider = provider.getGpsStatusProvider();
            LocationProviderProxy proxy = new LocationProviderProxy(LocationManager.GPS_PROVIDER, provider);
            addProvider(proxy);
        }

        updateProvidersLocked();
    }

    /**
     * @param context the context that the LocationManagerService runs in
     */
    public LocationManagerService(Context context) {
        super();
        mContext = context;

        Thread thread = new Thread(null, this, "LocationManagerService");
        thread.start();

        if (LOCAL_LOGV) {
            Log.v(TAG, "Constructed LocationManager Service");
        }
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
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);

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

    public void installLocationProvider(String name, ILocationProvider provider) {
        if (mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires INSTALL_LOCATION_PROVIDER permission");
        }

        synchronized (mLock) {
            // check to see if we are reinstalling a dead provider
            LocationProviderProxy oldProvider = mProvidersByName.get(name);
            if (oldProvider != null) {
                if (oldProvider.isDead()) {
                    Log.d(TAG, "replacing dead provider");
                    removeProvider(oldProvider);
                } else {
                    throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
                }
            }

            LocationProviderProxy proxy = new LocationProviderProxy(name, provider);
            addProvider(proxy);
            updateProvidersLocked();

            // notify provider of current network state
            proxy.updateNetworkState(mNetworkState);
        }
    }

    public void installGeocodeProvider(IGeocodeProvider provider) {
        if (mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires INSTALL_LOCATION_PROVIDER permission");
        }

        mGeocodeProvider = provider;
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
        String allowedProviders = Settings.Secure.getString(resolver,
           Settings.Secure.LOCATION_PROVIDERS_ALLOWED);

        return ((allowedProviders != null) && (allowedProviders.contains(provider)));
    }

    private void checkPermissionsSafe(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)
            && (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)
            && (mContext.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            && (mContext.checkCallingOrSelfPermission(ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                "Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission");
        }
    }

    private boolean isAllowedProviderSafe(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)
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
            Log.e(TAG, "getAllProviders got exception:", e);
            return null;
        }
    }

    private List<String> _getAllProvidersLocked() {
        if (LOCAL_LOGV) {
            Log.v(TAG, "getAllProviders");
        }
        ArrayList<String> out = new ArrayList<String>(mProviders.size());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderProxy p = mProviders.get(i);
            out.add(p.getName());
        }
        return out;
    }

    public List<String> getProviders(boolean enabledOnly) {
        try {
            synchronized (mLock) {
                return _getProvidersLocked(enabledOnly);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getProviders got exception:", e);
            return null;
        }
    }

    private List<String> _getProvidersLocked(boolean enabledOnly) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "getProviders");
        }
        ArrayList<String> out = new ArrayList<String>(mProviders.size());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderProxy p = mProviders.get(i);
            String name = p.getName();
            if (isAllowedProviderSafe(name)) {
                if (enabledOnly && !isAllowedBySettingsLocked(name)) {
                    continue;
                }
                out.add(name);
            }
        }
        return out;
    }

    private void updateProvidersLocked() {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            LocationProviderProxy p = mProviders.get(i);
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedBySettingsLocked(name);

            if (isEnabled && !shouldBeEnabled) {
                updateProviderListenersLocked(name, false);
            } else if (!isEnabled && shouldBeEnabled) {
                updateProviderListenersLocked(name, true);
            }

        }
    }

    private void updateProviderListenersLocked(String provider, boolean enabled) {
        int listeners = 0;

        LocationProviderProxy p = mProvidersByName.get(provider);
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
                p.setMinTime(getMinTimeLocked(provider));
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
        if (records != null) {
            for (int i=records.size()-1; i>=0; i--) {
                minTime = Math.min(minTime, records.get(i).mMinTime);
            }
        }
        return minTime;
    }

    private class UpdateRecord {
        final String mProvider;
        final Receiver mReceiver;
        final long mMinTime;
        final float mMinDistance;
        final int mUid;
        Location mLastFixBroadcast;
        long mLastStatusBroadcast;

        /**
         * Note: must be constructed with lock held.
         */
        UpdateRecord(String provider, long minTime, float minDistance,
            Receiver receiver, int uid) {
            mProvider = provider;
            mReceiver = receiver;
            mMinTime = minTime;
            mMinDistance = minDistance;
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
            records.remove(this);
        }

        @Override
        public String toString() {
            return "UpdateRecord{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + mProvider + " " + mReceiver + "}";
        }
        
        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
            pw.println(prefix + "mProvider=" + mProvider + " mReceiver=" + mReceiver);
            pw.println(prefix + "mMinTime=" + mMinTime + " mMinDistance=" + mMinDistance);
            pw.println(prefix + "mUid=" + mUid);
            pw.println(prefix + "mLastFixBroadcast:");
            mLastFixBroadcast.dump(new PrintWriterPrinter(pw), prefix + "  ");
            pw.println(prefix + "mLastStatusBroadcast=" + mLastStatusBroadcast);
        }
        
        /**
         * Calls dispose().
         */
        @Override protected void finalize() {
            synchronized (mLock) {
                disposeLocked();
            }
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
                Log.e(TAG, "linkToDeath failed:", e);
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

    public void requestLocationUpdates(String provider,
        long minTime, float minDistance, ILocationListener listener) {

        try {
            synchronized (mLock) {
                requestLocationUpdatesLocked(provider, minTime, minDistance, getReceiver(listener));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "requestUpdates got exception:", e);
        }
    }

    public void requestLocationUpdatesPI(String provider,
            long minTime, float minDistance, PendingIntent intent) {
        try {
            synchronized (mLock) {
                requestLocationUpdatesLocked(provider, minTime, minDistance, getReceiver(intent));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "requestUpdates got exception:", e);
        }
    }

    private void requestLocationUpdatesLocked(String provider,
            long minTime, float minDistance, Receiver receiver) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "_requestLocationUpdates: listener = " + receiver);
        }

        LocationProviderProxy proxy = mProvidersByName.get(provider);
        if (proxy == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        checkPermissionsSafe(provider);

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        boolean newUid = !providerHasListener(provider, callingUid, null);
        long identity = Binder.clearCallingIdentity();
        try {
            UpdateRecord r = new UpdateRecord(provider, minTime, minDistance, receiver, callingUid);
            UpdateRecord oldRecord = receiver.mUpdateRecords.put(provider, r);
            if (oldRecord != null) {
                oldRecord.disposeLocked();
            }

            if (newUid) {
                proxy.addListener(callingUid);
            }

            boolean isProviderEnabled = isAllowedBySettingsLocked(provider);
            if (isProviderEnabled) {
                long minTimeForProvider = getMinTimeLocked(provider);
                proxy.setMinTime(minTimeForProvider);
                proxy.enableLocationTracking(true);
            } else {
                // Notify the listener that updates are currently disabled
                receiver.callProviderEnabledLocked(provider, false);
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
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    public void removeUpdatesPI(PendingIntent intent) {
        try {
            synchronized (mLock) {
                removeUpdatesLocked(getReceiver(intent));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    private void removeUpdatesLocked(Receiver receiver) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "_removeUpdates: listener = " + receiver);
        }

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            if (mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
                receiver.getListener().asBinder().unlinkToDeath(receiver, 0);
            }

            // Record which providers were associated with this listener
            HashSet<String> providers = new HashSet<String>();
            HashMap<String,UpdateRecord> oldRecords = receiver.mUpdateRecords;
            if (oldRecords != null) {
                // Call dispose() on the obsolete update records.
                for (UpdateRecord record : oldRecords.values()) {
                    if (!providerHasListener(record.mProvider, callingUid, receiver)) {
                        LocationProviderProxy proxy = mProvidersByName.get(record.mProvider);
                        if (proxy != null) {
                            proxy.removeListener(callingUid);
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

                LocationProviderProxy p = mProvidersByName.get(provider);
                if (p != null) {
                    if (hasOtherListener) {
                        p.setMinTime(getMinTimeLocked(provider));
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
            Log.e(TAG, "mGpsStatusProvider.addGpsStatusListener failed", e);
            return false;
        }
        return true;
    }

    public void removeGpsStatusListener(IGpsStatusListener listener) {
        synchronized (mLock) {
            try {
                mGpsStatusProvider.removeGpsStatusListener(listener);
            } catch (Exception e) {
                Log.e(TAG, "mGpsStatusProvider.removeGpsStatusListener failed", e);
            }
        }
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        // first check for permission to the provider
        checkPermissionsSafe(provider);
        // and check for ACCESS_LOCATION_EXTRA_COMMANDS
        if ((mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }

        synchronized (mLock) {
            LocationProviderProxy proxy = mProvidersByName.get(provider);
            if (provider == null) {
                return false;
            }
    
            return proxy.sendExtraCommand(command, extras);
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

        boolean isInProximity(double latitude, double longitude) {
            Location loc = new Location("");
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);

            double radius = loc.distanceTo(mLocation);
            return radius <= mRadius;
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
            ArrayList<PendingIntent> intentsToRemove = null;

            for (ProximityAlert alert : mProximityAlerts.values()) {
                PendingIntent intent = alert.getIntent();
                long expiration = alert.getExpiration();

                if ((expiration == -1) || (now <= expiration)) {
                    boolean entered = mProximitiesEntered.contains(alert);
                    boolean inProximity =
                        alert.isInProximity(latitude, longitude);
                    if (!entered && inProximity) {
                        if (LOCAL_LOGV) {
                            Log.v(TAG, "Entered alert");
                        }
                        mProximitiesEntered.add(alert);
                        Intent enteredIntent = new Intent();
                        enteredIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
                        try {
                            synchronized (this) {
                                // synchronize to ensure incrementPendingBroadcasts()
                                // is called before decrementPendingBroadcasts()
                                intent.send(mContext, 0, enteredIntent, this, mLocationHandler);
                                // call this after broadcasting so we do not increment
                                // if we throw an exeption.
                                incrementPendingBroadcasts();
                            }
                        } catch (PendingIntent.CanceledException e) {
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "Canceled proximity alert: " + alert, e);
                            }
                            if (intentsToRemove == null) {
                                intentsToRemove = new ArrayList<PendingIntent>();
                            }
                            intentsToRemove.add(intent);
                        }
                    } else if (entered && !inProximity) {
                        if (LOCAL_LOGV) {
                            Log.v(TAG, "Exited alert");
                        }
                        mProximitiesEntered.remove(alert);
                        Intent exitedIntent = new Intent();
                        exitedIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
                        try {
                            synchronized (this) {
                                // synchronize to ensure incrementPendingBroadcasts()
                                // is called before decrementPendingBroadcasts()
                                intent.send(mContext, 0, exitedIntent, this, mLocationHandler);
                                // call this after broadcasting so we do not increment
                                // if we throw an exeption.
                                incrementPendingBroadcasts();
                            }
                        } catch (PendingIntent.CanceledException e) {
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "Canceled proximity alert: " + alert, e);
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
                        Log.v(TAG, "Expiring proximity alert: " + alert);
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
                    mProximityAlerts.remove(i);
                    ProximityAlert alert = mProximityAlerts.get(i);
                    mProximitiesEntered.remove(alert);
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
        try {
            synchronized (mLock) {
                addProximityAlertLocked(latitude, longitude, radius, expiration, intent);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "addProximityAlert got exception:", e);
        }
    }

    private void addProximityAlertLocked(double latitude, double longitude,
        float radius, long expiration, PendingIntent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "addProximityAlert: latitude = " + latitude +
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
                LocationProviderProxy provider = mProviders.get(i);
                requestLocationUpdatesLocked(provider.getName(), 1000L, 1.0f, mProximityReceiver);
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
        } catch (Exception e) {
            Log.e(TAG, "removeProximityAlert got exception:", e);
        }
    }

    private void removeProximityAlertLocked(PendingIntent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "removeProximityAlert: intent = " + intent);
        }

        mProximityAlerts.remove(intent);
        if (mProximityAlerts.size() == 0) {
            removeUpdatesLocked(mProximityReceiver);
            mProximityReceiver = null;
            mProximityListener = null;
        }
     }

    /**
     * @return null if the provider does not exits
     * @throw SecurityException if the provider is not allowed to be
     * accessed by the caller
     */
    public Bundle getProviderInfo(String provider) {
        try {
            synchronized (mLock) {
                return _getProviderInfoLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "_getProviderInfo got exception:", e);
            return null;
        }
    }

    private Bundle _getProviderInfoLocked(String provider) {
        LocationProviderProxy p = mProvidersByName.get(provider);
        if (p == null) {
            return null;
        }

        checkPermissionsSafe(provider);

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
            Log.e(TAG, "isProviderEnabled got exception:", e);
            return false;
        }
    }

    public void reportLocation(Location location) {
        if (mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires INSTALL_LOCATION_PROVIDER permission");
        }

        mLocationHandler.removeMessages(MESSAGE_LOCATION_CHANGED, location);
        Message m = Message.obtain(mLocationHandler, MESSAGE_LOCATION_CHANGED, location);
        mLocationHandler.sendMessageAtFrontOfQueue(m);
    }

    private boolean _isProviderEnabledLocked(String provider) {
        checkPermissionsSafe(provider);

        LocationProviderProxy p = mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }
        return isAllowedBySettingsLocked(provider);
    }

    public Location getLastKnownLocation(String provider) {
        try {
            synchronized (mLock) {
                return _getLastKnownLocationLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getLastKnownLocation got exception:", e);
            return null;
        }
    }

    private Location _getLastKnownLocationLocked(String provider) {
        checkPermissionsSafe(provider);

        LocationProviderProxy p = mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        if (!isAllowedBySettingsLocked(provider)) {
            return null;
        }

        Location location = mLastKnownLocation.get(provider);
        if (location == null) {
            // Get the persistent last known location for the provider
            location = readLastKnownLocationLocked(provider);
            if (location != null) {
                mLastKnownLocation.put(provider, location);
            }
        }

        return location;
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

    private void handleLocationChangedLocked(Location location) {
        String provider = location.getProvider();
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records == null || records.size() == 0) {
            return;
        }

        LocationProviderProxy p = mProvidersByName.get(provider);
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
        writeLastKnownLocationLocked(provider, location);

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

            Location lastLoc = r.mLastFixBroadcast;
            if ((lastLoc == null) || shouldBroadcastSafe(location, lastLoc, r)) {
                if (lastLoc == null) {
                    lastLoc = new Location(location);
                    r.mLastFixBroadcast = lastLoc;
                } else {
                    lastLoc.set(location);
                }
                if (!receiver.callLocationChangedLocked(location)) {
                    Log.w(TAG, "RemoteException calling onLocationChanged on " + receiver);
                    if (deadReceivers == null) {
                        deadReceivers = new ArrayList<Receiver>();
                    }
                    deadReceivers.add(receiver);
                }
            }

            long prevStatusUpdateTime = r.mLastStatusBroadcast;
            if ((newStatusUpdateTime > prevStatusUpdateTime) &&
                (prevStatusUpdateTime != 0 || status != LocationProvider.AVAILABLE)) {

                r.mLastStatusBroadcast = newStatusUpdateTime;
                if (!receiver.callStatusChangedLocked(provider, status, extras)) {
                    Log.w(TAG, "RemoteException calling onStatusChanged on " + receiver);
                    if (deadReceivers == null) {
                        deadReceivers = new ArrayList<Receiver>();
                    }
                    if (!deadReceivers.contains(receiver)) {
                        deadReceivers.add(receiver);
                    }
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

                        // notify other providers of the new location
                        for (int i = mProviders.size() - 1; i >= 0; i--) {
                            LocationProviderProxy proxy = mProviders.get(i);
                            if (!provider.equals(proxy.getName())) {
                                proxy.updateLocation(location);
                            }
                        }

                        if (isAllowedBySettingsLocked(provider)) {
                            handleLocationChangedLocked(location);
                        }
                    }
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in LocationWorkerHandler.handleMessage:", e);
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)) {
                synchronized (mLock) {
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid >= 0) {
                        ArrayList<Receiver> removedRecs = null;
                        for (ArrayList<UpdateRecord> i : mRecordsByProvider.values()) {
                            for (int j=i.size()-1; j>=0; j--) {
                                UpdateRecord ur = i.get(j);
                                if (ur.mReceiver.isPendingIntent() && ur.mUid == uid) {
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
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (!noConnectivity) {
                    mNetworkState = LocationProvider.AVAILABLE;
                } else {
                    mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;
                }

                // Notify location providers of current network state
                synchronized (mLock) {
                    for (int i = mProviders.size() - 1; i >= 0; i--) {
                        LocationProviderProxy provider = mProviders.get(i);
                        if (provider.requiresNetwork()) {
                            provider.updateNetworkState(mNetworkState);
                        }
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
                    Log.e(TAG, "exception in acquireWakeLock()", e);
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
                    Log.e(TAG, "exception in releaseWakeLock()", e);
                }
            }
        }
    }

    // Geocoder

    public String getFromLocation(double latitude, double longitude, int maxResults,
            String language, String country, String variant, String appName, List<Address> addrs) {
        if (mGeocodeProvider != null) {
            try {
                return mGeocodeProvider.getFromLocation(latitude, longitude, maxResults, language, country,
                        variant, appName,  addrs);
            } catch (RemoteException e) {
                Log.e(TAG, "getFromLocation failed", e);
                mGeocodeProvider = null;
            }
        }
        return null;
    }


    public String getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            String language, String country, String variant, String appName, List<Address> addrs) {

        if (mGeocodeProvider != null) {
            try {
                return mGeocodeProvider.getFromLocationName(locationName, lowerLeftLatitude,
                        lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                        maxResults, language, country, variant, appName, addrs);
            } catch (RemoteException e) {
                Log.e(TAG, "getFromLocationName failed", e);
                mGeocodeProvider = null;
            }
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

        synchronized (mLock) {
            MockProvider provider = new MockProvider(name, this,
                requiresNetwork, requiresSatellite,
                requiresCell, hasMonetaryCost, supportsAltitude,
                supportsSpeed, supportsBearing, powerRequirement, accuracy);
            if (mProvidersByName.get(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }

            // clear calling identity so INSTALL_LOCATION_PROVIDER permission is not required
            long identity = Binder.clearCallingIdentity();
            addProvider(new LocationProviderProxy(name, provider));
            mMockProviders.put(name, provider);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeTestProvider(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            removeProvider(mProvidersByName.get(provider));
            mMockProviders.remove(mockProvider);
            updateProvidersLocked();
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
        }
    }

    public void clearTestProviderEnabled(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mEnabledProviders.remove(provider);
            mDisabledProviders.remove(provider);
            updateProvidersLocked();
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
            Log.d(TAG, log);
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
        }
    }
}
