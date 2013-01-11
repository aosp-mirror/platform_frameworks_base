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

package android.net;

import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 * A simple object for retrieving the results of a DHCP request.
 * Optimized (attempted) for that jni interface
 * TODO - remove when DhcpInfo is deprecated.  Move the remaining api to LinkProperties.
 * @hide
 */
public class DhcpResults implements Parcelable {
    private static final String TAG = "DhcpResults";

    public final LinkProperties linkProperties;

    public InetAddress serverAddress;

    /**
     * Vendor specific information (from RFC 2132).
     */
    public String vendorInfo;

    public int leaseDuration;

    public DhcpResults() {
        linkProperties = new LinkProperties();
    }

    /** copy constructor */
    public DhcpResults(DhcpResults source) {
        if (source != null) {
            linkProperties = new LinkProperties(source.linkProperties);
            serverAddress = source.serverAddress;
            leaseDuration = source.leaseDuration;
            vendorInfo = source.vendorInfo;
        } else {
            linkProperties = new LinkProperties();
        }
    }

    public DhcpResults(LinkProperties lp) {
        linkProperties = new LinkProperties(lp);
    }

    /**
     * Updates the DHCP fields that need to be retained from
     * original DHCP request if the current renewal shows them
     * being empty.
     */
    public void updateFromDhcpRequest(DhcpResults orig) {
        if (orig == null || orig.linkProperties == null) return;
        if (linkProperties.getRoutes().size() == 0) {
            for (RouteInfo r : orig.linkProperties.getRoutes()) linkProperties.addRoute(r);
        }
        if (linkProperties.getDnses().size() == 0) {
            for (InetAddress d : orig.linkProperties.getDnses()) linkProperties.addDns(d);
        }
    }

    /**
     * Test if this DHCP lease includes vendor hint that network link is
     * metered, and sensitive to heavy data transfers.
     */
    public boolean hasMeteredHint() {
        if (vendorInfo != null) {
            return vendorInfo.contains("ANDROID_METERED");
        } else {
            return false;
        }
    }

    public void clear() {
        linkProperties.clear();
        serverAddress = null;
        vendorInfo = null;
        leaseDuration = 0;
    }

    @Override
    public String toString() {
        StringBuffer str = new StringBuffer(linkProperties.toString());

        str.append(" DHCP server ").append(serverAddress);
        str.append(" Vendor info ").append(vendorInfo);
        str.append(" lease ").append(leaseDuration).append(" seconds");

        return str.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof DhcpResults)) return false;

        DhcpResults target = (DhcpResults)obj;

        if (linkProperties == null) {
            if (target.linkProperties != null) return false;
        } else if (!linkProperties.equals(target.linkProperties)) return false;
        if (serverAddress == null) {
            if (target.serverAddress != null) return false;
        } else if (!serverAddress.equals(target.serverAddress)) return false;
        if (vendorInfo == null) {
            if (target.vendorInfo != null) return false;
        } else if (!vendorInfo.equals(target.vendorInfo)) return false;
        if (leaseDuration != target.leaseDuration) return false;

        return true;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        linkProperties.writeToParcel(dest, flags);

        dest.writeInt(leaseDuration);

        if (serverAddress != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(serverAddress.getAddress());
        } else {
            dest.writeByte((byte)0);
        }

        dest.writeString(vendorInfo);
    }

    /** Implement the Parcelable interface */
    public static final Creator<DhcpResults> CREATOR =
        new Creator<DhcpResults>() {
            public DhcpResults createFromParcel(Parcel in) {
                DhcpResults prop = new DhcpResults((LinkProperties)in.readParcelable(null));

                prop.leaseDuration = in.readInt();

                if (in.readByte() == 1) {
                    try {
                        prop.serverAddress = InetAddress.getByAddress(in.createByteArray());
                    } catch (UnknownHostException e) {}
                }

                prop.vendorInfo = in.readString();

                return prop;
            }

            public DhcpResults[] newArray(int size) {
                return new DhcpResults[size];
            }
        };

    // Utils for jni population - false on success
    public void setInterfaceName(String interfaceName) {
        linkProperties.setInterfaceName(interfaceName);
    }

    public boolean addLinkAddress(String addrString, int prefixLength) {
        InetAddress addr;
        try {
            addr = NetworkUtils.numericToInetAddress(addrString);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "addLinkAddress failed with addrString " + addrString);
            return true;
        }

        LinkAddress linkAddress = new LinkAddress(addr, prefixLength);
        linkProperties.addLinkAddress(linkAddress);

        RouteInfo routeInfo = new RouteInfo(linkAddress);
        linkProperties.addRoute(routeInfo);
        return false;
    }

    public boolean addGateway(String addrString) {
        try {
            linkProperties.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(addrString)));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "addGateway failed with addrString " + addrString);
            return true;
        }
        return false;
    }

    public boolean addDns(String addrString) {
        if (TextUtils.isEmpty(addrString) == false) {
            try {
                linkProperties.addDns(NetworkUtils.numericToInetAddress(addrString));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "addDns failed with addrString " + addrString);
                return true;
            }
        }
        return false;
    }

    public boolean setServerAddress(String addrString) {
        try {
            serverAddress = NetworkUtils.numericToInetAddress(addrString);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setServerAddress failed with addrString " + addrString);
            return true;
        }
        return false;
    }

    public void setLeaseDuration(int duration) {
        leaseDuration = duration;
    }

    public void setVendorInfo(String info) {
        vendorInfo = info;
    }

    public void setDomains(String domains) {
        linkProperties.setDomains(domains);
    }
}
