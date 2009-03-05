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
import android.net.Uri;
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
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Config;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.SparseIntArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.CellState;
import com.android.internal.location.GpsLocationProvider;
import com.android.internal.location.ILocationCollector;
import com.android.internal.location.INetworkLocationManager;
import com.android.internal.location.INetworkLocationProvider;
import com.android.internal.location.TrackProvider;
import com.android.server.am.BatteryStatsService;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 *
 * {@hide}
 */
public class LocationManagerService extends ILocationManager.Stub
        implements INetworkLocationManager {
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
    private boolean mGpsNavigating;
    private LocationProviderImpl mNetworkLocationProvider;
    private INetworkLocationProvider mNetworkLocationInterface;
    private LocationWorkerHandler mLocationHandler;

    // Handler messages
    private static final int MESSAGE_HEARTBEAT = 1;
    private static final int MESSAGE_ACQUIRE_WAKE_LOCK = 2;
    private static final int MESSAGE_RELEASE_WAKE_LOCK = 3;
    private static final int MESSAGE_INSTALL_NETWORK_LOCATION_PROVIDER = 4;

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
    
    private final IBatteryStats mBatteryStats;
    
    /**
     * Mapping from listener IBinder/PendingIntent to local Listener wrappers.
     */
    private final ArrayList<Receiver> mListeners = new ArrayList<Receiver>();

    /**
     * Used for reporting which UIDs are causing the GPS to run.
     */
    private final SparseIntArray mReportedGpsUids = new SparseIntArray();
    private int mReportedGpsSeq = 0;
    
    /**
     * Mapping from listener IBinder/PendingIntent to a map from provider name to UpdateRecord.
     * This also serves as the lock for our state.
     */
    private final HashMap<Receiver,HashMap<String,UpdateRecord>> mLocationListeners =
        new HashMap<Receiver,HashMap<String,UpdateRecord>>();

    /**
     * Mapping from listener IBinder/PendingIntent to a map from provider name to last broadcast
     * location.
     */
    private final HashMap<Receiver,HashMap<String,Location>> mLastFixBroadcast =
        new HashMap<Receiver,HashMap<String,Location>>();
    private final HashMap<Receiver,HashMap<String,Long>> mLastStatusBroadcast =
        new HashMap<Receiver,HashMap<String,Long>>();

    /**
     * Mapping from provider name to all its UpdateRecords
     */
    private final HashMap<String,ArrayList<UpdateRecord>> mRecordsByProvider =
        new HashMap<String,ArrayList<UpdateRecord>>();

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

    // Location collector
    private ILocationCollector mCollector;

    // Wifi Manager
    private WifiManager mWifiManager;

    private int mNetworkState = LocationProvider.TEMPORARILY_UNAVAILABLE;
    private boolean mWifiEnabled = false;

    /**
     * A wrapper class holding either an ILocationListener or a PendingIntent to receive
     * location updates.
     */
    private final class Receiver implements IBinder.DeathRecipient {
        final ILocationListener mListener;
        final PendingIntent mPendingIntent;
        final int mUid;
        final Object mKey;

        Receiver(ILocationListener listener, int uid) {
            mListener = listener;
            mPendingIntent = null;
            mUid = uid;
            mKey = listener.asBinder();
        }

        Receiver(PendingIntent intent, int uid) {
            mPendingIntent = intent;
            mListener = null;
            mUid = uid;
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
                        + " uid " + mUid + " Listener " + mKey + "}";
            } else {
                return "Receiver{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + " uid " + mUid + " Intent " + mKey + "}";
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
            if (Config.LOGD) {
                Log.d(TAG, "Location listener died");
            }
            synchronized (mLocationListeners) {
                removeUpdatesLocked(this);
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
        synchronized (mLocationListeners) {
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
            mGpsLocationProvider = new GpsLocationProvider(mContext);
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

        updateProvidersLocked();
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

        // Alarm manager, needs to be done before calling loadProviders() below
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Create a wake lock, needs to be done before calling loadProviders() below
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        
        // Battery statistics service to be notified when GPS turns on or off
        mBatteryStats = BatteryStatsService.getService();

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
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        context.registerReceiver(powerStateReceiver, intentFilter);

        // Get the wifi manager
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        // Create a wifi lock for future use
        mWifiLock = getWifiWakelockLocked();
    }

    public void setInstallCallback(InstallCallback callback) {
        synchronized (mLocationListeners) {
            mLocationHandler.removeMessages(MESSAGE_INSTALL_NETWORK_LOCATION_PROVIDER);
            Message m = Message.obtain(mLocationHandler, 
                    MESSAGE_INSTALL_NETWORK_LOCATION_PROVIDER, callback);
            mLocationHandler.sendMessageAtFrontOfQueue(m);
        }
    }

    public void setNetworkLocationProvider(INetworkLocationProvider provider) {
        synchronized (mLocationListeners) {
            mNetworkLocationInterface = provider;
            provider.addListener(getPackageNames());
            mNetworkLocationProvider = (LocationProviderImpl)provider;
            LocationProviderImpl.addProvider(mNetworkLocationProvider);
            updateProvidersLocked();
            
            // notify NetworkLocationProvider of any events it might have missed
            synchronized (mLocationListeners) {
                mNetworkLocationProvider.updateNetworkState(mNetworkState);
                mNetworkLocationInterface.updateWifiEnabledState(mWifiEnabled);
                mNetworkLocationInterface.updateCellLockStatus(mCellWakeLockAcquired);

                if (mLastCellState != null) {
                    if (mCollector != null) {
                        mCollector.updateCellState(mLastCellState);
                    }
                    mNetworkLocationProvider.updateCellState(mLastCellState);
                }

                // There might be an existing wifi scan available
                if (mWifiManager != null) {
                    List<ScanResult> wifiScanResults = mWifiManager.getScanResults();
                    if (wifiScanResults != null && wifiScanResults.size() != 0) {
                        mNetworkLocationInterface.updateWifiScanResults(wifiScanResults);
                        if (mCollector != null) {
                            mCollector.updateWifiScanResults(wifiScanResults);
                        }
                    }
                }
            }
        }
    }

    public void setLocationCollector(ILocationCollector collector) {
        synchronized (mLocationListeners) {
            mCollector = collector;
            if (mGpsLocationProvider != null) {
                mGpsLocationProvider.setLocationCollector(mCollector);
            }
        }
    }

    private WifiManager.WifiLock getWifiWakelockLocked() {
        if (mWifiLock == null && mWifiManager != null) {
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, WIFILOCK_KEY);
            mWifiLock.setReferenceCounted(false);
        }
        return mWifiLock;
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

    private String[] getPackageNames() {
        // Since a single UID may correspond to multiple packages, this can only be used as an
        // approximation for tracking
        return mContext.getPackageManager().getPackagesForUid(Binder.getCallingUid());
    }

    public List<String> getAllProviders() {
        try {
            synchronized (mLocationListeners) {
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
            synchronized (mLocationListeners) {
                return _getProvidersLocked(enabledOnly);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "getProviders gotString exception:", e);
            return null;
        }
    }

    private List<String> _getProvidersLocked(boolean enabledOnly) {
        if (Config.LOGD) {
            Log.d(TAG, "getProviders");
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

    public void updateProviders() {
        synchronized (mLocationListeners) {
            updateProvidersLocked();
        }
    }

    private void updateProvidersLocked() {
        for (LocationProviderImpl p : LocationProviderImpl.getProviders()) {
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedBySettingsLocked(name);

            // Collection is only allowed when network provider is being used
            if (mCollector != null &&
                    p.getName().equals(LocationManager.NETWORK_PROVIDER)) {
                mCollector.updateNetworkProviderStatus(shouldBeEnabled);
            }

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
                updateWakelockStatusLocked(mScreenOn);
            }
        } else {
            p.enableLocationTracking(false);
            if (p == mGpsLocationProvider) {
                mGpsNavigating = false;
                reportStopGpsLocked();
            }
            p.disable();
            updateWakelockStatusLocked(mScreenOn);
        }

        if (enabled && listeners > 0) {
            mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
            Message m = Message.obtain(mLocationHandler, MESSAGE_HEARTBEAT, provider);
            mLocationHandler.sendMessageAtTime(m, SystemClock.uptimeMillis() + 1000);
        } else {
            mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
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
        final String[] mPackages;

        /**
         * Note: must be constructed with lock held.
         */
        UpdateRecord(String provider, long minTime, float minDistance,
            Receiver receiver, int uid, String[] packages) {
            mProvider = provider;
            mReceiver = receiver;
            mMinTime = minTime;
            mMinDistance = minDistance;
            mUid = uid;
            mPackages = packages;

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
            StringBuilder sb = new StringBuilder();
            if (mPackages != null) {
                for (int i=0; i<mPackages.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(mPackages[i]);
                }
            }
            pw.println(prefix + "mUid=" + mUid + " mPackages=" + sb);
        }
        
        /**
         * Calls dispose().
         */
        @Override protected void finalize() {
            synchronized (mLocationListeners) {
                disposeLocked();
            }
        }
    }

    public void requestLocationUpdates(String provider,
        long minTime, float minDistance, ILocationListener listener) {

        try {
            synchronized (mLocationListeners) {
                requestLocationUpdatesLocked(provider, minTime, minDistance,
                    new Receiver(listener, Binder.getCallingUid()));
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
            synchronized (mLocationListeners) {
                requestLocationUpdatesLocked(provider, minTime, minDistance,
                        new Receiver(intent, Binder.getCallingUid()));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "requestUpdates got exception:", e);
        }
    }

    private void requestLocationUpdatesLocked(String provider,
            long minTime, float minDistance, Receiver receiver) {
        if (Config.LOGD) {
            Log.d(TAG, "_requestLocationUpdates: listener = " + receiver);
        }

        LocationProviderImpl impl = LocationProviderImpl.getProvider(provider);
        if (impl == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }

        checkPermissionsSafe(provider);

        String[] packages = getPackageNames();

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            UpdateRecord r = new UpdateRecord(provider, minTime, minDistance,
                    receiver, callingUid, packages);
            if (!mListeners.contains(receiver)) {
                try {
                    if (receiver.isListener()) {
                        receiver.getListener().asBinder().linkToDeath(receiver, 0);
                    }
                    mListeners.add(receiver);
                } catch (RemoteException e) {
                    return;
                }
            }

            HashMap<String,UpdateRecord> records = mLocationListeners.get(receiver);
            if (records == null) {
                records = new HashMap<String,UpdateRecord>();
                mLocationListeners.put(receiver, records);
            }
            UpdateRecord oldRecord = records.put(provider, r);
            if (oldRecord != null) {
                oldRecord.disposeLocked();
            }

            boolean isProviderEnabled = isAllowedBySettingsLocked(provider);
            if (isProviderEnabled) {
                long minTimeForProvider = getMinTimeLocked(provider);
                impl.setMinTime(minTimeForProvider);
                impl.enableLocationTracking(true);
                updateWakelockStatusLocked(mScreenOn);

                if (provider.equals(LocationManager.GPS_PROVIDER)) {
                    if (mGpsNavigating) {
                        updateReportedGpsLocked();
                    }
                }
                
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
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeUpdates(ILocationListener listener) {
        try {
            synchronized (mLocationListeners) {
                removeUpdatesLocked(new Receiver(listener, Binder.getCallingUid()));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    public void removeUpdatesPI(PendingIntent intent) {
        try {
            synchronized (mLocationListeners) {
                removeUpdatesLocked(new Receiver(intent, Binder.getCallingUid()));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates got exception:", e);
        }
    }

    private void removeUpdatesLocked(Receiver receiver) {
        if (Config.LOGD) {
            Log.d(TAG, "_removeUpdates: listener = " + receiver);
        }

        // so wakelock calls will succeed
        final int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            int idx = mListeners.indexOf(receiver);
            if (idx >= 0) {
                Receiver myReceiver = mListeners.remove(idx);
                if (myReceiver.isListener()) {
                    myReceiver.getListener().asBinder().unlinkToDeath(myReceiver, 0);
                }
            }

            // Record which providers were associated with this listener
            HashSet<String> providers = new HashSet<String>();
            HashMap<String,UpdateRecord> oldRecords = mLocationListeners.get(receiver);
            if (oldRecords != null) {
                // Call dispose() on the obsolete update records.
                for (UpdateRecord record : oldRecords.values()) {
                    if (record.mProvider.equals(LocationManager.NETWORK_PROVIDER)) {
                        if (mNetworkLocationInterface != null) {
                            mNetworkLocationInterface.removeListener(record.mPackages);
                        }
                    }
                    record.disposeLocked();
                }
                // Accumulate providers
                providers.addAll(oldRecords.keySet());
            }
            
            mLocationListeners.remove(receiver);
            mLastFixBroadcast.remove(receiver);
            mLastStatusBroadcast.remove(receiver);

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
                        mLocationHandler.removeMessages(MESSAGE_HEARTBEAT, provider);
                        p.enableLocationTracking(false);
                    }
                    
                    if (p == mGpsLocationProvider && mGpsNavigating) {
                        updateReportedGpsLocked();
                    }
                }
            }

            updateWakelockStatusLocked(mScreenOn);
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
        synchronized (mLocationListeners) {
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

        synchronized (mLocationListeners) {
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
            synchronized (mLocationListeners) {
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
        if (Config.LOGD) {
            Log.d(TAG, "addProximityAlert: latitude = " + latitude +
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
            mProximityListener = new Receiver(new ProximityListener(), -1);

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
        } else if (mGpsNavigating) {
            updateReportedGpsLocked();
        }
    }

    public void removeProximityAlert(PendingIntent intent) {
        try {
            synchronized (mLocationListeners) {
               removeProximityAlertLocked(intent);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "removeProximityAlert got exception:", e);
        }
    }

    private void removeProximityAlertLocked(PendingIntent intent) {
        if (Config.LOGD) {
            Log.d(TAG, "removeProximityAlert: intent = " + intent);
        }

        mProximityAlerts.remove(intent);
        if (mProximityAlerts.size() == 0) {
            removeUpdatesLocked(mProximityListener);
            mProximityListener = null;
        } else if (mGpsNavigating) {
            updateReportedGpsLocked();
        }
     }

    /**
     * @return null if the provider does not exits
     * @throw SecurityException if the provider is not allowed to be
     * accessed by the caller
     */
    public Bundle getProviderInfo(String provider) {
        try {
            synchronized (mLocationListeners) {
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
            synchronized (mLocationListeners) {
                return _isProviderEnabledLocked(provider);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            Log.e(TAG, "isProviderEnabled got exception:", e);
            return false;
        }
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
            synchronized (mLocationListeners) {
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

    private void handleLocationChangedLocked(String provider) {
        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
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
            writeLastKnownLocationLocked(provider, loc);

            if (p instanceof INetworkLocationProvider) {
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

        ArrayList<Receiver> deadReceivers = null;
        
        // Broadcast location or status to all listeners
        final int N = records.size();
        for (int i=0; i<N; i++) {
            UpdateRecord r = records.get(i);
            Receiver receiver = r.mReceiver;

            // Broadcast location only if it is valid
            if (locationValid) {
                HashMap<String,Location> map = mLastFixBroadcast.get(receiver);
                if (map == null) {
                    map = new HashMap<String,Location>();
                    mLastFixBroadcast.put(receiver, map);
                }
                Location lastLoc = map.get(provider);
                if ((lastLoc == null) || shouldBroadcastSafe(loc, lastLoc, r)) {
                    if (lastLoc == null) {
                        lastLoc = new Location(loc);
                        map.put(provider, lastLoc);
                    } else {
                        lastLoc.set(loc);
                    }
                    if (!receiver.callLocationChangedLocked(loc)) {
                        Log.w(TAG, "RemoteException calling onLocationChanged on " + receiver);
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<Receiver>();
                        }
                        deadReceivers.add(receiver);
                    }
                }
            }

            // Broadcast status message
            HashMap<String,Long> statusMap = mLastStatusBroadcast.get(receiver);
            if (statusMap == null) {
                statusMap = new HashMap<String,Long>();
                mLastStatusBroadcast.put(receiver, statusMap);
            }
            long prevStatusUpdateTime =
                (statusMap.get(provider) != null) ? statusMap.get(provider) : 0;

            if ((newStatusUpdateTime > prevStatusUpdateTime) &&
                (prevStatusUpdateTime != 0 || status != LocationProvider.AVAILABLE)) {

                statusMap.put(provider, newStatusUpdateTime);
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
                if (msg.what == MESSAGE_HEARTBEAT) {
                    // log("LocationWorkerHandler: Heartbeat!");

                    synchronized (mLocationListeners) {
                        String provider = (String) msg.obj;
                        if (!isAllowedBySettingsLocked(provider)) {
                            return;
                        }

                        // Process the location fix if the screen is on or we're holding a wakelock
                        if (mScreenOn || (mWakeLockAcquireTime != 0)) {
                            handleLocationChangedLocked(provider);
                        }

                        // If it continues to have listeners
                        ArrayList<UpdateRecord> records = mRecordsByProvider.get(provider);
                        if (records != null && records.size() > 0) {
                            Message m = Message.obtain(this, MESSAGE_HEARTBEAT, provider);
                            sendMessageAtTime(m, SystemClock.uptimeMillis() + 1000);
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
                    }

                } else if (msg.what == MESSAGE_ACQUIRE_WAKE_LOCK) {
                    log("LocationWorkerHandler: Acquire");
                    synchronized (mLocationListeners) {
                        acquireWakeLockLocked();
                    }
                } else if (msg.what == MESSAGE_RELEASE_WAKE_LOCK) {
                    log("LocationWorkerHandler: Release");

                    // Update wakelock status so the next alarm is set before releasing wakelock
                    synchronized (mLocationListeners) {
                        updateWakelockStatusLocked(mScreenOn);
                        releaseWakeLockLocked();
                    }
                } else if (msg.what == MESSAGE_INSTALL_NETWORK_LOCATION_PROVIDER) {
                    synchronized (mLocationListeners) {
                        Log.d(TAG, "installing network location provider");
                        INetworkLocationManager.InstallCallback callback =
                                (INetworkLocationManager.InstallCallback)msg.obj;
                        callback.installNetworkLocationProvider(LocationManagerService.this);
                    }
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in LocationWorkerHandler.handleMessage:", e);
            }
        }
    }

    class CellLocationUpdater extends Thread {
        CellLocation mNextLocation;
        
        CellLocationUpdater() {
            super("CellLocationUpdater");
        }
        
        @Override
        public void run() {
            int curAsu = -1;
            CellLocation curLocation = null;
            
            while (true) {
                // See if there is more work to do...
                synchronized (mLocationListeners) {
                    if (curLocation == mNextLocation) {
                        mCellLocationUpdater = null;
                        break;
                    }
                    
                    curLocation = mNextLocation;
                    if (curLocation == null) {
                        mCellLocationUpdater = null;
                        break;
                    }
                    
                    curAsu = mLastSignalStrength;
                    
                    mNextLocation = null;
                }
                
                try {
                    // Gets cell state.  This can block so must be done without
                    // locks held.
                    CellState cs = new CellState(mTelephonyManager, curLocation, curAsu);
                    
                    synchronized (mLocationListeners) {
                        mLastCellState = cs;
        
                        cs.updateSignalStrength(mLastSignalStrength);
                        cs.updateRadioType(mLastRadioType);
                        
                        // Notify collector
                        if (mCollector != null) {
                            mCollector.updateCellState(cs);
                        }
    
                        // Updates providers
                        List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
                        for (LocationProviderImpl provider : providers) {
                            if (provider.requiresCell()) {
                                provider.updateCellState(cs);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Exception in PhoneStateListener.onCellLocationChanged:", e);
                }
            }
        }
    }
    
    CellLocationUpdater mCellLocationUpdater = null;
    CellState mLastCellState = null;
    int mLastSignalStrength = -1;
    int mLastRadioType = -1;
    
    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {

        @Override
        public void onCellLocationChanged(CellLocation cellLocation) {
            synchronized (mLocationListeners) {
                if (mCellLocationUpdater == null) {
                    mCellLocationUpdater = new CellLocationUpdater();
                    mCellLocationUpdater.start();
                }
                mCellLocationUpdater.mNextLocation = cellLocation;
            }
        }

        @Override
        public void onSignalStrengthChanged(int asu) {
            synchronized (mLocationListeners) {
                mLastSignalStrength = asu;
    
                if (mLastCellState != null) {
                    mLastCellState.updateSignalStrength(asu);
                }
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state) {
            synchronized (mLocationListeners) {
                // Get radio type
                int radioType = mTelephonyManager.getNetworkType();
                if (radioType == TelephonyManager.NETWORK_TYPE_GPRS ||
                    radioType == TelephonyManager.NETWORK_TYPE_EDGE) {
                    radioType = CellState.RADIO_TYPE_GPRS;
                } else if (radioType == TelephonyManager.NETWORK_TYPE_UMTS) {
                    radioType = CellState.RADIO_TYPE_WCDMA;
                }
                mLastRadioType = radioType;

                if (mLastCellState != null) {
                    mLastCellState.updateRadioType(radioType);
                }
            }
        }
    };

    private class PowerStateBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(ALARM_INTENT)) {
                synchronized (mLocationListeners) {
                    log("PowerStateBroadcastReceiver: Alarm received");
                    mLocationHandler.removeMessages(MESSAGE_ACQUIRE_WAKE_LOCK);
                    // Have to do this immediately, rather than posting a
                    // message, so we execute our code while the system
                    // is holding a wake lock until the alarm broadcast
                    // is finished.
                    acquireWakeLockLocked();
                }

            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                log("PowerStateBroadcastReceiver: Screen off");
                synchronized (mLocationListeners) {
                    updateWakelockStatusLocked(false);
                }

            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                log("PowerStateBroadcastReceiver: Screen on");
                synchronized (mLocationListeners) {
                    updateWakelockStatusLocked(true);
                }

            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                log("PowerStateBroadcastReceiver: Battery changed");
                synchronized (mLocationListeners) {
                    int scale = intent.getIntExtra(BATTERY_EXTRA_SCALE, 100);
                    int level = intent.getIntExtra(BATTERY_EXTRA_LEVEL, 0);
                    boolean plugged = intent.getIntExtra(BATTERY_EXTRA_PLUGGED, 0) != 0;
    
                    // Notify collector battery state
                    if (mCollector != null) {
                        mCollector.updateBatteryState(scale, level, plugged);
                    }
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)) {
                synchronized (mLocationListeners) {
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

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {

                List<ScanResult> wifiScanResults = mWifiManager.getScanResults();

                if (wifiScanResults == null) {
                    return;
                }

                // Notify provider and collector of Wifi scan results
                synchronized (mLocationListeners) {
                    if (mCollector != null) {
                        mCollector.updateWifiScanResults(wifiScanResults);
                    }
                    if (mNetworkLocationInterface != null) {
                        mNetworkLocationInterface.updateWifiScanResults(wifiScanResults);
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
                synchronized (mLocationListeners) {
                    List<LocationProviderImpl> providers = LocationProviderImpl.getProviders();
                    for (LocationProviderImpl provider : providers) {
                        if (provider.requiresNetwork()) {
                            provider.updateNetworkState(mNetworkState);
                        }
                    }
                }

            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN);

                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    mWifiEnabled = true;
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    mWifiEnabled = false;
                } else {
                    return;
                }

                // Notify network provider of current wifi enabled state
                synchronized (mLocationListeners) {
                    if (mNetworkLocationInterface != null) {
                        mNetworkLocationInterface.updateWifiEnabledState(mWifiEnabled);
                    }
                }

            } else if (action.equals(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION)) {

                final boolean enabled = intent.getBooleanExtra(GpsLocationProvider.EXTRA_ENABLED,
                    false);

                synchronized (mLocationListeners) {
                    if (enabled) {
                        updateReportedGpsLocked();
                        mGpsNavigating = true;
                    } else {
                        reportStopGpsLocked();
                        mGpsNavigating = false;
                        // When GPS is disabled, we are OK to release wake-lock
                        mWakeLockGpsReceived = true;
                    }
                }
            }

        }
    }

    // Wake locks

    private void updateWakelockStatusLocked(boolean screenOn) {
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
                startGpsLocked();
            } else if (mScreenOn && !screenOn) {
                // We just turned the screen off so stop navigating
                stopGpsLocked();
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
            releaseWakeLockLocked();
        }
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

        // Start the gps provider
        startGpsLocked();

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
        if (mNetworkLocationInterface != null) {
            mNetworkLocationInterface.updateCellLockStatus(mCellWakeLockAcquired);
        }

        // Acquire wifi lock
        WifiManager.WifiLock wifiLock = getWifiWakelockLocked();
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

    private boolean reportGpsUidLocked(int curSeq, int nextSeq, int uid) {
        int seq = mReportedGpsUids.get(uid, -1);
        if (seq == curSeq) {
            // Already reported; propagate to next sequence.
            mReportedGpsUids.put(uid, nextSeq);
            return true;
        } else if (seq != nextSeq) {
            try {
                // New UID; report it.
                mBatteryStats.noteStartGps(uid);
                mReportedGpsUids.put(uid, nextSeq);
                return true;
            } catch (RemoteException e) {
            }
        }
        return false;
    }
    
    private void updateReportedGpsLocked() {
        if (mGpsLocationProvider == null) {
            return;
        }
        
        final String name = mGpsLocationProvider.getName();
        final int curSeq = mReportedGpsSeq;
        final int nextSeq = (curSeq+1) >= 0 ? (curSeq+1) : 0;
        mReportedGpsSeq = nextSeq;
        
        ArrayList<UpdateRecord> urs = mRecordsByProvider.get(name);
        int num = 0;
        final int N = urs.size();
        for (int i=0; i<N; i++) {
            UpdateRecord ur = urs.get(i);
            if (ur.mReceiver == mProximityListener) {
                // We don't want the system to take the blame for this one.
                continue;
            }
            if (reportGpsUidLocked(curSeq, nextSeq, ur.mUid)) {
                num++;
            }
        }
        
        for (ProximityAlert pe : mProximityAlerts.values()) {
            if (reportGpsUidLocked(curSeq, nextSeq, pe.mUid)) {
                num++;
            }
        }
        
        if (num != mReportedGpsUids.size()) {
            // The number of uids is processed is different than the
            // array; report any that are no longer active.
            for (int i=mReportedGpsUids.size()-1; i>=0; i--) {
                if (mReportedGpsUids.valueAt(i) != nextSeq) {
                    try {
                        mBatteryStats.noteStopGps(mReportedGpsUids.keyAt(i));
                    } catch (RemoteException e) {
                    }
                    mReportedGpsUids.removeAt(i);
                }
            }
        }
    }
    
    private void reportStopGpsLocked() {
        int curSeq = mReportedGpsSeq;
        for (int i=mReportedGpsUids.size()-1; i>=0; i--) {
            if (mReportedGpsUids.valueAt(i) == curSeq) {
                try {
                    mBatteryStats.noteStopGps(mReportedGpsUids.keyAt(i));
                } catch (RemoteException e) {
                }
            }
        }
        curSeq++;
        if (curSeq < 0) curSeq = 0;
        mReportedGpsSeq = curSeq;
        mReportedGpsUids.clear();
    }
    
    private void startGpsLocked() {
        boolean gpsActive = (mGpsLocationProvider != null)
                    && mGpsLocationProvider.isLocationTracking();
        if (gpsActive) {
            mGpsLocationProvider.startNavigating();
        }
    }

    private void stopGpsLocked() {
        boolean gpsActive = mGpsLocationProvider != null
                    && mGpsLocationProvider.isLocationTracking();
        if (gpsActive) {
            mGpsLocationProvider.stopNavigating();
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
        // Release wifi lock
        WifiManager.WifiLock wifiLock = getWifiWakelockLocked();
        if (wifiLock != null) {
            if (mWifiWakeLockAcquired) {
                wifiLock.release();
                mWifiWakeLockAcquired = false;
            }
        }

        if (!mScreenOn) {
            // Stop the gps
            stopGpsLocked();
        }

        // Release cell lock
        if (mCellWakeLockAcquired) {
            mTelephonyManager.disableLocationUpdates();
            mCellWakeLockAcquired = false;
        }

        // Notify NetworkLocationProvider
        if (mNetworkLocationInterface != null) {
            mNetworkLocationInterface.updateCellLockStatus(mCellWakeLockAcquired);
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
        synchronized (mLocationListeners) {
            if (mNetworkLocationInterface != null) {
                return mNetworkLocationInterface.getFromLocation(latitude, longitude, maxResults,
                        language, country, variant, appName, addrs);
            } else {
                return null;
            }
        }
    }

    public String getFromLocationName(String locationName,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults,
        String language, String country, String variant, String appName, List<Address> addrs) {
        synchronized (mLocationListeners) {
            if (mNetworkLocationInterface != null) {
                return mNetworkLocationInterface.getFromLocationName(locationName, lowerLeftLatitude, 
                        lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults,
                        language, country, variant, appName, addrs);
            } else {
                return null;
            }
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
            // We shouldn't normally need to lock, since this should only be called
            // by the service with the lock held, but let's be paranid.
            synchronized (mLocationListeners) {
                mEnabledProviders.remove(name);
                mDisabledProviders.add(name);
            }
        }

        @Override
        public void enable() {
            String name = getName();
            // We shouldn't normally need to lock, since this should only be called
            // by the service with the lock held, but let's be paranid.
            synchronized (mLocationListeners) {
                mEnabledProviders.add(name);
                mDisabledProviders.remove(name);
            }
        }

        @Override
        public boolean getLocation(Location l) {
            // We shouldn't normally need to lock, since this should only be called
            // by the service with the lock held, but let's be paranid.
            synchronized (mLocationListeners) {
                Location loc = mMockProviderLocation.get(getName());
                if (loc == null) {
                    return false;
                }
                l.set(loc);
                return true;
            }
        }

        @Override
        public int getStatus(Bundle extras) {
            // We shouldn't normally need to lock, since this should only be called
            // by the service with the lock held, but let's be paranid.
            synchronized (mLocationListeners) {
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
        }

        @Override
        public boolean isEnabled() {
            // We shouldn't normally need to lock, since this should only be called
            // by the service with the lock held, but let's be paranid.
            synchronized (mLocationListeners) {
                return mEnabledProviders.contains(getName());
            }
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

        synchronized (mLocationListeners) {
            MockProvider provider = new MockProvider(name, requiresNetwork, requiresSatellite,
                requiresCell, hasMonetaryCost, supportsAltitude,
                supportsSpeed, supportsBearing, powerRequirement, accuracy);
            if (LocationProviderImpl.getProvider(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }
            LocationProviderImpl.addProvider(provider);
            updateProvidersLocked();
        }
    }

    public void removeTestProvider(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            LocationProviderImpl p = LocationProviderImpl.getProvider(provider);
            if (p == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            LocationProviderImpl.removeProvider(p);
            updateProvidersLocked();
        }
    }

    public void setTestProviderLocation(String provider, Location loc) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            if (LocationProviderImpl.getProvider(provider) == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mMockProviderLocation.put(provider, loc);
        }
    }

    public void clearTestProviderLocation(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            if (LocationProviderImpl.getProvider(provider) == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mMockProviderLocation.remove(provider);
        }
    }

    public void setTestProviderEnabled(String provider, boolean enabled) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
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
            updateProvidersLocked();
        }
    }

    public void clearTestProviderEnabled(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            if (LocationProviderImpl.getProvider(provider) == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mEnabledProviders.remove(provider);
            mDisabledProviders.remove(provider);
            updateProvidersLocked();
        }
    }

    public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            if (LocationProviderImpl.getProvider(provider) == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mMockProviderStatus.put(provider, new Integer(status));
            mMockProviderStatusExtras.put(provider, extras);
            mMockProviderStatusUpdateTime.put(provider, new Long(updateTime));
        }
    }

    public void clearTestProviderStatus(String provider) {
        checkMockPermissionsSafe();
        synchronized (mLocationListeners) {
            if (LocationProviderImpl.getProvider(provider) == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mMockProviderStatus.remove(provider);
            mMockProviderStatusExtras.remove(provider);
            mMockProviderStatusUpdateTime.remove(provider);
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
        
        synchronized (mLocationListeners) {
            pw.println("Current Location Manager state:");
            pw.println("  sProvidersLoaded=" + sProvidersLoaded);
            pw.println("  mGpsLocationProvider=" + mGpsLocationProvider);
            pw.println("  mGpsNavigating=" + mGpsNavigating);
            pw.println("  mNetworkLocationProvider=" + mNetworkLocationProvider);
            pw.println("  mNetworkLocationInterface=" + mNetworkLocationInterface);
            pw.println("  mLastSignalStrength=" + mLastSignalStrength
                    + "  mLastRadioType=" + mLastRadioType);
            pw.println("  mCellLocationUpdater=" + mCellLocationUpdater);
            pw.println("  mLastCellState=" + mLastCellState);
            pw.println("  mCollector=" + mCollector);
            pw.println("  mAlarmInterval=" + mAlarmInterval
                    + " mScreenOn=" + mScreenOn
                    + " mWakeLockAcquireTime=" + mWakeLockAcquireTime);
            pw.println("  mWakeLockGpsReceived=" + mWakeLockGpsReceived
                    + " mWakeLockNetworkReceived=" + mWakeLockNetworkReceived);
            pw.println("  mWifiWakeLockAcquired=" + mWifiWakeLockAcquired
                    + " mCellWakeLockAcquired=" + mCellWakeLockAcquired);
            pw.println("  Listeners:");
            int N = mListeners.size();
            for (int i=0; i<N; i++) {
                pw.println("    " + mListeners.get(i));
            }
            pw.println("  Location Listeners:");
            for (Map.Entry<Receiver, HashMap<String,UpdateRecord>> i
                    : mLocationListeners.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                for (Map.Entry<String,UpdateRecord> j : i.getValue().entrySet()) {
                    pw.println("      " + j.getKey() + ":");
                    j.getValue().dump(pw, "        ");
                }
            }
            pw.println("  Last Fix Broadcasts:");
            for (Map.Entry<Receiver, HashMap<String,Location>> i
                    : mLastFixBroadcast.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                for (Map.Entry<String,Location> j : i.getValue().entrySet()) {
                    pw.println("      " + j.getKey() + ":");
                    j.getValue().dump(new PrintWriterPrinter(pw), "        ");
                }
            }
            pw.println("  Last Status Broadcasts:");
            for (Map.Entry<Receiver, HashMap<String,Long>> i
                    : mLastStatusBroadcast.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                for (Map.Entry<String,Long> j : i.getValue().entrySet()) {
                    pw.println("      " + j.getKey() + " -> 0x"
                            + Long.toHexString(j.getValue()));
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
            pw.println("  Locations by Provider:");
            for (Map.Entry<String, Location> i
                    : mLocationsByProvider.entrySet()) {
                pw.println("    " + i.getKey() + ":");
                i.getValue().dump(new PrintWriterPrinter(pw), "      ");
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
                    pw.println("    " + i.getKey() + " -> " + i.getValue());
                }
            }
            if (mMockProviderLocation.size() > 0) {
                pw.println("  Mock Provider Location:");
                for (Map.Entry<String, Location> i : mMockProviderLocation.entrySet()) {
                    pw.println("    " + i.getKey() + ":");
                    i.getValue().dump(new PrintWriterPrinter(pw), "      ");
                }
            }
            if (mMockProviderStatus.size() > 0) {
                pw.println("  Mock Provider Status:");
                for (Map.Entry<String, Integer> i : mMockProviderStatus.entrySet()) {
                    pw.println("    " + i.getKey() + " -> 0x"
                            + Integer.toHexString(i.getValue()));
                }
            }
            if (mMockProviderStatusExtras.size() > 0) {
                pw.println("  Mock Provider Status Extras:");
                for (Map.Entry<String, Bundle> i : mMockProviderStatusExtras.entrySet()) {
                    pw.println("    " + i.getKey() + " -> " + i.getValue());
                }
            }
            if (mMockProviderStatusUpdateTime.size() > 0) {
                pw.println("  Mock Provider Status Update Time:");
                for (Map.Entry<String, Long> i : mMockProviderStatusUpdateTime.entrySet()) {
                    pw.println("    " + i.getKey() + " -> " + i.getValue());
                }
            }
            pw.println("  Reported GPS UIDs @ seq " + mReportedGpsSeq + ":");
            N = mReportedGpsUids.size();
            for (int i=0; i<N; i++)  {
                pw.println("    UID " + mReportedGpsUids.keyAt(i)
                        + " seq=" + mReportedGpsUids.valueAt(i));
            }
        }
    }
}

