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

import static com.android.net.module.util.Inet4AddressUtils.inet4AddressToIntHTH;
import static com.android.net.module.util.Inet4AddressUtils.intToInet4AddressHTH;
import static com.android.net.module.util.Inet4AddressUtils.prefixLengthToV4NetmaskIntHTH;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
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

    // Upstream monitor would be stopped when tethering is down. When tethering restart, downstream
    // address may be requested before coordinator get current upstream notification. To ensure
    // coordinator do not select conflict downstream prefix, mUpstreamPrefixMap would not be cleared
    // when tethering is down. Instead tethering would remove all deprecated upstreams from
    // mUpstreamPrefixMap when tethering is starting. See #maybeRemoveDeprecatedUpstreams().
    private final ArrayMap<Network, List<IpPrefix>> mUpstreamPrefixMap;
    private final ArraySet<IpServer> mDownstreams;
    private static final String LEGACY_WIFI_P2P_IFACE_ADDRESS = "192.168.49.1/24";
    private static final String LEGACY_BLUETOOTH_IFACE_ADDRESS = "192.168.44.1/24";
    private final List<IpPrefix> mTetheringPrefixes;
    private final ConnectivityManager mConnectivityMgr;
    private final TetheringConfiguration mConfig;
    // keyed by downstream type(TetheringManager.TETHERING_*).
    private final SparseArray<LinkAddress> mCachedAddresses;

    public PrivateAddressCoordinator(Context context, TetheringConfiguration config) {
        mDownstreams = new ArraySet<>();
        mUpstreamPrefixMap = new ArrayMap<>();
        mConnectivityMgr = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mConfig = config;
        mCachedAddresses = new SparseArray<>();
        // Reserved static addresses for bluetooth and wifi p2p.
        mCachedAddresses.put(TETHERING_BLUETOOTH, new LinkAddress(LEGACY_BLUETOOTH_IFACE_ADDRESS));
        mCachedAddresses.put(TETHERING_WIFI_P2P, new LinkAddress(LEGACY_WIFI_P2P_IFACE_ADDRESS));

        mTetheringPrefixes = new ArrayList<>(Arrays.asList(new IpPrefix("192.168.0.0/16")));
        if (config.isSelectAllPrefixRangeEnabled()) {
            mTetheringPrefixes.add(new IpPrefix("172.16.0.0/12"));
            mTetheringPrefixes.add(new IpPrefix("10.0.0.0/8"));
        }
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
            mDownstreams.add(ipServer);
            return cachedAddress;
        }

        for (IpPrefix prefixRange : mTetheringPrefixes) {
            final LinkAddress newAddress = chooseDownstreamAddress(prefixRange);
            if (newAddress != null) {
                mDownstreams.add(ipServer);
                mCachedAddresses.put(ipServer.interfaceType(), newAddress);
                return newAddress;
            }
        }

        // No available address.
        return null;
    }

    private int getPrefixBaseAddress(final IpPrefix prefix) {
        return inet4AddressToIntHTH((Inet4Address) prefix.getAddress());
    }

    /**
     * Check whether input prefix conflict with upstream prefixes or in-use downstream prefixes.
     * If yes, return one of them.
     */
    private IpPrefix getConflictPrefix(final IpPrefix prefix) {
        final IpPrefix upstream = getConflictWithUpstream(prefix);
        if (upstream != null) return upstream;

        return getInUseDownstreamPrefix(prefix);
    }

    // Get the next non-conflict sub prefix. E.g: To get next sub prefix from 10.0.0.0/8, if the
    // previously selected prefix is 10.20.42.0/24(subPrefix: 0.20.42.0) and the conflicting prefix
    // is 10.16.0.0/20 (10.16.0.0 ~ 10.16.15.255), then the max address under subPrefix is
    // 0.16.15.255 and the next subPrefix is 0.16.16.255/24 (0.16.15.255 + 0.0.1.0).
    // Note: the sub address 0.0.0.255 here is fine to be any value that it will be replaced as
    // selected random sub address later.
    private int getNextSubPrefix(final IpPrefix conflictPrefix, final int prefixRangeMask) {
        final int suffixMask = ~prefixLengthToV4NetmaskIntHTH(conflictPrefix.getPrefixLength());
        // The largest offset within the prefix assignment block that still conflicts with
        // conflictPrefix.
        final int maxConflict =
                (getPrefixBaseAddress(conflictPrefix) | suffixMask) & ~prefixRangeMask;

        final int prefixMask = prefixLengthToV4NetmaskIntHTH(PREFIX_LENGTH);
        // Pick a sub prefix a full prefix (1 << (32 - PREFIX_LENGTH) addresses) greater than
        // maxConflict. This ensures that the selected prefix never overlaps with conflictPrefix.
        // There is no need to mask the result with PREFIX_LENGTH bits because this is done by
        // findAvailablePrefixFromRange when it constructs the prefix.
        return maxConflict + (1 << (32 - PREFIX_LENGTH));
    }

    private LinkAddress chooseDownstreamAddress(final IpPrefix prefixRange) {
        // The netmask of the prefix assignment block (e.g., 0xfff00000 for 172.16.0.0/12).
        final int prefixRangeMask = prefixLengthToV4NetmaskIntHTH(prefixRange.getPrefixLength());

        // The zero address in the block (e.g., 0xac100000 for 172.16.0.0/12).
        final int baseAddress = getPrefixBaseAddress(prefixRange);

        // The subnet mask corresponding to PREFIX_LENGTH.
        final int prefixMask = prefixLengthToV4NetmaskIntHTH(PREFIX_LENGTH);

        // The offset within prefixRange of a randomly-selected prefix of length PREFIX_LENGTH.
        // This may not be the prefix of the address returned by this method:
        // - If it is already in use, the method will return an address in another prefix.
        // - If all prefixes within prefixRange are in use, the method will return null. For
        // example, for a /24 prefix within 172.26.0.0/12, this will be a multiple of 256 in
        // [0, 1048576). In other words, a random 32-bit number with mask 0x000fff00.
        //
        // prefixRangeMask is required to ensure no wrapping. For example, consider:
        // - prefixRange 127.0.0.0/8
        // - randomPrefixStart 127.255.255.0
        // - A conflicting prefix of 127.255.254.0/23
        // In this case without prefixRangeMask, getNextSubPrefix would return 128.0.0.0, which
        // means the "start < end" check in findAvailablePrefixFromRange would not reject the prefix
        // because Java doesn't have unsigned integers, so 128.0.0.0 = 0x80000000 = -2147483648
        // is less than 127.0.0.0 = 0x7f000000 = 2130706432.
        //
        // Additionally, it makes debug output easier to read by making the numbers smaller.
        final int randomPrefixStart = getRandomInt() & ~prefixRangeMask & prefixMask;

        // A random offset within the prefix. Used to determine the local address once the prefix
        // is selected. It does not result in an IPv4 address ending in .0, .1, or .255
        // For a PREFIX_LENGTH of 255, this is a number between 2 and 254.
        final int subAddress = getSanitizedSubAddr(~prefixMask);

        // Find a prefix length PREFIX_LENGTH between randomPrefixStart and the end of the block,
        // such that the prefix does not conflict with any upstream.
        IpPrefix downstreamPrefix = findAvailablePrefixFromRange(
                 randomPrefixStart, (~prefixRangeMask) + 1, baseAddress, prefixRangeMask);
        if (downstreamPrefix != null) return getLinkAddress(downstreamPrefix, subAddress);

        // If that failed, do the same, but between 0 and randomPrefixStart.
        downstreamPrefix = findAvailablePrefixFromRange(
                0, randomPrefixStart, baseAddress, prefixRangeMask);

        return getLinkAddress(downstreamPrefix, subAddress);
    }

    private LinkAddress getLinkAddress(final IpPrefix prefix, final int subAddress) {
        if (prefix == null) return null;

        final InetAddress address = intToInet4AddressHTH(getPrefixBaseAddress(prefix) | subAddress);
        return new LinkAddress(address, PREFIX_LENGTH);
    }

    private IpPrefix findAvailablePrefixFromRange(final int start, final int end,
            final int baseAddress, final int prefixRangeMask) {
        int newSubPrefix = start;
        while (newSubPrefix < end) {
            final InetAddress address = intToInet4AddressHTH(baseAddress | newSubPrefix);
            final IpPrefix prefix = new IpPrefix(address, PREFIX_LENGTH);

            final IpPrefix conflictPrefix = getConflictPrefix(prefix);

            if (conflictPrefix == null) return prefix;

            newSubPrefix = getNextSubPrefix(conflictPrefix, prefixRangeMask);
        }

        return null;
    }

    /** Get random int which could be used to generate random address. */
    @VisibleForTesting
    public int getRandomInt() {
        return (new Random()).nextInt();
    }

    /** Get random subAddress and avoid selecting x.x.x.0, x.x.x.1 and x.x.x.255 address. */
    private int getSanitizedSubAddr(final int subAddrMask) {
        final int randomSubAddr = getRandomInt() & subAddrMask;
        // If prefix length > 30, the selecting speace would be less than 4 which may be hard to
        // avoid 3 consecutive address.
        if (PREFIX_LENGTH > 30) return randomSubAddr;

        // TODO: maybe it is not necessary to avoid .0, .1 and .255 address because tethering
        // address would not be conflicted. This code only works because PREFIX_LENGTH is not longer
        // than 24
        final int candidate = randomSubAddr & 0xff;
        if (candidate == 0 || candidate == 1 || candidate == 255) {
            return (randomSubAddr & 0xfffffffc) + 2;
        }

        return randomSubAddr;
    }

    /** Release downstream record for IpServer. */
    public void releaseDownstream(final IpServer ipServer) {
        mDownstreams.remove(ipServer);
    }

    /** Clear current upstream prefixes records. */
    public void clearUpstreamPrefixes() {
        mUpstreamPrefixMap.clear();
    }

    private IpPrefix getConflictWithUpstream(final IpPrefix prefix) {
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            final List<IpPrefix> list = mUpstreamPrefixMap.valueAt(i);
            for (IpPrefix upstream : list) {
                if (isConflictPrefix(prefix, upstream)) return upstream;
            }
        }
        return null;
    }

    private boolean isConflictWithUpstream(final IpPrefix prefix) {
        return getConflictWithUpstream(prefix) != null;
    }

    private boolean isConflictPrefix(final IpPrefix prefix1, final IpPrefix prefix2) {
        if (prefix2.getPrefixLength() < prefix1.getPrefixLength()) {
            return prefix2.contains(prefix1.getAddress());
        }

        return prefix1.contains(prefix2.getAddress());
    }

    // InUse Prefixes are prefixes of mCachedAddresses which are active downstream addresses, last
    // downstream addresses(reserved for next time) and static addresses(e.g. bluetooth, wifi p2p).
    private IpPrefix getInUseDownstreamPrefix(final IpPrefix prefix) {
        for (int i = 0; i < mCachedAddresses.size(); i++) {
            final IpPrefix downstream = asIpPrefix(mCachedAddresses.valueAt(i));
            if (isConflictPrefix(prefix, downstream)) return downstream;
        }

        // IpServer may use manually-defined address (mStaticIpv4ServerAddr) which does not include
        // in mCachedAddresses.
        for (IpServer downstream : mDownstreams) {
            final IpPrefix target = getDownstreamPrefix(downstream);

            if (isConflictPrefix(prefix, target)) return target;
        }

        return null;
    }

    @NonNull
    private IpPrefix getDownstreamPrefix(final IpServer downstream) {
        final LinkAddress address = downstream.getAddress();

        return asIpPrefix(address);
    }

    void dump(final IndentingPrintWriter pw) {
        pw.println("mTetheringPrefixes:");
        pw.increaseIndent();
        for (IpPrefix prefix : mTetheringPrefixes) {
            pw.println(prefix);
        }
        pw.decreaseIndent();

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
