/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import com.android.internal.R;

/**
 * This class allows captive portal detection on a network.
 * @hide
 */
public class CaptivePortalTracker extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "CaptivePortalTracker";

    private static final String DEFAULT_SERVER = "clients3.google.com";

    private static final int SOCKET_TIMEOUT_MS = 10000;

    private String mServer;
    private String mUrl;
    private boolean mIsCaptivePortalCheckEnabled = false;
    private IConnectivityManager mConnService;
    private TelephonyManager mTelephonyManager;
    private Context mContext;
    private NetworkInfo mNetworkInfo;

    private static final int CMD_DETECT_PORTAL          = 0;
    private static final int CMD_CONNECTIVITY_CHANGE    = 1;
    private static final int CMD_DELAYED_CAPTIVE_CHECK  = 2;

    /* This delay happens every time before we do a captive check on a network */
    private static final int DELAYED_CHECK_INTERVAL_MS = 10000;
    private int mDelayedCheckToken = 0;

    private State mDefaultState = new DefaultState();
    private State mNoActiveNetworkState = new NoActiveNetworkState();
    private State mActiveNetworkState = new ActiveNetworkState();
    private State mDelayedCaptiveCheckState = new DelayedCaptiveCheckState();

    private static final String SETUP_WIZARD_PACKAGE = "com.google.android.setupwizard";
    private boolean mDeviceProvisioned = false;
    private ProvisioningObserver mProvisioningObserver;

    private CaptivePortalTracker(Context context, IConnectivityManager cs) {
        super(TAG);

        mContext = context;
        mConnService = cs;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mProvisioningObserver = new ProvisioningObserver();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE);
        mContext.registerReceiver(mReceiver, filter);

        mServer = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_SERVER);
        if (mServer == null) mServer = DEFAULT_SERVER;

        mIsCaptivePortalCheckEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED, 1) == 1;

        addState(mDefaultState);
            addState(mNoActiveNetworkState, mDefaultState);
            addState(mActiveNetworkState, mDefaultState);
                addState(mDelayedCaptiveCheckState, mActiveNetworkState);
        setInitialState(mNoActiveNetworkState);
    }

    private class ProvisioningObserver extends ContentObserver {
        ProvisioningObserver() {
            super(new Handler());
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.DEVICE_PROVISIONED), false, this);
            onChange(false); // load initial value
        }

        @Override
        public void onChange(boolean selfChange) {
            mDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Normally, we respond to CONNECTIVITY_ACTION, allowing time for the change in
            // connectivity to stabilize, but if the device is not yet provisioned, respond
            // immediately to speed up transit through the setup wizard.
            if ((mDeviceProvisioned && action.equals(ConnectivityManager.CONNECTIVITY_ACTION))
                    || (!mDeviceProvisioned
                            && action.equals(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE))) {
                NetworkInfo info = intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_NETWORK_INFO);
                sendMessage(obtainMessage(CMD_CONNECTIVITY_CHANGE, info));
            }
        }
    };

    public static CaptivePortalTracker makeCaptivePortalTracker(Context context,
            IConnectivityManager cs) {
        CaptivePortalTracker captivePortal = new CaptivePortalTracker(context, cs);
        captivePortal.start();
        return captivePortal;
    }

    public void detectCaptivePortal(NetworkInfo info) {
        sendMessage(obtainMessage(CMD_DETECT_PORTAL, info));
    }

    private class DefaultState extends State {
        @Override
        public void enter() {
            setNotificationOff();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_DETECT_PORTAL:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    // Checking on a secondary connection is not supported
                    // yet
                    notifyPortalCheckComplete(info);
                    break;
                case CMD_CONNECTIVITY_CHANGE:
                case CMD_DELAYED_CAPTIVE_CHECK:
                    break;
                default:
                    loge("Ignoring " + message);
                    break;
            }
            return HANDLED;
        }
    }

    private class NoActiveNetworkState extends State {
        @Override
        public void enter() {
            mNetworkInfo = null;
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            InetAddress server;
            NetworkInfo info;
            switch (message.what) {
                case CMD_CONNECTIVITY_CHANGE:
                    info = (NetworkInfo) message.obj;
                    if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                        if (info.isConnected() && isActiveNetwork(info)) {
                            mNetworkInfo = info;
                            transitionTo(mDelayedCaptiveCheckState);
                        }
                    } else {
                        log(getName() + " not a wifi connectivity change, ignore");
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class ActiveNetworkState extends State {
        @Override
        public void enter() {
            setNotificationOff();
        }

        @Override
        public boolean processMessage(Message message) {
            NetworkInfo info;
            switch (message.what) {
               case CMD_CONNECTIVITY_CHANGE:
                    info = (NetworkInfo) message.obj;
                    if (!info.isConnected()
                            && info.getType() == mNetworkInfo.getType()) {
                        if (DBG) log("Disconnected from active network " + info);
                        transitionTo(mNoActiveNetworkState);
                    } else if (info.getType() != mNetworkInfo.getType() &&
                            info.isConnected() &&
                            isActiveNetwork(info)) {
                        if (DBG) log("Active network switched " + info);
                        deferMessage(message);
                        transitionTo(mNoActiveNetworkState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }



    private class DelayedCaptiveCheckState extends State {
        @Override
        public void enter() {
            Message message = obtainMessage(CMD_DELAYED_CAPTIVE_CHECK, ++mDelayedCheckToken, 0);
            if (mDeviceProvisioned) {
                sendMessageDelayed(message, DELAYED_CHECK_INTERVAL_MS);
            } else {
                sendMessage(message);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString());
            switch (message.what) {
                case CMD_DELAYED_CAPTIVE_CHECK:
                    if (message.arg1 == mDelayedCheckToken) {
                        InetAddress server = lookupHost(mServer);
                        boolean captive = server != null && isCaptivePortal(server);
                        if (captive) {
                            if (DBG) log("Captive network " + mNetworkInfo);
                        } else {
                            if (DBG) log("Not captive network " + mNetworkInfo);
                        }
                        notifyPortalCheckCompleted(mNetworkInfo, captive);
                        if (mDeviceProvisioned) {
                            if (captive) {
                                // Setup Wizard will assist the user in connecting to a captive
                                // portal, so make the notification visible unless during setup
                                try {
                                    mConnService.setProvisioningNotificationVisible(true,
                                        mNetworkInfo.getType(), mNetworkInfo.getExtraInfo(), mUrl);
                                } catch(RemoteException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else {
                            Intent intent = new Intent(
                                    ConnectivityManager.ACTION_CAPTIVE_PORTAL_TEST_COMPLETED);
                            intent.putExtra(ConnectivityManager.EXTRA_IS_CAPTIVE_PORTAL, captive);
                            intent.setPackage(SETUP_WIZARD_PACKAGE);
                            mContext.sendBroadcast(intent);
                        }

                        transitionTo(mActiveNetworkState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void notifyPortalCheckComplete(NetworkInfo info) {
        if (info == null) {
            loge("notifyPortalCheckComplete on null");
            return;
        }
        try {
            if (DBG) log("notifyPortalCheckComplete: ni=" + info);
            mConnService.captivePortalCheckComplete(info);
        } catch(RemoteException e) {
            e.printStackTrace();
        }
    }

    private void notifyPortalCheckCompleted(NetworkInfo info, boolean isCaptivePortal) {
        if (info == null) {
            loge("notifyPortalCheckComplete on null");
            return;
        }
        try {
            if (DBG) log("notifyPortalCheckCompleted: captive=" + isCaptivePortal + " ni=" + info);
            mConnService.captivePortalCheckCompleted(info, isCaptivePortal);
        } catch(RemoteException e) {
            e.printStackTrace();
        }
    }

    private boolean isActiveNetwork(NetworkInfo info) {
        try {
            NetworkInfo active = mConnService.getActiveNetworkInfo();
            if (active != null && active.getType() == info.getType()) {
                return true;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void setNotificationOff() {
        try {
            mConnService.setProvisioningNotificationVisible(false, ConnectivityManager.TYPE_NONE,
                    null, null);
        } catch (RemoteException e) {
            log("setNotificationOff: " + e);
        }
    }

    /**
     * Do a URL fetch on a known server to see if we get the data we expect
     */
    private boolean isCaptivePortal(InetAddress server) {
        HttpURLConnection urlConnection = null;
        if (!mIsCaptivePortalCheckEnabled) return false;

        mUrl = "http://" + server.getHostAddress() + "/generate_204";
        if (DBG) log("Checking " + mUrl);
        try {
            URL url = new URL(mUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setInstanceFollowRedirects(false);
            urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
            urlConnection.setUseCaches(false);
            urlConnection.getInputStream();
            // we got a valid response, but not from the real google
            return urlConnection.getResponseCode() != 204;
        } catch (IOException e) {
            if (DBG) log("Probably not a portal: exception " + e);
            return false;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private InetAddress lookupHost(String hostname) {
        InetAddress inetAddress[];
        try {
            inetAddress = InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            return null;
        }

        for (InetAddress a : inetAddress) {
            if (a instanceof Inet4Address) return a;
        }
        return null;
    }
}
