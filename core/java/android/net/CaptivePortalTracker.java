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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.internal.R;

/**
 * This class allows captive portal detection
 * @hide
 */
public class CaptivePortalTracker {
    private static final boolean DBG = true;
    private static final String TAG = "CaptivePortalTracker";

    private static final String DEFAULT_SERVER = "clients3.google.com";
    private static final String NOTIFICATION_ID = "CaptivePortal.Notification";

    private static final int SOCKET_TIMEOUT_MS = 10000;

    private String mServer;
    private String mUrl;
    private boolean mNotificationShown = false;
    private boolean mIsCaptivePortalCheckEnabled = false;
    private InternalHandler mHandler;
    private IConnectivityManager mConnService;
    private Context mContext;
    private NetworkInfo mNetworkInfo;
    private boolean mIsCaptivePortal = false;

    private static final int DETECT_PORTAL = 0;
    private static final int HANDLE_CONNECT = 1;

    /**
     * Activity Action: Switch to the captive portal network
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    public static final String ACTION_SWITCH_TO_CAPTIVE_PORTAL
            = "android.net.SWITCH_TO_CAPTIVE_PORTAL";

    private CaptivePortalTracker(Context context, NetworkInfo info, IConnectivityManager cs) {
        mContext = context;
        mNetworkInfo = info;
        mConnService = cs;

        HandlerThread handlerThread = new HandlerThread("CaptivePortalThread");
        handlerThread.start();
        mHandler = new InternalHandler(handlerThread.getLooper());
        mHandler.obtainMessage(DETECT_PORTAL).sendToTarget();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SWITCH_TO_CAPTIVE_PORTAL);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);

        mContext.registerReceiver(mReceiver, filter);

        mServer = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.CAPTIVE_PORTAL_SERVER);
        if (mServer == null) mServer = DEFAULT_SERVER;

        mIsCaptivePortalCheckEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.CAPTIVE_PORTAL_DETECTION_ENABLED, 1) == 1;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_SWITCH_TO_CAPTIVE_PORTAL)) {
                notifyPortalCheckComplete();
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                NetworkInfo info = intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_NETWORK_INFO);
                mHandler.obtainMessage(HANDLE_CONNECT, info).sendToTarget();
            }
        }
    };

    public static CaptivePortalTracker detect(Context context, NetworkInfo info,
            IConnectivityManager cs) {
        CaptivePortalTracker captivePortal = new CaptivePortalTracker(context, info, cs);
        return captivePortal;
    }

    private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DETECT_PORTAL:
                    InetAddress server = lookupHost(mServer);
                    if (server != null) {
                        requestRouteToHost(server);
                        if (isCaptivePortal(server)) {
                            if (DBG) log("Captive portal " + mNetworkInfo);
                            setNotificationVisible(true);
                            mIsCaptivePortal = true;
                            break;
                        }
                    }
                    notifyPortalCheckComplete();
                    quit();
                    break;
                case HANDLE_CONNECT:
                    NetworkInfo info = (NetworkInfo) msg.obj;
                    if (info.getType() != mNetworkInfo.getType()) break;

                    if (info.getState() == NetworkInfo.State.CONNECTED ||
                            info.getState() == NetworkInfo.State.DISCONNECTED) {
                        setNotificationVisible(false);
                    }

                    /* Connected to a captive portal */
                    if (info.getState() == NetworkInfo.State.CONNECTED &&
                            mIsCaptivePortal) {
                        launchBrowser();
                        quit();
                    }
                    break;
                default:
                    loge("Unhandled message " + msg);
                    break;
            }
        }

        private void quit() {
            mIsCaptivePortal = false;
            getLooper().quit();
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private void launchBrowser() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mUrl));
        intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    private void notifyPortalCheckComplete() {
        try {
            mConnService.captivePortalCheckComplete(mNetworkInfo);
        } catch(RemoteException e) {
            e.printStackTrace();
        }
    }

    private void requestRouteToHost(InetAddress server) {
        try {
            mConnService.requestRouteToHostAddress(mNetworkInfo.getType(),
                    server.getAddress());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Do a URL fetch on a known server to see if we get the data we expect
     */
    private boolean isCaptivePortal(InetAddress server) {
        HttpURLConnection urlConnection = null;
        if (!mIsCaptivePortalCheckEnabled) return false;

        mUrl = "http://" + server.getHostAddress() + "/generate_204";
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
            CharSequence title = r.getString(R.string.wifi_available_sign_in, 0);
            CharSequence details = r.getString(R.string.wifi_available_sign_in_detailed,
                    mNetworkInfo.getExtraInfo());

            Notification notification = new Notification();
            notification.when = 0;
            notification.icon = com.android.internal.R.drawable.stat_notify_wifi_in_range;
            notification.flags = Notification.FLAG_AUTO_CANCEL;
            notification.contentIntent = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(CaptivePortalTracker.ACTION_SWITCH_TO_CAPTIVE_PORTAL), 0);

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
