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
import java.io.Serializable;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

/**
 * The service base class for managing a type of VPN connection.
 */
abstract class VpnService<E extends VpnProfile> implements Serializable {
    static final long serialVersionUID = 1L;
    private static final boolean DBG = true;
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

    private final String TAG = VpnService.class.getSimpleName();

    // FIXME: profile is only needed in connecting phase, so we can just save
    // the profile name and service class name for recovery
    E mProfile;
    transient VpnServiceBinder mContext;

    private VpnState mState = VpnState.IDLE;
    private Throwable mError;

    // connection settings
    private String mOriginalDns1;
    private String mOriginalDns2;
    private String mOriginalDomainSuffices;
    private String mLocalIp;
    private String mLocalIf;

    private long mStartTime; // VPN connection start time

    // for helping managing daemons
    private VpnDaemons mDaemons = new VpnDaemons();

    // for helping showing, updating notification
    private transient NotificationHelper mNotification;

    /**
     * Establishes a VPN connection with the specified username and password.
     */
    protected abstract void connect(String serverIp, String username,
            String password) throws IOException;

    /**
     * Returns the daemons management class for this service object.
     */
    protected VpnDaemons getDaemons() {
        return mDaemons;
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
        mProfile = profile;
        recover(context);
    }

    void recover(VpnServiceBinder context) {
        mContext = context;
        mNotification = new NotificationHelper();

        if (VpnState.CONNECTED.equals(mState)) {
            Log.i("VpnService", "     recovered: " + mProfile.getName());
            startConnectivityMonitor();
        }
    }

    VpnState getState() {
        return mState;
    }

    synchronized boolean onConnect(String username, String password) {
        try {
            setState(VpnState.CONNECTING);

            mDaemons.stopAll();
            String serverIp = getIp(getProfile().getServerName());
            saveLocalIpAndInterface(serverIp);
            onBeforeConnect();
            connect(serverIp, username, password);
            waitUntilConnectedOrTimedout();
            return true;
        } catch (Throwable e) {
            onError(e);
            return false;
        }
    }

    synchronized void onDisconnect() {
        try {
            Log.i(TAG, "disconnecting VPN...");
            setState(VpnState.DISCONNECTING);
            mNotification.showDisconnect();

            mDaemons.stopAll();
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
        Log.e(TAG, "onError()", error);
        mError = error;
        onDisconnect();
    }

    private void onError(int errorCode) {
        onError(new VpnConnectingError(errorCode));
    }


    private void onBeforeConnect() throws IOException {
        mNotification.disableNotification();

        SystemProperties.set(VPN_DNS1, "");
        SystemProperties.set(VPN_DNS2, "");
        SystemProperties.set(VPN_STATUS, VPN_IS_DOWN);
        if (DBG) {
            Log.d(TAG, "       VPN UP: " + SystemProperties.get(VPN_STATUS));
        }
    }

    private void waitUntilConnectedOrTimedout() throws IOException {
        sleep(2000); // 2 seconds
        for (int i = 0; i < 80; i++) {
            if (mState != VpnState.CONNECTING) {
                break;
            } else if (VPN_IS_UP.equals(
                    SystemProperties.get(VPN_STATUS))) {
                onConnected();
                return;
            } else {
                int err = mDaemons.getSocketError();
                if (err != 0) {
                    onError(err);
                    return;
                }
            }
            sleep(500); // 0.5 second
        }

        if (mState == VpnState.CONNECTING) {
            onError(new IOException("Connecting timed out"));
        }
    }

    private synchronized void onConnected() throws IOException {
        if (DBG) Log.d(TAG, "onConnected()");

        mDaemons.closeSockets();
        saveOriginalDns();
        saveAndSetDomainSuffices();

        mStartTime = System.currentTimeMillis();

        // Correct order to make sure VpnService doesn't break when killed:
        // (1) set state to CONNECTED
        // (2) save states
        // (3) set DNS
        setState(VpnState.CONNECTED);
        saveSelf();
        setVpnDns();

        startConnectivityMonitor();
    }

    private void saveSelf() throws IOException {
        mContext.saveStates();
    }

    private synchronized void onFinalCleanUp() {
        if (DBG) Log.d(TAG, "onFinalCleanUp()");

        if (mState == VpnState.IDLE) return;

        // keep the notification when error occurs
        if (!anyError()) mNotification.disableNotification();

        restoreOriginalDns();
        restoreOriginalDomainSuffices();
        setState(VpnState.IDLE);

        // stop the service itself
        SystemProperties.set(VPN_STATUS, VPN_IS_DOWN);
        mContext.removeStates();
        mContext.stopSelf();
    }

    private boolean anyError() {
        return (mError != null);
    }

    private void restoreOriginalDns() {
        // restore only if they are not overridden
        String vpnDns1 = SystemProperties.get(VPN_DNS1);
        if (vpnDns1.equals(SystemProperties.get(DNS1))) {
            Log.i(TAG, String.format("restore original dns prop: %s --> %s",
                    SystemProperties.get(DNS1), mOriginalDns1));
            Log.i(TAG, String.format("restore original dns prop: %s --> %s",
                    SystemProperties.get(DNS2), mOriginalDns2));
            SystemProperties.set(DNS1, mOriginalDns1);
            SystemProperties.set(DNS2, mOriginalDns2);
        }
    }

    private void saveOriginalDns() {
        mOriginalDns1 = SystemProperties.get(DNS1);
        mOriginalDns2 = SystemProperties.get(DNS2);
        Log.i(TAG, String.format("save original dns prop: %s, %s",
                mOriginalDns1, mOriginalDns2));
    }

    private void setVpnDns() {
        String vpnDns1 = SystemProperties.get(VPN_DNS1);
        String vpnDns2 = SystemProperties.get(VPN_DNS2);
        SystemProperties.set(DNS1, vpnDns1);
        SystemProperties.set(DNS2, vpnDns2);
        Log.i(TAG, String.format("set vpn dns prop: %s, %s",
                vpnDns1, vpnDns2));
    }

    private void saveAndSetDomainSuffices() {
        mOriginalDomainSuffices = SystemProperties.get(DNS_DOMAIN_SUFFICES);
        Log.i(TAG, "save original suffices: " + mOriginalDomainSuffices);
        String list = mProfile.getDomainSuffices();
        if (!TextUtils.isEmpty(list)) {
            SystemProperties.set(DNS_DOMAIN_SUFFICES, list);
        }
    }

    private void restoreOriginalDomainSuffices() {
        Log.i(TAG, "restore original suffices --> " + mOriginalDomainSuffices);
        SystemProperties.set(DNS_DOMAIN_SUFFICES, mOriginalDomainSuffices);
    }

    private void setState(VpnState newState) {
        mState = newState;
        broadcastConnectivity(newState);
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
            } else if (VPN_IS_UP.equals(SystemProperties.get(VPN_STATUS))) {
                m.broadcastConnectivity(mProfile.getName(), s,
                        VpnManager.VPN_ERROR_CONNECTION_LOST);
            } else {
                m.broadcastConnectivity(mProfile.getName(), s,
                        VpnManager.VPN_ERROR_CONNECTION_FAILED);
            }
        } else {
            m.broadcastConnectivity(mProfile.getName(), s);
        }
    }

    private void startConnectivityMonitor() {
        new Thread(new Runnable() {
            public void run() {
                Log.i(TAG, "VPN connectivity monitor running");
                try {
                    for (int i = 10; ; i--) {
                        long now = System.currentTimeMillis();

                        boolean heavyCheck = i == 0;
                        synchronized (VpnService.this) {
                            if (mState != VpnState.CONNECTED) break;
                            mNotification.update(now);

                            if (heavyCheck) {
                                i = 10;
                                if (checkConnectivity()) checkDns();
                            }
                            long t = 1000L - System.currentTimeMillis() + now;
                            if (t > 100L) VpnService.this.wait(t);
                        }
                    }
                } catch (InterruptedException e) {
                    onError(e);
                }
                Log.i(TAG, "VPN connectivity monitor stopped");
            }
        }).start();
    }

    private void saveLocalIpAndInterface(String serverIp) throws IOException {
        DatagramSocket s = new DatagramSocket();
        int port = 80; // arbitrary
        s.connect(InetAddress.getByName(serverIp), port);
        InetAddress localIp = s.getLocalAddress();
        mLocalIp = localIp.getHostAddress();
        NetworkInterface localIf = NetworkInterface.getByInetAddress(localIp);
        mLocalIf = (localIf == null) ? null : localIf.getName();
        if (TextUtils.isEmpty(mLocalIf)) {
            throw new IOException("Local interface is empty!");
        }
        if (DBG) {
            Log.d(TAG, "  Local IP: " + mLocalIp + ", if: " + mLocalIf);
        }
    }

    // returns false if vpn connectivity is broken
    private boolean checkConnectivity() {
        if (mDaemons.anyDaemonStopped() || isLocalIpChanged()) {
            onError(new IOException("Connectivity lost"));
            return false;
        } else {
            return true;
        }
    }

    private void checkDns() {
        String dns1 = SystemProperties.get(DNS1);
        String vpnDns1 = SystemProperties.get(VPN_DNS1);
        if (!dns1.equals(vpnDns1) && dns1.equals(mOriginalDns1)) {
            // dhcp expires?
            setVpnDns();
        }
    }

    private boolean isLocalIpChanged() {
        try {
            InetAddress localIp = InetAddress.getByName(mLocalIp);
            NetworkInterface localIf =
                    NetworkInterface.getByInetAddress(localIp);
            if (localIf == null || !mLocalIf.equals(localIf.getName())) {
                Log.w(TAG, "       local If changed from " + mLocalIf
                        + " to " + localIf);
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            Log.w(TAG, "isLocalIpChanged()", e);
            return true;
        }
    }

    protected void sleep(int ms) {
        try {
            Thread.currentThread().sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private class DaemonHelper implements Serializable {
    }

    // Helper class for showing, updating notification.
    private class NotificationHelper {
        void update(long now) {
            String title = getNotificationTitle(true);
            Notification n = new Notification(R.drawable.vpn_connected, title,
                    mStartTime);
            n.setLatestEventInfo(mContext, title,
                    getConnectedNotificationMessage(now),
                    prepareNotificationIntent());
            n.flags |= Notification.FLAG_NO_CLEAR;
            n.flags |= Notification.FLAG_ONGOING_EVENT;
            enableNotification(n);
        }

        void showDisconnect() {
            String title = getNotificationTitle(false);
            Notification n = new Notification(R.drawable.vpn_disconnected,
                    title, System.currentTimeMillis());
            n.setLatestEventInfo(mContext, title,
                    getDisconnectedNotificationMessage(),
                    prepareNotificationIntent());
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

        private String getFormattedTime(int duration) {
            int hours = duration / 3600;
            StringBuilder sb = new StringBuilder();
            if (hours > 0) sb.append(hours).append(':');
            sb.append(String.format("%02d:%02d", (duration % 3600 / 60),
                    (duration % 60)));
            return sb.toString();
        }

        private String getConnectedNotificationMessage(long now) {
            return getFormattedTime((int) (now - mStartTime) / 1000);
        }

        private String getDisconnectedNotificationMessage() {
            return mContext.getString(
                    R.string.vpn_notification_hint_disconnected);
        }
    }
}
