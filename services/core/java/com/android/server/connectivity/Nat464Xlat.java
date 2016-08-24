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

import java.net.Inet4Address;

import android.content.Context;
import android.net.InterfaceConfiguration;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Message;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.net.BaseNetworkObserver;
import com.android.internal.util.ArrayUtils;

/**
 * @hide
 *
 * Class to manage a 464xlat CLAT daemon.
 */
public class Nat464Xlat extends BaseNetworkObserver {
    private static final String TAG = "Nat464Xlat";

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // The network types we will start clatd on.
    private static final int[] NETWORK_TYPES = {
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_ETHERNET,
    };

    private final INetworkManagementService mNMService;

    // ConnectivityService Handler for LinkProperties updates.
    private final Handler mHandler;

    // The network we're running on, and its type.
    private final NetworkAgentInfo mNetwork;

    // Internal state variables.
    //
    // The possible states are:
    //  - Idle: start() not called. Everything is null.
    //  - Starting: start() called. Interfaces are non-null. isStarted() returns true.
    //    mIsRunning is false.
    //  - Running: start() called, and interfaceLinkStateChanged() told us that mIface is up.
    //    mIsRunning is true.
    //
    // Once mIface is non-null and isStarted() is true, methods called by ConnectivityService on
    // its handler thread must not modify any internal state variables; they are only updated by the
    // interface observers, called on the notification threads.
    private String mBaseIface;
    private String mIface;
    private boolean mIsRunning;

    public Nat464Xlat(
            Context context, INetworkManagementService nmService,
            Handler handler, NetworkAgentInfo nai) {
        mNMService = nmService;
        mHandler = handler;
        mNetwork = nai;
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
        // Only support clat on mobile and wifi for now, because these are the only IPv6-only
        // networks we can connect to.
        return connected && !hasIPv4Address && ArrayUtils.contains(NETWORK_TYPES, netType);
    }

    /**
     * Determines whether clatd is started. Always true, except a) if start has not yet been called,
     * or b) if our interface was removed.
     */
    public boolean isStarted() {
        return mIface != null;
    }

    /**
     * Clears internal state. Must not be called by ConnectivityService.
     */
    private void clear() {
        mIface = null;
        mBaseIface = null;
        mIsRunning = false;
    }

    /**
     * Starts the clat daemon. Called by ConnectivityService on the handler thread.
     */
    public void start() {
        if (isStarted()) {
            Slog.e(TAG, "startClat: already started");
            return;
        }

        if (mNetwork.linkProperties == null) {
            Slog.e(TAG, "startClat: Can't start clat with null LinkProperties");
            return;
        }

        try {
            mNMService.registerObserver(this);
        } catch(RemoteException e) {
            Slog.e(TAG, "startClat: Can't register interface observer for clat on " + mNetwork);
            return;
        }

        mBaseIface = mNetwork.linkProperties.getInterfaceName();
        if (mBaseIface == null) {
            Slog.e(TAG, "startClat: Can't start clat on null interface");
            return;
        }
        mIface = CLAT_PREFIX + mBaseIface;
        // From now on, isStarted() will return true.

        Slog.i(TAG, "Starting clatd on " + mBaseIface);
        try {
            mNMService.startClatd(mBaseIface);
        } catch(RemoteException|IllegalStateException e) {
            Slog.e(TAG, "Error starting clatd: " + e);
        }
    }

    /**
     * Stops the clat daemon. Called by ConnectivityService on the handler thread.
     */
    public void stop() {
        if (isStarted()) {
            Slog.i(TAG, "Stopping clatd");
            try {
                mNMService.stopClatd(mBaseIface);
            } catch(RemoteException|IllegalStateException e) {
                Slog.e(TAG, "Error stopping clatd: " + e);
            }
            // When clatd stops and its interface is deleted, interfaceRemoved() will notify
            // ConnectivityService and call clear().
        } else {
            Slog.e(TAG, "clatd: already stopped");
        }
    }

    private void updateConnectivityService(LinkProperties lp) {
        Message msg = mHandler.obtainMessage(NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED, lp);
        msg.replyTo = mNetwork.messenger;
        Slog.i(TAG, "sending message to ConnectivityService: " + msg);
        msg.sendToTarget();
    }

    /**
     * Copies the stacked clat link in oldLp, if any, to the LinkProperties in mNetwork.
     * This is necessary because the LinkProperties in mNetwork come from the transport layer, which
     * has no idea that 464xlat is running on top of it.
     */
    public void fixupLinkProperties(LinkProperties oldLp) {
        if (mNetwork.clatd != null &&
                mIsRunning &&
                mNetwork.linkProperties != null &&
                !mNetwork.linkProperties.getAllInterfaceNames().contains(mIface)) {
            Slog.d(TAG, "clatd running, updating NAI for " + mIface);
            for (LinkProperties stacked: oldLp.getStackedLinks()) {
                if (mIface.equals(stacked.getInterfaceName())) {
                    mNetwork.linkProperties.addStackedLink(stacked);
                    break;
                }
            }
        }
    }

    private LinkProperties makeLinkProperties(LinkAddress clatAddress) {
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName(mIface);

        // Although the clat interface is a point-to-point tunnel, we don't
        // point the route directly at the interface because some apps don't
        // understand routes without gateways (see, e.g., http://b/9597256
        // http://b/9597516). Instead, set the next hop of the route to the
        // clat IPv4 address itself (for those apps, it doesn't matter what
        // the IP of the gateway is, only that there is one).
        RouteInfo ipv4Default = new RouteInfo(
                new LinkAddress(Inet4Address.ANY, 0),
                clatAddress.getAddress(), mIface);
        stacked.addRoute(ipv4Default);
        stacked.addLinkAddress(clatAddress);
        return stacked;
    }

    private LinkAddress getLinkAddress(String iface) {
        try {
            InterfaceConfiguration config = mNMService.getInterfaceConfig(iface);
            return config.getLinkAddress();
        } catch(RemoteException|IllegalStateException e) {
            Slog.e(TAG, "Error getting link properties: " + e);
            return null;
        }
    }

    private void maybeSetIpv6NdOffload(String iface, boolean on) {
        if (mNetwork.networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
            return;
        }
        try {
            Slog.d(TAG, (on ? "En" : "Dis") + "abling ND offload on " + iface);
            mNMService.setInterfaceIpv6NdOffload(iface, on);
        } catch(RemoteException|IllegalStateException e) {
            Slog.w(TAG, "Changing IPv6 ND offload on " + iface + "failed: " + e);
        }
    }

    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        // Called by the InterfaceObserver on its own thread, so can race with stop().
        if (isStarted() && up && mIface.equals(iface)) {
            Slog.i(TAG, "interface " + iface + " is up, mIsRunning " + mIsRunning + "->true");

            if (!mIsRunning) {
                LinkAddress clatAddress = getLinkAddress(iface);
                if (clatAddress == null) {
                    return;
                }
                mIsRunning = true;
                maybeSetIpv6NdOffload(mBaseIface, false);
                LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
                lp.addStackedLink(makeLinkProperties(clatAddress));
                Slog.i(TAG, "Adding stacked link " + mIface + " on top of " + mBaseIface);
                updateConnectivityService(lp);
            }
        }
    }

    @Override
    public void interfaceRemoved(String iface) {
        if (isStarted() && mIface.equals(iface)) {
            Slog.i(TAG, "interface " + iface + " removed, mIsRunning " + mIsRunning + "->false");

            if (mIsRunning) {
                // The interface going away likely means clatd has crashed. Ask netd to stop it,
                // because otherwise when we try to start it again on the same base interface netd
                // will complain that it's already started.
                //
                // Note that this method can be called by the interface observer at the same time
                // that ConnectivityService calls stop(). In this case, the second call to
                // stopClatd() will just throw IllegalStateException, which we'll ignore.
                try {
                    mNMService.unregisterObserver(this);
                    mNMService.stopClatd(mBaseIface);
                } catch (RemoteException|IllegalStateException e) {
                    // Well, we tried.
                }
                maybeSetIpv6NdOffload(mBaseIface, true);
                LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
                lp.removeStackedLink(mIface);
                clear();
                updateConnectivityService(lp);
            }
        }
    }
}
