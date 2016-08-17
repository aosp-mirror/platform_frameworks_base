/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import android.net.INetd;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkState;
import android.net.RouteInfo;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.os.INetworkManagementService;
import android.os.ServiceSpecificException;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;


/**
 * @hide
 */
class IPv6TetheringInterfaceServices {
    private static final String TAG = IPv6TetheringInterfaceServices.class.getSimpleName();
    private static final IpPrefix LINK_LOCAL_PREFIX = new IpPrefix("fe80::/64");
    private static final int RFC7421_IP_PREFIX_LENGTH = 64;

    private final String mIfName;
    private final INetworkManagementService mNMService;

    private NetworkInterface mNetworkInterface;
    private byte[] mHwAddr;
    private LinkProperties mLastIPv6LinkProperties;
    private RouterAdvertisementDaemon mRaDaemon;
    private RaParams mLastRaParams;

    IPv6TetheringInterfaceServices(String ifname, INetworkManagementService nms) {
        mIfName = ifname;
        mNMService = nms;
    }

    public boolean start() {
        try {
            mNetworkInterface = NetworkInterface.getByName(mIfName);
        } catch (SocketException e) {
            Log.e(TAG, "Failed to find NetworkInterface for " + mIfName, e);
            stop();
            return false;
        }

        try {
            mHwAddr = mNetworkInterface.getHardwareAddress();
        } catch (SocketException e) {
            Log.e(TAG, "Failed to find hardware address for " + mIfName, e);
            stop();
            return false;
        }

        final int ifindex = mNetworkInterface.getIndex();
        mRaDaemon = new RouterAdvertisementDaemon(mIfName, ifindex, mHwAddr);
        if (!mRaDaemon.start()) {
            stop();
            return false;
        }

        return true;
    }

    public void stop() {
        mNetworkInterface = null;
        mHwAddr = null;
        setRaParams(null);

        if (mRaDaemon != null) {
            mRaDaemon.stop();
            mRaDaemon = null;
        }
    }

    // IPv6TetheringCoordinator sends updates with carefully curated IPv6-only
    // LinkProperties. These have extraneous data filtered out and only the
    // necessary prefixes included (per its prefix distribution policy).
    //
    // TODO: Evaluate using a data structure than is more directly suited to
    // communicating only the relevant information.
    public void updateUpstreamIPv6LinkProperties(LinkProperties v6only) {
        if (mRaDaemon == null) return;

        // Avoid unnecessary work on spurious updates.
        if (Objects.equals(mLastIPv6LinkProperties, v6only)) {
            return;
        }

        RaParams params = null;

        if (v6only != null) {
            params = new RaParams();
            params.mtu = v6only.getMtu();
            params.hasDefaultRoute = v6only.hasIPv6DefaultRoute();

            for (LinkAddress linkAddr : v6only.getLinkAddresses()) {
                if (linkAddr.getPrefixLength() != RFC7421_IP_PREFIX_LENGTH) continue;

                final IpPrefix prefix = new IpPrefix(
                        linkAddr.getAddress(), linkAddr.getPrefixLength());
                params.prefixes.add(prefix);

                final Inet6Address dnsServer = getLocalDnsIpFor(prefix);
                if (dnsServer != null) {
                    params.dnses.add(dnsServer);
                }
            }
        }
        // If v6only is null, we pass in null to setRaParams(), which handles
        // deprecation of any existing RA data.

        setRaParams(params);
        mLastIPv6LinkProperties = v6only;
    }


    private void configureLocalRoutes(
            HashSet<IpPrefix> deprecatedPrefixes, HashSet<IpPrefix> newPrefixes) {
        // [1] Remove the routes that are deprecated.
        if (!deprecatedPrefixes.isEmpty()) {
            final ArrayList<RouteInfo> toBeRemoved = getLocalRoutesFor(deprecatedPrefixes);
            try {
                final int removalFailures = mNMService.removeRoutesFromLocalNetwork(toBeRemoved);
                if (removalFailures > 0) {
                    Log.e(TAG, String.format("Failed to remove %d IPv6 routes from local table.",
                            removalFailures));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove IPv6 routes from local table: ", e);
            }
        }

        // [2] Add only the routes that have not previously been added.
        if (newPrefixes != null && !newPrefixes.isEmpty()) {
            HashSet<IpPrefix> addedPrefixes = (HashSet) newPrefixes.clone();
            if (mLastRaParams != null) {
                addedPrefixes.removeAll(mLastRaParams.prefixes);
            }

            if (mLastRaParams == null || mLastRaParams.prefixes.isEmpty()) {
                // We need to be able to send unicast RAs, and clients might
                // like to ping the default router's link-local address.  Note
                // that we never remove the link-local route from the network
                // until Tethering disables tethering on the interface. We
                // only need to add the link-local prefix once, but in the
                // event we add it more than once netd silently ignores EEXIST.
                addedPrefixes.add(LINK_LOCAL_PREFIX);
            }

            if (!addedPrefixes.isEmpty()) {
                final ArrayList<RouteInfo> toBeAdded = getLocalRoutesFor(addedPrefixes);
                try {
                    // It's safe to call addInterfaceToLocalNetwork() even if
                    // the interface is already in the local_network. Note also
                    // that adding routes that already exist does not cause an
                    // error (EEXIST is silently ignored).
                    mNMService.addInterfaceToLocalNetwork(mIfName, toBeAdded);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to add IPv6 routes to local table: ", e);
                }
            }
        }
    }

    private void configureLocalDns(
            HashSet<Inet6Address> deprecatedDnses, HashSet<Inet6Address> newDnses) {
        INetd netd = getNetdServiceOrNull();
        if (netd == null) {
            if (newDnses != null) newDnses.clear();
            Log.e(TAG, "No netd service instance available; not setting local IPv6 addresses");
            return;
        }

        // [1] Remove deprecated local DNS IP addresses.
        if (!deprecatedDnses.isEmpty()) {
            for (Inet6Address dns : deprecatedDnses) {
                final String dnsString = dns.getHostAddress();
                try {
                    netd.interfaceDelAddress(mIfName, dnsString, RFC7421_IP_PREFIX_LENGTH);
                } catch (ServiceSpecificException | RemoteException e) {
                    Log.e(TAG, "Failed to remove local dns IP: " + dnsString, e);
                }
            }
        }

        // [2] Add only the local DNS IP addresses that have not previously been added.
        if (newDnses != null && !newDnses.isEmpty()) {
            final HashSet<Inet6Address> addedDnses = (HashSet) newDnses.clone();
            if (mLastRaParams != null) {
                addedDnses.removeAll(mLastRaParams.dnses);
            }

            for (Inet6Address dns : addedDnses) {
                final String dnsString = dns.getHostAddress();
                try {
                    netd.interfaceAddAddress(mIfName, dnsString, RFC7421_IP_PREFIX_LENGTH);
                } catch (ServiceSpecificException | RemoteException e) {
                    Log.e(TAG, "Failed to add local dns IP: " + dnsString, e);
                    newDnses.remove(dns);
                }
            }
        }

        try {
            netd.tetherApplyDnsInterfaces();
        } catch (ServiceSpecificException | RemoteException e) {
            Log.e(TAG, "Failed to update local DNS caching server");
            if (newDnses != null) newDnses.clear();
        }
    }

    private void setRaParams(RaParams newParams) {
        if (mRaDaemon != null) {
            final RaParams deprecatedParams =
                    RaParams.getDeprecatedRaParams(mLastRaParams, newParams);

            configureLocalRoutes(deprecatedParams.prefixes,
                    (newParams != null) ? newParams.prefixes : null);

            configureLocalDns(deprecatedParams.dnses,
                    (newParams != null) ? newParams.dnses : null);

            mRaDaemon.buildNewRa(deprecatedParams, newParams);
        }

        mLastRaParams = newParams;
    }

    // Accumulate routes representing "prefixes to be assigned to the local
    // interface", for subsequent modification of local_network routing.
    private ArrayList<RouteInfo> getLocalRoutesFor(HashSet<IpPrefix> prefixes) {
        final ArrayList<RouteInfo> localRoutes = new ArrayList<RouteInfo>();
        for (IpPrefix ipp : prefixes) {
            localRoutes.add(new RouteInfo(ipp, null, mIfName));
        }
        return localRoutes;
    }

    private INetd getNetdServiceOrNull() {
        if (mNMService != null) {
            try {
                return mNMService.getNetdService();
            } catch (RemoteException ignored) {
                // This blocks until netd can be reached, but it can return
                // null during a netd crash.
            }
        }
        return null;
    }

    // Given a prefix like 2001:db8::/64 return 2001:db8::1.
    private static Inet6Address getLocalDnsIpFor(IpPrefix localPrefix) {
        final byte[] dnsBytes = localPrefix.getRawAddress();
        dnsBytes[dnsBytes.length - 1] = 0x1;
        try {
            return Inet6Address.getByAddress(null, dnsBytes, 0);
        } catch (UnknownHostException e) {
            Slog.wtf(TAG, "Failed to construct Inet6Address from: " + localPrefix);
            return null;
        }
    }
}
