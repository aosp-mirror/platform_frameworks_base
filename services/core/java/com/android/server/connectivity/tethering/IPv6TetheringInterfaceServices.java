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

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkState;
import android.net.RouteInfo;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashSet;


/**
 * @hide
 */
class IPv6TetheringInterfaceServices {
    private static final String TAG = IPv6TetheringInterfaceServices.class.getSimpleName();

    private final String mIfName;
    private final INetworkManagementService mNMService;

    private NetworkInterface mNetworkInterface;
    private byte[] mHwAddr;
    private ArrayList<RouteInfo> mLastLocalRoutes;
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
        updateLocalRoutes(null);
        updateRaParams(null);

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

        if (v6only == null) {
            updateLocalRoutes(null);
            updateRaParams(null);
            return;
        }

        RaParams params = new RaParams();
        params.mtu = v6only.getMtu();
        params.hasDefaultRoute = v6only.hasIPv6DefaultRoute();

        ArrayList<RouteInfo> localRoutes = new ArrayList<RouteInfo>();
        for (LinkAddress linkAddr : v6only.getLinkAddresses()) {
            final IpPrefix prefix = new IpPrefix(linkAddr.getAddress(),
                                                 linkAddr.getPrefixLength());

            // Accumulate routes representing "prefixes to be assigned to the
            // local interface", for subsequent addition to the local network
            // in the routing rules.
            localRoutes.add(new RouteInfo(prefix, null, mIfName));

            params.prefixes.add(prefix);
        }

        // We need to be able to send unicast RAs, and clients might like to
        // ping the default router's link-local address, so add that as well.
        localRoutes.add(new RouteInfo(new IpPrefix("fe80::/64"), null, mIfName));

        // TODO: Add a local interface address, update dnsmasq to listen on the
        // new address, and use only that address as a DNS server.
        for (InetAddress dnsServer : v6only.getDnsServers()) {
            if (dnsServer instanceof Inet6Address) {
                params.dnses.add((Inet6Address) dnsServer);
            }
        }

        updateLocalRoutes(localRoutes);
        updateRaParams(params);
    }

    private void updateLocalRoutes(ArrayList<RouteInfo> localRoutes) {
        if (localRoutes != null) {
            // TODO: Compare with mLastLocalRoutes and take appropriate
            // appropriate action on the difference between the two.

            if (!localRoutes.isEmpty()) {
                try {
                    mNMService.addInterfaceToLocalNetwork(mIfName, localRoutes);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to add IPv6 routes to local table: ", e);
                }
            }
        } else {
            if (mLastLocalRoutes != null && !mLastLocalRoutes.isEmpty()) {
                try {
                    final int removalFailures =
                            mNMService.removeRoutesFromLocalNetwork(mLastLocalRoutes);
                    if (removalFailures > 0) {
                        Log.e(TAG,
                                String.format("Failed to remove %d IPv6 routes from local table.",
                                removalFailures));
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to remove IPv6 routes from local table: ", e);
                }
            }
        }

        mLastLocalRoutes = localRoutes;
    }

    private void updateRaParams(RaParams params) {
        if (mRaDaemon != null) {
            HashSet<IpPrefix> deprecated = null;

            if (mLastRaParams != null) {
                deprecated = new HashSet<>();

                for (IpPrefix ipp : mLastRaParams.prefixes) {
                    if (params == null || !params.prefixes.contains(ipp)) {
                        deprecated.add(ipp);
                    }
                }
            }

            // Currently, we send spurious RAs (5) whenever there's any update.
            // TODO: Compare params with mLastParams to avoid spurious updates.
            mRaDaemon.buildNewRa(params, deprecated);
        }

        mLastRaParams = params;
    }
}
