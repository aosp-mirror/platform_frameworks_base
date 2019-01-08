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

import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.net.BaseNetworkObserver;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Objects;

/**
 * Class to manage a 464xlat CLAT daemon. Nat464Xlat is not thread safe and should be manipulated
 * from a consistent and unique thread context. It is the responsibility of ConnectivityService to
 * call into this class from its own Handler thread.
 *
 * @hide
 */
public class Nat464Xlat extends BaseNetworkObserver {
    private static final String TAG = Nat464Xlat.class.getSimpleName();

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // The network types on which we will start clatd,
    // allowing clat only on networks for which we can support IPv6-only.
    private static final int[] NETWORK_TYPES = {
        ConnectivityManager.TYPE_MOBILE,
        ConnectivityManager.TYPE_WIFI,
        ConnectivityManager.TYPE_ETHERNET,
    };

    // The network states in which running clatd is supported.
    private static final NetworkInfo.State[] NETWORK_STATES = {
        NetworkInfo.State.CONNECTED,
        NetworkInfo.State.SUSPENDED,
    };

    private final INetd mNetd;
    private final INetworkManagementService mNMService;

    // The network we're running on, and its type.
    private final NetworkAgentInfo mNetwork;

    private enum State {
        IDLE,       // start() not called. Base iface and stacked iface names are null.
        STARTING,   // start() called. Base iface and stacked iface names are known.
        RUNNING,    // start() called, and the stacked iface is known to be up.
    }

    private IpPrefix mNat64Prefix;
    private String mBaseIface;
    private String mIface;
    private Inet6Address mIPv6Address;
    private State mState = State.IDLE;

    public Nat464Xlat(NetworkAgentInfo nai, INetd netd, INetworkManagementService nmService) {
        mNetd = netd;
        mNMService = nmService;
        mNetwork = nai;
    }

    /**
     * Determines whether a network requires clat.
     * @param network the NetworkAgentInfo corresponding to the network.
     * @return true if the network requires clat, false otherwise.
     */
    public static boolean requiresClat(NetworkAgentInfo nai) {
        // TODO: migrate to NetworkCapabilities.TRANSPORT_*.
        final boolean supported = ArrayUtils.contains(NETWORK_TYPES, nai.networkInfo.getType());
        final boolean connected = ArrayUtils.contains(NETWORK_STATES, nai.networkInfo.getState());

        // We only run clat on networks that have a global IPv6 address and a NAT64 prefix and don't
        // have a native IPv4 address.
        LinkProperties lp = nai.linkProperties;
        final boolean isNat64Network = (lp != null) && lp.hasGlobalIPv6Address()
                && lp.getNat64Prefix() != null && !lp.hasIPv4Address();

        // If the network tells us it doesn't use clat, respect that.
        final boolean skip464xlat = (nai.netMisc() != null) && nai.netMisc().skip464xlat;

        return supported && connected && isNat64Network && !skip464xlat;
    }

    /**
     * @return true if clatd has been started and has not yet stopped.
     * A true result corresponds to internal states STARTING and RUNNING.
     */
    public boolean isStarted() {
        return mState != State.IDLE;
    }

    /**
     * @return true if clatd has been started but the stacked interface is not yet up.
     */
    public boolean isStarting() {
        return mState == State.STARTING;
    }

    /**
     * @return true if clatd has been started and the stacked interface is up.
     */
    public boolean isRunning() {
        return mState == State.RUNNING;
    }

    /**
     * Start clatd, register this Nat464Xlat as a network observer for the stacked interface,
     * and set internal state.
     */
    private void enterStartingState(String baseIface) {
        try {
            mNMService.registerObserver(this);
        } catch (RemoteException e) {
            Slog.e(TAG, "Can't register interface observer for clat on " + mNetwork.name());
            return;
        }

        String addrStr = null;
        try {
            addrStr = mNetd.clatdStart(baseIface, mNat64Prefix.toString());
        } catch (RemoteException | IllegalStateException e) {
            Slog.e(TAG, "Error starting clatd on " + baseIface, e);
        }
        mIface = CLAT_PREFIX + baseIface;
        mBaseIface = baseIface;
        mState = State.STARTING;
        try {
            mIPv6Address = (Inet6Address) InetAddresses.parseNumericAddress(addrStr);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            Slog.e(TAG, "Invalid IPv6 address " + addrStr);
        }
    }

    /**
     * Enter running state just after getting confirmation that the stacked interface is up, and
     * turn ND offload off if on WiFi.
     */
    private void enterRunningState() {
        mState = State.RUNNING;
    }

    /**
     * Unregister as a base observer for the stacked interface, and clear internal state.
     */
    private void enterIdleState() {
        try {
            mNMService.unregisterObserver(this);
        } catch (RemoteException | IllegalStateException e) {
            Slog.e(TAG, "Error unregistering clatd observer on " + mBaseIface, e);
        }
        mIface = null;
        mBaseIface = null;
        mState = State.IDLE;
    }

    /**
     * Starts the clat daemon.
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

        String baseIface = mNetwork.linkProperties.getInterfaceName();
        if (baseIface == null) {
            Slog.e(TAG, "startClat: Can't start clat on null interface");
            return;
        }
        // TODO: should we only do this if mNetd.clatdStart() succeeds?
        Slog.i(TAG, "Starting clatd on " + baseIface);
        enterStartingState(baseIface);
    }

    /**
     * Stops the clat daemon.
     */
    public void stop() {
        if (!isStarted()) {
            Slog.e(TAG, "stopClat: already stopped");
            return;
        }

        Slog.i(TAG, "Stopping clatd on " + mBaseIface);
        try {
            mNetd.clatdStop(mBaseIface);
        } catch (RemoteException | IllegalStateException e) {
            Slog.e(TAG, "Error stopping clatd on " + mBaseIface, e);
        }

        String iface = mIface;
        boolean wasRunning = isRunning();

        // Enter IDLE state before updating LinkProperties. handleUpdateLinkProperties ends up
        // calling fixupLinkProperties, and if at that time the state is still RUNNING,
        // fixupLinkProperties would wrongly inform ConnectivityService that there is still a
        // stacked interface.
        enterIdleState();

        if (wasRunning) {
            LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
            lp.removeStackedLink(iface);
            mNetwork.connService().handleUpdateLinkProperties(mNetwork, lp);
        }
    }

    public void setNat64Prefix(IpPrefix nat64Prefix) {
        mNat64Prefix = nat64Prefix;
    }

    /**
     * Copies the stacked clat link in oldLp, if any, to the passed LinkProperties.
     * This is necessary because the LinkProperties in mNetwork come from the transport layer, which
     * has no idea that 464xlat is running on top of it.
     */
    public void fixupLinkProperties(LinkProperties oldLp, LinkProperties lp) {
        lp.setNat64Prefix(mNat64Prefix);

        if (!isRunning()) {
            return;
        }
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
        } catch (RemoteException | IllegalStateException e) {
            Slog.e(TAG, "Error getting link properties: " + e);
            return null;
        }
    }

    /**
     * Adds stacked link on base link and transitions to RUNNING state.
     */
    private void handleInterfaceLinkStateChanged(String iface, boolean up) {
        if (!isStarting() || !up || !Objects.equals(mIface, iface)) {
            return;
        }

        LinkAddress clatAddress = getLinkAddress(iface);
        if (clatAddress == null) {
            Slog.e(TAG, "clatAddress was null for stacked iface " + iface);
            return;
        }

        Slog.i(TAG, String.format("interface %s is up, adding stacked link %s on top of %s",
                mIface, mIface, mBaseIface));
        enterRunningState();
        LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
        lp.addStackedLink(makeLinkProperties(clatAddress));
        mNetwork.connService().handleUpdateLinkProperties(mNetwork, lp);
    }

    /**
     * Removes stacked link on base link and transitions to IDLE state.
     */
    private void handleInterfaceRemoved(String iface) {
        if (!Objects.equals(mIface, iface)) {
            return;
        }
        if (!isRunning()) {
            return;
        }

        Slog.i(TAG, "interface " + iface + " removed");
        // If we're running, and the interface was removed, then we didn't call stop(), and it's
        // likely that clatd crashed. Ensure we call stop() so we can start clatd again. Calling
        // stop() will also update LinkProperties, and if clatd crashed, the LinkProperties update
        // will cause ConnectivityService to call start() again.
        stop();
    }

    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        mNetwork.handler().post(() -> { handleInterfaceLinkStateChanged(iface, up); });
    }

    @Override
    public void interfaceRemoved(String iface) {
        mNetwork.handler().post(() -> { handleInterfaceRemoved(iface); });
    }

    @Override
    public String toString() {
        return "mBaseIface: " + mBaseIface + ", mIface: " + mIface + ", mState: " + mState;
    }
}
