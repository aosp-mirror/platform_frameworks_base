/*
 * Copyright (C) 2007, The Android Open Source Project
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
import android.net.vpn.SingleServerProfile;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnState;
import android.os.FileObserver;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * The service base class for managing a type of VPN connection.
 */
abstract class VpnService<E extends SingleServerProfile> {
    private static final int NOTIFICATION_ID = 1;
    private static final String PROFILES_ROOT = VpnManager.PROFILES_PATH + "/";
    public static final String DEFAULT_CONFIG_PATH = "/etc";

    private static final int DNS_TIMEOUT = 3000; // ms
    private static final String DNS1 = "net.dns1";
    private static final String DNS2 = "net.dns2";
    private static final String REMOTE_IP = "net.ipremote";
    private static final String DNS_DOMAIN_SUFFICES = "net.dns.search";
    private static final String SERVER_IP = "net.vpn.server_ip";

    private static final int VPN_TIMEOUT = 30000; // milliseconds
    private static final int ONE_SECOND = 1000; // milliseconds
    private static final int FIVE_SECOND = 5000; // milliseconds

    private static final String LOGWRAPPER = "/system/bin/logwrapper";
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

    // monitors if the VPN connection is sucessfully established
    private FileMonitor mConnectMonitor;

    // watch dog timer; fired up if the connection cannot be established within
    // VPN_TIMEOUT
    private Object mWatchdog;

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

    protected String getPppOptionFilePath() throws IOException {
        String subpath = getProfileSubpath("/ppp/peers");
        String[] kids = new File(subpath).list();
        if ((kids == null) || (kids.length == 0)) {
            throw new IOException("no option file found in " + subpath);
        }
        if (kids.length > 1) {
            Log.w(TAG, "more than one option file found in " + subpath
                    + ", arbitrarily choose " + kids[0]);
        }
        return subpath + "/" + kids[0];
    }

    /**
     * Returns the VPN profile associated with the connection.
     */
    protected E getProfile() {
        return mProfile;
    }

    /**
     * Returns the profile path where configuration files reside.
     */
    protected String getProfilePath() throws IOException {
        String path = PROFILES_ROOT + mProfile.getId();
        File dir = new File(path);
        if (!dir.exists()) throw new IOException("Profile dir does not exist");
        return path;
    }

    /**
     * Returns the path where default configuration files reside.
     */
    protected String getDefaultConfigPath() throws IOException {
        return DEFAULT_CONFIG_PATH;
    }

    /**
     * Returns the host IP for establishing the VPN connection.
     */
    protected String getHostIp() throws IOException {
        if (mHostIp == null) mHostIp = reallyGetHostIp();
        return mHostIp;
    }

    /**
     * Returns the IP of the specified host name.
     */
    protected String getIp(String hostName) throws IOException {
        InetAddress iaddr = InetAddress.getByName(hostName);
        byte[] aa = iaddr.getAddress();
        StringBuilder sb = new StringBuilder().append(byteToInt(aa[0]));
        for (int i = 1; i < aa.length; i++) {
            sb.append(".").append(byteToInt(aa[i]));
        }
        return sb.toString();
    }

    /**
     * Returns the path of the script file that is executed when the VPN
     * connection is established.
     */
    protected String getConnectMonitorFile() {
        return "/etc/ppp/ip-up";
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
        setSystemProperty(SERVER_IP, serverIp);
        onBeforeConnect();

        connect(serverIp, username, password);
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

    private void createConnectMonitor() {
        mConnectMonitor = new FileMonitor(getConnectMonitorFile(),
                new Runnable() {
                    public void run() {
                        onConnectMonitorTriggered();
                    }
                });
    }

    private void onBeforeConnect() {
        mNotification.disableNotification();

        createConnectMonitor();
        mConnectMonitor.startWatching();
        saveOriginalDnsProperties();

        mWatchdog = startTimer(VPN_TIMEOUT, new Runnable() {
            public void run() {
                synchronized (VpnService.this) {
                    if (mState == VpnState.CONNECTING) {
                        Log.d(TAG, "       watchdog timer is fired !!");
                        onError();
                    }
                }
            }
        });
    }

    private synchronized void onConnectMonitorTriggered() {
        Log.d(TAG, "onConnectMonitorTriggered()");

        stopTimer(mWatchdog);
        mConnectMonitor.stopWatching();
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
        if (mConnectMonitor != null) mConnectMonitor.stopWatching();
        if (mWatchdog != null) stopTimer(mWatchdog);
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

    private void saveOriginalDnsProperties() {
        mOriginalDns1 = SystemProperties.get(DNS1);
        mOriginalDns2 = SystemProperties.get(DNS2);
        Log.d(TAG, String.format("save original dns prop: %s, %s",
                mOriginalDns1, mOriginalDns2));
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
        mVpnDns1 = mVpnDns2 = "";
        for (int i = 0; i < 10; i++) {
            mVpnDns1 = SystemProperties.get(DNS1);
            mVpnDns2 = SystemProperties.get(DNS2);
            if (mVpnDns1.equals(mOriginalDns1)) {
                Log.d(TAG, "wait for vpn dns to settle in..." + i);
                sleep(500);
            } else {
                Log.d(TAG, String.format("save vpn dns prop: %s, %s",
                        mVpnDns1, mVpnDns2));
                return;
            }
        }
        Log.e(TAG, "saveVpnDnsProperties(): DNS not updated??");
    }

    private void restoreVpnDnsProperties() {
        if (isNullOrEmpty(mVpnDns1) && isNullOrEmpty(mVpnDns2)) {
            return;
        }
        Log.d(TAG, String.format("restore vpn dns prop: %s --> %s",
                SystemProperties.get(DNS1), mVpnDns1));
        Log.d(TAG, String.format("restore vpn dns prop: %s --> %s",
                SystemProperties.get(DNS2), mVpnDns2));
        SystemProperties.set(DNS1, mVpnDns1);
        SystemProperties.set(DNS2, mVpnDns2);
    }

    private void saveAndSetDomainSuffices() {
        mOriginalDomainSuffices = SystemProperties.get(DNS_DOMAIN_SUFFICES);
        Log.d(TAG, "save original dns search: " + mOriginalDomainSuffices);
        String list = mProfile.getDomainSuffices();
        if (!isNullOrEmpty(list)) {
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
                            VpnService.this.wait(ONE_SECOND);
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

    private Object startTimer(final int milliseconds, final Runnable task) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                Log.d(TAG, "watchdog timer started");
                Thread t = Thread.currentThread();
                try {
                    synchronized (t) {
                        t.wait(milliseconds);
                    }
                    task.run();
                } catch (InterruptedException e) {
                    // ignored
                }
                Log.d(TAG, "watchdog timer stopped");
            }
        });
        thread.start();
        return thread;
    }

    private void stopTimer(Object timer) {
        synchronized (timer) {
            timer.notify();
        }
    }

    private String reallyGetHostIp() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("www.google.com", 80), DNS_TIMEOUT);
        String ipAddress = s.getLocalAddress().getHostAddress();
        Log.d(TAG, "Host IP: " + ipAddress);
        s.close();
        return ipAddress;
    }

    private String getProfileSubpath(String subpath) throws IOException {
        String path = getProfilePath() + subpath;
        if (new File(path).exists()) {
            return path;
        } else {
            Log.w(TAG, "Profile subpath does not exist: " + path
                    + ", use default one");
            String path2 = getDefaultConfigPath() + subpath;
            if (!new File(path2).exists()) {
                throw new IOException("Profile subpath does not exist at "
                        + path + " or " + path2);
            }
            return path2;
        }
    }

    private void sleep(int ms) {
        try {
            Thread.currentThread().sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private static boolean isNullOrEmpty(String message) {
        return ((message == null) || (message.length() == 0));
    }

    private static int byteToInt(byte b) {
        return ((int) b) & 0x0FF;
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

    private class FileMonitor extends FileObserver {
        private Runnable mCallback;

        FileMonitor(String path, Runnable callback) {
            super(path, CLOSE_NOWRITE);
            mCallback = callback;
        }

        @Override
        public void onEvent(int event, String path) {
            if ((event & CLOSE_NOWRITE) > 0) mCallback.run();
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
            String connectedOrNot = connected
                    ? mContext.getString(R.string.vpn_notification_connected)
                    : mContext.getString(
                            R.string.vpn_notification_disconnected);
            return String.format(
                    mContext.getString(R.string.vpn_notification_title),
                    mProfile.getName(), connectedOrNot);
        }

        private String getTimeFormat(long duration) {
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
                return String.format(mContext.getString(
                        R.string.vpn_notification_connected_message),
                        getTimeFormat(time));
            } else {
                return "";
            }
        }
    }
}
