/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.net;

import android.compat.annotation.UnsupportedAppUsage;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps track of link configuration received from Netlink.
 *
 * Instances of this class are expected to be owned by subsystems such as Wi-Fi
 * or Ethernet that manage one or more network interfaces. Each interface to be
 * tracked needs its own {@code NetlinkTracker}.
 *
 * An instance of this class is constructed by passing in an interface name and
 * a callback. The owner is then responsible for registering the tracker with
 * NetworkManagementService. When the class receives update notifications from
 * the NetworkManagementService notification threads, it applies the update to
 * its local LinkProperties, and if something has changed, notifies its owner of
 * the update via the callback.
 *
 * The owner can then call {@code getLinkProperties()} in order to find out
 * what changed. If in the meantime the LinkProperties stored here have changed,
 * this class will return the current LinkProperties. Because each change
 * triggers an update callback after the change is made, the owner may get more
 * callbacks than strictly necessary (some of which may be no-ops), but will not
 * be out of sync once all callbacks have been processed.
 *
 * Threading model:
 *
 * - The owner of this class is expected to create it, register it, and call
 *   getLinkProperties or clearLinkProperties on its thread.
 * - Most of the methods in the class are inherited from BaseNetworkObserver
 *   and are called by NetworkManagementService notification threads.
 * - All accesses to mLinkProperties must be synchronized(this). All the other
 *   member variables are immutable once the object is constructed.
 *
 * This class currently tracks IPv4 and IPv6 addresses. In the future it will
 * track routes and DNS servers.
 *
 * @hide
 */
public class NetlinkTracker extends BaseNetworkObserver {

    private final String TAG;

    public interface Callback {
        public void update();
    }

    private final String mInterfaceName;
    private final Callback mCallback;
    private final LinkProperties mLinkProperties;
    private DnsServerRepository mDnsServerRepository;

    private static final boolean DBG = false;

    @UnsupportedAppUsage
    public NetlinkTracker(String iface, Callback callback) {
        TAG = "NetlinkTracker/" + iface;
        mInterfaceName = iface;
        mCallback = callback;
        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(mInterfaceName);
        mDnsServerRepository = new DnsServerRepository();
    }

    private void maybeLog(String operation, String iface, LinkAddress address) {
        if (DBG) {
            Log.d(TAG, operation + ": " + address + " on " + iface +
                    " flags " + address.getFlags() + " scope " + address.getScope());
        }
    }

    private void maybeLog(String operation, Object o) {
        if (DBG) {
            Log.d(TAG, operation + ": " + o.toString());
        }
    }

    @Override
    public void interfaceRemoved(String iface) {
        maybeLog("interfaceRemoved", iface);
        if (mInterfaceName.equals(iface)) {
            // Our interface was removed. Clear our LinkProperties and tell our owner that they are
            // now empty. Note that from the moment that the interface is removed, any further
            // interface-specific messages (e.g., RTM_DELADDR) will not reach us, because the netd
            // code that parses them will not be able to resolve the ifindex to an interface name.
            clearLinkProperties();
            mCallback.update();
        }
    }

    @Override
    public void addressUpdated(String iface, LinkAddress address) {
        if (mInterfaceName.equals(iface)) {
            maybeLog("addressUpdated", iface, address);
            boolean changed;
            synchronized (this) {
                changed = mLinkProperties.addLinkAddress(address);
            }
            if (changed) {
                mCallback.update();
            }
        }
    }

    @Override
    public void addressRemoved(String iface, LinkAddress address) {
        if (mInterfaceName.equals(iface)) {
            maybeLog("addressRemoved", iface, address);
            boolean changed;
            synchronized (this) {
                changed = mLinkProperties.removeLinkAddress(address);
            }
            if (changed) {
                mCallback.update();
            }
        }
    }

    @Override
    public void routeUpdated(RouteInfo route) {
        if (mInterfaceName.equals(route.getInterface())) {
            maybeLog("routeUpdated", route);
            boolean changed;
            synchronized (this) {
                changed = mLinkProperties.addRoute(route);
            }
            if (changed) {
                mCallback.update();
            }
        }
    }

    @Override
    public void routeRemoved(RouteInfo route) {
        if (mInterfaceName.equals(route.getInterface())) {
            maybeLog("routeRemoved", route);
            boolean changed;
            synchronized (this) {
                changed = mLinkProperties.removeRoute(route);
            }
            if (changed) {
                mCallback.update();
            }
        }
    }

    @Override
    public void interfaceDnsServerInfo(String iface, long lifetime, String[] addresses) {
        if (mInterfaceName.equals(iface)) {
            maybeLog("interfaceDnsServerInfo", Arrays.toString(addresses));
            boolean changed = mDnsServerRepository.addServers(lifetime, addresses);
            if (changed) {
                synchronized (this) {
                    mDnsServerRepository.setDnsServersOn(mLinkProperties);
                }
                mCallback.update();
            }
        }
    }

    /**
     * Returns a copy of this object's LinkProperties.
     */
    @UnsupportedAppUsage
    public synchronized LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    @UnsupportedAppUsage
    public synchronized void clearLinkProperties() {
        // Clear the repository before clearing mLinkProperties. That way, if a clear() happens
        // while interfaceDnsServerInfo() is being called, we'll end up with no DNS servers in
        // mLinkProperties, as desired.
        mDnsServerRepository = new DnsServerRepository();
        mLinkProperties.clear();
        mLinkProperties.setInterfaceName(mInterfaceName);
    }
}

/**
 * Represents a DNS server entry with an expiry time.
 *
 * Implements Comparable so DNS server entries can be sorted by lifetime, longest-lived first.
 * The ordering of entries with the same lifetime is unspecified, because given two servers with
 * identical lifetimes, we don't care which one we use, and only comparing the lifetime is much
 * faster than comparing the IP address as well.
 *
 * Note: this class has a natural ordering that is inconsistent with equals.
 */
class DnsServerEntry implements Comparable<DnsServerEntry> {
    /** The IP address of the DNS server. */
    public final InetAddress address;
    /** The time until which the DNS server may be used. A Java millisecond time as might be
      * returned by currentTimeMillis(). */
    public long expiry;

    public DnsServerEntry(InetAddress address, long expiry) throws IllegalArgumentException {
        this.address = address;
        this.expiry = expiry;
    }

    public int compareTo(DnsServerEntry other) {
        return Long.compare(other.expiry, this.expiry);
    }
}

/**
 * Tracks DNS server updates received from Netlink.
 *
 * The network may announce an arbitrary number of DNS servers in Router Advertisements at any
 * time. Each announcement has a lifetime; when the lifetime expires, the servers should not be used
 * any more. In this way, the network can gracefully migrate clients from one set of DNS servers to
 * another. Announcements can both raise and lower the lifetime, and an announcement can expire
 * servers by announcing them with a lifetime of zero.
 *
 * Typically the system will only use a small number (2 or 3; {@code NUM_CURRENT_SERVERS}) of DNS
 * servers at any given time. These are referred to as the current servers. In case all the
 * current servers expire, the class also keeps track of a larger (but limited) number of servers
 * that are promoted to current servers when the current ones expire. In order to minimize updates
 * to the rest of the system (and potentially expensive cache flushes) this class attempts to keep
 * the list of current servers constant where possible. More specifically, the list of current
 * servers is only updated if a new server is learned and there are not yet {@code
 * NUM_CURRENT_SERVERS} current servers, or if one or more of the current servers expires or is
 * pushed out of the set. Therefore, the current servers will not necessarily be the ones with the
 * highest lifetime, but the ones learned first.
 *
 * This is by design: if instead the class always preferred the servers with the highest lifetime, a
 * (misconfigured?) network where two or more routers announce more than {@code NUM_CURRENT_SERVERS}
 * unique servers would cause persistent oscillations.
 *
 * TODO: Currently servers are only expired when a new DNS update is received.
 * Update them using timers, or possibly on every notification received by NetlinkTracker.
 *
 * Threading model: run by NetlinkTracker. Methods are synchronized(this) just in case netlink
 * notifications are sent by multiple threads. If future threads use alarms to expire, those
 * alarms must also be synchronized(this).
 *
 */
class DnsServerRepository {

    /** How many DNS servers we will use. 3 is suggested by RFC 6106. */
    public static final int NUM_CURRENT_SERVERS = 3;

    /** How many DNS servers we'll keep track of, in total. */
    public static final int NUM_SERVERS = 12;

    /** Stores up to {@code NUM_CURRENT_SERVERS} DNS servers we're currently using. */
    private Set<InetAddress> mCurrentServers;

    public static final String TAG = "DnsServerRepository";

    /**
     * Stores all the DNS servers we know about, for use when the current servers expire.
     * Always sorted in order of decreasing expiry. The elements in this list are also the values
     * of mIndex, and may be elements in mCurrentServers.
     */
    private ArrayList<DnsServerEntry> mAllServers;

    /**
     * Indexes the servers so we can update their lifetimes more quickly in the common case where
     * servers are not being added, but only being refreshed.
     */
    private HashMap<InetAddress, DnsServerEntry> mIndex;

    public DnsServerRepository() {
        mCurrentServers = new HashSet();
        mAllServers = new ArrayList<DnsServerEntry>(NUM_SERVERS);
        mIndex = new HashMap<InetAddress, DnsServerEntry>(NUM_SERVERS);
    }

    /** Sets the DNS servers of the provided LinkProperties object to the current servers. */
    public synchronized void setDnsServersOn(LinkProperties lp) {
        lp.setDnsServers(mCurrentServers);
    }

    /**
     * Notifies the class of new DNS server information.
     * @param lifetime the time in seconds that the DNS servers are valid.
     * @param addresses the string representations of the IP addresses of the DNS servers to use.
     */
    public synchronized boolean addServers(long lifetime, String[] addresses) {
        // The lifetime is actually an unsigned 32-bit number, but Java doesn't have unsigned.
        // Technically 0xffffffff (the maximum) is special and means "forever", but 2^32 seconds
        // (136 years) is close enough.
        long now = System.currentTimeMillis();
        long expiry = now + 1000 * lifetime;

        // Go through the list of servers. For each one, update the entry if one exists, and
        // create one if it doesn't.
        for (String addressString : addresses) {
            InetAddress address;
            try {
                address = InetAddress.parseNumericAddress(addressString);
            } catch (IllegalArgumentException ex) {
                continue;
            }

            if (!updateExistingEntry(address, expiry)) {
                // There was no entry for this server. Create one, unless it's already expired
                // (i.e., if the lifetime is zero; it cannot be < 0 because it's unsigned).
                if (expiry > now) {
                    DnsServerEntry entry = new DnsServerEntry(address, expiry);
                    mAllServers.add(entry);
                    mIndex.put(address, entry);
                }
            }
        }

        // Sort the servers by expiry.
        Collections.sort(mAllServers);

        // Prune excess entries and update the current server list.
        return updateCurrentServers();
    }

    private synchronized boolean updateExistingEntry(InetAddress address, long expiry) {
        DnsServerEntry existing = mIndex.get(address);
        if (existing != null) {
            existing.expiry = expiry;
            return true;
        }
        return false;
    }

    private synchronized boolean updateCurrentServers() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        // Prune excess or expired entries.
        for (int i = mAllServers.size() - 1; i >= 0; i--) {
            if (i >= NUM_SERVERS || mAllServers.get(i).expiry < now) {
                DnsServerEntry removed = mAllServers.remove(i);
                mIndex.remove(removed.address);
                changed |= mCurrentServers.remove(removed.address);
            } else {
                break;
            }
        }

        // Add servers to the current set, in order of decreasing lifetime, until it has enough.
        // Prefer existing servers over new servers in order to minimize updates to the rest of the
        // system and avoid persistent oscillations.
        for (DnsServerEntry entry : mAllServers) {
            if (mCurrentServers.size() < NUM_CURRENT_SERVERS) {
                changed |= mCurrentServers.add(entry.address);
            } else {
                break;
            }
        }
        return changed;
    }
}
