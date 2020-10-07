/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.util.PrefixUtils.asIpPrefix;

import static java.util.Arrays.asList;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.Network;
import android.net.ip.IpServer;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * This class coordinate IP addresses conflict problem.
 *
 * Tethering downstream IP addresses may conflict with network assigned addresses. This
 * coordinator is responsible for recording all of network assigned addresses and dispatched
 * free address to downstream interfaces.
 *
 * This class is not thread-safe and should be accessed on the same tethering internal thread.
 * @hide
 */
public class PrivateAddressCoordinator {
    public static final int PREFIX_LENGTH = 24;

    private static final int MAX_UBYTE = 256;
    private static final int BYTE_MASK = 0xff;
    private static final byte DEFAULT_ID = (byte) 42;

    // Upstream monitor would be stopped when tethering is down. When tethering restart, downstream
    // address may be requested before coordinator get current upstream notification. To ensure
    // coordinator do not select conflict downstream prefix, mUpstreamPrefixMap would not be cleared
    // when tethering is down. Instead tethering would remove all deprecated upstreams from
    // mUpstreamPrefixMap when tethering is starting. See #maybeRemoveDeprecatedUpstreams().
    private final ArrayMap<Network, List<IpPrefix>> mUpstreamPrefixMap;
    private final ArraySet<IpServer> mDownstreams;
    // IANA has reserved the following three blocks of the IP address space for private intranets:
    // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
    // Tethering use 192.168.0.0/16 that has 256 contiguous class C network numbers.
    private static final String DEFAULT_TETHERING_PREFIX = "192.168.0.0/16";
    private static final String LEGACY_WIFI_P2P_IFACE_ADDRESS = "192.168.49.1/24";
    private static final String LEGACY_BLUETOOTH_IFACE_ADDRESS = "192.168.44.1/24";
    private final IpPrefix mTetheringPrefix;
    private final ConnectivityManager mConnectivityMgr;
    private final TetheringConfiguration mConfig;
    // keyed by downstream type(TetheringManager.TETHERING_*).
    private final SparseArray<LinkAddress> mCachedAddresses;

    public PrivateAddressCoordinator(Context context, TetheringConfiguration config) {
        mDownstreams = new ArraySet<>();
        mUpstreamPrefixMap = new ArrayMap<>();
        mTetheringPrefix = new IpPrefix(DEFAULT_TETHERING_PREFIX);
        mConnectivityMgr = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mConfig = config;
        mCachedAddresses = new SparseArray<>();
        // Reserved static addresses for bluetooth and wifi p2p.
        mCachedAddresses.put(TETHERING_BLUETOOTH, new LinkAddress(LEGACY_BLUETOOTH_IFACE_ADDRESS));
        mCachedAddresses.put(TETHERING_WIFI_P2P, new LinkAddress(LEGACY_WIFI_P2P_IFACE_ADDRESS));
    }

    /**
     * Record a new upstream IpPrefix which may conflict with tethering downstreams.
     * The downstreams will be notified if a conflict is found. When updateUpstreamPrefix is called,
     * UpstreamNetworkState must have an already populated LinkProperties.
     */
    public void updateUpstreamPrefix(final UpstreamNetworkState ns) {
        // Do not support VPN as upstream. Normally, networkCapabilities is not expected to be null,
        // but just checking to be sure.
        if (ns.networkCapabilities != null && ns.networkCapabilities.hasTransport(TRANSPORT_VPN)) {
            removeUpstreamPrefix(ns.network);
            return;
        }

        final ArrayList<IpPrefix> ipv4Prefixes = getIpv4Prefixes(
                ns.linkProperties.getAllLinkAddresses());
        if (ipv4Prefixes.isEmpty()) {
            removeUpstreamPrefix(ns.network);
            return;
        }

        mUpstreamPrefixMap.put(ns.network, ipv4Prefixes);
        handleMaybePrefixConflict(ipv4Prefixes);
    }

    private ArrayList<IpPrefix> getIpv4Prefixes(final List<LinkAddress> linkAddresses) {
        final ArrayList<IpPrefix> list = new ArrayList<>();
        for (LinkAddress address : linkAddresses) {
            if (!address.isIpv4()) continue;

            list.add(asIpPrefix(address));
        }

        return list;
    }

    private void handleMaybePrefixConflict(final List<IpPrefix> prefixes) {
        for (IpServer downstream : mDownstreams) {
            final IpPrefix target = getDownstreamPrefix(downstream);
            if (target == null) continue;

            for (IpPrefix source : prefixes) {
                if (isConflictPrefix(source, target)) {
                    downstream.sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
                    break;
                }
            }
        }
    }

    /** Remove IpPrefix records corresponding to input network. */
    public void removeUpstreamPrefix(final Network network) {
        mUpstreamPrefixMap.remove(network);
    }

    /**
     * Maybe remove deprecated upstream records, this would be called once tethering started without
     * any exiting tethered downstream.
     */
    public void maybeRemoveDeprecatedUpstreams() {
        if (mUpstreamPrefixMap.isEmpty()) return;

        // Remove all upstreams that are no longer valid networks
        final Set<Network> toBeRemoved = new HashSet<>(mUpstreamPrefixMap.keySet());
        toBeRemoved.removeAll(asList(mConnectivityMgr.getAllNetworks()));

        mUpstreamPrefixMap.removeAll(toBeRemoved);
    }

    /**
     * Pick a random available address and mark its prefix as in use for the provided IpServer,
     * returns null if there is no available address.
     */
    @Nullable
    public LinkAddress requestDownstreamAddress(final IpServer ipServer, boolean useLastAddress) {
        if (mConfig.shouldEnableWifiP2pDedicatedIp()
                && ipServer.interfaceType() == TETHERING_WIFI_P2P) {
            return new LinkAddress(LEGACY_WIFI_P2P_IFACE_ADDRESS);
        }

        final LinkAddress cachedAddress = mCachedAddresses.get(ipServer.interfaceType());
        if (useLastAddress && cachedAddress != null
                && !isConflictWithUpstream(asIpPrefix(cachedAddress))) {
            return cachedAddress;
        }

        // Address would be 192.168.[subAddress]/24.
        final byte[] bytes = mTetheringPrefix.getRawAddress();
        final int subAddress = getRandomSubAddr();
        final int subNet = (subAddress >> 8) & BYTE_MASK;
        bytes[3] = getSanitizedAddressSuffix(subAddress, (byte) 0, (byte) 1, (byte) 0xff);
        for (int i = 0; i < MAX_UBYTE; i++) {
            final int newSubNet = (subNet + i) & BYTE_MASK;
            bytes[2] = (byte) newSubNet;

            final InetAddress addr;
            try {
                addr = InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid address, shouldn't happen.", e);
            }

            if (isConflict(new IpPrefix(addr, PREFIX_LENGTH))) continue;

            mDownstreams.add(ipServer);
            final LinkAddress newAddress = new LinkAddress(addr, PREFIX_LENGTH);
            mCachedAddresses.put(ipServer.interfaceType(), newAddress);
            return newAddress;
        }

        // No available address.
        return null;
    }

    private boolean isConflict(final IpPrefix prefix) {
        // Check whether this prefix is in use or conflict with any current upstream network.
        return isDownstreamPrefixInUse(prefix) || isConflictWithUpstream(prefix);
    }

    /** Get random sub address value. Return value is in 0 ~ 0xffff. */
    @VisibleForTesting
    public int getRandomSubAddr() {
        return ((new Random()).nextInt()) & 0xffff; // subNet is in 0 ~ 0xffff.
    }

    private byte getSanitizedAddressSuffix(final int source, byte... excluded) {
        final byte subId = (byte) (source & BYTE_MASK);
        for (byte value : excluded) {
            if (subId == value) return DEFAULT_ID;
        }

        return subId;
    }

    /** Release downstream record for IpServer. */
    public void releaseDownstream(final IpServer ipServer) {
        mDownstreams.remove(ipServer);
    }

    /** Clear current upstream prefixes records. */
    public void clearUpstreamPrefixes() {
        mUpstreamPrefixMap.clear();
    }

    private boolean isConflictWithUpstream(final IpPrefix source) {
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            final List<IpPrefix> list = mUpstreamPrefixMap.valueAt(i);
            for (IpPrefix target : list) {
                if (isConflictPrefix(source, target)) return true;
            }
        }
        return false;
    }

    private boolean isConflictPrefix(final IpPrefix prefix1, final IpPrefix prefix2) {
        if (prefix2.getPrefixLength() < prefix1.getPrefixLength()) {
            return prefix2.contains(prefix1.getAddress());
        }

        return prefix1.contains(prefix2.getAddress());
    }

    // InUse Prefixes are prefixes of mCachedAddresses which are active downstream addresses, last
    // downstream addresses(reserved for next time) and static addresses(e.g. bluetooth, wifi p2p).
    private boolean isDownstreamPrefixInUse(final IpPrefix prefix) {
        // This class always generates downstream prefixes with the same prefix length, so
        // prefixes cannot be contained in each other. They can only be equal to each other.
        for (int i = 0; i < mCachedAddresses.size(); i++) {
            if (prefix.equals(asIpPrefix(mCachedAddresses.valueAt(i)))) return true;
        }

        // IpServer may use manually-defined address (mStaticIpv4ServerAddr) which does not include
        // in mCachedAddresses.
        for (IpServer downstream : mDownstreams) {
            final IpPrefix target = getDownstreamPrefix(downstream);
            if (target == null) continue;

            if (isConflictPrefix(prefix, target)) return true;
        }

        return false;
    }

    private IpPrefix getDownstreamPrefix(final IpServer downstream) {
        final LinkAddress address = downstream.getAddress();
        if (address == null) return null;

        return asIpPrefix(address);
    }

    void dump(final IndentingPrintWriter pw) {
        pw.println("mUpstreamPrefixMap:");
        pw.increaseIndent();
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            pw.println(mUpstreamPrefixMap.keyAt(i) + " - " + mUpstreamPrefixMap.valueAt(i));
        }
        pw.decreaseIndent();

        pw.println("mDownstreams:");
        pw.increaseIndent();
        for (IpServer ipServer : mDownstreams) {
            pw.println(ipServer.interfaceType() + " - " + ipServer.getAddress());
        }
        pw.decreaseIndent();

        pw.println("mCachedAddresses:");
        pw.increaseIndent();
        for (int i = 0; i < mCachedAddresses.size(); i++) {
            pw.println(mCachedAddresses.keyAt(i) + " - " + mCachedAddresses.valueAt(i));
        }
        pw.decreaseIndent();
    }
}
