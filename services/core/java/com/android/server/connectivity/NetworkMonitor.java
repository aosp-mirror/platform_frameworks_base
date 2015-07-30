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

import static android.net.CaptivePortal.APP_RETURN_DISMISSED;
import static android.net.CaptivePortal.APP_RETURN_UNWANTED;
import static android.net.CaptivePortal.APP_RETURN_WANTED_AS_IS;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
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
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.LocalLog.ReadOnlyLocalLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.connectivity.NetworkAgentInfo;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.Random;

/**
 * {@hide}
 */
public class NetworkMonitor extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "NetworkMonitor";
    private static final String DEFAULT_SERVER = "connectivitycheck.gstatic.com";
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
     * Message to self indicating captive portal app finished.
     * arg1 = one of: APP_RETURN_DISMISSED,
     *                APP_RETURN_UNWANTED,
     *                APP_RETURN_WANTED_AS_IS
     * obj = mCaptivePortalLoggedInResponseToken as String
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
     * Message to self indicating sign-in app should be launched.
     * Sent by mLaunchCaptivePortalAppBroadcastReceiver when the
     * user touches the sign in notification.
     */
    private static final int CMD_LAUNCH_CAPTIVE_PORTAL_APP = BASE + 11;

    /**
     * Retest network to see if captive portal is still in place.
     * arg1 = UID responsible for requesting this reeval.  Will be billed for data.
     *        0 indicates self-initiated, so nobody to blame.
     */
    private static final int CMD_CAPTIVE_PORTAL_RECHECK = BASE + 12;

    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    // Default to 30s linger time-out.  Modifyable only for testing.
    private static int DEFAULT_LINGER_DELAY_MS = 30000;
    private final int mLingerDelayMs;
    private int mLingerToken = 0;

    // Start mReevaluateDelayMs at this value and double.
    private static final int INITIAL_REEVALUATE_DELAY_MS = 1000;
    private static final int MAX_REEVALUATE_DELAY_MS = 10*60*1000;
    // Before network has been evaluated this many times, ignore repeated reevaluate requests.
    private static final int IGNORE_REEVALUATE_ATTEMPTS = 5;
    private int mReevaluateToken = 0;
    private static final int INVALID_UID = -1;
    private int mUidResponsibleForReeval = INVALID_UID;
    // Stop blaming UID that requested re-evaluation after this many attempts.
    private static final int BLAME_FOR_EVALUATION_ATTEMPTS = 5;
    // Delay between reevaluations once a captive portal has been found.
    private static final int CAPTIVE_PORTAL_REEVALUATE_DELAY_MS = 10*60*1000;

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
    // Avoids surfacing "Sign in to network" notification.
    private boolean mDontDisplaySigninNotification = false;

    public boolean systemReady = false;

    private final State mDefaultState = new DefaultState();
    private final State mValidatedState = new ValidatedState();
    private final State mMaybeNotifyState = new MaybeNotifyState();
    private final State mEvaluatingState = new EvaluatingState();
    private final State mCaptivePortalState = new CaptivePortalState();
    private final State mLingeringState = new LingeringState();

    private CustomIntentReceiver mLaunchCaptivePortalAppBroadcastReceiver = null;

    private final LocalLog validationLogs = new LocalLog(20); // 20 lines

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

        mIsCaptivePortalCheckEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED, 1) == 1;

        start();
    }

    @Override
    protected void log(String s) {
        if (DBG) Log.d(TAG + "/" + mNetworkAgentInfo.name(), s);
    }

    private void validationLog(String s) {
        if (DBG) log(s);
        validationLogs.log(s);
    }

    public ReadOnlyLocalLog getValidationLogs() {
        return validationLogs.readOnlyLocalLog();
    }

    // DefaultState is the parent of all States.  It exists only to handle CMD_* messages but
    // does not entail any real state (hence no enter() or exit() routines).
    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_NETWORK_LINGER:
                    log("Lingering");
                    transitionTo(mLingeringState);
                    return HANDLED;
                case CMD_NETWORK_CONNECTED:
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_NETWORK_DISCONNECTED:
                    if (mLaunchCaptivePortalAppBroadcastReceiver != null) {
                        mContext.unregisterReceiver(mLaunchCaptivePortalAppBroadcastReceiver);
                        mLaunchCaptivePortalAppBroadcastReceiver = null;
                    }
                    quit();
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                case CMD_CAPTIVE_PORTAL_RECHECK:
                    log("Forcing reevaluation for UID " + message.arg1);
                    mUidResponsibleForReeval = message.arg1;
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_CAPTIVE_PORTAL_APP_FINISHED:
                    log("CaptivePortal App responded with " + message.arg1);
                    switch (message.arg1) {
                        case APP_RETURN_DISMISSED:
                            sendMessage(CMD_FORCE_REEVALUATION, 0 /* no UID */, 0);
                            break;
                        case APP_RETURN_WANTED_AS_IS:
                            mDontDisplaySigninNotification = true;
                            // TODO: Distinguish this from a network that actually validates.
                            // Displaying the "!" on the system UI icon may still be a good idea.
                            transitionTo(mValidatedState);
                            break;
                        case APP_RETURN_UNWANTED:
                            mDontDisplaySigninNotification = true;
                            mUserDoesNotWant = true;
                            mConnectivityServiceHandler.sendMessage(obtainMessage(
                                    EVENT_NETWORK_TESTED, NETWORK_TEST_RESULT_INVALID, 0,
                                    mNetworkAgentInfo));
                            // TODO: Should teardown network.
                            mUidResponsibleForReeval = 0;
                            transitionTo(mEvaluatingState);
                            break;
                    }
                    return HANDLED;
                default:
                    return HANDLED;
            }
        }
    }

    // Being in the ValidatedState State indicates a Network is:
    // - Successfully validated, or
    // - Wanted "as is" by the user, or
    // - Does not satisfy the default NetworkRequest and so validation has been skipped.
    private class ValidatedState extends State {
        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_VALID, 0, mNetworkAgentInfo));
        }

        @Override
        public boolean processMessage(Message message) {
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
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_LAUNCH_CAPTIVE_PORTAL_APP:
                    final Intent intent = new Intent(
                            ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
                    intent.putExtra(ConnectivityManager.EXTRA_NETWORK, mNetworkAgentInfo.network);
                    intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,
                            new CaptivePortal(new ICaptivePortal.Stub() {
                                @Override
                                public void appResponse(int response) {
                                    if (response == APP_RETURN_WANTED_AS_IS) {
                                        mContext.enforceCallingPermission(
                                                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                                                "CaptivePortal");
                                    }
                                    sendMessage(CMD_CAPTIVE_PORTAL_APP_FINISHED, response);
                                }
                            }));
                    intent.setFlags(
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivityAsUser(intent, UserHandle.CURRENT);
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
        }
    }

    // Being in the EvaluatingState State indicates the Network is being evaluated for internet
    // connectivity, or that the user has indicated that this network is unwanted.
    private class EvaluatingState extends State {
        private int mReevaluateDelayMs;
        private int mAttempts;

        @Override
        public void enter() {
            sendMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
            if (mUidResponsibleForReeval != INVALID_UID) {
                TrafficStats.setThreadStatsUid(mUidResponsibleForReeval);
                mUidResponsibleForReeval = INVALID_UID;
            }
            mReevaluateDelayMs = INITIAL_REEVALUATE_DELAY_MS;
            mAttempts = 0;
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_REEVALUATE:
                    if (message.arg1 != mReevaluateToken || mUserDoesNotWant)
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
                    mAttempts++;
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
                    } else {
                        final Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
                        sendMessageDelayed(msg, mReevaluateDelayMs);
                        mConnectivityServiceHandler.sendMessage(obtainMessage(
                                EVENT_NETWORK_TESTED, NETWORK_TEST_RESULT_INVALID, 0,
                                mNetworkAgentInfo));
                        if (mAttempts >= BLAME_FOR_EVALUATION_ATTEMPTS) {
                            // Don't continue to blame UID forever.
                            TrafficStats.clearThreadStatsUid();
                        }
                        mReevaluateDelayMs *= 2;
                        if (mReevaluateDelayMs > MAX_REEVALUATE_DELAY_MS) {
                            mReevaluateDelayMs = MAX_REEVALUATE_DELAY_MS;
                        }
                    }
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    // Before IGNORE_REEVALUATE_ATTEMPTS attempts are made,
                    // ignore any re-evaluation requests. After, restart the
                    // evaluation process via EvaluatingState#enter.
                    return mAttempts < IGNORE_REEVALUATE_ATTEMPTS ? HANDLED : NOT_HANDLED;
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
            final Intent intent = new Intent(mAction);
            intent.setPackage(mContext.getPackageName());
            return PendingIntent.getBroadcast(mContext, 0, intent, 0);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(mAction)) sendMessage(obtainMessage(mWhat, mToken));
        }
    }

    // Being in the CaptivePortalState State indicates a captive portal was detected and the user
    // has been shown a notification to sign-in.
    private class CaptivePortalState extends State {
        private static final String ACTION_LAUNCH_CAPTIVE_PORTAL_APP =
                "android.net.netmon.launchCaptivePortalApp";

        @Override
        public void enter() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_INVALID, 0, mNetworkAgentInfo));
            // Don't annoy user with sign-in notifications.
            if (mDontDisplaySigninNotification) return;
            // Create a CustomIntentReceiver that sends us a
            // CMD_LAUNCH_CAPTIVE_PORTAL_APP message when the user
            // touches the notification.
            if (mLaunchCaptivePortalAppBroadcastReceiver == null) {
                // Wait for result.
                mLaunchCaptivePortalAppBroadcastReceiver = new CustomIntentReceiver(
                        ACTION_LAUNCH_CAPTIVE_PORTAL_APP, new Random().nextInt(),
                        CMD_LAUNCH_CAPTIVE_PORTAL_APP);
            }
            // Display the sign in notification.
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 1,
                    mNetworkAgentInfo.network.netId,
                    mLaunchCaptivePortalAppBroadcastReceiver.getPendingIntent());
            mConnectivityServiceHandler.sendMessage(message);
            // Retest for captive portal occasionally.
            sendMessageDelayed(CMD_CAPTIVE_PORTAL_RECHECK, 0 /* no UID */,
                    CAPTIVE_PORTAL_REEVALUATE_DELAY_MS);
        }

        @Override
        public void exit() {
             removeMessages(CMD_CAPTIVE_PORTAL_RECHECK);
        }
    }

    // Being in the LingeringState State indicates a Network's validated bit is true and it once
    // was the highest scoring Network satisfying a particular NetworkRequest, but since then
    // another Network satisfied the NetworkRequest with a higher score and hence this Network
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
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    log("Unlingered");
                    // If already validated, go straight to validated state.
                    if (mNetworkAgentInfo.lastValidated) {
                        transitionTo(mValidatedState);
                        return HANDLED;
                    }
                    return NOT_HANDLED;
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
    @VisibleForTesting
    protected int isCaptivePortal() {
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
            final ProxyInfo proxyInfo = mNetworkAgentInfo.linkProperties.getHttpProxy();
            if (proxyInfo != null && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl())) {
                url = new URL(proxyInfo.getPacFileUrl().toString());
                fetchPac = true;
            }
            final StringBuffer connectInfo = new StringBuffer();
            String hostToResolve = null;
            // Only resolve a host if HttpURLConnection is about to, to avoid any potentially
            // unnecessary resolution.
            if (proxyInfo == null || fetchPac) {
                hostToResolve = url.getHost();
            } else if (proxyInfo != null) {
                hostToResolve = proxyInfo.getHost();
            }
            if (!TextUtils.isEmpty(hostToResolve)) {
                connectInfo.append(", " + hostToResolve + "=");
                final InetAddress[] addresses =
                        mNetworkAgentInfo.network.getAllByName(hostToResolve);
                for (InetAddress address : addresses) {
                    connectInfo.append(address.getHostAddress());
                    if (address != addresses[addresses.length-1]) connectInfo.append(",");
                }
            }
            validationLog("Checking " + url.toString() + " on " +
                    mNetworkAgentInfo.networkInfo.getExtraInfo() + connectInfo);
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
            validationLog("isCaptivePortal: ret=" + httpResponseCode +
                    " headers=" + urlConnection.getHeaderFields());
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
                validationLog("Empty 200 response interpreted as 204 response.");
                httpResponseCode = 204;
            }

            if (httpResponseCode == 200 && fetchPac) {
                validationLog("PAC fetch 200 response interpreted as 204 response.");
                httpResponseCode = 204;
            }

            sendNetworkConditionsBroadcast(true /* response received */,
                    httpResponseCode != 204 /* isCaptivePortal */,
                    requestTimestamp, responseTimestamp);
        } catch (IOException e) {
            validationLog("Probably not a portal: exception " + e);
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
                            log("more than one registered CellInfo.  Can't " +
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

    // Allow tests to override linger time.
    @VisibleForTesting
    public static void SetDefaultLingerTime(int time_ms) {
        if (Process.myUid() == Process.SYSTEM_UID) {
            throw new SecurityException("SetDefaultLingerTime only for internal testing.");
        }
        DEFAULT_LINGER_DELAY_MS = time_ms;
    }
}
