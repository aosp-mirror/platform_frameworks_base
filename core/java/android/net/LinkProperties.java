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

import android.net.ProxyInfo;
import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;

/**
 * Describes the properties of a network link.
 *
 * A link represents a connection to a network.
 * It may have multiple addresses and multiple gateways,
 * multiple dns servers but only one http proxy and one
 * network interface.
 *
 * Note that this is just a holder of data.  Modifying it
 * does not affect live networks.
 *
 */
public class LinkProperties implements Parcelable {
    // The interface described by the network link.
    private String mIfaceName;
    private ArrayList<LinkAddress> mLinkAddresses = new ArrayList<LinkAddress>();
    private ArrayList<InetAddress> mDnses = new ArrayList<InetAddress>();
    private String mDomains;
    private ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
    private ProxyInfo mHttpProxy;
    private int mMtu;

    // Stores the properties of links that are "stacked" above this link.
    // Indexed by interface name to allow modification and to prevent duplicates being added.
    private Hashtable<String, LinkProperties> mStackedLinks =
        new Hashtable<String, LinkProperties>();

    // @hide
    public static class CompareResult<T> {
        public Collection<T> removed = new ArrayList<T>();
        public Collection<T> added = new ArrayList<T>();

        @Override
        public String toString() {
            String retVal = "removed=[";
            for (T addr : removed) retVal += addr.toString() + ",";
            retVal += "] added=[";
            for (T addr : added) retVal += addr.toString() + ",";
            retVal += "]";
            return retVal;
        }
    }

    public LinkProperties() {
    }

    public LinkProperties(LinkProperties source) {
        if (source != null) {
            mIfaceName = source.getInterfaceName();
            for (LinkAddress l : source.getLinkAddresses()) mLinkAddresses.add(l);
            for (InetAddress i : source.getDnses()) mDnses.add(i);
            mDomains = source.getDomains();
            for (RouteInfo r : source.getRoutes()) mRoutes.add(r);
            mHttpProxy = (source.getHttpProxy() == null)  ?
                    null : new ProxyInfo(source.getHttpProxy());
            for (LinkProperties l: source.mStackedLinks.values()) {
                addStackedLink(l);
            }
            setMtu(source.getMtu());
        }
    }

    /**
     * Sets the interface name for this link.  All {@link RouteInfo} already set for this
     * will have their interface changed to match this new value.
     *
     * @param iface The name of the network interface used for this link.
     */
    public void setInterfaceName(String iface) {
        mIfaceName = iface;
        ArrayList<RouteInfo> newRoutes = new ArrayList<RouteInfo>(mRoutes.size());
        for (RouteInfo route : mRoutes) {
            newRoutes.add(routeWithInterface(route));
        }
        mRoutes = newRoutes;
    }

    /**
     * Gets the interface name for this link.  May be {@code null} if not set.
     *
     * @return The interface name set for this link or {@code null}.
     */
    public String getInterfaceName() {
        return mIfaceName;
    }

    // @hide
    public Collection<String> getAllInterfaceNames() {
        Collection interfaceNames = new ArrayList<String>(mStackedLinks.size() + 1);
        if (mIfaceName != null) interfaceNames.add(new String(mIfaceName));
        for (LinkProperties stacked: mStackedLinks.values()) {
            interfaceNames.addAll(stacked.getAllInterfaceNames());
        }
        return interfaceNames;
    }

    /**
     * Returns all the addresses on this link.  We often think of a link having a single address,
     * however, particularly with Ipv6 several addresses are typical.  Note that the
     * {@code LinkProperties} actually contains {@link LinkAddress} objects which also include
     * prefix lengths for each address.  This is a simplified utility alternative to
     * {@link LinkProperties#getLinkAddresses}.
     *
     * @return An umodifiable {@link Collection} of {@link InetAddress} for this link.
     * @hide
     */
    public Collection<InetAddress> getAddresses() {
        Collection<InetAddress> addresses = new ArrayList<InetAddress>();
        for (LinkAddress linkAddress : mLinkAddresses) {
            addresses.add(linkAddress.getAddress());
        }
        return Collections.unmodifiableCollection(addresses);
    }

    /**
     * Returns all the addresses on this link and all the links stacked above it.
     * @hide
     */
    public Collection<InetAddress> getAllAddresses() {
        Collection<InetAddress> addresses = new ArrayList<InetAddress>();
        for (LinkAddress linkAddress : mLinkAddresses) {
            addresses.add(linkAddress.getAddress());
        }
        for (LinkProperties stacked: mStackedLinks.values()) {
            addresses.addAll(stacked.getAllAddresses());
        }
        return addresses;
    }

    private int findLinkAddressIndex(LinkAddress address) {
        for (int i = 0; i < mLinkAddresses.size(); i++) {
            if (mLinkAddresses.get(i).isSameAddressAs(address)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds a {@link LinkAddress} to this {@code LinkProperties} if a {@link LinkAddress} of the
     * same address/prefix does not already exist.  If it does exist it is replaced.
     * @param address The {@code LinkAddress} to add.
     * @return true if {@code address} was added or updated, false otherwise.
     */
    public boolean addLinkAddress(LinkAddress address) {
        if (address == null) {
            return false;
        }
        int i = findLinkAddressIndex(address);
        if (i < 0) {
            // Address was not present. Add it.
            mLinkAddresses.add(address);
            return true;
        } else if (mLinkAddresses.get(i).equals(address)) {
            // Address was present and has same properties. Do nothing.
            return false;
        } else {
            // Address was present and has different properties. Update it.
            mLinkAddresses.set(i, address);
            return true;
        }
    }

    /**
     * Removes a {@link LinkAddress} from this {@code LinkProperties}.  Specifically, matches
     * and {@link LinkAddress} with the same address and prefix.
     *
     * @param toRemove A {@link LinkAddress} specifying the address to remove.
     * @return true if the address was removed, false if it did not exist.
     */
    public boolean removeLinkAddress(LinkAddress toRemove) {
        int i = findLinkAddressIndex(toRemove);
        if (i >= 0) {
            mLinkAddresses.remove(i);
            return true;
        }
        return false;
    }

    /**
     * Returns all the {@link LinkAddress} on this link.  Typically a link will have
     * one IPv4 address and one or more IPv6 addresses.
     *
     * @return An unmodifiable {@link Collection} of {@link LinkAddress} for this link.
     */
    public Collection<LinkAddress> getLinkAddresses() {
        return Collections.unmodifiableCollection(mLinkAddresses);
    }

    /**
     * Returns all the addresses on this link and all the links stacked above it.
     * @hide
     */
    public Collection<LinkAddress> getAllLinkAddresses() {
        Collection<LinkAddress> addresses = new ArrayList<LinkAddress>();
        addresses.addAll(mLinkAddresses);
        for (LinkProperties stacked: mStackedLinks.values()) {
            addresses.addAll(stacked.getAllLinkAddresses());
        }
        return addresses;
    }

    /**
     * Replaces the {@link LinkAddress} in this {@code LinkProperties} with
     * the given {@link Collection} of {@link LinkAddress}.
     *
     * @param addresses The {@link Collection} of {@link LinkAddress} to set in this
     *                  object.
     */
    public void setLinkAddresses(Collection<LinkAddress> addresses) {
        mLinkAddresses.clear();
        for (LinkAddress address: addresses) {
            addLinkAddress(address);
        }
    }

    /**
     * Adds the given {@link InetAddress} to the list of DNS servers.
     *
     * @param dns The {@link InetAddress} to add to the list of DNS servers.
     */
    public void addDns(InetAddress dns) {
        if (dns != null) mDnses.add(dns);
    }

    /**
     * Returns all the {@link LinkAddress} for DNS servers on this link.
     *
     * @return An umodifiable {@link Collection} of {@link InetAddress} for DNS servers on
     *         this link.
     */
    public Collection<InetAddress> getDnses() {
        return Collections.unmodifiableCollection(mDnses);
    }

    /**
     * Sets the DNS domain search path used on this link.
     *
     * @param domains A {@link String} listing in priority order the comma separated
     *                domains to search when resolving host names on this link.
     */
    public void setDomains(String domains) {
        mDomains = domains;
    }

    /**
     * Get the DNS domains search path set for this link.
     *
     * @return A {@link String} containing the comma separated domains to search when resolving
     *         host names on this link.
     */
    public String getDomains() {
        return mDomains;
    }

    /**
     * Sets the Maximum Transmission Unit size to use on this link.  This should not be used
     * unless the system default (1500) is incorrect.  Values less than 68 or greater than
     * 10000 will be ignored.
     *
     * @param mtu The MTU to use for this link.
     * @hide
     */
    public void setMtu(int mtu) {
        mMtu = mtu;
    }

    /**
     * Gets any non-default MTU size set for this link.  Note that if the default is being used
     * this will return 0.
     *
     * @return The mtu value set for this link.
     * @hide
     */
    public int getMtu() {
        return mMtu;
    }

    private RouteInfo routeWithInterface(RouteInfo route) {
        return new RouteInfo(
            route.getDestination(),
            route.getGateway(),
            mIfaceName);
    }

    /**
     * Adds a {@link RouteInfo} to this {@code LinkProperties}.  If the {@link RouteInfo}
     * had an interface name set and that differs from the interface set for this
     * {@code LinkProperties} an {@link IllegalArgumentException} will be thrown.  The
     * proper course is to add either un-named or properly named {@link RouteInfo}.
     *
     * @param route A {@link RouteInfo} to add to this object.
     */
    public void addRoute(RouteInfo route) {
        if (route != null) {
            String routeIface = route.getInterface();
            if (routeIface != null && !routeIface.equals(mIfaceName)) {
                throw new IllegalArgumentException(
                   "Route added with non-matching interface: " + routeIface +
                   " vs. " + mIfaceName);
            }
            mRoutes.add(routeWithInterface(route));
        }
    }

    /**
     * Returns all the {@link RouteInfo} set on this link.
     *
     * @return An unmodifiable {@link Collection} of {@link RouteInfo} for this link.
     */
    public Collection<RouteInfo> getRoutes() {
        return Collections.unmodifiableCollection(mRoutes);
    }

    /**
     * Returns all the routes on this link and all the links stacked above it.
     * @hide
     */
    public Collection<RouteInfo> getAllRoutes() {
        Collection<RouteInfo> routes = new ArrayList();
        routes.addAll(mRoutes);
        for (LinkProperties stacked: mStackedLinks.values()) {
            routes.addAll(stacked.getAllRoutes());
        }
        return routes;
    }

    /**
     * Sets the recommended {@link ProxyInfo} to use on this link, or {@code null} for none.
     * Note that Http Proxies are only a hint - the system recommends their use, but it does
     * not enforce it and applications may ignore them.
     *
     * @param proxy A {@link ProxyInfo} defining the Http Proxy to use on this link.
     */
    public void setHttpProxy(ProxyInfo proxy) {
        mHttpProxy = proxy;
    }

    /**
     * Gets the recommended {@link ProxyInfo} (or {@code null}) set on this link.
     *
     * @return The {@link ProxyInfo} set on this link
     */
    public ProxyInfo getHttpProxy() {
        return mHttpProxy;
    }

    /**
     * Adds a stacked link.
     *
     * If there is already a stacked link with the same interfacename as link,
     * that link is replaced with link. Otherwise, link is added to the list
     * of stacked links. If link is null, nothing changes.
     *
     * @param link The link to add.
     * @return true if the link was stacked, false otherwise.
     * @hide
     */
    public boolean addStackedLink(LinkProperties link) {
        if (link != null && link.getInterfaceName() != null) {
            mStackedLinks.put(link.getInterfaceName(), link);
            return true;
        }
        return false;
    }

    /**
     * Removes a stacked link.
     *
     * If there a stacked link with the same interfacename as link, it is
     * removed. Otherwise, nothing changes.
     *
     * @param link The link to remove.
     * @return true if the link was removed, false otherwise.
     * @hide
     */
    public boolean removeStackedLink(LinkProperties link) {
        if (link != null && link.getInterfaceName() != null) {
            LinkProperties removed = mStackedLinks.remove(link.getInterfaceName());
            return removed != null;
        }
        return false;
    }

    /**
     * Returns all the links stacked on top of this link.
     * @hide
     */
    public Collection<LinkProperties> getStackedLinks() {
        Collection<LinkProperties> stacked = new ArrayList<LinkProperties>();
        for (LinkProperties link : mStackedLinks.values()) {
          stacked.add(new LinkProperties(link));
        }
        return Collections.unmodifiableCollection(stacked);
    }

    /**
     * Clears this object to its initial state.
     */
    public void clear() {
        mIfaceName = null;
        mLinkAddresses.clear();
        mDnses.clear();
        mDomains = null;
        mRoutes.clear();
        mHttpProxy = null;
        mStackedLinks.clear();
        mMtu = 0;
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

        String domainName = "Domains: " + mDomains;

        String mtu = "MTU: " + mMtu;

        String routes = " Routes: [";
        for (RouteInfo route : mRoutes) routes += route.toString() + ",";
        routes += "] ";
        String proxy = (mHttpProxy == null ? "" : "HttpProxy: " + mHttpProxy.toString() + " ");

        String stacked = "";
        if (mStackedLinks.values().size() > 0) {
            stacked += " Stacked: [";
            for (LinkProperties link: mStackedLinks.values()) {
                stacked += " [" + link.toString() + " ],";
            }
            stacked += "] ";
        }
        return "{" + ifaceName + linkAddresses + routes + dns + domainName + mtu
            + proxy + stacked + "}";
    }

    /**
     * Returns true if this link has an IPv4 address.
     *
     * @return {@code true} if there is an IPv4 address, {@code false} otherwise.
     */
    public boolean hasIPv4Address() {
        for (LinkAddress address : mLinkAddresses) {
          if (address.getAddress() instanceof Inet4Address) {
            return true;
          }
        }
        return false;
    }

    /**
     * Returns true if this link has an IPv6 address.
     *
     * @return {@code true} if there is an IPv6 address, {@code false} otherwise.
     */
    public boolean hasIPv6Address() {
        for (LinkAddress address : mLinkAddresses) {
          if (address.getAddress() instanceof Inet6Address) {
            return true;
          }
        }
        return false;
    }

    /**
     * Compares this {@code LinkProperties} interface name against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalInterfaceName(LinkProperties target) {
        return TextUtils.equals(getInterfaceName(), target.getInterfaceName());
    }

    /**
     * Compares this {@code LinkProperties} interface addresses against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalAddresses(LinkProperties target) {
        Collection<InetAddress> targetAddresses = target.getAddresses();
        Collection<InetAddress> sourceAddresses = getAddresses();
        return (sourceAddresses.size() == targetAddresses.size()) ?
                    sourceAddresses.containsAll(targetAddresses) : false;
    }

    /**
     * Compares this {@code LinkProperties} DNS addresses against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalDnses(LinkProperties target) {
        Collection<InetAddress> targetDnses = target.getDnses();
        String targetDomains = target.getDomains();
        if (mDomains == null) {
            if (targetDomains != null) return false;
        } else {
            if (mDomains.equals(targetDomains) == false) return false;
        }
        return (mDnses.size() == targetDnses.size()) ?
                    mDnses.containsAll(targetDnses) : false;
    }

    /**
     * Compares this {@code LinkProperties} Routes against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalRoutes(LinkProperties target) {
        Collection<RouteInfo> targetRoutes = target.getRoutes();
        return (mRoutes.size() == targetRoutes.size()) ?
                    mRoutes.containsAll(targetRoutes) : false;
    }

    /**
     * Compares this {@code LinkProperties} HttpProxy against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalHttpProxy(LinkProperties target) {
        return getHttpProxy() == null ? target.getHttpProxy() == null :
                    getHttpProxy().equals(target.getHttpProxy());
    }

    /**
     * Compares this {@code LinkProperties} stacked links against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalStackedLinks(LinkProperties target) {
        if (!mStackedLinks.keySet().equals(target.mStackedLinks.keySet())) {
            return false;
        }
        for (LinkProperties stacked : mStackedLinks.values()) {
            // Hashtable values can never be null.
            String iface = stacked.getInterfaceName();
            if (!stacked.equals(target.mStackedLinks.get(iface))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares this {@code LinkProperties} MTU against the target
     *
     * @param target LinkProperties to compare.
     * @return {@code true} if both are identical, {@code false} otherwise.
     * @hide
     */
    public boolean isIdenticalMtu(LinkProperties target) {
        return getMtu() == target.getMtu();
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

        LinkProperties target = (LinkProperties) obj;
        /**
         * This method does not check that stacked interfaces are equal, because
         * stacked interfaces are not so much a property of the link as a
         * description of connections between links.
         */
        return isIdenticalInterfaceName(target) &&
                isIdenticalAddresses(target) &&
                isIdenticalDnses(target) &&
                isIdenticalRoutes(target) &&
                isIdenticalHttpProxy(target) &&
                isIdenticalStackedLinks(target) &&
                isIdenticalMtu(target);
    }

    /**
     * Compares the addresses in this LinkProperties with another
     * LinkProperties, examining only addresses on the base link.
     *
     * @param target a LinkProperties with the new list of addresses
     * @return the differences between the addresses.
     * @hide
     */
    public CompareResult<LinkAddress> compareAddresses(LinkProperties target) {
        /*
         * Duplicate the LinkAddresses into removed, we will be removing
         * address which are common between mLinkAddresses and target
         * leaving the addresses that are different. And address which
         * are in target but not in mLinkAddresses are placed in the
         * addedAddresses.
         */
        CompareResult<LinkAddress> result = new CompareResult<LinkAddress>();
        result.removed = new ArrayList<LinkAddress>(mLinkAddresses);
        result.added.clear();
        if (target != null) {
            for (LinkAddress newAddress : target.getLinkAddresses()) {
                if (! result.removed.remove(newAddress)) {
                    result.added.add(newAddress);
                }
            }
        }
        return result;
    }

    /**
     * Compares the DNS addresses in this LinkProperties with another
     * LinkProperties, examining only DNS addresses on the base link.
     *
     * @param target a LinkProperties with the new list of dns addresses
     * @return the differences between the DNS addresses.
     * @hide
     */
    public CompareResult<InetAddress> compareDnses(LinkProperties target) {
        /*
         * Duplicate the InetAddresses into removed, we will be removing
         * dns address which are common between mDnses and target
         * leaving the addresses that are different. And dns address which
         * are in target but not in mDnses are placed in the
         * addedAddresses.
         */
        CompareResult<InetAddress> result = new CompareResult<InetAddress>();

        result.removed = new ArrayList<InetAddress>(mDnses);
        result.added.clear();
        if (target != null) {
            for (InetAddress newAddress : target.getDnses()) {
                if (! result.removed.remove(newAddress)) {
                    result.added.add(newAddress);
                }
            }
        }
        return result;
    }

    /**
     * Compares all routes in this LinkProperties with another LinkProperties,
     * examining both the the base link and all stacked links.
     *
     * @param target a LinkProperties with the new list of routes
     * @return the differences between the routes.
     * @hide
     */
    public CompareResult<RouteInfo> compareAllRoutes(LinkProperties target) {
        /*
         * Duplicate the RouteInfos into removed, we will be removing
         * routes which are common between mRoutes and target
         * leaving the routes that are different. And route address which
         * are in target but not in mRoutes are placed in added.
         */
        CompareResult<RouteInfo> result = new CompareResult<RouteInfo>();

        result.removed = getAllRoutes();
        result.added.clear();
        if (target != null) {
            for (RouteInfo r : target.getAllRoutes()) {
                if (! result.removed.remove(r)) {
                    result.added.add(r);
                }
            }
        }
        return result;
    }

    /**
     * Compares all interface names in this LinkProperties with another
     * LinkProperties, examining both the the base link and all stacked links.
     *
     * @param target a LinkProperties with the new list of interface names
     * @return the differences between the interface names.
     * @hide
     */
    public CompareResult<String> compareAllInterfaceNames(LinkProperties target) {
        /*
         * Duplicate the interface names into removed, we will be removing
         * interface names which are common between this and target
         * leaving the interface names that are different. And interface names which
         * are in target but not in this are placed in added.
         */
        CompareResult<String> result = new CompareResult<String>();

        result.removed = getAllInterfaceNames();
        result.added.clear();
        if (target != null) {
            for (String r : target.getAllInterfaceNames()) {
                if (! result.removed.remove(r)) {
                    result.added.add(r);
                }
            }
        }
        return result;
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
                + ((null == mDomains) ? 0 : mDomains.hashCode())
                + mRoutes.size() * 41
                + ((null == mHttpProxy) ? 0 : mHttpProxy.hashCode())
                + mStackedLinks.hashCode() * 47)
                + mMtu * 51;
    }

    /**
     * Implement the Parcelable interface.
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
        dest.writeString(mDomains);
        dest.writeInt(mMtu);
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
        ArrayList<LinkProperties> stackedLinks = new ArrayList(mStackedLinks.values());
        dest.writeList(stackedLinks);
    }

    /**
     * Implement the Parcelable interface.
     */
    public static final Creator<LinkProperties> CREATOR =
        new Creator<LinkProperties>() {
            public LinkProperties createFromParcel(Parcel in) {
                LinkProperties netProp = new LinkProperties();

                String iface = in.readString();
                if (iface != null) {
                    netProp.setInterfaceName(iface);
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
                netProp.setDomains(in.readString());
                netProp.setMtu(in.readInt());
                addressCount = in.readInt();
                for (int i=0; i<addressCount; i++) {
                    netProp.addRoute((RouteInfo)in.readParcelable(null));
                }
                if (in.readByte() == 1) {
                    netProp.setHttpProxy((ProxyInfo)in.readParcelable(null));
                }
                ArrayList<LinkProperties> stackedLinks = new ArrayList<LinkProperties>();
                in.readList(stackedLinks, LinkProperties.class.getClassLoader());
                for (LinkProperties stackedLink: stackedLinks) {
                    netProp.addStackedLink(stackedLink);
                }
                return netProp;
            }

            public LinkProperties[] newArray(int size) {
                return new LinkProperties[size];
            }
        };
}
