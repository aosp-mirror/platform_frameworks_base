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

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.util.Log;

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

    private static final boolean DBG = true;

    public NetlinkTracker(String iface, Callback callback) {
        TAG = "NetlinkTracker/" + iface;
        mInterfaceName = iface;
        mCallback = callback;
        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(mInterfaceName);
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

    /**
     * Returns a copy of this object's LinkProperties.
     */
    public synchronized LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    public synchronized void clearLinkProperties() {
        mLinkProperties.clear();
        mLinkProperties.setInterfaceName(mInterfaceName);
    }
}
