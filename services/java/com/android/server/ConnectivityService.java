/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.MobileDataStateTracker;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;
import android.net.wifi.WifiStateTracker;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Sync;
import android.util.EventLog;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub {

    private static final boolean DBG = false;
    private static final String TAG = "ConnectivityService";

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_CONNECTIVITY_STATE_CHANGED = 50020;

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];
    private WifiStateTracker mWifiStateTracker;
    private MobileDataStateTracker mMobileDataStateTracker;
    private WifiWatchdogService mWifiWatchdogService;

    private Context mContext;
    private int mNetworkPreference;
    private NetworkStateTracker mActiveNetwork;

    private int mNumDnsEntries;
    private static int sDnsChangeCounter;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private static class ConnectivityThread extends Thread {
        private Context mContext;
        
        private ConnectivityThread(Context context) {
            super("ConnectivityThread");
            mContext = context;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (this) {
                sServiceInstance = new ConnectivityService(mContext);
                notifyAll();
            }
            Looper.loop();
        }
        
        public static ConnectivityService getServiceInstance(Context context) {
            ConnectivityThread thread = new ConnectivityThread(context);
            thread.start();
            
            synchronized (thread) {
                while (sServiceInstance == null) {
                    try {
                        // Wait until sServiceInstance has been initialized.
                        thread.wait();
                    } catch (InterruptedException ignore) {
                        Log.e(TAG,
                            "Unexpected InterruptedException while waiting for ConnectivityService thread");
                    }
                }
            }
            
            return sServiceInstance;
        }
    }
    
    public static ConnectivityService getInstance(Context context) {
        return ConnectivityThread.getServiceInstance(context);
    }
    
    private ConnectivityService(Context context) {
        if (DBG) Log.v(TAG, "ConnectivityService starting up");
        mContext = context;
        mNetTrackers = new NetworkStateTracker[2];
        Handler handler = new MyHandler();
        
        mNetworkPreference = getPersistedNetworkPreference();
                
        /*
         * Create the network state trackers for Wi-Fi and mobile
         * data. Maybe this could be done with a factory class,
         * but it's not clear that it's worth it, given that
         * the number of different network types is not going
         * to change very often.
         */
        if (DBG) Log.v(TAG, "Starting Wifi Service.");
        mWifiStateTracker = new WifiStateTracker(context, handler);
        WifiService wifiService = new WifiService(context, mWifiStateTracker);
        ServiceManager.addService(Context.WIFI_SERVICE, wifiService);
        mNetTrackers[ConnectivityManager.TYPE_WIFI] = mWifiStateTracker;

        mMobileDataStateTracker = new MobileDataStateTracker(context, handler);
        mNetTrackers[ConnectivityManager.TYPE_MOBILE] = mMobileDataStateTracker;
        
        mActiveNetwork = null;
        mNumDnsEntries = 0;

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");

        for (NetworkStateTracker t : mNetTrackers)
            t.startMonitoring();

        // Constructing this starts it too
        mWifiWatchdogService = new WifiWatchdogService(context, mWifiStateTracker);
    }

    /**
     * Sets the preferred network. 
     * @param preference the new preference
     */
    public synchronized void setNetworkPreference(int preference) {
        enforceChangePermission();
        if (ConnectivityManager.isNetworkTypeValid(preference)) {
            if (mNetworkPreference != preference) {
                persistNetworkPreference(preference);
                mNetworkPreference = preference;
                enforcePreference();
            }
        }
    }

    public int getNetworkPreference() {
        enforceAccessPermission();
        return mNetworkPreference;
    }

    private void persistNetworkPreference(int networkPreference) {
        final ContentResolver cr = mContext.getContentResolver();
        Settings.Secure.putInt(cr, Settings.Secure.NETWORK_PREFERENCE, networkPreference);
    }
    
    private int getPersistedNetworkPreference() {
        final ContentResolver cr = mContext.getContentResolver();

        final int networkPrefSetting = Settings.Secure
                .getInt(cr, Settings.Secure.NETWORK_PREFERENCE, -1);
        if (networkPrefSetting != -1) {
            return networkPrefSetting;
        }

        return ConnectivityManager.DEFAULT_NETWORK_PREFERENCE;
    }
    
    /**
     * Make the state of network connectivity conform to the preference settings.
     * In this method, we only tear down a non-preferred network. Establishing
     * a connection to the preferred network is taken care of when we handle
     * the disconnect event from the non-preferred network
     * (see {@link #handleDisconnect(NetworkInfo)}).
     */
    private void enforcePreference() {
        if (mActiveNetwork == null)
            return;

        for (NetworkStateTracker t : mNetTrackers) {
            if (t == mActiveNetwork) {
                int netType = t.getNetworkInfo().getType();
                int otherNetType = ((netType == ConnectivityManager.TYPE_WIFI) ?
                        ConnectivityManager.TYPE_MOBILE :
                        ConnectivityManager.TYPE_WIFI);

                if (t.getNetworkInfo().getType() != mNetworkPreference) {
                    NetworkStateTracker otherTracker = mNetTrackers[otherNetType];
                    if (otherTracker.isAvailable()) {
                        teardown(t);
                    }
                }
            }
        }
    }

    private boolean teardown(NetworkStateTracker netTracker) {
        if (netTracker.teardown()) {
            netTracker.setTeardownRequested(true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Return NetworkInfo for the active (i.e., connected) network interface.
     * It is assumed that at most one network is active at a time. If more
     * than one is active, it is indeterminate which will be returned.
     * @return the info for the active network, or {@code null} if none is active
     */
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        for (NetworkStateTracker t : mNetTrackers) {
            NetworkInfo info = t.getNetworkInfo();
            if (info.isConnected()) {
                return info;
            }
        }
        return null;
    }

    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        if (ConnectivityManager.isNetworkTypeValid(networkType)) {
            NetworkStateTracker t = mNetTrackers[networkType];
            if (t != null)
                return t.getNetworkInfo();
        }
        return null;
    }

    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        NetworkInfo[] result = new NetworkInfo[mNetTrackers.length];
        int i = 0;
        for (NetworkStateTracker t : mNetTrackers) {
            result[i++] = t.getNetworkInfo();
        }
        return result;
    }

    public boolean setRadios(boolean turnOn) {
        boolean result = true;
        enforceChangePermission();
        for (NetworkStateTracker t : mNetTrackers) {
            result = t.setRadio(turnOn) && result;
        }
        return result;
    }

    public boolean setRadio(int netType, boolean turnOn) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(netType)) {
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[netType];
        return tracker != null && tracker.setRadio(turnOn);
    }

    public int startUsingNetworkFeature(int networkType, String feature) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return -1;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];
        if (tracker != null) {
            return tracker.startUsingNetworkFeature(feature, getCallingPid(), getCallingUid());
        }
        return -1;
    }

    public int stopUsingNetworkFeature(int networkType, String feature) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return -1;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];
        if (tracker != null) {
            return tracker.stopUsingNetworkFeature(feature, getCallingPid(), getCallingUid());
        }
        return -1;
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the specified
     * host is to be routed
     * @param hostAddress the IP address of the host to which the route is desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];
        /*
         * If there's only one connected network, and it's the one requested,
         * then we don't have to do anything - the requested route already
         * exists. If it's not the requested network, then it's not possible
         * to establish the requested route. Finally, if there is more than
         * one connected network, then we must insert an entry in the routing
         * table.
         */
        if (getNumConnectedNetworks() > 1) {
            return tracker.requestRouteToHost(hostAddress);
        } else {
            return tracker.getNetworkInfo().getType() == networkType;
        }
    }

    /**
     * @see ConnectivityManager#getBackgroundDataSetting()
     */
    public boolean getBackgroundDataSetting() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.BACKGROUND_DATA, 1) == 1;
    }
    
    /**
     * @see ConnectivityManager#setBackgroundDataSetting(boolean)
     */
    public void setBackgroundDataSetting(boolean allowBackgroundDataUsage) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_BACKGROUND_DATA_SETTING,
                "ConnectivityService");
        
        if (getBackgroundDataSetting() == allowBackgroundDataUsage) return;

        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.BACKGROUND_DATA, allowBackgroundDataUsage ? 1 : 0);
        
        Intent broadcast = new Intent(
                ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);
        mContext.sendBroadcast(broadcast);
    }    
    
    private int getNumConnectedNetworks() {
        int numConnectedNets = 0;

        for (NetworkStateTracker nt : mNetTrackers) {
            if (nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
                ++numConnectedNets;
            }
        }
        return numConnectedNets;
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE,
                                          "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_NETWORK_STATE,
                                          "ConnectivityService");

    }

    /**
     * Handle a {@code DISCONNECTED} event. If this pertains to the non-active network,
     * we ignore it. If it is for the active network, we send out a broadcast.
     * But first, we check whether it might be possible to connect to a different
     * network.
     * @param info the {@code NetworkInfo} for the network
     */
    private void handleDisconnect(NetworkInfo info) {

        if (DBG) Log.v(TAG, "Handle DISCONNECT for " + info.getTypeName());

        mNetTrackers[info.getType()].setTeardownRequested(false);
        /*
         * If the disconnected network is not the active one, then don't report
         * this as a loss of connectivity. What probably happened is that we're
         * getting the disconnect for a network that we explicitly disabled
         * in accordance with network preference policies.
         */
        if (mActiveNetwork == null ||  info.getType() != mActiveNetwork.getNetworkInfo().getType())
            return;

        NetworkStateTracker newNet;
        if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
            newNet = mWifiStateTracker;
        } else /* info().getType() == TYPE_WIFI */ {
            newNet = mMobileDataStateTracker;
        }

        /**
         * See if the other network is available to fail over to.
         * If is not available, we enable it anyway, so that it
         * will be able to connect when it does become available,
         * but we report a total loss of connectivity rather than
         * report that we are attempting to fail over.
         */
        NetworkInfo switchTo = null;
        if (newNet.isAvailable()) {
            mActiveNetwork = newNet;
            switchTo = newNet.getNetworkInfo();
            switchTo.setFailover(true);
            if (!switchTo.isConnectedOrConnecting()) {
                newNet.reconnect();
            }
        } else {
            newNet.reconnect();
        }

        boolean otherNetworkConnected = false;
        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, info.getExtraInfo());
        }
        if (switchTo != null) {
            otherNetworkConnected = switchTo.isConnected();
            if (DBG) {
                if (otherNetworkConnected) {
                    Log.v(TAG, "Switching to already connected " + switchTo.getTypeName());
                } else {
                    Log.v(TAG, "Attempting to switch to " + switchTo.getTypeName());
                }
            }
            intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
        } else {
            intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
        }
        if (DBG) Log.v(TAG, "Sending DISCONNECT bcast for " + info.getTypeName() +
                (switchTo == null ? "" : " other=" + switchTo.getTypeName()));

        mContext.sendStickyBroadcast(intent);
        /*
         * If the failover network is already connected, then immediately send out
         * a followup broadcast indicating successful failover
         */
        if (switchTo != null && otherNetworkConnected)
            sendConnectedBroadcast(switchTo);
    }

    private void sendConnectedBroadcast(NetworkInfo info) {
        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, info.getExtraInfo());
        }
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Called when an attempt to fail over to another network has failed.
     * @param info the {@link NetworkInfo} for the failed network
     */
    private void handleConnectionFailure(NetworkInfo info) {
        mNetTrackers[info.getType()].setTeardownRequested(false);
        if (getActiveNetworkInfo() == null) {
            String reason = info.getReason();
            String extraInfo = info.getExtraInfo();

            if (DBG) {
                String reasonText;
                if (reason == null) {
                    reasonText = ".";
                } else {
                    reasonText = " (" + reason + ").";
                }
                Log.v(TAG, "Attempt to connect to " + info.getTypeName() + " failed" + reasonText);
            }
            
            Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
            intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            if (reason != null) {
                intent.putExtra(ConnectivityManager.EXTRA_REASON, reason);
            }
            if (extraInfo != null) {
                intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, extraInfo);
            }
            if (info.isFailover()) {
                intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                info.setFailover(false);
            }
            mContext.sendStickyBroadcast(intent);
        }
    }

    private void handleConnect(NetworkInfo info) {
        if (DBG) Log.v(TAG, "Handle CONNECT for " + info.getTypeName());

        // snapshot isFailover, because sendConnectedBroadcast() resets it
        boolean isFailover = info.isFailover();
        NetworkStateTracker thisNet = mNetTrackers[info.getType()];
        NetworkStateTracker deadnet = null;
        NetworkStateTracker otherNet;
        if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
            otherNet = mWifiStateTracker;
        } else /* info().getType() == TYPE_WIFI */ {
            otherNet = mMobileDataStateTracker;
        }
        /*
         * Check policy to see whether we are connected to a non-preferred
         * network that now needs to be torn down.
         */
        NetworkInfo wifiInfo = mWifiStateTracker.getNetworkInfo();
        NetworkInfo mobileInfo = mMobileDataStateTracker.getNetworkInfo();
        if (wifiInfo.isConnected() && mobileInfo.isConnected()) {
            if (mNetworkPreference == ConnectivityManager.TYPE_WIFI)
                deadnet = mMobileDataStateTracker;
            else
                deadnet = mWifiStateTracker;
        }

        boolean toredown = false;
        thisNet.setTeardownRequested(false);
        if (!mTestMode && deadnet != null) {
            if (DBG) Log.v(TAG, "Policy requires " +
                  deadnet.getNetworkInfo().getTypeName() + " teardown");
            toredown = teardown(deadnet);
            if (DBG && !toredown) {
                Log.d(TAG, "Network declined teardown request");
            }
        }

        /*
         * Note that if toredown is true, deadnet cannot be null, so there is
         * no danger of a null pointer exception here..
         */
        if (!toredown || deadnet.getNetworkInfo().getType() != info.getType()) {
            mActiveNetwork = thisNet;
            if (DBG) Log.v(TAG, "Sending CONNECT bcast for " + info.getTypeName());
            thisNet.updateNetworkSettings();
            sendConnectedBroadcast(info);
            if (isFailover) {
                otherNet.releaseWakeLock();
            }
        } else {
            if (DBG) Log.v(TAG, "Not broadcasting CONNECT_ACTION to torn down network " +
                info.getTypeName());
        }
    }

    private void handleScanResultsAvailable(NetworkInfo info) {
        int networkType = info.getType();
        if (networkType != ConnectivityManager.TYPE_WIFI) {
            if (DBG) Log.v(TAG, "Got ScanResultsAvailable for " + info.getTypeName() + " network."
                + " Don't know how to handle.");
        }
        
        mNetTrackers[networkType].interpretScanResultsAvailable();
    }

    private void handleNotificationChange(boolean visible, int id, Notification notification) {
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (visible) {
            notificationManager.notify(id, notification);
        } else {
            notificationManager.cancel(id);
        }
    }

    /**
     * After any kind of change in the connectivity state of any network,
     * make sure that anything that depends on the connectivity state of
     * more than one network is set up correctly. We're mainly concerned
     * with making sure that the list of DNS servers is set up  according
     * to which networks are connected, and ensuring that the right routing
     * table entries exist.
     */
    private void handleConnectivityChange() {
        /*
         * If both mobile and wifi are enabled, add the host routes that
         * will allow MMS traffic to pass on the mobile network. But
         * remove the default route for the mobile network, so that there
         * will be only one default route, to ensure that all traffic
         * except MMS will travel via Wi-Fi.
         */
        int numConnectedNets = handleConfigurationChange();
        if (numConnectedNets > 1) {
            mMobileDataStateTracker.addPrivateRoutes();
            mMobileDataStateTracker.removeDefaultRoute();
        } else if (mMobileDataStateTracker.getNetworkInfo().isConnected()) {
            mMobileDataStateTracker.removePrivateRoutes();
            mMobileDataStateTracker.restoreDefaultRoute();
        }
    }

    private int handleConfigurationChange() {
        /*
         * Set DNS properties. Always put Wi-Fi entries at the front of
         * the list if it is active.
         */
        int index = 1;
        String lastDns = "";
        int numConnectedNets = 0;
        int incrValue = ConnectivityManager.TYPE_MOBILE - ConnectivityManager.TYPE_WIFI;
        int stopValue = ConnectivityManager.TYPE_MOBILE + incrValue;

        for (int netType = ConnectivityManager.TYPE_WIFI; netType != stopValue; netType += incrValue) {
            NetworkStateTracker nt = mNetTrackers[netType];
            if (nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
                ++numConnectedNets;
                String[] dnsList = nt.getNameServers();
                for (int i = 0; i < dnsList.length && dnsList[i] != null; i++) {
                    // skip duplicate entries
                    if (!dnsList[i].equals(lastDns)) {
                        SystemProperties.set("net.dns" + index++, dnsList[i]);
                        lastDns = dnsList[i];
                    }
                }
            }
        }
        // Null out any DNS properties that are no longer used
        for (int i = index; i <= mNumDnsEntries; i++) {
            SystemProperties.set("net.dns" + i, "");
        }
        mNumDnsEntries = index - 1;
        // Notify the name resolver library of the change
        SystemProperties.set("net.dnschange", String.valueOf(sDnsChangeCounter++));
        return numConnectedNets;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        if (mActiveNetwork == null) {
            pw.println("No active network");
        } else {
            pw.println("Active network: " + mActiveNetwork.getNetworkInfo().getTypeName());
        }
        pw.println();
        for (NetworkStateTracker nst : mNetTrackers) {
            pw.println(nst.getNetworkInfo());
            pw.println(nst);
            pw.println();
        }
    }

    private class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
                case NetworkStateTracker.EVENT_STATE_CHANGED:
                    info = (NetworkInfo) msg.obj;
                    if (DBG) Log.v(TAG, "ConnectivityChange for " + info.getTypeName() + ": " +
                            info.getState() + "/" + info.getDetailedState());

                    // Connectivity state changed:
                    // [31-13] Reserved for future use
                    // [12-9] Network subtype (for mobile network, as defined by TelephonyManager)
                    // [8-3] Detailed state ordinal (as defined by NetworkInfo.DetailedState)
                    // [2-0] Network type (as defined by ConnectivityManager)
                    int eventLogParam = (info.getType() & 0x7) |
                            ((info.getDetailedState().ordinal() & 0x3f) << 3) |
                            (info.getSubtype() << 9);
                    EventLog.writeEvent(EVENTLOG_CONNECTIVITY_STATE_CHANGED, eventLogParam);
                    
                    if (info.getDetailedState() == NetworkInfo.DetailedState.FAILED) {
                        handleConnectionFailure(info);
                    } else if (info.getState() == NetworkInfo.State.DISCONNECTED) {
                        handleDisconnect(info);
                    } else if (info.getState() == NetworkInfo.State.SUSPENDED) {
                        // TODO: need to think this over.
                        // the logic here is, handle SUSPENDED the same as DISCONNECTED. The
                        // only difference being we are broadcasting an intent with NetworkInfo
                        // that's suspended. This allows the applications an opportunity to
                        // handle DISCONNECTED and SUSPENDED differently, or not.
                        handleDisconnect(info);
                    } else if (info.getState() == NetworkInfo.State.CONNECTED) {
                        handleConnect(info);
                    }
                    handleConnectivityChange();
                    break;

                case NetworkStateTracker.EVENT_SCAN_RESULTS_AVAILABLE:
                    info = (NetworkInfo) msg.obj;
                    handleScanResultsAvailable(info);
                    break;
                    
                case NetworkStateTracker.EVENT_NOTIFICATION_CHANGED:
                    handleNotificationChange(msg.arg1 == 1, msg.arg2, (Notification) msg.obj);

                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED:
                    handleConfigurationChange();
                    break;

                case NetworkStateTracker.EVENT_ROAMING_CHANGED:
                    // fill me in
                    break;

                case NetworkStateTracker.EVENT_NETWORK_SUBTYPE_CHANGED:
                    // fill me in
                    break;
            }
        }
    }
}
