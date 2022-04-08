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

import android.annotation.NonNull;
import android.net.ConnectivityManager;
import android.net.IDnsResolver;
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
import android.os.ServiceSpecificException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
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

    private final IDnsResolver mDnsResolver;
    private final INetd mNetd;
    private final INetworkManagementService mNMService;

    // The network we're running on, and its type.
    private final NetworkAgentInfo mNetwork;

    private enum State {
        IDLE,         // start() not called. Base iface and stacked iface names are null.
        DISCOVERING,  // same as IDLE, except prefix discovery in progress.
        STARTING,     // start() called. Base iface and stacked iface names are known.
        RUNNING,      // start() called, and the stacked iface is known to be up.
    }

    /**
     * NAT64 prefix currently in use. Only valid in STARTING or RUNNING states.
     * Used, among other things, to avoid updates when switching from a prefix learned from one
     * source (e.g., RA) to the same prefix learned from another source (e.g., RA).
     */
    private IpPrefix mNat64PrefixInUse;
    /** NAT64 prefix (if any) discovered from DNS via RFC 7050. */
    private IpPrefix mNat64PrefixFromDns;
    /** NAT64 prefix (if any) learned from the network via RA. */
    private IpPrefix mNat64PrefixFromRa;
    private String mBaseIface;
    private String mIface;
    private Inet6Address mIPv6Address;
    private State mState = State.IDLE;

    private boolean mPrefixDiscoveryRunning;

    public Nat464Xlat(NetworkAgentInfo nai, INetd netd, IDnsResolver dnsResolver,
            INetworkManagementService nmService) {
        mDnsResolver = dnsResolver;
        mNetd = netd;
        mNMService = nmService;
        mNetwork = nai;
    }

    /**
     * Whether to attempt 464xlat on this network. This is true for an IPv6-only network that is
     * currently connected and where the NetworkAgent has not disabled 464xlat. It is the signal to
     * enable NAT64 prefix discovery.
     *
     * @param nai the NetworkAgentInfo corresponding to the network.
     * @return true if the network requires clat, false otherwise.
     */
    @VisibleForTesting
    protected static boolean requiresClat(NetworkAgentInfo nai) {
        // TODO: migrate to NetworkCapabilities.TRANSPORT_*.
        final boolean supported = ArrayUtils.contains(NETWORK_TYPES, nai.networkInfo.getType());
        final boolean connected = ArrayUtils.contains(NETWORK_STATES, nai.networkInfo.getState());

        // Only run clat on networks that have a global IPv6 address and don't have a native IPv4
        // address.
        LinkProperties lp = nai.linkProperties;
        final boolean isIpv6OnlyNetwork = (lp != null) && lp.hasGlobalIpv6Address()
                && !lp.hasIpv4Address();

        // If the network tells us it doesn't use clat, respect that.
        final boolean skip464xlat = (nai.netAgentConfig() != null)
                && nai.netAgentConfig().skip464xlat;

        return supported && connected && isIpv6OnlyNetwork && !skip464xlat;
    }

    /**
     * Whether the clat demon should be started on this network now. This is true if requiresClat is
     * true and a NAT64 prefix has been discovered.
     *
     * @param nai the NetworkAgentInfo corresponding to the network.
     * @return true if the network should start clat, false otherwise.
     */
    @VisibleForTesting
    protected static boolean shouldStartClat(NetworkAgentInfo nai) {
        LinkProperties lp = nai.linkProperties;
        return requiresClat(nai) && lp != null && lp.getNat64Prefix() != null;
    }

    /**
     * @return true if clatd has been started and has not yet stopped.
     * A true result corresponds to internal states STARTING and RUNNING.
     */
    public boolean isStarted() {
        return (mState == State.STARTING || mState == State.RUNNING);
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
            Slog.e(TAG, "Can't register iface observer for clat on " + mNetwork.toShortString());
            return;
        }

        mNat64PrefixInUse = selectNat64Prefix();
        String addrStr = null;
        try {
            addrStr = mNetd.clatdStart(baseIface, mNat64PrefixInUse.toString());
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error starting clatd on " + baseIface + ": " + e);
        }
        mIface = CLAT_PREFIX + baseIface;
        mBaseIface = baseIface;
        mState = State.STARTING;
        try {
            mIPv6Address = (Inet6Address) InetAddresses.parseNumericAddress(addrStr);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            Slog.e(TAG, "Invalid IPv6 address " + addrStr);
        }
        if (mPrefixDiscoveryRunning && !isPrefixDiscoveryNeeded()) {
            stopPrefixDiscovery();
        }
        if (!mPrefixDiscoveryRunning) {
            setPrefix64(mNat64PrefixInUse);
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
    private void leaveStartedState() {
        try {
            mNMService.unregisterObserver(this);
        } catch (RemoteException | IllegalStateException e) {
            Slog.e(TAG, "Error unregistering clatd observer on " + mBaseIface + ": " + e);
        }
        mNat64PrefixInUse = null;
        mIface = null;
        mBaseIface = null;

        if (!mPrefixDiscoveryRunning) {
            setPrefix64(null);
        }

        if (isPrefixDiscoveryNeeded()) {
            if (!mPrefixDiscoveryRunning) {
                startPrefixDiscovery();
            }
            mState = State.DISCOVERING;
        } else {
            stopPrefixDiscovery();
            mState = State.IDLE;
        }
    }

    @VisibleForTesting
    protected void start() {
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

    @VisibleForTesting
    protected void stop() {
        if (!isStarted()) {
            Slog.e(TAG, "stopClat: already stopped");
            return;
        }

        Slog.i(TAG, "Stopping clatd on " + mBaseIface);
        try {
            mNetd.clatdStop(mBaseIface);
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error stopping clatd on " + mBaseIface + ": " + e);
        }

        String iface = mIface;
        boolean wasRunning = isRunning();

        // Change state before updating LinkProperties. handleUpdateLinkProperties ends up calling
        // fixupLinkProperties, and if at that time the state is still RUNNING, fixupLinkProperties
        // would wrongly inform ConnectivityService that there is still a stacked interface.
        leaveStartedState();

        if (wasRunning) {
            LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
            lp.removeStackedLink(iface);
            mNetwork.connService().handleUpdateLinkProperties(mNetwork, lp);
        }
    }

    private void startPrefixDiscovery() {
        try {
            mDnsResolver.startPrefix64Discovery(getNetId());
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error starting prefix discovery on netId " + getNetId() + ": " + e);
        }
        mPrefixDiscoveryRunning = true;
    }

    private void stopPrefixDiscovery() {
        try {
            mDnsResolver.stopPrefix64Discovery(getNetId());
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error stopping prefix discovery on netId " + getNetId() + ": " + e);
        }
        mPrefixDiscoveryRunning = false;
    }

    private boolean isPrefixDiscoveryNeeded() {
        // If there is no NAT64 prefix in the RA, prefix discovery is always needed. It cannot be
        // stopped after it succeeds, because stopping it will cause netd to report that the prefix
        // has been removed, and that will cause us to stop clatd.
        return requiresClat(mNetwork) && mNat64PrefixFromRa == null;
    }

    private void setPrefix64(IpPrefix prefix) {
        final String prefixString = (prefix != null) ? prefix.toString() : "";
        try {
            mDnsResolver.setPrefix64(getNetId(), prefixString);
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error setting NAT64 prefix on netId " + getNetId() + " to "
                    + prefix + ": " + e);
        }
    }

    private void maybeHandleNat64PrefixChange() {
        final IpPrefix newPrefix = selectNat64Prefix();
        if (!Objects.equals(mNat64PrefixInUse, newPrefix)) {
            Slog.d(TAG, "NAT64 prefix changed from " + mNat64PrefixInUse + " to "
                    + newPrefix);
            stop();
            // It's safe to call update here, even though this method is called from update, because
            // stop() is guaranteed to have moved out of STARTING and RUNNING, which are the only
            // states in which this method can be called.
            update();
        }
    }

    /**
     * Starts/stops NAT64 prefix discovery and clatd as necessary.
     */
    public void update() {
        // TODO: turn this class into a proper StateMachine. http://b/126113090
        switch (mState) {
            case IDLE:
                if (isPrefixDiscoveryNeeded()) {
                    startPrefixDiscovery();  // Enters DISCOVERING state.
                    mState = State.DISCOVERING;
                } else if (requiresClat(mNetwork)) {
                    start();  // Enters STARTING state.
                }
                break;

            case DISCOVERING:
                if (shouldStartClat(mNetwork)) {
                    // NAT64 prefix detected. Start clatd.
                    start();  // Enters STARTING state.
                    return;
                }
                if (!requiresClat(mNetwork)) {
                    // IPv4 address added. Go back to IDLE state.
                    stopPrefixDiscovery();
                    mState = State.IDLE;
                    return;
                }
                break;

            case STARTING:
            case RUNNING:
                // NAT64 prefix removed, or IPv4 address added.
                // Stop clatd and go back into DISCOVERING or idle.
                if (!shouldStartClat(mNetwork)) {
                    stop();
                    break;
                }
                // Only necessary while clat is actually started.
                maybeHandleNat64PrefixChange();
                break;
        }
    }

    /**
     * Picks a NAT64 prefix to use. Always prefers the prefix from the RA if one is received from
     * both RA and DNS, because the prefix in the RA has better security and updatability, and will
     * almost always be received first anyway.
     *
     * Any network that supports legacy hosts will support discovering the DNS64 prefix via DNS as
     * well. If the prefix from the RA is withdrawn, fall back to that for reliability purposes.
     */
    private IpPrefix selectNat64Prefix() {
        return mNat64PrefixFromRa != null ? mNat64PrefixFromRa : mNat64PrefixFromDns;
    }

    public void setNat64PrefixFromRa(IpPrefix prefix) {
        mNat64PrefixFromRa = prefix;
    }

    public void setNat64PrefixFromDns(IpPrefix prefix) {
        mNat64PrefixFromDns = prefix;
    }

    /**
     * Copies the stacked clat link in oldLp, if any, to the passed LinkProperties.
     * This is necessary because the LinkProperties in mNetwork come from the transport layer, which
     * has no idea that 464xlat is running on top of it.
     */
    public void fixupLinkProperties(@NonNull LinkProperties oldLp, @NonNull LinkProperties lp) {
        // This must be done even if clatd is not running, because otherwise shouldStartClat would
        // never return true.
        lp.setNat64Prefix(selectNat64Prefix());

        if (!isRunning()) {
            return;
        }
        if (lp.getAllInterfaceNames().contains(mIface)) {
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
        // TODO: if we call start(), then stop(), then start() again, and the
        // interfaceLinkStateChanged notification for the first start is delayed past the first
        // stop, then the code becomes out of sync with system state and will behave incorrectly.
        //
        // This is not trivial to fix because:
        // 1. It is not guaranteed that start() will eventually result in the interface coming up,
        //    because there could be an error starting clat (e.g., if the interface goes down before
        //    the packet socket can be bound).
        // 2. If start is called multiple times, there is nothing in the interfaceLinkStateChanged
        //    notification that says which start() call the interface was created by.
        //
        // Once this code is converted to StateMachine, it will be possible to use deferMessage to
        // ensure it stays in STARTING state until the interfaceLinkStateChanged notification fires,
        // and possibly use a timeout (or provide some guarantees at the lower layer) to address #1.
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
        mNetwork.handler().post(() -> handleInterfaceRemoved(iface));
    }

    @Override
    public String toString() {
        return "mBaseIface: " + mBaseIface + ", mIface: " + mIface + ", mState: " + mState;
    }

    @VisibleForTesting
    protected int getNetId() {
        return mNetwork.network.netId;
    }
}
