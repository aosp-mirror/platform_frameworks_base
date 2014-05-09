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

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.connectivity.NetworkAgentInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
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
     * Message to self indicating it's time to check for a captive portal again.
     * TODO - Remove this once broadcast intents are used to communicate with
     * apps to log into captive portals.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_CAPTIVE_PORTAL_REEVALUATE = BASE + 6;

    /**
     * Message to self indicating it's time to evaluate a network's connectivity.
     * arg1 = Token to ignore old messages.
     */
    private static final int CMD_REEVALUATE = BASE + 7;

    /**
     * Message to self indicating network evaluation is complete.
     * arg1 = Token to ignore old messages.
     * arg2 = HTTP response code of network evaluation.
     */
    private static final int EVENT_REEVALUATION_COMPLETE = BASE + 8;

    /**
     * Inform NetworkMonitor that the network has disconnected.
     */
    public static final int CMD_NETWORK_DISCONNECTED = BASE + 9;

    /**
     * Force evaluation even if it has succeeded in the past.
     */
    public static final int CMD_FORCE_REEVALUATION = BASE + 10;

    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    // Default to 30s linger time-out.
    private static final int DEFAULT_LINGER_DELAY_MS = 30000;
    private final int mLingerDelayMs;
    private int mLingerToken = 0;

    private static final int CAPTIVE_PORTAL_REEVALUATE_DELAY_MS = 5000;
    private int mCaptivePortalReevaluateToken = 0;

    // Negative values disable reevaluation.
    private static final String REEVALUATE_DELAY_PROPERTY = "persist.netmon.reeval_delay";
    // Default to 5s reevaluation delay.
    private static final int DEFAULT_REEVALUATE_DELAY_MS = 5000;
    private final int mReevaluateDelayMs;
    private int mReevaluateToken = 0;

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final NetworkAgentInfo mNetworkAgentInfo;

    private String mServer;
    private boolean mIsCaptivePortalCheckEnabled = false;

    private State mDefaultState = new DefaultState();
    private State mOfflineState = new OfflineState();
    private State mValidatedState = new ValidatedState();
    private State mEvaluatingState = new EvaluatingState();
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
        addState(mCaptivePortalState, mDefaultState);
        addState(mLingeringState, mDefaultState);
        setInitialState(mOfflineState);

        mServer = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_SERVER);
        if (mServer == null) mServer = DEFAULT_SERVER;

        mLingerDelayMs = SystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);
        mReevaluateDelayMs = SystemProperties.getInt(REEVALUATE_DELAY_PROPERTY,
                DEFAULT_REEVALUATE_DELAY_MS);

        // TODO: Enable this when we're ready.
        // mIsCaptivePortalCheckEnabled = Settings.Global.getInt(mContext.getContentResolver(),
        //        Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED, 1) == 1;

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
            sendMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_REEVALUATE:
                    if (message.arg1 != mReevaluateToken)
                        break;
                    // If network provides no internet connectivity adjust evaluation.
                    if (mNetworkAgentInfo.networkCapabilities.hasCapability(
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
                        transitionTo(mCaptivePortalState);
                    } else {
                        if (mReevaluateDelayMs >= 0) {
                            Message msg = obtainMessage(CMD_REEVALUATE, ++mReevaluateToken, 0);
                            sendMessageDelayed(msg, mReevaluateDelayMs);
                        }
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    // TODO: Until we add an intent from the app handling captive portal
    // login we'll just re-evaluate after a delay.
    private class CaptivePortalState extends State {
        @Override
        public void enter() {
            Message message = obtainMessage(CMD_CAPTIVE_PORTAL_REEVALUATE,
                    ++mCaptivePortalReevaluateToken, 0);
            sendMessageDelayed(message, CAPTIVE_PORTAL_REEVALUATE_DELAY_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_CAPTIVE_PORTAL_REEVALUATE:
                    if (message.arg1 != mCaptivePortalReevaluateToken)
                        break;
                    transitionTo(mEvaluatingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
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
        if (DBG) log("Checking " + urlString);
        HttpURLConnection urlConnection = null;
        Socket socket = null;
        int httpResponseCode = 500;
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
                socket = new Socket();
                // TODO: setNetworkForSocket(socket, mNetworkAgentInfo.network.netId);
                InetSocketAddress address = new InetSocketAddress(url.getHost(), 80);
                // TODO: address = new InetSocketAddress(
                //               getByNameOnNetwork(mNetworkAgentInfo.network, url.getHost()), 80);
                socket.connect(address);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                writer.write("GET " + url.getFile() + " HTTP/1.1\r\n\n");
                writer.flush();
                String response = reader.readLine();
                if (response.startsWith("HTTP/1.1 ")) {
                    httpResponseCode = Integer.parseInt(response.substring(9, 12));
                }
            }
            if (DBG) log("isCaptivePortal: ret=" + httpResponseCode);
        } catch (IOException e) {
            if (DBG) log("Probably not a portal: exception " + e);
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
