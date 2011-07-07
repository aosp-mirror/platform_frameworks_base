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

package android.net.wifi;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DnsPinger;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;

/**
 * {@link WifiWatchdogService} monitors the initial connection to a Wi-Fi
 * network with multiple access points. After the framework successfully
 * connects to an access point, the watchdog verifies connectivity by 'pinging'
 * the configured DNS server using {@link DnsPinger}.
 * <p>
 * On DNS check failure, the BSSID is blacklisted if it is reasonably likely
 * that another AP might have internet access; otherwise the SSID is disabled.
 * <p>
 * On DNS success, the WatchdogService initiates a walled garden check via an
 * http get. A browser windows is activated if a walled garden is detected.
 * 
 * @hide
 */
public class WifiWatchdogService {

    private static final String WWS_TAG = "WifiWatchdogService";

    private static final boolean VDBG = true;
    private static final boolean DBG = true;

    // Used for verbose logging
    private String mDNSCheckLogStr;

    private Context mContext;
    private ContentResolver mContentResolver;
    private WifiManager mWifiManager;

    private WifiWatchdogHandler mHandler;

    private DnsPinger mDnsPinger;

    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;
    private boolean mBroadcastsEnabled;

    private static final int WIFI_SIGNAL_LEVELS = 4;

    /**
     * Low signal is defined as less than or equal to cut off
     */
    private static final int LOW_SIGNAL_CUTOFF = 0;

    private static final long MIN_LOW_SIGNAL_CHECK_INTERVAL = 2 * 60 * 1000;
    private static final long MIN_SINGLE_DNS_CHECK_INTERVAL = 10 * 60 * 1000;
    private static final long MIN_WALLED_GARDEN_INTERVAL = 15 * 60 * 1000;

    private static final int MAX_CHECKS_PER_SSID = 9;
    private static final int NUM_DNS_PINGS = 7;
    private static double MIN_RESPONSE_RATE = 0.50;

    // TODO : Adjust multiple DNS downward to 250 on repeated failure
    // private static final int MULTI_DNS_PING_TIMEOUT_MS = 250;

    private static final int DNS_PING_TIMEOUT_MS = 800;
    private static final long DNS_PING_INTERVAL = 250;

    private static final long BLACKLIST_FOLLOWUP_INTERVAL = 15 * 1000;

    private Status mStatus = new Status();

    private static class Status {
        String bssid = "";
        String ssid = "";

        HashSet<String> allBssids = new HashSet<String>();
        int numFullDNSchecks = 0;

        long lastSingleCheckTime = -24 * 60 * 60 * 1000;
        long lastWalledGardenCheckTime = -24 * 60 * 60 * 1000;

        WatchdogState state = WatchdogState.INACTIVE;

        // Info for dns check
        int dnsCheckTries = 0;
        int dnsCheckSuccesses = 0;

        public int signal = -200;

    }

    private enum WatchdogState {
        /**
         * Full DNS check in progress
         */
        DNS_FULL_CHECK,

        /**
         * Walled Garden detected, will pop up browser next round.
         */
        WALLED_GARDEN_DETECTED,

        /**
         * DNS failed, will blacklist/disable AP next round
         */
        DNS_CHECK_FAILURE,

        /**
         * Online or displaying walled garden auth page
         */
        CHECKS_COMPLETE,

        /**
         * Watchdog idle, network has been blacklisted or received disconnect
         * msg
         */
        INACTIVE,

        BLACKLISTED_AP
    }

    public WifiWatchdogService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mDnsPinger = new DnsPinger("WifiWatchdogServer.DnsPinger", context,
                ConnectivityManager.TYPE_WIFI);

        HandlerThread handlerThread = new HandlerThread("WifiWatchdogServiceThread");
        handlerThread.start();
        mHandler = new WifiWatchdogHandler(handlerThread.getLooper());

        setupNetworkReceiver();

        // The content observer to listen needs a handler, which createThread
        // creates
        registerForSettingsChanges();

        // Start things off
        if (isWatchdogEnabled()) {
            mHandler.sendEmptyMessage(WifiWatchdogHandler.MESSAGE_CONTEXT_EVENT);
        }
    }

    /**
     *
     */
    private void setupNetworkReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    mHandler.sendMessage(mHandler.obtainMessage(
                            WifiWatchdogHandler.MESSAGE_NETWORK_EVENT,
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                            ));
                } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                    mHandler.sendEmptyMessage(WifiWatchdogHandler.RSSI_CHANGE_EVENT);
                } else if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    mHandler.sendEmptyMessage(WifiWatchdogHandler.SCAN_RESULTS_AVAILABLE);
                } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    mHandler.sendMessage(mHandler.obtainMessage(
                            WifiWatchdogHandler.WIFI_STATE_CHANGE,
                            intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 4)));
                }
            }
        };

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    }

    /**
     * Observes the watchdog on/off setting, and takes action when changed.
     */
    private void registerForSettingsChanges() {
        ContentObserver contentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mHandler.sendEmptyMessage((WifiWatchdogHandler.MESSAGE_CONTEXT_EVENT));
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_ON),
                false, contentObserver);
    }

    private void handleNewConnection() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        String newSsid = wifiInfo.getSSID();
        String newBssid = wifiInfo.getBSSID();

        if (VDBG) {
            Slog.v(WWS_TAG, String.format("handleConnected:: old (%s, %s) ==> new (%s, %s)",
                    mStatus.ssid, mStatus.bssid, newSsid, newBssid));
        }

        if (TextUtils.isEmpty(newSsid) || TextUtils.isEmpty(newBssid)) {
            return;
        }

        if (!TextUtils.equals(mStatus.ssid, newSsid)) {
            mStatus = new Status();
            mStatus.ssid = newSsid;
        }

        mStatus.bssid = newBssid;
        mStatus.allBssids.add(newBssid);
        mStatus.signal = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), WIFI_SIGNAL_LEVELS);

        initDnsFullCheck();
    }

    public void updateRssi() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        if (!TextUtils.equals(mStatus.ssid, wifiInfo.getSSID()) ||
                !TextUtils.equals(mStatus.bssid, wifiInfo.getBSSID())) {
            return;
        }

        mStatus.signal = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), WIFI_SIGNAL_LEVELS);
    }

    /**
     * Single step in state machine
     */
    private void handleStateStep() {
        // Slog.v(WWS_TAG, "handleStateStep:: " + mStatus.state);

        switch (mStatus.state) {
            case DNS_FULL_CHECK:
                if (VDBG) {
                    Slog.v(WWS_TAG, "DNS_FULL_CHECK: " + mDNSCheckLogStr);
                }

                long pingResponseTime = mDnsPinger.pingDns(mDnsPinger.getDns(),
                        DNS_PING_TIMEOUT_MS);

                mStatus.dnsCheckTries++;
                if (pingResponseTime >= 0)
                    mStatus.dnsCheckSuccesses++;

                if (DBG) {
                    if (pingResponseTime >= 0) {
                        mDNSCheckLogStr += " | " + pingResponseTime;
                    } else {
                        mDNSCheckLogStr += " | " + "x";
                    }
                }

                switch (currentDnsCheckStatus()) {
                    case SUCCESS:
                        if (DBG) {
                            Slog.d(WWS_TAG, mDNSCheckLogStr + " -- Success");
                        }
                        doWalledGardenCheck();
                        break;
                    case FAILURE:
                        if (DBG) {
                            Slog.d(WWS_TAG, mDNSCheckLogStr + " -- Failure");
                        }
                        mStatus.state = WatchdogState.DNS_CHECK_FAILURE;
                        break;
                    case INCOMPLETE:
                        // Taking no action
                        break;
                }
                break;
            case DNS_CHECK_FAILURE:
                WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                if (!mStatus.ssid.equals(wifiInfo.getSSID()) ||
                        !mStatus.bssid.equals(wifiInfo.getBSSID())) {
                    Slog.i(WWS_TAG, "handleState DNS_CHECK_FAILURE:: network has changed!");
                    mStatus.state = WatchdogState.INACTIVE;
                    break;
                }

                if (mStatus.numFullDNSchecks >= mStatus.allBssids.size() ||
                        mStatus.numFullDNSchecks >= MAX_CHECKS_PER_SSID) {
                    disableAP(wifiInfo);
                } else {
                    blacklistAP();
                }
                break;
            case WALLED_GARDEN_DETECTED:
                popUpBrowser();
                mStatus.state = WatchdogState.CHECKS_COMPLETE;
                break;
            case BLACKLISTED_AP:
                WifiInfo wifiInfo2 = mWifiManager.getConnectionInfo();
                if (wifiInfo2.getSupplicantState() != SupplicantState.COMPLETED) {
                    Slog.d(WWS_TAG,
                            "handleState::BlacklistedAP - offline, but didn't get disconnect!");
                    mStatus.state = WatchdogState.INACTIVE;
                    break;
                }
                if (mStatus.bssid.equals(wifiInfo2.getBSSID())) {
                    Slog.d(WWS_TAG, "handleState::BlacklistedAP - connected to same bssid");
                    if (!handleSingleDnsCheck()) {
                        disableAP(wifiInfo2);
                        break;
                    }
                }

                Slog.d(WWS_TAG, "handleState::BlacklistedAP - Simiulating a new connection");
                handleNewConnection();
                break;
        }
    }

    private void doWalledGardenCheck() {
        if (!isWalledGardenTestEnabled()) {
            if (VDBG)
                Slog.v(WWS_TAG, "Skipping walled garden check - disabled");
            mStatus.state = WatchdogState.CHECKS_COMPLETE;
            return;
        }
        long waitTime = waitTime(MIN_WALLED_GARDEN_INTERVAL,
                mStatus.lastWalledGardenCheckTime);
        if (waitTime > 0) {
            if (VDBG) {
                Slog.v(WWS_TAG, "Skipping walled garden check - wait " +
                        waitTime + " ms.");
            }
            mStatus.state = WatchdogState.CHECKS_COMPLETE;
            return;
        }

        mStatus.lastWalledGardenCheckTime = SystemClock.elapsedRealtime();
        if (isWalledGardenConnection()) {
            if (DBG)
                Slog.d(WWS_TAG,
                        "Walled garden test complete - walled garden detected");
            mStatus.state = WatchdogState.WALLED_GARDEN_DETECTED;
        } else {
            if (DBG)
                Slog.d(WWS_TAG, "Walled garden test complete - online");
            mStatus.state = WatchdogState.CHECKS_COMPLETE;
        }
    }

    private boolean handleSingleDnsCheck() {
        mStatus.lastSingleCheckTime = SystemClock.elapsedRealtime();
        long responseTime = mDnsPinger.pingDns(mDnsPinger.getDns(),
                DNS_PING_TIMEOUT_MS);
        if (DBG) {
            Slog.d(WWS_TAG, "Ran a single DNS ping. Response time: " + responseTime);
        }
        if (responseTime < 0) {
            return false;
        }
        return true;

    }

    /**
     * @return Delay in MS before next single DNS check can proceed.
     */
    private long timeToNextScheduledDNSCheck() {
        if (mStatus.signal > LOW_SIGNAL_CUTOFF) {
            return waitTime(MIN_SINGLE_DNS_CHECK_INTERVAL, mStatus.lastSingleCheckTime);
        } else {
            return waitTime(MIN_LOW_SIGNAL_CHECK_INTERVAL, mStatus.lastSingleCheckTime);
        }
    }

    /**
     * Helper to return wait time left given a min interval and last run
     * 
     * @param interval minimum wait interval
     * @param lastTime last time action was performed in
     *            SystemClock.elapsedRealtime()
     * @return non negative time to wait
     */
    private static long waitTime(long interval, long lastTime) {
        long wait = interval + lastTime - SystemClock.elapsedRealtime();
        return wait > 0 ? wait : 0;
    }

    private void popUpBrowser() {
        Uri uri = Uri.parse("http://www.google.com");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    private void disableAP(WifiInfo info) {
        // TODO : Unban networks if they had low signal ?
        Slog.i(WWS_TAG, String.format("Disabling current SSID, %s [bssid %s].  " +
                "numChecks %d, numAPs %d", mStatus.ssid, mStatus.bssid,
                mStatus.numFullDNSchecks, mStatus.allBssids.size()));
        mWifiManager.disableNetwork(info.getNetworkId());
        mStatus.state = WatchdogState.INACTIVE;
    }

    private void blacklistAP() {
        Slog.i(WWS_TAG, String.format("Blacklisting current BSSID %s [ssid %s].  " +
                "numChecks %d, numAPs %d", mStatus.bssid, mStatus.ssid,
                mStatus.numFullDNSchecks, mStatus.allBssids.size()));

        mWifiManager.addToBlacklist(mStatus.bssid);
        mWifiManager.reassociate();
        mStatus.state = WatchdogState.BLACKLISTED_AP;
    }

    /**
     * Checks the scan for new BBIDs using current mSsid
     */
    private void updateBssids() {
        String curSsid = mStatus.ssid;
        HashSet<String> bssids = mStatus.allBssids;
        List<ScanResult> results = mWifiManager.getScanResults();
        int oldNumBssids = bssids.size();

        if (results == null) {
            if (VDBG) {
                Slog.v(WWS_TAG, "updateBssids: Got null scan results!");
            }
            return;
        }

        for (ScanResult result : results) {
            if (result != null && curSsid.equals(result.SSID))
                bssids.add(result.BSSID);
        }

        // if (VDBG && bssids.size() - oldNumBssids > 0) {
        // Slog.v(WWS_TAG,
        // String.format("updateBssids:: Found %d new APs (total %d) on SSID %s",
        // bssids.size() - oldNumBssids, bssids.size(), curSsid));
        // }
    }

    enum DnsCheckStatus {
        SUCCESS,
        FAILURE,
        INCOMPLETE
    }

    /**
     * Computes the current results of the dns check, ends early if outcome is
     * assured.
     */
    private DnsCheckStatus currentDnsCheckStatus() {
        /**
         * After a full ping count, if we have more responses than this cutoff,
         * the outcome is success; else it is 'failure'.
         */
        double pingResponseCutoff = MIN_RESPONSE_RATE * NUM_DNS_PINGS;
        int remainingChecks = NUM_DNS_PINGS - mStatus.dnsCheckTries;

        /**
         * Our final success count will be at least this big, so we're
         * guaranteed to succeed.
         */
        if (mStatus.dnsCheckSuccesses >= pingResponseCutoff) {
            return DnsCheckStatus.SUCCESS;
        }

        /**
         * Our final count will be at most the current count plus the remaining
         * pings - we're guaranteed to fail.
         */
        if (remainingChecks + mStatus.dnsCheckSuccesses < pingResponseCutoff) {
            return DnsCheckStatus.FAILURE;
        }

        return DnsCheckStatus.INCOMPLETE;
    }

    private void initDnsFullCheck() {
        if (DBG) {
            Slog.d(WWS_TAG, "Starting DNS pings at " + SystemClock.elapsedRealtime());
        }
        mStatus.numFullDNSchecks++;
        mStatus.dnsCheckSuccesses = 0;
        mStatus.dnsCheckTries = 0;
        mStatus.state = WatchdogState.DNS_FULL_CHECK;

        if (DBG) {
            mDNSCheckLogStr = String.format("Dns Check %d.  Pinging %s on ssid [%s]: ",
                    mStatus.numFullDNSchecks, mDnsPinger.getDns(),
                    mStatus.ssid);
        }
    }

    /**
     * DNS based detection techniques do not work at all hotspots. The one sure
     * way to check a walled garden is to see if a URL fetch on a known address
     * fetches the data we expect
     */
    private boolean isWalledGardenConnection() {
        InputStream in = null;
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(getWalledGardenUrl());
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream());
            Scanner scanner = new Scanner(in);
            if (scanner.findInLine(getWalledGardenPattern()) != null) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
            if (urlConnection != null)
                urlConnection.disconnect();
        }
    }

    /**
     * There is little logic inside this class, instead methods of the form
     * "handle___" are called in the main {@link WifiWatchdogService}.
     */
    private class WifiWatchdogHandler extends Handler {
        /**
         * Major network event, object is NetworkInfo
         */
        static final int MESSAGE_NETWORK_EVENT = 1;
        /**
         * Change in settings, no object
         */
        static final int MESSAGE_CONTEXT_EVENT = 2;

        /**
         * Change in signal strength
         */
        static final int RSSI_CHANGE_EVENT = 3;
        static final int SCAN_RESULTS_AVAILABLE = 4;

        static final int WIFI_STATE_CHANGE = 5;

        /**
         * Single step of state machine. One DNS check, or one WalledGarden
         * check, or one external action. We separate out external actions to
         * increase chance of detecting that a check failure is caused by change
         * in network status. Messages should have an arg1 which to sync status
         * messages.
         */
        static final int CHECK_SEQUENCE_STEP = 10;
        static final int SINGLE_DNS_CHECK = 11;

        /**
         * @param looper
         */
        public WifiWatchdogHandler(Looper looper) {
            super(looper);
        }

        boolean singleCheckQueued = false;
        long queuedSingleDnsCheckArrival;

        /**
         * Sends a singleDnsCheck message with shortest time - guards against
         * multiple.
         */
        private boolean queueSingleDnsCheck() {
            long delay = timeToNextScheduledDNSCheck();
            long newArrival = delay + SystemClock.elapsedRealtime();
            if (singleCheckQueued && queuedSingleDnsCheckArrival <= newArrival)
                return true;
            queuedSingleDnsCheckArrival = newArrival;
            singleCheckQueued = true;
            removeMessages(SINGLE_DNS_CHECK);
            return sendMessageDelayed(obtainMessage(SINGLE_DNS_CHECK), delay);
        }

        boolean checkSequenceQueued = false;
        long queuedCheckSequenceArrival;

        /**
         * Sends a state_machine_step message if the delay requested is lower
         * than the current delay.
         */
        private boolean sendCheckSequenceStep(long delay) {
            long newArrival = delay + SystemClock.elapsedRealtime();
            if (checkSequenceQueued && queuedCheckSequenceArrival <= newArrival)
                return true;
            queuedCheckSequenceArrival = newArrival;
            checkSequenceQueued = true;
            removeMessages(CHECK_SEQUENCE_STEP);
            return sendMessageDelayed(obtainMessage(CHECK_SEQUENCE_STEP), delay);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CHECK_SEQUENCE_STEP:
                    checkSequenceQueued = false;
                    handleStateStep();
                    if (mStatus.state == WatchdogState.CHECKS_COMPLETE) {
                        queueSingleDnsCheck();
                    } else if (mStatus.state == WatchdogState.DNS_FULL_CHECK) {
                        sendCheckSequenceStep(DNS_PING_INTERVAL);
                    } else if (mStatus.state == WatchdogState.BLACKLISTED_AP) {
                        sendCheckSequenceStep(BLACKLIST_FOLLOWUP_INTERVAL);
                    } else if (mStatus.state != WatchdogState.INACTIVE) {
                        sendCheckSequenceStep(0);
                    }
                    return;
                case MESSAGE_NETWORK_EVENT:
                    if (!mBroadcastsEnabled) {
                        Slog.e(WWS_TAG,
                                "MessageNetworkEvent - WatchdogService not enabled... returning");
                        return;
                    }
                    NetworkInfo info = (NetworkInfo) msg.obj;
                    switch (info.getState()) {
                        case DISCONNECTED:
                            mStatus.state = WatchdogState.INACTIVE;
                            return;
                        case CONNECTED:
                            handleNewConnection();
                            sendCheckSequenceStep(0);
                    }
                    return;
                case SINGLE_DNS_CHECK:
                    singleCheckQueued = false;
                    if (mStatus.state != WatchdogState.CHECKS_COMPLETE) {
                        Slog.d(WWS_TAG, "Single check returning, curState: " + mStatus.state);
                        break;
                    }

                    if (!handleSingleDnsCheck()) {
                        initDnsFullCheck();
                        sendCheckSequenceStep(0);
                    } else {
                        queueSingleDnsCheck();
                    }

                    break;
                case RSSI_CHANGE_EVENT:
                    updateRssi();
                    if (mStatus.state == WatchdogState.CHECKS_COMPLETE)
                        queueSingleDnsCheck();
                    break;
                case SCAN_RESULTS_AVAILABLE:
                    updateBssids();
                    break;
                case WIFI_STATE_CHANGE:
                    if ((Integer) msg.obj == WifiManager.WIFI_STATE_DISABLING) {
                        Slog.i(WWS_TAG, "WifiStateDisabling -- Resetting WatchdogState");
                        mStatus = new Status();
                    }
                    break;
                case MESSAGE_CONTEXT_EVENT:
                    if (isWatchdogEnabled() && !mBroadcastsEnabled) {
                        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
                        mBroadcastsEnabled = true;
                        Slog.i(WWS_TAG, "WifiWatchdogService enabled");
                    } else if (!isWatchdogEnabled() && mBroadcastsEnabled) {
                        mContext.unregisterReceiver(mBroadcastReceiver);
                        removeMessages(SINGLE_DNS_CHECK);
                        removeMessages(CHECK_SEQUENCE_STEP);
                        mBroadcastsEnabled = false;
                        Slog.i(WWS_TAG, "WifiWatchdogService disabled");
                    }
                    break;
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.print("WatchdogStatus: ");
        pw.print("State " + mStatus.state);
        pw.println(", network [" + mStatus.ssid + ", " + mStatus.bssid + "]");
        pw.print("checkCount " + mStatus.numFullDNSchecks);
        pw.println(", bssids: " + mStatus.allBssids);
        pw.print(", hasCheckMessages? " +
                mHandler.hasMessages(WifiWatchdogHandler.CHECK_SEQUENCE_STEP));
        pw.println(" hasSingleCheckMessages? " +
                mHandler.hasMessages(WifiWatchdogHandler.SINGLE_DNS_CHECK));
        pw.println("DNS check log str: " + mDNSCheckLogStr);
        pw.println("lastSingleCheck: " + mStatus.lastSingleCheckTime);
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED
     */
    private Boolean isWalledGardenTestEnabled() {
        return Settings.Secure.getInt(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED, 1) == 1;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_URL
     */
    private String getWalledGardenUrl() {
        String url = Settings.Secure.getString(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL);
        if (TextUtils.isEmpty(url))
            return "http://www.google.com/";
        return url;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_PATTERN
     */
    private String getWalledGardenPattern() {
        String pattern = Settings.Secure.getString(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_PATTERN);
        if (TextUtils.isEmpty(pattern))
            return "<title>.*Google.*</title>";
        return pattern;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_ON
     */
    private boolean isWatchdogEnabled() {
        return Settings.Secure.getInt(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_ON, 1) == 1;
    }
}
