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

import android.net.BaseNetworkStateTracker;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.INetworkManagementService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.text.TextUtils;
import android.util.Log;
import java.net.InterfaceAddress;
import android.net.LinkAddress;
import android.net.RouteInfo;
import java.net.Inet4Address;
import android.os.SystemProperties;

import com.android.internal.util.AsyncChannel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class tracks the data connection associated with Bluetooth
 * reverse tethering. This is a singleton class and an instance will be
 * created by ConnectivityService. BluetoothService will call into this
 * when a reverse tethered connection needs to be activated.
 *
 * @hide
 */
public class BluetoothTetheringDataTracker extends BaseNetworkStateTracker {
    private static final String NETWORKTYPE = "BLUETOOTH_TETHER";
    private static final String TAG = "BluetoothTethering";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private AtomicBoolean mTeardownRequested = new AtomicBoolean(false);
    private AtomicBoolean mPrivateDnsRouteSet = new AtomicBoolean(false);
    private AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);
    private AtomicBoolean mDefaultRouteSet = new AtomicBoolean(false);

    private final Object mLinkPropertiesLock = new Object();
    private final Object mNetworkInfoLock = new Object();

    private BluetoothPan mBluetoothPan;
    private static String mRevTetheredIface;
    /* For sending events to connectivity service handler */
    private Handler mCsHandler;
    private static BluetoothTetheringDataTracker sInstance;
    private BtdtHandler mBtdtHandler;
    private AtomicReference<AsyncChannel> mAsyncChannel = new AtomicReference<AsyncChannel>(null);

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

    public Object clone() throws CloneNotSupportedException {
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
        mBtdtHandler = new BtdtHandler(target.getLooper(), this);
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

    @Override
    public void captivePortalCheckCompleted(boolean isCaptivePortal) {
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
    public NetworkInfo getNetworkInfo() {
        synchronized (mNetworkInfoLock) {
            return new NetworkInfo(mNetworkInfo);
        }
    }

    /**
     * Fetch LinkProperties for the network
     */
    public LinkProperties getLinkProperties() {
        synchronized (mLinkPropertiesLock) {
            return new LinkProperties(mLinkProperties);
        }
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

    void startReverseTether(final LinkProperties linkProperties) {
        if (linkProperties == null || TextUtils.isEmpty(linkProperties.getInterfaceName())) {
            Log.e(TAG, "attempted to reverse tether with empty interface");
            return;
        }
        synchronized (mLinkPropertiesLock) {
            if (mLinkProperties.getInterfaceName() != null) {
                Log.e(TAG, "attempted to reverse tether while already in process");
                return;
            }
            mLinkProperties = linkProperties;
        }
        Thread dhcpThread = new Thread(new Runnable() {
            public void run() {
                //Currently this thread runs independently.
                DhcpResults dhcpResults = new DhcpResults();
                boolean success = NetworkUtils.runDhcp(linkProperties.getInterfaceName(),
                        dhcpResults);
                synchronized (mLinkPropertiesLock) {
                    if (linkProperties.getInterfaceName() != mLinkProperties.getInterfaceName()) {
                        Log.e(TAG, "obsolete DHCP run aborted");
                        return;
                    }
                    if (!success) {
                        Log.e(TAG, "DHCP request error:" + NetworkUtils.getDhcpError());
                        return;
                    }
                    mLinkProperties = dhcpResults.linkProperties;
                    synchronized (mNetworkInfoLock) {
                        mNetworkInfo.setIsAvailable(true);
                        mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
                        if (mCsHandler != null) {
                            Message msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED,
                                    new NetworkInfo(mNetworkInfo));
                            msg.sendToTarget();
                       }
                    }
                    return;
                }
            }
        });
        dhcpThread.start();
    }

    void stopReverseTether() {
        synchronized (mLinkPropertiesLock) {
            if (TextUtils.isEmpty(mLinkProperties.getInterfaceName())) {
                Log.e(TAG, "attempted to stop reverse tether with nothing tethered");
                return;
            }
            NetworkUtils.stopDhcp(mLinkProperties.getInterfaceName());
            mLinkProperties.clear();
            synchronized (mNetworkInfoLock) {
                mNetworkInfo.setIsAvailable(false);
                mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);

                if (mCsHandler != null) {
                    mCsHandler.obtainMessage(EVENT_STATE_CHANGED, new NetworkInfo(mNetworkInfo)).
                            sendToTarget();
                }
            }
        }
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }

    @Override
    public void addStackedLink(LinkProperties link) {
        mLinkProperties.addStackedLink(link);
    }

    @Override
    public void removeStackedLink(LinkProperties link) {
        mLinkProperties.removeStackedLink(link);
    }

    static class BtdtHandler extends Handler {
        private AsyncChannel mStackChannel;
        private final BluetoothTetheringDataTracker mBtdt;

        BtdtHandler(Looper looper, BluetoothTetheringDataTracker parent) {
            super(looper);
            mBtdt = parent;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (VDBG) Log.d(TAG, "got CMD_CHANNEL_HALF_CONNECTED");
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        AsyncChannel ac = (AsyncChannel)msg.obj;
                        if (mBtdt.mAsyncChannel.compareAndSet(null, ac) == false) {
                            Log.e(TAG, "Trying to set mAsyncChannel twice!");
                        } else {
                            ac.sendMessage(
                                    AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                        }
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (VDBG) Log.d(TAG, "got CMD_CHANNEL_DISCONNECTED");
                    mBtdt.stopReverseTether();
                    mBtdt.mAsyncChannel.set(null);
                    break;
                case NetworkStateTracker.EVENT_NETWORK_CONNECTED:
                    LinkProperties linkProperties = (LinkProperties)(msg.obj);
                    if (VDBG) Log.d(TAG, "got EVENT_NETWORK_CONNECTED, " + linkProperties);
                    mBtdt.startReverseTether(linkProperties);
                    break;
                case NetworkStateTracker.EVENT_NETWORK_DISCONNECTED:
                    linkProperties = (LinkProperties)(msg.obj);
                    if (VDBG) Log.d(TAG, "got EVENT_NETWORK_DISCONNECTED, " + linkProperties);
                    mBtdt.stopReverseTether();
                    break;
            }
        }
    }

    @Override
    public void supplyMessenger(Messenger messenger) {
        if (messenger != null) {
            new AsyncChannel().connect(mContext, mBtdtHandler, messenger);
        }
    }
}
