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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.dns.ResolvUtil;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.metrics.ValidationProbeEvent;
import android.net.util.Stopwatch;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
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
import com.android.server.connectivity.DnsManager.PrivateDnsConfig;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@hide}
 */
public class NetworkMonitor extends StateMachine {
    private static final String TAG = NetworkMonitor.class.getSimpleName();
    private static final boolean DBG  = true;
    private static final boolean VDBG = false;

    // Default configuration values for captive portal detection probes.
    // TODO: append a random length parameter to the default HTTPS url.
    // TODO: randomize browser version ids in the default User-Agent String.
    private static final String DEFAULT_HTTPS_URL     = "https://www.google.com/generate_204";
    private static final String DEFAULT_HTTP_URL      =
            "http://connectivitycheck.gstatic.com/generate_204";
    private static final String DEFAULT_FALLBACK_URL  = "http://www.google.com/gen_204";
    private static final String DEFAULT_OTHER_FALLBACK_URLS =
            "http://play.googleapis.com/generate_204";
    private static final String DEFAULT_USER_AGENT    = "Mozilla/5.0 (X11; Linux x86_64) "
                                                      + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                                      + "Chrome/60.0.3112.32 Safari/537.36";

    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static final int PROBE_TIMEOUT_MS  = 3000;

    static enum EvaluationResult {
        VALIDATED(true),
        CAPTIVE_PORTAL(false);
        final boolean isValidated;
        EvaluationResult(boolean isValidated) {
            this.isValidated = isValidated;
        }
    }

    static enum ValidationStage {
        FIRST_VALIDATION(true),
        REVALIDATION(false);
        final boolean isFirstValidation;
        ValidationStage(boolean isFirstValidation) {
            this.isFirstValidation = isFirstValidation;
        }
    }

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
     * obj = String representing URL that Internet probe was redirect to, if it was redirected.
     * arg1 = One of the NETWORK_TESTED_RESULT_* constants.
     * arg2 = NetID.
     */
    public static final int EVENT_NETWORK_TESTED = BASE + 2;

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
    private static final int CMD_FORCE_REEVALUATION = BASE + 8;

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
     * Message indicating sign-in app should be launched.
     * Sent by mLaunchCaptivePortalAppBroadcastReceiver when the
     * user touches the sign in notification, or sent by
     * ConnectivityService when the user touches the "sign into
     * network" button in the wifi access point detail page.
     */
    public static final int CMD_LAUNCH_CAPTIVE_PORTAL_APP = BASE + 11;

    /**
     * Retest network to see if captive portal is still in place.
     * arg1 = UID responsible for requesting this reeval.  Will be billed for data.
     *        0 indicates self-initiated, so nobody to blame.
     */
    private static final int CMD_CAPTIVE_PORTAL_RECHECK = BASE + 12;

    /**
     * ConnectivityService notifies NetworkMonitor of settings changes to
     * Private DNS. If a DNS resolution is required, e.g. for DNS-over-TLS in
     * strict mode, then an event is sent back to ConnectivityService with the
     * result of the resolution attempt.
     *
     * A separate message is used to trigger (re)evaluation of the Private DNS
     * configuration, so that the message can be handled as needed in different
     * states, including being ignored until after an ongoing captive portal
     * validation phase is completed.
     */
    private static final int CMD_PRIVATE_DNS_SETTINGS_CHANGED = BASE + 13;
    public static final int EVENT_PRIVATE_DNS_CONFIG_RESOLVED = BASE + 14;
    private static final int CMD_EVALUATE_PRIVATE_DNS = BASE + 15;

    // Start mReevaluateDelayMs at this value and double.
    private static final int INITIAL_REEVALUATE_DELAY_MS = 1000;
    private static final int MAX_REEVALUATE_DELAY_MS = 10*60*1000;
    // Before network has been evaluated this many times, ignore repeated reevaluate requests.
    private static final int IGNORE_REEVALUATE_ATTEMPTS = 5;
    private int mReevaluateToken = 0;
    private static final int NO_UID = 0;
    private static final int INVALID_UID = -1;
    private int mUidResponsibleForReeval = INVALID_UID;
    // Stop blaming UID that requested re-evaluation after this many attempts.
    private static final int BLAME_FOR_EVALUATION_ATTEMPTS = 5;
    // Delay between reevaluations once a captive portal has been found.
    private static final int CAPTIVE_PORTAL_REEVALUATE_DELAY_MS = 10*60*1000;

    private static final int NUM_VALIDATION_LOG_LINES = 20;

    private String mPrivateDnsProviderHostname = "";

    public static boolean isValidationRequired(
            NetworkCapabilities dfltNetCap, NetworkCapabilities nc) {
        // TODO: Consider requiring validation for DUN networks.
        return dfltNetCap.satisfiedByNetworkCapabilities(nc);
    }

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final NetworkAgentInfo mNetworkAgentInfo;
    private final Network mNetwork;
    private final int mNetId;
    private final TelephonyManager mTelephonyManager;
    private final WifiManager mWifiManager;
    private final NetworkRequest mDefaultRequest;
    private final IpConnectivityLog mMetricsLog;
    private final NetworkMonitorSettings mSettings;

    // Configuration values for captive portal detection probes.
    private final String mCaptivePortalUserAgent;
    private final URL mCaptivePortalHttpsUrl;
    private final URL mCaptivePortalHttpUrl;
    private final URL[] mCaptivePortalFallbackUrls;

    @VisibleForTesting
    protected boolean mIsCaptivePortalCheckEnabled;

    private boolean mUseHttps;
    // The total number of captive portal detection attempts for this NetworkMonitor instance.
    private int mValidations = 0;

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
    private final State mEvaluatingPrivateDnsState = new EvaluatingPrivateDnsState();

    private CustomIntentReceiver mLaunchCaptivePortalAppBroadcastReceiver = null;

    private final LocalLog validationLogs = new LocalLog(NUM_VALIDATION_LOG_LINES);

    private final Stopwatch mEvaluationTimer = new Stopwatch();

    // This variable is set before transitioning to the mCaptivePortalState.
    private CaptivePortalProbeResult mLastPortalProbeResult = CaptivePortalProbeResult.FAILED;

    private int mNextFallbackUrlIndex = 0;

    public NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo,
            NetworkRequest defaultRequest) {
        this(context, handler, networkAgentInfo, defaultRequest, new IpConnectivityLog(),
                NetworkMonitorSettings.DEFAULT);
    }

    @VisibleForTesting
    protected NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo,
            NetworkRequest defaultRequest, IpConnectivityLog logger,
            NetworkMonitorSettings settings) {
        // Add suffix indicating which NetworkMonitor we're talking about.
        super(TAG + networkAgentInfo.name());

        // Logs with a tag of the form given just above, e.g.
        //     <timestamp>   862  2402 D NetworkMonitor/NetworkAgentInfo [WIFI () - 100]: ...
        setDbg(VDBG);

        mContext = context;
        mMetricsLog = logger;
        mConnectivityServiceHandler = handler;
        mSettings = settings;
        mNetworkAgentInfo = networkAgentInfo;
        mNetwork = new OneAddressPerFamilyNetwork(networkAgentInfo.network());
        mNetId = mNetwork.netId;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mDefaultRequest = defaultRequest;

        addState(mDefaultState);
        addState(mMaybeNotifyState, mDefaultState);
            addState(mEvaluatingState, mMaybeNotifyState);
            addState(mCaptivePortalState, mMaybeNotifyState);
        addState(mEvaluatingPrivateDnsState, mDefaultState);
        addState(mValidatedState, mDefaultState);
        setInitialState(mDefaultState);

        mIsCaptivePortalCheckEnabled = getIsCaptivePortalCheckEnabled();
        mUseHttps = getUseHttpsValidation();
        mCaptivePortalUserAgent = getCaptivePortalUserAgent();
        mCaptivePortalHttpsUrl = makeURL(getCaptivePortalServerHttpsUrl());
        mCaptivePortalHttpUrl = makeURL(getCaptivePortalServerHttpUrl(settings, context));
        mCaptivePortalFallbackUrls = makeCaptivePortalFallbackUrls();

        start();
    }

    public void forceReevaluation(int responsibleUid) {
        sendMessage(CMD_FORCE_REEVALUATION, responsibleUid, 0);
    }

    public void notifyPrivateDnsSettingsChanged(PrivateDnsConfig newCfg) {
        // Cancel any outstanding resolutions.
        removeMessages(CMD_PRIVATE_DNS_SETTINGS_CHANGED);
        // Send the update to the proper thread.
        sendMessage(CMD_PRIVATE_DNS_SETTINGS_CHANGED, newCfg);
    }

    @Override
    protected void log(String s) {
        if (DBG) Log.d(TAG + "/" + mNetworkAgentInfo.name(), s);
    }

    private void validationLog(int probeType, Object url, String msg) {
        String probeName = ValidationProbeEvent.getProbeName(probeType);
        validationLog(String.format("%s %s %s", probeName, url, msg));
    }

    private void validationLog(String s) {
        if (DBG) log(s);
        validationLogs.log(s);
    }

    public ReadOnlyLocalLog getValidationLogs() {
        return validationLogs.readOnlyLocalLog();
    }

    private ValidationStage validationStage() {
        return 0 == mValidations ? ValidationStage.FIRST_VALIDATION : ValidationStage.REVALIDATION;
    }

    private boolean isValidationRequired() {
        return isValidationRequired(
                mDefaultRequest.networkCapabilities, mNetworkAgentInfo.networkCapabilities);
    }


    private void notifyNetworkTestResultInvalid(Object obj) {
        mConnectivityServiceHandler.sendMessage(obtainMessage(
                EVENT_NETWORK_TESTED, NETWORK_TEST_RESULT_INVALID, mNetId, obj));
    }

    // DefaultState is the parent of all States.  It exists only to handle CMD_* messages but
    // does not entail any real state (hence no enter() or exit() routines).
    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    logNetworkEvent(NetworkEvent.NETWORK_CONNECTED);
                    transitionTo(mEvaluatingState);
                    return HANDLED;
                case CMD_NETWORK_DISCONNECTED:
                    logNetworkEvent(NetworkEvent.NETWORK_DISCONNECTED);
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

                    // If the user has seen and acted on a captive portal notification, and the
                    // captive portal app is now closed, disable HTTPS probes. This avoids the
                    // following pathological situation:
                    //
                    // 1. HTTP probe returns a captive portal, HTTPS probe fails or times out.
                    // 2. User opens the app and logs into the captive portal.
                    // 3. HTTP starts working, but HTTPS still doesn't work for some other reason -
                    //    perhaps due to the network blocking HTTPS?
                    //
                    // In this case, we'll fail to validate the network even after the app is
                    // dismissed. There is now no way to use this network, because the app is now
                    // gone, so the user cannot select "Use this network as is".
                    mUseHttps = false;

                    switch (message.arg1) {
                        case APP_RETURN_DISMISSED:
                            sendMessage(CMD_FORCE_REEVALUATION, NO_UID, 0);
                            break;
                        case APP_RETURN_WANTED_AS_IS:
                            mDontDisplaySigninNotification = true;
                            // TODO: Distinguish this from a network that actually validates.
                            // Displaying the "x" on the system UI icon may still be a good idea.
                            transitionTo(mEvaluatingPrivateDnsState);
                            break;
                        case APP_RETURN_UNWANTED:
                            mDontDisplaySigninNotification = true;
                            mUserDoesNotWant = true;
                            notifyNetworkTestResultInvalid(null);
                            // TODO: Should teardown network.
                            mUidResponsibleForReeval = 0;
                            transitionTo(mEvaluatingState);
                            break;
                    }
                    return HANDLED;
                case CMD_PRIVATE_DNS_SETTINGS_CHANGED: {
                    final PrivateDnsConfig cfg = (PrivateDnsConfig) message.obj;
                    if (!isValidationRequired() || cfg == null || !cfg.inStrictMode()) {
                        // No DNS resolution required.
                        //
                        // We don't force any validation in opportunistic mode
                        // here. Opportunistic mode nameservers are validated
                        // separately within netd.
                        //
                        // Reset Private DNS settings state.
                        mPrivateDnsProviderHostname = "";
                        break;
                    }

                    mPrivateDnsProviderHostname = cfg.hostname;

                    // DNS resolutions via Private DNS strict mode block for a
                    // few seconds (~4.2) checking for any IP addresses to
                    // arrive and validate. Initiating a (re)evaluation now
                    // should not significantly alter the validation outcome.
                    //
                    // No matter what: enqueue a validation request; one of
                    // three things can happen with this request:
                    //     [1] ignored (EvaluatingState or CaptivePortalState)
                    //     [2] transition to EvaluatingPrivateDnsState
                    //         (DefaultState and ValidatedState)
                    //     [3] handled (EvaluatingPrivateDnsState)
                    //
                    // The Private DNS configuration to be evaluated will:
                    //     [1] be skipped (not in strict mode), or
                    //     [2] validate (huzzah), or
                    //     [3] encounter some problem (invalid hostname,
                    //         no resolved IP addresses, IPs unreachable,
                    //         port 853 unreachable, port 853 is not running a
                    //         DNS-over-TLS server, et cetera).
                    sendMessage(CMD_EVALUATE_PRIVATE_DNS);
                    break;
                }
                default:
                    break;
            }
            return HANDLED;
        }
    }

    // Being in the ValidatedState State indicates a Network is:
    // - Successfully validated, or
    // - Wanted "as is" by the user, or
    // - Does not satisfy the default NetworkRequest and so validation has been skipped.
    private class ValidatedState extends State {
        @Override
        public void enter() {
            maybeLogEvaluationResult(
                    networkEventType(validationStage(), EvaluationResult.VALIDATED));
            mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED,
                    NETWORK_TEST_RESULT_VALID, mNetId, null));
            mValidations++;
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    transitionTo(mValidatedState);
                    break;
                case CMD_EVALUATE_PRIVATE_DNS:
                    transitionTo(mEvaluatingPrivateDnsState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
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
                    // OneAddressPerFamilyNetwork is not parcelable across processes.
                    intent.putExtra(ConnectivityManager.EXTRA_NETWORK, new Network(mNetwork));
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
                    intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL,
                            mLastPortalProbeResult.detectUrl);
                    intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_USER_AGENT,
                            mCaptivePortalUserAgent);
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
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 0, mNetId, null);
            mConnectivityServiceHandler.sendMessage(message);
        }
    }

    /**
     * Result of calling isCaptivePortal().
     * @hide
     */
    @VisibleForTesting
    public static final class CaptivePortalProbeResult {
        static final int SUCCESS_CODE = 204;
        static final int FAILED_CODE = 599;

        static final CaptivePortalProbeResult FAILED = new CaptivePortalProbeResult(FAILED_CODE);
        static final CaptivePortalProbeResult SUCCESS = new CaptivePortalProbeResult(SUCCESS_CODE);

        private final int mHttpResponseCode;  // HTTP response code returned from Internet probe.
        final String redirectUrl;             // Redirect destination returned from Internet probe.
        final String detectUrl;               // URL where a 204 response code indicates
                                              // captive portal has been appeased.

        public CaptivePortalProbeResult(
                int httpResponseCode, String redirectUrl, String detectUrl) {
            mHttpResponseCode = httpResponseCode;
            this.redirectUrl = redirectUrl;
            this.detectUrl = detectUrl;
        }

        public CaptivePortalProbeResult(int httpResponseCode) {
            this(httpResponseCode, null, null);
        }

        boolean isSuccessful() {
            return mHttpResponseCode == SUCCESS_CODE;
        }

        boolean isPortal() {
            return !isSuccessful() && (mHttpResponseCode >= 200) && (mHttpResponseCode <= 399);
        }

        boolean isFailed() {
            return !isSuccessful() && !isPortal();
        }
    }

    // Being in the EvaluatingState State indicates the Network is being evaluated for internet
    // connectivity, or that the user has indicated that this network is unwanted.
    private class EvaluatingState extends State {
        private int mReevaluateDelayMs;
        private int mAttempts;

        @Override
        public void enter() {
            // If we have already started to track time spent in EvaluatingState
            // don't reset the timer due simply to, say, commands or events that
            // cause us to exit and re-enter EvaluatingState.
            if (!mEvaluationTimer.isStarted()) {
                mEvaluationTimer.start();
            }
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
                    // Don't bother validating networks that don't satisfy the default request.
                    // This includes:
                    //  - VPNs which can be considered explicitly desired by the user and the
                    //    user's desire trumps whether the network validates.
                    //  - Networks that don't provide Internet access.  It's unclear how to
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
                    if (!isValidationRequired()) {
                        validationLog("Network would not satisfy default request, not validating");
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
                    CaptivePortalProbeResult probeResult = isCaptivePortal();
                    if (probeResult.isSuccessful()) {
                        // Transit EvaluatingPrivateDnsState to get to Validated
                        // state (even if no Private DNS validation required).
                        transitionTo(mEvaluatingPrivateDnsState);
                    } else if (probeResult.isPortal()) {
                        notifyNetworkTestResultInvalid(probeResult.redirectUrl);
                        mLastPortalProbeResult = probeResult;
                        transitionTo(mCaptivePortalState);
                    } else {
                        final Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
                        sendMessageDelayed(msg, mReevaluateDelayMs);
                        logNetworkEvent(NetworkEvent.NETWORK_VALIDATION_FAILED);
                        notifyNetworkTestResultInvalid(probeResult.redirectUrl);
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
                    return (mAttempts < IGNORE_REEVALUATE_ATTEMPTS) ? HANDLED : NOT_HANDLED;
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
            mAction = action + "_" + mNetId + "_" + token;
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
            maybeLogEvaluationResult(
                    networkEventType(validationStage(), EvaluationResult.CAPTIVE_PORTAL));
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
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 1, mNetId,
                    mLaunchCaptivePortalAppBroadcastReceiver.getPendingIntent());
            mConnectivityServiceHandler.sendMessage(message);
            // Retest for captive portal occasionally.
            sendMessageDelayed(CMD_CAPTIVE_PORTAL_RECHECK, 0 /* no UID */,
                    CAPTIVE_PORTAL_REEVALUATE_DELAY_MS);
            mValidations++;
        }

        @Override
        public void exit() {
            removeMessages(CMD_CAPTIVE_PORTAL_RECHECK);
        }
    }

    private class EvaluatingPrivateDnsState extends State {
        private int mPrivateDnsReevalDelayMs;
        private PrivateDnsConfig mPrivateDnsConfig;

        @Override
        public void enter() {
            mPrivateDnsReevalDelayMs = INITIAL_REEVALUATE_DELAY_MS;
            mPrivateDnsConfig = null;
            sendMessage(CMD_EVALUATE_PRIVATE_DNS);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_EVALUATE_PRIVATE_DNS:
                    if (inStrictMode()) {
                        if (!isStrictModeHostnameResolved()) {
                            resolveStrictModeHostname();

                            if (isStrictModeHostnameResolved()) {
                                notifyPrivateDnsConfigResolved();
                            } else {
                                handlePrivateDnsEvaluationFailure();
                                break;
                            }
                        }

                        // Look up a one-time hostname, to bypass caching.
                        //
                        // Note that this will race with ConnectivityService
                        // code programming the DNS-over-TLS server IP addresses
                        // into netd (if invoked, above). If netd doesn't know
                        // the IP addresses yet, or if the connections to the IP
                        // addresses haven't yet been validated, netd will block
                        // for up to a few seconds before failing the lookup.
                        if (!sendPrivateDnsProbe()) {
                            handlePrivateDnsEvaluationFailure();
                            break;
                        }
                    }

                    // All good!
                    transitionTo(mValidatedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean inStrictMode() {
            return !TextUtils.isEmpty(mPrivateDnsProviderHostname);
        }

        private boolean isStrictModeHostnameResolved() {
            return (mPrivateDnsConfig != null) &&
                   mPrivateDnsConfig.hostname.equals(mPrivateDnsProviderHostname) &&
                   (mPrivateDnsConfig.ips.length > 0);
        }

        private void resolveStrictModeHostname() {
            try {
                // Do a blocking DNS resolution using the network-assigned nameservers.
                final InetAddress[] ips = ResolvUtil.blockingResolveAllLocally(
                        mNetwork, mPrivateDnsProviderHostname);
                mPrivateDnsConfig = new PrivateDnsConfig(mPrivateDnsProviderHostname, ips);
            } catch (UnknownHostException uhe) {
                mPrivateDnsConfig = null;
            }
        }

        private void notifyPrivateDnsConfigResolved() {
            mConnectivityServiceHandler.sendMessage(obtainMessage(
                    EVENT_PRIVATE_DNS_CONFIG_RESOLVED, 0, mNetId, mPrivateDnsConfig));
        }

        private void handlePrivateDnsEvaluationFailure() {
            notifyNetworkTestResultInvalid(null);

            // Queue up a re-evaluation with backoff.
            //
            // TODO: Consider abandoning this state after a few attempts and
            // transitioning back to EvaluatingState, to perhaps give ourselves
            // the opportunity to (re)detect a captive portal or something.
            sendMessageDelayed(CMD_EVALUATE_PRIVATE_DNS, mPrivateDnsReevalDelayMs);
            mPrivateDnsReevalDelayMs *= 2;
            if (mPrivateDnsReevalDelayMs > MAX_REEVALUATE_DELAY_MS) {
                mPrivateDnsReevalDelayMs = MAX_REEVALUATE_DELAY_MS;
            }
        }

        private boolean sendPrivateDnsProbe() {
            // q.v. system/netd/server/dns/DnsTlsTransport.cpp
            final String ONE_TIME_HOSTNAME_SUFFIX = "-dnsotls-ds.metric.gstatic.com";
            final String host = UUID.randomUUID().toString().substring(0, 8) +
                    ONE_TIME_HOSTNAME_SUFFIX;
            try {
                final InetAddress[] ips = mNetworkAgentInfo.network().getAllByName(host);
                return (ips != null && ips.length > 0);
            } catch (UnknownHostException uhe) {}
            return false;
        }
    }

    // Limits the list of IP addresses returned by getAllByName or tried by openConnection to at
    // most one per address family. This ensures we only wait up to 20 seconds for TCP connections
    // to complete, regardless of how many IP addresses a host has.
    private static class OneAddressPerFamilyNetwork extends Network {
        public OneAddressPerFamilyNetwork(Network network) {
            super(network);
        }

        @Override
        public InetAddress[] getAllByName(String host) throws UnknownHostException {
            // Always bypass Private DNS.
            final List<InetAddress> addrs = Arrays.asList(
                    ResolvUtil.blockingResolveAllLocally(this, host));

            // Ensure the address family of the first address is tried first.
            LinkedHashMap<Class, InetAddress> addressByFamily = new LinkedHashMap<>();
            addressByFamily.put(addrs.get(0).getClass(), addrs.get(0));
            Collections.shuffle(addrs);

            for (InetAddress addr : addrs) {
                addressByFamily.put(addr.getClass(), addr);
            }

            return addressByFamily.values().toArray(new InetAddress[addressByFamily.size()]);
        }
    }

    public boolean getIsCaptivePortalCheckEnabled() {
        String symbol = Settings.Global.CAPTIVE_PORTAL_MODE;
        int defaultValue = Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT;
        int mode = mSettings.getSetting(mContext, symbol, defaultValue);
        return mode != Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE;
    }

    public boolean getUseHttpsValidation() {
        return mSettings.getSetting(mContext, Settings.Global.CAPTIVE_PORTAL_USE_HTTPS, 1) == 1;
    }

    public boolean getWifiScansAlwaysAvailableDisabled() {
        return mSettings.getSetting(mContext, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0) == 0;
    }

    private String getCaptivePortalServerHttpsUrl() {
        return mSettings.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_HTTPS_URL, DEFAULT_HTTPS_URL);
    }

    // Static for direct access by ConnectivityService
    public static String getCaptivePortalServerHttpUrl(Context context) {
        return getCaptivePortalServerHttpUrl(NetworkMonitorSettings.DEFAULT, context);
    }

    public static String getCaptivePortalServerHttpUrl(
            NetworkMonitorSettings settings, Context context) {
        return settings.getSetting(
                context, Settings.Global.CAPTIVE_PORTAL_HTTP_URL, DEFAULT_HTTP_URL);
    }

    private URL[] makeCaptivePortalFallbackUrls() {
        String separator = ",";
        String firstUrl = mSettings.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL, DEFAULT_FALLBACK_URL);
        String joinedUrls = firstUrl + separator + mSettings.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS, DEFAULT_OTHER_FALLBACK_URLS);
        List<URL> urls = new ArrayList<>();
        for (String s : joinedUrls.split(separator)) {
            URL u = makeURL(s);
            if (u == null) {
                continue;
            }
            urls.add(u);
        }
        if (urls.isEmpty()) {
            Log.e(TAG, String.format("could not create any url from %s", joinedUrls));
        }
        return urls.toArray(new URL[urls.size()]);
    }

    private String getCaptivePortalUserAgent() {
        return mSettings.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_USER_AGENT, DEFAULT_USER_AGENT);
    }

    private URL nextFallbackUrl() {
        if (mCaptivePortalFallbackUrls.length == 0) {
            return null;
        }
        int idx = Math.abs(mNextFallbackUrlIndex) % mCaptivePortalFallbackUrls.length;
        mNextFallbackUrlIndex += new Random().nextInt(); // randomely change url without memory.
        return mCaptivePortalFallbackUrls[idx];
    }

    @VisibleForTesting
    protected CaptivePortalProbeResult isCaptivePortal() {
        if (!mIsCaptivePortalCheckEnabled) {
            validationLog("Validation disabled.");
            return CaptivePortalProbeResult.SUCCESS;
        }

        URL pacUrl = null;
        URL httpsUrl = mCaptivePortalHttpsUrl;
        URL httpUrl = mCaptivePortalHttpUrl;

        // On networks with a PAC instead of fetching a URL that should result in a 204
        // response, we instead simply fetch the PAC script.  This is done for a few reasons:
        // 1. At present our PAC code does not yet handle multiple PACs on multiple networks
        //    until something like https://android-review.googlesource.com/#/c/115180/ lands.
        //    Network.openConnection() will ignore network-specific PACs and instead fetch
        //    using NO_PROXY.  If a PAC is in place, the only fetch we know will succeed with
        //    NO_PROXY is the fetch of the PAC itself.
        // 2. To proxy the generate_204 fetch through a PAC would require a number of things
        //    happen before the fetch can commence, namely:
        //        a) the PAC script be fetched
        //        b) a PAC script resolver service be fired up and resolve the captive portal
        //           server.
        //    Network validation could be delayed until these prerequisities are satisifed or
        //    could simply be left to race them.  Neither is an optimal solution.
        // 3. PAC scripts are sometimes used to block or restrict Internet access and may in
        //    fact block fetching of the generate_204 URL which would lead to false negative
        //    results for network validation.
        final ProxyInfo proxyInfo = mNetworkAgentInfo.linkProperties.getHttpProxy();
        if (proxyInfo != null && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl())) {
            pacUrl = makeURL(proxyInfo.getPacFileUrl().toString());
            if (pacUrl == null) {
                return CaptivePortalProbeResult.FAILED;
            }
        }

        if ((pacUrl == null) && (httpUrl == null || httpsUrl == null)) {
            return CaptivePortalProbeResult.FAILED;
        }

        long startTime = SystemClock.elapsedRealtime();

        final CaptivePortalProbeResult result;
        if (pacUrl != null) {
            result = sendDnsAndHttpProbes(null, pacUrl, ValidationProbeEvent.PROBE_PAC);
        } else if (mUseHttps) {
            result = sendParallelHttpProbes(proxyInfo, httpsUrl, httpUrl);
        } else {
            result = sendDnsAndHttpProbes(proxyInfo, httpUrl, ValidationProbeEvent.PROBE_HTTP);
        }

        long endTime = SystemClock.elapsedRealtime();

        sendNetworkConditionsBroadcast(true /* response received */,
                result.isPortal() /* isCaptivePortal */,
                startTime, endTime);

        return result;
    }

    /**
     * Do a DNS resolution and URL fetch on a known web server to see if we get the data we expect.
     * @return a CaptivePortalProbeResult inferred from the HTTP response.
     */
    private CaptivePortalProbeResult sendDnsAndHttpProbes(ProxyInfo proxy, URL url, int probeType) {
        // Pre-resolve the captive portal server host so we can log it.
        // Only do this if HttpURLConnection is about to, to avoid any potentially
        // unnecessary resolution.
        final String host = (proxy != null) ? proxy.getHost() : url.getHost();
        sendDnsProbe(host);
        return sendHttpProbe(url, probeType);
    }

    /** Do a DNS resolution of the given server. */
    private void sendDnsProbe(String host) {
        if (TextUtils.isEmpty(host)) {
            return;
        }

        final String name = ValidationProbeEvent.getProbeName(ValidationProbeEvent.PROBE_DNS);
        final Stopwatch watch = new Stopwatch().start();
        int result;
        String connectInfo;
        try {
            InetAddress[] addresses = mNetwork.getAllByName(host);
            StringBuffer buffer = new StringBuffer();
            for (InetAddress address : addresses) {
                buffer.append(',').append(address.getHostAddress());
            }
            result = ValidationProbeEvent.DNS_SUCCESS;
            connectInfo = "OK " + buffer.substring(1);
        } catch (UnknownHostException e) {
            result = ValidationProbeEvent.DNS_FAILURE;
            connectInfo = "FAIL";
        }
        final long latency = watch.stop();
        validationLog(ValidationProbeEvent.PROBE_DNS, host,
                String.format("%dms %s", latency, connectInfo));
        logValidationProbe(latency, ValidationProbeEvent.PROBE_DNS, result);
    }

    /**
     * Do a URL fetch on a known web server to see if we get the data we expect.
     * @return a CaptivePortalProbeResult inferred from the HTTP response.
     */
    @VisibleForTesting
    protected CaptivePortalProbeResult sendHttpProbe(URL url, int probeType) {
        HttpURLConnection urlConnection = null;
        int httpResponseCode = CaptivePortalProbeResult.FAILED_CODE;
        String redirectUrl = null;
        final Stopwatch probeTimer = new Stopwatch().start();
        final int oldTag = TrafficStats.getAndSetThreadStatsTag(TrafficStats.TAG_SYSTEM_PROBE);
        try {
            urlConnection = (HttpURLConnection) mNetwork.openConnection(url);
            urlConnection.setInstanceFollowRedirects(probeType == ValidationProbeEvent.PROBE_PAC);
            urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setUseCaches(false);
            if (mCaptivePortalUserAgent != null) {
                urlConnection.setRequestProperty("User-Agent", mCaptivePortalUserAgent);
            }
            // cannot read request header after connection
            String requestHeader = urlConnection.getRequestProperties().toString();

            // Time how long it takes to get a response to our request
            long requestTimestamp = SystemClock.elapsedRealtime();

            httpResponseCode = urlConnection.getResponseCode();
            redirectUrl = urlConnection.getHeaderField("location");

            // Time how long it takes to get a response to our request
            long responseTimestamp = SystemClock.elapsedRealtime();

            validationLog(probeType, url, "time=" + (responseTimestamp - requestTimestamp) + "ms" +
                    " ret=" + httpResponseCode +
                    " request=" + requestHeader +
                    " headers=" + urlConnection.getHeaderFields());
            // NOTE: We may want to consider an "HTTP/1.0 204" response to be a captive
            // portal.  The only example of this seen so far was a captive portal.  For
            // the time being go with prior behavior of assuming it's not a captive
            // portal.  If it is considered a captive portal, a different sign-in URL
            // is needed (i.e. can't browse a 204).  This could be the result of an HTTP
            // proxy server.
            if (httpResponseCode == 200) {
                if (probeType == ValidationProbeEvent.PROBE_PAC) {
                    validationLog(
                            probeType, url, "PAC fetch 200 response interpreted as 204 response.");
                    httpResponseCode = CaptivePortalProbeResult.SUCCESS_CODE;
                } else if (urlConnection.getContentLengthLong() == 0) {
                    // Consider 200 response with "Content-length=0" to not be a captive portal.
                    // There's no point in considering this a captive portal as the user cannot
                    // sign-in to an empty page. Probably the result of a broken transparent proxy.
                    // See http://b/9972012.
                    validationLog(probeType, url,
                        "200 response with Content-length=0 interpreted as 204 response.");
                    httpResponseCode = CaptivePortalProbeResult.SUCCESS_CODE;
                } else if (urlConnection.getContentLengthLong() == -1) {
                    // When no Content-length (default value == -1), attempt to read a byte from the
                    // response. Do not use available() as it is unreliable. See http://b/33498325.
                    if (urlConnection.getInputStream().read() == -1) {
                        validationLog(
                                probeType, url, "Empty 200 response interpreted as 204 response.");
                        httpResponseCode = CaptivePortalProbeResult.SUCCESS_CODE;
                    }
                }
            }
        } catch (IOException e) {
            validationLog(probeType, url, "Probe failed with exception " + e);
            if (httpResponseCode == CaptivePortalProbeResult.FAILED_CODE) {
                // TODO: Ping gateway and DNS server and log results.
            }
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            TrafficStats.setThreadStatsTag(oldTag);
        }
        logValidationProbe(probeTimer.stop(), probeType, httpResponseCode);
        return new CaptivePortalProbeResult(httpResponseCode, redirectUrl, url.toString());
    }

    private CaptivePortalProbeResult sendParallelHttpProbes(
            ProxyInfo proxy, URL httpsUrl, URL httpUrl) {
        // Number of probes to wait for. If a probe completes with a conclusive answer
        // it shortcuts the latch immediately by forcing the count to 0.
        final CountDownLatch latch = new CountDownLatch(2);

        final class ProbeThread extends Thread {
            private final boolean mIsHttps;
            private volatile CaptivePortalProbeResult mResult = CaptivePortalProbeResult.FAILED;

            public ProbeThread(boolean isHttps) {
                mIsHttps = isHttps;
            }

            public CaptivePortalProbeResult result() {
                return mResult;
            }

            @Override
            public void run() {
                if (mIsHttps) {
                    mResult =
                            sendDnsAndHttpProbes(proxy, httpsUrl, ValidationProbeEvent.PROBE_HTTPS);
                } else {
                    mResult = sendDnsAndHttpProbes(proxy, httpUrl, ValidationProbeEvent.PROBE_HTTP);
                }
                if ((mIsHttps && mResult.isSuccessful()) || (!mIsHttps && mResult.isPortal())) {
                    // Stop waiting immediately if https succeeds or if http finds a portal.
                    while (latch.getCount() > 0) {
                        latch.countDown();
                    }
                }
                // Signal this probe has completed.
                latch.countDown();
            }
        }

        final ProbeThread httpsProbe = new ProbeThread(true);
        final ProbeThread httpProbe = new ProbeThread(false);

        try {
            httpsProbe.start();
            httpProbe.start();
            latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            validationLog("Error: probes wait interrupted!");
            return CaptivePortalProbeResult.FAILED;
        }

        final CaptivePortalProbeResult httpsResult = httpsProbe.result();
        final CaptivePortalProbeResult httpResult = httpProbe.result();

        // Look for a conclusive probe result first.
        if (httpResult.isPortal()) {
            return httpResult;
        }
        // httpsResult.isPortal() is not expected, but check it nonetheless.
        if (httpsResult.isPortal() || httpsResult.isSuccessful()) {
            return httpsResult;
        }
        // If a fallback url exists, use a fallback probe to try again portal detection.
        URL fallbackUrl = nextFallbackUrl();
        if (fallbackUrl != null) {
            CaptivePortalProbeResult result =
                    sendHttpProbe(fallbackUrl, ValidationProbeEvent.PROBE_FALLBACK);
            if (result.isPortal()) {
                return result;
            }
        }
        // Otherwise wait until http and https probes completes and use their results.
        try {
            httpProbe.join();
            if (httpProbe.result().isPortal()) {
                return httpProbe.result();
            }
            httpsProbe.join();
            return httpsProbe.result();
        } catch (InterruptedException e) {
            validationLog("Error: http or https probe wait interrupted!");
            return CaptivePortalProbeResult.FAILED;
        }
    }

    private URL makeURL(String url) {
        if (url != null) {
            try {
                return new URL(url);
            } catch (MalformedURLException e) {
                validationLog("Bad URL: " + url);
            }
        }
        return null;
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
        if (getWifiScansAlwaysAvailableDisabled()) {
            return;
        }

        if (!systemReady) {
            return;
        }

        Intent latencyBroadcast =
                new Intent(ConnectivityConstants.ACTION_NETWORK_CONDITIONS_MEASURED);
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
                    latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_SSID,
                            currentWifiInfo.getSSID());
                    latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_BSSID,
                            currentWifiInfo.getBSSID());
                } else {
                    if (VDBG) logw("network info is TYPE_WIFI but no ConnectionInfo found");
                    return;
                }
                break;
            case ConnectivityManager.TYPE_MOBILE:
                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_NETWORK_TYPE,
                        mTelephonyManager.getNetworkType());
                List<CellInfo> info = mTelephonyManager.getAllCellInfo();
                if (info == null) return;
                int numRegisteredCellInfo = 0;
                for (CellInfo cellInfo : info) {
                    if (cellInfo.isRegistered()) {
                        numRegisteredCellInfo++;
                        if (numRegisteredCellInfo > 1) {
                            if (VDBG) logw("more than one registered CellInfo." +
                                    " Can't tell which is active.  Bailing.");
                            return;
                        }
                        if (cellInfo instanceof CellInfoCdma) {
                            CellIdentityCdma cellId = ((CellInfoCdma) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoGsm) {
                            CellIdentityGsm cellId = ((CellInfoGsm) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoLte) {
                            CellIdentityLte cellId = ((CellInfoLte) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId);
                        } else if (cellInfo instanceof CellInfoWcdma) {
                            CellIdentityWcdma cellId = ((CellInfoWcdma) cellInfo).getCellIdentity();
                            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId);
                        } else {
                            if (VDBG) logw("Registered cellinfo is unrecognized");
                            return;
                        }
                    }
                }
                break;
            default:
                return;
        }
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CONNECTIVITY_TYPE,
                mNetworkAgentInfo.networkInfo.getType());
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_RESPONSE_RECEIVED,
                responseReceived);
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_REQUEST_TIMESTAMP_MS,
                requestTimestampMs);

        if (responseReceived) {
            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_IS_CAPTIVE_PORTAL,
                    isCaptivePortal);
            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_RESPONSE_TIMESTAMP_MS,
                    responseTimestampMs);
        }
        mContext.sendBroadcastAsUser(latencyBroadcast, UserHandle.CURRENT,
                ConnectivityConstants.PERMISSION_ACCESS_NETWORK_CONDITIONS);
    }

    private void logNetworkEvent(int evtype) {
        int[] transports = mNetworkAgentInfo.networkCapabilities.getTransportTypes();
        mMetricsLog.log(mNetId, transports, new NetworkEvent(evtype));
    }

    private int networkEventType(ValidationStage s, EvaluationResult r) {
        if (s.isFirstValidation) {
            if (r.isValidated) {
                return NetworkEvent.NETWORK_FIRST_VALIDATION_SUCCESS;
            } else {
                return NetworkEvent.NETWORK_FIRST_VALIDATION_PORTAL_FOUND;
            }
        } else {
            if (r.isValidated) {
                return NetworkEvent.NETWORK_REVALIDATION_SUCCESS;
            } else {
                return NetworkEvent.NETWORK_REVALIDATION_PORTAL_FOUND;
            }
        }
    }

    private void maybeLogEvaluationResult(int evtype) {
        if (mEvaluationTimer.isRunning()) {
            int[] transports = mNetworkAgentInfo.networkCapabilities.getTransportTypes();
            mMetricsLog.log(mNetId, transports, new NetworkEvent(evtype, mEvaluationTimer.stop()));
            mEvaluationTimer.reset();
        }
    }

    private void logValidationProbe(long durationMs, int probeType, int probeResult) {
        int[] transports = mNetworkAgentInfo.networkCapabilities.getTransportTypes();
        boolean isFirstValidation = validationStage().isFirstValidation;
        ValidationProbeEvent ev = new ValidationProbeEvent();
        ev.probeType = ValidationProbeEvent.makeProbeType(probeType, isFirstValidation);
        ev.returnCode = probeResult;
        ev.durationMs = durationMs;
        mMetricsLog.log(mNetId, transports, ev);
    }

    @VisibleForTesting
    public interface NetworkMonitorSettings {
        int getSetting(Context context, String symbol, int defaultValue);
        String getSetting(Context context, String symbol, String defaultValue);

        static NetworkMonitorSettings DEFAULT = new DefaultNetworkMonitorSettings();
    }

    @VisibleForTesting
    public static class DefaultNetworkMonitorSettings implements NetworkMonitorSettings {
        public int getSetting(Context context, String symbol, int defaultValue) {
            return Settings.Global.getInt(context.getContentResolver(), symbol, defaultValue);
        }

        public String getSetting(Context context, String symbol, String defaultValue) {
            final String value = Settings.Global.getString(context.getContentResolver(), symbol);
            return value != null ? value : defaultValue;
        }
    }
}
