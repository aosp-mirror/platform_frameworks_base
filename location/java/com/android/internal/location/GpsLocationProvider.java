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

package com.android.internal.location;

import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.IGpsStatusListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationProviderImpl;
import android.net.SntpClient;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

/**
 * A GPS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GpsLocationProvider extends LocationProviderImpl {

    private static final String TAG = "GpsLocationProvider";
    
    /**
     * Broadcast intent action indicating that the GPS has either been
     * enabled or disabled. An intent extra provides this state as a boolean,
     * where {@code true} means enabled.
     * @see #EXTRA_ENABLED
     *
     * {@hide}
     */
    public static final String GPS_ENABLED_CHANGE_ACTION =
        "android.location.GPS_ENABLED_CHANGE";

    /**
     * Broadcast intent action indicating that the GPS has either started or
     * stopped receiving GPS fixes. An intent extra provides this state as a
     * boolean, where {@code true} means that the GPS is actively receiving fixes.
     * @see #EXTRA_ENABLED
     *
     * {@hide}
     */
    public static final String GPS_FIX_CHANGE_ACTION =
        "android.location.GPS_FIX_CHANGE";

    /**
     * The lookup key for a boolean that indicates whether GPS is enabled or
     * disabled. {@code true} means GPS is enabled. Retrieve it with
     * {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     *
     * {@hide}
     */
    public static final String EXTRA_ENABLED = "enabled";

    // these need to match GpsStatusValue defines in gps.h
    private static final int GPS_STATUS_NONE = 0;
    private static final int GPS_STATUS_SESSION_BEGIN = 1;
    private static final int GPS_STATUS_SESSION_END = 2;
    private static final int GPS_STATUS_ENGINE_ON = 3;
    private static final int GPS_STATUS_ENGINE_OFF = 4;

    // these need to match GpsLocationFlags enum in gps.h
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_ACCURACY = 16;
    
// IMPORTANT - the GPS_DELETE_* symbols here must match constants in GpsLocationProvider.java
    private static final int GPS_DELETE_EPHEMERIS = 0x0001;
    private static final int GPS_DELETE_ALMANAC = 0x0002;
    private static final int GPS_DELETE_POSITION = 0x0004;
    private static final int GPS_DELETE_TIME = 0x0008;
    private static final int GPS_DELETE_IONO = 0x0010;
    private static final int GPS_DELETE_UTC = 0x0020;
    private static final int GPS_DELETE_HEALTH = 0x0040;
    private static final int GPS_DELETE_SVDIR = 0x0080;
    private static final int GPS_DELETE_SVSTEER = 0x0100;
    private static final int GPS_DELETE_SADATA = 0x0200;
    private static final int GPS_DELETE_RTI = 0x0400;
    private static final int GPS_DELETE_CELLDB_INFO = 0x8000;
    private static final int GPS_DELETE_ALL = 0xFFFF;

    private static final String PROPERTIES_FILE = "/etc/gps.conf";

    private int mLocationFlags = LOCATION_INVALID;

    // current status
    private int mStatus = TEMPORARILY_UNAVAILABLE;

    // time for last status update
    private long mStatusUpdateTime = SystemClock.elapsedRealtime();
    
    // turn off GPS fix icon if we haven't received a fix in 10 seconds
    private static final long RECENT_FIX_TIMEOUT = 10 * 1000;

    // true if we are enabled
    private boolean mEnabled;
    // true if we are enabled for location updates
    private boolean mLocationTracking;
    
    // true if we have network connectivity
    private boolean mNetworkAvailable;

    // true if GPS is navigating
    private boolean mNavigating;
    
    // requested frequency of fixes, in seconds
    private int mFixInterval = 1;

    // true if we started navigation
    private boolean mStarted;

    // for calculating time to first fix
    private long mFixRequestTime = 0;
    // time to first fix for most recent session
    private int mTTFF = 0;
    // time we received our last fix
    private long mLastFixTime;

    // properties loaded from PROPERTIES_FILE
    private Properties mProperties;
    private String mNtpServer;

    private Context mContext;
    private Location mLocation = new Location(LocationManager.GPS_PROVIDER);
    private Bundle mLocationExtras = new Bundle();
    private ArrayList<Listener> mListeners = new ArrayList<Listener>();
    private GpsEventThread mEventThread;
    private GpsNetworkThread mNetworkThread;
    private Object mNetworkThreadLock = new Object();
   
    // how often to request NTP time, in milliseconds
    // current setting 4 hours
    private static final long NTP_INTERVAL = 4*60*60*1000; 
    // how long to wait if we have a network error in NTP or XTRA downloading
    // current setting - 5 minutes
    private static final long RETRY_INTERVAL = 5*60*1000; 

    private LocationCollector mCollector;

    public static boolean isSupported() {
        return native_is_supported();
    }

    public GpsLocationProvider(Context context, LocationCollector collector) {
        super(LocationManager.GPS_PROVIDER);
        mContext = context;
        mCollector = collector;

        mProperties = new Properties();
        try {
            File file = new File(PROPERTIES_FILE);
            FileInputStream stream = new FileInputStream(file);
            mProperties.load(stream);
            stream.close();
            mNtpServer = mProperties.getProperty("NTP_SERVER", null);
        } catch (IOException e) {
            Log.e(TAG, "Could not open GPS configuration file " + PROPERTIES_FILE, e);
        }
    }

    /**
     * Returns true if the provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    @Override
    public boolean requiresNetwork() {
        // We want updateNetworkState() to get called when the network state changes
        // for XTRA and NTP time injection support.
        return (mNtpServer != null || native_supports_xtra());
    }

    public void updateNetworkState(int state) {
        mNetworkAvailable = (state == LocationProvider.AVAILABLE);

        if (Config.LOGD) {
            Log.d(TAG, "updateNetworkState " + (mNetworkAvailable ? "available" : "unavailable"));
        }
        
        if (mNetworkAvailable && mNetworkThread != null && mEnabled) {
            // signal the network thread when the network becomes available
            mNetworkThread.signal();
        } 
    }

    /**
     * Returns true if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    @Override
    public boolean requiresSatellite() {
        return true;
    }

    /**
     * Returns true if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    @Override
    public boolean requiresCell() {
        return false;
    }

    /**
     * Returns true if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information.
     */
    @Override
    public boolean hasMonetaryCost() {
        return false;
    }

    /**
     * Returns true if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    @Override
    public boolean supportsAltitude() {
        return true;
    }

    /**
     * Returns true if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    @Override
    public boolean supportsSpeed() {
        return true;
    }

    /**
     * Returns true if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    @Override
    public boolean supportsBearing() {
        return true;
    }

    /**
     * Returns the power requirement for this provider.
     *
     * @return the power requirement for this provider, as one of the
     * constants Criteria.POWER_REQUIREMENT_*.
     */
    @Override
    public int getPowerRequirement() {
        return Criteria.POWER_HIGH;
    }

    /**
     * Returns the horizontal accuracy of this provider
     *
     * @return the accuracy of location from this provider, as one
     * of the constants Criteria.ACCURACY_*.
     */
    @Override
    public int getAccuracy() {
        return Criteria.ACCURACY_FINE;
    }

    /**
     * Enables this provider.  When enabled, calls to getStatus()
     * and getLocation() must be handled.  Hardware may be started up
     * when the provider is enabled.
     */
    @Override
    public synchronized void enable() {
        if (Config.LOGD) Log.d(TAG, "enable");
        if (mEnabled) return;
        mEnabled = native_init();

        if (mEnabled) {
            // run event listener thread while we are enabled
            mEventThread = new GpsEventThread();
            mEventThread.start();

            if (requiresNetwork()) {
                // run network thread for NTP and XTRA support
                if (mNetworkThread == null) {
                    mNetworkThread = new GpsNetworkThread();
                    mNetworkThread.start();
                } else {
                    mNetworkThread.signal();
                }
            }
        } else {
            Log.w(TAG, "Failed to enable location provider");
        }
    }

    /**
     * Disables this provider.  When disabled, calls to getStatus()
     * and getLocation() need not be handled.  Hardware may be shut
     * down while the provider is disabled.
     */
    @Override
    public synchronized void disable() {
        if (Config.LOGD) Log.d(TAG, "disable");
        if (!mEnabled) return;

        mEnabled = false;
        stopNavigating();
        native_disable();

        // make sure our event thread exits
        if (mEventThread != null) {
            try {
                mEventThread.join();
            } catch (InterruptedException e) {
                Log.w(TAG, "InterruptedException when joining mEventThread");
            }
            mEventThread = null;
        }

        if (mNetworkThread != null) {
            mNetworkThread.setDone();
            mNetworkThread = null;
        }

        native_cleanup();
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public int getStatus(Bundle extras) {
        if (extras != null) {
            extras.putInt("satellites", mSvCount);
        }
        return mStatus;
    }

    private void updateStatus(int status, int svCount) {
        if (status != mStatus || svCount != mSvCount) {
            mStatus = status;
            mSvCount = svCount;
            mLocationExtras.putInt("satellites", svCount);
            mStatusUpdateTime = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    @Override
    public boolean getLocation(Location l) {
        synchronized (mLocation) {
            // don't report locations without latitude and longitude
            if ((mLocationFlags & LOCATION_HAS_LAT_LONG) == 0) {
                return false;
            }
            l.set(mLocation);
            l.setExtras(mLocationExtras);
            return true;
        }
    }

    @Override
    public void enableLocationTracking(boolean enable) {
        if (mLocationTracking == enable) {
            return;
        }

        if (enable) {
            mFixRequestTime = System.currentTimeMillis();
            mTTFF = 0;
            mLastFixTime = 0;
            startNavigating();
        } else {
            stopNavigating();
        }
        mLocationTracking = enable;
    }

    @Override
    public boolean isLocationTracking() {
        return mLocationTracking;
    }

    @Override
    public void setMinTime(long minTime) {
        super.setMinTime(minTime);
        if (Config.LOGD) Log.d(TAG, "setMinTime " + minTime);
        
        if (minTime >= 0) {
            int interval = (int)(minTime/1000);
            if (interval < 1) {
                interval = 1;
            }
            mFixInterval = interval;
            native_set_fix_frequency(mFixInterval);
        }
    }

    private final class Listener implements IBinder.DeathRecipient {
        final IGpsStatusListener mListener;
        
        int mSensors = 0;
        
        Listener(IGpsStatusListener listener) {
            mListener = listener;
        }
        
        public void binderDied() {
            if (Config.LOGD) Log.d(TAG, "GPS status listener died");

            synchronized(mListeners) {
                mListeners.remove(this);
            }
        }
    }

    public void addGpsStatusListener(IGpsStatusListener listener) throws RemoteException {        
        if (listener == null) throw new NullPointerException("listener is null in addGpsStatusListener");

        synchronized(mListeners) {
            IBinder binder = listener.asBinder();
            int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                Listener test = mListeners.get(i);
                if (binder.equals(test.mListener.asBinder())) {
                    // listener already added
                    return;
                }
            }

            Listener l = new Listener(listener);
            binder.linkToDeath(l, 0);
            mListeners.add(l);
        }
    }
    
    public void removeGpsStatusListener(IGpsStatusListener listener) {
        if (listener == null) throw new NullPointerException("listener is null in addGpsStatusListener");

        synchronized(mListeners) {        
            IBinder binder = listener.asBinder();
            Listener l = null;
            int size = mListeners.size();
            for (int i = 0; i < size && l == null; i++) {
                Listener test = mListeners.get(i);
                if (binder.equals(test.mListener.asBinder())) {
                    l = test;
                }
            }

            if (l != null) {
                mListeners.remove(l);
                binder.unlinkToDeath(l, 0);
            }
        }
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        
        if ("delete_aiding_data".equals(command)) {
            return deleteAidingData(extras);
        }
        
        Log.w(TAG, "sendExtraCommand: unknown command " + command);
        return false;
    }

    private boolean deleteAidingData(Bundle extras) {
        int flags;

        if (extras == null) {
            flags = GPS_DELETE_ALL;
        } else {
            flags = 0;
            if (extras.getBoolean("ephemeris")) flags |= GPS_DELETE_EPHEMERIS;
            if (extras.getBoolean("almanac")) flags |= GPS_DELETE_ALMANAC;
            if (extras.getBoolean("position")) flags |= GPS_DELETE_POSITION;
            if (extras.getBoolean("time")) flags |= GPS_DELETE_TIME;
            if (extras.getBoolean("iono")) flags |= GPS_DELETE_IONO;
            if (extras.getBoolean("utc")) flags |= GPS_DELETE_UTC;
            if (extras.getBoolean("health")) flags |= GPS_DELETE_HEALTH;
            if (extras.getBoolean("svdir")) flags |= GPS_DELETE_SVDIR;
            if (extras.getBoolean("svsteer")) flags |= GPS_DELETE_SVSTEER;
            if (extras.getBoolean("sadata")) flags |= GPS_DELETE_SADATA;
            if (extras.getBoolean("rti")) flags |= GPS_DELETE_RTI;
            if (extras.getBoolean("celldb-info")) flags |= GPS_DELETE_CELLDB_INFO;
            if (extras.getBoolean("all")) flags |= GPS_DELETE_ALL;
        }

        if (flags != 0) {
            native_delete_aiding_data(flags);
            return true;
        }

        return false;
    }

    public void startNavigating() {
        if (!mStarted) {
            if (Config.LOGV) Log.v(TAG, "startNavigating");
            mStarted = true;
            if (!native_start(false, mFixInterval)) {
                mStarted = false;
                Log.e(TAG, "native_start failed in startNavigating()");
            }

            // reset SV count to zero
            updateStatus(TEMPORARILY_UNAVAILABLE, 0);
        }
    }

    public void stopNavigating() {
        if (Config.LOGV) Log.v(TAG, "stopNavigating");
        if (mStarted) {
            mStarted = false;
            native_stop();
            mTTFF = 0;
            mLastFixTime = 0;
            mLocationFlags = LOCATION_INVALID;

            // reset SV count to zero
            updateStatus(TEMPORARILY_UNAVAILABLE, 0);
        }
    }

    /**
     * called from native code to update our position.
     */
    private void reportLocation(int flags, double latitude, double longitude, double altitude,
            float speed, float bearing, float accuracy, long timestamp) {
        if (Config.LOGV) Log.v(TAG, "reportLocation lat: " + latitude + " long: " + longitude +
                " timestamp: " + timestamp);

        mLastFixTime = System.currentTimeMillis();
        // report time to first fix
        if (mTTFF == 0 && (flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
            mTTFF = (int)(mLastFixTime - mFixRequestTime);
            if (Config.LOGD) Log.d(TAG, "TTFF: " + mTTFF);

            // notify status listeners
            synchronized(mListeners) {
                int size = mListeners.size();
                for (int i = 0; i < size; i++) {
                    Listener listener = mListeners.get(i);
                    try {
                        listener.mListener.onFirstFix(mTTFF); 
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException in stopNavigating");
                        mListeners.remove(listener);
                        // adjust for size of list changing
                        size--;
                    }
                }
            }
        }

        synchronized (mLocation) {
            mLocationFlags = flags;
            if ((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
                mLocation.setLatitude(latitude);
                mLocation.setLongitude(longitude);
                mLocation.setTime(timestamp);
            }
            if ((flags & LOCATION_HAS_ALTITUDE) == LOCATION_HAS_ALTITUDE) {
                mLocation.setAltitude(altitude);
            } else {
                mLocation.removeAltitude();
            }
            if ((flags & LOCATION_HAS_SPEED) == LOCATION_HAS_SPEED) {
                mLocation.setSpeed(speed);
            } else {
                mLocation.removeSpeed();
            }
            if ((flags & LOCATION_HAS_BEARING) == LOCATION_HAS_BEARING) {
                mLocation.setBearing(bearing);
            } else {
                mLocation.removeBearing();
            }
            if ((flags & LOCATION_HAS_ACCURACY) == LOCATION_HAS_ACCURACY) {
                mLocation.setAccuracy(accuracy);
            } else {
                mLocation.removeAccuracy();
            }

            // Send to collector
            if ((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
                mCollector.updateLocation(mLocation);
            }
        }

        if (mStarted && mStatus != AVAILABLE) {
            // send an intent to notify that the GPS is receiving fixes.
            Intent intent = new Intent(GPS_FIX_CHANGE_ACTION);
            intent.putExtra(EXTRA_ENABLED, true);
            mContext.sendBroadcast(intent);
            updateStatus(AVAILABLE, mSvCount);
        }
   }

    /**
     * called from native code to update our status
     */
    private void reportStatus(int status) {
        if (Config.LOGV) Log.v(TAG, "reportStatus status: " + status);

        boolean wasNavigating = mNavigating;
        mNavigating = (status == GPS_STATUS_SESSION_BEGIN || status == GPS_STATUS_ENGINE_ON);

        if (wasNavigating != mNavigating) {
            synchronized(mListeners) {
                int size = mListeners.size();
                for (int i = 0; i < size; i++) {
                    Listener listener = mListeners.get(i);
                    try {
                        if (mNavigating) {
                            listener.mListener.onGpsStarted(); 
                        } else {
                            listener.mListener.onGpsStopped(); 
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException in reportStatus");
                        mListeners.remove(listener);
                        // adjust for size of list changing
                        size--;
                    }
                }
            }

            // send an intent to notify that the GPS has been enabled or disabled.
            Intent intent = new Intent(GPS_ENABLED_CHANGE_ACTION);
            intent.putExtra(EXTRA_ENABLED, mNavigating);
            mContext.sendBroadcast(intent);
        }
    }

    /**
     * called from native code to update SV info
     */
    private void reportSvStatus() {

        int svCount = native_read_sv_status(mSvs, mSnrs, mSvElevations, mSvAzimuths, mSvMasks);
        
        synchronized(mListeners) {
            int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                Listener listener = mListeners.get(i);
                try {
                    listener.mListener.onSvStatusChanged(svCount, mSvs, mSnrs, 
                            mSvElevations, mSvAzimuths, mSvMasks[EPHEMERIS_MASK], 
                            mSvMasks[ALMANAC_MASK], mSvMasks[USED_FOR_FIX_MASK]); 
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException in reportSvInfo");
                    mListeners.remove(listener);
                    // adjust for size of list changing
                    size--;
                }
            }
        }

        if (Config.LOGD) {
            if (Config.LOGV) Log.v(TAG, "SV count: " + svCount +
                    " ephemerisMask: " + Integer.toHexString(mSvMasks[EPHEMERIS_MASK]) +
                    " almanacMask: " + Integer.toHexString(mSvMasks[ALMANAC_MASK]));
            for (int i = 0; i < svCount; i++) {
                if (Config.LOGV) Log.v(TAG, "sv: " + mSvs[i] +
                        " snr: " + (float)mSnrs[i]/10 +
                        " elev: " + mSvElevations[i] +
                        " azimuth: " + mSvAzimuths[i] +
                        ((mSvMasks[EPHEMERIS_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " E") +
                        ((mSvMasks[ALMANAC_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " A") +
                        ((mSvMasks[USED_FOR_FIX_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "" : "U"));
            }
        }

        updateStatus(mStatus, svCount);

        if (mNavigating && mStatus == AVAILABLE && mLastFixTime > 0 &&
            System.currentTimeMillis() - mLastFixTime > RECENT_FIX_TIMEOUT) {
            // send an intent to notify that the GPS is no longer receiving fixes.
            Intent intent = new Intent(GPS_FIX_CHANGE_ACTION);
            intent.putExtra(EXTRA_ENABLED, false);
            mContext.sendBroadcast(intent);
            updateStatus(TEMPORARILY_UNAVAILABLE, mSvCount);
        }
    }
    
    private void xtraDownloadRequest() {
        if (Config.LOGD) Log.d(TAG, "xtraDownloadRequest");
        if (mNetworkThread != null) {
            mNetworkThread.xtraDownloadRequest();
        }
    }

    private class GpsEventThread extends Thread {

        public GpsEventThread() {
            super("GpsEventThread");
        }

        public void run() {
            if (Config.LOGD) Log.d(TAG, "GpsEventThread starting");
            // thread exits after disable() is called and navigation has stopped
            while (mEnabled || mNavigating) {
                // this will wait for an event from the GPS,
                // which will be reported via reportLocation or reportStatus
                native_wait_for_event();
            }
            if (Config.LOGD) Log.d(TAG, "GpsEventThread exiting");
        }
    }

    private class GpsNetworkThread extends Thread {

        private long mNextNtpTime = 0;
        private long mNextXtraTime = 0;
        private boolean mXtraDownloadRequested = false;
        private boolean mDone = false;

        public GpsNetworkThread() {
            super("GpsNetworkThread");
        }

        public void run() {
            synchronized (mNetworkThreadLock) {
                if (!mDone) {
                    runLocked();
                }
            }
        }

        public void runLocked() {
            if (Config.LOGD) Log.d(TAG, "NetworkThread starting");
            
            SntpClient client = new SntpClient();
            GpsXtraDownloader xtraDownloader = null;
            
            if (native_supports_xtra()) {
                xtraDownloader = new GpsXtraDownloader(mContext, mProperties);
            }
            
            // thread exits after disable() is called
            while (!mDone) {
                long waitTime = getWaitTime();
                do {                        
                    synchronized (this) {
                        try {
                            if (!mNetworkAvailable) {
                                if (Config.LOGD) Log.d(TAG, 
                                        "NetworkThread wait for network");
                                wait();
                            } else if (waitTime > 0) {
                                if (Config.LOGD) {
                                    Log.d(TAG, "NetworkThread wait for " +
                                            waitTime + "ms");
                                }
                                wait(waitTime);
                            }
                        } catch (InterruptedException e) {
                            if (Config.LOGD) {
                                Log.d(TAG, "InterruptedException in GpsNetworkThread");
                            }
                        }
                    }
                    waitTime = getWaitTime();
                } while (!mDone && ((!mXtraDownloadRequested && waitTime > 0)
                        || !mNetworkAvailable));
                if (Config.LOGD) Log.d(TAG, "NetworkThread out of wake loop");
                
                if (!mDone) {
                    if (mNtpServer != null && 
                            mNextNtpTime <= System.currentTimeMillis()) {
                        if (Config.LOGD) {
                            Log.d(TAG, "Requesting time from NTP server " + mNtpServer);
                        }
                        if (client.requestTime(mNtpServer, 10000)) {
                            long time = client.getNtpTime();
                            long timeReference = client.getNtpTimeReference();
                            int certainty = (int)(client.getRoundTripTime()/2);
        
                            if (Config.LOGD) Log.d(TAG, "calling native_inject_time: " + 
                                    time + " reference: " + timeReference 
                                    + " certainty: " + certainty);
        
                            native_inject_time(time, timeReference, certainty);
                            mNextNtpTime = System.currentTimeMillis() + NTP_INTERVAL;
                        } else {
                            if (Config.LOGD) Log.d(TAG, "requestTime failed");
                            mNextNtpTime = System.currentTimeMillis() + RETRY_INTERVAL;
                        }
                    }

                    if ((mXtraDownloadRequested || 
                            (mNextXtraTime > 0 && mNextXtraTime <= System.currentTimeMillis()))
                            && xtraDownloader != null) {
                        byte[] data = xtraDownloader.downloadXtraData();
                        if (data != null) {
                            if (Config.LOGD) {
                                Log.d(TAG, "calling native_inject_xtra_data");
                            }
                            native_inject_xtra_data(data, data.length);
                            mNextXtraTime = 0;
                            mXtraDownloadRequested = false;
                        } else {
                            mNextXtraTime = System.currentTimeMillis() + RETRY_INTERVAL;
                        }
                    }
                }
            }
            if (Config.LOGD) Log.d(TAG, "NetworkThread exiting");
        }
        
        synchronized void xtraDownloadRequest() {
            mXtraDownloadRequested = true;
            notify();
        }

        synchronized void signal() {
            notify();
        }

        synchronized void setDone() {
            if (Config.LOGD) Log.d(TAG, "stopping NetworkThread");
            mDone = true;
            notify();
        }

        private long getWaitTime() {
            long now = System.currentTimeMillis();
            long waitTime = Long.MAX_VALUE;
            if (mNtpServer != null) {
                waitTime = mNextNtpTime - now;
            }
            if (mNextXtraTime != 0) {
                long xtraWaitTime = mNextXtraTime - now;
                if (xtraWaitTime < waitTime) {
                    waitTime = xtraWaitTime;
                }
            }
            if (waitTime < 0) {
                waitTime = 0;
            }
            return waitTime;
        }
    }

    // for GPS SV statistics
    private static final int MAX_SVS = 32;
    private static final int EPHEMERIS_MASK = 0;
    private static final int ALMANAC_MASK = 1;
    private static final int USED_FOR_FIX_MASK = 2;

    // preallocated arrays, to avoid memory allocation in reportStatus()
    private int mSvs[] = new int[MAX_SVS];
    private float mSnrs[] = new float[MAX_SVS];
    private float mSvElevations[] = new float[MAX_SVS];
    private float mSvAzimuths[] = new float[MAX_SVS];
    private int mSvMasks[] = new int[3];
    private int mSvCount;

    static { class_init_native(); }
    private static native void class_init_native();
    private static native boolean native_is_supported();

    private native boolean native_init();
    private native void native_disable();
    private native void native_cleanup();
    private native boolean native_start(boolean singleFix, int fixInterval);
    private native boolean native_stop();
    private native void native_set_fix_frequency(int fixFrequency);
    private native void native_delete_aiding_data(int flags);
    private native void native_wait_for_event();
    // returns number of SVs
    // mask[0] is ephemeris mask and mask[1] is almanac mask
    private native int native_read_sv_status(int[] svs, float[] snrs,
            float[] elevations, float[] azimuths, int[] masks);
    
    // XTRA Support    
    private native void native_inject_time(long time, long timeReference, int uncertainty);
    private native boolean native_supports_xtra();
    private native void native_inject_xtra_data(byte[] data, int length);
}
