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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.IGpsStatusListener;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationProviderImpl;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Config;
import android.util.Log;

import com.android.internal.location.CellState;
import com.android.internal.location.GpsLocationProvider;
import com.android.internal.location.LocationCollector;
import com.android.internal.location.LocationMasfClient;
import com.android.internal.location.NetworkLocationProvider;
import com.android.internal.location.TrackProvider;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 *
 * {@hide}
 */
public class LocationManagerService extends ILocationManager.Stub {
    private static final String TAG = "LocationManagerService";

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
    HashMap<String,MockProvider> mMockProviders = new HashMap<String,MockProvider>();
    private final HashMap<String,Location> mMockProviderLocation = new HashMap<String,Location>();
    private final HashMap<String,Integer> mMockProviderStatus = new HashMap<String,Integer>();
    private final HashMap<String,Bundle> mMockProviderStatusExtras = new HashMap<String,Bundle>();
    private final HashMap<String,Long> mMockProviderStatusUpdateTime = new HashMap<String,Long>();

    private static boolean sProvidersLoaded = false;

    private final Context mContext;
    private GpsLocationProvider mGpsLocationProvider;
    private NetworkLocationProvider mNetworkLocationProvider;
    private LocationWorkerHandler mLocationHandler;

    // Handler messages
    private static final int MESSAGE_HEARTBEAT = 1;
    private static final int MESSAGE_ACQUIRE_WAKE_LOCK = 2;
    private static final int MESSAGE_RELEASE_WAKE_LOCK = 3;

    // Alarm manager and wakelock variables
    private final static String ALARM_INTENT = "com.android.location.ALARM_INTENT";
    private final static String WAKELOCK_KEY = "LocationManagerService";
    private final static String WIFILOCK_KEY = "LocationManagerService";
    private AlarmManager mAlarmManager;
    private long mAlarmInterval = 0;
    private boolean mScreenOn = true;
    private PowerManager.WakeLock mWakeLock = null;
    private WifiManager.WifiLock mWifiLock = null;
    private long mWakeLockAcquireTime = 0;
    private boolean mWakeLockGpsReceived = true;
    private boolean mWakeLockNetworkReceived = true;
    private boolean mWifiWakeLockAcquired = false;
    private boolean mCellWakeLockAcquired = false;

    /**
     * Mapping from listener IBinder/PendingIntent to local Listener wrappers.
     */
    private final HashMap<Object,Receiver> mListeners =
        new HashMap<Object,Receiver>();

    /**
     * Mapping from listener IBinder/PendingIntent to a map from provider name to UpdateRecord.
     */
    private final HashMap<Object,HashMap<String,UpdateRecord>> mLocationListeners =
        new HashMap<Object,HashMap<String,UpdateRecord>>();

    /**
     * Mapping from listener IBinder/PendingIntent to a map from provider name to last broadcast
     * location.
     */
    private final HashMap<Object,HashMap<String,Location>> mLastFixBroadcast =
        new HashMap<Object,HashMap<String,Location>>();
    private final HashMap<Object,HashMap<String,Long>> mLastStatusBroadcast =
        new HashMap<Object,HashMap<String,Long>>();

    /**
     * Mapping from provider name to all its UpdateRecords
     */
    private final HashMap<String,HashSet<UpdateRecord>> mRecordsByProvider =
        new HashMap<String,HashSet<UpdateRecord>>();

    /**
     * Mappings from provider name to object to use for current location. Locations
     * contained in this list may not always be valid.
     */
    private final HashMap<String,Location> mLocationsByProvider =
        new HashMap<String,Location>();

    // Proximity listeners
    private Receiver mProximityListener = null;
    private HashMap<PendingIntent,ProximityAlert> mProximityAlerts =
        new HashMap<PendingIntent,ProximityAlert>();
    private HashSet<ProximityAlert> mProximitiesEntered =
        new HashSet<ProximityAlert>();

    // Last known location for each provider
    private HashMap<String,Location> mLastKnownLocation =
        new HashMap<String,Location>();

    // Battery status extras (from com.android.server.BatteryService)
    private static final String BATTERY_EXTRA_SCALE = "scale";
    private static final String BATTERY_EXTRA_LEVEL = "level";
    private static final String BATTERY_EXTRA_PLUGGED = "plugged";

    // Last known cell service state
    private TelephonyManager mTelephonyManager;
    private int mSignalStrength = -1;

    // Location collector
    private LocationCollector mCollector;

    // Location MASF service
    private LocationMasfClient mMasfClient;

    // Wifi Manager
    private WifiManager mWifiManager;

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver implements IBinder.DeathRecipient {
        final ILocationListener mListener;
        final PendingIntent mPendingIntent;

        Receiver(ILocationListener listener) {
            mListener = listener;
            mPendingIntent = null;
        }

        Receiver(PendingIntent intent) {
            mPendingIntent = intent;
            mListener = null;
        }

        public Object getKey() {
            if (mListener != null) {
                return mListener.asBinder();
            } else {
                return mPendingIntent;
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

        public void onStatusChanged(String provider, int status, Bundle extras)
        throws RemoteException {
            if (mListener != null) {
                mListener.onStatusChanged(provider, status, extras);
            } else {
                Intent statusChanged = new Intent();
                statusChanged.putExtras(extras);
                statusChanged.putExtra(LocationManager.KEY_STATUS_CHANGED, status);
                try {
                    mPendingIntent.send(mContext, 0, statusChanged, null, null);
                } catch (PendingIntent.CanceledException e) {
                    _removeUpdates(this);
                }
            }
        }

        public void onLocationChanged(Location location) throws RemoteException {
            if (mListener != null) {
                mListener.onLocationChanged(location);
            } else {
                Intent locationChanged = new Intent();
                locationChanged.putExtra(LocationManager.KEY_LOCATION_CHANGED, location);
                try {
                    mPendingIntent.send(mContext, 0, locationChanged, null, null);
                } catch (PendingIntent.CanceledException e) {
                    _removeUpdates(this);
                }
            }
        }

        public void binderDied() {
            if (Config.LOGD) {
                Log.d(TAG, "Location listener died");
            }
            synchronized (mLocationListeners) {
                _removeUpdates(this);
            }
        }
    }

    private Location readLastKnownLocation(String provider) {
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

    private void writeLastKnownLocation(String provider,
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
        synchronized (LocationManagerService.class) {
            if (sProvidersLoaded) {
                return;
            }

            // Load providers
            loadProvidersNoSync();
            sProvidersLoaded = true;
        }
    }

    private void loadProvidersNoSync() {
        try {
            _loadProvidersNoSync();
        } catch (Exception e) {
            Log.e(TAG, "Exception loading providers:", e);
        }
    }

    private void _loadProvidersNoSync() {
        // Attempt to load "real" providers first
        if (NetworkLocationProvider.isSupported()) {
            // Create a network location provider
            mNetworkLocationProvider = new NetworkLocationProvider(mContext, mMasfClient);
            LocationProviderImpl.addProvider(mNetworkLocationProvider);
        }

        if (GpsLocationProvider.isSupported()) {
            // Create a gps location provider
            mGpsLocationProvider = new GpsLocationProvider(mContext, mCollector);
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

                if (Config.LOGD) {
                    Log.d(TAG, "Found dir " + subdirs[i].getAbsolutePath());
                    Log.d(TAG, "name = " + name);
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
                                provider = new TrackProvider(name);
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

        updateProviders();
    }

    /**
     * @param context the context that the LocationManagerService runs in
     */
    public LocationManagerService(Context context) {
        super();
        mContext = context;
        mLocationHandler = new LocationWorkerHandler();

        if (Config.LOGD) {
            Log.d(TAG, "Constructed LocationManager Service");
        }

        // Initialize the LocationMasfClient
        mMasfClient = new LocationMasfClient(mContext);

        // Create location collector
        mCollector = new LocationCollector(mMasfClient);

        // Alarm manager, needs to be done before calling loadProviders() below
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Create a wake lock, needs to be done before calling loadProviders() below
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);

        // Load providers
        loadProviders();

        // Listen for Radio changes
        mTelephonyManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyManager.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_CELL_LOCATION |
                PhoneStateListener.LISTEN_SIGNAL_STRENGTH |
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);

        // Register for Network (Wifi or Mobile) updates
        NetworkStateBroadcastReceiver networkReceiver = new NetworkStateBroadcastReceiver();
        IntentFilter networkIntentFilter = new IntentFilter();
        networkIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        networkIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        networkIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        networkIntentFilter.addAction(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION);
        context.registerReceiver(networkReceiver, networkIntentFilter);

        // Register for power updates
        PowerStateBroadcastReceiver powerStateReceiver = new PowerStateBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_INTENT);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(powerStateReceiver, intentFilter);

        // Get the wifi manager
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        // Create a wifi lock for future use
        mWifiLock = getWifiWakelock();

        // There might be an existing wifi scan available
        if (mWifiManager != null) {
            List<ScanResult> wifiScanResults = mWifiManager.getScanResults();
            if (wifiScanResults != null && wifiScanResults.size() != 0) {
                if (mNetworkLocationProvider != null) {
                    mNetworkLocationProvider.updateWifiScanResults(wifiScanResults);
                }
            }
        }
    }

    private WifiManager.WifiLock getWifiWakelock() {
        if (mWifiLock == null && mWifiManager != null) {
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, WIFILOCK_KEY);
            mWifiLock.setReferenceCounted(false);
        }
        return mWifiLock;
    }

    private boolean isAllowedBySettings(String provider) {
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

    private void checkPermissions(String provider) {
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

    private boolean isAllowedProvider(String provider) {
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

    private String[] getPackageNames() {
        // Since a single UID may correspond to multiple packages, this can only be used as an
        // approximation for tracking
        return mContext.getPackageManager().getPackagesForUid(Binder.getCallingUid());
    }

    public List<String> getAllProviders() {
        try {
            return _getAllProviders();
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getAllProviders got exception:", e);
            return null;
        }
    }

    private List<String> _getAllProviders() {
        if (Config.LOGD) {
            Log.d(TAG, "getAllProviders");
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
            return _getProviders(enabledOnly);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getProviders gotString exception:", e);
            return null;
        }
    }

    private List<String> _getProviders(boolean enabledOnly) {
        if (Config.LOGD) {
            Log.d(TAG, "getProviders");
        }
        List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
        ArrayList<String> out = new ArrayList<String>();

        for (LocationProviderImpl p : providers) {
            String name = p.getName();
            if (isAllowedProvider(name)) {
                if (enabledOnly && !isAllowedBySettings(name)) {
                    continue;
                }
                out.add(name);
            }
        }
        return out;
    }

    public void updateProviders() {
        for (LocationProviderImpl p : LocationProviderImpl.getProviders()) {
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedBySettings(name);

            // Collection is only allowed when network provider is being used
            if (p.getName().equals(LocationManager.NETWORK_PROVIDER)) {
                mCollector.updateNetworkProviderStatus(shouldBeEnabled);
            }

            if (isEnabled && !shouldBeEnabled) {
                updateProviderListeners(name, false);
            } else if (!isEnabled && shouldBeEnabled) {
                updateProviderListeners(name, true);
            }

        }
    }

    private void updateProviderListeners(String provider, boolean enabled) {
        int listeners = 0;

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            return;
        }

        synchronized (mRecordsByProvider) {
            HashSet<UpdateRecord> records = mRecordsByProvider.get(provider);
            if (records != null) {
                for (UpdateRecord record : records) {
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
                            PendingIntent intent = receiver.getPendingIntent();
                            Intent providerIntent = new Intent();
                            providerIntent.putExtra(LocationManager.KEY_PROVIDER_ENABLED, enabled);
                            try {
                                receiver.getPendingIntent().send(mContext, 0,
                                     providerIntent, null, null);
                            } catch (PendingIntent.CanceledException e) {
                                _removeUpdates(receiver);
                            }
                        }
                    } catch (RemoteException e) {
                        // The death link will clean this up.
                    }
                    listeners++;
                }
            }
        }

        if (enabled) {
            p.enable();
            if (listeners > 0) {
                p.setMinTime(getMinTime(provider));
                p.enableLocationTracking(true);
                updateWakelockStatus(mScreenOn);
            }
        } else {
            p.enableLocationTracking(false);
            p.disable();
            updateWakelockStatus(mScreenOn);
        }

        if (enabled && listeners > 0) {
            mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
            Message m = Message.obtain(mLocationHandler, MESSAGE_HEARTBEAT, provider);
            mLocationHandler.sendMessageAtTime(m, SystemClock.uptimeMillis() + 1000);
        } else {
            mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
        }
    }

    private long getMinTime(String provider) {
        long minTime = Long.MAX_VALUE;
        synchronized (mRecordsByProvider) {
            HashSet<UpdateRecord> records = mRecordsByProvider.get(provider);
            if (records != null) {
                for (UpdateRecord r : records) {
                    minTime = Math.min(minTime, r.mMinTime);
                }
            }
        }
        return minTime;
    }

    private class UpdateRecord {
        String mProvider;
        Receiver mReceiver;
        long mMinTime;
        float mMinDistance;
        String[] mPackages;

        UpdateRecord(String provider, long minTime, float minDistance,
            Receiver receiver, String[] packages) {
            mProvider = provider;
            mReceiver = receiver;
            mMinTime = minTime;
            mMinDistance = minDistance;
            mPackages = packages;

            synchronized (mRecordsByProvider) {
                HashSet<UpdateRecord> records = mRecordsByProvider.get(provider);
                if (records == null) {
                    records = new HashSet<UpdateRecord>();
                    mRecordsByProvider.put(provider, records);
                }
                records.add(this);
            }
        }

        /**
         * Method to be called when a record will no longer be used.  Calling this multiple times
         * must have the same effect as calling it once.
         */
        public void dispose() {
            synchronized (mRecordsByProvider) {
                HashSet<UpdateRecord> records = mRecordsByProvider.get(this.mProvider);
                records.remove(this);
            }
        }

        /**
         * Calls dispose().
         */
        @Override protected void finalize() {
            dispose();
        }
    }

    public void requestLocationUpdates(String provider,
        long minTime, float minDistance, ILocationListener listener) {

        try {
            _requestLocationUpdates(provider, minTime, minDistance,
                new Receiver(listener));
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "requestUpdates got exception:", e);
        }
    }

    public void requestLocationUpdatesPI(String provider,
            long minTime, float minDistance, PendingIntent intent) {
        try {
            _requestLocationUpdates(provider, minTime, minDistance,
                    new Receiver(intent));
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "requestUpdates got exception:", e);
        }
    }

    private void _requestLocationUpdates(String provider,
            long minTime, float minDistance, Receiver receiver) {
        Object key = receiver.getKey();
        if (Config.LOGD) {
            Log.d(TAG, "_requestLocationUpdates: listener = " + key);
        }

        LocationProviderImpl impl = LocationProviderImpl.getProvider(provider);
        if (impl == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        checkPermissions(provider);

        String[] packages = getPackageNames();

        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            UpdateRecord r = new UpdateRecord(provider, minTime, minDistance, receiver, packages);
            synchronized (mLocationListeners) {
                if (mListeners.get(key) == null) {
                    try {
                        if (receiver.isListener()) {
                            receiver.getListener().asBinder().linkToDeath(receiver, 0);
                        }
                        mListeners.put(key, receiver);
                    } catch (RemoteException e) {
                        return;
                    }
                }

                HashMap<String,UpdateRecord> records = mLocationListeners.get(key);
                if (records == null) {
                    records = new HashMap<String,UpdateRecord>();
                    mLocationListeners.put(key, records);
                }
                UpdateRecord oldRecord = records.put(provider, r);
                if (oldRecord != null) {
                    oldRecord.dispose();
                }

                if (impl instanceof NetworkLocationProvider) {
                    ((NetworkLocationProvider) impl).addListener(packages);
                }

                boolean isProviderEnabled = isAllowedBySettings(provider);
                if (isProviderEnabled) {
                    long minTimeForProvider = getMinTime(provider);
                    impl.setMinTime(minTimeForProvider);
                    impl.enableLocationTracking(true);
                    updateWakelockStatus(mScreenOn);

                    // Clear heartbeats if any before starting a new one
                    mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
                    Message m = Message.obtain(mLocationHandler, MESSAGE_HEARTBEAT, provider);
                    mLocationHandler.sendMessageAtTime(m, SystemClock.uptimeMillis() + 1000);

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
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeUpdates(ILocationListener listener) {
        try {
            _removeUpdates(new Receiver(listener));
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    public void removeUpdatesPI(PendingIntent intent) {
        try {
            _removeUpdates(new Receiver(intent));
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    private void _removeUpdates(Receiver receiver) {
        Object key = receiver.getKey();
        if (Config.LOGD) {
            Log.d(TAG, "_removeUpdates: listener = " + key);
        }

        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLocationListeners) {
                Receiver myReceiver = mListeners.remove(key);
                if ((myReceiver != null) && (myReceiver.isListener())) {
                    myReceiver.getListener().asBinder().unlinkToDeath(myReceiver, 0);
                }

                // Record which providers were associated with this listener
                HashSet<String> providers = new HashSet<String>();
                HashMap<String,UpdateRecord> oldRecords = mLocationListeners.get(key);
                if (oldRecords != null) {
                    // Call dispose() on the obsolete update records.
                    for (UpdateRecord record : oldRecords.values()) {
                        if (record.mProvider.equals(LocationManager.NETWORK_PROVIDER)) {
                            if (mNetworkLocationProvider != null) {
                                mNetworkLocationProvider.removeListener(record.mPackages);
                            }
                        }
                        record.dispose();
                    }
                    // Accumulate providers
                    providers.addAll(oldRecords.keySet());
                }

                mLocationListeners.remove(key);
                mLastFixBroadcast.remove(key);
                mLastStatusBroadcast.remove(key);

                // See if the providers associated with this listener have any
                // other listeners; if one does, inform it of the new smallest minTime
                // value; if one does not, disable location tracking for it
                for (String provider : providers) {
                    // If provider is already disabled, don't need to do anything
                    if (!isAllowedBySettings(provider)) {
                        continue;
                    }

                    boolean hasOtherListener = false;
                    synchronized (mRecordsByProvider) {
                        HashSet<UpdateRecord> recordsForProvider = mRecordsByProvider.get(provider);
                        if (recordsForProvider != null && recordsForProvider.size() > 0) {
                            hasOtherListener = true;
                        }
                    }

                    LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
                    if (p != null) {
                        if (hasOtherListener) {
                            p.setMinTime(getMinTime(provider));
                        } else {
                            mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
                            p.enableLocationTracking(false);
                        }
                    }
                }

                updateWakelockStatus(mScreenOn);
            }
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
        mGpsLocationProvider.removeGpsStatusListener(listener);
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        // first check for permission to the provider
        checkPermissions(provider);
        // and check for ACCESS_LOCATION_EXTRA_COMMANDS
        if ((mContext.checkCallingPermission(ACCESS_LOCATION_EXTRA_COMMANDS)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }

        LocationProviderImpl impl = LocationProviderImpl.getProvider(provider);
        if (provider == null) {
            return false;
        }

        return impl.sendExtraCommand(command, extras);
    }

    class ProximityAlert {
        double mLatitude;
        double mLongitude;
        float mRadius;
        long mExpiration;
        PendingIntent mIntent;
        Location mLocation;

        public ProximityAlert(double latitude, double longitude,
            float radius, long expiration, PendingIntent intent) {
            mLatitude = latitude;
            mLongitude = longitude;
            mRadius = radius;
            mExpiration = expiration;
            mIntent = intent;

            mLocation = new Location("");
            mLocation.setLatitude(latitude);
            mLocation.setLongitude(longitude);
        }

        public long getExpiration() {
            return mExpiration;
        }

        public PendingIntent getIntent() {
            return mIntent;
        }

        public boolean isInProximity(double latitude, double longitude) {
            Location loc = new Location("");
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);

            double radius = loc.distanceTo(mLocation);
            return radius <= mRadius;
        }
    }

    // Listener for receiving locations to trigger proximity alerts
    class ProximityListener extends ILocationListener.Stub {

        boolean isGpsAvailable = false;

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
                        if (Config.LOGD) {
                            Log.i(TAG, "Entered alert");
                        }
                        mProximitiesEntered.add(alert);
                        Intent enteredIntent = new Intent();
                        enteredIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
                        try {
                            intent.send(mContext, 0, enteredIntent, null, null);
                        } catch (PendingIntent.CanceledException e) {
                            if (Config.LOGD) {
                                Log.i(TAG, "Canceled proximity alert: " + alert, e);
                            }
                            if (intentsToRemove == null) {
                                intentsToRemove = new ArrayList<PendingIntent>();
                            }
                            intentsToRemove.add(intent);
                        }
                    } else if (entered && !inProximity) {
                        if (Config.LOGD) {
                            Log.i(TAG, "Exited alert");
                        }
                        mProximitiesEntered.remove(alert);
                        Intent exitedIntent = new Intent();
                        exitedIntent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
                        try {
                            intent.send(mContext, 0, exitedIntent, null, null);
                        } catch (PendingIntent.CanceledException e) {
                            if (Config.LOGD) {
                                Log.i(TAG, "Canceled proximity alert: " + alert, e);
                            }
                            if (intentsToRemove == null) {
                                intentsToRemove = new ArrayList<PendingIntent>();
                            }
                            intentsToRemove.add(intent);
                        }
                    }
                } else {
                    // Mark alert for expiration
                    if (Config.LOGD) {
                        Log.i(TAG, "Expiring proximity alert: " + alert);
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

        public void onProviderDisabled(String provider) {
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                isGpsAvailable = false;
            }
        }

        public void onProviderEnabled(String provider) {
            // ignore
        }

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
          _addProximityAlert(latitude, longitude, radius, expiration, intent);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "addProximityAlert got exception:", e);
        }
    }

    private void _addProximityAlert(double latitude, double longitude,
        float radius, long expiration, PendingIntent intent) {
        if (Config.LOGD) {
            Log.d(TAG, "addProximityAlert: latitude = " + latitude +
                    ", longitude = " + longitude +
                    ", expiration = " + expiration +
                    ", intent = " + intent);
        }

        // Require ability to access all providers for now
        if (!isAllowedProvider(LocationManager.GPS_PROVIDER) ||
            !isAllowedProvider(LocationManager.NETWORK_PROVIDER)) {
            throw new SecurityException("Requires ACCESS_FINE_LOCATION permission");
        }

        if (expiration != -1) {
            expiration += System.currentTimeMillis();
        }
        ProximityAlert alert = new ProximityAlert(latitude, longitude, radius, expiration, intent);
        mProximityAlerts.put(intent, alert);

        if (mProximityListener == null) {
            mProximityListener = new Receiver(new ProximityListener());

            LocationProvider provider = LocationProviderImpl.getProvider(
                LocationManager.GPS_PROVIDER);
            if (provider != null) {
                _requestLocationUpdates(provider.getName(), 1000L, 1.0f, mProximityListener);
            }

            provider =
                LocationProviderImpl.getProvider(LocationManager.NETWORK_PROVIDER);
            if (provider != null) {
                _requestLocationUpdates(provider.getName(), 1000L, 1.0f, mProximityListener);
            }
        }
    }

    public void removeProximityAlert(PendingIntent intent) {
        try {
           _removeProximityAlert(intent);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeProximityAlert got exception:", e);
        }
    }

    private void _removeProximityAlert(PendingIntent intent) {
        if (Config.LOGD) {
            Log.d(TAG, "removeProximityAlert: intent = " + intent);
        }

        mProximityAlerts.remove(intent);
        if (mProximityAlerts.size() == 0) {
            _removeUpdates(mProximityListener);
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
            return _getProviderInfo(provider);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "_getProviderInfo got exception:", e);
            return null;
        }
    }

    private Bundle _getProviderInfo(String provider) {
        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            return null;
        }

        checkPermissions(provider);

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
            return _isProviderEnabled(provider);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "isProviderEnabled got exception:", e);
            return false;
        }
    }

    private boolean _isProviderEnabled(String provider) {
        checkPermissions(provider);

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }
        return isAllowedBySettings(provider);
    }

    public Location getLastKnownLocation(String provider) {
        try {
            return _getLastKnownLocation(provider);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getLastKnownLocation got exception:", e);
            return null;
        }
    }

    private Location _getLastKnownLocation(String provider) {
        checkPermissions(provider);

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        if (!isAllowedBySettings(provider)) {
            return null;
        }

        Location location = mLastKnownLocation.get(provider);
        if (location == null) {
            // Get the persistent last known location for the provider
            location = readLastKnownLocation(provider);
            if (location != null) {
                mLastKnownLocation.put(provider, location);
            }
        }

        return location;
    }

    private boolean shouldBroadcast(Location loc, Location lastLoc, UpdateRecord record) {
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

    private void handleLocationChanged(String provider) {
        HashSet<UpdateRecord> records = mRecordsByProvider.get(provider);
        if (records == null || records.size() == 0) {
            return;
        }

        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            return;
        }

        // Get location object
        Location loc = mLocationsByProvider.get(provider);
        if (loc == null) {
            loc = new Location(provider);
            mLocationsByProvider.put(provider, loc);
        } else {
            loc.reset();
        }

        // Use the mock location if available
        Location mockLoc = mMockProviderLocation.get(provider);
        boolean locationValid;
        if (mockLoc != null) {
            locationValid = true;
            loc.set(mockLoc);
        } else {
            locationValid = p.getLocation(loc);
        }

        // Update last known location for provider
        if (locationValid) {
            Location location = mLastKnownLocation.get(provider);
            if (location == null) {
                mLastKnownLocation.put(provider, new Location(loc));
            } else {
                location.set(loc);
            }
            writeLastKnownLocation(provider, loc);

            if (p instanceof NetworkLocationProvider) {
                mWakeLockNetworkReceived = true;
            } else if (p instanceof GpsLocationProvider) {
                // Gps location received signal is in NetworkStateBroadcastReceiver
            }
        }

        // Fetch latest status update time
        long newStatusUpdateTime = p.getStatusUpdateTime();

        // Override real time with mock time if present
        Long mockStatusUpdateTime = mMockProviderStatusUpdateTime.get(provider);
        if (mockStatusUpdateTime != null) {
            newStatusUpdateTime = mockStatusUpdateTime.longValue();
        }

        // Get latest status
        Bundle extras = new Bundle();
        int status = p.getStatus(extras);

        // Override status with mock status if present
        Integer mockStatus = mMockProviderStatus.get(provider);
        if (mockStatus != null) {
            status = mockStatus.intValue();
        }

        // Override extras with mock extras if present
        Bundle mockExtras = mMockProviderStatusExtras.get(provider);
        if (mockExtras != null) {
            extras.clear();
            extras.putAll(mockExtras);
        }

        // Broadcast location or status to all listeners
        for (UpdateRecord r : records) {
            Receiver receiver = r.mReceiver;
            Object key = receiver.getKey();

            // Broadcast location only if it is valid
            if (locationValid) {
                HashMap<String,Location> map = mLastFixBroadcast.get(key);
                if (map == null) {
                    map = new HashMap<String,Location>();
                    mLastFixBroadcast.put(key, map);
                }
                Location lastLoc = map.get(provider);
                if ((lastLoc == null) || shouldBroadcast(loc, lastLoc, r)) {
                    if (lastLoc == null) {
                        lastLoc = new Location(loc);
                        map.put(provider, lastLoc);
                    } else {
                        lastLoc.set(loc);
                    }
                    try {
                        receiver.onLocationChanged(loc);
                    } catch (RemoteException doe) {
                        Log.w(TAG, "RemoteException calling onLocationChanged on " + receiver);
                        _removeUpdates(receiver);
                    }
                }
            }

            // Broadcast status message
            HashMap<String,Long> statusMap = mLastStatusBroadcast.get(key);
            if (statusMap == null) {
                statusMap = new HashMap<String,Long>();
                mLastStatusBroadcast.put(key, statusMap);
            }
            long prevStatusUpdateTime =
                (statusMap.get(provider) != null) ? statusMap.get(provider) : 0;

            if ((newStatusUpdateTime > prevStatusUpdateTime) &&
                (prevStatusUpdateTime != 0 || status != LocationProvider.AVAILABLE)) {

                statusMap.put(provider, newStatusUpdateTime);
                try {
                    receiver.onStatusChanged(provider, status, extras);
                } catch (RemoteException doe) {
                    Log.w(TAG, "RemoteException calling onStatusChanged on " + receiver);
                    _removeUpdates(receiver);
                }
            }
        }
    }

    private class LocationWorkerHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MESSAGE_HEARTBEAT) {
                    // log("LocationWorkerHandler: Heartbeat!");

                    synchronized (mRecordsByProvider) {
                        String provider = (String) msg.obj;
                        if (!isAllowedBySettings(provider)) {
                            return;
                        }

                        // Process the location fix if the screen is on or we're holding a wakelock
                        if (mScreenOn || (mWakeLockAcquireTime != 0)) {
                            handleLocationChanged(provider);
                        }

                        // If it continues to have listeners
                        HashSet<UpdateRecord> records = mRecordsByProvider.get(provider);
                        if (records != null && records.size() > 0) {
                            Message m = Message.obtain(this, MESSAGE_HEARTBEAT, provider);
                            sendMessageAtTime(m, SystemClock.uptimeMillis() + 1000);
                        }
                    }

                    if ((mWakeLockAcquireTime != 0) &&
                        (SystemClock.elapsedRealtime() - mWakeLockAcquireTime
                            > MAX_TIME_FOR_WAKE_LOCK)) {

                        removeMessages(MESSAGE_ACQUIRE_WAKE_LOCK);
                        removeMessages(MESSAGE_RELEASE_WAKE_LOCK);

                        log("LocationWorkerHandler: Exceeded max time for wake lock");
                        Message m = Message.obtain(this, MESSAGE_RELEASE_WAKE_LOCK);
                        sendMessageAtFrontOfQueue(m);

                    } else if (mWakeLockAcquireTime != 0 &&
                        mWakeLockGpsReceived && mWakeLockNetworkReceived) {

                        removeMessages(MESSAGE_ACQUIRE_WAKE_LOCK);
                        removeMessages(MESSAGE_RELEASE_WAKE_LOCK);

                        log("LocationWorkerHandler: Locations received.");
                        mWakeLockAcquireTime = 0;
                        Message m = Message.obtain(this, MESSAGE_RELEASE_WAKE_LOCK);
                        sendMessageDelayed(m, TIME_AFTER_WAKE_LOCK);
                    }

                } else if (msg.what == MESSAGE_ACQUIRE_WAKE_LOCK) {
                    log("LocationWorkerHandler: Acquire");
                    acquireWakeLock();
                } else if (msg.what == MESSAGE_RELEASE_WAKE_LOCK) {
                    log("LocationWorkerHandler: Release");

                    // Update wakelock status so the next alarm is set before releasing wakelock
                    updateWakelockStatus(mScreenOn);
                    releaseWakeLock();
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in LocationWorkerHandler.handleMessage:", e);
            }
        }
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {

        private CellState mLastCellState = null;
        @Override
        public void onCellLocationChanged(CellLocation cellLocation) {
            try {
                int asu = mSignalStrength;

                // Gets cell state
                mLastCellState = new CellState(mTelephonyManager, cellLocation, asu);

                // Notify collector
                mCollector.updateCellState(mLastCellState);

                // Updates providers
                List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
                for (LocationProviderImpl provider : providers) {
                    if (provider.requiresCell()) {
                        provider.updateCellState(mLastCellState);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in PhoneStateListener.onCellLocationCahnged:", e);
            }
        }

        @Override
        public void onSignalStrengthChanged(int asu) {
            mSignalStrength = asu;

            if (mLastCellState != null) {
                mLastCellState.updateSignalStrength(asu);
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state) {
            if (mLastCellState != null) {
                mLastCellState.updateRadioType(mTelephonyManager);
            }
        }
    };

    private class PowerStateBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(ALARM_INTENT)) {
                mLocationHandler.removeMessages(MESSAGE_ACQUIRE_WAKE_LOCK);
                mLocationHandler.removeMessages(MESSAGE_RELEASE_WAKE_LOCK);

                log("PowerStateBroadcastReceiver: Alarm received");
                Message m = mLocationHandler.obtainMessage(MESSAGE_ACQUIRE_WAKE_LOCK);
                mLocationHandler.sendMessageAtFrontOfQueue(m);

            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                log("PowerStateBroadcastReceiver: Screen off");
                updateWakelockStatus(false);

            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                log("PowerStateBroadcastReceiver: Screen on");
                updateWakelockStatus(true);

            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                log("PowerStateBroadcastReceiver: Battery changed");
                int scale = intent.getIntExtra(BATTERY_EXTRA_SCALE, 100);
                int level = intent.getIntExtra(BATTERY_EXTRA_LEVEL, 0);
                boolean plugged = intent.getIntExtra(BATTERY_EXTRA_PLUGGED, 0) != 0;

                // Notify collector battery state
                mCollector.updateBatteryState(scale, level, plugged);
            }
        }
    }

    private class NetworkStateBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {

                List<ScanResult> wifiScanResults = mWifiManager.getScanResults();

                if (wifiScanResults == null) {
                    return;
                }

                // Notify provider and collector of Wifi scan results
                mCollector.updateWifiScanResults(wifiScanResults);
                if (mNetworkLocationProvider != null) {
                    mNetworkLocationProvider.updateWifiScanResults(wifiScanResults);
                }

            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                int networkState = LocationProvider.TEMPORARILY_UNAVAILABLE;

                boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (!noConnectivity) {
                    networkState = LocationProvider.AVAILABLE;
                }

                // Notify location providers of current network state
                List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
                for (LocationProviderImpl provider : providers) {
                    if (provider.requiresNetwork()) {
                        provider.updateNetworkState(networkState);
                    }
                }

            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN);

                boolean enabled;
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    enabled = true;
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    enabled = false;
                } else {
                    return;
                }

                // Notify network provider of current wifi enabled state
                if (mNetworkLocationProvider != null) {
                    mNetworkLocationProvider.updateWifiEnabledState(enabled);
                }

            } else if (action.equals(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION)) {

                final boolean enabled = intent.getBooleanExtra(GpsLocationProvider.EXTRA_ENABLED,
                    false);

                if (!enabled) {
                    // When GPS is disabled, we are OK to release wake-lock
                    mWakeLockGpsReceived = true;
                }
            }

        }
    }

    // Wake locks

    private void updateWakelockStatus(boolean screenOn) {
        log("updateWakelockStatus(): " + screenOn);

        boolean needsLock = false;
        long minTime = Integer.MAX_VALUE;

        if (mNetworkLocationProvider != null && mNetworkLocationProvider.isLocationTracking()) {
            needsLock = true;
            minTime = Math.min(mNetworkLocationProvider.getMinTime(), minTime);
        }

        if (mGpsLocationProvider != null && mGpsLocationProvider.isLocationTracking()) {
            needsLock = true;
            minTime = Math.min(mGpsLocationProvider.getMinTime(), minTime);
            if (screenOn) {
                startGps();
            } else if (mScreenOn && !screenOn) {

                // We just turned the screen off so stop navigating
                stopGps();
            }
        }

        mScreenOn = screenOn;

        PendingIntent sender =
            PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_INTENT), 0);

        // Cancel existing alarm
        log("Cancelling existing alarm");
        mAlarmManager.cancel(sender);

        if (needsLock && !mScreenOn) {
            long now = SystemClock.elapsedRealtime();
            mAlarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, now + minTime, sender);
            mAlarmInterval = minTime;
            log("Creating a new wakelock alarm with minTime = " + minTime);
        } else {
            log("No need for alarm");
            mAlarmInterval = -1;

            // Clear out existing wakelocks
            mLocationHandler.removeMessages(MESSAGE_ACQUIRE_WAKE_LOCK);
            mLocationHandler.removeMessages(MESSAGE_RELEASE_WAKE_LOCK);
            releaseWakeLock();
        }
    }

    private void acquireWakeLock() {
        try {
            acquireWakeLockX();
        } catch (Exception e) {
            // This is to catch a runtime exception thrown when we try to release an
            // already released lock.
            Log.e(TAG, "exception in acquireWakeLock()", e);
        }
    }

    private void acquireWakeLockX() {
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

        // Start the gps provider
        startGps();

        // Acquire cell lock
        if (mCellWakeLockAcquired) {
            // Lock is already acquired
        } else if (!mWakeLockNetworkReceived) {
            mTelephonyManager.enableLocationUpdates();
            mCellWakeLockAcquired = true;
        } else {
            mCellWakeLockAcquired = false;
        }

        // Notify NetworkLocationProvider
        if (mNetworkLocationProvider != null) {
            mNetworkLocationProvider.updateCellLockStatus(mCellWakeLockAcquired);
        }

        // Acquire wifi lock
        WifiManager.WifiLock wifiLock = getWifiWakelock();
        if (wifiLock != null) {
            if (mWifiWakeLockAcquired) {
                // Lock is already acquired
            } else if (mWifiManager.isWifiEnabled() && !mWakeLockNetworkReceived) {
                wifiLock.acquire();
                mWifiWakeLockAcquired = true;
            } else {
                mWifiWakeLockAcquired = false;
                Log.w(TAG, "acquireWakeLock(): Unable to get WiFi lock");
            }
        }
    }

    private void startGps() {
        boolean gpsActive = (mGpsLocationProvider != null)
                    && mGpsLocationProvider.isLocationTracking();
        if (gpsActive) {
            mGpsLocationProvider.startNavigating();
        }
    }

    private void stopGps() {
        boolean gpsActive = mGpsLocationProvider != null
                    && mGpsLocationProvider.isLocationTracking();
        if (gpsActive) {
            mGpsLocationProvider.stopNavigating();
        }
    }

    private void releaseWakeLock() {
        try {
            releaseWakeLockX();
        } catch (Exception e) {
            // This is to catch a runtime exception thrown when we try to release an
            // already released lock.
            Log.e(TAG, "exception in releaseWakeLock()", e);
        }
    }

    private void releaseWakeLockX() {
        // Release wifi lock
        WifiManager.WifiLock wifiLock = getWifiWakelock();
        if (wifiLock != null) {
            if (mWifiWakeLockAcquired) {
                wifiLock.release();
                mWifiWakeLockAcquired = false;
            }
        }

        if (!mScreenOn) {

            // Stop the gps
            stopGps();
        }

        // Release cell lock
        if (mCellWakeLockAcquired) {
            mTelephonyManager.disableLocationUpdates();
            mCellWakeLockAcquired = false;
        }

        // Notify NetworkLocationProvider
        if (mNetworkLocationProvider != null) {
            mNetworkLocationProvider.updateCellLockStatus(mCellWakeLockAcquired);
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
        try {
            Locale locale = new Locale(language, country, variant);
            mMasfClient.reverseGeocode(locale, appName, latitude, longitude, maxResults, addrs);
            return null;
        } catch(IOException e) {
            return e.getMessage();
        } catch(Exception e) {
            Log.e(TAG, "getFromLocation got exception:", e);
            return null;
        }
    }

    public String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        String language, String country, String variant, String appName, List<Address> addrs) {

        try {
            Locale locale = new Locale(language, country, variant);
            mMasfClient.forwardGeocode(locale, appName, locationName,
                lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                maxResults, addrs);
            return null;
        } catch(IOException e) {
            return e.getMessage();
        } catch(Exception e) {
            Log.e(TAG, "getFromLocationName got exception:", e);
            return null;
        }
    }

    // Mock Providers

    class MockProvider extends LocationProviderImpl {
        boolean mRequiresNetwork;
        boolean mRequiresSatellite;
        boolean mRequiresCell;
        boolean mHasMonetaryCost;
        boolean mSupportsAltitude;
        boolean mSupportsSpeed;
        boolean mSupportsBearing;
        int mPowerRequirement;
        int mAccuracy;

        public MockProvider(String name,  boolean requiresNetwork, boolean requiresSatellite,
            boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
            boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
            super(name);

            mRequiresNetwork = requiresNetwork;
            mRequiresSatellite = requiresSatellite;
            mRequiresCell = requiresCell;
            mHasMonetaryCost = hasMonetaryCost;
            mSupportsAltitude = supportsAltitude;
            mSupportsBearing = supportsBearing;
            mSupportsSpeed = supportsSpeed;
            mPowerRequirement = powerRequirement;
            mAccuracy = accuracy;
        }

        @Override
        public void disable() {
            String name = getName();
            mEnabledProviders.remove(name);
            mDisabledProviders.add(name);
        }

        @Override
        public void enable() {
            String name = getName();
            mEnabledProviders.add(name);
            mDisabledProviders.remove(name);
        }

        @Override
        public boolean getLocation(Location l) {
            Location loc = mMockProviderLocation.get(getName());
            if (loc == null) {
                return false;
            }
            l.set(loc);
            return true;
        }

        @Override
        public int getStatus(Bundle extras) {
            String name = getName();
            Integer s = mMockProviderStatus.get(name);
            int status = (s == null) ? AVAILABLE : s.intValue();
            Bundle newExtras = mMockProviderStatusExtras.get(name);
            if (newExtras != null) {
                extras.clear();
                extras.putAll(newExtras);
            }
            return status;
        }

        @Override
        public boolean isEnabled() {
            return mEnabledProviders.contains(getName());
        }

        @Override
        public int getAccuracy() {
            return mAccuracy;
        }

        @Override
        public int getPowerRequirement() {
            return mPowerRequirement;
        }

        @Override
        public boolean hasMonetaryCost() {
            return mHasMonetaryCost;
        }

        @Override
        public boolean requiresCell() {
            return mRequiresCell;
        }

        @Override
        public boolean requiresNetwork() {
            return mRequiresNetwork;
        }

        @Override
        public boolean requiresSatellite() {
            return mRequiresSatellite;
        }

        @Override
        public boolean supportsAltitude() {
            return mSupportsAltitude;
        }

        @Override
        public boolean supportsBearing() {
            return mSupportsBearing;
        }

        @Override
        public boolean supportsSpeed() {
            return mSupportsSpeed;
        }
    }
    
    private void checkMockPermissions() {
        boolean allowMocks = false;
        try {
            allowMocks = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION) == 1;
        } catch (SettingNotFoundException e) {
            // Do nothing
        }
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
        checkMockPermissions();

        MockProvider provider = new MockProvider(name, requiresNetwork, requiresSatellite,
            requiresCell, hasMonetaryCost, supportsAltitude,
            supportsSpeed, supportsBearing, powerRequirement, accuracy);
        if (LocationProviderImpl.getProvider(name) != null) {
            throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
        }
        LocationProviderImpl.addProvider(provider);
        updateProviders();
    }

    public void removeTestProvider(String provider) {
        checkMockPermissions();
        LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
        if (p == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        LocationProviderImpl.removeProvider(p);
        updateProviders();
    }

    public void setTestProviderLocation(String provider, Location loc) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        mMockProviderLocation.put(provider, loc);
    }

    public void clearTestProviderLocation(String provider) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        mMockProviderLocation.remove(provider);
    }

    public void setTestProviderEnabled(String provider, boolean enabled) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        if (enabled) {
            mEnabledProviders.add(provider);
            mDisabledProviders.remove(provider);
        } else {
            mEnabledProviders.remove(provider);
            mDisabledProviders.add(provider);
        }
        updateProviders();
    }

    public void clearTestProviderEnabled(String provider) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        mEnabledProviders.remove(provider);
        mDisabledProviders.remove(provider);
        updateProviders();
    }

    public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        mMockProviderStatus.put(provider, new Integer(status));
        mMockProviderStatusExtras.put(provider, extras);
        mMockProviderStatusUpdateTime.put(provider, new Long(updateTime));
    }

    public void clearTestProviderStatus(String provider) {
        checkMockPermissions();
        if (LocationProviderImpl.getProvider(provider) == null) {
            throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
        }
        mMockProviderStatus.remove(provider);
        mMockProviderStatusExtras.remove(provider);
        mMockProviderStatusUpdateTime.remove(provider);
    }

    private void log(String log) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, log);
        }
    }
}

