/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import android.net.ProxyProperties;
import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Describes the properties of a network link.
 *
 * A link represents a connection to a network.
 * It may have multiple addresses and multiple gateways,
 * multiple dns servers but only one http proxy.
 *
 * Because it's a single network, the dns's
 * are interchangeable and don't need associating with
 * particular addresses.  The gateways similarly don't
 * need associating with particular addresses.
 *
 * A dual stack interface works fine in this model:
 * each address has it's own prefix length to describe
 * the local network.  The dns servers all return
 * both v4 addresses and v6 addresses regardless of the
 * address family of the server itself (rfc4213) and we
 * don't care which is used.  The gateways will be
 * selected based on the destination address and the
 * source address has no relavence.
 * @hide
 */
public class LinkProperties implements Parcelable {

    String mIfaceName;
    private Collection<LinkAddress> mLinkAddresses;
    private Collection<InetAddress> mDnses;
    private Collection<RouteInfo> mRoutes;
    private ProxyProperties mHttpProxy;

    public LinkProperties() {
        clear();
    }

    // copy constructor instead of clone
    public LinkProperties(LinkProperties source) {
        if (source != null) {
            mIfaceName = source.getInterfaceName();
            mLinkAddresses = source.getLinkAddresses();
            mDnses = source.getDnses();
            mRoutes = source.getRoutes();
            mHttpProxy = (source.getHttpProxy() == null)  ?
                null : new ProxyProperties(source.getHttpProxy());
        }
    }

    public void setInterfaceName(String iface) {
        mIfaceName = iface;
    }

    public String getInterfaceName() {
        return mIfaceName;
    }

    public Collection<InetAddress> getAddresses() {
        Collection<InetAddress> addresses = new ArrayList<InetAddress>();
        for (LinkAddress linkAddress : mLinkAddresses) {
            addresses.add(linkAddress.getAddress());
        }
        return Collections.unmodifiableCollection(addresses);
    }

    public void addLinkAddress(LinkAddress address) {
        if (address != null) mLinkAddresses.add(address);
    }

    public Collection<LinkAddress> getLinkAddresses() {
        return Collections.unmodifiableCollection(mLinkAddresses);
    }

    public void addDns(InetAddress dns) {
        if (dns != null) mDnses.add(dns);
    }

    public Collection<InetAddress> getDnses() {
        return Collections.unmodifiableCollection(mDnses);
    }

    public void addRoute(RouteInfo route) {
        if (route != null) mRoutes.add(route);
    }
    public Collection<RouteInfo> getRoutes() {
        return Collections.unmodifiableCollection(mRoutes);
    }

    public void setHttpProxy(ProxyProperties proxy) {
        mHttpProxy = proxy;
    }
    public ProxyProperties getHttpProxy() {
        return mHttpProxy;
    }

    public void clear() {
        mIfaceName = null;
        mLinkAddresses = new ArrayList<LinkAddress>();
        mDnses = new ArrayList<InetAddress>();
        mRoutes = new ArrayList<RouteInfo>();
        mHttpProxy = null;
    }

    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        String ifaceName = (mIfaceName == null ? "" : "InterfaceName: " + mIfaceName + " ");

        String linkAddresses = "LinkAddresses: [";
        for (LinkAddress addr : mLinkAddresses) linkAddresses += addr.toString() + ",";
        linkAddresses += "] ";

        String dns = "DnsAddresses: [";
        for (InetAddress addr : mDnses) dns += addr.getHostAddress() + ",";
        dns += "] ";

        String routes = "Routes: [";
        for (RouteInfo route : mRoutes) routes += route.toString() + ",";
        routes += "] ";
        String proxy = (mHttpProxy == null ? "" : "HttpProxy: " + mHttpProxy.toString() + " ");

        return ifaceName + linkAddresses + routes + dns + proxy;
    }


    @Override
    /**
     * Compares this {@code LinkProperties} instance against the target
     * LinkProperties in {@code obj}. Two LinkPropertieses are equal if
     * all their fields are equal in values.
     *
     * For collection fields, such as mDnses, containsAll() is used to check
     * if two collections contains the same elements, independent of order.
     * There are two thoughts regarding containsAll()
     * 1. Duplicated elements. eg, (A, B, B) and (A, A, B) are equal.
     * 2. Worst case performance is O(n^2).
     *
     * @param obj the object to be tested for equality.
     * @return {@code true} if both objects are equal, {@code false} otherwise.
     */
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof LinkProperties)) return false;

        boolean sameAddresses;
        boolean sameDnses;
        boolean sameRoutes;

        LinkProperties target = (LinkProperties) obj;

        Collection<InetAddress> targetAddresses = target.getAddresses();
        Collection<InetAddress> sourceAddresses = getAddresses();
        sameAddresses = (sourceAddresses.size() == targetAddresses.size()) ?
                sourceAddresses.containsAll(targetAddresses) : false;

        Collection<InetAddress> targetDnses = target.getDnses();
        sameDnses = (mDnses.size() == targetDnses.size()) ?
                mDnses.containsAll(targetDnses) : false;

        Collection<RouteInfo> targetRoutes = target.getRoutes();
        sameRoutes = (mRoutes.size() == targetRoutes.size()) ?
                mRoutes.containsAll(targetRoutes) : false;

        return
            sameAddresses && sameDnses && sameRoutes
            && TextUtils.equals(getInterfaceName(), target.getInterfaceName())
            && (getHttpProxy() == null ? target.getHttpProxy() == null :
                getHttpProxy().equals(target.getHttpProxy()));
    }

    @Override
    /**
     * generate hashcode based on significant fields
     * Equal objects must produce the same hash code, while unequal objects
     * may have the same hash codes.
     */
    public int hashCode() {
        return ((null == mIfaceName) ? 0 : mIfaceName.hashCode()
                + mLinkAddresses.size() * 31
                + mDnses.size() * 37
                + mRoutes.size() * 41
                + ((null == mHttpProxy) ? 0 : mHttpProxy.hashCode()));
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getInterfaceName());
        dest.writeInt(mLinkAddresses.size());
        for(LinkAddress linkAddress : mLinkAddresses) {
            dest.writeParcelable(linkAddress, flags);
        }

        dest.writeInt(mDnses.size());
        for(InetAddress d : mDnses) {
            dest.writeByteArray(d.getAddress());
        }

        dest.writeInt(mRoutes.size());
        for(RouteInfo route : mRoutes) {
            dest.writeParcelable(route, flags);
        }

        if (mHttpProxy != null) {
            dest.writeByte((byte)1);
            dest.writeParcelable(mHttpProxy, flags);
        } else {
            dest.writeByte((byte)0);
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<LinkProperties> CREATOR =
        new Creator<LinkProperties>() {
            public LinkProperties createFromParcel(Parcel in) {
                LinkProperties netProp = new LinkProperties();
                String iface = in.readString();
                if (iface != null) {
                    try {
                        netProp.setInterfaceName(iface);
                    } catch (Exception e) {
                        return null;
                    }
                }
                int addressCount = in.readInt();
                for (int i=0; i<addressCount; i++) {
                    netProp.addLinkAddress((LinkAddress)in.readParcelable(null));
                }
                addressCount = in.readInt();
                for (int i=0; i<addressCount; i++) {
                    try {
                        netProp.addDns(InetAddress.getByAddress(in.createByteArray()));
                    } catch (UnknownHostException e) { }
                }
                addressCount = in.readInt();
                for (int i=0; i<addressCount; i++) {
                    netProp.addRoute((RouteInfo)in.readParcelable(null));
                }
                if (in.readByte() == 1) {
                    netProp.setHttpProxy((ProxyProperties)in.readParcelable(null));
                }
                return netProp;
            }

            public LinkProperties[] newArray(int size) {
                return new LinkProperties[size];
            }
        };
}
