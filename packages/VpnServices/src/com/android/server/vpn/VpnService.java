/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vpn;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The service base class for managing a type of VPN connection.
 */
abstract class VpnService<E extends VpnProfile> {
    private static final int NOTIFICATION_ID = 1;

    private static final String DNS1 = "net.dns1";
    private static final String DNS2 = "net.dns2";
    private static final String VPN_DNS1 = "vpn.dns1";
    private static final String VPN_DNS2 = "vpn.dns2";
    private static final String VPN_STATUS = "vpn.status";
    private static final String VPN_IS_UP = "ok";
    private static final String VPN_IS_DOWN = "down";

    private static final String REMOTE_IP = "net.ipremote";
    private static final String DNS_DOMAIN_SUFFICES = "net.dns.search";

    private static final int AUTH_ERROR_CODE = 51;

    private final String TAG = VpnService.class.getSimpleName();

    E mProfile;
    VpnServiceBinder mContext;

    private VpnState mState = VpnState.IDLE;
    private Throwable mError;

    // connection settings
    private String mOriginalDns1;
    private String mOriginalDns2;
    private String mVpnDns1 = "";
    private String mVpnDns2 = "";
    private String mOriginalDomainSuffices;

    private long mStartTime; // VPN connection start time

    // for helping managing multiple daemons
    private DaemonHelper mDaemonHelper = new DaemonHelper();

    // for helping showing, updating notification
    private NotificationHelper mNotification = new NotificationHelper();

    /**
     * Establishes a VPN connection with the specified username and password.
     */
    protected abstract void connect(String serverIp, String username,
            String password) throws IOException;

    /**
     * Starts a VPN daemon.
     */
    protected DaemonProxy startDaemon(String daemonName)
            throws IOException {
        return mDaemonHelper.startDaemon(daemonName);
    }

    /**
     * Returns the VPN profile associated with the connection.
     */
    protected E getProfile() {
        return mProfile;
    }

    /**
     * Returns the IP address of the specified host name.
     */
    protected String getIp(String hostName) throws IOException {
        return InetAddress.getByName(hostName).getHostAddress();
    }

    void setContext(VpnServiceBinder context, E profile) {
        mContext = context;
        mProfile = profile;
    }

    VpnState getState() {
        return mState;
    }

    synchronized boolean onConnect(String username, String password) {
        try {
            mState = VpnState.CONNECTING;
            broadcastConnectivity(VpnState.CONNECTING);

            String serverIp = getIp(getProfile().getServerName());

            onBeforeConnect();
            connect(serverIp, username, password);
            waitUntilConnectedOrTimedout();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "onConnect()", e);
            onError(e);
            return false;
        }
    }

    synchronized void onDisconnect() {
        try {
            Log.d(TAG, "       disconnecting VPN...");
            mState = VpnState.DISCONNECTING;
            broadcastConnectivity(VpnState.DISCONNECTING);
            mNotification.showDisconnect();

            mDaemonHelper.stopAll();
        } catch (Throwable e) {
            Log.e(TAG, "onDisconnect()", e);
        } finally {
            onFinalCleanUp();
        }
    }

    private void onError(Throwable error) {
        // error may occur during or after connection setup
        // and it may be due to one or all services gone
        if (mError != null) {
            Log.w(TAG, "   multiple errors occur, record the last one: "
                    + error);
        }
        mError = error;
        onDisconnect();
    }

    private void onError(int errorCode) {
        onError(new VpnConnectingError(errorCode));
    }


    private void onBeforeConnect() {
        mNotification.disableNotification();

        SystemProperties.set(VPN_DNS1, "-");
        SystemProperties.set(VPN_DNS2, "-");
        SystemProperties.set(VPN_STATUS, VPN_IS_DOWN);
        Log.d(TAG, "       VPN UP: " + SystemProperties.get(VPN_STATUS));
    }

    private void waitUntilConnectedOrTimedout() {
        sleep(2000); // 2 seconds
        for (int i = 0; i < 60; i++) {
            if (mState != VpnState.CONNECTING) {
                break;
            } else if (VPN_IS_UP.equals(
                    SystemProperties.get(VPN_STATUS))) {
                onConnected();
                return;
            } else if (mDaemonHelper.anySocketError()) {
                return;
            }
            sleep(500); // 0.5 second
        }

        synchronized (VpnService.this) {
            if (mState == VpnState.CONNECTING) {
                Log.d(TAG, "       connecting timed out !!");
                onError(new IOException("Connecting timed out"));
            }
        }
    }

    private synchronized void onConnected() {
        Log.d(TAG, "onConnected()");

        mDaemonHelper.closeSockets();
        saveVpnDnsProperties();
        saveAndSetDomainSuffices();

        mState = VpnState.CONNECTED;
        broadcastConnectivity(VpnState.CONNECTED);

        enterConnectivityLoop();
    }

    private synchronized void onFinalCleanUp() {
        Log.d(TAG, "onFinalCleanUp()");

        if (mState == VpnState.IDLE) return;

        // keep the notification when error occurs
        if (!anyError()) mNotification.disableNotification();

        restoreOriginalDnsProperties();
        restoreOriginalDomainSuffices();
        mState = VpnState.IDLE;
        broadcastConnectivity(VpnState.IDLE);

        // stop the service itself
        mContext.stopSelf();
    }

    private boolean anyError() {
        return (mError != null);
    }

    private void restoreOriginalDnsProperties() {
        // restore only if they are not overridden
        if (mVpnDns1.equals(SystemProperties.get(DNS1))) {
            Log.d(TAG, String.format("restore original dns prop: %s --> %s",
                    SystemProperties.get(DNS1), mOriginalDns1));
            Log.d(TAG, String.format("restore original dns prop: %s --> %s",
                    SystemProperties.get(DNS2), mOriginalDns2));
            SystemProperties.set(DNS1, mOriginalDns1);
            SystemProperties.set(DNS2, mOriginalDns2);
        }
    }

    private void saveVpnDnsProperties() {
        mOriginalDns1 = mOriginalDns2 = "";
        for (int i = 0; i < 5; i++) {
            mVpnDns1 = SystemProperties.get(VPN_DNS1);
            mVpnDns2 = SystemProperties.get(VPN_DNS2);
            if (mOriginalDns1.equals(mVpnDns1)) {
                Log.d(TAG, "wait for vpn dns to settle in..." + i);
                sleep(200);
            } else {
                mOriginalDns1 = SystemProperties.get(DNS1);
                mOriginalDns2 = SystemProperties.get(DNS2);
                SystemProperties.set(DNS1, mVpnDns1);
                SystemProperties.set(DNS2, mVpnDns2);
                Log.d(TAG, String.format("save original dns prop: %s, %s",
                        mOriginalDns1, mOriginalDns2));
                Log.d(TAG, String.format("set vpn dns prop: %s, %s",
                        mVpnDns1, mVpnDns2));
                return;
            }
        }
        Log.d(TAG, "saveVpnDnsProperties(): DNS not updated??");
        mOriginalDns1 = mVpnDns1 = SystemProperties.get(DNS1);
        mOriginalDns2 = mVpnDns2 = SystemProperties.get(DNS2);
    }

    private void saveAndSetDomainSuffices() {
        mOriginalDomainSuffices = SystemProperties.get(DNS_DOMAIN_SUFFICES);
        Log.d(TAG, "save original dns search: " + mOriginalDomainSuffices);
        String list = mProfile.getDomainSuffices();
        if (!TextUtils.isEmpty(list)) {
            SystemProperties.set(DNS_DOMAIN_SUFFICES, list);
        }
    }

    private void restoreOriginalDomainSuffices() {
        Log.d(TAG, "restore original dns search --> " + mOriginalDomainSuffices);
        SystemProperties.set(DNS_DOMAIN_SUFFICES, mOriginalDomainSuffices);
    }

    private void broadcastConnectivity(VpnState s) {
        VpnManager m = new VpnManager(mContext);
        Throwable err = mError;
        if ((s == VpnState.IDLE) && (err != null)) {
            if (err instanceof UnknownHostException) {
                m.broadcastConnectivity(mProfile.getName(), s,
                        VpnManager.VPN_ERROR_UNKNOWN_SERVER);
            } else if (err instanceof VpnConnectingError) {
                m.broadcastConnectivity(mProfile.getName(), s,
                        ((VpnConnectingError) err).getErrorCode());
            } else {
                m.broadcastConnectivity(mProfile.getName(), s,
                        VpnManager.VPN_ERROR_CONNECTION_FAILED);
            }
        } else {
            m.broadcastConnectivity(mProfile.getName(), s);
        }
    }

    private void enterConnectivityLoop() {
        mStartTime = System.currentTimeMillis();

        Log.d(TAG, "   +++++   connectivity monitor running");
        try {
            for (;;) {
                synchronized (VpnService.this) {
                    if (mState != VpnState.CONNECTED) break;
                    mNotification.update();
                    checkConnectivity();
                    VpnService.this.wait(1000); // 1 second
                }
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "connectivity monitor", e);
        }
        Log.d(TAG, "   -----   connectivity monitor stopped");
    }

    private void checkConnectivity() {
        if (mDaemonHelper.anyDaemonStopped() || isLocalIpChanged()) {
            onDisconnect();
        }
    }

    private boolean isLocalIpChanged() {
        // TODO
        if (!isDnsIntact()) {
            Log.w(TAG, "       local IP changed");
            return true;
        } else {
            return false;
        }
    }

    private boolean isDnsIntact() {
        String dns1 = SystemProperties.get(DNS1);
        if (!mVpnDns1.equals(dns1)) {
            Log.w(TAG, "   dns being overridden by: " + dns1);
            return false;
        } else {
            return true;
        }
    }

    protected void sleep(int ms) {
        try {
            Thread.currentThread().sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private class DaemonHelper {
        private List<DaemonProxy> mDaemonList =
                new ArrayList<DaemonProxy>();

        synchronized DaemonProxy startDaemon(String daemonName)
                throws IOException {
            DaemonProxy daemon = new DaemonProxy(daemonName);
            mDaemonList.add(daemon);
            daemon.start();
            return daemon;
        }

        synchronized void stopAll() {
            if (mDaemonList.isEmpty()) {
                onFinalCleanUp();
            } else {
                for (DaemonProxy s : mDaemonList) s.stop();
            }
        }

        synchronized void closeSockets() {
            for (DaemonProxy s : mDaemonList) s.closeControlSocket();
        }

        synchronized boolean anyDaemonStopped() {
            for (DaemonProxy s : mDaemonList) {
                if (s.isStopped()) {
                    Log.w(TAG, "       daemon gone: " + s.getName());
                    return true;
                }
            }
            return false;
        }

        private int getResultFromSocket(DaemonProxy s) {
            try {
                return s.getResultFromSocket();
            } catch (IOException e) {
                return -1;
            }
        }

        synchronized boolean anySocketError() {
            for (DaemonProxy s : mDaemonList) {
                switch (getResultFromSocket(s)) {
                    case 0:
                        continue;

                    case AUTH_ERROR_CODE:
                        onError(VpnManager.VPN_ERROR_AUTH);
                        return true;

                    default:
                        onError(VpnManager.VPN_ERROR_CONNECTION_FAILED);
                        return true;
                }
            }
            return false;
        }
    }

    // Helper class for showing, updating notification.
    private class NotificationHelper {
        void update() {
            String title = getNotificationTitle(true);
            Notification n = new Notification(R.drawable.vpn_connected, title,
                    mStartTime);
            n.setLatestEventInfo(mContext, title,
                    getNotificationMessage(true), prepareNotificationIntent());
            n.flags |= Notification.FLAG_NO_CLEAR;
            n.flags |= Notification.FLAG_ONGOING_EVENT;
            enableNotification(n);
        }

        void showDisconnect() {
            String title = getNotificationTitle(false);
            Notification n = new Notification(R.drawable.vpn_disconnected,
                    title, System.currentTimeMillis());
            n.setLatestEventInfo(mContext, title,
                    getNotificationMessage(false), prepareNotificationIntent());
            n.flags |= Notification.FLAG_AUTO_CANCEL;
            disableNotification();
            enableNotification(n);
        }

        void disableNotification() {
            ((NotificationManager) mContext.getSystemService(
                    Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        }

        private void enableNotification(Notification n) {
            ((NotificationManager) mContext.getSystemService(
                    Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, n);
        }

        private PendingIntent prepareNotificationIntent() {
            return PendingIntent.getActivity(mContext, 0,
                    new VpnManager(mContext).createSettingsActivityIntent(), 0);
        }

        private String getNotificationTitle(boolean connected) {
            String formatString = connected
                    ? mContext.getString(
                            R.string.vpn_notification_title_connected)
                    : mContext.getString(
                            R.string.vpn_notification_title_disconnected);
            return String.format(formatString, mProfile.getName());
        }

        private String getFormattedTime(long duration) {
            long hours = duration / 3600;
            StringBuilder sb = new StringBuilder();
            if (hours > 0) sb.append(hours).append(':');
            sb.append(String.format("%02d:%02d", (duration % 3600 / 60),
                    (duration % 60)));
            return sb.toString();
        }

        private String getNotificationMessage(boolean connected) {
            if (connected) {
                long time = (System.currentTimeMillis() - mStartTime) / 1000;
                return getFormattedTime(time);
            } else {
                return mContext.getString(
                        R.string.vpn_notification_hint_disconnected);
            }
        }
    }
}
