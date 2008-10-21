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
import java.util.List;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Listens for GPS and cell/wifi changes and anonymously uploads to server for
 * improving quality of service of NetworkLocationProvider. This service is only enabled when
 * the user has enabled the network location provider.
 *
 * {@hide}
 */
public class LocationCollector {

    private static final String TAG = "LocationCollector";

    // last location valid for 12 minutes
    private static final long MIN_VALID_LOCATION_TIME = 12 * 60 * 1000L;

    // don't send wifi more than every 10 min
    private static final long MIN_TIME_BETWEEN_WIFI_REPORTS = 10 * 60 * 1000L;

    // atleast 5 changed APs for wifi collection
    private static final int MIN_CHANGED_WIFI_POINTS = 5;

    // don't collect if distance moved less than 200 meters
    private static final int MIN_DISTANCE_BETWEEN_REPORTS = 200;

    // don't collect if battery level less than 20%
    private static final double MIN_BATTERY_LEVEL = 0.2;

    // if battery level is greater than 90% and plugged in, collect more frequently
    private static final double CHARGED_BATTERY_LEVEL = 0.9;

    // collect bursts every 15 minutes (running on battery)
    private static final long BURST_REST_TIME_ON_BATTERY = 15 * 60 * 1000L;

    // collect bursts every 8 minutes (when plugged in)
    private static final long BURST_REST_TIME_PLUGGED = 8 * 60 * 1000L;

    // collect burst samples every 12 seconds
    private static final int BURST_MEASUREMENT_INTERVAL = 12 * 1000;

    // collect 11 burst samples before resting (11 samples every 12 seconds = 2 minute bursts)
    private static final int BURST_NUM_SAMPLES = 11;

    // don't collect bursts if user in same loc for 2 bursts
    private static final int MAX_BURSTS_FROM_SAME_LOCATION = 2;

    // don't send more than 2 bursts if user hasn't moved more than 25 meters
    private static final int MIN_DISTANCE_BETWEEN_BURSTS = 25;

    // Cell State
    private CellState mCellState = null;
    private CellUploads mCellUploads = new CellUploads();

    // GPS state
    private Location mLastKnownLocation = null;
    private Location mLastUploadedLocation = null;
    private long mLastKnownLocationTime = 0;
    private long mLastUploadedLocationTime = 0;

    // Burst state
    private Location mLastBurstLocation = null;
    private long mLastBurstEndTime = 0;
    private long mCurrentBurstStartTime = 0;
    private int mCurrentBurstNumSamples = 0;
    private int mNumBurstsFromLastLocation = 0;

    // WiFi state
    private List<ScanResult> mWifiLastScanResults = null;
    private List<ScanResult> mWifiCurrentScanResults = null;
    private long mLastWifiScanElapsedTime = 0;
    private long mLastWifiScanRealTime = 0;
    private boolean mWifiUploadedWithoutLocation = false;

    // Collection state
    private boolean mNetworkProviderIsEnabled = true;
    private boolean mBatteryLevelIsHealthy = true;
    private boolean mBatteryChargedAndPlugged = false;

    // Location masf service
    private LocationMasfClient mMasfClient;

    public LocationCollector(LocationMasfClient masfClient) {
        mMasfClient = masfClient;
    }

    /**
     * Updates cell tower state. This is usually always up to date so should be uploaded
     * each time a new location is available.
     *
     * @param newState cell state
     */
    public synchronized void updateCellState(CellState newState) {
        if (newState == null) {
            throw new IllegalArgumentException("cell state is null");
        }

        if (!newState.isValid()) {
            return;
        }

        if (mCellState != null && mCellState.equals(newState)) {
            return;
        }

        mCellState = newState;
        log("updateCellState(): Updated to " + mCellState.getCid() + "," + mCellState.getLac());

        if (isCollectionEnabled()) {
            addToQueue(GDebugProfile.TRIGGER_CELL_CHANGE);
        }
    }

    /**
     * Updates GPS location if collection is enabled
     *
     * @param location location object
     */
    public synchronized void updateLocation(Location location) {

        // Don't do anything if collection is disabled
        if (!isCollectionEnabled()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        // Update last known location
        if (mLastKnownLocation == null) {
            mLastKnownLocation = new Location(location);
        } else {
            mLastKnownLocation.set(location);
        }
        mLastKnownLocationTime = now;

        // Burst rest time depends on battery state
        long restTime = BURST_REST_TIME_ON_BATTERY;
        if (mBatteryChargedAndPlugged) {
            restTime = BURST_REST_TIME_PLUGGED;
        }

        int trigger;

        // In burst mode if either first burst or enough time has passed since last burst
        if (mLastBurstEndTime == 0 || (now - mLastBurstEndTime > restTime)) {

            // If location is too recent, then don't do anything!
            if (now - mLastUploadedLocationTime < BURST_MEASUREMENT_INTERVAL) {
                return;
            }

            int distanceFromLastBurst = -1;
            if (mLastBurstLocation != null) {
                distanceFromLastBurst = (int) mLastBurstLocation.distanceTo(location);

                // Too many bursts from same location, don't upload
                if (distanceFromLastBurst < MIN_DISTANCE_BETWEEN_BURSTS &&
                    mNumBurstsFromLastLocation >= MAX_BURSTS_FROM_SAME_LOCATION) {
                    log("NO UPLOAD: Too many bursts from same location.");
                    return;
                }
            }

            if (mCurrentBurstStartTime == 0) {
                // Start the burst!
                mCurrentBurstStartTime = now;
                mCurrentBurstNumSamples = 1;
                trigger = GDebugProfile.TRIGGER_COLLECTION_START_BURST;

            } else if (now - mCurrentBurstStartTime > restTime) {
                // Burst got old, start a new one
                mCurrentBurstStartTime = now;
                mCurrentBurstNumSamples = 1;
                trigger = GDebugProfile.TRIGGER_COLLECTION_RESTART_BURST;

            } else if (mCurrentBurstNumSamples == BURST_NUM_SAMPLES - 1) {
                // Finished a burst
                mLastBurstEndTime = now;
                mCurrentBurstStartTime = 0;
                mCurrentBurstNumSamples = 0;

                // Make sure we don't upload too many bursts from same location
                if (mLastBurstLocation == null) {
                    mLastBurstLocation = new Location(location);
                    mNumBurstsFromLastLocation = 1;
                    trigger = GDebugProfile.TRIGGER_COLLECTION_END_BURST;

                } else {

                    if (distanceFromLastBurst != -1 &&
                        distanceFromLastBurst < MIN_DISTANCE_BETWEEN_BURSTS) {
                        // User hasnt moved much from last location, keep track of count,
                        // don't update last burst loc
                        mNumBurstsFromLastLocation++;
                        trigger = GDebugProfile.TRIGGER_COLLECTION_END_BURST_AT_SAME_LOCATION;

                    } else {
                        // User has moved enough, update last burst loc
                        mLastBurstLocation.set(location);
                        mNumBurstsFromLastLocation = 1;
                        trigger = GDebugProfile.TRIGGER_COLLECTION_END_BURST;
                    }
                }

            } else {
                // Increment burst sample count
                mCurrentBurstNumSamples++;
                trigger = GDebugProfile.TRIGGER_COLLECTION_CONTINUE_BURST;
            }

        } else if (mLastUploadedLocation != null
            && (mLastUploadedLocation.distanceTo(location) > MIN_DISTANCE_BETWEEN_REPORTS)) {
            // If not in burst mode but has moved a reasonable distance, upload!
            trigger = GDebugProfile.TRIGGER_COLLECTION_MOVED_DISTANCE;

        } else {
            // Not in burst mode or hasn't moved enough
            log("NO UPLOAD: Not in burst or moving mode. Resting for " + restTime + " ms");
            return;
        }

        log("updateLocation(): Updated location with trigger " + trigger);
        addToQueue(trigger);
    }

    /**
     * Updates wifi scan results if collection is enabled
     *
     * @param currentScanResults scan results
     */
    public synchronized void updateWifiScanResults(List<ScanResult> currentScanResults) {
        if (!isCollectionEnabled()) {
            return;
        }

        if (currentScanResults == null || currentScanResults.size() == 0) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        // If wifi scan recently received, then don't upload
        if ((mLastWifiScanElapsedTime != 0)
            && ((now - mLastWifiScanElapsedTime) <= MIN_TIME_BETWEEN_WIFI_REPORTS)) {
            return;
        }

        if (mWifiCurrentScanResults == null) {
            mWifiCurrentScanResults = new ArrayList<ScanResult>();
        } else {
            mWifiCurrentScanResults.clear();
        }
        mWifiCurrentScanResults.addAll(currentScanResults);

        // If wifi has changed enough
        boolean wifiHasChanged = false;

        if (mWifiLastScanResults == null) {
            wifiHasChanged = true;
        } else {
            // Calculate the number of new AP points received
            HashSet<String> previous = new HashSet<String>();
            HashSet<String> current = new HashSet<String>();
            for (ScanResult s : mWifiLastScanResults) {
                previous.add(s.BSSID);
            }
            for (ScanResult s : mWifiCurrentScanResults) {
                current.add(s.BSSID);
            }
            current.removeAll(previous);

            if (current.size() >
                Math.min(MIN_CHANGED_WIFI_POINTS, ((mWifiCurrentScanResults.size()+1)/2))) {
                wifiHasChanged = true;
            }
        }

        if (!wifiHasChanged) {
            log("updateWifiScanResults(): Wifi results haven't changed much");
            return;
        }

        if (mWifiLastScanResults == null) {
            mWifiLastScanResults = new ArrayList<ScanResult>();
        } else {
            mWifiLastScanResults.clear();
        }
        mWifiLastScanResults.addAll(mWifiCurrentScanResults);

        mLastWifiScanElapsedTime = now;
        mLastWifiScanRealTime = System.currentTimeMillis();

        log("updateWifiScanResults(): Updated " + mWifiLastScanResults.size() + " APs");
        addToQueue(GDebugProfile.TRIGGER_WIFI_CHANGE);
    }

    /**
     * Updates the status of the network location provider.
     *
     * @param enabled true if user has enabled network location based on Google's database
     * of wifi points and cell towers.
     */
    public void updateNetworkProviderStatus(boolean enabled) {
        mNetworkProviderIsEnabled = enabled;
    }

    /**
     * Updates the battery health. Battery level is healthy if there is greater than
     * {@link #MIN_BATTERY_LEVEL} percentage left or if the device is plugged in
     *
     * @param scale maximum scale for battery
     * @param level current level
     * @param plugged true if device is plugged in
     */
    public void updateBatteryState(int scale, int level, boolean plugged) {
        mBatteryLevelIsHealthy = (plugged || (level >= (MIN_BATTERY_LEVEL * scale)));
        mBatteryChargedAndPlugged = (plugged && (level >= (CHARGED_BATTERY_LEVEL * scale)));
    }

    /**
     * Anonymous data collection is only enabled when the user has enabled the network
     * location provider, i.e. is making use of the service and if the device battery level
     * is healthy.
     *
     * Additionally, data collection will *never* happen if the system
     * property ro.com.google.enable_google_location_features is not set.
     *
     * @return true if anonymous location collection is enabled
     */
    private boolean isCollectionEnabled() {
        // This class provides a Google-specific location feature, so it's enabled only
        // when the system property ro.com.google.enable_google_location_features is set.
        if (!SystemProperties.get("ro.com.google.enable_google_location_features").equals("1")) {
            return false;
        }
        return mBatteryLevelIsHealthy && mNetworkProviderIsEnabled;
    }

    /**
     * Adds to the MASF request queue
     *
     * @param trigger the event that triggered this collection event
     */
    private synchronized void addToQueue(int trigger) {

        long now = SystemClock.elapsedRealtime();

        // Include location if:
        // It has been received in the last 12 minutes.
        boolean includeLocation = false;
        if (mLastKnownLocation != null &&
            (now - mLastKnownLocationTime <= MIN_VALID_LOCATION_TIME)) {
            includeLocation = true;
        }

        // Include wifi if:
        // Wifi is new OR
        // Wifi is old but last wifi upload was without location
        boolean includeWifi = false;
        if (trigger == GDebugProfile.TRIGGER_WIFI_CHANGE || (mWifiUploadedWithoutLocation &&
                includeLocation && (now - mLastWifiScanElapsedTime < MIN_VALID_LOCATION_TIME))) {
            includeWifi = true;
            mWifiUploadedWithoutLocation = !includeLocation;
        }

        // Include cell if:
        // Wifi or location information is already being included
        // The cell hasn't been uploaded with the same location recently
        boolean includeCell = false;

        if (mCellState != null && (includeWifi || includeLocation)) {
            includeCell = true;

            if (!includeWifi && includeLocation) {
                if (mCellUploads.contains(mCellState, mLastKnownLocation)) {
                    includeCell = false;
                }
            } 
        }        

        if (!includeLocation && !includeWifi) {
            log("NO UPLOAD: includeLocation=false, includeWifi=false");
            return;
        } else if (!includeCell && trigger == GDebugProfile.TRIGGER_CELL_CHANGE) {
            log("NO UPLOAD: includeCell=false");
            return;
        } else {
            log("UPLOAD: includeLocation=" + includeLocation + ", includeWifi=" +
                includeWifi + ", includeCell=" + includeCell);
        }

        if (includeLocation) {
            // Update last uploaded location
            if (mLastUploadedLocation == null) {
                mLastUploadedLocation = new Location(mLastKnownLocation);
            } else {
                mLastUploadedLocation.set(mLastKnownLocation);
            }
            mLastUploadedLocationTime = now;
        }

        // Immediately send output if finishing a burst for live traffic requirements
        boolean immediate = false;
        if (trigger == GDebugProfile.TRIGGER_COLLECTION_END_BURST||
            trigger == GDebugProfile.TRIGGER_COLLECTION_END_BURST_AT_SAME_LOCATION) {
            immediate = true;
        }

        try {
            CellState cell = includeCell ? mCellState : null;
            List<ScanResult> wifi = includeWifi ? mWifiLastScanResults : null;
            Location loc = includeLocation ? mLastUploadedLocation : null;

            mMasfClient.queueCollectionReport(
                trigger, loc, cell, wifi, mLastWifiScanRealTime, immediate);

        } catch(Exception e) {
            Log.e(TAG, "addToQueue got exception:", e);
        }
    }

    private class CellUploads {

        private final int MIN_DISTANCE = MIN_DISTANCE_BETWEEN_REPORTS / 4; // 50 meters
        private final int SIZE = 5;
        private final String[] cells = new String[SIZE];
        private final boolean[] valid = new boolean[SIZE];
        private final double[] latitudes = new double[SIZE];
        private final double[] longitudes = new double[SIZE];
        private final float[] distance = new float[1];
        private int index = 0;

        private CellUploads() {
            for (int i = 0; i < SIZE; i++) {
                valid[i] = false;
            }
        }

        private boolean contains(CellState cellState, Location loc) {
            String cell =
                cellState.getCid() + ":" + cellState.getLac() + ":" +
                cellState.getMnc() + ":" + cellState.getMcc();
            double lat = loc.getLatitude();
            double lng = loc.getLongitude();

            for (int i = 0; i < SIZE; i++) {
                if (valid[i] && cells[i].equals(cell)) {
                    Location.distanceBetween(latitudes[i], longitudes[i], lat, lng, distance);
                    if (distance[0] < MIN_DISTANCE) {
                        return true;
                    }
                }
            }
            cells[index] = cell;
            latitudes[index] = lat;
            longitudes[index] = lng;
            valid[index] = true;

            index++;
            if (index == SIZE) {
                index = 0;
            }
            return false;
        }
    }

    private void log(String string) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, string);
        }
    }
}
