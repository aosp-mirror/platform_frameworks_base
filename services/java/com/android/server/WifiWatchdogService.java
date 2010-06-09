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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiStateTracker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Config;
import android.util.Slog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;

/**
 * {@link WifiWatchdogService} monitors the initial connection to a Wi-Fi
 * network with multiple access points. After the framework successfully
 * connects to an access point, the watchdog verifies whether the DNS server is
 * reachable. If not, the watchdog blacklists the current access point, leading
 * to a connection on another access point within the same network.
 * <p>
 * The watchdog has a few safeguards:
 * <ul>
 * <li>Only monitor networks with multiple access points
 * <li>Only check at most {@link #getMaxApChecks()} different access points
 * within the network before giving up
 * <p>
 * The watchdog checks for connectivity on an access point by ICMP pinging the
 * DNS. There are settings that allow disabling the watchdog, or tweaking the
 * acceptable packet loss (and other various parameters).
 * <p>
 * The core logic of the watchdog is done on the main watchdog thread. Wi-Fi
 * callbacks can come in on other threads, so we must queue messages to the main
 * watchdog thread's handler. Most (if not all) state is only written to from
 * the main thread.
 * 
 * {@hide}
 */
public class WifiWatchdogService {
    private static final String TAG = "WifiWatchdogService";
    private static final boolean V = false || Config.LOGV;
    private static final boolean D = true || Config.LOGD;
    
    private Context mContext;
    private ContentResolver mContentResolver;
    private WifiStateTracker mWifiStateTracker;
    private WifiManager mWifiManager;
    
    /**
     * The main watchdog thread.
     */
    private WifiWatchdogThread mThread;
    /**
     * The handler for the main watchdog thread.
     */
    private WifiWatchdogHandler mHandler;

    private ContentObserver mContentObserver;

    /**
     * The current watchdog state. Only written from the main thread!
     */
    private WatchdogState mState = WatchdogState.IDLE;
    /**
     * The SSID of the network that the watchdog is currently monitoring. Only
     * touched in the main thread!
     */
    private String mSsid;
    /**
     * The number of access points in the current network ({@link #mSsid}) that
     * have been checked. Only touched in the main thread!
     */
    private int mNumApsChecked;
    /** Whether the current AP check should be canceled. */
    private boolean mShouldCancel;
    
    WifiWatchdogService(Context context, WifiStateTracker wifiStateTracker) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWifiStateTracker = wifiStateTracker;
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        
        createThread();
        
        // The content observer to listen needs a handler, which createThread creates
        registerForSettingsChanges();
        if (isWatchdogEnabled()) {
            registerForWifiBroadcasts();
        }
        
        if (V) {
            myLogV("WifiWatchdogService: Created");
        }
    }

    /**
     * Observes the watchdog on/off setting, and takes action when changed.
     */
    private void registerForSettingsChanges() {
        ContentResolver contentResolver = mContext.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_ON), false,
                mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (isWatchdogEnabled()) {
                    registerForWifiBroadcasts();
                } else {
                    unregisterForWifiBroadcasts();
                    if (mHandler != null) {
                        mHandler.disableWatchdog();
                    }
                }
            }
        });
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_ON
     */
    private boolean isWatchdogEnabled() {
        return Settings.Secure.getInt(mContentResolver, Settings.Secure.WIFI_WATCHDOG_ON, 1) == 1;
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_AP_COUNT
     */
    private int getApCount() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_AP_COUNT, 2);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT
     */
    private int getInitialIgnoredPingCount() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT , 2);
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_PING_COUNT
     */
    private int getPingCount() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_PING_COUNT, 4);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_PING_TIMEOUT_MS
     */
    private int getPingTimeoutMs() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS, 500);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_PING_DELAY_MS
     */
    private int getPingDelayMs() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_PING_DELAY_MS, 250);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE
     */
    private int getAcceptablePacketLossPercentage() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE, 25);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_MAX_AP_CHECKS
     */
    private int getMaxApChecks() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_MAX_AP_CHECKS, 7);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED
     */
    private boolean isBackgroundCheckEnabled() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED, 1) == 1;
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS
     */
    private int getBackgroundCheckDelayMs() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS, 60000);
    }
    
    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS
     */
    private int getBackgroundCheckTimeoutMs() {
        return Settings.Secure.getInt(mContentResolver,
            Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS, 1000);
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WATCH_LIST
     * @return the comma-separated list of SSIDs
     */
    private String getWatchList() {
        return Settings.Secure.getString(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WATCH_LIST);
    }
    
    /**
     * Registers to receive the necessary Wi-Fi broadcasts.
     */
    private void registerForWifiBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    /**
     * Unregisters from receiving the Wi-Fi broadcasts.
     */
    private void unregisterForWifiBroadcasts() {
        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Creates the main watchdog thread, including waiting for the handler to be
     * created.
     */
    private void createThread() {
        mThread = new WifiWatchdogThread();
        mThread.start();
        waitForHandlerCreation();
    }

    /**
     * Unregister broadcasts and quit the watchdog thread
     */
    private void quit() {
        unregisterForWifiBroadcasts();
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        mHandler.removeAllActions();
        mHandler.getLooper().quit();
    }

    /**
     * Waits for the main watchdog thread to create the handler.
     */
    private void waitForHandlerCreation() {
        synchronized(this) {
            while (mHandler == null) {
                try {
                    // Wait for the handler to be set by the other thread
                    wait();
                } catch (InterruptedException e) {
                    Slog.e(TAG, "Interrupted while waiting on handler.");
                }
            }
        }
    }

    // Utility methods
    
    /**
     * Logs with the current thread.
     */
    private static void myLogV(String message) {
        Slog.v(TAG, "(" + Thread.currentThread().getName() + ") " + message);
    }
    
    private static void myLogD(String message) {
        Slog.d(TAG, "(" + Thread.currentThread().getName() + ") " + message);
    }
    
    /**
     * Gets the DNS of the current AP.
     * 
     * @return The DNS of the current AP.
     */
    private int getDns() {
        DhcpInfo addressInfo = mWifiManager.getDhcpInfo();
        if (addressInfo != null) {
            return addressInfo.dns1;
        } else {
            return -1;
        }
    }
    
    /**
     * Checks whether the DNS can be reached using multiple attempts according
     * to the current setting values.
     * 
     * @return Whether the DNS is reachable
     */
    private boolean checkDnsConnectivity() {
        int dns = getDns();
        if (dns == -1) {
            if (V) {
                myLogV("checkDnsConnectivity: Invalid DNS, returning false");
            }
            return false;
        }
        
        if (V) {
            myLogV("checkDnsConnectivity: Checking 0x" +
                    Integer.toHexString(Integer.reverseBytes(dns)) + " for connectivity");
        }

        int numInitialIgnoredPings = getInitialIgnoredPingCount();
        int numPings = getPingCount();
        int pingDelay = getPingDelayMs();
        int acceptableLoss = getAcceptablePacketLossPercentage();
        
        /** See {@link Secure#WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT} */
        int ignoredPingCounter = 0;
        int pingCounter = 0;
        int successCounter = 0;
        
        // No connectivity check needed
        if (numPings == 0) {
            return true;
        }

        // Do the initial pings that we ignore
        for (; ignoredPingCounter < numInitialIgnoredPings; ignoredPingCounter++) {
            if (shouldCancel()) return false;

            boolean dnsAlive = DnsPinger.isDnsReachable(dns, getPingTimeoutMs());
            if (dnsAlive) {
                /*
                 * Successful "ignored" pings are *not* ignored (they count in the total number
                 * of pings), but failures are really ignored.
                 */
                pingCounter++;
                successCounter++;
            }
            
            if (V) {
                Slog.v(TAG, (dnsAlive ? "  +" : "  Ignored: -"));
            }

            if (shouldCancel()) return false;
            
            try {
                Thread.sleep(pingDelay);
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while pausing between pings", e);
            }
        }
        
        // Do the pings that we use to measure packet loss
        for (; pingCounter < numPings; pingCounter++) {
            if (shouldCancel()) return false;

            if (DnsPinger.isDnsReachable(dns, getPingTimeoutMs())) {
                successCounter++;
                if (V) {
                    Slog.v(TAG, "  +");
                }
            } else {
                if (V) {
                    Slog.v(TAG, "  -");
                }
            }

            if (shouldCancel()) return false;
            
            try {
                Thread.sleep(pingDelay);
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while pausing between pings", e);
            }
        }
        
        int packetLossPercentage = 100 * (numPings - successCounter) / numPings;
        if (D) {
            Slog.d(TAG, packetLossPercentage
                    + "% packet loss (acceptable is " + acceptableLoss + "%)");
        }
        
        return !shouldCancel() && (packetLossPercentage <= acceptableLoss);
    }

    private boolean backgroundCheckDnsConnectivity() {
        int dns = getDns();
        if (false && V) {
            myLogV("backgroundCheckDnsConnectivity: Background checking " + dns +
                    " for connectivity");
        }
        
        if (dns == -1) {
            if (V) {
                myLogV("backgroundCheckDnsConnectivity: DNS is empty, returning false");
            }
            return false;
        }
        
        return DnsPinger.isDnsReachable(dns, getBackgroundCheckTimeoutMs());
    }
    
    /**
     * Signals the current action to cancel.
     */
    private void cancelCurrentAction() {
        mShouldCancel = true;
    }
    
    /**
     * Helper to check whether to cancel. 
     * 
     * @return Whether to cancel processing the action.
     */
    private boolean shouldCancel() {
        if (V && mShouldCancel) {
            myLogV("shouldCancel: Cancelling");
        }
        
        return mShouldCancel;
    }
    
    // Wi-Fi initiated callbacks (could be executed in another thread)

    /**
     * Called when connected to an AP (this can be the next AP in line, or
     * it can be a completely different network).
     * 
     * @param ssid The SSID of the access point.
     * @param bssid The BSSID of the access point.
     */
    private void onConnected(String ssid, String bssid) {
        if (V) {
            myLogV("onConnected: SSID: " + ssid + ", BSSID: " + bssid);
        }

        /*
         * The current action being processed by the main watchdog thread is now
         * stale, so cancel it.
         */
        cancelCurrentAction();
        
        if ((mSsid == null) || !mSsid.equals(ssid)) {
            /*
             * This is a different network than what the main watchdog thread is
             * processing, dispatch the network change message on the main thread.
             */
            mHandler.dispatchNetworkChanged(ssid);
        }
        
        if (requiresWatchdog(ssid, bssid)) {
            if (D) {
                myLogD(ssid + " (" + bssid + ") requires the watchdog");
            }

            // This access point requires a watchdog, so queue the check on the main thread
            mHandler.checkAp(new AccessPoint(ssid, bssid));
            
        } else {
            if (D) {
                myLogD(ssid + " (" + bssid + ") does not require the watchdog");
            }

            // This access point does not require a watchdog, so queue idle on the main thread
            mHandler.idle();
        }
    }
    
    /**
     * Called when Wi-Fi is enabled.
     */
    private void onEnabled() {
        cancelCurrentAction();
        // Queue a hard-reset of the state on the main thread
        mHandler.reset();
    }
    
    /**
     * Called when disconnected (or some other event similar to being disconnected).
     */
    private void onDisconnected() {
        if (V) {
            myLogV("onDisconnected");
        }
        
        /*
         * Disconnected from an access point, the action being processed by the
         * watchdog thread is now stale, so cancel it.
         */
        cancelCurrentAction();
        // Dispatch the disconnected to the main watchdog thread
        mHandler.dispatchDisconnected();
        // Queue the action to go idle
        mHandler.idle();
    }

    /**
     * Checks whether an access point requires watchdog monitoring.
     * 
     * @param ssid The SSID of the access point.
     * @param bssid The BSSID of the access point.
     * @return Whether the access point/network should be monitored by the
     *         watchdog.
     */
    private boolean requiresWatchdog(String ssid, String bssid) {
        if (V) {
            myLogV("requiresWatchdog: SSID: " + ssid + ", BSSID: " + bssid);
        }
        
        WifiInfo info = null;
        if (ssid == null) {
            /*
             * This is called from a Wi-Fi callback, so assume the WifiInfo does
             * not have stale data.
             */
            info = mWifiManager.getConnectionInfo();
            ssid = info.getSSID();
            if (ssid == null) {
                // It's still null, give up
                if (V) {
                    Slog.v(TAG, "  Invalid SSID, returning false");
                }
                return false;
            }
        }
        
        if (TextUtils.isEmpty(bssid)) {
            // Similar as above
            if (info == null) {
                info = mWifiManager.getConnectionInfo();
            }
            bssid = info.getBSSID();
            if (TextUtils.isEmpty(bssid)) {
                // It's still null, give up
                if (V) {
                    Slog.v(TAG, "  Invalid BSSID, returning false");
                }
                return false;
            }
        }

        if (!isOnWatchList(ssid)) {
            if (V) {
                Slog.v(TAG, "  SSID not on watch list, returning false");
            }
            return false;
        }

        // The watchdog only monitors networks with multiple APs
        if (!hasRequiredNumberOfAps(ssid)) {
            return false;
        }

        return true;
    }

    private boolean isOnWatchList(String ssid) {
        String watchList;

        if (ssid == null || (watchList = getWatchList()) == null) {
            return false;
        }

        String[] list = watchList.split(" *, *");

        for (String name : list) {
            if (ssid.equals(name)) {
                return true;
            }
        }

        return false;
    }
    
    /**
     * Checks if the current scan results have multiple access points with an SSID.
     * 
     * @param ssid The SSID to check.
     * @return Whether the SSID has multiple access points.
     */
    private boolean hasRequiredNumberOfAps(String ssid) {
        List<ScanResult> results = mWifiManager.getScanResults();
        if (results == null) {
            if (V) {
                myLogV("hasRequiredNumberOfAps: Got null scan results, returning false");
            }
            return false;
        }
        
        int numApsRequired = getApCount();
        int numApsFound = 0;
        int resultsSize = results.size();
        for (int i = 0; i < resultsSize; i++) {
            ScanResult result = results.get(i);
            if (result == null) continue;
            if (result.SSID == null) continue;
            
            if (result.SSID.equals(ssid)) {
                numApsFound++;
                
                if (numApsFound >= numApsRequired) {
                    if (V) {
                        myLogV("hasRequiredNumberOfAps: SSID: " + ssid + ", returning true");
                    }
                    return true;
                }
            }
        }
        
        if (V) {
            myLogV("hasRequiredNumberOfAps: SSID: " + ssid + ", returning false");
        }
        return false;
    }
    
    // Watchdog logic (assume all of these methods will be in our main thread)
    
    /**
     * Handles a Wi-Fi network change (for example, from networkA to networkB).
     */
    private void handleNetworkChanged(String ssid) {
        // Set the SSID being monitored to the new SSID 
        mSsid = ssid;
        // Set various state to that when being idle 
        setIdleState(true);
    }
    
    /**
     * Handles checking whether an AP is a "good" AP.  If not, it will be blacklisted.
     * 
     * @param ap The access point to check.
     */
    private void handleCheckAp(AccessPoint ap) {
        // Reset the cancel state since this is the entry point of this action
        mShouldCancel = false;
        
        if (V) {
            myLogV("handleCheckAp: AccessPoint: " + ap);
        }
        
        // Make sure we are not sleeping
        if (mState == WatchdogState.SLEEP) {
            if (V) {
                Slog.v(TAG, "  Sleeping (in " + mSsid + "), so returning");
            }
            return;
        }
        
        mState = WatchdogState.CHECKING_AP;
        
        /*
         * Checks to make sure we haven't exceeded the max number of checks
         * we're allowed per network
         */
        mNumApsChecked++;
        if (mNumApsChecked > getMaxApChecks()) {
            if (V) {
                Slog.v(TAG, "  Passed the max attempts (" + getMaxApChecks()
                        + "), going to sleep for " + mSsid);
            }
            mHandler.sleep(mSsid);
            return;
        }

        // Do the check
        boolean isApAlive = checkDnsConnectivity();
        
        if (V) {
            Slog.v(TAG, "  Is it alive: " + isApAlive);
        }

        // Take action based on results
        if (isApAlive) {
            handleApAlive(ap);
        } else {
            handleApUnresponsive(ap);
        }
    }

    /**
     * Handles the case when an access point is alive.
     * 
     * @param ap The access point.
     */
    private void handleApAlive(AccessPoint ap) {
        // Check whether we are stale and should cancel
        if (shouldCancel()) return;
        // We're satisfied with this AP, so go idle
        setIdleState(false);
        
        if (D) {
            myLogD("AP is alive: " + ap.toString());
        }
        
        // Queue the next action to be a background check
        mHandler.backgroundCheckAp(ap);
    }
    
    /**
     * Handles an unresponsive AP by blacklisting it.
     * 
     * @param ap The access point.
     */
    private void handleApUnresponsive(AccessPoint ap) {
        // Check whether we are stale and should cancel
        if (shouldCancel()) return;
        // This AP is "bad", switch to another
        mState = WatchdogState.SWITCHING_AP;

        if (D) {
            myLogD("AP is dead: " + ap.toString());
        }
        
        // Black list this "bad" AP, this will cause an attempt to connect to another
        blacklistAp(ap.bssid);
        // Initiate an association to an alternate AP
        mWifiStateTracker.reassociate();
    }

    private void blacklistAp(String bssid) {
        if (TextUtils.isEmpty(bssid)) {
            return;
        }
        
        // Before taking action, make sure we should not cancel our processing
        if (shouldCancel()) return;
        
        if (!mWifiStateTracker.addToBlacklist(bssid)) {
            // There's a known bug where this method returns failure on success
            //Slog.e(TAG, "Blacklisting " + bssid + " failed");
        }

        if (D) {
            myLogD("Blacklisting " + bssid);
        }
    }

    /**
     * Handles a single background check. If it fails, it should trigger a
     * normal check. If it succeeds, it should queue another background check.
     * 
     * @param ap The access point to do a background check for. If this is no
     *        longer the current AP, it is okay to return without any
     *        processing.
     */
    private void handleBackgroundCheckAp(AccessPoint ap) {
        // Reset the cancel state since this is the entry point of this action
        mShouldCancel = false;
        
        if (false && V) {
            myLogV("handleBackgroundCheckAp: AccessPoint: " + ap);
        }
        
        // Make sure we are not sleeping
        if (mState == WatchdogState.SLEEP) {
            if (V) {
                Slog.v(TAG, "  handleBackgroundCheckAp: Sleeping (in " + mSsid + "), so returning");
            }
            return;
        }
        
        // Make sure the AP we're supposed to be background checking is still the active one
        WifiInfo info = mWifiManager.getConnectionInfo();
        if (info.getSSID() == null || !info.getSSID().equals(ap.ssid)) {
            if (V) {
                myLogV("handleBackgroundCheckAp: We are no longer connected to "
                        + ap + ", and instead are on " + info);
            }
            return;
        }
        
        if (info.getBSSID() == null || !info.getBSSID().equals(ap.bssid)) {
            if (V) {
                myLogV("handleBackgroundCheckAp: We are no longer connected to "
                        + ap + ", and instead are on " + info);
            }
            return;
        }

        // Do the check
        boolean isApAlive = backgroundCheckDnsConnectivity();
        
        if (V && !isApAlive) {
            Slog.v(TAG, "  handleBackgroundCheckAp: Is it alive: " + isApAlive);
        }

        if (shouldCancel()) {
            return;
        }
        
        // Take action based on results
        if (isApAlive) {
            // Queue another background check
            mHandler.backgroundCheckAp(ap);
            
        } else {
            if (D) {
                myLogD("Background check failed for " + ap.toString());
            }
            
            // Queue a normal check, so it can take proper action
            mHandler.checkAp(ap);
        }
    }
    
    /**
     * Handles going to sleep for this network. Going to sleep means we will not
     * monitor this network anymore.
     * 
     * @param ssid The network that will not be monitored anymore.
     */
    private void handleSleep(String ssid) {
        // Make sure the network we're trying to sleep in is still the current network
        if (ssid != null && ssid.equals(mSsid)) {
            mState = WatchdogState.SLEEP;

            if (D) {
                myLogD("Going to sleep for " + ssid);
            }
            
            /*
             * Before deciding to go to sleep, we may have checked a few APs
             * (and blacklisted them). Clear the blacklist so the AP with best
             * signal is chosen.
             */
            if (!mWifiStateTracker.clearBlacklist()) {
                // There's a known bug where this method returns failure on success
                //Slog.e(TAG, "Clearing blacklist failed");
            }
            
            if (V) {
                myLogV("handleSleep: Set state to SLEEP and cleared blacklist");
            }
        }
    }

    /**
     * Handles an access point disconnection.
     */
    private void handleDisconnected() {
        /*
         * We purposefully do not change mSsid to null. This is to handle
         * disconnected followed by connected better (even if there is some
         * duration in between). For example, if the watchdog went to sleep in a
         * network, and then the phone goes to sleep, when the phone wakes up we
         * still want to be in the sleeping state. When the phone went to sleep,
         * we would have gotten a disconnected event which would then set mSsid
         * = null. This is bad, since the following connect would cause us to do
         * the "network is good?" check all over again. */
        
        /* 
         * Set the state as if we were idle (don't come out of sleep, only
         * hard reset and network changed should do that.
         */
        setIdleState(false);
    }

    /**
     * Handles going idle. Idle means we are satisfied with the current state of
     * things, but if a new connection occurs we'll re-evaluate.
     */
    private void handleIdle() {
        // Reset the cancel state since this is the entry point for this action
        mShouldCancel = false;
        
        if (V) {
            myLogV("handleSwitchToIdle");
        }
        
        // If we're sleeping, don't do anything
        if (mState == WatchdogState.SLEEP) {
            Slog.v(TAG, "  Sleeping (in " + mSsid + "), so returning");
            return;
        }
        
        // Set the idle state
        setIdleState(false);
        
        if (V) {
            Slog.v(TAG, "  Set state to IDLE");
        }
    }
    
    /**
     * Sets the state as if we are going idle.
     */
    private void setIdleState(boolean forceIdleState) {
        // Setting idle state does not kick us out of sleep unless the forceIdleState is set
        if (forceIdleState || (mState != WatchdogState.SLEEP)) {
            mState = WatchdogState.IDLE;
        }
        mNumApsChecked = 0;
    }

    /**
     * Handles a hard reset. A hard reset is rarely used, but when used it
     * should revert anything done by the watchdog monitoring.
     */
    private void handleReset() {
        mWifiStateTracker.clearBlacklist();
        setIdleState(true);
    }
    
    // Inner classes

    /**
     * Possible states for the watchdog to be in.
     */
    private static enum WatchdogState {
        /** The watchdog is currently idle, but it is still responsive to future AP checks in this network. */
        IDLE,
        /** The watchdog is sleeping, so it will not try any AP checks for the network. */
        SLEEP,
        /** The watchdog is currently checking an AP for connectivity. */
        CHECKING_AP,
        /** The watchdog is switching to another AP in the network. */
        SWITCHING_AP
    }

    /**
     * The main thread for the watchdog monitoring. This will be turned into a
     * {@link Looper} thread.
     */
    private class WifiWatchdogThread extends Thread {
        WifiWatchdogThread() {
            super("WifiWatchdogThread");
        }
        
        @Override
        public void run() {
            // Set this thread up so the handler will work on it
            Looper.prepare();
            
            synchronized(WifiWatchdogService.this) {
                mHandler = new WifiWatchdogHandler();

                // Notify that the handler has been created
                WifiWatchdogService.this.notify();
            }
            
            // Listen for messages to the handler
            Looper.loop();
        }
    }

    /**
     * The main thread's handler. There are 'actions', and just general 
     * 'messages'. There should only ever be one 'action' in the queue (aside
     * from the one being processed, if any). There may be multiple messages in
     * the queue. So, actions are replaced by more recent actions, where as
     * messages will be executed for sure. Messages end up being used to just
     * change some state, and not really take any action.
     * <p>
     * There is little logic inside this class, instead methods of the form
     * "handle___" are called in the main {@link WifiWatchdogService}.
     */
    private class WifiWatchdogHandler extends Handler {
        /** Check whether the AP is "good".  The object will be an {@link AccessPoint}. */
        static final int ACTION_CHECK_AP = 1;
        /** Go into the idle state. */
        static final int ACTION_IDLE = 2;
        /**
         * Performs a periodic background check whether the AP is still "good".
         * The object will be an {@link AccessPoint}.
         */
        static final int ACTION_BACKGROUND_CHECK_AP = 3;

        /**
         * Go to sleep for the current network. We are conservative with making
         * this a message rather than action. We want to make sure our main
         * thread sees this message, but if it were an action it could be
         * removed from the queue and replaced by another action. The main
         * thread will ensure when it sees the message that the state is still
         * valid for going to sleep.
         * <p>
         * For an explanation of sleep, see {@link android.provider.Settings.Secure#WIFI_WATCHDOG_MAX_AP_CHECKS}.
         */
        static final int MESSAGE_SLEEP = 101;
        /** Disables the watchdog. */
        static final int MESSAGE_DISABLE_WATCHDOG = 102;
        /** The network has changed. */
        static final int MESSAGE_NETWORK_CHANGED = 103;
        /** The current access point has disconnected. */
        static final int MESSAGE_DISCONNECTED = 104;
        /** Performs a hard-reset on the watchdog state. */
        static final int MESSAGE_RESET = 105;
        
        void checkAp(AccessPoint ap) {
            removeAllActions();
            sendMessage(obtainMessage(ACTION_CHECK_AP, ap));
        }
        
        void backgroundCheckAp(AccessPoint ap) {
            if (!isBackgroundCheckEnabled()) return;
            
            removeAllActions();
            sendMessageDelayed(obtainMessage(ACTION_BACKGROUND_CHECK_AP, ap),
                    getBackgroundCheckDelayMs());
        }
        
        void idle() {
            removeAllActions();
            sendMessage(obtainMessage(ACTION_IDLE));
        }
        
        void sleep(String ssid) {
            removeAllActions();
            sendMessage(obtainMessage(MESSAGE_SLEEP, ssid));
        }
        
        void disableWatchdog() {
            removeAllActions();
            sendMessage(obtainMessage(MESSAGE_DISABLE_WATCHDOG));
        }
        
        void dispatchNetworkChanged(String ssid) {
            removeAllActions();
            sendMessage(obtainMessage(MESSAGE_NETWORK_CHANGED, ssid));
        }

        void dispatchDisconnected() {
            removeAllActions();
            sendMessage(obtainMessage(MESSAGE_DISCONNECTED));
        }

        void reset() {
            removeAllActions();
            sendMessage(obtainMessage(MESSAGE_RESET));
        }
        
        private void removeAllActions() {
            removeMessages(ACTION_CHECK_AP);
            removeMessages(ACTION_IDLE);
            removeMessages(ACTION_BACKGROUND_CHECK_AP);
        }
        
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_NETWORK_CHANGED:
                    handleNetworkChanged((String) msg.obj);
                    break;
                case ACTION_CHECK_AP:
                    handleCheckAp((AccessPoint) msg.obj);
                    break;
                case ACTION_BACKGROUND_CHECK_AP:
                    handleBackgroundCheckAp((AccessPoint) msg.obj);
                    break;
                case MESSAGE_SLEEP:
                    handleSleep((String) msg.obj);
                    break;
                case ACTION_IDLE:
                    handleIdle();
                    break;
                case MESSAGE_DISABLE_WATCHDOG:
                    handleIdle();
                    break;
                case MESSAGE_DISCONNECTED:
                    handleDisconnected();
                    break;
                case MESSAGE_RESET:
                    handleReset();
                    break;
            }
        }
    }

    /**
     * Receives Wi-Fi broadcasts.
     * <p>
     * There is little logic in this class, instead methods of the form "on___"
     * are called in the {@link WifiWatchdogService}.
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                handleNetworkStateChanged(
                        (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO));
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                handleWifiStateChanged(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN));
            }
        }

        private void handleNetworkStateChanged(NetworkInfo info) {
            if (V) {
                myLogV("Receiver.handleNetworkStateChanged: NetworkInfo: "
                        + info);
            }
            
            switch (info.getState()) {
                case CONNECTED:
                    WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                    if (wifiInfo.getSSID() == null || wifiInfo.getBSSID() == null) {
                        if (V) {
                            myLogV("handleNetworkStateChanged: Got connected event but SSID or BSSID are null. SSID: "
                                + wifiInfo.getSSID()
                                + ", BSSID: "
                                + wifiInfo.getBSSID() + ", ignoring event");
                        }
                        return;
                    }
                    onConnected(wifiInfo.getSSID(), wifiInfo.getBSSID());
                    break;

                case DISCONNECTED:
                    onDisconnected();
                    break;
            }
        }

        private void handleWifiStateChanged(int wifiState) {
            if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                quit();
            } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                onEnabled();
            }
        }
    };

    /**
     * Describes an access point by its SSID and BSSID.
     */
    private static class AccessPoint {
        String ssid;
        String bssid;
        
        AccessPoint(String ssid, String bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
        }

        private boolean hasNull() {
            return ssid == null || bssid == null;
        }
        
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AccessPoint)) return false;
            AccessPoint otherAp = (AccessPoint) o;
            boolean iHaveNull = hasNull();
            // Either we both have a null, or our SSIDs and BSSIDs are equal
            return (iHaveNull && otherAp.hasNull()) || 
                    (otherAp.bssid != null && ssid.equals(otherAp.ssid)
                    && bssid.equals(otherAp.bssid));
        }
        
        @Override
        public int hashCode() {
            if (ssid == null || bssid == null) return 0;
            return ssid.hashCode() + bssid.hashCode();
        }

        @Override
        public String toString() {
            return ssid + " (" + bssid + ")";
        }
    }

    /**
     * Performs a simple DNS "ping" by sending a "server status" query packet to
     * the DNS server. As long as the server replies, we consider it a success.
     * <p>
     * We do not use a simple hostname lookup because that could be cached and
     * the API may not differentiate between a time out and a failure lookup
     * (which we really care about).
     */
    private static class DnsPinger {
        
        /** Number of bytes for the query */
        private static final int DNS_QUERY_BASE_SIZE = 33;
        
        /** The DNS port */
        private static final int DNS_PORT = 53;
        
        /** Used to generate IDs */
        private static Random sRandom = new Random();
        
        static boolean isDnsReachable(int dns, int timeout) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                
                // Set some socket properties
                socket.setSoTimeout(timeout);
                
                byte[] buf = new byte[DNS_QUERY_BASE_SIZE];
                fillQuery(buf);
                
                // Send the DNS query
                byte parts[] = new byte[4];
                parts[0] = (byte)(dns & 0xff);
                parts[1] = (byte)((dns >> 8) & 0xff);
                parts[2] = (byte)((dns >> 16) & 0xff);
                parts[3] = (byte)((dns >> 24) & 0xff);

                InetAddress dnsAddress = InetAddress.getByAddress(parts);
                DatagramPacket packet = new DatagramPacket(buf,
                        buf.length, dnsAddress, DNS_PORT);
                socket.send(packet);
                
                // Wait for reply (blocks for the above timeout)
                DatagramPacket replyPacket = new DatagramPacket(buf, buf.length);
                socket.receive(replyPacket);

                // If a timeout occurred, an exception would have been thrown.  We got a reply!
                return true;
                
            } catch (SocketException e) {
                if (V) {
                    Slog.v(TAG, "DnsPinger.isReachable received SocketException", e);
                }
                return false;
                
            } catch (UnknownHostException e) {
                if (V) {
                    Slog.v(TAG, "DnsPinger.isReachable is unable to resolve the DNS host", e);
                }
                return false;

            } catch (SocketTimeoutException e) {
                return false;
                
            } catch (IOException e) {
                if (V) {
                    Slog.v(TAG, "DnsPinger.isReachable got an IOException", e);
                }
                return false;
                
            } catch (Exception e) {
                if (V || Config.LOGD) {
                    Slog.d(TAG, "DnsPinger.isReachable got an unknown exception", e);
                }
                return false;
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        
        private static void fillQuery(byte[] buf) {

            /*
             * See RFC2929 (though the bit tables in there are misleading for
             * us. For example, the recursion desired bit is the 0th bit for us,
             * but looking there it would appear as the 7th bit of the byte
             */

            // Make sure it's all zeroed out
            for (int i = 0; i < buf.length; i++) buf[i] = 0;

            // Form a query for www.android.com
            
            // [0-1] bytes are an ID, generate random ID for this query
            buf[0] = (byte) sRandom.nextInt(256); 
            buf[1] = (byte) sRandom.nextInt(256); 
            
            // [2-3] bytes are for flags.
            buf[2] = 1; // Recursion desired

            // [4-5] bytes are for the query count
            buf[5] = 1; // One query 
            
            // [6-7] [8-9] [10-11] are all counts of other fields we don't use

            // [12-15] for www
            writeString(buf, 12, "www");
            
            // [16-23] for android
            writeString(buf, 16, "android");
            
            // [24-27] for com
            writeString(buf, 24, "com");
            
            // [29-30] bytes are for QTYPE, set to 1 
            buf[30] = 1;

            // [31-32] bytes are for QCLASS, set to 1 
            buf[32] = 1;
        }
        
        private static void writeString(byte[] buf, int startPos, String string) {
            int pos = startPos;
            
            // Write the length first
            buf[pos++] = (byte) string.length();
            for (int i = 0; i < string.length(); i++) {
                buf[pos++] = (byte) string.charAt(i);
            }
        }
    }
}
