/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.locationtracker;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.widget.Toast;

import com.android.locationtracker.data.TrackerDataHelper;

import java.util.List;

/**
 * Location Tracking service
 *
 * Records location updates for all registered location providers, and cell
 * location updates
 */
public class TrackerService extends Service {

    private LocationTrackingListener mListener;

    private static final String LOG_TAG = TrackerActivity.LOG_TAG;

    private TrackerDataHelper mTrackerData;

    private TelephonyManager mTelephonyManager;
    private Location mNetworkLocation;

    // Handlers and Receivers for phone and network state
    private NetworkStateBroadcastReceiver mNetwork;
    private static final String CELL_PROVIDER_TAG = "cell";
    // signal strength updates
    private static final String SIGNAL_PROVIDER_TAG = "signal";
    private static final String WIFI_PROVIDER_TAG = "wifi";
    // tracking tag for data connectivity issues
    private static final String DATA_CONN_PROVIDER_TAG = "data";

    // preference constants
    private static final String MIN_TIME_PREF = "mintime_preference";
    private static final String POWER_PREF = "power_preference";
    private static final String GPS_PREF = "gps_preference";
    private static final String NETWORK_PREF = "network_preference";
    private static final String SIGNAL_PREF = "signal_preference";
    private static final String DEBUG_PREF = "advanced_log_preference";

    private PreferenceListener mPrefListener;

    public TrackerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // ignore - nothing to do
        return null;
    }

    /**
     * registers location listeners
     *
     * @param intent
     * @param startId
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mNetworkLocation = null;

        initLocationListeners();
        Toast.makeText(this, "Tracking service started", Toast.LENGTH_SHORT);
        return START_STICKY;
    }

    private synchronized void initLocationListeners() {
        mTrackerData = new TrackerDataHelper(this);

        mListener = new LocationTrackingListener();
        LocationManager lm = getLocationManager();

        long minUpdateTime = getLocationUpdateTime();
        int powerConsumption = getPowerConsumption();
        LocationRequest lr = LocationRequest.create();
        lr.setInterval(minUpdateTime);
        lr.setQuality(powerConsumption);
        if (doDebugLogging()) {
            mTrackerData.writeEntry("init", String.format(
                    "start listening to location update : %d ms; %d power consumption",
                     minUpdateTime, powerConsumption));
        }
        Log.d(LOG_TAG, "Adding location listener");
        lm.requestLocationUpdates(lr, mListener, null);


        mTelephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);

        if (doDebugLogging()) {
            // register for cell location updates
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION);

            // Register for Network (Wifi or Mobile) updates
            mNetwork = new NetworkStateBroadcastReceiver();
            IntentFilter mIntentFilter;
            mIntentFilter = new IntentFilter();
            mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            mIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            Log.d(LOG_TAG, "registering receiver");
            registerReceiver(mNetwork, mIntentFilter);
        }

        if (trackSignalStrength()) {
            mTelephonyManager.listen(mPhoneStateListener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        }

        // register for preference changes, so we can restart listeners on
        // pref changes
        mPrefListener = new PreferenceListener();
        getPreferences().registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    private SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private boolean doDebugLogging() {
        return getPreferences().getBoolean(DEBUG_PREF, false);
    }

    private boolean trackSignalStrength() {
        return getPreferences().getBoolean(SIGNAL_PREF, false);
    }

    private long getLocationUpdateTime() {
        try {
            String timeString = getPreferences().getString(MIN_TIME_PREF, "0");
            long secondsTime = Long.valueOf(timeString);
            return secondsTime * 1000;
        }
        catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Invalid preference for location min time", e);
        }
        return 0;
    }

    private int getPowerConsumption(){
        try {
            String power = getPreferences().getString(POWER_PREF, "203");
            return Integer.valueOf(power);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Invalid preference for power consumption", e);
        }
        return 203; //high
    }
    /**
     * Shuts down this service
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOG_TAG, "Removing location listeners");
        stopListeners();
        Toast.makeText(this, "Tracking service stopped", Toast.LENGTH_SHORT);
    }

    /**
     * De-registers all location listeners, closes persistent storage
     */
    protected synchronized void stopListeners() {
        LocationManager lm = getLocationManager();
        if (mListener != null) {
            lm.removeUpdates(mListener);
        }

        mListener = null;

        // stop cell state listener
        if (mTelephonyManager != null) {
            mTelephonyManager.listen(mPhoneStateListener, 0);
        }

        // stop network/wifi listener
        if (mNetwork != null) {
            unregisterReceiver(mNetwork);
        }
        mNetwork = null;

        mTrackerData = null;
        if (mPrefListener != null) {
            getPreferences().unregisterOnSharedPreferenceChangeListener(mPrefListener);
            mPrefListener = null;
        }
    }

    private LocationManager getLocationManager() {
        return (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Determine the current distance from given location to the last
     * approximated network location
     *
     * @param location - new location
     *
     * @return float distance in meters
     */
    private synchronized float getDistanceFromNetwork(Location location) {
        float value = 0;
        if (mNetworkLocation != null) {
            value = location.distanceTo(mNetworkLocation);
        }
        if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
            mNetworkLocation = location;
        }
        return value;
    }

    private class LocationTrackingListener implements LocationListener {

        /**
         * Writes details of location update to tracking file, including
         * recording the distance between this location update and the last
         * network location update
         *
         * @param location - new location
         */
        public void onLocationChanged(Location location) {
            if (location == null) {
                return;
            }
            float distance = getDistanceFromNetwork(location);
            mTrackerData.writeEntry(location, distance);
        }

        /**
         * Writes update to tracking file
         *
         * @param provider - name of disabled provider
         */
        public void onProviderDisabled(String provider) {
            if (doDebugLogging()) {
                mTrackerData.writeEntry(provider, "provider disabled");
            }
        }

        /**
         * Writes update to tracking file
         *
         * @param provider - name of enabled provider
         */
        public void onProviderEnabled(String provider) {
            if (doDebugLogging()) {
                mTrackerData.writeEntry(provider,  "provider enabled");
            }
        }

        /**
         * Writes update to tracking file
         *
         * @param provider - name of provider whose status changed
         * @param status - new status
         * @param extras - optional set of extra status messages
         */
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if (doDebugLogging()) {
                mTrackerData.writeEntry(provider,  "status change: " + status);
            }
        }
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCellLocationChanged(CellLocation location) {
            try {
                if (location instanceof GsmCellLocation) {
                    GsmCellLocation cellLocation = (GsmCellLocation)location;
                    String updateMsg = "cid=" + cellLocation.getCid() +
                            ", lac=" + cellLocation.getLac();
                    mTrackerData.writeEntry(CELL_PROVIDER_TAG, updateMsg);
                } else if (location instanceof CdmaCellLocation) {
                    CdmaCellLocation cellLocation = (CdmaCellLocation)location;
                    String updateMsg = "BID=" + cellLocation.getBaseStationId() +
                            ", SID=" + cellLocation.getSystemId() +
                            ", NID=" + cellLocation.getNetworkId() +
                            ", lat=" + cellLocation.getBaseStationLatitude() +
                            ", long=" + cellLocation.getBaseStationLongitude() +
                            ", SID=" + cellLocation.getSystemId() +
                            ", NID=" + cellLocation.getNetworkId();
                    mTrackerData.writeEntry(CELL_PROVIDER_TAG, updateMsg);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception in CellStateHandler.handleMessage:", e);
            }
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                String updateMsg = "cdma dBM=" + signalStrength.getCdmaDbm();
                mTrackerData.writeEntry(SIGNAL_PROVIDER_TAG, updateMsg);
            } else if  (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                String updateMsg = "gsm signal=" + signalStrength.getGsmSignalStrength();
                mTrackerData.writeEntry(SIGNAL_PROVIDER_TAG, updateMsg);
            }
        }
    };

    /**
     * Listener + recorder for mobile or wifi updates
     */
    private class NetworkStateBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                WifiManager wifiManager =
                    (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                List<ScanResult> wifiScanResults = wifiManager.getScanResults();
                String updateMsg = "num scan results=" +
                    (wifiScanResults == null ? "0" : wifiScanResults.size());
                mTrackerData.writeEntry(WIFI_PROVIDER_TAG, updateMsg);

            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                String updateMsg;
                boolean noConnectivity =
                    intent.getBooleanExtra(
                            ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (noConnectivity) {
                    updateMsg = "no connectivity";
                }
                else {
                    updateMsg = "connection available";
                }
                mTrackerData.writeEntry(DATA_CONN_PROVIDER_TAG, updateMsg);

            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN);

                String stateString = "unknown";
                switch (state) {
                    case WifiManager.WIFI_STATE_DISABLED:
                        stateString = "disabled";
                        break;
                    case WifiManager.WIFI_STATE_DISABLING:
                        stateString = "disabling";
                        break;
                    case WifiManager.WIFI_STATE_ENABLED:
                        stateString = "enabled";
                        break;
                    case WifiManager.WIFI_STATE_ENABLING:
                        stateString = "enabling";
                        break;
                }
                mTrackerData.writeEntry(WIFI_PROVIDER_TAG,
                        "state = " + stateString);
            }
        }
    }

    private class PreferenceListener implements OnSharedPreferenceChangeListener {

        public void onSharedPreferenceChanged(
                SharedPreferences sharedPreferences, String key) {
            Log.d(LOG_TAG, "restarting listeners due to preference change");
            synchronized (TrackerService.this) {
                stopListeners();
                initLocationListeners();
            }
        }
    }
}
