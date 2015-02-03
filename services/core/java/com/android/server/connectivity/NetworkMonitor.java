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
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
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
import android.util.Log;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkAgentInfo;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Random;

/**
 * {@hide}
 */
public class NetworkMonitor extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "NetworkMonitor";
    private static final String DEFAULT_SERVER = "connectivitycheck.android.com";
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
    //     LOGGED_IN_RESULT = one of the CAPTIVE_PORTAL_APP_RETURN_* values below.
    //     RESPONSE_TOKEN   = data fragment from launching Intent
    private static final String ACTION_CAPTIVE_PORTAL_LOGGED_IN =
            "android.net.netmon.captive_portal_logged_in";
    private static final String LOGGED_IN_RESULT = "result";
    private static final String RESPONSE_TOKEN = "response_token";

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
     * arg2 = Number of evaluation attempts to make. (If 0, make INITIAL_ATTEMPTS attempts.)
     */
    public static final int CMD_FORCE_REEVALUATION = BASE + 8;

    /**
     * Message to self indicating captive portal app finished.
     * arg1 = one of: CAPTIVE_PORTAL_APP_RETURN_APPEASED,
     *                CAPTIVE_PORTAL_APP_RETURN_UNWANTED,
     *                CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS
     */
    private static final int CMD_CAPTIVE_PORTAL_APP_FINISHED = BASE + 9;

    /**
     * Request ConnectivityService display provisioning notification.
     * arg1    = Whether to make the notification visible.
     * arg2    = NetID.
     * obj     = Intent to be launched when notification selected by user, null if !arg1.
     */
    public static final int EVENT_PROVISIONING_NOTIFICATION = BASE + 10;

    /**
     * Message to self indicating sign-in app bypassed captive portal.
     */
    private static final int EVENT_APP_BYPASSED_CAPTIVE_PORTAL = BASE + 11;

    /**
     * Message to self indicating no sign-in app responded.
     */
    private static final int EVENT_NO_APP_RESPONSE = BASE + 12;

    /**
     * Message to self indicating sign-in app indicates sign-in is not possible.
     */
    private static final int EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE = BASE + 13;

    /**
     * Return codes from captive portal sign-in app.
     */
    public static final int CAPTIVE_PORTAL_APP_RETURN_APPEASED = 0;
    public static final int CAPTIVE_PORTAL_APP_RETURN_UNWANTED = 1;
    public static final int CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS = 2;

    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    // Default to 30s linger time-out.
    private static final int DEFAULT_LINGER_DELAY_MS = 30000;
    private final int mLingerDelayMs;
    private int mLingerToken = 0;

    // Negative values disable reevaluation.
    private static final String REEVALUATE_DELAY_PROPERTY = "persist.netmon.reeval_delay";
    // When connecting, attempt to validate 3 times, pausing 5s between them.
    private static final int DEFAULT_REEVALUATE_DELAY_MS = 5000;
    private static final int INITIAL_ATTEMPTS = 3;
    // If a network is not validated, make one attempt every 10 mins to see if it starts working.
    private static final int REEVALUATE_PAUSE_MS = 10*60*1000;
    private static final int PERIODIC_ATTEMPTS = 1;
    // When an application calls reportBadNetwork, only make one attempt.
    private static final int REEVALUATE_ATTEMPTS = 1;
    private final int mReevaluateDelayMs;
    private int mReevaluateToken = 0;
    private static final int INVALID_UID = -1;
    private int mUidResponsibleForReeval = INVALID_UID;

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final NetworkAgentInfo mNetworkAgentInfo;
    private final TelephonyManager mTelephonyManager;
    private final WifiManager mWifiManager;
    private final AlarmManager mAlarmManager;
    private final NetworkRequest mDefaultRequest;

    private String mServer;
    private boolean mIsCaptivePortalCheckEnabled = false;

    // Set if the user explicitly selected "Do not use this network" in captive portal sign-in app.
    private boolean mUserDoesNotWant = false;

    // How many times we should attempt validation. Only checked in EvaluatingState; must be set
    // before entering EvaluatingState. Note that whatever code causes us to transition to
    // EvaluatingState last decides how many attempts will be made, so if one codepath were to
    // enter EvaluatingState with a specific number of attempts, and then another were to enter it
    // with a different number of attempts, the second number would be used. This is not currently
    // a problem because EvaluatingState is not reentrant.
    private int mMaxAttempts;

    public boolean systemReady = false;

    private final State mDefaultState = new DefaultState();
    private final State mOfflineState = new OfflineState();
    private final State mValidatedState = new ValidatedState();
    private final State mMaybeNotifyState = new MaybeNotifyState();
    private final State mEvaluatingState = new EvaluatingState();
    private final State mCaptivePortalState = new CaptivePortalState();
    private final State mLingeringState = new LingeringState();

    private CaptivePortalLoggedInBroadcastReceiver mCaptivePortalLoggedInBroadcastReceiver = null;
    private String mCaptivePortalLoggedInResponseToken = null;

    public NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo,
            NetworkRequest defaultRequest) {
        // Add suffix indicating which NetworkMonitor we're talking about.
        super(TAG + networkAgentInfo.name());

        mContext = context;
        mConnectivityServiceHandler = handler;
        mNetworkAgentInfo = networkAgentInfo;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mDefaultRequest = defaultRequest;

        addState(mDefaultState);
        addState(mOfflineState, mDefaultState);
        addState(mValidatedState, mDefaultState);
        addState(mMaybeNotifyState, mDefaultState);
            addState(mEvaluatingState, mMaybeNotifyState);
            addState(mCaptivePortalState, mMaybeNotifyState);
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

        mCaptivePortalLoggedInResponseToken = String.valueOf(new Random().nextLong());

        start();
    }

    @Override
    protected void log(String s) {
        Log.d(TAG + "/" + mNetworkAgentInfo.name(), s);
    }

    // DefaultState is the parent of all States.  It exists only to handle CMD_* messages but
    // does not entail any real state (hence no enter() or exit() routines).
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
                    mMaxAttempts = INITIAL_ATTEMPTS;
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_NETWORK_DISCONNECTED:
                    if (DBG) log("Disconnected - quitting");
                    if (mCaptivePortalLoggedInBroadcastReceiver != null) {
                        mContext.unregisterReceiver(mCaptivePortalLoggedInBroadcastReceiver);
                        mCaptivePortalLoggedInBroadcastReceiver = null;
                    }
                    quit();
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    if (DBG) log("Forcing reevaluation");
                    mUidResponsibleForReeval = message.arg1;
                    mMaxAttempts = message.arg2 != 0 ? message.arg2 : REEVALUATE_ATTEMPTS;
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_CAPTIVE_PORTAL_APP_FINISHED:
                    // Previous token was broadcast, come up with a new one.
                    mCaptivePortalLoggedInResponseToken = String.valueOf(new Random().nextLong());
                    switch (message.arg1) {
                        case CAPTIVE_PORTAL_APP_RETURN_APPEASED:
                        case CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS:
                            transitionTo(mValidatedState);
                            break;
                        case CAPTIVE_PORTAL_APP_RETURN_UNWANTED:
                            mUserDoesNotWant = true;
                            // TODO: Should teardown network.
                            transitionTo(mOfflineState);
                            break;
                    }
                    return HANDLED;
                default:
                    return HANDLED;
            }
        }
    }

    // Being in the OfflineState State indicates a Network is unwanted or failed validation.
    private class OfflineState extends State {
        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_INVALID, 0, mNetworkAgentInfo));
            if (!mUserDoesNotWant) {
                sendMessageDelayed(CMD_FORCE_REEVALUATION, 0 /* no UID */,
                        PERIODIC_ATTEMPTS, REEVALUATE_PAUSE_MS);
            }
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

        @Override
        public void exit() {
             // NOTE: This removes the delayed message posted by enter() but will inadvertently
             // remove any other CMD_FORCE_REEVALUATION in the message queue.  At the moment this
             // is harmless.  If in the future this becomes problematic a different message could
             // be used.
             removeMessages(CMD_FORCE_REEVALUATION);
        }
    }

    // Being in the ValidatedState State indicates a Network is:
    // - Successfully validated, or
    // - Wanted "as is" by the user, or
    // - Does not satsify the default NetworkRequest and so validation has been skipped.
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

    // Being in the MaybeNotifyState State indicates the user may have been notified that sign-in
    // is required.  This State takes care to clear the notification upon exit from the State.
    private class MaybeNotifyState extends State {
        @Override
        public void exit() {
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 0,
                    mNetworkAgentInfo.network.netId, null);
            mConnectivityServiceHandler.sendMessage(message);
        }
    }

    // Being in the EvaluatingState State indicates the Network is being evaluated for internet
    // connectivity.
    private class EvaluatingState extends State {
        private int mAttempt;

        @Override
        public void enter() {
            mAttempt = 1;
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
                    // Don't bother validating networks that don't satisify the default request.
                    // This includes:
                    //  - VPNs which can be considered explicitly desired by the user and the
                    //    user's desire trumps whether the network validates.
                    //  - Networks that don't provide internet access.  It's unclear how to
                    //    validate such networks.
                    //  - Untrusted networks.  It's unsafe to prompt the user to sign-in to
                    //    such networks and the user didn't express interest in connecting to
                    //    such networks (an app did) so the user may be unhappily surprised when
                    //    asked to sign-in to a network they didn't want to connect to in the
                    //    first place.  Validation could be done to adjust the network scores
                    //    however these networks are app-requested and may not be intended for
                    //    general usage, in which case general validation may not be an accurate
                    //    measure of the network's quality.  Only the app knows how to evaluate
                    //    the network so don't bother validating here.  Furthermore sending HTTP
                    //    packets over the network may be undesirable, for example an extremely
                    //    expensive metered network, or unwanted leaking of the User Agent string.
                    if (!mDefaultRequest.networkCapabilities.satisfiedByNetworkCapabilities(
                            mNetworkAgentInfo.networkCapabilities)) {
                        transitionTo(mValidatedState);
                        return HANDLED;
                    }
                    // Note: This call to isCaptivePortal() could take up to a minute. Resolving the
                    // server's IP addresses could hit the DNS timeout, and attempting connections
                    // to each of the server's several IP addresses (currently one IPv4 and one
                    // IPv6) could each take SOCKET_TIMEOUT_MS.  During this time this StateMachine
                    // will be unresponsive. isCaptivePortal() could be executed on another Thread
                    // if this is found to cause problems.
                    int httpResponseCode = isCaptivePortal();
                    if (httpResponseCode == 204) {
                        transitionTo(mValidatedState);
                    } else if (httpResponseCode >= 200 && httpResponseCode <= 399) {
                        transitionTo(mCaptivePortalState);
                    } else if (++mAttempt > mMaxAttempts) {
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
        private final int mToken;
        private final int mWhat;
        private final String mAction;
        CustomIntentReceiver(String action, int token, int what) {
            mToken = token;
            mWhat = what;
            mAction = action + "_" + mNetworkAgentInfo.network.netId + "_" + token;
            mContext.registerReceiver(this, new IntentFilter(mAction));
        }
        public PendingIntent getPendingIntent() {
            return PendingIntent.getBroadcast(mContext, 0, new Intent(mAction), 0);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(mAction)) sendMessage(obtainMessage(mWhat, mToken));
        }
    }

    private class CaptivePortalLoggedInBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Integer.parseInt(intent.getStringExtra(Intent.EXTRA_TEXT)) ==
                    mNetworkAgentInfo.network.netId &&
                    mCaptivePortalLoggedInResponseToken.equals(
                            intent.getStringExtra(RESPONSE_TOKEN))) {
                sendMessage(obtainMessage(CMD_CAPTIVE_PORTAL_APP_FINISHED,
                        Integer.parseInt(intent.getStringExtra(LOGGED_IN_RESULT)), 0));
            }
        }
    }

    // Being in the CaptivePortalState State indicates a captive portal was detected and the user
    // has been shown a notification to sign-in.
    private class CaptivePortalState extends State {
        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_INVALID, 0, mNetworkAgentInfo));

            // Assemble Intent to launch captive portal sign-in app.
            final Intent intent = new Intent(Intent.ACTION_SEND);
            // Intent cannot use extras because PendingIntent.getActivity will merge matching
            // Intents erasing extras.  Use data instead of extras to encode NetID.
            intent.setData(Uri.fromParts("netid", Integer.toString(mNetworkAgentInfo.network.netId),
                    mCaptivePortalLoggedInResponseToken));
            intent.setComponent(new ComponentName("com.android.captiveportallogin",
                    "com.android.captiveportallogin.CaptivePortalLoginActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

            if (mCaptivePortalLoggedInBroadcastReceiver == null) {
                // Wait for result.
                mCaptivePortalLoggedInBroadcastReceiver =
                        new CaptivePortalLoggedInBroadcastReceiver();
                final IntentFilter filter = new IntentFilter(ACTION_CAPTIVE_PORTAL_LOGGED_IN);
                mContext.registerReceiver(mCaptivePortalLoggedInBroadcastReceiver, filter);
            }
            // Initiate notification to sign-in.
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 1,
                    mNetworkAgentInfo.network.netId,
                    PendingIntent.getActivity(mContext, 0, intent, 0));
            mConnectivityServiceHandler.sendMessage(message);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            return NOT_HANDLED;
        }
    }

    // Being in the LingeringState State indicates a Network's validated bit is true and it once
    // was the highest scoring Network satisfying a particular NetworkRequest, but since then
    // another Network satsified the NetworkRequest with a higher score and hence this Network
    // is "lingered" for a fixed period of time before it is disconnected.  This period of time
    // allows apps to wrap up communication and allows for seamless reactivation if the other
    // higher scoring Network happens to disconnect.
    private class LingeringState extends State {
        private static final String ACTION_LINGER_EXPIRED = "android.net.netmon.lingerExpired";

        private CustomIntentReceiver mBroadcastReceiver;
        private PendingIntent mIntent;

        @Override
        public void enter() {
            mLingerToken = new Random().nextInt();
            mBroadcastReceiver = new CustomIntentReceiver(ACTION_LINGER_EXPIRED, mLingerToken,
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
                case CMD_CAPTIVE_PORTAL_APP_FINISHED:
                    // Ignore user network determination as this could abort linger timeout.
                    // Networks are only lingered once validated because:
                    // - Unvalidated networks are never lingered (see rematchNetworkAndRequests).
                    // - Once validated, a Network's validated bit is never cleared.
                    // Since networks are only lingered after being validated a user's
                    // determination will not change the death sentence that lingering entails:
                    // - If the user wants to use the network or bypasses the captive portal,
                    //   the network's score will not be increased beyond its current value
                    //   because it is already validated.  Without a score increase there is no
                    //   chance of reactivation (i.e. aborting linger timeout).
                    // - If the user does not want the network, lingering will disconnect the
                    //   network anyhow.
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
            // On networks with a PAC instead of fetching a URL that should result in a 204
            // reponse, we instead simply fetch the PAC script.  This is done for a few reasons:
            // 1. At present our PAC code does not yet handle multiple PACs on multiple networks
            //    until something like https://android-review.googlesource.com/#/c/115180/ lands.
            //    Network.openConnection() will ignore network-specific PACs and instead fetch
            //    using NO_PROXY.  If a PAC is in place, the only fetch we know will succeed with
            //    NO_PROXY is the fetch of the PAC itself.
            // 2. To proxy the generate_204 fetch through a PAC would require a number of things
            //    happen before the fetch can commence, namely:
            //        a) the PAC script be fetched
            //        b) a PAC script resolver service be fired up and resolve mServer
            //    Network validation could be delayed until these prerequisities are satisifed or
            //    could simply be left to race them.  Neither is an optimal solution.
            // 3. PAC scripts are sometimes used to block or restrict Internet access and may in
            //    fact block fetching of the generate_204 URL which would lead to false negative
            //    results for network validation.
            boolean fetchPac = false;
            {
                final ProxyInfo proxyInfo = mNetworkAgentInfo.linkProperties.getHttpProxy();
                if (proxyInfo != null && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl())) {
                    url = new URL(proxyInfo.getPacFileUrl().toString());
                    fetchPac = true;
                }
            }
            if (DBG) {
                log("Checking " + url.toString() + " on " +
                        mNetworkAgentInfo.networkInfo.getExtraInfo());
            }
            urlConnection = (HttpURLConnection) mNetworkAgentInfo.network.openConnection(url);
            urlConnection.setInstanceFollowRedirects(fetchPac);
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

            if (httpResponseCode == 200 && fetchPac) {
                if (DBG) log("PAC fetch 200 response interpreted as 204 response.");
                httpResponseCode = 204;
            }

            sendNetworkConditionsBroadcast(true /* response received */,
                    httpResponseCode != 204 /* isCaptivePortal */,
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
