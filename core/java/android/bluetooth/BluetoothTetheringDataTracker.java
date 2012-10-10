/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.bluetooth;

import android.os.IBinder;
import android.os.ServiceManager;
import android.os.INetworkManagementService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfoInternal;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import java.net.InterfaceAddress;
import android.net.LinkAddress;
import android.net.RouteInfo;
import java.net.Inet4Address;
import android.os.SystemProperties;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class tracks the data connection associated with Bluetooth
 * reverse tethering. This is a singleton class and an instance will be
 * created by ConnectivityService. BluetoothService will call into this
 * when a reverse tethered connection needs to be activated.
 *
 * @hide
 */
public class BluetoothTetheringDataTracker implements NetworkStateTracker {
    private static final String NETWORKTYPE = "BLUETOOTH_TETHER";
    private static final String TAG = "BluetoothTethering";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private AtomicBoolean mTeardownRequested = new AtomicBoolean(false);
    private AtomicBoolean mPrivateDnsRouteSet = new AtomicBoolean(false);
    private AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);
    private AtomicBoolean mDefaultRouteSet = new AtomicBoolean(false);

    private LinkProperties mLinkProperties;
    private LinkCapabilities mLinkCapabilities;
    private NetworkInfo mNetworkInfo;

    private BluetoothPan mBluetoothPan;
    private static String mIface;
    private Thread mDhcpThread;
    /* For sending events to connectivity service handler */
    private Handler mCsHandler;
    private Context mContext;
    public static BluetoothTetheringDataTracker sInstance;

    private BluetoothTetheringDataTracker() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_BLUETOOTH, 0, NETWORKTYPE, "");
        mLinkProperties = new LinkProperties();
        mLinkCapabilities = new LinkCapabilities();

        mNetworkInfo.setIsAvailable(false);
        setTeardownRequested(false);
    }

    public static synchronized BluetoothTetheringDataTracker getInstance() {
        if (sInstance == null) sInstance = new BluetoothTetheringDataTracker();
        return sInstance;
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested.set(isRequested);
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested.get();
    }

    /**
     * Begin monitoring connectivity
     */
    public void startMonitoring(Context context, Handler target) {
        if (DBG) Log.d(TAG, "startMonitoring: target: " + target);
        mContext = context;
        mCsHandler = target;
        if (VDBG) Log.d(TAG, "startMonitoring: mCsHandler: " + mCsHandler);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.PAN);
        }
    }

    private BluetoothProfile.ServiceListener mProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothPan = (BluetoothPan) proxy;
        }
        public void onServiceDisconnected(int profile) {
            mBluetoothPan = null;
        }
    };

    /**
     * Disable connectivity to a network
     * TODO: do away with return value after making MobileDataStateTracker async
     */
    public boolean teardown() {
        mTeardownRequested.set(true);
        if (mBluetoothPan != null) {
            for (BluetoothDevice device: mBluetoothPan.getConnectedDevices()) {
                mBluetoothPan.disconnect(device);
            }
        }
        return true;
    }

    @Override
    public void captivePortalCheckComplete() {
        // not implemented
    }

    /**
     * Re-enable connectivity to a network after a {@link #teardown()}.
     */
    public boolean reconnect() {
        mTeardownRequested.set(false);
        //Ignore
        return true;
    }

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public boolean setRadio(boolean turnOn) {
        return true;
    }

    /**
     * @return true - If are we currently tethered with another device.
     */
    public synchronized boolean isAvailable() {
        return mNetworkInfo.isAvailable();
    }

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature that is no longer needed.
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    @Override
    public void setUserDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setUserDataEnable(" + enabled + ")");
    }

    @Override
    public void setPolicyDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setPolicyDataEnable(" + enabled + ")");
    }

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet.get();
    }

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet.set(enabled);
    }

    /**
     * Fetch NetworkInfo for the network
     */
    public synchronized NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * Fetch LinkProperties for the network
     */
    public synchronized LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

   /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    /**
     * Fetch default gateway address for the network
     */
    public int getDefaultGatewayAddr() {
        return mDefaultGatewayAddr.get();
    }

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet.get();
    }

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet.set(enabled);
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    private static short countPrefixLength(byte [] mask) {
        short count = 0;
        for (byte b : mask) {
            for (int i = 0; i < 8; ++i) {
                if ((b & (1 << i)) != 0) {
                    ++count;
                }
            }
        }
        return count;
    }


    private boolean readLinkProperty(String iface) {
        String DhcpPrefix = "dhcp." + iface + ".";
        String ip = SystemProperties.get(DhcpPrefix + "ipaddress");
        String dns1 = SystemProperties.get(DhcpPrefix + "dns1");
        String dns2 = SystemProperties.get(DhcpPrefix + "dns2");
        String gateway = SystemProperties.get(DhcpPrefix + "gateway");
        String mask = SystemProperties.get(DhcpPrefix + "mask");
        if(ip.isEmpty() || gateway.isEmpty()) {
            Log.e(TAG, "readLinkProperty, ip: " +  ip + ", gateway: " + gateway + ", can not be empty");
            return false;
        }
        int PrefixLen = countPrefixLength(NetworkUtils.numericToInetAddress(mask).getAddress());
        mLinkProperties.addLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(ip), PrefixLen));
        RouteInfo ri = new RouteInfo(NetworkUtils.numericToInetAddress(gateway));
        mLinkProperties.addRoute(ri);
        if(!dns1.isEmpty())
            mLinkProperties.addDns(NetworkUtils.numericToInetAddress(dns1));
        if(!dns2.isEmpty())
            mLinkProperties.addDns(NetworkUtils.numericToInetAddress(dns2));
        mLinkProperties.setInterfaceName(iface);
        return true;
    }
    public synchronized void startReverseTether(String iface) {
        mIface = iface;
        if (DBG) Log.d(TAG, "startReverseTether mCsHandler: " + mCsHandler);
         mDhcpThread = new Thread(new Runnable() {
            public void run() {
                //TODO(): Add callbacks for failure and success case.
                //Currently this thread runs independently.
                if (DBG) Log.d(TAG, "startReverseTether mCsHandler: " + mCsHandler);
                String DhcpResultName = "dhcp." + mIface + ".result";;
                String result = "";
                if (VDBG) Log.d(TAG, "waiting for change of sys prop dhcp result: " + DhcpResultName);
                for(int i = 0; i < 30*5; i++) {
                    try { Thread.sleep(200); } catch (InterruptedException ie) { return;}
                    result = SystemProperties.get(DhcpResultName);
                    if (VDBG) Log.d(TAG, "read " + DhcpResultName + ": " + result);
                    if(result.equals("failed")) {
                        Log.e(TAG, "startReverseTether, failed to start dhcp service");
                        return;
                    }
                    if(result.equals("ok")) {
                        if (VDBG) Log.d(TAG, "startReverseTether, dhcp resut: " + result);
                        if(readLinkProperty(mIface)) {

                            mNetworkInfo.setIsAvailable(true);
                            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);

                            if (VDBG) Log.d(TAG, "startReverseTether mCsHandler: " + mCsHandler);
                            if(mCsHandler != null) {
                                Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                                msg.sendToTarget();

                                msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
                                msg.sendToTarget();
                            }
                        }
                        return;
                    }
                }
                Log.e(TAG, "startReverseTether, dhcp failed, resut: " + result);
            }
        });
        mDhcpThread.start();
    }

    public synchronized void stopReverseTether() {
        //NetworkUtils.stopDhcp(iface);
        if(mDhcpThread != null && mDhcpThread.isAlive()) {
            mDhcpThread.interrupt();
            try { mDhcpThread.join(); } catch (InterruptedException ie) { return; }
        }
        mLinkProperties.clear();
        mNetworkInfo.setIsAvailable(false);
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);

        Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }
}
