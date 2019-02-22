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
import static android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL_PROBE_SPEC;
import static android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_INVALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.metrics.ValidationProbeEvent.DNS_FAILURE;
import static android.net.metrics.ValidationProbeEvent.DNS_SUCCESS;
import static android.net.metrics.ValidationProbeEvent.PROBE_FALLBACK;
import static android.net.metrics.ValidationProbeEvent.PROBE_PRIVDNS;
import static android.net.util.NetworkStackUtils.isEmpty;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.captiveportal.CaptivePortalProbeResult;
import android.net.captiveportal.CaptivePortalProbeSpec;
import android.net.metrics.DataStallDetectionStats;
import android.net.metrics.DataStallStatsUtils;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.metrics.ValidationProbeEvent;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.SharedLog;
import android.net.util.Stopwatch;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellSignalStrength;
import android.telephony.NetworkRegistrationState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBufferIndices;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
    private static final boolean VDBG_STALL = Log.isLoggable(TAG, Log.DEBUG);
    // TODO: use another permission for CaptivePortalLoginActivity once it has its own certificate
    private static final String PERMISSION_NETWORK_SETTINGS = "android.permission.NETWORK_SETTINGS";
    // Default configuration values for captive portal detection probes.
    // TODO: append a random length parameter to the default HTTPS url.
    // TODO: randomize browser version ids in the default User-Agent String.
    private static final String DEFAULT_HTTPS_URL = "https://www.google.com/generate_204";
    private static final String DEFAULT_FALLBACK_URL  = "http://www.google.com/gen_204";
    private static final String DEFAULT_OTHER_FALLBACK_URLS =
            "http://play.googleapis.com/generate_204";
    private static final String DEFAULT_USER_AGENT    = "Mozilla/5.0 (X11; Linux x86_64) "
                                                      + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                                      + "Chrome/60.0.3112.32 Safari/537.36";

    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static final int PROBE_TIMEOUT_MS  = 3000;

    // Default configuration values for data stall detection.
    private static final int DEFAULT_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD = 5;
    private static final int DEFAULT_DATA_STALL_MIN_EVALUATE_TIME_MS = 60 * 1000;
    private static final int DEFAULT_DATA_STALL_VALID_DNS_TIME_THRESHOLD_MS = 30 * 60 * 1000;

    private static final int DATA_STALL_EVALUATION_TYPE_DNS = 1;
    private static final int DEFAULT_DATA_STALL_EVALUATION_TYPES =
            (1 << DATA_STALL_EVALUATION_TYPE_DNS);
    // Reevaluate it as intending to increase the number. Larger log size may cause statsd
    // log buffer bust and have stats log lost.
    private static final int DEFAULT_DNS_LOG_SIZE = 20;

    enum EvaluationResult {
        VALIDATED(true),
        CAPTIVE_PORTAL(false);
        final boolean mIsValidated;
        EvaluationResult(boolean isValidated) {
            this.mIsValidated = isValidated;
        }
    }

    enum ValidationStage {
        FIRST_VALIDATION(true),
        REVALIDATION(false);
        final boolean mIsFirstValidation;
        ValidationStage(boolean isFirstValidation) {
            this.mIsFirstValidation = isFirstValidation;
        }
    }

    /**
     * ConnectivityService has sent a notification to indicate that network has connected.
     * Initiates Network Validation.
     */
    private static final int CMD_NETWORK_CONNECTED = 1;

    /**
     * Message to self indicating it's time to evaluate a network's connectivity.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_REEVALUATE = 6;

    /**
     * ConnectivityService has sent a notification to indicate that network has disconnected.
     */
    private static final int CMD_NETWORK_DISCONNECTED = 7;

    /**
     * Force evaluation even if it has succeeded in the past.
     * arg1 = UID responsible for requesting this reeval.  Will be billed for data.
     */
    private static final int CMD_FORCE_REEVALUATION = 8;

    /**
     * Message to self indicating captive portal app finished.
     * arg1 = one of: APP_RETURN_DISMISSED,
     *                APP_RETURN_UNWANTED,
     *                APP_RETURN_WANTED_AS_IS
     * obj = mCaptivePortalLoggedInResponseToken as String
     */
    private static final int CMD_CAPTIVE_PORTAL_APP_FINISHED = 9;

    /**
     * Message indicating sign-in app should be launched.
     * Sent by mLaunchCaptivePortalAppBroadcastReceiver when the
     * user touches the sign in notification, or sent by
     * ConnectivityService when the user touches the "sign into
     * network" button in the wifi access point detail page.
     */
    private static final int CMD_LAUNCH_CAPTIVE_PORTAL_APP = 11;

    /**
     * Retest network to see if captive portal is still in place.
     * arg1 = UID responsible for requesting this reeval.  Will be billed for data.
     *        0 indicates self-initiated, so nobody to blame.
     */
    private static final int CMD_CAPTIVE_PORTAL_RECHECK = 12;

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
    private static final int CMD_PRIVATE_DNS_SETTINGS_CHANGED = 13;
    private static final int CMD_EVALUATE_PRIVATE_DNS = 15;

    /**
     * Message to self indicating captive portal detection is completed.
     * obj = CaptivePortalProbeResult for detection result;
     */
    public static final int CMD_PROBE_COMPLETE = 16;

    /**
     * ConnectivityService notifies NetworkMonitor of DNS query responses event.
     * arg1 = returncode in OnDnsEvent which indicates the response code for the DNS query.
     */
    public static final int EVENT_DNS_NOTIFICATION = 17;

    // Start mReevaluateDelayMs at this value and double.
    private static final int INITIAL_REEVALUATE_DELAY_MS = 1000;
    private static final int MAX_REEVALUATE_DELAY_MS = 10 * 60 * 1000;
    // Before network has been evaluated this many times, ignore repeated reevaluate requests.
    private static final int IGNORE_REEVALUATE_ATTEMPTS = 5;
    private int mReevaluateToken = 0;
    private static final int NO_UID = 0;
    private static final int INVALID_UID = -1;
    private int mUidResponsibleForReeval = INVALID_UID;
    // Stop blaming UID that requested re-evaluation after this many attempts.
    private static final int BLAME_FOR_EVALUATION_ATTEMPTS = 5;
    // Delay between reevaluations once a captive portal has been found.
    private static final int CAPTIVE_PORTAL_REEVALUATE_DELAY_MS = 10 * 60 * 1000;

    private String mPrivateDnsProviderHostname = "";

    private final Context mContext;
    private final INetworkMonitorCallbacks mCallback;
    private final Network mNetwork;
    private final Network mNonPrivateDnsBypassNetwork;
    private final TelephonyManager mTelephonyManager;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mCm;
    private final IpConnectivityLog mMetricsLog;
    private final Dependencies mDependencies;
    private final DataStallStatsUtils mDetectionStatsUtils;

    // Configuration values for captive portal detection probes.
    private final String mCaptivePortalUserAgent;
    private final URL mCaptivePortalHttpsUrl;
    private final URL mCaptivePortalHttpUrl;
    private final URL[] mCaptivePortalFallbackUrls;
    @Nullable
    private final CaptivePortalProbeSpec[] mCaptivePortalFallbackSpecs;

    private NetworkCapabilities mNetworkCapabilities;
    private LinkProperties mLinkProperties;

    @VisibleForTesting
    protected boolean mIsCaptivePortalCheckEnabled;

    private boolean mUseHttps;
    // The total number of captive portal detection attempts for this NetworkMonitor instance.
    private int mValidations = 0;

    // Set if the user explicitly selected "Do not use this network" in captive portal sign-in app.
    private boolean mUserDoesNotWant = false;
    // Avoids surfacing "Sign in to network" notification.
    private boolean mDontDisplaySigninNotification = false;

    private volatile boolean mSystemReady = false;

    private final State mDefaultState = new DefaultState();
    private final State mValidatedState = new ValidatedState();
    private final State mMaybeNotifyState = new MaybeNotifyState();
    private final State mEvaluatingState = new EvaluatingState();
    private final State mCaptivePortalState = new CaptivePortalState();
    private final State mEvaluatingPrivateDnsState = new EvaluatingPrivateDnsState();
    private final State mProbingState = new ProbingState();
    private final State mWaitingForNextProbeState = new WaitingForNextProbeState();

    private CustomIntentReceiver mLaunchCaptivePortalAppBroadcastReceiver = null;

    private final SharedLog mValidationLogs;

    private final Stopwatch mEvaluationTimer = new Stopwatch();

    // This variable is set before transitioning to the mCaptivePortalState.
    private CaptivePortalProbeResult mLastPortalProbeResult = CaptivePortalProbeResult.FAILED;

    // Random generator to select fallback URL index
    private final Random mRandom;
    private int mNextFallbackUrlIndex = 0;


    private int mReevaluateDelayMs = INITIAL_REEVALUATE_DELAY_MS;
    private int mEvaluateAttempts = 0;
    private volatile int mProbeToken = 0;
    private final int mConsecutiveDnsTimeoutThreshold;
    private final int mDataStallMinEvaluateTime;
    private final int mDataStallValidDnsTimeThreshold;
    private final int mDataStallEvaluationType;
    private final DnsStallDetector mDnsStallDetector;
    private long mLastProbeTime;
    // Set to true if data stall is suspected and reset to false after metrics are sent to statsd.
    private boolean mCollectDataStallMetrics = false;

    public NetworkMonitor(Context context, INetworkMonitorCallbacks cb, Network network,
            SharedLog validationLog) {
        this(context, cb, network, new IpConnectivityLog(), validationLog,
                Dependencies.DEFAULT, new DataStallStatsUtils());
    }

    @VisibleForTesting
    protected NetworkMonitor(Context context, INetworkMonitorCallbacks cb, Network network,
            IpConnectivityLog logger, SharedLog validationLogs,
            Dependencies deps, DataStallStatsUtils detectionStatsUtils) {
        // Add suffix indicating which NetworkMonitor we're talking about.
        super(TAG + "/" + network.toString());

        // Logs with a tag of the form given just above, e.g.
        //     <timestamp>   862  2402 D NetworkMonitor/NetworkAgentInfo [WIFI () - 100]: ...
        setDbg(VDBG);

        mContext = context;
        mMetricsLog = logger;
        mValidationLogs = validationLogs;
        mCallback = cb;
        mDependencies = deps;
        mDetectionStatsUtils = detectionStatsUtils;
        mNonPrivateDnsBypassNetwork = network;
        mNetwork = deps.getPrivateDnsBypassNetwork(network);
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // CHECKSTYLE:OFF IndentationCheck
        addState(mDefaultState);
        addState(mMaybeNotifyState, mDefaultState);
            addState(mEvaluatingState, mMaybeNotifyState);
                addState(mProbingState, mEvaluatingState);
                addState(mWaitingForNextProbeState, mEvaluatingState);
            addState(mCaptivePortalState, mMaybeNotifyState);
        addState(mEvaluatingPrivateDnsState, mDefaultState);
        addState(mValidatedState, mDefaultState);
        setInitialState(mDefaultState);
        // CHECKSTYLE:ON IndentationCheck

        mIsCaptivePortalCheckEnabled = getIsCaptivePortalCheckEnabled();
        mUseHttps = getUseHttpsValidation();
        mCaptivePortalUserAgent = getCaptivePortalUserAgent();
        mCaptivePortalHttpsUrl = makeURL(getCaptivePortalServerHttpsUrl());
        mCaptivePortalHttpUrl = makeURL(deps.getCaptivePortalServerHttpUrl(context));
        mCaptivePortalFallbackUrls = makeCaptivePortalFallbackUrls();
        mCaptivePortalFallbackSpecs = makeCaptivePortalFallbackProbeSpecs();
        mRandom = deps.getRandom();
        // TODO: Evaluate to move data stall configuration to a specific class.
        mConsecutiveDnsTimeoutThreshold = getConsecutiveDnsTimeoutThreshold();
        mDnsStallDetector = new DnsStallDetector(mConsecutiveDnsTimeoutThreshold);
        mDataStallMinEvaluateTime = getDataStallMinEvaluateTime();
        mDataStallValidDnsTimeThreshold = getDataStallValidDnsTimeThreshold();
        mDataStallEvaluationType = getDataStallEvalutionType();

        // mLinkProperties and mNetworkCapbilities must never be null or we will NPE.
        // Provide empty objects in case we are started and the network disconnects before
        // we can ever fetch them.
        // TODO: Delete ASAP.
        mLinkProperties = new LinkProperties();
        mNetworkCapabilities = new NetworkCapabilities(null);
    }

    /**
     * Request the NetworkMonitor to reevaluate the network.
     */
    public void forceReevaluation(int responsibleUid) {
        sendMessage(CMD_FORCE_REEVALUATION, responsibleUid, 0);
    }

    /**
     * Send a notification to NetworkMonitor indicating that there was a DNS query response event.
     * @param returnCode the DNS return code of the response.
     */
    public void notifyDnsResponse(int returnCode) {
        sendMessage(EVENT_DNS_NOTIFICATION, returnCode);
    }

    /**
     * Send a notification to NetworkMonitor indicating that private DNS settings have changed.
     * @param newCfg The new private DNS configuration.
     */
    public void notifyPrivateDnsSettingsChanged(PrivateDnsConfig newCfg) {
        // Cancel any outstanding resolutions.
        removeMessages(CMD_PRIVATE_DNS_SETTINGS_CHANGED);
        // Send the update to the proper thread.
        sendMessage(CMD_PRIVATE_DNS_SETTINGS_CHANGED, newCfg);
    }

    /**
     * Send a notification to NetworkMonitor indicating that the system is ready.
     */
    public void notifySystemReady() {
        // No need to run on the handler thread: mSystemReady is volatile and read only once on the
        // isCaptivePortal() thread.
        mSystemReady = true;
    }

    /**
     * Send a notification to NetworkMonitor indicating that the network is now connected.
     */
    public void notifyNetworkConnected() {
        sendMessage(CMD_NETWORK_CONNECTED);
    }

    /**
     * Send a notification to NetworkMonitor indicating that the network is now disconnected.
     */
    public void notifyNetworkDisconnected() {
        sendMessage(CMD_NETWORK_DISCONNECTED);
    }

    /**
     * Send a notification to NetworkMonitor indicating that link properties have changed.
     */
    public void notifyLinkPropertiesChanged() {
        getHandler().post(() -> {
            updateLinkProperties();
        });
    }

    private void updateLinkProperties() {
        final LinkProperties lp = mCm.getLinkProperties(mNetwork);
        // If null, we should soon get a message that the network was disconnected, and will stop.
        if (lp != null) {
            // TODO: send LinkProperties parceled in notifyLinkPropertiesChanged() and start().
            mLinkProperties = lp;
        }
    }

    /**
     * Send a notification to NetworkMonitor indicating that network capabilities have changed.
     */
    public void notifyNetworkCapabilitiesChanged() {
        getHandler().post(() -> {
            updateNetworkCapabilities();
        });
    }

    private void updateNetworkCapabilities() {
        final NetworkCapabilities nc = mCm.getNetworkCapabilities(mNetwork);
        // If null, we should soon get a message that the network was disconnected, and will stop.
        if (nc != null) {
            // TODO: send NetworkCapabilities parceled in notifyNetworkCapsChanged() and start().
            mNetworkCapabilities = nc;
        }
    }

    /**
     * Request the captive portal application to be launched.
     */
    public void launchCaptivePortalApp() {
        sendMessage(CMD_LAUNCH_CAPTIVE_PORTAL_APP);
    }

    /**
     * Notify that the captive portal app was closed with the provided response code.
     */
    public void notifyCaptivePortalAppFinished(int response) {
        sendMessage(CMD_CAPTIVE_PORTAL_APP_FINISHED, response);
    }

    @Override
    protected void log(String s) {
        if (DBG) Log.d(TAG + "/" + mNetwork.toString(), s);
    }

    private void validationLog(int probeType, Object url, String msg) {
        String probeName = ValidationProbeEvent.getProbeName(probeType);
        validationLog(String.format("%s %s %s", probeName, url, msg));
    }

    private void validationLog(String s) {
        if (DBG) log(s);
        mValidationLogs.log(s);
    }

    private ValidationStage validationStage() {
        return 0 == mValidations ? ValidationStage.FIRST_VALIDATION : ValidationStage.REVALIDATION;
    }

    private boolean isValidationRequired() {
        return NetworkMonitorUtils.isValidationRequired(mNetworkCapabilities);
    }


    private void notifyNetworkTested(int result, @Nullable String redirectUrl) {
        try {
            mCallback.notifyNetworkTested(result, redirectUrl);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending network test result", e);
        }
    }

    private void showProvisioningNotification(String action) {
        try {
            mCallback.showProvisioningNotification(action, mContext.getPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error showing provisioning notification", e);
        }
    }

    private void hideProvisioningNotification() {
        try {
            mCallback.hideProvisioningNotification();
        } catch (RemoteException e) {
            Log.e(TAG, "Error hiding provisioning notification", e);
        }
    }

    // DefaultState is the parent of all States.  It exists only to handle CMD_* messages but
    // does not entail any real state (hence no enter() or exit() routines).
    private class DefaultState extends State {
        @Override
        public void enter() {
            // TODO: have those passed parceled in start() and remove this
            updateLinkProperties();
            updateNetworkCapabilities();
        }

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
                    final int dnsCount = mDnsStallDetector.getConsecutiveTimeoutCount();
                    validationLog("Forcing reevaluation for UID " + message.arg1
                            + ". Dns signal count: " + dnsCount);
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
                            notifyNetworkTested(NETWORK_TEST_RESULT_INVALID, null);
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
                case EVENT_DNS_NOTIFICATION:
                    mDnsStallDetector.accumulateConsecutiveDnsTimeoutCount(message.arg1);
                    break;
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
            notifyNetworkTested(INetworkMonitor.NETWORK_TEST_RESULT_VALID, null);
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
                case EVENT_DNS_NOTIFICATION:
                    mDnsStallDetector.accumulateConsecutiveDnsTimeoutCount(message.arg1);
                    if (isDataStall()) {
                        mCollectDataStallMetrics = true;
                        validationLog("Suspecting data stall, reevaluate");
                        transitionTo(mEvaluatingState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void writeDataStallStats(@NonNull final CaptivePortalProbeResult result) {
        /*
         * Collect data stall detection level information for each transport type. Collect type
         * specific information for cellular and wifi only currently. Generate
         * DataStallDetectionStats for each transport type. E.g., if a network supports both
         * TRANSPORT_WIFI and TRANSPORT_VPN, two DataStallDetectionStats will be generated.
         */
        final int[] transports = mNetworkCapabilities.getTransportTypes();

        for (int i = 0; i < transports.length; i++) {
            DataStallStatsUtils.write(buildDataStallDetectionStats(transports[i]), result);
        }
        mCollectDataStallMetrics = false;
    }

    @VisibleForTesting
    protected DataStallDetectionStats buildDataStallDetectionStats(int transport) {
        final DataStallDetectionStats.Builder stats = new DataStallDetectionStats.Builder();
        if (VDBG_STALL) log("collectDataStallMetrics: type=" + transport);
        stats.setEvaluationType(DATA_STALL_EVALUATION_TYPE_DNS);
        stats.setNetworkType(transport);
        switch (transport) {
            case NetworkCapabilities.TRANSPORT_WIFI:
                // TODO: Update it if status query in dual wifi is supported.
                final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                stats.setWiFiData(wifiInfo);
                break;
            case NetworkCapabilities.TRANSPORT_CELLULAR:
                final boolean isRoaming = !mNetworkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
                final SignalStrength ss = mTelephonyManager.getSignalStrength();
                // TODO(b/120452078): Support multi-sim.
                stats.setCellData(
                        mTelephonyManager.getDataNetworkType(),
                        isRoaming,
                        mTelephonyManager.getNetworkOperator(),
                        mTelephonyManager.getSimOperator(),
                        (ss != null)
                        ? ss.getLevel() : CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
                break;
            default:
                // No transport type specific information for the other types.
                break;
        }
        addDnsEvents(stats);

        return stats.build();
    }

    private void addDnsEvents(@NonNull final DataStallDetectionStats.Builder stats) {
        final int size = mDnsStallDetector.mResultIndices.size();
        for (int i = 1; i <= DEFAULT_DNS_LOG_SIZE && i <= size; i++) {
            final int index = mDnsStallDetector.mResultIndices.indexOf(size - i);
            stats.addDnsEvent(mDnsStallDetector.mDnsEvents[index].mReturnCode,
                    mDnsStallDetector.mDnsEvents[index].mTimeStamp);
        }
    }


    // Being in the MaybeNotifyState State indicates the user may have been notified that sign-in
    // is required.  This State takes care to clear the notification upon exit from the State.
    private class MaybeNotifyState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_LAUNCH_CAPTIVE_PORTAL_APP:
                    final Bundle appExtras = new Bundle();
                    // OneAddressPerFamilyNetwork is not parcelable across processes.
                    final Network network = new Network(mNetwork);
                    appExtras.putParcelable(ConnectivityManager.EXTRA_NETWORK, network);
                    final CaptivePortalProbeResult probeRes = mLastPortalProbeResult;
                    appExtras.putString(EXTRA_CAPTIVE_PORTAL_URL, probeRes.detectUrl);
                    if (probeRes.probeSpec != null) {
                        final String encodedSpec = probeRes.probeSpec.getEncodedSpec();
                        appExtras.putString(EXTRA_CAPTIVE_PORTAL_PROBE_SPEC, encodedSpec);
                    }
                    appExtras.putString(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_USER_AGENT,
                            mCaptivePortalUserAgent);
                    mCm.startCaptivePortalApp(network, appExtras);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            hideProvisioningNotification();
        }
    }

    // Being in the EvaluatingState State indicates the Network is being evaluated for internet
    // connectivity, or that the user has indicated that this network is unwanted.
    private class EvaluatingState extends State {
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
            mEvaluateAttempts = 0;
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_REEVALUATE:
                    if (message.arg1 != mReevaluateToken || mUserDoesNotWant) {
                        return HANDLED;
                    }
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
                    mEvaluateAttempts++;

                    transitionTo(mProbingState);
                    return HANDLED;
                case CMD_FORCE_REEVALUATION:
                    // Before IGNORE_REEVALUATE_ATTEMPTS attempts are made,
                    // ignore any re-evaluation requests. After, restart the
                    // evaluation process via EvaluatingState#enter.
                    return (mEvaluateAttempts < IGNORE_REEVALUATE_ATTEMPTS) ? HANDLED : NOT_HANDLED;
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
            mAction = action + "_" + mNetwork.getNetworkHandle() + "_" + token;
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
            showProvisioningNotification(mLaunchCaptivePortalAppBroadcastReceiver.mAction);
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
            return (mPrivateDnsConfig != null)
                    && mPrivateDnsConfig.hostname.equals(mPrivateDnsProviderHostname)
                    && (mPrivateDnsConfig.ips.length > 0);
        }

        private void resolveStrictModeHostname() {
            try {
                // Do a blocking DNS resolution using the network-assigned nameservers.
                final InetAddress[] ips = mNetwork.getAllByName(mPrivateDnsProviderHostname);
                mPrivateDnsConfig = new PrivateDnsConfig(mPrivateDnsProviderHostname, ips);
                validationLog("Strict mode hostname resolved: " + mPrivateDnsConfig);
            } catch (UnknownHostException uhe) {
                mPrivateDnsConfig = null;
                validationLog("Strict mode hostname resolution failed: " + uhe.getMessage());
            }
        }

        private void notifyPrivateDnsConfigResolved() {
            try {
                mCallback.notifyPrivateDnsConfigResolved(mPrivateDnsConfig.toParcel());
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending private DNS config resolved notification", e);
            }
        }

        private void handlePrivateDnsEvaluationFailure() {
            notifyNetworkTested(NETWORK_TEST_RESULT_INVALID, null);

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
            final String oneTimeHostnameSuffix = "-dnsotls-ds.metric.gstatic.com";
            final String host = UUID.randomUUID().toString().substring(0, 8)
                    + oneTimeHostnameSuffix;
            final Stopwatch watch = new Stopwatch().start();
            try {
                final InetAddress[] ips = mNonPrivateDnsBypassNetwork.getAllByName(host);
                final long time = watch.stop();
                final String strIps = Arrays.toString(ips);
                final boolean success = (ips != null && ips.length > 0);
                validationLog(PROBE_PRIVDNS, host, String.format("%dms: %s", time, strIps));
                logValidationProbe(time, PROBE_PRIVDNS, success ? DNS_SUCCESS : DNS_FAILURE);
                return success;
            } catch (UnknownHostException uhe) {
                final long time = watch.stop();
                validationLog(PROBE_PRIVDNS, host,
                        String.format("%dms - Error: %s", time, uhe.getMessage()));
                logValidationProbe(time, PROBE_PRIVDNS, DNS_FAILURE);
            }
            return false;
        }
    }

    private class ProbingState extends State {
        private Thread mThread;

        @Override
        public void enter() {
            if (mEvaluateAttempts >= BLAME_FOR_EVALUATION_ATTEMPTS) {
                //Don't continue to blame UID forever.
                TrafficStats.clearThreadStatsUid();
            }

            final int token = ++mProbeToken;
            mThread = new Thread(() -> sendMessage(obtainMessage(CMD_PROBE_COMPLETE, token, 0,
                    isCaptivePortal())));
            mThread.start();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_PROBE_COMPLETE:
                    // Ensure that CMD_PROBE_COMPLETE from stale threads are ignored.
                    if (message.arg1 != mProbeToken) {
                        return HANDLED;
                    }

                    final CaptivePortalProbeResult probeResult =
                            (CaptivePortalProbeResult) message.obj;
                    mLastProbeTime = SystemClock.elapsedRealtime();

                    if (mCollectDataStallMetrics) {
                        writeDataStallStats(probeResult);
                    }

                    if (probeResult.isSuccessful()) {
                        // Transit EvaluatingPrivateDnsState to get to Validated
                        // state (even if no Private DNS validation required).
                        transitionTo(mEvaluatingPrivateDnsState);
                    } else if (probeResult.isPortal()) {
                        notifyNetworkTested(NETWORK_TEST_RESULT_INVALID, probeResult.redirectUrl);
                        mLastPortalProbeResult = probeResult;
                        transitionTo(mCaptivePortalState);
                    } else {
                        logNetworkEvent(NetworkEvent.NETWORK_VALIDATION_FAILED);
                        notifyNetworkTested(NETWORK_TEST_RESULT_INVALID, probeResult.redirectUrl);
                        transitionTo(mWaitingForNextProbeState);
                    }
                    return HANDLED;
                case EVENT_DNS_NOTIFICATION:
                    // Leave the event to DefaultState to record correct dns timestamp.
                    return NOT_HANDLED;
                default:
                    // Wait for probe result and defer events to next state by default.
                    deferMessage(message);
                    return HANDLED;
            }
        }

        @Override
        public void exit() {
            if (mThread.isAlive()) {
                mThread.interrupt();
            }
            mThread = null;
        }
    }

    // Being in the WaitingForNextProbeState indicates that evaluating probes failed and state is
    // transited from ProbingState. This ensures that the state machine is only in ProbingState
    // while a probe is in progress, not while waiting to perform the next probe. That allows
    // ProbingState to defer most messages until the probe is complete, which keeps the code simple
    // and matches the pre-Q behaviour where probes were a blocking operation performed on the state
    // machine thread.
    private class WaitingForNextProbeState extends State {
        @Override
        public void enter() {
            scheduleNextProbe();
        }

        private void scheduleNextProbe() {
            final Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
            sendMessageDelayed(msg, mReevaluateDelayMs);
            mReevaluateDelayMs *= 2;
            if (mReevaluateDelayMs > MAX_REEVALUATE_DELAY_MS) {
                mReevaluateDelayMs = MAX_REEVALUATE_DELAY_MS;
            }
        }

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }
    }

    // Limits the list of IP addresses returned by getAllByName or tried by openConnection to at
    // most one per address family. This ensures we only wait up to 20 seconds for TCP connections
    // to complete, regardless of how many IP addresses a host has.
    private static class OneAddressPerFamilyNetwork extends Network {
        OneAddressPerFamilyNetwork(Network network) {
            // Always bypass Private DNS.
            super(network.getPrivateDnsBypassingCopy());
        }

        @Override
        public InetAddress[] getAllByName(String host) throws UnknownHostException {
            final List<InetAddress> addrs = Arrays.asList(super.getAllByName(host));

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

    private boolean getIsCaptivePortalCheckEnabled() {
        String symbol = Settings.Global.CAPTIVE_PORTAL_MODE;
        int defaultValue = Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT;
        int mode = mDependencies.getSetting(mContext, symbol, defaultValue);
        return mode != Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE;
    }

    private boolean getUseHttpsValidation() {
        return mDependencies.getSetting(mContext, Settings.Global.CAPTIVE_PORTAL_USE_HTTPS, 1) == 1;
    }

    private String getCaptivePortalServerHttpsUrl() {
        return mDependencies.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_HTTPS_URL, DEFAULT_HTTPS_URL);
    }

    private int getConsecutiveDnsTimeoutThreshold() {
        return mDependencies.getSetting(mContext,
                Settings.Global.DATA_STALL_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD,
                DEFAULT_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD);
    }

    private int getDataStallMinEvaluateTime() {
        return mDependencies.getSetting(mContext,
                Settings.Global.DATA_STALL_MIN_EVALUATE_INTERVAL,
                DEFAULT_DATA_STALL_MIN_EVALUATE_TIME_MS);
    }

    private int getDataStallValidDnsTimeThreshold() {
        return mDependencies.getSetting(mContext,
                Settings.Global.DATA_STALL_VALID_DNS_TIME_THRESHOLD,
                DEFAULT_DATA_STALL_VALID_DNS_TIME_THRESHOLD_MS);
    }

    private int getDataStallEvalutionType() {
        return mDependencies.getSetting(mContext, Settings.Global.DATA_STALL_EVALUATION_TYPE,
                DEFAULT_DATA_STALL_EVALUATION_TYPES);
    }

    private URL[] makeCaptivePortalFallbackUrls() {
        try {
            String separator = ",";
            String firstUrl = mDependencies.getSetting(mContext,
                    Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL, DEFAULT_FALLBACK_URL);
            String joinedUrls = firstUrl + separator + mDependencies.getSetting(mContext,
                    Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS,
                    DEFAULT_OTHER_FALLBACK_URLS);
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
        } catch (Exception e) {
            // Don't let a misconfiguration bootloop the system.
            Log.e(TAG, "Error parsing configured fallback URLs", e);
            return new URL[0];
        }
    }

    private CaptivePortalProbeSpec[] makeCaptivePortalFallbackProbeSpecs() {
        try {
            final String settingsValue = mDependencies.getSetting(
                    mContext, Settings.Global.CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS, null);
            // Probe specs only used if configured in settings
            if (TextUtils.isEmpty(settingsValue)) {
                return null;
            }

            final Collection<CaptivePortalProbeSpec> specs =
                    CaptivePortalProbeSpec.parseCaptivePortalProbeSpecs(settingsValue);
            final CaptivePortalProbeSpec[] specsArray = new CaptivePortalProbeSpec[specs.size()];
            return specs.toArray(specsArray);
        } catch (Exception e) {
            // Don't let a misconfiguration bootloop the system.
            Log.e(TAG, "Error parsing configured fallback probe specs", e);
            return null;
        }
    }

    private String getCaptivePortalUserAgent() {
        return mDependencies.getSetting(mContext,
                Settings.Global.CAPTIVE_PORTAL_USER_AGENT, DEFAULT_USER_AGENT);
    }

    private URL nextFallbackUrl() {
        if (mCaptivePortalFallbackUrls.length == 0) {
            return null;
        }
        int idx = Math.abs(mNextFallbackUrlIndex) % mCaptivePortalFallbackUrls.length;
        mNextFallbackUrlIndex += mRandom.nextInt(); // randomly change url without memory.
        return mCaptivePortalFallbackUrls[idx];
    }

    private CaptivePortalProbeSpec nextFallbackSpec() {
        if (isEmpty(mCaptivePortalFallbackSpecs)) {
            return null;
        }
        // Randomly change spec without memory. Also randomize the first attempt.
        final int idx = Math.abs(mRandom.nextInt()) % mCaptivePortalFallbackSpecs.length;
        return mCaptivePortalFallbackSpecs[idx];
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
        final ProxyInfo proxyInfo = mLinkProperties.getHttpProxy();
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

        log("isCaptivePortal: isSuccessful()=" + result.isSuccessful()
                + " isPortal()=" + result.isPortal()
                + " RedirectUrl=" + result.redirectUrl
                + " Time=" + (endTime - startTime) + "ms");

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
        return sendHttpProbe(url, probeType, null);
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
    protected CaptivePortalProbeResult sendHttpProbe(URL url, int probeType,
            @Nullable CaptivePortalProbeSpec probeSpec) {
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
            urlConnection.setRequestProperty("Connection", "close");
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

            validationLog(probeType, url, "time=" + (responseTimestamp - requestTimestamp) + "ms"
                    + " ret=" + httpResponseCode
                    + " request=" + requestHeader
                    + " headers=" + urlConnection.getHeaderFields());
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

        if (probeSpec == null) {
            return new CaptivePortalProbeResult(httpResponseCode, redirectUrl, url.toString());
        } else {
            return probeSpec.getResult(httpResponseCode, redirectUrl);
        }
    }

    private CaptivePortalProbeResult sendParallelHttpProbes(
            ProxyInfo proxy, URL httpsUrl, URL httpUrl) {
        // Number of probes to wait for. If a probe completes with a conclusive answer
        // it shortcuts the latch immediately by forcing the count to 0.
        final CountDownLatch latch = new CountDownLatch(2);

        final class ProbeThread extends Thread {
            private final boolean mIsHttps;
            private volatile CaptivePortalProbeResult mResult = CaptivePortalProbeResult.FAILED;

            ProbeThread(boolean isHttps) {
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
        // If a fallback method exists, use it to retry portal detection.
        // If we have new-style probe specs, use those. Otherwise, use the fallback URLs.
        final CaptivePortalProbeSpec probeSpec = nextFallbackSpec();
        final URL fallbackUrl = (probeSpec != null) ? probeSpec.getUrl() : nextFallbackUrl();
        if (fallbackUrl != null) {
            CaptivePortalProbeResult result = sendHttpProbe(fallbackUrl, PROBE_FALLBACK, probeSpec);
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
        if (!mSystemReady) {
            return;
        }

        Intent latencyBroadcast =
                new Intent(NetworkMonitorUtils.ACTION_NETWORK_CONDITIONS_MEASURED);
        if (mNetworkCapabilities.hasTransport(TRANSPORT_WIFI)) {
            if (!mWifiManager.isScanAlwaysAvailable()) {
                return;
            }

            WifiInfo currentWifiInfo = mWifiManager.getConnectionInfo();
            if (currentWifiInfo != null) {
                // NOTE: getSSID()'s behavior changed in API 17; before that, SSIDs were not
                // surrounded by double quotation marks (thus violating the Javadoc), but this
                // was changed to match the Javadoc in API 17. Since clients may have started
                // sanitizing the output of this method since API 17 was released, we should
                // not change it here as it would become impossible to tell whether the SSID is
                // simply being surrounded by quotes due to the API, or whether those quotes
                // are actually part of the SSID.
                latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_SSID,
                        currentWifiInfo.getSSID());
                latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_BSSID,
                        currentWifiInfo.getBSSID());
            } else {
                if (VDBG) logw("network info is TYPE_WIFI but no ConnectionInfo found");
                return;
            }
            latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_CONNECTIVITY_TYPE, TYPE_WIFI);
        } else if (mNetworkCapabilities.hasTransport(TRANSPORT_CELLULAR)) {
            // TODO(b/123893112): Support multi-sim.
            latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_NETWORK_TYPE,
                    mTelephonyManager.getNetworkType());
            final ServiceState dataSs = mTelephonyManager.getServiceState();
            if (dataSs == null) {
                logw("failed to retrieve ServiceState");
                return;
            }
            // See if the data sub is registered for PS services on cell.
            final NetworkRegistrationState nrs = dataSs.getNetworkRegistrationState(
                    NetworkRegistrationState.DOMAIN_PS,
                    AccessNetworkConstants.TransportType.WWAN);
            latencyBroadcast.putExtra(
                    NetworkMonitorUtils.EXTRA_CELL_ID,
                    nrs == null ? null : nrs.getCellIdentity());
            latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_CONNECTIVITY_TYPE, TYPE_MOBILE);
        } else {
            return;
        }
        latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_RESPONSE_RECEIVED,
                responseReceived);
        latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_REQUEST_TIMESTAMP_MS,
                requestTimestampMs);

        if (responseReceived) {
            latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_IS_CAPTIVE_PORTAL,
                    isCaptivePortal);
            latencyBroadcast.putExtra(NetworkMonitorUtils.EXTRA_RESPONSE_TIMESTAMP_MS,
                    responseTimestampMs);
        }
        mContext.sendBroadcastAsUser(latencyBroadcast, UserHandle.CURRENT,
                NetworkMonitorUtils.PERMISSION_ACCESS_NETWORK_CONDITIONS);
    }

    private void logNetworkEvent(int evtype) {
        int[] transports = mNetworkCapabilities.getTransportTypes();
        mMetricsLog.log(mNetwork, transports, new NetworkEvent(evtype));
    }

    private int networkEventType(ValidationStage s, EvaluationResult r) {
        if (s.mIsFirstValidation) {
            if (r.mIsValidated) {
                return NetworkEvent.NETWORK_FIRST_VALIDATION_SUCCESS;
            } else {
                return NetworkEvent.NETWORK_FIRST_VALIDATION_PORTAL_FOUND;
            }
        } else {
            if (r.mIsValidated) {
                return NetworkEvent.NETWORK_REVALIDATION_SUCCESS;
            } else {
                return NetworkEvent.NETWORK_REVALIDATION_PORTAL_FOUND;
            }
        }
    }

    private void maybeLogEvaluationResult(int evtype) {
        if (mEvaluationTimer.isRunning()) {
            int[] transports = mNetworkCapabilities.getTransportTypes();
            mMetricsLog.log(mNetwork, transports,
                    new NetworkEvent(evtype, mEvaluationTimer.stop()));
            mEvaluationTimer.reset();
        }
    }

    private void logValidationProbe(long durationMs, int probeType, int probeResult) {
        int[] transports = mNetworkCapabilities.getTransportTypes();
        boolean isFirstValidation = validationStage().mIsFirstValidation;
        ValidationProbeEvent ev = new ValidationProbeEvent.Builder()
                .setProbeType(probeType, isFirstValidation)
                .setReturnCode(probeResult)
                .setDurationMs(durationMs)
                .build();
        mMetricsLog.log(mNetwork, transports, ev);
    }

    @VisibleForTesting
    static class Dependencies {
        public Network getPrivateDnsBypassNetwork(Network network) {
            return new OneAddressPerFamilyNetwork(network);
        }

        public Random getRandom() {
            return new Random();
        }

        /**
         * Get the captive portal server HTTP URL that is configured on the device.
         */
        public String getCaptivePortalServerHttpUrl(Context context) {
            return NetworkMonitorUtils.getCaptivePortalServerHttpUrl(context);
        }

        /**
         * Get the value of a global integer setting.
         * @param symbol Name of the setting
         * @param defaultValue Value to return if the setting is not defined.
         */
        public int getSetting(Context context, String symbol, int defaultValue) {
            return Settings.Global.getInt(context.getContentResolver(), symbol, defaultValue);
        }

        /**
         * Get the value of a global String setting.
         * @param symbol Name of the setting
         * @param defaultValue Value to return if the setting is not defined.
         */
        public String getSetting(Context context, String symbol, String defaultValue) {
            final String value = Settings.Global.getString(context.getContentResolver(), symbol);
            return value != null ? value : defaultValue;
        }

        public static final Dependencies DEFAULT = new Dependencies();
    }

    /**
     * Methods in this class perform no locking because all accesses are performed on the state
     * machine's thread. Need to consider the thread safety if it ever could be accessed outside the
     * state machine.
     */
    @VisibleForTesting
    protected class DnsStallDetector {
        private int mConsecutiveTimeoutCount = 0;
        private int mSize;
        final DnsResult[] mDnsEvents;
        final RingBufferIndices mResultIndices;

        DnsStallDetector(int size) {
            mSize = Math.max(DEFAULT_DNS_LOG_SIZE, size);
            mDnsEvents = new DnsResult[mSize];
            mResultIndices = new RingBufferIndices(mSize);
        }

        @VisibleForTesting
        protected void accumulateConsecutiveDnsTimeoutCount(int code) {
            final DnsResult result = new DnsResult(code);
            mDnsEvents[mResultIndices.add()] = result;
            if (result.isTimeout()) {
                mConsecutiveTimeoutCount++;
            } else {
                // Keep the event in mDnsEvents without clearing it so that there are logs to do the
                // simulation and analysis.
                mConsecutiveTimeoutCount = 0;
            }
        }

        private boolean isDataStallSuspected(int timeoutCountThreshold, int validTime) {
            if (timeoutCountThreshold <= 0) {
                Log.wtf(TAG, "Timeout count threshold should be larger than 0.");
                return false;
            }

            // Check if the consecutive timeout count reach the threshold or not.
            if (mConsecutiveTimeoutCount < timeoutCountThreshold) {
                return false;
            }

            // Check if the target dns event index is valid or not.
            final int firstConsecutiveTimeoutIndex =
                    mResultIndices.indexOf(mResultIndices.size() - timeoutCountThreshold);

            // If the dns timeout events happened long time ago, the events are meaningless for
            // data stall evaluation. Thus, check if the first consecutive timeout dns event
            // considered in the evaluation happened in defined threshold time.
            final long now = SystemClock.elapsedRealtime();
            final long firstTimeoutTime = now - mDnsEvents[firstConsecutiveTimeoutIndex].mTimeStamp;
            return (firstTimeoutTime < validTime);
        }

        int getConsecutiveTimeoutCount() {
            return mConsecutiveTimeoutCount;
        }
    }

    private static class DnsResult {
        // TODO: Need to move the DNS return code definition to a specific class once unify DNS
        // response code is done.
        private static final int RETURN_CODE_DNS_TIMEOUT = 255;

        private final long mTimeStamp;
        private final int mReturnCode;

        DnsResult(int code) {
            mTimeStamp = SystemClock.elapsedRealtime();
            mReturnCode = code;
        }

        private boolean isTimeout() {
            return mReturnCode == RETURN_CODE_DNS_TIMEOUT;
        }
    }


    @VisibleForTesting
    protected DnsStallDetector getDnsStallDetector() {
        return mDnsStallDetector;
    }

    private boolean dataStallEvaluateTypeEnabled(int type) {
        return (mDataStallEvaluationType & (1 << type)) != 0;
    }

    @VisibleForTesting
    protected long getLastProbeTime() {
        return mLastProbeTime;
    }

    @VisibleForTesting
    protected boolean isDataStall() {
        boolean result = false;
        // Reevaluation will generate traffic. Thus, set a minimal reevaluation timer to limit the
        // possible traffic cost in metered network.
        if (!mNetworkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)
                && (SystemClock.elapsedRealtime() - getLastProbeTime()
                < mDataStallMinEvaluateTime)) {
            return false;
        }

        // Check dns signal. Suspect it may be a data stall if both :
        // 1. The number of consecutive DNS query timeouts > mConsecutiveDnsTimeoutThreshold.
        // 2. Those consecutive DNS queries happened in the last mValidDataStallDnsTimeThreshold ms.
        if (dataStallEvaluateTypeEnabled(DATA_STALL_EVALUATION_TYPE_DNS)) {
            if (mDnsStallDetector.isDataStallSuspected(mConsecutiveDnsTimeoutThreshold,
                    mDataStallValidDnsTimeThreshold)) {
                result = true;
                logNetworkEvent(NetworkEvent.NETWORK_CONSECUTIVE_DNS_TIMEOUT_FOUND);
            }
        }

        if (VDBG_STALL) {
            log("isDataStall: result=" + result + ", consecutive dns timeout count="
                    + mDnsStallDetector.getConsecutiveTimeoutCount());
        }

        return result;
    }
}
