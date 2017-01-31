/**
 * Copyright (c) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2.pps;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class representing Policy subtree in PerProviderSubscription (PPS)
 * Management Object (MO) tree.
 *
 * The Policy specifies additional criteria for Passpoint network selections, such as preferred
 * roaming partner, minimum backhaul bandwidth, and etc. It also provides the meta data for
 * updating the policy.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * @hide
 */
public final class Policy implements Parcelable {
    private static final String TAG = "Policy";

    /**
     * Default priority for preferred roaming partner.
     */
    public static final int PREFERRED_ROAMING_PARTNER_DEFAULT_PRIORITY = 128;

    /**
     * Maximum number of SSIDs in the exclusion list.
     */
    private static final int MAX_EXCLUSION_SSIDS = 128;

    /**
     * Maximum byte for SSID.
     */
    private static final int MAX_SSID_BYTES = 32;

    /**
     * Maximum bytes for port string in {@link #requiredProtoPortMap}.
     */
    private static final int MAX_PORT_STRING_BYTES = 64;

    /**
     * Integer value used for indicating null value in the Parcel.
     */
    private static final int NULL_VALUE = -1;

    /**
     * Minimum available downlink/uplink bandwidth (in kilobits per second) required when
     * selecting a network from home providers.
     *
     * The bandwidth is calculated as the LinkSpeed * (1 – LinkLoad/255), where LinkSpeed
     * and LinkLoad parameters are drawn from the WAN Metrics ANQP element at that hotspot.
     *
     * Using Long.MIN_VALUE to indicate unset value.
     */
    public long minHomeDownlinkBandwidth = Long.MIN_VALUE;
    public long minHomeUplinkBandwidth = Long.MIN_VALUE;

    /**
     * Minimum available downlink/uplink bandwidth (in kilobits per second) required when
     * selecting a network from roaming providers.
     *
     * The bandwidth is calculated as the LinkSpeed * (1 – LinkLoad/255), where LinkSpeed
     * and LinkLoad parameters are drawn from the WAN Metrics ANQP element at that hotspot.
     *
     * Using Long.MIN_VALUE to indicate unset value.
     */
    public long minRoamingDownlinkBandwidth = Long.MIN_VALUE;
    public long minRoamingUplinkBandwidth = Long.MIN_VALUE;

    /**
     * List of SSIDs that are not preferred by the Home SP.
     */
    public String[] excludedSsidList = null;

    /**
     * List of IP protocol and port number required by one or more operator supported application.
     * The port string contained one or more port numbers delimited by ",".
     */
    public Map<Integer, String> requiredProtoPortMap = null;

    /**
     * This specifies the maximum acceptable BSS load policy.  This is used to prevent device
     * from joining an AP whose channel is overly congested with traffic.
     * Using Integer.MIN_VALUE to indicate unset value.
     */
    public int maximumBssLoadValue = Integer.MIN_VALUE;

    /**
     * Policy associated with a roaming provider.  This specifies a priority associated
     * with a roaming provider for given list of countries.
     *
     * Contains field under PerProviderSubscription/Policy/PreferredRoamingPartnerList.
     */
    public static final class RoamingPartner implements Parcelable {
        /**
         * FQDN of the roaming partner.
         */
        public String fqdn = null;

        /**
         * Flag indicating the exact match of FQDN is required for FQDN matching.
         *
         * When this flag is set to false, sub-domain matching is used.  For example, when
         * {@link #fqdn} s set to "example.com", "host.example.com" would be a match.
         */
        public boolean fqdnExactMatch = false;

        /**
         * Priority associated with this roaming partner policy.
         */
        public int priority = PREFERRED_ROAMING_PARTNER_DEFAULT_PRIORITY;

        /**
         * A string contained One or more, comma delimited (i.e., ",") ISO/IEC 3166-1 two
         * character country strings or the country-independent value, "*".
         */
        public String countries = null;

        public RoamingPartner() {}

        public RoamingPartner(RoamingPartner source) {
            if (source != null) {
                fqdn = source.fqdn;
                fqdnExactMatch = source.fqdnExactMatch;
                priority = source.priority;
                countries = source.countries;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(fqdn);
            dest.writeInt(fqdnExactMatch ? 1 : 0);
            dest.writeInt(priority);
            dest.writeString(countries);
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (!(thatObject instanceof RoamingPartner)) {
                return false;
            }

            RoamingPartner that = (RoamingPartner) thatObject;
            return TextUtils.equals(fqdn, that.fqdn)
                    && fqdnExactMatch == that.fqdnExactMatch
                    && priority == that.priority
                    && TextUtils.equals(countries, that.countries);
        }

        /**
         * Validate RoamingParnter data.
         *
         * @return true on success
         */
        public boolean validate() {
            if (TextUtils.isEmpty(fqdn)) {
                Log.d(TAG, "Missing FQDN");
                return false;
            }
            if (TextUtils.isEmpty(countries)) {
                Log.d(TAG, "Missing countries");
                return false;
            }
            return true;
        }

        public static final Creator<RoamingPartner> CREATOR =
            new Creator<RoamingPartner>() {
                @Override
                public RoamingPartner createFromParcel(Parcel in) {
                    RoamingPartner roamingPartner = new RoamingPartner();
                    roamingPartner.fqdn = in.readString();
                    roamingPartner.fqdnExactMatch = in.readInt() != 0;
                    roamingPartner.priority = in.readInt();
                    roamingPartner.countries = in.readString();
                    return roamingPartner;
                }

                @Override
                public RoamingPartner[] newArray(int size) {
                    return new RoamingPartner[size];
                }
            };
    }
    public List<RoamingPartner> preferredRoamingPartnerList = null;

    /**
     * Meta data used for policy update.
     */
    public UpdateParameter policyUpdate = null;

    /**
     * Constructor for creating Policy with default values.
     */
    public Policy() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public Policy(Policy source) {
        if (source == null) {
            return;
        }
        minHomeDownlinkBandwidth = source.minHomeDownlinkBandwidth;
        minHomeUplinkBandwidth = source.minHomeUplinkBandwidth;
        minRoamingDownlinkBandwidth = source.minRoamingDownlinkBandwidth;
        minRoamingUplinkBandwidth = source.minRoamingUplinkBandwidth;
        maximumBssLoadValue = source.maximumBssLoadValue;
        if (source.excludedSsidList != null) {
            excludedSsidList = Arrays.copyOf(source.excludedSsidList,
                    source.excludedSsidList.length);
        }
        if (source.requiredProtoPortMap != null) {
            requiredProtoPortMap = Collections.unmodifiableMap(source.requiredProtoPortMap);
        }
        if (source.preferredRoamingPartnerList != null) {
            preferredRoamingPartnerList = Collections.unmodifiableList(
                    source.preferredRoamingPartnerList);
        }
        if (source.policyUpdate != null) {
            policyUpdate = new UpdateParameter(source.policyUpdate);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(minHomeDownlinkBandwidth);
        dest.writeLong(minHomeUplinkBandwidth);
        dest.writeLong(minRoamingDownlinkBandwidth);
        dest.writeLong(minRoamingUplinkBandwidth);
        dest.writeStringArray(excludedSsidList);
        writeProtoPortMap(dest, requiredProtoPortMap);
        dest.writeInt(maximumBssLoadValue);
        writeRoamingPartnerList(dest, flags, preferredRoamingPartnerList);
        dest.writeParcelable(policyUpdate, flags);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof Policy)) {
            return false;
        }
        Policy that = (Policy) thatObject;

        return minHomeDownlinkBandwidth == that.minHomeDownlinkBandwidth
                && minHomeUplinkBandwidth == that.minHomeUplinkBandwidth
                && minRoamingDownlinkBandwidth == that.minRoamingDownlinkBandwidth
                && minRoamingUplinkBandwidth == that.minRoamingUplinkBandwidth
                && Arrays.equals(excludedSsidList, that.excludedSsidList)
                && (requiredProtoPortMap == null ? that.requiredProtoPortMap == null
                        : requiredProtoPortMap.equals(that.requiredProtoPortMap))
                && maximumBssLoadValue == that.maximumBssLoadValue
                && (preferredRoamingPartnerList == null ? that.preferredRoamingPartnerList == null
                        : preferredRoamingPartnerList.equals(that.preferredRoamingPartnerList))
                && (policyUpdate == null ? that.policyUpdate == null
                        : policyUpdate.equals(that.policyUpdate));
    }

    /**
     * Validate Policy data.
     *
     * @return true on success
     */
    public boolean validate() {
        if (policyUpdate == null) {
            Log.d(TAG, "PolicyUpdate not specified");
            return false;
        }
        if (!policyUpdate.validate()) {
            return false;
        }

        // Validate SSID exclusion list.
        if (excludedSsidList != null) {
            if (excludedSsidList.length > MAX_EXCLUSION_SSIDS) {
                Log.d(TAG, "SSID exclusion list size exceeded the max: "
                        + excludedSsidList.length);
                return false;
            }
            for (String ssid : excludedSsidList) {
                if (ssid.getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
                    Log.d(TAG, "Invalid SSID: " + ssid);
                    return false;
                }
            }
        }
        // Validate required protocol to port map.
        if (requiredProtoPortMap != null) {
            for (Map.Entry<Integer, String> entry : requiredProtoPortMap.entrySet()) {
                String portNumber = entry.getValue();
                if (portNumber.getBytes(StandardCharsets.UTF_8).length > MAX_PORT_STRING_BYTES) {
                    Log.d(TAG, "PortNumber string bytes exceeded the max: " + portNumber);
                    return false;
                }
            }
        }
        // Validate preferred roaming partner list.
        if (preferredRoamingPartnerList != null) {
            for (RoamingPartner partner : preferredRoamingPartnerList) {
                if (!partner.validate()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static final Creator<Policy> CREATOR =
        new Creator<Policy>() {
            @Override
            public Policy createFromParcel(Parcel in) {
                Policy policy = new Policy();
                policy.minHomeDownlinkBandwidth = in.readLong();
                policy.minHomeUplinkBandwidth = in.readLong();
                policy.minRoamingDownlinkBandwidth = in.readLong();
                policy.minRoamingUplinkBandwidth = in.readLong();
                policy.excludedSsidList = in.createStringArray();
                policy.requiredProtoPortMap = readProtoPortMap(in);
                policy.maximumBssLoadValue = in.readInt();
                policy.preferredRoamingPartnerList = readRoamingPartnerList(in);
                policy.policyUpdate = in.readParcelable(null);
                return policy;
            }

            @Override
            public Policy[] newArray(int size) {
                return new Policy[size];
            }

            /**
             * Helper function for reading IP Protocol to Port Number map from a Parcel.
             *
             * @param in The Parcel to read from
             * @return Map of IP protocol to port number
             */
            private Map<Integer, String> readProtoPortMap(Parcel in) {
                int size = in.readInt();
                if (size == NULL_VALUE) {
                    return null;
                }
                Map<Integer, String> protoPortMap = new HashMap<>(size);
                for (int i = 0; i < size; i++) {
                    int key = in.readInt();
                    String value = in.readString();
                    protoPortMap.put(key, value);
                }
                return protoPortMap;
            }

            /**
             * Helper function for reading roaming partner list from a Parcel.
             *
             * @param in The Parcel to read from
             * @return List of roaming partners
             */
            private List<RoamingPartner> readRoamingPartnerList(Parcel in) {
                int size = in.readInt();
                if (size == NULL_VALUE) {
                    return null;
                }
                List<RoamingPartner> partnerList = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    partnerList.add(in.readParcelable(null));
                }
                return partnerList;
            }

        };

    /**
     * Helper function for writing IP Protocol to Port Number map to a Parcel.
     *
     * @param dest The Parcel to write to
     * @param protoPortMap The map to write
     */
    private static void writeProtoPortMap(Parcel dest, Map<Integer, String> protoPortMap) {
        if (protoPortMap == null) {
            dest.writeInt(NULL_VALUE);
            return;
        }
        dest.writeInt(protoPortMap.size());
        for (Map.Entry<Integer, String> entry : protoPortMap.entrySet()) {
            dest.writeInt(entry.getKey());
            dest.writeString(entry.getValue());
        }
    }

    /**
     * Helper function for writing roaming partner list to a Parcel.
     *
     * @param dest The Parcel to write to
     * @param flags The flag about how the object should be written
     * @param partnerList The partner list to write
     */
    private static void writeRoamingPartnerList(Parcel dest, int flags,
            List<RoamingPartner> partnerList) {
        if (partnerList == null) {
            dest.writeInt(NULL_VALUE);
            return;
        }
        dest.writeInt(partnerList.size());
        for (RoamingPartner partner : partnerList) {
            dest.writeParcelable(partner, flags);
        }
    }
}
