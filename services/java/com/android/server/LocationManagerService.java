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

import android.app.AlarmManager;
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
import android.location.ILocationCollector;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.ILocationProvider;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationProviderImpl;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Config;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.SparseIntArray;

import com.android.internal.location.GpsLocationProvider;
import com.android.internal.location.LocationProviderProxy;
import com.android.internal.location.MockProvider;
import com.android.internal.location.TrackProvider;
import com.android.server.am.BatteryStatsService;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 *
 * {@hide}
 */
public class LocationManagerService extends ILocationManager.Stub {
    private static final String TAG = "LocationManagerService";
    private static final boolean LOCAL_LOGV = false;

    // Minimum time interval between last known location writes, in milliseconds.
    private static final long MIN_LAST_KNOWN_LOCATION_TIME = 60L * 1000L;

    // Max time to hold wake lock for, in milliseconds.
    private static final long MAX_TIME_FOR_WAKE_LOCK = 60 * 1000L;

    // Time to wait after releasing a wake lock for clients to process location update,
    // in milliseconds.
    private static final long TIME_AFTER_WAKE_LOCK = 2 * 1000L;

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

    // Set of providers that are explicitly enabled
    private final Set<String> mEnabledProviders = new HashSet<String>();

    // Set of providers that are explicitly disabled
    private final Set<String> mDisabledProviders = new HashSet<String>();

    // Locations, status values, and extras for mock providers
    private final HashMap<String,MockProvider> mMockProviders = new HashMap<String,MockProvider>();

    private static boolean sProvidersLoaded = false;

    private final Context mContext;
    private GpsLocationProvider mGpsLocationProvider;
    private LocationProviderProxy mNetworkLocationProvider;
    private IGeocodeProvider mGeocodeProvider;
    private LocationWorkerHandler mLocationHandler;

    // Handler messages
    private static final int MESSAGE_LOCATION_CHANGED = 1;
    private static final int MESSAGE_RELEASE_WAKE_LOCK = 2;

    // Alarm manager and wakelock variables
    private final static String ALARM_INTENT = "com.android.location.ALARM_INTENT";
    private final static String WAKELOCK_KEY = "LocationManagerService";
    private AlarmManager mAlarmManager;
    private long mAlarmInterval = 0;
    private PowerManager.WakeLock mWakeLock = null;
    private long mWakeLockAcquireTime = 0;
    private boolean mWakeLockGpsReceived = true;
    private boolean mWakeLockNetworkReceived = true;
    
    /**
     * List of all receivers.
     */
    private final HashMap<Object, Receiver> mReceivers = new HashMap<Object, Receiver>();

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
    private Receiver mProximityListener = null;
    private HashMap<PendingIntent,ProximityAlert> mProximityAlerts =
        new HashMap<PendingIntent,ProximityAlert>();
    private HashSet<ProximityAlert> mProximitiesEntered =
        new HashSet<ProximityAlert>();

    // Last known location for each provider
    private HashMap<String,Location> mLastKnownLocation =
        new HashMap<String,Location>();

    // Location collector
    private ILocationCollector mCollector;

    private int mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;

    // for Settings change notification
    private ContentQueryMap mSettings;

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver implements IBinder.DeathRecipient {
        final ILocationListener mListener;
        final PendingIntent mPendingIntent;
        final Object mKey;
        final HashMap<String,UpdateRecord> mUpdateRecords = new HashMap<String,UpdateRecord>();

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
                    mListener.onStatusChanged(provider, status, extras);
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent statusChanged = new Intent();
                statusChanged.putExtras(extras);
                statusChanged.putExtra(LocationManager.KEY_STATUS_CHANGED, status);
                try {
                    mPendingIntent.send(mContext, 0, statusChanged, null, null);
                } catch (PendingIntent.CanceledException e) {
                    return false;
                }
            }
            return true;
        }

        public boolean callLocationChangedLocked(Location location) {
            if (mListener != null) {
                try {
                    mListener.onLocationChanged(location);
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent locationChanged = new Intent();
                locationChanged.putExtra(LocationManager.KEY_LOCATION_CHANGED, location);
                try {
                    mPendingIntent.send(mContext, 0, locationChanged, null, null);
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

    /**
     * Load providers from /data/location/<provider_name>/
     *                                                          class
     *                                                          kml
     *                                                          nmea
     *                                                          track
     *                                                          location
     *                                                          properties
     */
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
            mGpsLocationProvider = new GpsLocationProvider(mContext, this);
            LocationProviderImpl.addProvider(mGpsLocationProvider);
        }

        // Load fake providers if real providers are not available
        File f = new File(LocationManager.PROVIDER_DIR);
        if (f.isDirectory()) {
            File[] subdirs = f.listFiles();
            for (int i = 0; i < subdirs.length; i++) {
                if (!subdirs[i].isDirectory()) {
                    continue;
                }

                String name = subdirs[i].getName();

                if (LOCAL_LOGV) {
                    Log.v(TAG, "Found dir " + subdirs[i].getAbsolutePath());
                    Log.v(TAG, "name = " + name);
                }

                // Don't create a fake provider if a real provider exists
                if (LocationProviderImpl.getProvider(name) == null) {
                    LocationProviderImpl provider = null;
                    try {
                        File classFile = new File(subdirs[i], "class");
                        // Look for a 'class' file
                        provider = LocationProviderImpl.loadFromClass(classFile);

                        // Look for an 'kml', 'nmea', or 'track' file
                        if (provider == null) {
                            // Load properties from 'properties' file, if present
                            File propertiesFile = new File(subdirs[i], "properties");

                            if (propertiesFile.exists()) {
                                provider = new TrackProvider(name, this);
                                ((TrackProvider)provider).readProperties(propertiesFile);

                                File kmlFile = new File(subdirs[i], "kml");
                                if (kmlFile.exists()) {
                                    ((TrackProvider) provider).readKml(kmlFile);
                                } else {
                                    File nmeaFile = new File(subdirs[i], "nmea");
                                    if (nmeaFile.exists()) {
                                        ((TrackProvider) provider).readNmea(name, nmeaFile);
                                    } else {
                                        File trackFile = new File(subdirs[i], "track");
                                        if (trackFile.exists()) {
                                            ((TrackProvider) provider).readTrack(trackFile);
                                        }
                                    }
                                }
                            }
                        }
                        if (provider != null) {
                            LocationProviderImpl.addProvider(provider);
                        }
                        // Grab the initial location of a TrackProvider and
                        // store it as the last known location for that provider
                        if (provider instanceof TrackProvider) {
                            TrackProvider tp = (TrackProvider) provider;
                            mLastKnownLocation.put(tp.getName(), tp.getInitialLocation());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception loading provder " + name, e);
                    }
                }
            }
        }

        updateProvidersLocked();
    }

    /**
     * @param context the context that the LocationManagerService runs in
     */
    public LocationManagerService(Context context) {
        super();
        mContext = context;
        mLocationHandler = new LocationWorkerHandler();

        if (LOCAL_LOGV) {
            Log.v(TAG, "Constructed LocationManager Service");
        }

        // Alarm manager, needs to be done before calling loadProviders() below
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Create a wake lock, needs to be done before calling loadProviders() below
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        
        // Load providers
        loadProviders();

        // Register for Network (Wifi or Mobile) updates
        NetworkStateBroadcastReceiver networkReceiver = new NetworkStateBroadcastReceiver();
        IntentFilter networkIntentFilter = new IntentFilter();
        networkIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        networkIntentFilter.addAction(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION);
        context.registerReceiver(networkReceiver, networkIntentFilter);

        // Register for power updates
        PowerStateBroadcastReceiver powerStateReceiver = new PowerStateBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_INTENT);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        context.registerReceiver(powerStateReceiver, intentFilter);

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

    public void setNetworkLocationProvider(ILocationProvider provider) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                "Installing location providers outside of the system is not supported");
        }

        synchronized (mLock) {
            mNetworkLocationProvider =
                    new LocationProviderProxy(LocationManager.NETWORK_PROVIDER, this, provider);
            LocationProviderImpl.addProvider(mNetworkLocationProvider);
            updateProvidersLocked();
            
            // notify NetworkLocationProvider of any events it might have missed
            mNetworkLocationProvider.updateNetworkState(mNetworkState);
        }
    }

    public void setLocationCollector(ILocationCollector collector) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                "Installing location collectors outside of the system is not supported");
        }

        mCollector = collector;
    }

    public void setGeocodeProvider(IGeocodeProvider provider) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                "Installing location providers outside of the system is not supported");
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
            && (mContext.checkCallingPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)
            && (mContext.checkCallingPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            && (mContext.checkCallingPermission(ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                "Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission");
        }
    }

    private boolean isAllowedProviderSafe(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)
            && (mContext.checkCallingPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)
            && (mContext.checkCallingPermission(ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            && (mContext.checkCallingPermission(ACCESS_COARSE_LOCATION)
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
        List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
        ArrayList<String> out = new ArrayList<String>(providers.size());

        for (LocationProviderImpl p : providers) {
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
        List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
        ArrayList<String> out = new ArrayList<String>();

        for (LocationProviderImpl p : providers) {
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
        for (LocationProviderImpl p : LocationProviderImpl.getProviders()) {
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

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
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
                try {
                    Receiver receiver = record.mReceiver;
                    if (receiver.isListener()) {
                        if (enabled) {
                            receiver.getListener().onProviderEnabled(provider);
                        } else {
                            receiver.getListener().onProviderDisabled(provider);
                        }
                    } else {
                        Intent providerIntent = new Intent();
                        providerIntent.putExtra(LocationManager.KEY_PROVIDER_ENABLED, enabled);
                        try {
                            receiver.getPendingIntent().send(mContext, 0,
                                 providerIntent, null, null);
                        } catch (PendingIntent.CanceledException e) {
                            if (deadReceivers == null) {
                                deadReceivers = new ArrayList<Receiver>();
                                deadReceivers.add(receiver);
                            }
                        }
                    }
                } catch (RemoteException e) {
                    // The death link will clean this up.
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
                updateWakelockStatusLocked();
            }
        } else {
            p.enableLocationTracking(false);
            p.disable();
            updateWakelockStatusLocked();
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
        if (LocationManager.GPS_PROVIDER.equals(provider) ||
                LocationManager.NETWORK_PROVIDER.equals(provider)) {
            for (ProximityAlert alert : mProximityAlerts.values()) {
                if (alert.mUid == uid) {
                    return true;
                }
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

        LocationProviderImpl impl = LocationProviderImpl.getProvider(provider);
        if (impl == null) {
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
                impl.addListener(callingUid);
            }

            boolean isProviderEnabled = isAllowedBySettingsLocked(provider);
            if (isProviderEnabled) {
                long minTimeForProvider = getMinTimeLocked(provider);
                impl.setMinTime(minTimeForProvider);
                impl.enableLocationTracking(true);
                updateWakelockStatusLocked();
            } else {
                try {
                    // Notify the listener that updates are currently disabled
                    if (receiver.isListener()) {
                        receiver.getListener().onProviderDisabled(provider);
                    }
                } catch(RemoteException e) {
                    Log.w(TAG, "RemoteException calling onProviderDisabled on " +
                            receiver.getListener());
                }
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
                        LocationProviderImpl impl =
                                LocationProviderImpl.getProvider(record.mProvider);
                        if (impl != null) {
                            impl.removeListener(callingUid);
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

                LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
                if (p != null) {
                    if (hasOtherListener) {
                        p.setMinTime(getMinTimeLocked(provider));
                    } else {
                        p.enableLocationTracking(false);
                    }
                }
            }

            updateWakelockStatusLocked();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean addGpsStatusListener(IGpsStatusListener listener) {
        if (mGpsLocationProvider == null) {
            return false;
        }
        if (mContext.checkCallingPermission(ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }

        try {
            mGpsLocationProvider.addGpsStatusListener(listener);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException in addGpsStatusListener");
            return false;
        }
        return true;
    }

    public void removeGpsStatusListener(IGpsStatusListener listener) {
        synchronized (mLock) {
            mGpsLocationProvider.removeGpsStatusListener(listener);
        }
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        // first check for permission to the provider
        checkPermissionsSafe(provider);
        // and check for ACCESS_LOCATION_EXTRA_COMMANDS
        if ((mContext.checkCallingPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }

        synchronized (mLock) {
            LocationProviderImpl impl = LocationProviderImpl.getProvider(provider);
            if (provider == null) {
                return false;
            }
    
            return impl.sendExtraCommand(command, extras);
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
    class ProximityListener extends ILocationListener.Stub {

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
                            intent.send(mContext, 0, enteredIntent, null, null);
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
                            intent.send(mContext, 0, exitedIntent, null, null);
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

        if (mProximityListener == null) {
            mProximityListener = new Receiver(new ProximityListener());

            LocationProvider provider = LocationProviderImpl.getProvider(
                LocationManager.GPS_PROVIDER);
            if (provider != null) {
                requestLocationUpdatesLocked(provider.getName(), 1000L, 1.0f, mProximityListener);
            }

            provider =
                LocationProviderImpl.getProvider(LocationManager.NETWORK_PROVIDER);
            if (provider != null) {
                requestLocationUpdatesLocked(provider.getName(), 1000L, 1.0f, mProximityListener);
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
            removeUpdatesLocked(mProximityListener);
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
        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
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

    public void setLocation(Location location) {
        mLocationHandler.removeMessages(MESSAGE_LOCATION_CHANGED, location);
        Message m = Message.obtain(mLocationHandler, MESSAGE_LOCATION_CHANGED, location);
        mLocationHandler.sendMessageAtFrontOfQueue(m);
    }

    private boolean _isProviderEnabledLocked(String provider) {
        checkPermissionsSafe(provider);

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
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

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
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

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
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

        if (LocationManager.NETWORK_PROVIDER.equals(p.getName())) {
            mWakeLockNetworkReceived = true;
        } else if (p instanceof GpsLocationProvider) {
            // Gps location received signal is in NetworkStateBroadcastReceiver
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

                        if (mCollector != null && 
                                LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                            try {
                                mCollector.updateLocation(location);
                            } catch (RemoteException e) {
                                Log.w(TAG, "mCollector.updateLocation failed");
                            }
                        }

                        String provider = location.getProvider();
                        if (!isAllowedBySettingsLocked(provider)) {
                            return;
                        }

                        handleLocationChangedLocked(location);

                        if ((mWakeLockAcquireTime != 0) &&
                            (SystemClock.elapsedRealtime() - mWakeLockAcquireTime
                                > MAX_TIME_FOR_WAKE_LOCK)) {
    
                            removeMessages(MESSAGE_RELEASE_WAKE_LOCK);
    
                            log("LocationWorkerHandler: Exceeded max time for wake lock");
                            Message m = Message.obtain(this, MESSAGE_RELEASE_WAKE_LOCK);
                            sendMessageAtFrontOfQueue(m);
    
                        } else if (mWakeLockAcquireTime != 0 &&
                            mWakeLockGpsReceived && mWakeLockNetworkReceived) {
    
                            removeMessages(MESSAGE_RELEASE_WAKE_LOCK);
    
                            log("LocationWorkerHandler: Locations received.");
                            mWakeLockAcquireTime = 0;
                            Message m = Message.obtain(this, MESSAGE_RELEASE_WAKE_LOCK);
                            sendMessageDelayed(m, TIME_AFTER_WAKE_LOCK);
                        }
                    }
                } else if (msg.what == MESSAGE_RELEASE_WAKE_LOCK) {
                    log("LocationWorkerHandler: Release");

                    // Update wakelock status so the next alarm is set before releasing wakelock
                    synchronized (mLock) {
                        updateWakelockStatusLocked();
                        releaseWakeLockLocked();
                    }
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in LocationWorkerHandler.handleMessage:", e);
            }
        }
    }

    private class PowerStateBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(ALARM_INTENT)) {
                synchronized (mLock) {
                    log("PowerStateBroadcastReceiver: Alarm received");
                    // Have to do this immediately, rather than posting a
                    // message, so we execute our code while the system
                    // is holding a wake lock until the alarm broadcast
                    // is finished.
                    acquireWakeLockLocked();
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
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
            }
        }
    }

    private class NetworkStateBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (!noConnectivity) {
                    mNetworkState = LocationProvider.AVAILABLE;
                } else {
                    mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;
                }

                // Notify location providers of current network state
                synchronized (mLock) {
                    List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
                    for (LocationProviderImpl provider : providers) {
                        if (provider.requiresNetwork()) {
                            provider.updateNetworkState(mNetworkState);
                        }
                    }
                }
            } else if (action.equals(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION)) {

                final boolean enabled = intent.getBooleanExtra(GpsLocationProvider.EXTRA_ENABLED,
                    false);

                synchronized (mLock) {
                    if (!enabled) {
                        // When GPS is disabled, we are OK to release wake-lock
                        mWakeLockGpsReceived = true;
                    }
                }
            }

        }
    }

    // Wake locks

    private void updateWakelockStatusLocked() {
        log("updateWakelockStatus()");

        long callerId = Binder.clearCallingIdentity();
        
        boolean needsLock = false;
        long minTime = Integer.MAX_VALUE;

        if (mNetworkLocationProvider != null && mNetworkLocationProvider.isLocationTracking()) {
            needsLock = true;
            minTime = Math.min(mNetworkLocationProvider.getMinTime(), minTime);
        }

        if (mGpsLocationProvider != null && mGpsLocationProvider.isLocationTracking()) {
            needsLock = true;
            minTime = Math.min(mGpsLocationProvider.getMinTime(), minTime);
        }

        PendingIntent sender =
            PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_INTENT), 0);

        // Cancel existing alarm
        log("Cancelling existing alarm");
        mAlarmManager.cancel(sender);

        if (needsLock) {
            long now = SystemClock.elapsedRealtime();
            mAlarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, now + minTime, sender);
            mAlarmInterval = minTime;
            log("Creating a new wakelock alarm with minTime = " + minTime);
        } else {
            log("No need for alarm");
            mAlarmInterval = -1;

            // Clear out existing wakelocks
            mLocationHandler.removeMessages(MESSAGE_RELEASE_WAKE_LOCK);
            releaseWakeLockLocked();
        }
        Binder.restoreCallingIdentity(callerId);
    }

    private void acquireWakeLockLocked() {
        try {
            acquireWakeLockXLocked();
        } catch (Exception e) {
            // This is to catch a runtime exception thrown when we try to release an
            // already released lock.
            Log.e(TAG, "exception in acquireWakeLock()", e);
        }
    }

    private void acquireWakeLockXLocked() {
        if (mWakeLock.isHeld()) {
            log("Must release wakelock before acquiring");
            mWakeLockAcquireTime = 0;
            mWakeLock.release();
        }

        boolean networkActive = (mNetworkLocationProvider != null)
                && mNetworkLocationProvider.isLocationTracking();
        boolean gpsActive = (mGpsLocationProvider != null)
                && mGpsLocationProvider.isLocationTracking();

        boolean needsLock = networkActive || gpsActive;
        if (!needsLock) {
            log("No need for Lock!");
            return;
        }

        mWakeLockGpsReceived = !gpsActive;
        mWakeLockNetworkReceived = !networkActive;

        // Acquire wake lock
        mWakeLock.acquire();
        mWakeLockAcquireTime = SystemClock.elapsedRealtime();
        log("Acquired wakelock");

        if (mNetworkLocationProvider != null) {
            mNetworkLocationProvider.wakeLockAcquired();
        }
        if (mGpsLocationProvider != null) {
            mGpsLocationProvider.wakeLockAcquired();
        }
    }

    private void releaseWakeLockLocked() {
        try {
            releaseWakeLockXLocked();
        } catch (Exception e) {
            // This is to catch a runtime exception thrown when we try to release an
            // already released lock.
            Log.e(TAG, "exception in releaseWakeLock()", e);
        }
    }

    private void releaseWakeLockXLocked() {
        if (mNetworkLocationProvider != null) {
            mNetworkLocationProvider.wakeLockReleased();
        }
        if (mGpsLocationProvider != null) {
            mGpsLocationProvider.wakeLockReleased();
        }

        // Release wake lock
        mWakeLockAcquireTime = 0;
        if (mWakeLock.isHeld()) {
            log("Released wakelock");
            mWakeLock.release();
        } else {
            log("Can't release wakelock again!");
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
            if (LocationProviderImpl.getProvider(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }
            LocationProviderImpl.addProvider(provider);
            mMockProviders.put(name, provider);
            updateProvidersLocked();
        }
    }

    public void removeTestProvider(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLock) {
            MockProvider mockProvider = mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            LocationProviderImpl.removeProvider(mockProvider);
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
            mockProvider.setLocation(loc);
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
            pw.println("Permission Denial: can't dump AlarmManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        
        synchronized (mLock) {
            pw.println("Current Location Manager state:");
            pw.println("  sProvidersLoaded=" + sProvidersLoaded);
            pw.println("  mGpsLocationProvider=" + mGpsLocationProvider);
            pw.println("  mNetworkLocationProvider=" + mNetworkLocationProvider);
            pw.println("  mCollector=" + mCollector);
            pw.println("  mAlarmInterval=" + mAlarmInterval
                    + " mWakeLockAcquireTime=" + mWakeLockAcquireTime);
            pw.println("  mWakeLockGpsReceived=" + mWakeLockGpsReceived
                    + " mWakeLockNetworkReceived=" + mWakeLockNetworkReceived);
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
