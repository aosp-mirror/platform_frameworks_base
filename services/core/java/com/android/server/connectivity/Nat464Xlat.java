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

import java.net.Inet4Address;
import java.util.Objects;

/**
 * Class to manage a 464xlat CLAT daemon.
 *
 * @hide
 */
public class Nat464Xlat extends BaseNetworkObserver {
    private static final String TAG = Nat464Xlat.class.getSimpleName();

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // The network types we will start clatd on,
    // allowing clat only on networks for which we can support IPv6-only.
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

    private enum State {
        IDLE,       // start() not called. Base iface and stacked iface names are null.
        STARTING,   // start() called. Base iface and stacked iface names are known.
        RUNNING;    // start() called, and the stacked iface is known to be up.
    }

    // Once mIface is non-null and isStarted() is true, methods called by ConnectivityService on
    // its handler thread must not modify any internal state variables; they are only updated by the
    // interface observers, called on the notification threads.
    private String mBaseIface;
    private String mIface;
    private volatile State mState = State.IDLE;

    public Nat464Xlat(INetworkManagementService nmService, Handler handler, NetworkAgentInfo nai) {
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
        // TODO: migrate to NetworkCapabilities.TRANSPORT_*.
        final int netType = nai.networkInfo.getType();
        final boolean supported = ArrayUtils.contains(NETWORK_TYPES, nai.networkInfo.getType());
        final boolean connected = nai.networkInfo.isConnected();
        // We only run clat on networks that don't have a native IPv4 address.
        final boolean hasIPv4Address =
                (nai.linkProperties != null) && nai.linkProperties.hasIPv4Address();
        return supported && connected && !hasIPv4Address;
    }

    /**
     * @return true if clatd has been started and has not yet stopped.
     * A true result corresponds to internal states STARTING and RUNNING.
     */
    public boolean isStarted() {
        return mState != State.IDLE;
    }

    /**
     * @return true if clatd has been started and the stacked interface is up.
     */
    public boolean isRunning() {
        return mState == State.RUNNING;
    }

    /**
     * Sets internal state.
     */
    private void enterStartingState(String baseIface) {
        mIface = CLAT_PREFIX + baseIface;
        mBaseIface = baseIface;
        mState = State.STARTING;
    }

    /**
     * Clears internal state. Must not be called by ConnectivityService.
     */
    private void enterIdleState() {
        mIface = null;
        mBaseIface = null;
        mState = State.IDLE;
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

        String baseIface = mNetwork.linkProperties.getInterfaceName();
        if (baseIface == null) {
            Slog.e(TAG, "startClat: Can't start clat on null interface");
            return;
        }
        // TODO: should we only do this if mNMService.startClatd() succeeds?
        enterStartingState(baseIface);

        Slog.i(TAG, "Starting clatd on " + mBaseIface);
        try {
            mNMService.startClatd(mBaseIface);
        } catch(RemoteException|IllegalStateException e) {
            Slog.e(TAG, "Error starting clatd on " + mBaseIface, e);
        }
    }

    /**
     * Stops the clat daemon. Called by ConnectivityService on the handler thread.
     */
    public void stop() {
        if (!isStarted()) {
            Slog.e(TAG, "stopClat: already stopped or not started");
            return;
        }

        Slog.i(TAG, "Stopping clatd on " + mBaseIface);
        try {
            mNMService.stopClatd(mBaseIface);
        } catch(RemoteException|IllegalStateException e) {
            Slog.e(TAG, "Error stopping clatd on " + mBaseIface, e);
        }
        // When clatd stops and its interface is deleted, interfaceRemoved() will notify
        // ConnectivityService and call enterIdleState().
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
        if (!isRunning()) {
            return;
        }
        LinkProperties lp = mNetwork.linkProperties;
        if (lp == null || lp.getAllInterfaceNames().contains(mIface)) {
            return;
        }

        Slog.d(TAG, "clatd running, updating NAI for " + mIface);
        for (LinkProperties stacked: oldLp.getStackedLinks()) {
            if (Objects.equals(mIface, stacked.getInterfaceName())) {
                lp.addStackedLink(stacked);
                return;
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
        // TODO: migrate to NetworkCapabilities.TRANSPORT_*.
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

    /**
     * Adds stacked link on base link and transitions to Running state
     * This is called by the InterfaceObserver on its own thread, so can race with stop().
     */
    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        if (!isStarted() || !up || !Objects.equals(mIface, iface)) {
            return;
        }
        if (isRunning()) {
            return;
        }
        LinkAddress clatAddress = getLinkAddress(iface);
        if (clatAddress == null) {
            return;
        }
        mState = State.RUNNING;
        Slog.i(TAG, String.format("interface %s is up, adding stacked link %s on top of %s",
                mIface, mIface, mBaseIface));

        maybeSetIpv6NdOffload(mBaseIface, false);
        LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
        lp.addStackedLink(makeLinkProperties(clatAddress));
        updateConnectivityService(lp);
    }

    @Override
    public void interfaceRemoved(String iface) {
        if (!isStarted() || !Objects.equals(mIface, iface)) {
            return;
        }
        if (!isRunning()) {
            return;
        }

        Slog.i(TAG, "interface " + iface + " removed");
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
        enterIdleState();
        updateConnectivityService(lp);
    }

    @Override
    public String toString() {
        return "mBaseIface: " + mBaseIface + ", mIface: " + mIface + ", mState: " + mState;
    }
}
