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
import android.net.NetworkUtils;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
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
    private static final String VPN_UP = "vpn.up";
    private static final String VPN_IS_UP = "1";
    private static final String VPN_IS_DOWN = "0";

    private static final String REMOTE_IP = "net.ipremote";
    private static final String DNS_DOMAIN_SUFFICES = "net.dns.search";

    private final String TAG = VpnService.class.getSimpleName();

    E mProfile;
    VpnServiceBinder mContext;

    private VpnState mState = VpnState.IDLE;
    private boolean mInError;

    // connection settings
    private String mOriginalDns1;
    private String mOriginalDns2;
    private String mVpnDns1 = "";
    private String mVpnDns2 = "";
    private String mOriginalDomainSuffices;
    private String mHostIp;

    private long mStartTime; // VPN connection start time

    // for helping managing multiple Android services
    private ServiceHelper mServiceHelper = new ServiceHelper();

    // for helping showing, updating notification
    private NotificationHelper mNotification = new NotificationHelper();

    /**
     * Establishes a VPN connection with the specified username and password.
     */
    protected abstract void connect(String serverIp, String username,
            String password) throws IOException;

    /**
     * Tears down the VPN connection. The base class simply terminates all the
     * Android services. A subclass may need to do some clean-up before that.
     */
    protected void disconnect() {
    }

    /**
     * Starts an Android service defined in init.rc.
     */
    protected AndroidServiceProxy startService(String serviceName)
            throws IOException {
        return mServiceHelper.startService(serviceName);
    }

    /**
     * Returns the VPN profile associated with the connection.
     */
    protected E getProfile() {
        return mProfile;
    }

    /**
     * Returns the host IP for establishing the VPN connection.
     */
    protected String getHostIp() throws IOException {
        if (mHostIp == null) mHostIp = reallyGetHostIp();
        return mHostIp;
    }

    /**
     * Returns the IP address of the specified host name.
     */
    protected String getIp(String hostName) throws IOException {
        return InetAddress.getByName(hostName).getHostAddress();
    }

    /**
     * Returns the IP address of the default gateway.
     */
    protected String getGatewayIp() throws IOException {
        Enumeration<NetworkInterface> ifces =
                NetworkInterface.getNetworkInterfaces();
        for (; ifces.hasMoreElements(); ) {
            NetworkInterface ni = ifces.nextElement();
            int gateway = NetworkUtils.getDefaultRoute(ni.getName());
            if (gateway == 0) continue;
            return toInetAddress(gateway).getHostAddress();
        }
        throw new IOException("Default gateway is not available");
    }

    /**
     * Sets the system property. The method is blocked until the value is
     * settled in.
     * @param name the name of the property
     * @param value the value of the property
     * @throws IOException if it fails to set the property within 2 seconds
     */
    protected void setSystemProperty(String name, String value)
            throws IOException {
        SystemProperties.set(name, value);
        for (int i = 0; i < 5; i++) {
            String v = SystemProperties.get(name);
            if (v.equals(value)) {
                return;
            } else {
                Log.d(TAG, "sys_prop: wait for " + name + " to settle in");
                sleep(400);
            }
        }
        throw new IOException("Failed to set system property: " + name);
    }

    void setContext(VpnServiceBinder context, E profile) {
        mContext = context;
        mProfile = profile;
    }

    VpnState getState() {
        return mState;
    }

    synchronized void onConnect(String username, String password)
            throws IOException {
        mState = VpnState.CONNECTING;
        broadcastConnectivity(VpnState.CONNECTING);

        String serverIp = getIp(getProfile().getServerName());

        onBeforeConnect();
        connect(serverIp, username, password);
        waitUntilConnectedOrTimedout();
    }

    synchronized void onDisconnect(boolean cleanUpServices) {
        try {
            mState = VpnState.DISCONNECTING;
            broadcastConnectivity(VpnState.DISCONNECTING);
            mNotification.showDisconnect();

            // subclass implementation
            if (cleanUpServices) disconnect();

            mServiceHelper.stop();
        } catch (Throwable e) {
            Log.e(TAG, "onError()", e);
            onFinalCleanUp();
        }
    }

    synchronized void onError() {
        // error may occur during or after connection setup
        // and it may be due to one or all services gone
        mInError = true;
        switch (mState) {
        case CONNECTED:
            onDisconnect(true);
            break;

        case CONNECTING:
            onDisconnect(false);
            break;
        }
    }

    private void onBeforeConnect() {
        mNotification.disableNotification();

        SystemProperties.set(VPN_DNS1, "-");
        SystemProperties.set(VPN_DNS2, "-");
        SystemProperties.set(VPN_UP, VPN_IS_DOWN);
        Log.d(TAG, "       VPN UP: " + SystemProperties.get(VPN_UP));
    }

    private void waitUntilConnectedOrTimedout() {
        sleep(2000); // 2 seconds
        for (int i = 0; i < 60; i++) {
            if (VPN_IS_UP.equals(SystemProperties.get(VPN_UP))) {
                onConnected();
                return;
            }
            sleep(500); // 0.5 second
        }

        synchronized (this) {
            if (mState == VpnState.CONNECTING) {
                Log.d(TAG, "       connecting timed out !!");
                onError();
            }
        }
    }

    private synchronized void onConnected() {
        Log.d(TAG, "onConnected()");

        saveVpnDnsProperties();
        saveAndSetDomainSuffices();
        startConnectivityMonitor();

        mState = VpnState.CONNECTED;
        broadcastConnectivity(VpnState.CONNECTED);
    }

    private synchronized void onFinalCleanUp() {
        Log.d(TAG, "onFinalCleanUp()");

        if (mState == VpnState.IDLE) return;

        // keep the notification when error occurs
        if (!mInError) mNotification.disableNotification();

        restoreOriginalDnsProperties();
        restoreOriginalDomainSuffices();
        mState = VpnState.IDLE;
        broadcastConnectivity(VpnState.IDLE);

        // stop the service itself
        mContext.stopSelf();
    }

    private synchronized void onOneServiceGone() {
        switch (mState) {
        case IDLE:
        case DISCONNECTING:
            break;

        default:
            onError();
        }
    }

    private synchronized void onAllServicesGone() {
        switch (mState) {
        case IDLE:
            break;

        case DISCONNECTING:
            // daemons are gone; now clean up everything
            onFinalCleanUp();
            break;

        default:
            onError();
        }
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
        for (int i = 0; i < 10; i++) {
            mVpnDns1 = SystemProperties.get(VPN_DNS1);
            mVpnDns2 = SystemProperties.get(VPN_DNS2);
            if (mOriginalDns1.equals(mVpnDns1)) {
                Log.d(TAG, "wait for vpn dns to settle in..." + i);
                sleep(500);
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
        Log.e(TAG, "saveVpnDnsProperties(): DNS not updated??");
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
        new VpnManager(mContext).broadcastConnectivity(mProfile.getName(), s);
    }

    private void startConnectivityMonitor() {
        mStartTime = System.currentTimeMillis();

        new Thread(new Runnable() {
            public void run() {
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
        }).start();
    }

    private void checkConnectivity() {
        checkDnsProperties();
    }

    private void checkDnsProperties() {
        String dns1 = SystemProperties.get(DNS1);
        if (!mVpnDns1.equals(dns1)) {
            Log.w(TAG, "   @@ !!!    dns being overridden");
            onError();
        }
    }

    private String reallyGetHostIp() throws IOException {
        Enumeration<NetworkInterface> ifces =
                NetworkInterface.getNetworkInterfaces();
        for (; ifces.hasMoreElements(); ) {
            NetworkInterface ni = ifces.nextElement();
            int gateway = NetworkUtils.getDefaultRoute(ni.getName());
            if (gateway == 0) continue;
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            for (; addrs.hasMoreElements(); ) {
                return addrs.nextElement().getHostAddress();
            }
        }
        throw new IOException("Host IP is not available");
    }

    protected void sleep(int ms) {
        try {
            Thread.currentThread().sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private InetAddress toInetAddress(int addr) throws IOException {
        byte[] aa = new byte[4];
        for (int i= 0; i < aa.length; i++) {
            aa[i] = (byte) (addr & 0x0FF);
            addr >>= 8;
        }
        return InetAddress.getByAddress(aa);
    }

    private class ServiceHelper implements ProcessProxy.Callback {
        private List<AndroidServiceProxy> mServiceList =
                new ArrayList<AndroidServiceProxy>();

        // starts an Android service
        AndroidServiceProxy startService(String serviceName)
                throws IOException {
            AndroidServiceProxy service = new AndroidServiceProxy(serviceName);
            mServiceList.add(service);
            service.start(this);
            return service;
        }

        // stops all the Android services
        void stop() {
            if (mServiceList.isEmpty()) {
                onFinalCleanUp();
            } else {
                for (AndroidServiceProxy s : mServiceList) s.stop();
            }
        }

        //@Override
        public void done(ProcessProxy p) {
            Log.d(TAG, "service done: " + p.getName());
            commonCallback((AndroidServiceProxy) p);
        }

        //@Override
        public void error(ProcessProxy p, Throwable e) {
            Log.e(TAG, "service error: " + p.getName(), e);
            commonCallback((AndroidServiceProxy) p);
        }

        private void commonCallback(AndroidServiceProxy service) {
            mServiceList.remove(service);
            onOneServiceGone();
            if (mServiceList.isEmpty()) onAllServicesGone();
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
