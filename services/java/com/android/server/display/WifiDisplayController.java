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

package com.android.server.display;

import com.android.internal.util.DumpUtils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Handler;
import android.util.Slog;

import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;

/**
 * Manages all of the various asynchronous interactions with the {@link WifiP2pManager}
 * on behalf of {@link WifiDisplayAdapter}.
 * <p>
 * This code is isolated from {@link WifiDisplayAdapter} so that we can avoid
 * accidentally introducing any deadlocks due to the display manager calling
 * outside of itself while holding its lock.  It's also way easier to write this
 * asynchronous code if we can assume that it is single-threaded.
 * </p><p>
 * The controller must be instantiated on the handler thread.
 * </p>
 */
final class WifiDisplayController implements DumpUtils.Dump {
    private static final String TAG = "WifiDisplayController";
    private static final boolean DEBUG = true;

    private static final int DEFAULT_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT = 50;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final WifiP2pManager mWifiP2pManager;
    private final Channel mWifiP2pChannel;

    private boolean mWifiP2pEnabled;
    private boolean mWfdEnabled;
    private boolean mWfdEnabling;
    private NetworkInfo mNetworkInfo;

    private final ArrayList<WifiP2pDevice> mKnownWifiDisplayPeers =
            new ArrayList<WifiP2pDevice>();

    // The device to which we want to connect, or null if we want to be disconnected.
    private WifiP2pDevice mDesiredDevice;

    // The device to which we are currently connecting, or null if we have already connected
    // or are not trying to connect.
    private WifiP2pDevice mConnectingDevice;

    // The device to which we are currently connected, which means we have an active P2P group.
    private WifiP2pDevice mConnectedDevice;

    // The group info obtained after connecting.
    private WifiP2pGroup mConnectedDeviceGroupInfo;

    // The device that we announced to the rest of the system.
    private WifiP2pDevice mPublishedDevice;

    public WifiDisplayController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;

        mWifiP2pManager = (WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(context, handler.getLooper(), null);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        context.registerReceiver(mWifiP2pReceiver, intentFilter);
    }

    public void dump(PrintWriter pw) {
        pw.println("mWifiP2pEnabled=" + mWifiP2pEnabled);
        pw.println("mWfdEnabled=" + mWfdEnabled);
        pw.println("mWfdEnabling=" + mWfdEnabling);
        pw.println("mNetworkInfo=" + mNetworkInfo);
        pw.println("mDesiredDevice=" + describeWifiP2pDevice(mDesiredDevice));
        pw.println("mConnectingDisplay=" + describeWifiP2pDevice(mConnectingDevice));
        pw.println("mConnectedDevice=" + describeWifiP2pDevice(mConnectedDevice));
        pw.println("mPublishedDevice=" + describeWifiP2pDevice(mPublishedDevice));

        pw.println("mKnownWifiDisplayPeers: size=" + mKnownWifiDisplayPeers.size());
        for (WifiP2pDevice device : mKnownWifiDisplayPeers) {
            pw.println("  " + describeWifiP2pDevice(device));
        }
    }

    private void enableWfd() {
        if (!mWfdEnabled && !mWfdEnabling) {
            mWfdEnabling = true;

            WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
            wfdInfo.setWfdEnabled(true);
            wfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
            wfdInfo.setSessionAvailable(true);
            wfdInfo.setControlPort(DEFAULT_CONTROL_PORT);
            wfdInfo.setMaxThroughput(MAX_THROUGHPUT);
            mWifiP2pManager.setWFDInfo(mWifiP2pChannel, wfdInfo, new ActionListener() {
                @Override
                public void onSuccess() {
                    if (DEBUG) {
                        Slog.d(TAG, "Successfully set WFD info.");
                    }
                    if (mWfdEnabling) {
                        mWfdEnabled = true;
                        mWfdEnabling = false;
                        discoverPeers();
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (DEBUG) {
                        Slog.d(TAG, "Failed to set WFD info with reason " + reason + ".");
                    }
                    mWfdEnabling = false;
                }
            });
        }
    }

    private void discoverPeers() {
        mWifiP2pManager.discoverPeers(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) {
                    Slog.d(TAG, "Discover peers succeeded.  Requesting peers now.");
                }

                requestPeers();
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) {
                    Slog.d(TAG, "Discover peers failed with reason " + reason + ".");
                }
            }
        });
    }

    private void requestPeers() {
        mWifiP2pManager.requestPeers(mWifiP2pChannel, new PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peers) {
                if (DEBUG) {
                    Slog.d(TAG, "Received list of peers.");
                }

                mKnownWifiDisplayPeers.clear();
                for (WifiP2pDevice device : peers.getDeviceList()) {
                    if (DEBUG) {
                        Slog.d(TAG, "  " + describeWifiP2pDevice(device));
                    }

                    if (isWifiDisplay(device)) {
                        mKnownWifiDisplayPeers.add(device);
                    }
                }

                // TODO: shouldn't auto-connect like this, let UI do it explicitly
                if (!mKnownWifiDisplayPeers.isEmpty()) {
                    final WifiP2pDevice device = mKnownWifiDisplayPeers.get(0);

                    if (device.status == WifiP2pDevice.AVAILABLE) {
                        connect(device);
                    }
                }

                // TODO: publish this information to applications
            }
        });
    }

    private void connect(final WifiP2pDevice device) {
        if (mDesiredDevice != null
                && !mDesiredDevice.deviceAddress.equals(device.deviceAddress)) {
            if (DEBUG) {
                Slog.d(TAG, "connect: nothing to do, already connecting to "
                        + describeWifiP2pDevice(device));
            }
            return;
        }

        if (mConnectedDevice != null
                && !mConnectedDevice.deviceAddress.equals(device.deviceAddress)
                && mDesiredDevice == null) {
            if (DEBUG) {
                Slog.d(TAG, "connect: nothing to do, already connected to "
                        + describeWifiP2pDevice(device) + " and not part way through "
                        + "connecting to a different device.");
            }
        }

        mDesiredDevice = device;
        updateConnection();
    }

    private void disconnect() {
        mDesiredDevice = null;
        updateConnection();
    }

    /**
     * This function is called repeatedly after each asynchronous operation
     * until all preconditions for the connection have been satisfied and the
     * connection is established (or not).
     */
    private void updateConnection() {
        // Step 1. Before we try to connect to a new device, tell the system we
        // have disconnected from the old one.
        if (mPublishedDevice != null && mPublishedDevice != mDesiredDevice) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onDisplayDisconnected();
                }
            });
            mPublishedDevice = null;

            // continue to next step
        }

        // Step 2. Before we try to connect to a new device, disconnect from the old one.
        if (mConnectedDevice != null && mConnectedDevice != mDesiredDevice) {
            Slog.i(TAG, "Disconnecting from Wifi display: " + mConnectedDevice.deviceName);

            final WifiP2pDevice oldDevice = mConnectedDevice;
            mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Disconnected from Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to disconnect from Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason);
                    next();
                }

                private void next() {
                    if (mConnectedDevice == oldDevice) {
                        mConnectedDevice = null;
                        updateConnection();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 3. Before we try to connect to a new device, stop trying to connect
        // to the old one.
        if (mConnectingDevice != null && mConnectingDevice != mDesiredDevice) {
            Slog.i(TAG, "Canceling connection to Wifi display: " + mConnectingDevice.deviceName);

            mHandler.removeCallbacks(mConnectionTimeout);

            final WifiP2pDevice oldDevice = mConnectingDevice;
            mWifiP2pManager.cancelConnect(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Canceled connection to Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to cancel connection to Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason);
                    next();
                }

                private void next() {
                    if (mConnectingDevice == oldDevice) {
                        mConnectingDevice = null;
                        updateConnection();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 4. If we wanted to disconnect, then mission accomplished.
        if (mDesiredDevice == null) {
            return; // done
        }

        // Step 5. Try to connect.
        if (mConnectedDevice == null && mConnectingDevice == null) {
            Slog.i(TAG, "Connecting to Wifi display: " + mDesiredDevice.deviceName);

            mConnectingDevice = mDesiredDevice;
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = mConnectingDevice.deviceAddress;

            final WifiP2pDevice newDevice = mDesiredDevice;
            mWifiP2pManager.connect(mWifiP2pChannel, config, new ActionListener() {
                @Override
                public void onSuccess() {
                    // The connection may not yet be established.  We still need to wait
                    // for WIFI_P2P_CONNECTION_CHANGED_ACTION.  However, we might never
                    // get that broadcast, so we register a timeout.
                    Slog.i(TAG, "Initiated connection to Wifi display: " + newDevice.deviceName);

                    mHandler.postDelayed(mConnectionTimeout, CONNECTION_TIMEOUT_SECONDS * 1000);
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to initiate connection to Wifi display: "
                            + newDevice.deviceName + ", reason=" + reason);
                    if (mConnectingDevice == newDevice) {
                        mConnectingDevice = null;
                        handleConnectionFailure();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 6. Publish the new connection.
        if (mConnectedDevice != null && mPublishedDevice == null) {
            Inet4Address addr = getInterfaceAddress(mConnectedDeviceGroupInfo);
            if (addr == null) {
                Slog.i(TAG, "Failed to get local interface address for communicating "
                        + "with Wifi display: " + mConnectedDevice.deviceName);
                handleConnectionFailure();
                return; // done
            }

            WifiP2pWfdInfo wfdInfo = mConnectedDevice.wfdInfo;
            int port = (wfdInfo != null ? wfdInfo.getControlPort() : DEFAULT_CONTROL_PORT);
            final String name = mConnectedDevice.deviceName;
            final String iface = addr.getHostAddress() + ":" + port;

            mPublishedDevice = mConnectedDevice;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onDisplayConnected(name, iface);
                }
            });
        }
    }

    private void handleStateChanged(boolean enabled) {
        if (mWifiP2pEnabled != enabled) {
            mWifiP2pEnabled = enabled;
            if (enabled) {
                if (mWfdEnabled) {
                    discoverPeers();
                } else {
                    enableWfd();
                }
            } else {
                mWfdEnabled = false;
                disconnect();
            }
        }
    }

    private void handlePeersChanged() {
        if (mWifiP2pEnabled) {
            if (mWfdEnabled) {
                requestPeers();
            } else {
                enableWfd();
            }
        }
    }

    private void handleConnectionChanged(NetworkInfo networkInfo) {
        mNetworkInfo = networkInfo;
        if (mWifiP2pEnabled && mWfdEnabled && networkInfo.isConnected()) {
            if (mDesiredDevice != null) {
                mWifiP2pManager.requestGroupInfo(mWifiP2pChannel, new GroupInfoListener() {
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup info) {
                        if (DEBUG) {
                            Slog.d(TAG, "Received group info: " + describeWifiP2pGroup(info));
                        }

                        if (mConnectingDevice != null && !info.contains(mConnectingDevice)) {
                            Slog.i(TAG, "Aborting connection to Wifi display because "
                                    + "the current P2P group does not contain the device "
                                    + "we expected to find: " + mConnectingDevice.deviceName);
                            handleConnectionFailure();
                            return;
                        }

                        if (mDesiredDevice != null && !info.contains(mDesiredDevice)) {
                            disconnect();
                            return;
                        }

                        if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                            Slog.i(TAG, "Connected to Wifi display: " + mConnectingDevice.deviceName);

                            mHandler.removeCallbacks(mConnectionTimeout);
                            mConnectedDeviceGroupInfo = info;
                            mConnectedDevice = mConnectingDevice;
                            mConnectingDevice = null;
                            updateConnection();
                        }
                    }
                });
            }
        } else {
            disconnect();
        }
    }

    private final Runnable mConnectionTimeout = new Runnable() {
        @Override
        public void run() {
            if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                Slog.i(TAG, "Timed out waiting for Wifi display connection after "
                        + CONNECTION_TIMEOUT_SECONDS + " seconds: "
                        + mConnectingDevice.deviceName);
                handleConnectionFailure();
            }
        }
    };

    private void handleConnectionFailure() {
        if (mDesiredDevice != null) {
            Slog.i(TAG, "Wifi display connection failed!");
            disconnect();
        }
    }

    private static Inet4Address getInterfaceAddress(WifiP2pGroup info) {
        NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(info.getInterface());
        } catch (SocketException ex) {
            Slog.w(TAG, "Could not obtain address of network interface "
                    + info.getInterface(), ex);
            return null;
        }

        Enumeration<InetAddress> addrs = iface.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress addr = addrs.nextElement();
            if (addr instanceof Inet4Address) {
                return (Inet4Address)addr;
            }
        }

        Slog.w(TAG, "Could not obtain address of network interface "
                + info.getInterface() + " because it had no IPv4 addresses.");
        return null;
    }

    private static boolean isWifiDisplay(WifiP2pDevice device) {
        // FIXME: the wfdInfo API doesn't work yet
        return false;
        //return device.deviceName.equals("DWD-300-22ACC2");
        //return device.deviceName.startsWith("DWD-")
        //        || device.deviceName.startsWith("DIRECT-")
        //        || device.deviceName.startsWith("CAVM-");
        //return device.wfdInfo != null && device.wfdInfo.isWfdEnabled();
    }

    private static String describeWifiP2pDevice(WifiP2pDevice device) {
        return device != null ? device.toString().replace('\n', ',') : "null";
    }

    private static String describeWifiP2pGroup(WifiP2pGroup group) {
        return group != null ? group.toString().replace('\n', ',') : "null";
    }

    private final BroadcastReceiver mWifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                boolean enabled = (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED)) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_STATE_CHANGED_ACTION: enabled="
                            + enabled);
                }

                handleStateChanged(enabled);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)) {
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_PEERS_CHANGED_ACTION.");
                }

                handlePeersChanged();
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_CONNECTION_CHANGED_ACTION: networkInfo="
                            + networkInfo);
                }

                handleConnectionChanged(networkInfo);
            }
        }
    };

    /**
     * Called on the handler thread when displays are connected or disconnected.
     */
    public interface Listener {
        void onDisplayConnected(String deviceName, String iface);
        void onDisplayDisconnected();
    }
}
