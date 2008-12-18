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

package com.android.internal.location;

import com.android.internal.location.protocol.GDebugProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProviderImpl;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * A location provider which gets approximate location from Google's
 * database of cell-tower and wi-fi locations.
 *
 * <p> It is the responsibility of the LocationManagerService to
 * notify this class of any changes in the radio information
 * by calling {@link #updateCellState} and of data state
 * changes by calling {@link #updateNetworkState}
 *
 * <p> The LocationManagerService must also notify the provider
 * of Wifi updates using the {@link #updateWifiScanResults}
 * and {@link #updateWifiEnabledState}
 * methods.
 *
 * <p> The provider uses whichever radio is available - Cell
 * or WiFi. If neither is available, it does NOT attempt to
 * switch them on.
 *
 * {@hide}
 */
public class NetworkLocationProvider extends LocationProviderImpl {
    private static final String TAG = "NetworkLocationProvider";

    // Wait at least 60 seconds between network queries
    private static final int MIN_NETWORK_RETRY_MILLIS = 60000;

    // Max time to wait for radio update
    private static final long MAX_TIME_TO_WAIT_FOR_RADIO = 5 * 1000; // 5 seconds

    // State of entire provider
    private int mStatus = AVAILABLE;
    private long mStatusUpdateTime = 0;

    // Network state
    private int mNetworkState = TEMPORARILY_UNAVAILABLE;

    // Cell state
    private static final int MAX_CELL_HISTORY_TO_KEEP = 4;
    private LinkedList<CellState> mCellHistory = new LinkedList<CellState>();
    private CellState mCellState = null;
    private long mLastCellStateChangeTime = 0;
    private long mLastCellLockTime = 0;

    // Wifi state
    private static final long MIN_TIME_BETWEEN_WIFI_REPORTS = 45 * 1000; // 45 seconds
    private List<ScanResult> mWifiLastScanResults = null;
    private long mLastWifiScanTriggerTime = 0;
    private long mLastWifiScanElapsedTime = 0;
    private long mLastWifiScanRealTime = 0;
    private long mWifiScanFrequency = MIN_TIME_BETWEEN_WIFI_REPORTS;
    private boolean mWifiEnabled = false;

    // Last known location state
    private Location mLocation = new Location(LocationManager.NETWORK_PROVIDER);
    private long mLastNetworkQueryTime = 0;  // Last network request, successful or not
    private long mLastSuccessfulNetworkQueryTime = 0; // Last successful network query time

    // Is provider enabled by user -- ignored by this class
    private boolean mEnabled;

    // Is provider being used by an application
    private HashSet<String> mApplications = new HashSet<String>();
    private boolean mTracking = false;

    // Location masf service
    private LocationMasfClient mMasfClient;

    // Context of location manager service
    private Context mContext;

    public static boolean isSupported() {
        // This class provides a Google-specific location feature, so it's enabled only
        // when the system property ro.com.google.locationfeatures  is set.
        if (!SystemProperties.get("ro.com.google.locationfeatures").equals("1")) {
            return false;
        }

        // Otherwise, assume cell location should work if we are not running in the emulator
        return !SystemProperties.get("ro.kernel.qemu").equals("1");
    }

    public NetworkLocationProvider(Context context, LocationMasfClient masfClient) {
        super(LocationManager.NETWORK_PROVIDER);
        mContext = context;
        mMasfClient = masfClient;
    }

    @Override
    public void updateNetworkState(int state) {
        if (state == mNetworkState) {
            return;
        }
        log("updateNetworkState(): Updating network state to " + state);
        mNetworkState = state;

        updateStatus(mNetworkState);
    }

    @Override
    public void updateCellState(CellState newState) {
        if (newState == null) {
            log("updateCellState(): Cell state is invalid");
            return;
        }

        if (mCellState != null && mCellState.equals(newState)) {
            log("updateCellState(): Cell state is the same");
            return;
        }

        // Add previous state to history
        if ((mCellState != null) && mCellState.isValid()) {
            if (mCellHistory.size() >= MAX_CELL_HISTORY_TO_KEEP) {
                mCellHistory.remove(0);
            }
            mCellHistory.add(mCellState);
        }

        mCellState = newState;
        log("updateCellState(): Received");

        mLastCellLockTime = 0;        
        mLastCellStateChangeTime = SystemClock.elapsedRealtime();
    }

    public void updateCellLockStatus(boolean acquired) {
        if (acquired) {
            mLastCellLockTime = SystemClock.elapsedRealtime();
        } else {
            mLastCellLockTime = 0;
        }
    }

    @Override
    public boolean requiresNetwork() {
        return true;
    }

    @Override
    public boolean requiresSatellite() {
        return false;
    }

    @Override
    public boolean requiresCell() {
        return true;
    }

    @Override
    public boolean hasMonetaryCost() {
        return true;
    }

    @Override
    public boolean supportsAltitude() {
        return false;
    }

    @Override
    public boolean supportsSpeed() {
        return false;
    }

    @Override
    public boolean supportsBearing() {
        return false;
    }

    @Override
    public int getPowerRequirement() {
        return Criteria.POWER_LOW;
    }

    @Override
    public void enable() {
        // Nothing else needs to be done
        mEnabled = true;
    }

    @Override
    public void disable() {
        // Nothing else needs to be done
        mEnabled = false;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public int getAccuracy() {
        return Criteria.ACCURACY_COARSE;
    }

    @Override
    public int getStatus(Bundle extras) {
        return mStatus;
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    @Override
    public void setMinTime(long minTime) {
        if (minTime < MIN_TIME_BETWEEN_WIFI_REPORTS) {
            mWifiScanFrequency = MIN_TIME_BETWEEN_WIFI_REPORTS;
        } else {
            mWifiScanFrequency = minTime;
        }
        super.setMinTime(minTime);
    }

    @Override
    public boolean getLocation(Location l) {

        long now = SystemClock.elapsedRealtime();

        // Trigger a wifi scan and wait for its results if necessary
        if ((mWifiEnabled) &&
            (mWifiLastScanResults == null ||
                ((now - mLastWifiScanElapsedTime) > mWifiScanFrequency))) {

            boolean fallback = false;

            // If scan has been recently triggered
            if (mLastWifiScanTriggerTime != 0 &&
                ((now - mLastWifiScanTriggerTime) < mWifiScanFrequency)) {
                if ((now - mLastWifiScanTriggerTime) > MAX_TIME_TO_WAIT_FOR_RADIO) {
                    // If no results from last trigger available, use cell results
                    // This will also trigger a new scan
                    log("getLocation(): falling back to cell");
                    fallback = true;
                } else {
                    // Just wait for the Wifi results to be available
                    return false;
                }
            }

            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            log("getLocation(): triggering a wifi scan");
            mLastWifiScanTriggerTime = now;
            boolean succeeded = wifiManager.startScan();
            if (!succeeded) {
                log("getLocation(): wifi scan did not succeed");
                // Wifi trigger failed, use cell results
                fallback = true;
            }

            // Wait for scan results
            if (!fallback) {
                return false;
            }
        }

        // If waiting for cell location
        if (mLastCellLockTime != 0 && ((now - mLastCellLockTime) < MAX_TIME_TO_WAIT_FOR_RADIO)) {
            return false;
        }

        // Update Location
        // 1) If there has been a cell state change
        // 2) If there was no successful reply for last network request
        if (mLastCellStateChangeTime > mLastNetworkQueryTime) {
            updateLocation();
            return false;

        } else if ((mLastNetworkQueryTime != 0)
            && (mLastNetworkQueryTime > mLastSuccessfulNetworkQueryTime)
            && ((now - mLastNetworkQueryTime) > MIN_NETWORK_RETRY_MILLIS)) {
            updateLocation();
            return false;
        }

        if (mLocation != null && mLocation.getAccuracy() > 0) {
            
            // We could have a Cell Id location which hasn't changed in a
            // while because we haven't switched towers so if the last location
            // time + mWifiScanFrequency is less than current time update the 
            // locations time.
            long currentTime = System.currentTimeMillis();
            if ((mLocation.getTime() + mWifiScanFrequency) < currentTime) {
                mLocation.setTime(currentTime);
            }
            l.set(mLocation);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void enableLocationTracking(boolean enable) {
        if (enable == mTracking) {
            return;
        }

        log("enableLocationTracking(): " + enable);
        mTracking = enable;

        if (!enable) {
            // When disabling the location provider, be sure to clear out old location
            clearLocation();
        } else {
            // When enabling provider, force location
            forceLocation();
        }
    }

    @Override
    public boolean isLocationTracking() {
        return mTracking;
    }

    /**
     * Notifies the provider that there are scan results available.
     *
     * @param scanResults list of wifi scan results
     */
    public void updateWifiScanResults(List<ScanResult> scanResults) {
        if (!mTracking) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (scanResults == null) {
            mWifiLastScanResults = null;
            mLastWifiScanElapsedTime = now;
            mLastWifiScanRealTime = System.currentTimeMillis();

            log("updateWifIScanResults(): NULL APs");

            // Force cell location since no wifi results available
            if (mWifiEnabled) {
                mLastCellLockTime = 0;
                mLastCellStateChangeTime = SystemClock.elapsedRealtime();
            }

        } else if ((mWifiLastScanResults == null)
            || (mWifiLastScanResults.size() <= 2 && scanResults.size() > mWifiLastScanResults.size()) 
            || ((now - mLastWifiScanElapsedTime) > mWifiScanFrequency)) {

            if (mWifiLastScanResults == null) {
                mWifiLastScanResults = new ArrayList<ScanResult>();
            } else {
                mWifiLastScanResults.clear();
            }
            mWifiLastScanResults.addAll(scanResults);
            mLastWifiScanElapsedTime = now;
            mLastWifiScanRealTime = System.currentTimeMillis();

            log("updateWifIScanResults(): " + mWifiLastScanResults.size() + " APs");
            updateLocation();

        }
    }

    /**
     * Notifies the provider if Wifi has been enabled or disabled
     * by the user
     *
     * @param enabled true if wifi is enabled; false otherwise
     */
    public void updateWifiEnabledState(boolean enabled) {
        mWifiEnabled = enabled;

        log("updateWifiEnabledState(): " + enabled);

        // Force location update
        forceLocation();
    }
    
    public void addListener(String[] applications) {
        if (applications != null) {
            for (String app : applications) {
                String a = app.replaceAll("com.google.android.", "");
                a = a.replaceAll("com.android.", "");
                mApplications.add(a);
                log("addListener(): " + a);
            }
        }
    }

    public void removeListener(String[] applications) {
        if (applications != null) {
            for (String app : applications) {
                String a = app.replaceAll("com.google.android.", "");
                a = a.replaceAll("com.android.", "");
                mApplications.remove(a);
                log("removeListener(): " + a);
            }
        }
    }

    private void clearLocation() {
        mLocation.setAccuracy(-1);
        updateStatus(TEMPORARILY_UNAVAILABLE);
    }

    private void forceLocation() {
        if (mWifiEnabled) {
            // Force another wifi scan
            mWifiLastScanResults = null;
            mLastWifiScanTriggerTime = 0;
            mLastWifiScanElapsedTime = 0;
            mLastWifiScanRealTime = 0;
        } else {
            // Force another cell location request
            mLastCellLockTime = 0;
            mLastCellStateChangeTime = SystemClock.elapsedRealtime();
        }
    }

    private void updateStatus(int status) {
        if (status != mStatus) {
            mStatus = status;
            mStatusUpdateTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Gets location from the server is applications are tracking this provider
     *
     */
    private void updateLocation() {

        // If not being tracked, no need to do anything.
        if (!mTracking) {
            return;
        }

        // If network is not available, can't do anything
        if (mNetworkState != AVAILABLE) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        
        // There is a pending network request
        if ((mLastNetworkQueryTime != 0) &&
            (mLastNetworkQueryTime > mLastSuccessfulNetworkQueryTime) &&
            ((now - mLastNetworkQueryTime) <= MIN_NETWORK_RETRY_MILLIS)) {
            return;
        }

        // Don't include wifi points if they're too old
        List<ScanResult> scanResults = null;
        if (mWifiEnabled && (mWifiLastScanResults != null &&
            ((now - mLastWifiScanElapsedTime) < (mWifiScanFrequency + MAX_TIME_TO_WAIT_FOR_RADIO)))) {
            scanResults = mWifiLastScanResults;
        }

        // If no valid cell information available
        boolean noCell = mCellState == null || !mCellState.isValid();

        // If no valid wifi information available
        boolean noWifi = scanResults == null || (scanResults.size() == 0);

        // If no cell-id or wi-fi update, just return invalid location
        if (noCell && noWifi) {
            clearLocation();
            return;
        }

        // What kind of a network location request was it
        int trigger;
        if (!mWifiEnabled) {
            if (!noCell) {
                trigger = GDebugProfile.TRIGGER_CELL_AND_WIFI_CHANGE;
            } else {
                trigger = GDebugProfile.TRIGGER_WIFI_CHANGE;
            }
        } else {
            trigger = GDebugProfile.TRIGGER_CELL_CHANGE;
        }

        try {
            mLastNetworkQueryTime = now;
            mMasfClient.getNetworkLocation(mApplications, trigger, mCellState, mCellHistory,
                scanResults, mLastWifiScanRealTime, new Callback() {
                public void locationReceived(Location location, boolean networkSuccessful) {
                    // If location is valid and not the same as previously known location
                    if ((location != null) && (location.getAccuracy() > 0) &&
                        (location.getTime() != mLocation.getTime())) {
                        mLocation.set(location);
                        updateStatus(AVAILABLE);
                    } else {
                        // Location is unavailable
                        clearLocation();
                    } 

                    // Even if no location is available, network request could have succeeded
                    if (networkSuccessful) {
                        mLastSuccessfulNetworkQueryTime = SystemClock.elapsedRealtime();
                    }

                }
            });
        } catch(Exception e) {
            Log.e(TAG, "updateLocation got exception:", e);
        }
    }

    public interface Callback {

        /**
         * Callback function to notify of a received network location
         *
         * @param location location object that is received. may be null if not a valid location
         * @param successful true if network query was successful, even if no location was found
         */
        void locationReceived(Location location, boolean successful);
    }

    private void log(String log) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, log);
        }
    }

}
