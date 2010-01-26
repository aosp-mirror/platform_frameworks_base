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
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.Phone;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub {

    private static final boolean DBG = true;
    private static final String TAG = "ConnectivityService";

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_CONNECTIVITY_STATE_CHANGED = 50020;

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];

    /**
     * A per Net list of the PID's that requested access to the net
     * used both as a refcount and for per-PID DNS selection
     */
    private List mNetRequestersPids[];

    private WifiWatchdogService mWifiWatchdogService;

    // priority order of the nettrackers
    // (excluding dynamically set mNetworkPreference)
    // TODO - move mNetworkTypePreference into this
    private int[] mPriorityList;

    private Context mContext;
    private int mNetworkPreference;
    private int mActiveDefaultNetwork = -1;

    private int mNumDnsEntries;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private Handler mHandler;

    // list of DeathRecipients used to make sure features are turned off when
    // a process dies
    private List mFeatureUsers;

    private boolean mSystemReady;
    private ArrayList<Intent> mDeferredBroadcasts;

    private class NetworkAttributes {
        /**
         * Class for holding settings read from resources.
         */
        public String mName;
        public int mType;
        public int mRadio;
        public int mPriority;
        public NetworkInfo.State mLastState;
        public NetworkAttributes(String init) {
            String fragments[] = init.split(",");
            mName = fragments[0].toLowerCase();
            if (fragments[1].toLowerCase().equals("wifi")) {
                mRadio = ConnectivityManager.TYPE_WIFI;
            } else {
                mRadio = ConnectivityManager.TYPE_MOBILE;
            }
            if (mName.equals("default")) {
                mType = mRadio;
            } else if (mName.equals("mms")) {
                mType = ConnectivityManager.TYPE_MOBILE_MMS;
            } else if (mName.equals("supl")) {
                mType = ConnectivityManager.TYPE_MOBILE_SUPL;
            } else if (mName.equals("dun")) {
                mType = ConnectivityManager.TYPE_MOBILE_DUN;
            } else if (mName.equals("hipri")) {
                mType = ConnectivityManager.TYPE_MOBILE_HIPRI;
            }
            mPriority = Integer.parseInt(fragments[2]);
            mLastState = NetworkInfo.State.UNKNOWN;
        }
        public boolean isDefault() {
            return (mType == mRadio);
        }
    }
    NetworkAttributes[] mNetAttributes;

    private class RadioAttributes {
        public String mName;
        public int mPriority;
        public int mSimultaneity;
        public int mType;
        public RadioAttributes(String init) {
            String fragments[] = init.split(",");
            mName = fragments[0].toLowerCase();
            mPriority = Integer.parseInt(fragments[1]);
            mSimultaneity = Integer.parseInt(fragments[2]);
            if (mName.equals("wifi")) {
                mType = ConnectivityManager.TYPE_WIFI;
            } else {
                mType = ConnectivityManager.TYPE_MOBILE;
            }
        }
    }
    RadioAttributes[] mRadioAttributes;

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
                            "Unexpected InterruptedException while waiting"+
                            " for ConnectivityService thread");
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
        mNetTrackers = new NetworkStateTracker[
                ConnectivityManager.MAX_NETWORK_TYPE+1];
        mHandler = new MyHandler();

        mNetworkPreference = getPersistedNetworkPreference();

        // Load device network attributes from resources
        mNetAttributes = new NetworkAttributes[
                ConnectivityManager.MAX_NETWORK_TYPE+1];
        mRadioAttributes = new RadioAttributes[
                ConnectivityManager.MAX_RADIO_TYPE+1];
        String[] naStrings = context.getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        // TODO - what if the setting has gaps/unknown types?
        for (String a : naStrings) {
            NetworkAttributes n = new NetworkAttributes(a);
            mNetAttributes[n.mType] = n;
        }
        String[] raStrings = context.getResources().getStringArray(
                com.android.internal.R.array.radioAttributes);
        for (String a : raStrings) {
            RadioAttributes r = new RadioAttributes(a);
            mRadioAttributes[r.mType] = r;
        }

        // high priority first
        mPriorityList = new int[naStrings.length];
        {
            int priority = 0; //lowest
            int nextPos = naStrings.length-1;
            while (nextPos>-1) {
                for (int i = 0; i < mNetAttributes.length; i++) {
                    if(mNetAttributes[i].mPriority == priority) {
                        mPriorityList[nextPos--] = i;
                    }
                }
                priority++;
            }
        }

        mNetRequestersPids =
                new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE+1];
        for (int i=0; i<=ConnectivityManager.MAX_NETWORK_TYPE; i++) {
            mNetRequestersPids[i] = new ArrayList();
        }

        mFeatureUsers = new ArrayList();

        /*
         * Create the network state trackers for Wi-Fi and mobile
         * data. Maybe this could be done with a factory class,
         * but it's not clear that it's worth it, given that
         * the number of different network types is not going
         * to change very often.
         */
        if (DBG) Log.v(TAG, "Starting Wifi Service.");
        WifiStateTracker wst = new WifiStateTracker(context, mHandler);
        WifiService wifiService = new WifiService(context, wst);
        ServiceManager.addService(Context.WIFI_SERVICE, wifiService);
        mNetTrackers[ConnectivityManager.TYPE_WIFI] = wst;

        mNetTrackers[ConnectivityManager.TYPE_MOBILE] =
                new MobileDataStateTracker(context, mHandler,
                ConnectivityManager.TYPE_MOBILE, Phone.APN_TYPE_DEFAULT,
                "MOBILE");

        mNetTrackers[ConnectivityManager.TYPE_MOBILE_MMS] =
                new MobileDataStateTracker(context, mHandler,
                ConnectivityManager.TYPE_MOBILE_MMS, Phone.APN_TYPE_MMS,
                "MOBILE_MMS");

        mNetTrackers[ConnectivityManager.TYPE_MOBILE_SUPL] =
                new MobileDataStateTracker(context, mHandler,
                ConnectivityManager.TYPE_MOBILE_SUPL, Phone.APN_TYPE_SUPL,
                "MOBILE_SUPL");

        mNetTrackers[ConnectivityManager.TYPE_MOBILE_DUN] =
                new MobileDataStateTracker(context, mHandler,
                ConnectivityManager.TYPE_MOBILE_DUN, Phone.APN_TYPE_DUN,
                "MOBILE_DUN");

        mNetTrackers[ConnectivityManager.TYPE_MOBILE_HIPRI] =
                new MobileDataStateTracker(context, mHandler,
                ConnectivityManager.TYPE_MOBILE_HIPRI, Phone.APN_TYPE_HIPRI,
                "MOBILE_HIPRI");

        mNumDnsEntries = 0;

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");

        for (NetworkStateTracker t : mNetTrackers)
            t.startMonitoring();

        // Constructing this starts it too
        mWifiWatchdogService = new WifiWatchdogService(context, wst);
    }

    /**
     * Sets the preferred network.
     * @param preference the new preference
     */
    public synchronized void setNetworkPreference(int preference) {
        enforceChangePermission();
        if (ConnectivityManager.isNetworkTypeValid(preference) &&
                mNetAttributes[preference].isDefault()) {
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
        Settings.Secure.putInt(cr, Settings.Secure.NETWORK_PREFERENCE,
                networkPreference);
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
     * Make the state of network connectivity conform to the preference settings
     * In this method, we only tear down a non-preferred network. Establishing
     * a connection to the preferred network is taken care of when we handle
     * the disconnect event from the non-preferred network
     * (see {@link #handleDisconnect(NetworkInfo)}).
     */
    private void enforcePreference() {
        if (mNetTrackers[mNetworkPreference].getNetworkInfo().isConnected())
            return;

        if (!mNetTrackers[mNetworkPreference].isAvailable())
            return;

        for (int t=0; t <= ConnectivityManager.MAX_RADIO_TYPE; t++) {
            if (t != mNetworkPreference &&
                    mNetTrackers[t].getNetworkInfo().isConnected()) {
                if (DBG) {
                    Log.d(TAG, "tearing down " +
                            mNetTrackers[t].getNetworkInfo() +
                            " in enforcePreference");
                }
                teardown(mNetTrackers[t]);
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
     * @return the info for the active network, or {@code null} if none is
     * active
     */
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        for (int type=0; type <= ConnectivityManager.MAX_NETWORK_TYPE; type++) {
            if (!mNetAttributes[type].isDefault()) {
                continue;
            }
            NetworkStateTracker t = mNetTrackers[type];
            NetworkInfo info = t.getNetworkInfo();
            if (info.isConnected()) {
                if (DBG && type != mActiveDefaultNetwork) Log.e(TAG,
                        "connected default network is not " +
                        "mActiveDefaultNetwork!");
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

    /**
     * Used to notice when the calling process dies so we can self-expire
     *
     * Also used to know if the process has cleaned up after itself when
     * our auto-expire timer goes off.  The timer has a link to an object.
     *
     */
    private class FeatureUser implements IBinder.DeathRecipient {
        int mNetworkType;
        String mFeature;
        IBinder mBinder;
        int mPid;
        int mUid;
        long mCreateTime;

        FeatureUser(int type, String feature, IBinder binder) {
            super();
            mNetworkType = type;
            mFeature = feature;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            mCreateTime = System.currentTimeMillis();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public void binderDied() {
            Log.d(TAG, "ConnectivityService FeatureUser binderDied(" +
                    mNetworkType + ", " + mFeature + ", " + mBinder + "), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            stopUsingNetworkFeature(this, false);
        }

        public void expire() {
            Log.d(TAG, "ConnectivityService FeatureUser expire(" +
                    mNetworkType + ", " + mFeature + ", " + mBinder +"), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            stopUsingNetworkFeature(this, false);
        }

        public String toString() {
            return "FeatureUser("+mNetworkType+","+mFeature+","+mPid+","+mUid+"), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago";
        }
    }

    // javadoc from interface
    public int startUsingNetworkFeature(int networkType, String feature,
            IBinder binder) {
        if (DBG) {
            Log.d(TAG, "startUsingNetworkFeature for net " + networkType +
                    ": " + feature);
        }
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return Phone.APN_REQUEST_FAILED;
        }

        FeatureUser f = new FeatureUser(networkType, feature, binder);

        // TODO - move this into the MobileDataStateTracker
        int usedNetworkType = networkType;
        if(networkType == ConnectivityManager.TYPE_MOBILE) {
            if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_MMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_SUPL)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_SUPL;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_DUN;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_HIPRI)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_HIPRI;
            }
        }
        NetworkStateTracker network = mNetTrackers[usedNetworkType];
        if (network != null) {
            if (usedNetworkType != networkType) {
                Integer currentPid = new Integer(getCallingPid());

                NetworkStateTracker radio = mNetTrackers[networkType];
                NetworkInfo ni = network.getNetworkInfo();

                if (ni.isAvailable() == false) {
                    if (DBG) Log.d(TAG, "special network not available");
                    return Phone.APN_TYPE_NOT_AVAILABLE;
                }

                synchronized(this) {
                    mFeatureUsers.add(f);
                    if (!mNetRequestersPids[usedNetworkType].contains(currentPid)) {
                        // this gets used for per-pid dns when connected
                        mNetRequestersPids[usedNetworkType].add(currentPid);
                    }
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                        NetworkStateTracker.EVENT_RESTORE_DEFAULT_NETWORK,
                        f), getRestoreDefaultNetworkDelay());


                if ((ni.isConnectedOrConnecting() == true) &&
                        !network.isTeardownRequested()) {
                    if (ni.isConnected() == true) {
                        // add the pid-specific dns
                        handleDnsConfigurationChange();
                        if (DBG) Log.d(TAG, "special network already active");
                        return Phone.APN_ALREADY_ACTIVE;
                    }
                    if (DBG) Log.d(TAG, "special network already connecting");
                    return Phone.APN_REQUEST_STARTED;
                }

                // check if the radio in play can make another contact
                // assume if cannot for now

                if (DBG) Log.d(TAG, "reconnecting to special network");
                network.reconnect();
                return Phone.APN_REQUEST_STARTED;
            } else {
                synchronized(this) {
                    mFeatureUsers.add(f);
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                        NetworkStateTracker.EVENT_RESTORE_DEFAULT_NETWORK,
                        f), getRestoreDefaultNetworkDelay());

                return network.startUsingNetworkFeature(feature,
                        getCallingPid(), getCallingUid());
            }
        }
        return Phone.APN_TYPE_NOT_AVAILABLE;
    }

    // javadoc from interface
    public int stopUsingNetworkFeature(int networkType, String feature) {
        int pid = getCallingPid();
        int uid = getCallingUid();

        FeatureUser u = null;
        boolean found = false;

        synchronized(this) {
            for (int i = 0; i < mFeatureUsers.size() ; i++) {
                u = (FeatureUser)mFeatureUsers.get(i);
                if (uid == u.mUid && pid == u.mPid &&
                        networkType == u.mNetworkType &&
                        TextUtils.equals(feature, u.mFeature)) {
                    found = true;
                    break;
                }
            }
        }
        if (found && u != null) {
            // stop regardless of how many other time this proc had called start
            return stopUsingNetworkFeature(u, true);
        } else {
            // none found!
            if (DBG) Log.d(TAG, "ignoring stopUsingNetworkFeature - not a live request");
            return 1;
        }
    }

    private int stopUsingNetworkFeature(FeatureUser u, boolean ignoreDups) {
        int networkType = u.mNetworkType;
        String feature = u.mFeature;
        int pid = u.mPid;
        int uid = u.mUid;

        NetworkStateTracker tracker = null;
        boolean callTeardown = false;  // used to carry our decision outside of sync block

        if (DBG) {
            Log.d(TAG, "stopUsingNetworkFeature for net " + networkType +
                    ": " + feature);
        }
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return -1;
        }

        // need to link the mFeatureUsers list with the mNetRequestersPids state in this
        // sync block
        synchronized(this) {
            // check if this process still has an outstanding start request
            if (!mFeatureUsers.contains(u)) {
                return 1;
            }
            u.unlinkDeathRecipient();
            mFeatureUsers.remove(mFeatureUsers.indexOf(u));
            // If we care about duplicate requests, check for that here.
            //
            // This is done to support the extension of a request - the app
            // can request we start the network feature again and renew the
            // auto-shutoff delay.  Normal "stop" calls from the app though
            // do not pay attention to duplicate requests - in effect the
            // API does not refcount and a single stop will counter multiple starts.
            if (ignoreDups == false) {
                for (int i = 0; i < mFeatureUsers.size() ; i++) {
                    FeatureUser x = (FeatureUser)mFeatureUsers.get(i);
                    if (x.mUid == u.mUid && x.mPid == u.mPid &&
                            x.mNetworkType == u.mNetworkType &&
                            TextUtils.equals(x.mFeature, u.mFeature)) {
                        if (DBG) Log.d(TAG, "ignoring stopUsingNetworkFeature as dup is found");
                        return 1;
                    }
                }
            }

            // TODO - move to MobileDataStateTracker
            int usedNetworkType = networkType;
            if (networkType == ConnectivityManager.TYPE_MOBILE) {
                if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
                    usedNetworkType = ConnectivityManager.TYPE_MOBILE_MMS;
                } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_SUPL)) {
                    usedNetworkType = ConnectivityManager.TYPE_MOBILE_SUPL;
                } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN)) {
                    usedNetworkType = ConnectivityManager.TYPE_MOBILE_DUN;
                } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_HIPRI)) {
                    usedNetworkType = ConnectivityManager.TYPE_MOBILE_HIPRI;
                }
            }
            tracker =  mNetTrackers[usedNetworkType];
            if(usedNetworkType != networkType) {
                Integer currentPid = new Integer(pid);
                mNetRequestersPids[usedNetworkType].remove(currentPid);
                reassessPidDns(pid, true);
                if (mNetRequestersPids[usedNetworkType].size() != 0) {
                    if (DBG) Log.d(TAG, "not tearing down special network - " +
                           "others still using it");
                    return 1;
                }
                callTeardown = true;
            }
        }

        if (callTeardown) {
            tracker.teardown();
            return 1;
        } else {
            // do it the old fashioned way
            return tracker.stopUsingNetworkFeature(feature, pid, uid);
        }
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];

        if (!tracker.getNetworkInfo().isConnected() || tracker.isTeardownRequested()) {
            if (DBG) {
                Log.d(TAG, "requestRouteToHost on down network (" + networkType + " - dropped");
            }
            return false;
        }
        return tracker.requestRouteToHost(hostAddress);
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
                Settings.Secure.BACKGROUND_DATA,
                allowBackgroundDataUsage ? 1 : 0);

        Intent broadcast = new Intent(
                ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);
        mContext.sendBroadcast(broadcast);
    }

    private int getNumConnectedNetworks() {
        int numConnectedNets = 0;

        for (NetworkStateTracker nt : mNetTrackers) {
            if (nt.getNetworkInfo().isConnected() &&
                    !nt.isTeardownRequested()) {
                ++numConnectedNets;
            }
        }
        return numConnectedNets;
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    /**
     * Handle a {@code DISCONNECTED} event. If this pertains to the non-active
     * network, we ignore it. If it is for the active network, we send out a
     * broadcast. But first, we check whether it might be possible to connect
     * to a different network.
     * @param info the {@code NetworkInfo} for the network
     */
    private void handleDisconnect(NetworkInfo info) {

        int prevNetType = info.getType();

        mNetTrackers[prevNetType].setTeardownRequested(false);
        /*
         * If the disconnected network is not the active one, then don't report
         * this as a loss of connectivity. What probably happened is that we're
         * getting the disconnect for a network that we explicitly disabled
         * in accordance with network preference policies.
         */
        if (!mNetAttributes[prevNetType].isDefault()) {
            List pids = mNetRequestersPids[prevNetType];
            for (int i = 0; i<pids.size(); i++) {
                Integer pid = (Integer)pids.get(i);
                // will remove them because the net's no longer connected
                // need to do this now as only now do we know the pids and
                // can properly null things that are no longer referenced.
                reassessPidDns(pid.intValue(), false);
            }
        }

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
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }

        NetworkStateTracker newNet = null;
        if (mNetAttributes[prevNetType].isDefault()) {
            newNet = tryFailover(prevNetType);
            if (newNet != null) {
                NetworkInfo switchTo = newNet.getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }
        // do this before we broadcast the change
        handleConnectivityChange();

        sendStickyBroadcast(intent);
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (newNet != null && newNet.getNetworkInfo().isConnected()) {
            sendConnectedBroadcast(newNet.getNetworkInfo());
        }
    }

    // returns null if no failover available
    private NetworkStateTracker tryFailover(int prevNetType) {
        /*
         * If this is a default network, check if other defaults are available
         * or active
         */
        NetworkStateTracker newNet = null;
        if (mNetAttributes[prevNetType].isDefault()) {
            if (mActiveDefaultNetwork == prevNetType) {
                mActiveDefaultNetwork = -1;
            }

            int newType = -1;
            int newPriority = -1;
            for (int checkType=0; checkType <= ConnectivityManager.MAX_NETWORK_TYPE; checkType++) {
                if (checkType == prevNetType) {
                    continue;
                }
                if (mNetAttributes[checkType].isDefault()) {
                    /* TODO - if we have multiple nets we could use
                     * we may want to put more thought into which we choose
                     */
                    if (checkType == mNetworkPreference) {
                        newType = checkType;
                        break;
                    }
                    if (mRadioAttributes[mNetAttributes[checkType].mRadio].
                            mPriority > newPriority) {
                        newType = checkType;
                        newPriority = mRadioAttributes[mNetAttributes[newType].mRadio].mPriority;
                    }
                }
            }

            if (newType != -1) {
                newNet = mNetTrackers[newType];
                /**
                 * See if the other network is available to fail over to.
                 * If is not available, we enable it anyway, so that it
                 * will be able to connect when it does become available,
                 * but we report a total loss of connectivity rather than
                 * report that we are attempting to fail over.
                 */
                if (newNet.isAvailable()) {
                    NetworkInfo switchTo = newNet.getNetworkInfo();
                    switchTo.setFailover(true);
                    if (!switchTo.isConnectedOrConnecting() ||
                            newNet.isTeardownRequested()) {
                        newNet.reconnect();
                    }
                    if (DBG) {
                        if (switchTo.isConnected()) {
                            Log.v(TAG, "Switching to already connected " +
                                    switchTo.getTypeName());
                        } else {
                            Log.v(TAG, "Attempting to switch to " +
                                    switchTo.getTypeName());
                        }
                    }
                } else {
                    newNet.reconnect();
                }
            }
        }

        return newNet;
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
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }
        sendStickyBroadcast(intent);
    }

    /**
     * Called when an attempt to fail over to another network has failed.
     * @param info the {@link NetworkInfo} for the failed network
     */
    private void handleConnectionFailure(NetworkInfo info) {
        mNetTrackers[info.getType()].setTeardownRequested(false);

        String reason = info.getReason();
        String extraInfo = info.getExtraInfo();

        if (DBG) {
            String reasonText;
            if (reason == null) {
                reasonText = ".";
            } else {
                reasonText = " (" + reason + ").";
            }
            Log.v(TAG, "Attempt to connect to " + info.getTypeName() +
                    " failed" + reasonText);
        }

        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (getActiveNetworkInfo() == null) {
            intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
        }
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

        NetworkStateTracker newNet = null;
        if (mNetAttributes[info.getType()].isDefault()) {
            newNet = tryFailover(info.getType());
            if (newNet != null) {
                NetworkInfo switchTo = newNet.getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }

        // do this before we broadcast the change
        handleConnectivityChange();

        sendStickyBroadcast(intent);
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (newNet != null && newNet.getNetworkInfo().isConnected()) {
            sendConnectedBroadcast(newNet.getNetworkInfo());
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized(this) {
            if (mSystemReady) {
                mContext.sendStickyBroadcast(intent);
            } else {
                if (mDeferredBroadcasts == null) {
                    mDeferredBroadcasts = new ArrayList<Intent>();
                }
                mDeferredBroadcasts.add(intent);
            }
        }
    }

    void systemReady() {
        synchronized(this) {
            mSystemReady = true;
            if (mDeferredBroadcasts != null) {
                int count = mDeferredBroadcasts.size();
                for (int i = 0; i < count; i++) {
                    mContext.sendStickyBroadcast(mDeferredBroadcasts.get(i));
                }
                mDeferredBroadcasts = null;
            }
        }
    }

    private void handleConnect(NetworkInfo info) {
        int type = info.getType();

        // snapshot isFailover, because sendConnectedBroadcast() resets it
        boolean isFailover = info.isFailover();
        NetworkStateTracker thisNet = mNetTrackers[type];

        // if this is a default net and other default is running
        // kill the one not preferred
        if (mNetAttributes[type].isDefault()) {
            if (mActiveDefaultNetwork != -1 && mActiveDefaultNetwork != type) {
                if ((type != mNetworkPreference &&
                        mNetAttributes[mActiveDefaultNetwork].mPriority >
                        mNetAttributes[type].mPriority) ||
                        mNetworkPreference == mActiveDefaultNetwork) {
                        // don't accept this one
                        if (DBG) Log.v(TAG, "Not broadcasting CONNECT_ACTION " +
                                "to torn down network " + info.getTypeName());
                        teardown(thisNet);
                        return;
                } else {
                    // tear down the other
                    NetworkStateTracker otherNet =
                            mNetTrackers[mActiveDefaultNetwork];
                    if (DBG) Log.v(TAG, "Policy requires " +
                            otherNet.getNetworkInfo().getTypeName() +
                            " teardown");
                    if (!teardown(otherNet)) {
                        Log.e(TAG, "Network declined teardown request");
                        return;
                    }
                    if (isFailover) {
                        otherNet.releaseWakeLock();
                    }
                }
            }
            mActiveDefaultNetwork = type;
        }
        thisNet.setTeardownRequested(false);
        thisNet.updateNetworkSettings();
        handleConnectivityChange();
        sendConnectedBroadcast(info);
    }

    private void handleScanResultsAvailable(NetworkInfo info) {
        int networkType = info.getType();
        if (networkType != ConnectivityManager.TYPE_WIFI) {
            if (DBG) Log.v(TAG, "Got ScanResultsAvailable for " +
                    info.getTypeName() + " network. Don't know how to handle.");
        }

        mNetTrackers[networkType].interpretScanResultsAvailable();
    }

    private void handleNotificationChange(boolean visible, int id,
            Notification notification) {
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
         * If a non-default network is enabled, add the host routes that
         * will allow it's DNS servers to be accessed.  Only
         * If both mobile and wifi are enabled, add the host routes that
         * will allow MMS traffic to pass on the mobile network. But
         * remove the default route for the mobile network, so that there
         * will be only one default route, to ensure that all traffic
         * except MMS will travel via Wi-Fi.
         */
        handleDnsConfigurationChange();

        for (int netType : mPriorityList) {
            if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
                if (mNetAttributes[netType].isDefault()) {
                    mNetTrackers[netType].addDefaultRoute();
                } else {
                    mNetTrackers[netType].addPrivateDnsRoutes();
                }
            } else {
                if (mNetAttributes[netType].isDefault()) {
                    mNetTrackers[netType].removeDefaultRoute();
                } else {
                    mNetTrackers[netType].removePrivateDnsRoutes();
                }
            }
        }
    }

    /**
     * Adjust the per-process dns entries (net.dns<x>.<pid>) based
     * on the highest priority active net which this process requested.
     * If there aren't any, clear it out
     */
    private void reassessPidDns(int myPid, boolean doBump)
    {
        if (DBG) Log.d(TAG, "reassessPidDns for pid " + myPid);
        for(int i : mPriorityList) {
            if (mNetAttributes[i].isDefault()) {
                continue;
            }
            NetworkStateTracker nt = mNetTrackers[i];
            if (nt.getNetworkInfo().isConnected() &&
                    !nt.isTeardownRequested()) {
                List pids = mNetRequestersPids[i];
                for (int j=0; j<pids.size(); j++) {
                    Integer pid = (Integer)pids.get(j);
                    if (pid.intValue() == myPid) {
                        String[] dnsList = nt.getNameServers();
                        writePidDns(dnsList, myPid);
                        if (doBump) {
                            bumpDns();
                        }
                        return;
                    }
                }
           }
        }
        // nothing found - delete
        for (int i = 1; ; i++) {
            String prop = "net.dns" + i + "." + myPid;
            if (SystemProperties.get(prop).length() == 0) {
                if (doBump) {
                    bumpDns();
                }
                return;
            }
            SystemProperties.set(prop, "");
        }
    }

    private void writePidDns(String[] dnsList, int pid) {
        int j = 1;
        for (String dns : dnsList) {
            if (dns != null && !TextUtils.equals(dns, "0.0.0.0")) {
                SystemProperties.set("net.dns" + j++ + "." + pid, dns);
            }
        }
    }

    private void bumpDns() {
        /*
         * Bump the property that tells the name resolver library to reread
         * the DNS server list from the properties.
         */
        String propVal = SystemProperties.get("net.dnschange");
        int n = 0;
        if (propVal.length() != 0) {
            try {
                n = Integer.parseInt(propVal);
            } catch (NumberFormatException e) {}
        }
        SystemProperties.set("net.dnschange", "" + (n+1));
    }

    private void handleDnsConfigurationChange() {
        // add default net's dns entries
        for (int x = mPriorityList.length-1; x>= 0; x--) {
            int netType = mPriorityList[x];
            NetworkStateTracker nt = mNetTrackers[netType];
            if (nt != null && nt.getNetworkInfo().isConnected() &&
                    !nt.isTeardownRequested()) {
                String[] dnsList = nt.getNameServers();
                if (mNetAttributes[netType].isDefault()) {
                    int j = 1;
                    for (String dns : dnsList) {
                        if (dns != null && !TextUtils.equals(dns, "0.0.0.0")) {
                            if (DBG) {
                                Log.d(TAG, "adding dns " + dns + " for " +
                                        nt.getNetworkInfo().getTypeName());
                            }
                            SystemProperties.set("net.dns" + j++, dns);
                        }
                    }
                    for (int k=j ; k<mNumDnsEntries; k++) {
                        if (DBG) Log.d(TAG, "erasing net.dns" + k);
                        SystemProperties.set("net.dns" + k, "");
                    }
                    mNumDnsEntries = j;
                } else {
                    // set per-pid dns for attached secondary nets
                    List pids = mNetRequestersPids[netType];
                    for (int y=0; y< pids.size(); y++) {
                        Integer pid = (Integer)pids.get(y);
                        writePidDns(dnsList, pid.intValue());
                    }
                }
            }
        }

        bumpDns();
    }

    private int getRestoreDefaultNetworkDelay() {
        String restoreDefaultNetworkDelayStr = SystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        return RESTORE_DEFAULT_NETWORK_DELAY;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }
        pw.println();
        for (NetworkStateTracker nst : mNetTrackers) {
            if (nst != null) {
                if (nst.getNetworkInfo().isConnected()) {
                    pw.println("Active network: " + nst.getNetworkInfo().
                            getTypeName());
                }
                pw.println(nst.getNetworkInfo());
                pw.println(nst);
                pw.println();
            }
        }

        pw.println("Network Requester Pids:");
        for (int net : mPriorityList) {
            String pidString = net + ": ";
            for (Object pid : mNetRequestersPids[net]) {
                pidString = pidString + pid.toString() + ", ";
            }
            pw.println(pidString);
        }
        pw.println();

        pw.println("FeatureUsers:");
        for (Object requester : mFeatureUsers) {
            pw.println(requester.toString());
        }
        pw.println();
    }

    // must be stateless - things change under us.
    private class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
                case NetworkStateTracker.EVENT_STATE_CHANGED:
                    info = (NetworkInfo) msg.obj;
                    int type = info.getType();
                    NetworkInfo.State state = info.getState();
                    // only do this optimization for wifi.  It going into scan mode for location
                    // services generates alot of noise.  Meanwhile the mms apn won't send out
                    // subsequent notifications when on default cellular because it never
                    // disconnects..  so only do this to wifi notifications.  Fixed better when the
                    // APN notifications are standardized.
                    if (mNetAttributes[type].mLastState == state &&
                            mNetAttributes[type].mRadio == ConnectivityManager.TYPE_WIFI) {
                        if (DBG) {
                            // TODO - remove this after we validate the dropping doesn't break
                            // anything
                            Log.d(TAG, "Dropping ConnectivityChange for " +
                                    info.getTypeName() + ": " +
                                    state + "/" + info.getDetailedState());
                        }
                        return;
                    }
                    mNetAttributes[type].mLastState = state;

                    if (DBG) Log.d(TAG, "ConnectivityChange for " +
                            info.getTypeName() + ": " +
                            state + "/" + info.getDetailedState());

                    // Connectivity state changed:
                    // [31-13] Reserved for future use
                    // [12-9] Network subtype (for mobile network, as defined
                    //         by TelephonyManager)
                    // [8-3] Detailed state ordinal (as defined by
                    //         NetworkInfo.DetailedState)
                    // [2-0] Network type (as defined by ConnectivityManager)
                    int eventLogParam = (info.getType() & 0x7) |
                            ((info.getDetailedState().ordinal() & 0x3f) << 3) |
                            (info.getSubtype() << 9);
                    EventLog.writeEvent(EVENTLOG_CONNECTIVITY_STATE_CHANGED,
                            eventLogParam);

                    if (info.getDetailedState() ==
                            NetworkInfo.DetailedState.FAILED) {
                        handleConnectionFailure(info);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        handleDisconnect(info);
                    } else if (state == NetworkInfo.State.SUSPENDED) {
                        // TODO: need to think this over.
                        // the logic here is, handle SUSPENDED the same as
                        // DISCONNECTED. The only difference being we are
                        // broadcasting an intent with NetworkInfo that's
                        // suspended. This allows the applications an
                        // opportunity to handle DISCONNECTED and SUSPENDED
                        // differently, or not.
                        handleDisconnect(info);
                    } else if (state == NetworkInfo.State.CONNECTED) {
                        handleConnect(info);
                    }
                    break;

                case NetworkStateTracker.EVENT_SCAN_RESULTS_AVAILABLE:
                    info = (NetworkInfo) msg.obj;
                    handleScanResultsAvailable(info);
                    break;

                case NetworkStateTracker.EVENT_NOTIFICATION_CHANGED:
                    handleNotificationChange(msg.arg1 == 1, msg.arg2,
                            (Notification) msg.obj);

                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED:
                    handleDnsConfigurationChange();
                    break;

                case NetworkStateTracker.EVENT_ROAMING_CHANGED:
                    // fill me in
                    break;

                case NetworkStateTracker.EVENT_NETWORK_SUBTYPE_CHANGED:
                    // fill me in
                    break;
                case NetworkStateTracker.EVENT_RESTORE_DEFAULT_NETWORK:
                    FeatureUser u = (FeatureUser)msg.obj;
                    u.expire();
                    break;
            }
        }
    }
}
