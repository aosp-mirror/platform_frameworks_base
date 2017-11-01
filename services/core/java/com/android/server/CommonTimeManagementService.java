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

package com.android.server;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.InterfaceConfiguration;
import android.os.Binder;
import android.os.CommonTimeConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.util.DumpUtils;
import com.android.server.net.BaseNetworkObserver;

/**
 * @hide
 * <p>CommonTimeManagementService manages the configuration of the native Common Time service,
 * reconfiguring the native service as appropriate in response to changes in network configuration.
 */
class CommonTimeManagementService extends Binder {
    /*
     * Constants and globals.
     */
    private static final String TAG = CommonTimeManagementService.class.getSimpleName();
    private static final int NATIVE_SERVICE_RECONNECT_TIMEOUT = 5000;
    private static final String AUTO_DISABLE_PROP = "ro.common_time.auto_disable";
    private static final String ALLOW_WIFI_PROP = "ro.common_time.allow_wifi";
    private static final String SERVER_PRIO_PROP = "ro.common_time.server_prio";
    private static final String NO_INTERFACE_TIMEOUT_PROP = "ro.common_time.no_iface_timeout";
    private static final boolean AUTO_DISABLE;
    private static final boolean ALLOW_WIFI;
    private static final byte BASE_SERVER_PRIO;
    private static final int NO_INTERFACE_TIMEOUT;
    private static final InterfaceScoreRule[] IFACE_SCORE_RULES;

    static {
        int tmp;
        AUTO_DISABLE         = (0 != SystemProperties.getInt(AUTO_DISABLE_PROP, 1));
        ALLOW_WIFI           = (0 != SystemProperties.getInt(ALLOW_WIFI_PROP, 0));
        tmp                  = SystemProperties.getInt(SERVER_PRIO_PROP, 1);
        NO_INTERFACE_TIMEOUT = SystemProperties.getInt(NO_INTERFACE_TIMEOUT_PROP, 60000);

        if (tmp < 1)
            BASE_SERVER_PRIO = 1;
        else
        if (tmp > 30)
            BASE_SERVER_PRIO = 30;
        else
            BASE_SERVER_PRIO = (byte)tmp;

        if (ALLOW_WIFI) {
            IFACE_SCORE_RULES = new InterfaceScoreRule[] {
                new InterfaceScoreRule("wlan", (byte)1),
                new InterfaceScoreRule("eth", (byte)2),
            };
        } else {
            IFACE_SCORE_RULES = new InterfaceScoreRule[] {
                new InterfaceScoreRule("eth", (byte)2),
            };
        }
    };

    /*
     * Internal state
     */
    private final Context mContext;
    private final Object mLock = new Object();
    private INetworkManagementService mNetMgr;
    private CommonTimeConfig mCTConfig;
    private String mCurIface;
    private Handler mReconnectHandler = new Handler();
    private Handler mNoInterfaceHandler = new Handler();
    private boolean mDetectedAtStartup = false;
    private byte mEffectivePrio = BASE_SERVER_PRIO;

    /*
     * Callback handler implementations.
     */
    private INetworkManagementEventObserver mIfaceObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceStatusChanged(String iface, boolean up) {
            reevaluateServiceState();
        }
        @Override
        public void interfaceLinkStateChanged(String iface, boolean up) {
            reevaluateServiceState();
        }
        @Override
        public void interfaceAdded(String iface) {
            reevaluateServiceState();
        }
        @Override
        public void interfaceRemoved(String iface) {
            reevaluateServiceState();
        }
    };

    private BroadcastReceiver mConnectivityMangerObserver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reevaluateServiceState();
        }
    };

    private CommonTimeConfig.OnServerDiedListener mCTServerDiedListener =
            () -> scheduleTimeConfigReconnect();

    private Runnable mReconnectRunnable = () -> connectToTimeConfig();

    private Runnable mNoInterfaceRunnable = () -> handleNoInterfaceTimeout();

    /*
     * Public interface (constructor, systemReady and dump)
     */
    public CommonTimeManagementService(Context context) {
        mContext = context;
    }

    void systemRunning() {
        if (ServiceManager.checkService(CommonTimeConfig.SERVICE_NAME) == null) {
            Log.i(TAG, "No common time service detected on this platform.  " +
                       "Common time services will be unavailable.");
            return;
        }

        mDetectedAtStartup = true;

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNetMgr = INetworkManagementService.Stub.asInterface(b);

        // Network manager is running along-side us, so we should never receiver a remote exception
        // while trying to register this observer.
        try {
            mNetMgr.registerObserver(mIfaceObserver);
        }
        catch (RemoteException e) { }

        // Register with the connectivity manager for connectivity changed intents.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mConnectivityMangerObserver, filter);

        // Connect to the common time config service and apply the initial configuration.
        connectToTimeConfig();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        if (!mDetectedAtStartup) {
            pw.println("Native Common Time service was not detected at startup.  " +
                       "Service is unavailable");
            return;
        }

        synchronized (mLock) {
            pw.println("Current Common Time Management Service Config:");
            pw.println(String.format("  Native service     : %s",
                                     (null == mCTConfig) ? "reconnecting"
                                                         : "alive"));
            pw.println(String.format("  Bound interface    : %s",
                                     (null == mCurIface ? "unbound" : mCurIface)));
            pw.println(String.format("  Allow WiFi         : %s", ALLOW_WIFI ? "yes" : "no"));
            pw.println(String.format("  Allow Auto Disable : %s", AUTO_DISABLE ? "yes" : "no"));
            pw.println(String.format("  Server Priority    : %d", mEffectivePrio));
            pw.println(String.format("  No iface timeout   : %d", NO_INTERFACE_TIMEOUT));
        }
    }

    /*
     * Inner helper classes
     */
    private static class InterfaceScoreRule {
        public final String mPrefix;
        public final byte mScore;
        public InterfaceScoreRule(String prefix, byte score) {
            mPrefix = prefix;
            mScore = score;
        }
    };

    /*
     * Internal implementation
     */
    private void cleanupTimeConfig() {
        mReconnectHandler.removeCallbacks(mReconnectRunnable);
        mNoInterfaceHandler.removeCallbacks(mNoInterfaceRunnable);
        if (null != mCTConfig) {
            mCTConfig.release();
            mCTConfig = null;
        }
    }

    private void connectToTimeConfig() {
        // Get access to the common time service configuration interface.  If we catch a remote
        // exception in the process (service crashed or no running for w/e reason), schedule an
        // attempt to reconnect in the future.
        cleanupTimeConfig();
        try {
            synchronized (mLock) {
                mCTConfig = new CommonTimeConfig();
                mCTConfig.setServerDiedListener(mCTServerDiedListener);
                mCurIface = mCTConfig.getInterfaceBinding();
                mCTConfig.setAutoDisable(AUTO_DISABLE);
                mCTConfig.setMasterElectionPriority(mEffectivePrio);
            }

            if (NO_INTERFACE_TIMEOUT >= 0)
                mNoInterfaceHandler.postDelayed(mNoInterfaceRunnable, NO_INTERFACE_TIMEOUT);

            reevaluateServiceState();
        }
        catch (RemoteException e) {
            scheduleTimeConfigReconnect();
        }
    }

    private void scheduleTimeConfigReconnect() {
        cleanupTimeConfig();
        Log.w(TAG, String.format("Native service died, will reconnect in %d mSec",
                                 NATIVE_SERVICE_RECONNECT_TIMEOUT));
        mReconnectHandler.postDelayed(mReconnectRunnable,
                                      NATIVE_SERVICE_RECONNECT_TIMEOUT);
    }

    private void handleNoInterfaceTimeout() {
        if (null != mCTConfig) {
            Log.i(TAG, "Timeout waiting for interface to come up.  " +
                       "Forcing networkless master mode.");
            if (CommonTimeConfig.ERROR_DEAD_OBJECT == mCTConfig.forceNetworklessMasterMode())
                scheduleTimeConfigReconnect();
        }
    }

    private void reevaluateServiceState() {
        String bindIface = null;
        byte bestScore = -1;
        try {
            // Check to see if this interface is suitable to use for time synchronization.
            //
            // TODO : This selection algorithm needs to be enhanced for use with mobile devices.  In
            // particular, the choice of whether to a wireless interface or not should not be an all
            // or nothing thing controlled by properties.  It would probably be better if the
            // platform had some concept of public wireless networks vs. home or friendly wireless
            // networks (something a user would configure in settings or when a new interface is
            // added).  Then this algorithm could pick only wireless interfaces which were flagged
            // as friendly, and be dormant when on public wireless networks.
            //
            // Another issue which needs to be dealt with is the use of driver supplied interface
            // name to determine the network type.  The fact that the wireless interface on a device
            // is named "wlan0" is just a matter of convention; its not a 100% rule.  For example,
            // there are devices out there where the wireless is name "tiwlan0", not "wlan0".  The
            // internal network management interfaces in Android have all of the information needed
            // to make a proper classification, there is just no way (currently) to fetch an
            // interface's type (available from the ConnectionManager) as well as its address
            // (available from either the java.net interfaces or from the NetworkManagment service).
            // Both can enumerate interfaces, but that is no way to correlate their results (no
            // common shared key; although using the interface name in the connection manager would
            // be a good start).  Until this gets resolved, we resort to substring searching for
            // tags like wlan and eth.
            //
            String ifaceList[] = mNetMgr.listInterfaces();
            if (null != ifaceList) {
                for (String iface : ifaceList) {

                    byte thisScore = -1;
                    for (InterfaceScoreRule r : IFACE_SCORE_RULES) {
                        if (iface.contains(r.mPrefix)) {
                            thisScore = r.mScore;
                            break;
                        }
                    }

                    if (thisScore <= bestScore)
                        continue;

                    InterfaceConfiguration config = mNetMgr.getInterfaceConfig(iface);
                    if (null == config)
                        continue;

                    if (config.isActive()) {
                        bindIface = iface;
                        bestScore = thisScore;
                    }
                }
            }
        }
        catch (RemoteException e) {
            // Bad news; we should not be getting remote exceptions from the connectivity manager
            // since it is running in SystemServer along side of us.  It probably does not matter
            // what we do here, but go ahead and unbind the common time service in this case, just
            // so we have some defined behavior.
            bindIface = null;
        }

        boolean doRebind = true;
        synchronized (mLock) {
            if ((null != bindIface) && (null == mCurIface)) {
                Log.e(TAG, String.format("Binding common time service to %s.", bindIface));
                mCurIface = bindIface;
            } else
            if ((null == bindIface) && (null != mCurIface)) {
                Log.e(TAG, "Unbinding common time service.");
                mCurIface = null;
            } else
            if ((null != bindIface) && (null != mCurIface) && !bindIface.equals(mCurIface)) {
                Log.e(TAG, String.format("Switching common time service binding from %s to %s.",
                                         mCurIface, bindIface));
                mCurIface = bindIface;
            } else {
                doRebind = false;
            }
        }

        if (doRebind && (null != mCTConfig)) {
            byte newPrio = (bestScore > 0)
                         ? (byte)(bestScore * BASE_SERVER_PRIO)
                         : BASE_SERVER_PRIO;
            if (newPrio != mEffectivePrio) {
                mEffectivePrio = newPrio;
                mCTConfig.setMasterElectionPriority(mEffectivePrio);
            }

            int res = mCTConfig.setNetworkBinding(mCurIface);
            if (res != CommonTimeConfig.SUCCESS)
                scheduleTimeConfigReconnect();

            else if (NO_INTERFACE_TIMEOUT >= 0) {
                mNoInterfaceHandler.removeCallbacks(mNoInterfaceRunnable);
                if (null == mCurIface)
                    mNoInterfaceHandler.postDelayed(mNoInterfaceRunnable, NO_INTERFACE_TIMEOUT);
            }
        }
    }
}
