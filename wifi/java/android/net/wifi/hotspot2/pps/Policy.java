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

import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_NUMBER_OF_ENTRIES;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_STRING_LENGTH;

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
import java.util.Objects;

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
    private long mMinHomeDownlinkBandwidth = Long.MIN_VALUE;
    public void setMinHomeDownlinkBandwidth(long minHomeDownlinkBandwidth) {
        mMinHomeDownlinkBandwidth = minHomeDownlinkBandwidth;
    }
    public long getMinHomeDownlinkBandwidth() {
        return mMinHomeDownlinkBandwidth;
    }
    private long mMinHomeUplinkBandwidth = Long.MIN_VALUE;
    public void setMinHomeUplinkBandwidth(long minHomeUplinkBandwidth) {
        mMinHomeUplinkBandwidth = minHomeUplinkBandwidth;
    }
    public long getMinHomeUplinkBandwidth() {
        return mMinHomeUplinkBandwidth;
    }

    /**
     * Minimum available downlink/uplink bandwidth (in kilobits per second) required when
     * selecting a network from roaming providers.
     *
     * The bandwidth is calculated as the LinkSpeed * (1 – LinkLoad/255), where LinkSpeed
     * and LinkLoad parameters are drawn from the WAN Metrics ANQP element at that hotspot.
     *
     * Using Long.MIN_VALUE to indicate unset value.
     */
    private long mMinRoamingDownlinkBandwidth = Long.MIN_VALUE;
    public void setMinRoamingDownlinkBandwidth(long minRoamingDownlinkBandwidth) {
        mMinRoamingDownlinkBandwidth = minRoamingDownlinkBandwidth;
    }
    public long getMinRoamingDownlinkBandwidth() {
        return mMinRoamingDownlinkBandwidth;
    }
    private long mMinRoamingUplinkBandwidth = Long.MIN_VALUE;
    public void setMinRoamingUplinkBandwidth(long minRoamingUplinkBandwidth) {
        mMinRoamingUplinkBandwidth = minRoamingUplinkBandwidth;
    }
    public long getMinRoamingUplinkBandwidth() {
        return mMinRoamingUplinkBandwidth;
    }

    /**
     * List of SSIDs that are not preferred by the Home SP.
     */
    private String[] mExcludedSsidList = null;
    public void setExcludedSsidList(String[] excludedSsidList) {
        mExcludedSsidList = excludedSsidList;
    }
    public String[] getExcludedSsidList() {
        return mExcludedSsidList;
    }

    /**
     * List of IP protocol and port number required by one or more operator supported application.
     * The port string contained one or more port numbers delimited by ",".
     */
    private Map<Integer, String> mRequiredProtoPortMap = null;
    public void setRequiredProtoPortMap(Map<Integer, String> requiredProtoPortMap) {
        mRequiredProtoPortMap = requiredProtoPortMap;
    }
    public Map<Integer, String> getRequiredProtoPortMap() {
        return mRequiredProtoPortMap;
    }

    /**
     * This specifies the maximum acceptable BSS load policy.  This is used to prevent device
     * from joining an AP whose channel is overly congested with traffic.
     * Using Integer.MIN_VALUE to indicate unset value.
     */
    private int mMaximumBssLoadValue = Integer.MIN_VALUE;
    public void setMaximumBssLoadValue(int maximumBssLoadValue) {
        mMaximumBssLoadValue = maximumBssLoadValue;
    }
    public int getMaximumBssLoadValue() {
        return mMaximumBssLoadValue;
    }

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
        private String mFqdn = null;
        public void setFqdn(String fqdn) {
            mFqdn = fqdn;
        }
        public String getFqdn() {
            return mFqdn;
        }

        /**
         * Flag indicating the exact match of FQDN is required for FQDN matching.
         *
         * When this flag is set to false, sub-domain matching is used.  For example, when
         * {@link #fqdn} s set to "example.com", "host.example.com" would be a match.
         */
        private boolean mFqdnExactMatch = false;
        public void setFqdnExactMatch(boolean fqdnExactMatch) {
            mFqdnExactMatch = fqdnExactMatch;
        }
        public boolean getFqdnExactMatch() {
            return mFqdnExactMatch;
        }

        /**
         * Priority associated with this roaming partner policy.
         * Using Integer.MIN_VALUE to indicate unset value.
         */
        private int mPriority = Integer.MIN_VALUE;
        public void setPriority(int priority) {
            mPriority = priority;
        }
        public int getPriority() {
            return mPriority;
        }

        /**
         * A string contained One or more, comma delimited (i.e., ",") ISO/IEC 3166-1 two
         * character country strings or the country-independent value, "*".
         */
        private String mCountries = null;
        public void setCountries(String countries) {
            mCountries = countries;
        }
        public String getCountries() {
            return mCountries;
        }

        public RoamingPartner() {}

        public RoamingPartner(RoamingPartner source) {
            if (source != null) {
                mFqdn = source.mFqdn;
                mFqdnExactMatch = source.mFqdnExactMatch;
                mPriority = source.mPriority;
                mCountries = source.mCountries;
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mFqdn);
            dest.writeInt(mFqdnExactMatch ? 1 : 0);
            dest.writeInt(mPriority);
            dest.writeString(mCountries);
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
            return TextUtils.equals(mFqdn, that.mFqdn)
                    && mFqdnExactMatch == that.mFqdnExactMatch
                    && mPriority == that.mPriority
                    && TextUtils.equals(mCountries, that.mCountries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFqdn, mFqdnExactMatch, mPriority, mCountries);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("FQDN: ").append(mFqdn).append("\n");
            builder.append("ExactMatch: ").append("mFqdnExactMatch").append("\n");
            builder.append("Priority: ").append(mPriority).append("\n");
            builder.append("Countries: ").append(mCountries).append("\n");
            return builder.toString();
        }

        /**
         * Validate RoamingParnter data.
         *
         * @return true on success
         * @hide
         */
        public boolean validate() {
            if (TextUtils.isEmpty(mFqdn)) {
                Log.e(TAG, "Missing FQDN");
                return false;
            }
            if (mFqdn.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_LENGTH) {
                Log.e(TAG, "FQDN is too long");
                return false;
            }
            if (TextUtils.isEmpty(mCountries)) {
                Log.e(TAG, "Missing countries");
                return false;
            }
            if (mCountries.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_LENGTH) {
                Log.e(TAG, "country is too long");
                return false;
            }
            return true;
        }

        public static final @android.annotation.NonNull Creator<RoamingPartner> CREATOR =
            new Creator<RoamingPartner>() {
                @Override
                public RoamingPartner createFromParcel(Parcel in) {
                    RoamingPartner roamingPartner = new RoamingPartner();
                    roamingPartner.setFqdn(in.readString());
                    roamingPartner.setFqdnExactMatch(in.readInt() != 0);
                    roamingPartner.setPriority(in.readInt());
                    roamingPartner.setCountries(in.readString());
                    return roamingPartner;
                }

                @Override
                public RoamingPartner[] newArray(int size) {
                    return new RoamingPartner[size];
                }
            };
    }
    private List<RoamingPartner> mPreferredRoamingPartnerList = null;
    public void setPreferredRoamingPartnerList(List<RoamingPartner> partnerList) {
        mPreferredRoamingPartnerList = partnerList;
    }
    public List<RoamingPartner> getPreferredRoamingPartnerList() {
        return mPreferredRoamingPartnerList;
    }

    /**
     * Meta data used for policy update.
     */
    private UpdateParameter mPolicyUpdate = null;
    public void setPolicyUpdate(UpdateParameter policyUpdate) {
        mPolicyUpdate = policyUpdate;
    }
    public UpdateParameter getPolicyUpdate() {
        return mPolicyUpdate;
    }

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
        mMinHomeDownlinkBandwidth = source.mMinHomeDownlinkBandwidth;
        mMinHomeUplinkBandwidth = source.mMinHomeUplinkBandwidth;
        mMinRoamingDownlinkBandwidth = source.mMinRoamingDownlinkBandwidth;
        mMinRoamingUplinkBandwidth = source.mMinRoamingUplinkBandwidth;
        mMaximumBssLoadValue = source.mMaximumBssLoadValue;
        if (source.mExcludedSsidList != null) {
            mExcludedSsidList = Arrays.copyOf(source.mExcludedSsidList,
                    source.mExcludedSsidList.length);
        }
        if (source.mRequiredProtoPortMap != null) {
            mRequiredProtoPortMap = Collections.unmodifiableMap(source.mRequiredProtoPortMap);
        }
        if (source.mPreferredRoamingPartnerList != null) {
            mPreferredRoamingPartnerList = Collections.unmodifiableList(
                    source.mPreferredRoamingPartnerList);
        }
        if (source.mPolicyUpdate != null) {
            mPolicyUpdate = new UpdateParameter(source.mPolicyUpdate);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mMinHomeDownlinkBandwidth);
        dest.writeLong(mMinHomeUplinkBandwidth);
        dest.writeLong(mMinRoamingDownlinkBandwidth);
        dest.writeLong(mMinRoamingUplinkBandwidth);
        dest.writeStringArray(mExcludedSsidList);
        writeProtoPortMap(dest, mRequiredProtoPortMap);
        dest.writeInt(mMaximumBssLoadValue);
        writeRoamingPartnerList(dest, flags, mPreferredRoamingPartnerList);
        dest.writeParcelable(mPolicyUpdate, flags);
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

        return mMinHomeDownlinkBandwidth == that.mMinHomeDownlinkBandwidth
                && mMinHomeUplinkBandwidth == that.mMinHomeUplinkBandwidth
                && mMinRoamingDownlinkBandwidth == that.mMinRoamingDownlinkBandwidth
                && mMinRoamingUplinkBandwidth == that.mMinRoamingUplinkBandwidth
                && Arrays.equals(mExcludedSsidList, that.mExcludedSsidList)
                && (mRequiredProtoPortMap == null ? that.mRequiredProtoPortMap == null
                        : mRequiredProtoPortMap.equals(that.mRequiredProtoPortMap))
                && mMaximumBssLoadValue == that.mMaximumBssLoadValue
                && (mPreferredRoamingPartnerList == null
                        ? that.mPreferredRoamingPartnerList == null
                        : mPreferredRoamingPartnerList.equals(that.mPreferredRoamingPartnerList))
                && (mPolicyUpdate == null ? that.mPolicyUpdate == null
                        : mPolicyUpdate.equals(that.mPolicyUpdate));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMinHomeDownlinkBandwidth, mMinHomeUplinkBandwidth,
                mMinRoamingDownlinkBandwidth, mMinRoamingUplinkBandwidth, mExcludedSsidList,
                mRequiredProtoPortMap, mMaximumBssLoadValue, mPreferredRoamingPartnerList,
                mPolicyUpdate);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("MinHomeDownlinkBandwidth: ").append(mMinHomeDownlinkBandwidth)
                .append("\n");
        builder.append("MinHomeUplinkBandwidth: ").append(mMinHomeUplinkBandwidth).append("\n");
        builder.append("MinRoamingDownlinkBandwidth: ").append(mMinRoamingDownlinkBandwidth)
                .append("\n");
        builder.append("MinRoamingUplinkBandwidth: ").append(mMinRoamingUplinkBandwidth)
                .append("\n");
        builder.append("ExcludedSSIDList: ").append(mExcludedSsidList).append("\n");
        builder.append("RequiredProtoPortMap: ").append(mRequiredProtoPortMap).append("\n");
        builder.append("MaximumBSSLoadValue: ").append(mMaximumBssLoadValue).append("\n");
        builder.append("PreferredRoamingPartnerList: ").append(mPreferredRoamingPartnerList)
                .append("\n");
        if (mPolicyUpdate != null) {
            builder.append("PolicyUpdate Begin ---\n");
            builder.append(mPolicyUpdate);
            builder.append("PolicyUpdate End ---\n");
        }
        return builder.toString();
    }

    /**
     * Validate Policy data.
     *
     * @return true on success
     * @hide
     */
    public boolean validate() {
        if (mPolicyUpdate == null) {
            Log.d(TAG, "PolicyUpdate not specified");
            return false;
        }
        if (!mPolicyUpdate.validate()) {
            return false;
        }

        // Validate SSID exclusion list.
        if (mExcludedSsidList != null) {
            if (mExcludedSsidList.length > MAX_EXCLUSION_SSIDS) {
                Log.d(TAG, "SSID exclusion list size exceeded the max: "
                        + mExcludedSsidList.length);
                return false;
            }
            for (String ssid : mExcludedSsidList) {
                if (ssid.getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
                    Log.e(TAG, "Invalid SSID: " + ssid);
                    return false;
                }
            }
        }
        // Validate required protocol to port map.
        if (mRequiredProtoPortMap != null) {
            for (Map.Entry<Integer, String> entry : mRequiredProtoPortMap.entrySet()) {
                int protocol = entry.getKey();
                if (protocol < 0 || protocol > 255) {
                    Log.e(TAG, "Invalid IP protocol: " + protocol);
                    return false;
                }
                String portNumber = entry.getValue();
                if (portNumber.getBytes(StandardCharsets.UTF_8).length > MAX_PORT_STRING_BYTES) {
                    Log.e(TAG, "PortNumber string bytes exceeded the max: " + portNumber);
                    return false;
                }
            }
        }
        // Validate preferred roaming partner list.
        if (mPreferredRoamingPartnerList != null) {
            if (mPreferredRoamingPartnerList.size() > MAX_NUMBER_OF_ENTRIES) {
                Log.e(TAG, "Number of the Preferred Roaming Partner exceed the limit");
                return false;
            }
            for (RoamingPartner partner : mPreferredRoamingPartnerList) {
                if (!partner.validate()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static final @android.annotation.NonNull Creator<Policy> CREATOR =
        new Creator<Policy>() {
            @Override
            public Policy createFromParcel(Parcel in) {
                Policy policy = new Policy();
                policy.setMinHomeDownlinkBandwidth(in.readLong());
                policy.setMinHomeUplinkBandwidth(in.readLong());
                policy.setMinRoamingDownlinkBandwidth(in.readLong());
                policy.setMinRoamingUplinkBandwidth(in.readLong());
                policy.setExcludedSsidList(in.createStringArray());
                policy.setRequiredProtoPortMap(readProtoPortMap(in));
                policy.setMaximumBssLoadValue(in.readInt());
                policy.setPreferredRoamingPartnerList(readRoamingPartnerList(in));
                policy.setPolicyUpdate(in.readParcelable(null));
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
