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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.android.net.module.util.InetAddressUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A simple object for retrieving the results of a DHCP request.
 * Optimized (attempted) for that jni interface
 * TODO: remove this class and replace with other existing constructs
 * @hide
 */
public final class DhcpResults implements Parcelable {
    private static final String TAG = "DhcpResults";

    @UnsupportedAppUsage
    public LinkAddress ipAddress;

    @UnsupportedAppUsage
    public InetAddress gateway;

    @UnsupportedAppUsage
    public final ArrayList<InetAddress> dnsServers = new ArrayList<>();

    @UnsupportedAppUsage
    public String domains;

    @UnsupportedAppUsage
    public Inet4Address serverAddress;

    /** Vendor specific information (from RFC 2132). */
    @UnsupportedAppUsage
    public String vendorInfo;

    @UnsupportedAppUsage
    public int leaseDuration;

    /** Link MTU option. 0 means unset. */
    @UnsupportedAppUsage
    public int mtu;

    public String serverHostName;

    @Nullable
    public String captivePortalApiUrl;

    public DhcpResults() {
        super();
    }

    /**
     * Create a {@link StaticIpConfiguration} based on the DhcpResults.
     */
    public StaticIpConfiguration toStaticIpConfiguration() {
        return new StaticIpConfiguration.Builder()
                .setIpAddress(ipAddress)
                .setGateway(gateway)
                .setDnsServers(dnsServers)
                .setDomains(domains)
                .build();
    }

    public DhcpResults(StaticIpConfiguration source) {
        if (source != null) {
            ipAddress = source.getIpAddress();
            gateway = source.getGateway();
            dnsServers.addAll(source.getDnsServers());
            domains = source.getDomains();
        }
    }

    /** copy constructor */
    public DhcpResults(DhcpResults source) {
        this(source == null ? null : source.toStaticIpConfiguration());
        if (source != null) {
            serverAddress = source.serverAddress;
            vendorInfo = source.vendorInfo;
            leaseDuration = source.leaseDuration;
            mtu = source.mtu;
            serverHostName = source.serverHostName;
            captivePortalApiUrl = source.captivePortalApiUrl;
        }
    }

    /**
     * @see StaticIpConfiguration#getRoutes(String)
     * @hide
     */
    public List<RouteInfo> getRoutes(String iface) {
        return toStaticIpConfiguration().getRoutes(iface);
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
        ipAddress = null;
        gateway = null;
        dnsServers.clear();
        domains = null;
        serverAddress = null;
        vendorInfo = null;
        leaseDuration = 0;
        mtu = 0;
        serverHostName = null;
        captivePortalApiUrl = null;
    }

    @Override
    public String toString() {
        StringBuffer str = new StringBuffer(super.toString());

        str.append(" DHCP server ").append(serverAddress);
        str.append(" Vendor info ").append(vendorInfo);
        str.append(" lease ").append(leaseDuration).append(" seconds");
        if (mtu != 0) str.append(" MTU ").append(mtu);
        str.append(" Servername ").append(serverHostName);
        if (captivePortalApiUrl != null) {
            str.append(" CaptivePortalApiUrl ").append(captivePortalApiUrl);
        }

        return str.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof DhcpResults)) return false;

        DhcpResults target = (DhcpResults)obj;

        return toStaticIpConfiguration().equals(target.toStaticIpConfiguration())
                && Objects.equals(serverAddress, target.serverAddress)
                && Objects.equals(vendorInfo, target.vendorInfo)
                && Objects.equals(serverHostName, target.serverHostName)
                && leaseDuration == target.leaseDuration
                && mtu == target.mtu
                && Objects.equals(captivePortalApiUrl, target.captivePortalApiUrl);
    }

    /**
     * Implement the Parcelable interface
     */
    public static final @android.annotation.NonNull Creator<DhcpResults> CREATOR =
        new Creator<DhcpResults>() {
            public DhcpResults createFromParcel(Parcel in) {
                return readFromParcel(in);
            }

            public DhcpResults[] newArray(int size) {
                return new DhcpResults[size];
            }
        };

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        toStaticIpConfiguration().writeToParcel(dest, flags);
        dest.writeInt(leaseDuration);
        dest.writeInt(mtu);
        InetAddressUtils.parcelInetAddress(dest, serverAddress, flags);
        dest.writeString(vendorInfo);
        dest.writeString(serverHostName);
        dest.writeString(captivePortalApiUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private static DhcpResults readFromParcel(Parcel in) {
        final StaticIpConfiguration s = StaticIpConfiguration.CREATOR.createFromParcel(in);
        final DhcpResults dhcpResults = new DhcpResults(s);
        dhcpResults.leaseDuration = in.readInt();
        dhcpResults.mtu = in.readInt();
        dhcpResults.serverAddress = (Inet4Address) InetAddressUtils.unparcelInetAddress(in);
        dhcpResults.vendorInfo = in.readString();
        dhcpResults.serverHostName = in.readString();
        dhcpResults.captivePortalApiUrl = in.readString();
        return dhcpResults;
    }

    // Utils for jni population - false on success
    // Not part of the superclass because they're only used by the JNI iterface to the DHCP daemon.
    public boolean setIpAddress(String addrString, int prefixLength) {
        try {
            Inet4Address addr = (Inet4Address) InetAddresses.parseNumericAddress(addrString);
            ipAddress = new LinkAddress(addr, prefixLength);
        } catch (IllegalArgumentException|ClassCastException e) {
            Log.e(TAG, "setIpAddress failed with addrString " + addrString + "/" + prefixLength);
            return true;
        }
        return false;
    }

    public boolean setGateway(String addrString) {
        try {
            gateway = InetAddresses.parseNumericAddress(addrString);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setGateway failed with addrString " + addrString);
            return true;
        }
        return false;
    }

    public boolean addDns(String addrString) {
        if (TextUtils.isEmpty(addrString) == false) {
            try {
                dnsServers.add(InetAddresses.parseNumericAddress(addrString));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "addDns failed with addrString " + addrString);
                return true;
            }
        }
        return false;
    }

    public LinkAddress getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(LinkAddress ipAddress) {
        this.ipAddress = ipAddress;
    }

    public InetAddress getGateway() {
        return gateway;
    }

    public void setGateway(InetAddress gateway) {
        this.gateway = gateway;
    }

    public List<InetAddress> getDnsServers() {
        return dnsServers;
    }

    /**
     * Add a DNS server to this configuration.
     */
    public void addDnsServer(InetAddress server) {
        dnsServers.add(server);
    }

    public String getDomains() {
        return domains;
    }

    public void setDomains(String domains) {
        this.domains = domains;
    }

    public Inet4Address getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(Inet4Address addr) {
        serverAddress = addr;
    }

    public int getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(int duration) {
        leaseDuration = duration;
    }

    public String getVendorInfo() {
        return vendorInfo;
    }

    public void setVendorInfo(String info) {
        vendorInfo = info;
    }

    public int getMtu() {
        return mtu;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public String getCaptivePortalApiUrl() {
        return captivePortalApiUrl;
    }

    public void setCaptivePortalApiUrl(String url) {
        captivePortalApiUrl = url;
    }
}
