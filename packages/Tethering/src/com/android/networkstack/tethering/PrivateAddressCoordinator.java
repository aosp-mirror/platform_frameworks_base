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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ip.IpServer;
import android.net.util.PrefixUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

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
    // reserved for bluetooth tethering.
    private static final int BLUETOOTH_RESERVED = 44;
    private static final byte DEFAULT_ID = (byte) 42;

    // Upstream monitor would be stopped when tethering is down. When tethering restart, downstream
    // address may be requested before coordinator get current upstream notification. To ensure
    // coordinator do not select conflict downstream prefix, mUpstreamPrefixMap would not be cleared
    // when tethering is down. Instead coordinator would remove all depcreted upstreams from
    // mUpstreamPrefixMap when tethering is starting. See #maybeRemoveDeprectedUpstreams().
    private final ArrayMap<Network, List<IpPrefix>> mUpstreamPrefixMap;
    private final ArraySet<IpServer> mDownstreams;
    // IANA has reserved the following three blocks of the IP address space for private intranets:
    // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
    // Tethering use 192.168.0.0/16 that has 256 contiguous class C network numbers.
    private static final String DEFAULT_TETHERING_PREFIX = "192.168.0.0/16";
    private final IpPrefix mTetheringPrefix;
    private final ConnectivityManager mConnectivityMgr;

    public PrivateAddressCoordinator(Context context) {
        mDownstreams = new ArraySet<>();
        mUpstreamPrefixMap = new ArrayMap<>();
        mTetheringPrefix = new IpPrefix(DEFAULT_TETHERING_PREFIX);
        mConnectivityMgr = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Record a new upstream IpPrefix which may conflict with tethering downstreams.
     * The downstreams will be notified if a conflict is found.
     */
    public void updateUpstreamPrefix(final Network network, final LinkProperties lp) {
        final ArrayList<IpPrefix> ipv4Prefixes = getIpv4Prefixes(lp.getAllLinkAddresses());
        if (ipv4Prefixes.isEmpty()) {
            removeUpstreamPrefix(network);
            return;
        }

        mUpstreamPrefixMap.put(network, ipv4Prefixes);
        handleMaybePrefixConflict(ipv4Prefixes);
    }

    private ArrayList<IpPrefix> getIpv4Prefixes(final List<LinkAddress> linkAddresses) {
        final ArrayList<IpPrefix> list = new ArrayList<>();
        for (LinkAddress address : linkAddresses) {
            if (!address.isIpv4()) continue;

            list.add(PrefixUtils.asIpPrefix(address));
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

    private void maybeRemoveDeprectedUpstreams() {
        if (!mDownstreams.isEmpty() || mUpstreamPrefixMap.isEmpty()) return;

        final ArrayList<Network> toBeRemoved = new ArrayList<>();
        List<Network> allNetworks = Arrays.asList(mConnectivityMgr.getAllNetworks());
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            final Network network = mUpstreamPrefixMap.keyAt(i);
            if (!allNetworks.contains(network)) toBeRemoved.add(network);
        }

        mUpstreamPrefixMap.removeAll(toBeRemoved);
    }

    /**
     * Pick a random available address and mark its prefix as in use for the provided IpServer,
     * returns null if there is no available address.
     */
    @Nullable
    public LinkAddress requestDownstreamAddress(final IpServer ipServer) {
        maybeRemoveDeprectedUpstreams();

        // Address would be 192.168.[subAddress]/24.
        final byte[] bytes = mTetheringPrefix.getRawAddress();
        final int subAddress = getRandomSubAddr();
        final int subNet = (subAddress >> 8) & BYTE_MASK;
        bytes[3] = getSanitizedAddressSuffix(subAddress, (byte) 0, (byte) 1, (byte) 0xff);
        for (int i = 0; i < MAX_UBYTE; i++) {
            final int newSubNet = (subNet + i) & BYTE_MASK;
            if (newSubNet == BLUETOOTH_RESERVED) continue;

            bytes[2] = (byte) newSubNet;
            final InetAddress addr;
            try {
                addr = InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid address, shouldn't happen.", e);
            }

            final IpPrefix prefix = new IpPrefix(addr, PREFIX_LENGTH);
            // Check whether this prefix is in use.
            if (isDownstreamPrefixInUse(prefix)) continue;
            // Check whether this prefix is conflict with any current upstream network.
            if (isConflictWithUpstream(prefix)) continue;

            mDownstreams.add(ipServer);
            return new LinkAddress(addr, PREFIX_LENGTH);
        }

        // No available address.
        return null;
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

    private boolean isDownstreamPrefixInUse(final IpPrefix source) {
        // This class always generates downstream prefixes with the same prefix length, so
        // prefixes cannot be contained in each other. They can only be equal to each other.
        for (IpServer downstream : mDownstreams) {
            final IpPrefix prefix = getDownstreamPrefix(downstream);
            if (source.equals(prefix)) return true;
        }
        return false;
    }

    private IpPrefix getDownstreamPrefix(final IpServer downstream) {
        final LinkAddress address = downstream.getAddress();
        if (address == null) return null;

        return PrefixUtils.asIpPrefix(address);
    }

    void dump(final IndentingPrintWriter pw) {
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
    }
}
