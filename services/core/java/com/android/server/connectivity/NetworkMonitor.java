/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.connectivity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.TelephonyManager;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkAgentInfo;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * {@hide}
 */
public class NetworkMonitor extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "NetworkMonitor";
    private static final String DEFAULT_SERVER = "clients3.google.com";
    private static final int SOCKET_TIMEOUT_MS = 10000;
    public static final String ACTION_NETWORK_CONDITIONS_MEASURED =
            "android.net.conn.NETWORK_CONDITIONS_MEASURED";
    public static final String EXTRA_CONNECTIVITY_TYPE = "extra_connectivity_type";
    public static final String EXTRA_NETWORK_TYPE = "extra_network_type";
    public static final String EXTRA_RESPONSE_RECEIVED = "extra_response_received";
    public static final String EXTRA_IS_CAPTIVE_PORTAL = "extra_is_captive_portal";
    public static final String EXTRA_CELL_ID = "extra_cellid";
    public static final String EXTRA_SSID = "extra_ssid";
    public static final String EXTRA_BSSID = "extra_bssid";
    /** real time since boot */
    public static final String EXTRA_REQUEST_TIMESTAMP_MS = "extra_request_timestamp_ms";
    public static final String EXTRA_RESPONSE_TIMESTAMP_MS = "extra_response_timestamp_ms";

    private static final String PERMISSION_ACCESS_NETWORK_CONDITIONS =
            "android.permission.ACCESS_NETWORK_CONDITIONS";

    // Keep these in sync with CaptivePortalLoginActivity.java.
    // Intent broadcast from CaptivePortalLogin indicating sign-in is complete.
    // Extras:
    //     EXTRA_TEXT       = netId
    //     LOGGED_IN_RESULT = "1" if we should use network, "0" if not.
    private static final String ACTION_CAPTIVE_PORTAL_LOGGED_IN =
            "android.net.netmon.captive_portal_logged_in";
    private static final String LOGGED_IN_RESULT = "result";

    // After a network has been tested this result can be sent with EVENT_NETWORK_TESTED.
    // The network should be used as a default internet connection.  It was found to be:
    // 1. a functioning network providing internet access, or
    // 2. a captive portal and the user decided to use it as is.
    public static final int NETWORK_TEST_RESULT_VALID = 0;
    // After a network has been tested this result can be sent with EVENT_NETWORK_TESTED.
    // The network should not be used as a default internet connection.  It was found to be:
    // 1. a captive portal and the user is prompted to sign-in, or
    // 2. a captive portal and the user did not want to use it, or
    // 3. a broken network (e.g. DNS failed, connect failed, HTTP request failed).
    public static final int NETWORK_TEST_RESULT_INVALID = 1;

    private static final int BASE = Protocol.BASE_NETWORK_MONITOR;

    /**
     * Inform NetworkMonitor that their network is connected.
     * Initiates Network Validation.
     */
    public static final int CMD_NETWORK_CONNECTED = BASE + 1;

    /**
     * Inform ConnectivityService that the network has been tested.
     * obj = NetworkAgentInfo
     * arg1 = One of the NETWORK_TESTED_RESULT_* constants.
     */
    public static final int EVENT_NETWORK_TESTED = BASE + 2;

    /**
     * Inform NetworkMonitor to linger a network.  The Monitor should
     * start a timer and/or start watching for zero live connections while
     * moving towards LINGER_COMPLETE.  After the Linger period expires
     * (or other events mark the end of the linger state) the LINGER_COMPLETE
     * event should be sent and the network will be shut down.  If a
     * CMD_NETWORK_CONNECTED happens before the LINGER completes
     * it indicates further desire to keep the network alive and so
     * the LINGER is aborted.
     */
    public static final int CMD_NETWORK_LINGER = BASE + 3;

    /**
     * Message to self indicating linger delay has expired.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_LINGER_EXPIRED = BASE + 4;

    /**
     * Inform ConnectivityService that the network LINGER period has
     * expired.
     * obj = NetworkAgentInfo
     */
    public static final int EVENT_NETWORK_LINGER_COMPLETE = BASE + 5;

    /**
     * Message to self indicating it's time to evaluate a network's connectivity.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_REEVALUATE = BASE + 6;

    /**
     * Inform NetworkMonitor that the network has disconnected.
     */
    public static final int CMD_NETWORK_DISCONNECTED = BASE + 7;

    /**
     * Force evaluation even if it has succeeded in the past.
     * arg1 = UID responsible for requesting this reeval.  Will be billed for data.
     */
    public static final int CMD_FORCE_REEVALUATION = BASE + 8;

    /**
     * Message to self indicating captive portal login is complete.
     * arg1 = Token to ignore old messages.
     * arg2 = 1 if we should use this network, 0 otherwise.
     */
    private static final int CMD_CAPTIVE_PORTAL_LOGGED_IN = BASE + 9;

    /**
     * Message to self indicating user desires to log into captive portal.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_USER_WANTS_SIGN_IN = BASE + 10;

    /**
     * Request ConnectivityService display provisioning notification.
     * arg1    = Whether to make the notification visible.
     * arg2    = NetID.
     * obj     = Intent to be launched when notification selected by user, null if !arg1.
     */
    public static final int EVENT_PROVISIONING_NOTIFICATION = BASE + 11;

    /**
     * Message to self indicating sign-in app bypassed captive portal.
     */
    private static final int EVENT_APP_BYPASSED_CAPTIVE_PORTAL = BASE + 12;

    /**
     * Message to self indicating no sign-in app responded.
     */
    private static final int EVENT_NO_APP_RESPONSE = BASE + 13;

    /**
     * Message to self indicating sign-in app indicates sign-in is not possible.
     */
    private static final int EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE = BASE + 14;

    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    // Default to 30s linger time-out.
    private static final int DEFAULT_LINGER_DELAY_MS = 30000;
    private final int mLingerDelayMs;
    private int mLingerToken = 0;

    // Negative values disable reevaluation.
    private static final String REEVALUATE_DELAY_PROPERTY = "persist.netmon.reeval_delay";
    // Default to 5s reevaluation delay.
    private static final int DEFAULT_REEVALUATE_DELAY_MS = 5000;
    private static final int MAX_RETRIES = 10;
    private final int mReevaluateDelayMs;
    private int mReevaluateToken = 0;
    private static final int INVALID_UID = -1;
    private int mUidResponsibleForReeval = INVALID_UID;

    private int mCaptivePortalLoggedInToken = 0;
    private int mUserPromptedToken = 0;

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final NetworkAgentInfo mNetworkAgentInfo;
    private final TelephonyManager mTelephonyManager;
    private final WifiManager mWifiManager;
    private final AlarmManager mAlarmManager;

    private String mServer;
    private boolean mIsCaptivePortalCheckEnabled = false;

    // Set if the user explicitly selected "Do not use this network" in captive portal sign-in app.
    private boolean mUserDoesNotWant = false;

    public boolean systemReady = false;

    private State mDefaultState = new DefaultState();
    private State mOfflineState = new OfflineState();
    private State mValidatedState = new ValidatedState();
    private State mEvaluatingState = new EvaluatingState();
    private State mUserPromptedState = new UserPromptedState();
    private State mCaptivePortalState = new CaptivePortalState();
    private State mLingeringState = new LingeringState();

    public NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo) {
        // Add suffix indicating which NetworkMonitor we're talking about.
        super(TAG + networkAgentInfo.name());

        mContext = context;
        mConnectivityServiceHandler = handler;
        mNetworkAgentInfo = networkAgentInfo;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        addState(mDefaultState);
        addState(mOfflineState, mDefaultState);
        addState(mValidatedState, mDefaultState);
        addState(mEvaluatingState, mDefaultState);
        addState(mUserPromptedState, mDefaultState);
        addState(mCaptivePortalState, mDefaultState);
        addState(mLingeringState, mDefaultState);
        setInitialState(mDefaultState);

        mServer = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_SERVER);
        if (mServer == null) mServer = DEFAULT_SERVER;

        mLingerDelayMs = SystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);
        mReevaluateDelayMs = SystemProperties.getInt(REEVALUATE_DELAY_PROPERTY,
                DEFAULT_REEVALUATE_DELAY_MS);

        mIsCaptivePortalCheckEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED, 1) == 1;

        start();
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_NETWORK_LINGER:
                    if (DBG) log("Lingering");
                    transitionTo(mLingeringState);
                    return HANDLED;
                case CMD_NETWORK_CONNECTED:
                    if (DBG) log("Connected");
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_NETWORK_DISCONNECTED:
                    if (DBG) log("Disconnected - quitting");
                    quit();
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    if (DBG) log("Forcing reevaluation");
                    mUidResponsibleForReeval = message.arg1;
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                default:
                    return HANDLED;
            }
        }
    }

    private class OfflineState extends State {
        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_INVALID, 0, mNetworkAgentInfo));
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
                        switch (message.what) {
                case CMD_FORCE_REEVALUATION:
                    // If the user has indicated they explicitly do not want to use this network,
                    // don't allow a reevaluation as this will be pointless and could result in
                    // the user being annoyed with repeated unwanted notifications.
                    return mUserDoesNotWant ? HANDLED : NOT_HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    private class ValidatedState extends State {
        @Override
        public void enter() {
            if (DBG) log("Validated");
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_VALID, 0, mNetworkAgentInfo));
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    transitionTo(mValidatedState);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    private class EvaluatingState extends State {
        private int mRetries;

        @Override
        public void enter() {
            mRetries = 0;
            sendMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
            if (mUidResponsibleForReeval != INVALID_UID) {
                TrafficStats.setThreadStatsUid(mUidResponsibleForReeval);
                mUidResponsibleForReeval = INVALID_UID;
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_REEVALUATE:
                    if (message.arg1 != mReevaluateToken)
                        return HANDLED;
                    if (mNetworkAgentInfo.isVPN()) {
                        transitionTo(mValidatedState);
                        return HANDLED;
                    }
                    // If network provides no internet connectivity adjust evaluation.
                    if (!mNetworkAgentInfo.networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        // TODO: Try to verify something works.  Do all gateways respond to pings?
                        transitionTo(mValidatedState);
                        return HANDLED;
                    }
                    int httpResponseCode = isCaptivePortal();
                    if (httpResponseCode == 204) {
                        transitionTo(mValidatedState);
                    } else if (httpResponseCode >= 200 && httpResponseCode <= 399) {
                        transitionTo(mUserPromptedState);
                    } else if (++mRetries > MAX_RETRIES) {
                        transitionTo(mOfflineState);
                    } else if (mReevaluateDelayMs >= 0) {
                        Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
                        sendMessageDelayed(msg, mReevaluateDelayMs);
                    }
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    // Ignore duplicate requests.
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            TrafficStats.clearThreadStatsUid();
        }
    }

    // BroadcastReceiver that waits for a particular Intent and then posts a message.
    private class CustomIntentReceiver extends BroadcastReceiver {
        private final Message mMessage;
        private final String mAction;
        CustomIntentReceiver(String action, int token, int message) {
            mMessage = obtainMessage(message, token);
            mAction = action + "_" + mNetworkAgentInfo.network.netId + "_" + token;
            mContext.registerReceiver(this, new IntentFilter(mAction));
        }
        public PendingIntent getPendingIntent() {
            return PendingIntent.getBroadcast(mContext, 0, new Intent(mAction), 0);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(mAction)) sendMessage(mMessage);
        }
    }

    private class UserPromptedState extends State {
        // Intent broadcast when user selects sign-in notification.
        private static final String ACTION_SIGN_IN_REQUESTED =
                "android.net.netmon.sign_in_requested";

        private CustomIntentReceiver mUserRespondedBroadcastReceiver;

        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_INVALID, 0, mNetworkAgentInfo));
            // Wait for user to select sign-in notifcation.
            mUserRespondedBroadcastReceiver = new CustomIntentReceiver(ACTION_SIGN_IN_REQUESTED,
                    ++mUserPromptedToken, CMD_USER_WANTS_SIGN_IN);
            // Initiate notification to sign-in.
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 1,
                    mNetworkAgentInfo.network.netId,
                    mUserRespondedBroadcastReceiver.getPendingIntent());
            mConnectivityServiceHandler.sendMessage(message);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_USER_WANTS_SIGN_IN:
                    if (message.arg1 != mUserPromptedToken)
                        return HANDLED;
                    transitionTo(mCaptivePortalState);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 0,
                    mNetworkAgentInfo.network.netId, null);
            mConnectivityServiceHandler.sendMessage(message);
            mContext.unregisterReceiver(mUserRespondedBroadcastReceiver);
            mUserRespondedBroadcastReceiver = null;
        }
    }

    private class CaptivePortalState extends State {
        private class CaptivePortalLoggedInBroadcastReceiver extends BroadcastReceiver {
            private final int mToken;

            CaptivePortalLoggedInBroadcastReceiver(int token) {
                mToken = token;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                if (Integer.parseInt(intent.getStringExtra(Intent.EXTRA_TEXT)) ==
                        mNetworkAgentInfo.network.netId) {
                    sendMessage(obtainMessage(CMD_CAPTIVE_PORTAL_LOGGED_IN, mToken,
                            Integer.parseInt(intent.getStringExtra(LOGGED_IN_RESULT))));
                }
            }
        }

        private CaptivePortalLoggedInBroadcastReceiver mCaptivePortalLoggedInBroadcastReceiver;

        @Override
        public void enter() {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, String.valueOf(mNetworkAgentInfo.network.netId));
            intent.setType("text/plain");
            intent.setComponent(new ComponentName("com.android.captiveportallogin",
                    "com.android.captiveportallogin.CaptivePortalLoginActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

            // Wait for result.
            mCaptivePortalLoggedInBroadcastReceiver = new CaptivePortalLoggedInBroadcastReceiver(
                    ++mCaptivePortalLoggedInToken);
            IntentFilter filter = new IntentFilter(ACTION_CAPTIVE_PORTAL_LOGGED_IN);
            mContext.registerReceiver(mCaptivePortalLoggedInBroadcastReceiver, filter);
            // Initiate app to log in.
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_CAPTIVE_PORTAL_LOGGED_IN:
                    if (message.arg1 != mCaptivePortalLoggedInToken)
                        return HANDLED;
                    if (message.arg2 == 0) {
                        mUserDoesNotWant = true;
                        // TODO: Should teardown network.
                        transitionTo(mOfflineState);
                    } else {
                        transitionTo(mValidatedState);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            mContext.unregisterReceiver(mCaptivePortalLoggedInBroadcastReceiver);
            mCaptivePortalLoggedInBroadcastReceiver = null;
        }
    }

    private class LingeringState extends State {
        private static final String ACTION_LINGER_EXPIRED = "android.net.netmon.lingerExpired";

        private CustomIntentReceiver mBroadcastReceiver;
        private PendingIntent mIntent;

        @Override
        public void enter() {
            mBroadcastReceiver = new CustomIntentReceiver(ACTION_LINGER_EXPIRED, ++mLingerToken,
                    CMD_LINGER_EXPIRED);
            mIntent = mBroadcastReceiver.getPendingIntent();
            long wakeupTime = SystemClock.elapsedRealtime() + mLingerDelayMs;
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, wakeupTime,
                    // Give a specific window so we aren't subject to unknown inexactitude.
                    mLingerDelayMs / 6, mIntent);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    // Go straight to active as we've already evaluated.
                    transitionTo(mValidatedState);
                    return HANDLED;
                case CMD_LINGER_EXPIRED:
                    if (message.arg1 != mLingerToken)
                        return HANDLED;
                    mConnectivityServiceHandler.sendMessage(
                            obtainMessage(EVENT_NETWORK_LINGER_COMPLETE, mNetworkAgentInfo));
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    // Ignore reevaluation attempts when lingering.  A reevaluation could result
                    // in a transition to the validated state which would abort the linger
                    // timeout.  Lingering is the result of score assessment; validity is
                    // irrelevant.
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            mAlarmManager.cancel(mIntent);
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
    }

    /**
     * Do a URL fetch on a known server to see if we get the data we expect.
     * Returns HTTP response code.
     */
    private int isCaptivePortal() {
        if (!mIsCaptivePortalCheckEnabled) return 204;

        HttpURLConnection urlConnection = null;
        int httpResponseCode = 599;
        try {
            URL url = new URL("http", mServer, "/generate_204");
            if (DBG) {
                log("Checking " + url.toString() + " on " +
                        mNetworkAgentInfo.networkInfo.getExtraInfo());
            }
            urlConnection = (HttpURLConnection) mNetworkAgentInfo.network.openConnection(url);
            urlConnection.setInstanceFollowRedirects(false);
            urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setUseCaches(false);

            // Time how long it takes to get a response to our request
            long requestTimestamp = SystemClock.elapsedRealtime();

            urlConnection.getInputStream();

            // Time how long it takes to get a response to our request
            long responseTimestamp = SystemClock.elapsedRealtime();

            httpResponseCode = urlConnection.getResponseCode();
            if (DBG) {
                log("isCaptivePortal: ret=" + httpResponseCode +
                        " headers=" + urlConnection.getHeaderFields());
            }
            // NOTE: We may want to consider an "HTTP/1.0 204" response to be a captive
            // portal.  The only example of this seen so far was a captive portal.  For
            // the time being go with prior behavior of assuming it's not a captive
            // portal.  If it is considered a captive portal, a different sign-in URL
            // is needed (i.e. can't browse a 204).  This could be the result of an HTTP
            // proxy server.

            // Consider 200 response with "Content-length=0" to not be a captive portal.
            // There's no point in considering this a captive portal as the user cannot
            // sign-in to an empty page.  Probably the result of a broken transparent proxy.
            // See http://b/9972012.
            if (httpResponseCode == 200 && urlConnection.getContentLength() == 0) {
                if (DBG) log("Empty 200 response interpreted as 204 response.");
                httpResponseCode = 204;
            }

            sendNetworkConditionsBroadcast(true /* response received */, httpResponseCode == 204,
                    requestTimestamp, responseTimestamp);
        } catch (IOException e) {
            if (DBG) log("Probably not a portal: exception " + e);
            if (httpResponseCode == 599) {
                // TODO: Ping gateway and DNS server and log results.
            }
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
        return httpResponseCode;
    }

    /**
     * @param responseReceived - whether or not we received a valid HTTP response to our request.
     * If false, isCaptivePortal and responseTimestampMs are ignored
     * TODO: This should be moved to the transports.  The latency could be passed to the transports
     * along with the captive portal result.  Currently the TYPE_MOBILE broadcasts appear unused so
     * perhaps this could just be added to the WiFi transport only.
     */
    private void sendNetworkConditionsBroadcast(boolean responseReceived, boolean isCaptivePortal,
            long requestTimestampMs, long responseTimestampMs) {
        if (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0) == 0) {
            if (DBG) log("Don't send network conditions - lacking user consent.");
            return;
        }

        if (systemReady == false) return;

        Intent latencyBroadcast = new Intent(ACTION_NETWORK_CONDITIONS_MEASURED);
        switch (mNetworkAgentInfo.networkInfo.getType()) {
            case ConnectivityManager.TYPE_WIFI:
                WifiInfo currentWifiInfo = mWifiManager.getConnectionInfo();
                if (currentWifiInfo != null) {
                    // NOTE: getSSID()'s behavior changed in API 17; before that, SSIDs were not
                    // surrounded by double quotation marks (thus violating the Javadoc), but this
                    // was changed to match the Javadoc in API 17. Since clients may have started
                    // sanitizing the output of this method since API 17 was released, we should
                    // not change it here as it would become impossible to tell whether the SSID is
                    // simply being surrounded by quotes due to the API, or whether those quotes
                    // are actually part of the SSID.
                    latencyBroadcast.putExtra(EXTRA_SSID, currentWifiInfo.getSSID());
                    latencyBroadcast.putExtra(EXTRA_BSSID, currentWifiInfo.getBSSID());
                } else {
                    if (DBG) logw("network info is TYPE_WIFI but no ConnectionInfo found");
                    return;
                }
                break;
            case ConnectivityManager.TYPE_MOBILE:
                latencyBroadcast.putExtra(EXTRA_NETWORK_TYPE, mTelephonyManager.getNetworkType());
                List<CellInfo> info = mTelephonyManager.getAllCellInfo();
                if (info == null) return;
                int numRegisteredCellInfo = 0;
                for (CellInfo cellInfo : info) {
                    if (cellInfo.isRegistered()) {
                        numRegisteredCellInfo++;
                        if (numRegisteredCellInfo > 1) {
                            if (DBG) log("more than one registered CellInfo.  Can't " +
                                    "tell which is active.  Bailing.");
                            return;
                        }
                        if (cellInfo instanceof CellInfoCdma) {
                            CellIdentityCdma cellId = ((CellInfoCdma) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoGsm) {
                            CellIdentityGsm cellId = ((CellInfoGsm) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoLte) {
                            CellIdentityLte cellId = ((CellInfoLte) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoWcdma) {
                            CellIdentityWcdma cellId = ((CellInfoWcdma) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(EXTRA_CELL_ID, cellId);
                        } else {
                            if (DBG) logw("Registered cellinfo is unrecognized");
                            return;
                        }
                    }
                }
                break;
            default:
                return;
        }
        latencyBroadcast.putExtra(EXTRA_CONNECTIVITY_TYPE, mNetworkAgentInfo.networkInfo.getType());
        latencyBroadcast.putExtra(EXTRA_RESPONSE_RECEIVED, responseReceived);
        latencyBroadcast.putExtra(EXTRA_REQUEST_TIMESTAMP_MS, requestTimestampMs);

        if (responseReceived) {
            latencyBroadcast.putExtra(EXTRA_IS_CAPTIVE_PORTAL, isCaptivePortal);
            latencyBroadcast.putExtra(EXTRA_RESPONSE_TIMESTAMP_MS, responseTimestampMs);
        }
        mContext.sendBroadcastAsUser(latencyBroadcast, UserHandle.CURRENT,
                PERMISSION_ACCESS_NETWORK_CONDITIONS);
    }
}
