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

/**
 * TODO: doc
 */
public class PasspointInfo implements Parcelable {

    /** TODO doc */
    public static final int ANQP_CAPABILITY             = 1 << 0;

    /** TODO doc */
    public static final int VENUE_NAME                  = 1 << 1;

    /** TODO doc */
    public static final int NETWORK_AUTH_TYPE           = 1 << 2;

    /** TODO doc */
    public static final int ROAMING_CONSORTIUM          = 1 << 3;

    /** TODO doc */
    public static final int IP_ADDR_TYPE_AVAILABILITY   = 1 << 4;

    /** TODO doc */
    public static final int NAI_REALM                   = 1 << 5;

    /** TODO doc */
    public static final int CELLULAR_NETWORK            = 1 << 6;

    /** TODO doc */
    public static final int DOMAIN_NAME                 = 1 << 7;

    /** TODO doc */
    public static final int HOTSPOT_CAPABILITY          = 1 << 8;

    /** TODO doc */
    public static final int OPERATOR_FRIENDLY_NAME      = 1 << 9;

    /** TODO doc */
    public static final int WAN_METRICS                 = 1 << 10;

    /** TODO doc */
    public static final int CONNECTION_CAPABILITY       = 1 << 11;

    /** TODO doc */
    public static final int OSU_PROVIDER                = 1 << 12;

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


    /** TODO doc */
    public String bssid;

    /** TODO doc */
    public String venueName;

    /** TODO doc */
    public String networkAuthType;

    /** TODO doc */
    public String roamingConsortium;

    /** TODO doc */
    public String ipAddrTypeAvaibility;

    /** TODO doc */
    public String naiRealm;

    /** TODO doc */
    public String cellularNetwork;

    /** TODO doc */
    public String domainName;

    /** TODO doc */
    public String operatorFriendlyName;

    /** TODO doc */
    public String wanMetrics;

    /** TODO doc */
    public String connectionCapability;

    /** TODO doc */
    public List<PasspointOsuProvider> osuProviderList;


    /** default constructor @hide */
    public PasspointInfo() {
//        osuProviderList = new ArrayList<OsuProvider>();
    }

    /** copy constructor @hide */
    public PasspointInfo(PasspointInfo source) {
        // TODO
        bssid = source.bssid;
        venueName = source.venueName;
        networkAuthType = source.networkAuthType;
        roamingConsortium = source.roamingConsortium;
        ipAddrTypeAvaibility = source.ipAddrTypeAvaibility;
        naiRealm = source.naiRealm;
        cellularNetwork = source.cellularNetwork;
        domainName = source.domainName;
        operatorFriendlyName = source.operatorFriendlyName;
        wanMetrics = source.wanMetrics;
        connectionCapability = source.connectionCapability;
        if (source.osuProviderList != null) {
            osuProviderList = new ArrayList<PasspointOsuProvider>();
            for (PasspointOsuProvider osu : source.osuProviderList)
                osuProviderList.add(new PasspointOsuProvider(osu));
        }
    }

    /**
     * Convert mask to ANQP subtypes, for supplicant command use.
     *
     * @param mask The ANQP subtypes mask.
     * @return String of ANQP subtypes, good for supplicant command use
     * @hide
     */
    public static String toAnqpSubtypes(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & ANQP_CAPABILITY) != 0) sb.append("257,");
        if ((mask & VENUE_NAME) != 0) sb.append("258,");
        if ((mask & NETWORK_AUTH_TYPE) != 0) sb.append("260,");
        if ((mask & ROAMING_CONSORTIUM) != 0) sb.append("261,");
        if ((mask & IP_ADDR_TYPE_AVAILABILITY) != 0) sb.append("262,");
        if ((mask & NAI_REALM) != 0) sb.append("263,");
        if ((mask & CELLULAR_NETWORK) != 0) sb.append("264,");
        if ((mask & DOMAIN_NAME) != 0) sb.append("268,");
        if ((mask & HOTSPOT_CAPABILITY) != 0) sb.append("hs20:2,");
        if ((mask & OPERATOR_FRIENDLY_NAME) != 0) sb.append("hs20:3,");
        if ((mask & WAN_METRICS) != 0) sb.append("hs20:4,");
        if ((mask & CONNECTION_CAPABILITY) != 0) sb.append("hs20:5,");
        if ((mask & OSU_PROVIDER) != 0) sb.append("hs20:8,");
        if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("BSSID: ").append(bssid);
        if (venueName != null)
            sb.append(" venueName: ").append(venueName);
        if (networkAuthType != null)
            sb.append(" networkAuthType: ").append(networkAuthType);
        if (roamingConsortium != null)
            sb.append(" roamingConsortium: ").append(roamingConsortium);
        if (ipAddrTypeAvaibility != null)
            sb.append(" ipAddrTypeAvaibility: ").append(ipAddrTypeAvaibility);
        if (naiRealm != null)
            sb.append(" naiRealm: ").append(naiRealm);
        if (cellularNetwork != null)
            sb.append(" cellularNetwork: ").append(cellularNetwork);
        if (domainName != null)
            sb.append(" domainName: ").append(domainName);
        if (operatorFriendlyName != null)
            sb.append(" operatorFriendlyName: ").append(operatorFriendlyName);
        if (wanMetrics != null)
            sb.append(" wanMetrics: ").append(wanMetrics);
        if (connectionCapability != null)
            sb.append(" connectionCapability: ").append(connectionCapability);
        if (osuProviderList != null)
            sb.append(" osuProviderList: (size=" + osuProviderList.size() + ")");
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeValue(bssid);
        out.writeValue(venueName);
        out.writeValue(networkAuthType);
        out.writeValue(roamingConsortium);
        out.writeValue(ipAddrTypeAvaibility);
        out.writeValue(naiRealm);
        out.writeValue(cellularNetwork);
        out.writeValue(domainName);
        out.writeValue(operatorFriendlyName);
        out.writeValue(wanMetrics);
        out.writeValue(connectionCapability);
        if (osuProviderList == null) {
            out.writeInt(0);
        } else {
            out.writeInt(osuProviderList.size());
            for (PasspointOsuProvider osu : osuProviderList)
                osu.writeToParcel(out, flags);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Parcelable.Creator<PasspointInfo> CREATOR =
            new Parcelable.Creator<PasspointInfo>() {
        @Override
        public PasspointInfo createFromParcel(Parcel in) {
            PasspointInfo p = new PasspointInfo();
            p.bssid = (String) in.readValue(String.class.getClassLoader());
            p.venueName = (String) in.readValue(String.class.getClassLoader());
            p.networkAuthType = (String) in.readValue(String.class.getClassLoader());
            p.roamingConsortium = (String) in.readValue(String.class.getClassLoader());
            p.ipAddrTypeAvaibility = (String) in.readValue(String.class.getClassLoader());
            p.naiRealm = (String) in.readValue(String.class.getClassLoader());
            p.cellularNetwork = (String) in.readValue(String.class.getClassLoader());
            p.domainName = (String) in.readValue(String.class.getClassLoader());
            p.operatorFriendlyName = (String) in.readValue(String.class.getClassLoader());
            p.wanMetrics = (String) in.readValue(String.class.getClassLoader());
            p.connectionCapability = (String) in.readValue(String.class.getClassLoader());
            int n = in.readInt();
            if (n > 0) {
                p.osuProviderList = new ArrayList<PasspointOsuProvider>();
                for (int i = 0; i < n; i++) {
                    PasspointOsuProvider osu = PasspointOsuProvider.CREATOR.createFromParcel(in);
                    p.osuProviderList.add(osu);
                }
            }
            return p;
        }

        @Override
        public PasspointInfo[] newArray(int size) {
            return new PasspointInfo[size];
        }
    };
}
