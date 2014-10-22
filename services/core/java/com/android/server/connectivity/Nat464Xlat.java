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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.TYPE_MOBILE;

import java.net.Inet4Address;

import android.content.Context;
import android.net.IConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.net.BaseNetworkObserver;

/**
 * @hide
 *
 * Class to manage a 464xlat CLAT daemon.
 */
public class Nat464Xlat extends BaseNetworkObserver {
    private Context mContext;
    private INetworkManagementService mNMService;
    private IConnectivityManager mConnService;
    // Whether we started clatd and expect it to be running.
    private boolean mIsStarted;
    // Whether the clatd interface exists (i.e., clatd is running).
    private boolean mIsRunning;
    // The LinkProperties of the clat interface.
    private LinkProperties mLP;
    // Current LinkProperties of the network.  Includes mLP as a stacked link when clat is active.
    private LinkProperties mBaseLP;
    // ConnectivityService Handler for LinkProperties updates.
    private Handler mHandler;
    // Marker to connote which network we're augmenting.
    private Messenger mNetworkMessenger;

    // This must match the interface name in clatd.conf.
    private static final String CLAT_INTERFACE_NAME = "clat4";

    private static final String TAG = "Nat464Xlat";

    public Nat464Xlat(Context context, INetworkManagementService nmService,
                      IConnectivityManager connService, Handler handler) {
        mContext = context;
        mNMService = nmService;
        mConnService = connService;
        mHandler = handler;

        mIsStarted = false;
        mIsRunning = false;
        mLP = new LinkProperties();

        // If this is a runtime restart, it's possible that clatd is already
        // running, but we don't know about it. If so, stop it.
        try {
            if (mNMService.isClatdStarted()) {
                mNMService.stopClatd();
            }
        } catch(RemoteException e) {}  // Well, we tried.
    }

    /**
     * Determines whether a network requires clat.
     * @param network the NetworkAgentInfo corresponding to the network.
     * @return true if the network requires clat, false otherwise.
     */
    public static boolean requiresClat(NetworkAgentInfo nai) {
        final int netType = nai.networkInfo.getType();
        final boolean connected = nai.networkInfo.isConnected();
        final boolean hasIPv4Address =
                (nai.linkProperties != null) ? nai.linkProperties.hasIPv4Address() : false;
        Slog.d(TAG, "requiresClat: netType=" + netType +
                    ", connected=" + connected +
                    ", hasIPv4Address=" + hasIPv4Address);
        // Only support clat on mobile for now.
        return netType == TYPE_MOBILE && connected && !hasIPv4Address;
    }

    public boolean isRunningClat(NetworkAgentInfo network) {
        return mNetworkMessenger == network.messenger;
    }

    /**
     * Starts the clat daemon.
     * @param lp The link properties of the interface to start clatd on.
     */
    public void startClat(NetworkAgentInfo network) {
        if (mNetworkMessenger != null && mNetworkMessenger != network.messenger) {
            Slog.e(TAG, "startClat: too many networks requesting clat");
            return;
        }
        mNetworkMessenger = network.messenger;
        LinkProperties lp = network.linkProperties;
        mBaseLP = new LinkProperties(lp);
        if (mIsStarted) {
            Slog.e(TAG, "startClat: already started");
            return;
        }
        String iface = lp.getInterfaceName();
        Slog.i(TAG, "Starting clatd on " + iface + ", lp=" + lp);
        try {
            mNMService.startClatd(iface);
        } catch(RemoteException e) {
            Slog.e(TAG, "Error starting clat daemon: " + e);
        }
        mIsStarted = true;
    }

    /**
     * Stops the clat daemon.
     */
    public void stopClat() {
        if (mIsStarted) {
            Slog.i(TAG, "Stopping clatd");
            try {
                mNMService.stopClatd();
            } catch(RemoteException e) {
                Slog.e(TAG, "Error stopping clat daemon: " + e);
            }
            mIsStarted = false;
            mIsRunning = false;
            mNetworkMessenger = null;
            mBaseLP = null;
            mLP.clear();
        } else {
            Slog.e(TAG, "stopClat: already stopped");
        }
    }

    private void updateConnectivityService() {
        Message msg = mHandler.obtainMessage(
            NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED, mBaseLP);
        msg.replyTo = mNetworkMessenger;
        Slog.i(TAG, "sending message to ConnectivityService: " + msg);
        msg.sendToTarget();
    }

    // Copies the stacked clat link in oldLp, if any, to the LinkProperties in nai.
    public void fixupLinkProperties(NetworkAgentInfo nai, LinkProperties oldLp) {
        if (isRunningClat(nai) &&
                nai.linkProperties != null &&
                !nai.linkProperties.getAllInterfaceNames().contains(CLAT_INTERFACE_NAME)) {
            Slog.d(TAG, "clatd running, updating NAI for " + nai.linkProperties.getInterfaceName());
            for (LinkProperties stacked: oldLp.getStackedLinks()) {
                if (CLAT_INTERFACE_NAME.equals(stacked.getInterfaceName())) {
                    nai.linkProperties.addStackedLink(stacked);
                    break;
                }
            }
        }
    }

    @Override
    public void interfaceAdded(String iface) {
        if (iface.equals(CLAT_INTERFACE_NAME)) {
            Slog.i(TAG, "interface " + CLAT_INTERFACE_NAME +
                   " added, mIsRunning = " + mIsRunning + " -> true");
            mIsRunning = true;

            // Create the LinkProperties for the clat interface by fetching the
            // IPv4 address for the interface and adding an IPv4 default route,
            // then stack the LinkProperties on top of the link it's running on.
            try {
                InterfaceConfiguration config = mNMService.getInterfaceConfig(iface);
                LinkAddress clatAddress = config.getLinkAddress();
                mLP.clear();
                mLP.setInterfaceName(iface);

                // Although the clat interface is a point-to-point tunnel, we don't
                // point the route directly at the interface because some apps don't
                // understand routes without gateways (see, e.g., http://b/9597256
                // http://b/9597516). Instead, set the next hop of the route to the
                // clat IPv4 address itself (for those apps, it doesn't matter what
                // the IP of the gateway is, only that there is one).
                RouteInfo ipv4Default = new RouteInfo(new LinkAddress(Inet4Address.ANY, 0),
                                                      clatAddress.getAddress(), iface);
                mLP.addRoute(ipv4Default);
                mLP.addLinkAddress(clatAddress);
                mBaseLP.addStackedLink(mLP);
                Slog.i(TAG, "Adding stacked link. tracker LP: " + mBaseLP);
                updateConnectivityService();
            } catch(RemoteException e) {
                Slog.e(TAG, "Error getting link properties: " + e);
            }
        }
    }

    @Override
    public void interfaceRemoved(String iface) {
        if (iface == CLAT_INTERFACE_NAME) {
            if (mIsRunning) {
                NetworkUtils.resetConnections(
                    CLAT_INTERFACE_NAME,
                    NetworkUtils.RESET_IPV4_ADDRESSES);
                mBaseLP.removeStackedLink(mLP);
                updateConnectivityService();
            }
            Slog.i(TAG, "interface " + CLAT_INTERFACE_NAME +
                   " removed, mIsRunning = " + mIsRunning + " -> false");
            mIsRunning = false;
            mLP.clear();
            Slog.i(TAG, "mLP = " + mLP);
        }
    }
};
