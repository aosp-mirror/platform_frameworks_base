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

package android.net.wifi.passpoint;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class WifiPasspointInfo implements Parcelable {

    /** TODO doc */
    public static final int ANQP_CAPABILITY = 1 << 0;

    /** TODO doc */
    public static final int VENUE_NAME = 1 << 1;

    /** TODO doc */
    public static final int NETWORK_AUTH_TYPE = 1 << 2;

    /** TODO doc */
    public static final int ROAMING_CONSORTIUM = 1 << 3;

    /** TODO doc */
    public static final int IP_ADDR_TYPE_AVAILABILITY = 1 << 4;

    /** TODO doc */
    public static final int NAI_REALM = 1 << 5;

    /** TODO doc */
    public static final int CELLULAR_NETWORK = 1 << 6;

    /** TODO doc */
    public static final int DOMAIN_NAME = 1 << 7;

    /** TODO doc */
    public static final int HOTSPOT_CAPABILITY = 1 << 8;

    /** TODO doc */
    public static final int OPERATOR_FRIENDLY_NAME = 1 << 9;

    /** TODO doc */
    public static final int WAN_METRICS = 1 << 10;

    /** TODO doc */
    public static final int CONNECTION_CAPABILITY = 1 << 11;

    /** TODO doc */
    public static final int OSU_PROVIDER = 1 << 12;

    /** TODO doc */
    public static final int PRESET_CRED_MATCH =
            ANQP_CAPABILITY |
                    HOTSPOT_CAPABILITY |
                    NAI_REALM |
                    CELLULAR_NETWORK |
                    DOMAIN_NAME;

    /** TODO doc */
    public static final int PRESET_ALL =
            ANQP_CAPABILITY |
                    VENUE_NAME |
                    NETWORK_AUTH_TYPE |
                    ROAMING_CONSORTIUM |
                    IP_ADDR_TYPE_AVAILABILITY |
                    NAI_REALM |
                    CELLULAR_NETWORK |
                    DOMAIN_NAME |
                    HOTSPOT_CAPABILITY |
                    OPERATOR_FRIENDLY_NAME |
                    WAN_METRICS |
                    CONNECTION_CAPABILITY |
                    OSU_PROVIDER;


    public static class WanMetrics {
        public static final int STATUS_RESERVED = 0;
        public static final int STATUS_UP = 1;
        public static final int STATUS_DOWN = 2;
        public static final int STATUS_TEST = 3;

        public int wanInfo;
        public long downlinkSpeed;
        public long uplinkSpeed;
        public int downlinkLoad;
        public int uplinkLoad;
        public int lmd;

        public int getLinkStatus() {
            return wanInfo & 0x3;
        }

        public boolean getSymmetricLink() {
            return (wanInfo & (1 << 2)) != 0;
        }

        public boolean getAtCapacity() {
            return (wanInfo & (1 << 3)) != 0;
        }

        @Override
        public String toString() {
            return wanInfo + "," + downlinkSpeed + "," + uplinkSpeed + "," +
                    downlinkLoad + "," + uplinkLoad + "," + lmd;
        }
    }

    public static class IpProtoPort {
        public static final int STATUS_CLOSED = 0;
        public static final int STATUS_OPEN = 1;
        public static final int STATUS_UNKNOWN = 2;

        public int proto;
        public int port;
        public int status;

        @Override
        public String toString() {
            return proto + "," + port + "," + status;
        }
    }

    public static class NetworkAuthType {
        public static final int TYPE_TERMS_AND_CONDITION = 0;
        public static final int TYPE_ONLINE_ENROLLMENT = 1;
        public static final int TYPE_HTTP_REDIRECTION = 2;
        public static final int TYPE_DNS_REDIRECTION = 3;

        public int type;
        public String redirectUrl;

        @Override
        public String toString() {
            return type + "," + redirectUrl;
        }
    }

    public static class IpAddressType {
        public static final int IPV6_NOT_AVAILABLE = 0;
        public static final int IPV6_AVAILABLE = 1;
        public static final int IPV6_UNKNOWN = 2;

        public static final int IPV4_NOT_AVAILABLE = 0;
        public static final int IPV4_PUBLIC = 1;
        public static final int IPV4_PORT_RESTRICTED = 2;
        public static final int IPV4_SINGLE_NAT = 3;
        public static final int IPV4_DOUBLE_NAT = 4;
        public static final int IPV4_PORT_RESTRICTED_SINGLE_NAT = 5;
        public static final int IPV4_PORT_RESTRICTED_DOUBLE_NAT = 6;
        public static final int IPV4_PORT_UNKNOWN = 7;

        private static final int NULL_VALUE = -1;

        public int availability;

        public int getIpv6Availability() {
            return availability & 0x3;
        }

        public int getIpv4Availability() {
            return (availability & 0xFF) >> 2;
        }

        @Override
        public String toString() {
            return getIpv6Availability() + "," + getIpv4Availability();
        }
    }

    public static class NaiRealm {
        public static final int ENCODING_RFC4282 = 0;
        public static final int ENCODING_UTF8 = 1;

        public int encoding;
        public String realm;

        @Override
        public String toString() {
            return encoding + "," + realm;
        }
    }

    public static class CellularNetwork {
        public String mcc;
        public String mnc;

        @Override
        public String toString() {
            return mcc + "," + mnc;
        }
    }

    /** BSSID */
    public String bssid;

    /** venue name */
    public String venueName;

    /** list of network authentication types */
    public List<NetworkAuthType> networkAuthTypeList;

    /** list of roaming consortium OIs */
    public List<String> roamingConsortiumList;

    /** IP address availability */
    public IpAddressType ipAddrTypeAvailability;

    /** list of NAI realm */
    public List<NaiRealm> naiRealmList;

    /** list of 3GPP cellular network */
    public List<CellularNetwork> cellularNetworkList;

    /** list of fully qualified domain name (FQDN) */
    public List<String> domainNameList;

    /** HS 2.0 operator friendly name */
    public String operatorFriendlyName;

    /** HS 2.0 wan metrics */
    public WanMetrics wanMetrics;

    /** list of HS 2.0 IP proto port */
    public List<IpProtoPort> connectionCapabilityList;

    /** list of HS 2.0 OSU providers */
    public List<WifiPasspointOsuProvider> osuProviderList;

    /**
     * Convert mask to ANQP subtypes, for supplicant command use.
     *
     * @param mask The ANQP subtypes mask.
     * @return String of ANQP subtypes, good for supplicant command use
     * @hide
     */
    public static String toAnqpSubtypes(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & ANQP_CAPABILITY) != 0)
            sb.append("257,");
        if ((mask & VENUE_NAME) != 0)
            sb.append("258,");
        if ((mask & NETWORK_AUTH_TYPE) != 0)
            sb.append("260,");
        if ((mask & ROAMING_CONSORTIUM) != 0)
            sb.append("261,");
        if ((mask & IP_ADDR_TYPE_AVAILABILITY) != 0)
            sb.append("262,");
        if ((mask & NAI_REALM) != 0)
            sb.append("263,");
        if ((mask & CELLULAR_NETWORK) != 0)
            sb.append("264,");
        if ((mask & DOMAIN_NAME) != 0)
            sb.append("268,");
        if ((mask & HOTSPOT_CAPABILITY) != 0)
            sb.append("hs20:2,");
        if ((mask & OPERATOR_FRIENDLY_NAME) != 0)
            sb.append("hs20:3,");
        if ((mask & WAN_METRICS) != 0)
            sb.append("hs20:4,");
        if ((mask & CONNECTION_CAPABILITY) != 0)
            sb.append("hs20:5,");
        if ((mask & OSU_PROVIDER) != 0)
            sb.append("hs20:8,");
        if (sb.length() > 0)
            sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("BSSID: ").append("(").append(bssid).append(")");

        if (venueName != null)
            sb.append(" venueName: ").append("(")
              .append(venueName.replace("\n", "\\n")).append(")");

        if (networkAuthTypeList != null) {
            sb.append(" networkAuthType: ");
            for (NetworkAuthType auth : networkAuthTypeList)
                sb.append("(").append(auth.toString()).append(")");
        }

        if (roamingConsortiumList != null) {
            sb.append(" roamingConsortium: ");
            for (String oi : roamingConsortiumList)
                sb.append("(").append(oi).append(")");
        }

        if (ipAddrTypeAvailability != null) {
            sb.append(" ipAddrTypeAvaibility: ").append("(")
              .append(ipAddrTypeAvailability.toString()).append(")");
        }

        if (naiRealmList != null) {
            sb.append(" naiRealm: ");
            for (NaiRealm realm : naiRealmList)
                sb.append("(").append(realm.toString()).append(")");
        }

        if (cellularNetworkList != null) {
            sb.append(" cellularNetwork: ");
            for (CellularNetwork plmn : cellularNetworkList)
                sb.append("(").append(plmn.toString()).append(")");
        }

        if (domainNameList != null) {
            sb.append(" domainName: ");
            for (String fqdn : domainNameList)
                sb.append("(").append(fqdn).append(")");
        }

        if (operatorFriendlyName != null)
            sb.append(" operatorFriendlyName: ").append("(")
              .append(operatorFriendlyName).append(")");

        if (wanMetrics != null)
            sb.append(" wanMetrics: ").append("(")
              .append(wanMetrics.toString()).append(")");

        if (connectionCapabilityList != null) {
            sb.append(" connectionCapability: ");
            for (IpProtoPort ip : connectionCapabilityList)
                sb.append("(").append(ip.toString()).append(")");
        }

        if (osuProviderList != null) {
            sb.append(" osuProviderList: ");
            for (WifiPasspointOsuProvider osu : osuProviderList)
                sb.append("(").append(osu.toString()).append(")");
        }

        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(bssid);
        out.writeString(venueName);

        if (networkAuthTypeList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(networkAuthTypeList.size());
            for (NetworkAuthType auth : networkAuthTypeList) {
                out.writeInt(auth.type);
                out.writeString(auth.redirectUrl);
            }
        }

        if (roamingConsortiumList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(roamingConsortiumList.size());
            for (String oi : roamingConsortiumList)
                out.writeString(oi);
        }

        if (ipAddrTypeAvailability == null) {
            out.writeInt(IpAddressType.NULL_VALUE);
        } else {
            out.writeInt(ipAddrTypeAvailability.availability);
        }

        if (naiRealmList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(naiRealmList.size());
            for (NaiRealm realm : naiRealmList) {
                out.writeInt(realm.encoding);
                out.writeString(realm.realm);
            }
        }

        if (cellularNetworkList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(cellularNetworkList.size());
            for (CellularNetwork plmn : cellularNetworkList) {
                out.writeString(plmn.mcc);
                out.writeString(plmn.mnc);
            }
        }


        if (domainNameList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(domainNameList.size());
            for (String fqdn : domainNameList)
                out.writeString(fqdn);
        }

        out.writeString(operatorFriendlyName);

        if (wanMetrics == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            out.writeInt(wanMetrics.wanInfo);
            out.writeLong(wanMetrics.downlinkSpeed);
            out.writeLong(wanMetrics.uplinkSpeed);
            out.writeInt(wanMetrics.downlinkLoad);
            out.writeInt(wanMetrics.uplinkLoad);
            out.writeInt(wanMetrics.lmd);
        }

        if (connectionCapabilityList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(connectionCapabilityList.size());
            for (IpProtoPort ip : connectionCapabilityList) {
                out.writeInt(ip.proto);
                out.writeInt(ip.port);
                out.writeInt(ip.status);
            }
        }

        if (osuProviderList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(osuProviderList.size());
            for (WifiPasspointOsuProvider osu : osuProviderList)
                osu.writeToParcel(out, flags);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Parcelable.Creator<WifiPasspointInfo> CREATOR =
            new Parcelable.Creator<WifiPasspointInfo>() {
                @Override
                public WifiPasspointInfo createFromParcel(Parcel in) {
                    WifiPasspointInfo p = new WifiPasspointInfo();
                    int n;

                    p.bssid = in.readString();
                    p.venueName = in.readString();

                    n = in.readInt();
                    if (n > 0) {
                        p.networkAuthTypeList = new ArrayList<NetworkAuthType>();
                        for (int i = 0; i < n; i++) {
                            NetworkAuthType auth = new NetworkAuthType();
                            auth.type = in.readInt();
                            auth.redirectUrl = in.readString();
                            p.networkAuthTypeList.add(auth);
                        }
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.roamingConsortiumList = new ArrayList<String>();
                        for (int i = 0; i < n; i++)
                            p.roamingConsortiumList.add(in.readString());
                    }

                    n = in.readInt();
                    if (n != IpAddressType.NULL_VALUE) {
                        p.ipAddrTypeAvailability = new IpAddressType();
                        p.ipAddrTypeAvailability.availability = n;
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.naiRealmList = new ArrayList<NaiRealm>();
                        for (int i = 0; i < n; i++) {
                            NaiRealm realm = new NaiRealm();
                            realm.encoding = in.readInt();
                            realm.realm = in.readString();
                            p.naiRealmList.add(realm);
                        }
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.cellularNetworkList = new ArrayList<CellularNetwork>();
                        for (int i = 0; i < n; i++) {
                            CellularNetwork plmn = new CellularNetwork();
                            plmn.mcc = in.readString();
                            plmn.mnc = in.readString();
                            p.cellularNetworkList.add(plmn);
                        }
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.domainNameList = new ArrayList<String>();
                        for (int i = 0; i < n; i++)
                            p.domainNameList.add(in.readString());
                    }

                    p.operatorFriendlyName = in.readString();

                    n = in.readInt();
                    if (n > 0) {
                        p.wanMetrics = new WanMetrics();
                        p.wanMetrics.wanInfo = in.readInt();
                        p.wanMetrics.downlinkSpeed = in.readLong();
                        p.wanMetrics.uplinkSpeed = in.readLong();
                        p.wanMetrics.downlinkLoad = in.readInt();
                        p.wanMetrics.uplinkLoad = in.readInt();
                        p.wanMetrics.lmd = in.readInt();
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.connectionCapabilityList = new ArrayList<IpProtoPort>();
                        for (int i = 0; i < n; i++) {
                            IpProtoPort ip = new IpProtoPort();
                            ip.proto = in.readInt();
                            ip.port = in.readInt();
                            ip.status = in.readInt();
                            p.connectionCapabilityList.add(ip);
                        }
                    }

                    n = in.readInt();
                    if (n > 0) {
                        p.osuProviderList = new ArrayList<WifiPasspointOsuProvider>();
                        for (int i = 0; i < n; i++) {
                            WifiPasspointOsuProvider osu = WifiPasspointOsuProvider.CREATOR
                                    .createFromParcel(in);
                            p.osuProviderList.add(osu);
                        }
                    }

                    return p;
                }

                @Override
                public WifiPasspointInfo[] newArray(int size) {
                    return new WifiPasspointInfo[size];
                }
            };
}
