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
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkAgentInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * {@hide}
 */
public class NetworkMonitor extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "NetworkMonitor";
    private static final String DEFAULT_SERVER = "clients3.google.com";
    private static final int SOCKET_TIMEOUT_MS = 10000;

    // Intent broadcast when user selects sign-in notification.
    private static final String ACTION_SIGN_IN_REQUESTED =
            "android.net.netmon.sign_in_requested";

    // Keep these in sync with CaptivePortalLoginActivity.java.
    // Intent broadcast from CaptivePortalLogin indicating sign-in is complete.
    // Extras:
    //     EXTRA_TEXT       = netId
    //     LOGGED_IN_RESULT = "1" if we should use network, "0" if not.
    private static final String ACTION_CAPTIVE_PORTAL_LOGGED_IN =
            "android.net.netmon.captive_portal_logged_in";
    private static final String LOGGED_IN_RESULT = "result";

    private static final int BASE = Protocol.BASE_NETWORK_MONITOR;

    /**
     * Inform NetworkMonitor that their network is connected.
     * Initiates Network Validation.
     */
    public static final int CMD_NETWORK_CONNECTED = BASE + 1;

    /**
     * Inform ConnectivityService that the network is validated.
     * obj = NetworkAgentInfo
     */
    public static final int EVENT_NETWORK_VALIDATED = BASE + 2;

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
     * Message to self indicating network evaluation is complete.
     * arg1 = Token to ignore old messages.
     * arg2 = HTTP response code of network evaluation.
     */
    private static final int EVENT_REEVALUATION_COMPLETE = BASE + 7;

    /**
     * Inform NetworkMonitor that the network has disconnected.
     */
    public static final int CMD_NETWORK_DISCONNECTED = BASE + 8;

    /**
     * Force evaluation even if it has succeeded in the past.
     */
    public static final int CMD_FORCE_REEVALUATION = BASE + 9;

    /**
     * Message to self indicating captive portal login is complete.
     * arg1 = Token to ignore old messages.
     * arg2 = 1 if we should use this network, 0 otherwise.
     */
    private static final int CMD_CAPTIVE_PORTAL_LOGGED_IN = BASE + 10;

    /**
     * Message to self indicating user desires to log into captive portal.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_USER_WANTS_SIGN_IN = BASE + 11;

    /**
     * Request ConnectivityService display provisioning notification.
     * arg1    = Whether to make the notification visible.
     * obj     = Intent to be launched when notification selected by user.
     * replyTo = NetworkAgentInfo.messenger so ConnectivityService can identify sender.
     */
    public static final int EVENT_PROVISIONING_NOTIFICATION = BASE + 12;

    /**
     * Message to self indicating sign-in app bypassed captive portal.
     */
    private static final int EVENT_APP_BYPASSED_CAPTIVE_PORTAL = BASE + 13;

    /**
     * Message to self indicating no sign-in app responded.
     */
    private static final int EVENT_NO_APP_RESPONSE = BASE + 14;

    /**
     * Message to self indicating sign-in app indicates sign-in is not possible.
     */
    private static final int EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE = BASE + 15;

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

    private int mCaptivePortalLoggedInToken = 0;
    private int mUserPromptedToken = 0;

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final NetworkAgentInfo mNetworkAgentInfo;

    private String mServer;
    private boolean mIsCaptivePortalCheckEnabled = false;

    private State mDefaultState = new DefaultState();
    private State mOfflineState = new OfflineState();
    private State mValidatedState = new ValidatedState();
    private State mEvaluatingState = new EvaluatingState();
    private State mUninteractiveAppsPromptedState = new UninteractiveAppsPromptedState();
    private State mUserPromptedState = new UserPromptedState();
    private State mInteractiveAppsPromptedState = new InteractiveAppsPromptedState();
    private State mCaptivePortalState = new CaptivePortalState();
    private State mLingeringState = new LingeringState();

    public NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo) {
        // Add suffix indicating which NetworkMonitor we're talking about.
        super(TAG + networkAgentInfo.name());

        mContext = context;
        mConnectivityServiceHandler = handler;
        mNetworkAgentInfo = networkAgentInfo;

        addState(mDefaultState);
        addState(mOfflineState, mDefaultState);
        addState(mValidatedState, mDefaultState);
        addState(mEvaluatingState, mDefaultState);
        addState(mUninteractiveAppsPromptedState, mDefaultState);
        addState(mUserPromptedState, mDefaultState);
        addState(mInteractiveAppsPromptedState, mDefaultState);
        addState(mCaptivePortalState, mDefaultState);
        addState(mLingeringState, mDefaultState);
        setInitialState(mOfflineState);

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
                    break;
                case CMD_NETWORK_CONNECTED:
                    if (DBG) log("Connected");
                    transitionTo(mEvaluatingState);
                    break;
                case CMD_NETWORK_DISCONNECTED:
                    if (DBG) log("Disconnected");
                    transitionTo(mOfflineState);
                    break;
                case CMD_FORCE_REEVALUATION:
                    if (DBG) log("Forcing reevaluation");
                    transitionTo(mEvaluatingState);
                    break;
                default:
                    break;
            }
            return HANDLED;
        }
    }

    private class OfflineState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            return NOT_HANDLED;
        }
    }

    private class ValidatedState extends State {
        @Override
        public void enter() {
            if (DBG) log("Validated");
            mConnectivityServiceHandler.sendMessage(
                    obtainMessage(EVENT_NETWORK_VALIDATED, mNetworkAgentInfo));
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    transitionTo(mValidatedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class EvaluatingState extends State {
        private int mRetries;

        private class EvaluateInternetConnectivity extends Thread {
            private int mToken;
            EvaluateInternetConnectivity(int token) {
                mToken = token;
            }
            public void run() {
                sendMessage(EVENT_REEVALUATION_COMPLETE, mToken, isCaptivePortal());
            }
        }

        @Override
        public void enter() {
            mRetries = 0;
            sendMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_REEVALUATE:
                    if (message.arg1 != mReevaluateToken)
                        break;
                    if (mNetworkAgentInfo.isVPN()) {
                        transitionTo(mValidatedState);
                    }
                    // If network provides no internet connectivity adjust evaluation.
                    if (!mNetworkAgentInfo.networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        // TODO: Try to verify something works.  Do all gateways respond to pings?
                        transitionTo(mValidatedState);
                    }
                    // Kick off a thread to perform internet connectivity evaluation.
                    Thread thread = new EvaluateInternetConnectivity(mReevaluateToken);
                    thread.run();
                    break;
                case EVENT_REEVALUATION_COMPLETE:
                    if (message.arg1 != mReevaluateToken)
                        break;
                    int httpResponseCode = message.arg2;
                    if (httpResponseCode == 204) {
                        transitionTo(mValidatedState);
                    } else if (httpResponseCode >= 200 && httpResponseCode <= 399) {
                        transitionTo(mUninteractiveAppsPromptedState);
                    } else if (++mRetries > MAX_RETRIES) {
                        transitionTo(mOfflineState);
                    } else if (mReevaluateDelayMs >= 0) {
                        Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
                        sendMessageDelayed(msg, mReevaluateDelayMs);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class AppRespondedBroadcastReceiver extends BroadcastReceiver {
        private static final int CAPTIVE_PORTAL_UNINITIALIZED_RETURN_CODE = 0;
        private boolean mCanceled;
        AppRespondedBroadcastReceiver() {
            mCanceled = false;
        }
        public void send(String action) {
            Intent intent = new Intent(action);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK, mNetworkAgentInfo.network);
            mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, null, this, getHandler(),
                    CAPTIVE_PORTAL_UNINITIALIZED_RETURN_CODE, null, null);
        }
        public void cancel() {
            mCanceled = true;
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mCanceled) {
                cancel();
                switch (getResultCode()) {
                    case ConnectivityManager.CAPTIVE_PORTAL_SIGNED_IN:
                        sendMessage(EVENT_APP_BYPASSED_CAPTIVE_PORTAL);
                        break;
                    case ConnectivityManager.CAPTIVE_PORTAL_DISCONNECT:
                        sendMessage(EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE);
                        break;
                    // NOTE: This case label makes compiler enforce no overlap between result codes.
                    case CAPTIVE_PORTAL_UNINITIALIZED_RETURN_CODE:
                    default:
                        sendMessage(EVENT_NO_APP_RESPONSE);
                        break;
                }
            }
        }
    }

    private class UninteractiveAppsPromptedState extends State {
        private AppRespondedBroadcastReceiver mReceiver;
        @Override
        public void enter() {
            mReceiver = new AppRespondedBroadcastReceiver();
            mReceiver.send(ConnectivityManager.ACTION_CAPTIVE_PORTAL_DETECTED);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case EVENT_APP_BYPASSED_CAPTIVE_PORTAL:
                    transitionTo(mValidatedState);
                    break;
                case EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE:
                    transitionTo(mOfflineState);
                    break;
                case EVENT_NO_APP_RESPONSE:
                    transitionTo(mUserPromptedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        public void exit() {
            mReceiver.cancel();
        }
    }

    private class UserPromptedState extends State {
        private class UserRespondedBroadcastReceiver extends BroadcastReceiver {
            private final int mToken;
            UserRespondedBroadcastReceiver(int token) {
                mToken = token;
            }
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Integer.parseInt(intent.getStringExtra(Intent.EXTRA_TEXT)) ==
                        mNetworkAgentInfo.network.netId) {
                    sendMessage(obtainMessage(CMD_USER_WANTS_SIGN_IN, mToken));
                }
            }
        }

        private UserRespondedBroadcastReceiver mUserRespondedBroadcastReceiver;

        @Override
        public void enter() {
            // Wait for user to select sign-in notifcation.
            mUserRespondedBroadcastReceiver = new UserRespondedBroadcastReceiver(
                    ++mUserPromptedToken);
            IntentFilter filter = new IntentFilter(ACTION_SIGN_IN_REQUESTED);
            mContext.registerReceiver(mUserRespondedBroadcastReceiver, filter);
            // Initiate notification to sign-in.
            Intent intent = new Intent(ACTION_SIGN_IN_REQUESTED);
            intent.putExtra(Intent.EXTRA_TEXT, String.valueOf(mNetworkAgentInfo.network.netId));
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 1, 0,
                    PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
            message.replyTo = mNetworkAgentInfo.messenger;
            mConnectivityServiceHandler.sendMessage(message);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_USER_WANTS_SIGN_IN:
                    if (message.arg1 != mUserPromptedToken)
                        break;
                    transitionTo(mInteractiveAppsPromptedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            Message message = obtainMessage(EVENT_PROVISIONING_NOTIFICATION, 0, 0, null);
            message.replyTo = mNetworkAgentInfo.messenger;
            mConnectivityServiceHandler.sendMessage(message);
            mContext.unregisterReceiver(mUserRespondedBroadcastReceiver);
            mUserRespondedBroadcastReceiver = null;
        }
    }

    private class InteractiveAppsPromptedState extends State {
        private AppRespondedBroadcastReceiver mReceiver;
        @Override
        public void enter() {
            mReceiver = new AppRespondedBroadcastReceiver();
            mReceiver.send(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case EVENT_APP_BYPASSED_CAPTIVE_PORTAL:
                    transitionTo(mValidatedState);
                    break;
                case EVENT_APP_INDICATES_SIGN_IN_IMPOSSIBLE:
                    transitionTo(mOfflineState);
                    break;
                case EVENT_NO_APP_RESPONSE:
                    transitionTo(mCaptivePortalState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        public void exit() {
            mReceiver.cancel();
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
                        break;
                    if (message.arg2 == 0) {
                        // TODO: Should teardown network.
                        transitionTo(mOfflineState);
                    } else {
                        transitionTo(mValidatedState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mContext.unregisterReceiver(mCaptivePortalLoggedInBroadcastReceiver);
            mCaptivePortalLoggedInBroadcastReceiver = null;
        }
    }

    private class LingeringState extends State {
        @Override
        public void enter() {
            Message message = obtainMessage(CMD_LINGER_EXPIRED, ++mLingerToken, 0);
            sendMessageDelayed(message, mLingerDelayMs);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_NETWORK_CONNECTED:
                    // Go straight to active as we've already evaluated.
                    transitionTo(mValidatedState);
                    break;
                case CMD_LINGER_EXPIRED:
                    if (message.arg1 != mLingerToken)
                        break;
                    mConnectivityServiceHandler.sendMessage(
                            obtainMessage(EVENT_NETWORK_LINGER_COMPLETE, mNetworkAgentInfo));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    /**
     * Do a URL fetch on a known server to see if we get the data we expect.
     * Returns HTTP response code.
     */
    private int isCaptivePortal() {
        if (!mIsCaptivePortalCheckEnabled) return 204;

        String urlString = "http://" + mServer + "/generate_204";
        if (DBG) {
            log("Checking " + urlString + " on " + mNetworkAgentInfo.networkInfo.getExtraInfo());
        }
        HttpURLConnection urlConnection = null;
        Socket socket = null;
        int httpResponseCode = 599;
        try {
            URL url = new URL(urlString);
            if (false) {
                // TODO: Need to add URLConnection.setNetwork() before we can enable.
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setInstanceFollowRedirects(false);
                urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
                urlConnection.setUseCaches(false);
                urlConnection.getInputStream();
                httpResponseCode = urlConnection.getResponseCode();
            } else {
                socket = mNetworkAgentInfo.network.getSocketFactory().createSocket();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                // Lookup addresses only on this Network.
                InetAddress[] hostAddresses = mNetworkAgentInfo.network.getAllByName(url.getHost());
                // Try all addresses.
                for (int i = 0; i < hostAddresses.length; i++) {
                    if (DBG) log("Connecting to " + hostAddresses[i]);
                    try {
                        socket.connect(new InetSocketAddress(hostAddresses[i],
                                url.getDefaultPort()), SOCKET_TIMEOUT_MS);
                        break;
                    } catch (IOException e) {
                        // Ignore exceptions on all but the last.
                        if (i == (hostAddresses.length - 1)) throw e;
                    }
                }
                if (DBG) log("Requesting " + url.getFile());
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                writer.write("GET " + url.getFile() + " HTTP/1.1\r\nHost: " + url.getHost() +
                        "\r\nUser-Agent: " + System.getProperty("http.agent") +
                        "\r\nConnection: close\r\n\r\n");
                writer.flush();
                String response = reader.readLine();
                if (DBG) log("Received \"" + response + "\"");
                if (response != null && (response.startsWith("HTTP/1.1 ") ||
                        // NOTE: We may want to consider an "HTTP/1.0 204" response to be a captive
                        // portal.  The only example of this seen so far was a captive portal.  For
                        // the time being go with prior behavior of assuming it's not a captive
                        // portal.  If it is considered a captive portal, a different sign-in URL
                        // is needed (i.e. can't browse a 204).  This could be the result of an HTTP
                        // proxy server.
                        response.startsWith("HTTP/1.0 "))) {
                    // NOTE: We may want to consider an "200" response with "Content-length=0" to
                    // not be a captive portal. This could be the result of an HTTP proxy server.
                    // See b/9972012.
                    httpResponseCode = Integer.parseInt(response.substring(9, 12));
                } else {
                    // A response was received but not understood.  The fact that a
                    // response was sent indicates there's some kind of responsive network
                    // out there so put up the notification to the user to log into the network
                    // so the user can have the final say as to whether the network is useful.
                    httpResponseCode = 399;
                    while (DBG && response != null && !response.isEmpty()) {
                        try {
                            response = reader.readLine();
                        } catch (IOException e) {
                            break;
                        }
                        log("Received \"" + response + "\"");
                    }
                }
            }
            if (DBG) log("isCaptivePortal: ret=" + httpResponseCode);
        } catch (IOException e) {
            if (DBG) log("Probably not a portal: exception " + e);
            if (httpResponseCode == 599) {
                // TODO: Ping gateway and DNS server and log results.
            }
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        return httpResponseCode;
    }
}
