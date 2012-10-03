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
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.os.UserHandle;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.URL;
import java.net.UnknownHostException;

import com.android.internal.R;

/**
 * This class allows captive portal detection on a network.
 * @hide
 */
public class CaptivePortalTracker extends StateMachine {
    private static final boolean DBG = false;
    private static final String TAG = "CaptivePortalTracker";

    private static final String DEFAULT_SERVER = "clients3.google.com";
    private static final String NOTIFICATION_ID = "CaptivePortal.Notification";

    private static final int SOCKET_TIMEOUT_MS = 10000;

    private String mServer;
    private String mUrl;
    private boolean mNotificationShown = false;
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

    private CaptivePortalTracker(Context context, IConnectivityManager cs) {
        super(TAG);

        mContext = context;
        mConnService = cs;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
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

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
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
            if (DBG) log(getName() + "\n");
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
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
            if (DBG) log(getName() + "\n");
            mNetworkInfo = null;
            /* Clear any previous notification */
            setNotificationVisible(false);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            InetAddress server;
            NetworkInfo info;
            switch (message.what) {
                case CMD_CONNECTIVITY_CHANGE:
                    info = (NetworkInfo) message.obj;
                    if (info.isConnected() && isActiveNetwork(info)) {
                        mNetworkInfo = info;
                        transitionTo(mDelayedCaptiveCheckState);
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
            if (DBG) log(getName() + "\n");
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
            if (DBG) log(getName() + "\n");
            sendMessageDelayed(obtainMessage(CMD_DELAYED_CAPTIVE_CHECK,
                        ++mDelayedCheckToken, 0), DELAYED_CHECK_INTERVAL_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_DELAYED_CAPTIVE_CHECK:
                    if (message.arg1 == mDelayedCheckToken) {
                        InetAddress server = lookupHost(mServer);
                        if (server != null) {
                            if (isCaptivePortal(server)) {
                                if (DBG) log("Captive network " + mNetworkInfo);
                                setNotificationVisible(true);
                            }
                        }
                        if (DBG) log("Not captive network " + mNetworkInfo);
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
            mConnService.captivePortalCheckComplete(info);
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

    private void setNotificationVisible(boolean visible) {
        // if it should be hidden and it is already hidden, then noop
        if (!visible && !mNotificationShown) {
            return;
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            CharSequence title;
            CharSequence details;
            int icon;
            switch (mNetworkInfo.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                    title = r.getString(R.string.wifi_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            mNetworkInfo.getExtraInfo());
                    icon = R.drawable.stat_notify_wifi_in_range;
                    break;
                case ConnectivityManager.TYPE_MOBILE:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    // TODO: Change this to pull from NetworkInfo once a printable
                    // name has been added to it
                    details = mTelephonyManager.getNetworkOperatorName();
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
                default:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            mNetworkInfo.getExtraInfo());
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
            }

            Notification notification = new Notification();
            notification.when = 0;
            notification.icon = icon;
            notification.flags = Notification.FLAG_AUTO_CANCEL;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mUrl));
            intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                    Intent.FLAG_ACTIVITY_NEW_TASK);
            notification.contentIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
            notification.tickerText = title;
            notification.setLatestEventInfo(mContext, title, details, notification.contentIntent);

            notificationManager.notify(NOTIFICATION_ID, 1, notification);
        } else {
            notificationManager.cancel(NOTIFICATION_ID, 1);
        }
        mNotificationShown = visible;
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

}
