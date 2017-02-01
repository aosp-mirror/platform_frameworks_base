/**
 * Copyright (c) 2016, The Android Open Source Project
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

import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Class representing HomeSP subtree in PerProviderSubscription (PPS)
 * Management Object (MO) tree.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * @hide
 */
public final class HomeSP implements Parcelable {
    private static final String TAG = "HomeSP";

    /**
     * Maximum number of bytes allowed for a SSID.
     */
    private static final int MAX_SSID_BYTES = 32;

    /**
     * Integer value used for indicating null value in the Parcel.
     */
    private static final int NULL_VALUE = -1;

    /**
     * FQDN (Fully Qualified Domain Name) of this home service provider.
     */
    private String mFqdn = null;
    public void setFqdn(String fqdn) {
        mFqdn = fqdn;
    }
    public String getFqdn() {
        return mFqdn;
    }

    /**
     * Friendly name of this home service provider.
     */
    private String mFriendlyName = null;
    public void setFriendlyName(String friendlyName) {
        mFriendlyName = friendlyName;
    }
    public String getFriendlyName() {
        return mFriendlyName;
    }

    /**
     * Icon URL of this home service provider.
     */
    private String mIconUrl = null;
    public void setIconUrl(String iconUrl) {
        mIconUrl = iconUrl;
    }
    public String getIconUrl() {
        return mIconUrl;
    }

    /**
     * <SSID, HESSID> duple of the networks that are consider home networks.
     *
     * According to the Section 9.1.2 of the Hotspot 2.0 Release 2 Technical Specification,
     * all nodes in the PSS MO are encoded using UTF-8 unless stated otherwise.  Thus, the SSID
     * string is assumed to be encoded using UTF-8.
     */
    private Map<String, Long> mHomeNetworkIds = null;
    public void setHomeNetworkIds(Map<String, Long> homeNetworkIds) {
        mHomeNetworkIds = homeNetworkIds;
    }
    public Map<String, Long> getHomeNetworkIds() {
        return mHomeNetworkIds;
    }

    /**
     * Used for determining if this provider is a member of a given Hotspot provider.
     * Every Organization Identifiers (OIs) in this list are required to match an OI in the
     * the Roaming Consortium advertised by a Hotspot, in order to consider this provider
     * as a member of that Hotspot provider (e.g. successful authentication with such Hotspot
     * is possible).
     *
     * Refer to HomeSP/HomeOIList subtree in PerProviderSubscription (PPS) Management Object
     * (MO) tree for more detail.
     */
    private long[] mMatchAllOIs = null;
    public void setMatchAllOIs(long[] matchAllOIs) {
        mMatchAllOIs = matchAllOIs;
    }
    public long[] getMatchAllOIs() {
        return mMatchAllOIs;
    }

    /**
     * Used for determining if this provider is a member of a given Hotspot provider.
     * Matching of any Organization Identifiers (OIs) in this list with an OI in the
     * Roaming Consortium advertised by a Hotspot, will consider this provider as a member
     * of that Hotspot provider (e.g. successful authentication with such Hotspot
     * is possible).
     *
     * {@link #mMatchAllOIs} will have precedence over this one, meaning this list will
     * only be used for matching if {@link #mMatchAllOIs} is null or empty.
     *
     * Refer to HomeSP/HomeOIList subtree in PerProviderSubscription (PPS) Management Object
     * (MO) tree for more detail.
     */
    private long[] mMatchAnyOIs = null;
    public void setMatchAnyOIs(long[] matchAnyOIs) {
        mMatchAnyOIs = matchAnyOIs;
    }
    public long[] getMatchAnysOIs() {
        return mMatchAnyOIs;
    }

    /**
     * List of FQDN (Fully Qualified Domain Name) of partner providers.
     * These providers should also be regarded as home Hotspot operators.
     * This relationship is most likely achieved via a commercial agreement or
     * operator merges between the providers.
     */
    private String[] mOtherHomePartners = null;
    public void setOtherHomePartners(String[] otherHomePartners) {
        mOtherHomePartners = otherHomePartners;
    }
    public String[] getOtherHomePartners() {
        return mOtherHomePartners;
    }

    /**
     * List of Organization Identifiers (OIs) identifying a roaming consortium of
     * which this provider is a member.
     */
    private long[] mRoamingConsortiumOIs = null;
    public void setRoamingConsortiumOIs(long[] roamingConsortiumOIs) {
        mRoamingConsortiumOIs = roamingConsortiumOIs;
    }
    public long[] getRoamingConsortiumOIs() {
        return mRoamingConsortiumOIs;
    }

    /**
     * Constructor for creating HomeSP with default values.
     */
    public HomeSP() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public HomeSP(HomeSP source) {
        if (source == null) {
            return;
        }
        mFqdn = source.mFqdn;
        mFriendlyName = source.mFriendlyName;
        mIconUrl = source.mIconUrl;
        if (source.mHomeNetworkIds != null) {
            mHomeNetworkIds = Collections.unmodifiableMap(source.mHomeNetworkIds);
        }
        if (source.mMatchAllOIs != null) {
            mMatchAllOIs = Arrays.copyOf(source.mMatchAllOIs, source.mMatchAllOIs.length);
        }
        if (source.mMatchAnyOIs != null) {
            mMatchAnyOIs = Arrays.copyOf(source.mMatchAnyOIs, source.mMatchAnyOIs.length);
        }
        if (source.mOtherHomePartners != null) {
            mOtherHomePartners = Arrays.copyOf(source.mOtherHomePartners,
                    source.mOtherHomePartners.length);
        }
        if (source.mRoamingConsortiumOIs != null) {
            mRoamingConsortiumOIs = Arrays.copyOf(source.mRoamingConsortiumOIs,
                    source.mRoamingConsortiumOIs.length);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mFqdn);
        dest.writeString(mFriendlyName);
        dest.writeString(mIconUrl);
        writeHomeNetworkIds(dest, mHomeNetworkIds);
        dest.writeLongArray(mMatchAllOIs);
        dest.writeLongArray(mMatchAnyOIs);
        dest.writeStringArray(mOtherHomePartners);
        dest.writeLongArray(mRoamingConsortiumOIs);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HomeSP)) {
            return false;
        }
        HomeSP that = (HomeSP) thatObject;

        return TextUtils.equals(mFqdn, that.mFqdn)
                && TextUtils.equals(mFriendlyName, that.mFriendlyName)
                && TextUtils.equals(mIconUrl, that.mIconUrl)
                && (mHomeNetworkIds == null ? that.mHomeNetworkIds == null
                        : mHomeNetworkIds.equals(that.mHomeNetworkIds))
                && Arrays.equals(mMatchAllOIs, that.mMatchAllOIs)
                && Arrays.equals(mMatchAnyOIs, that.mMatchAnyOIs)
                && Arrays.equals(mOtherHomePartners, that.mOtherHomePartners)
                && Arrays.equals(mRoamingConsortiumOIs, that.mRoamingConsortiumOIs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFqdn, mFriendlyName, mIconUrl, mHomeNetworkIds, mMatchAllOIs,
                mMatchAnyOIs, mOtherHomePartners, mRoamingConsortiumOIs);
    }

    /**
     * Validate HomeSP data.
     *
     * @return true on success or false on failure
     */
    public boolean validate() {
        if (TextUtils.isEmpty(mFqdn)) {
            Log.d(TAG, "Missing FQDN");
            return false;
        }
        if (TextUtils.isEmpty(mFriendlyName)) {
            Log.d(TAG, "Missing friendly name");
            return false;
        }
        // Verify SSIDs specified in the NetworkID
        if (mHomeNetworkIds != null) {
            for (Map.Entry<String, Long> entry : mHomeNetworkIds.entrySet()) {
                if (entry.getKey() == null ||
                        entry.getKey().getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
                    Log.d(TAG, "Invalid SSID in HomeNetworkIDs");
                    return false;
                }
            }
        }
        return true;
    }

    public static final Creator<HomeSP> CREATOR =
        new Creator<HomeSP>() {
            @Override
            public HomeSP createFromParcel(Parcel in) {
                HomeSP homeSp = new HomeSP();
                homeSp.setFqdn(in.readString());
                homeSp.setFriendlyName(in.readString());
                homeSp.setIconUrl(in.readString());
                homeSp.setHomeNetworkIds(readHomeNetworkIds(in));
                homeSp.setMatchAllOIs(in.createLongArray());
                homeSp.setMatchAnyOIs(in.createLongArray());
                homeSp.setOtherHomePartners(in.createStringArray());
                homeSp.setRoamingConsortiumOIs(in.createLongArray());
                return homeSp;
            }

            @Override
            public HomeSP[] newArray(int size) {
                return new HomeSP[size];
            }

            /**
             * Helper function for reading a Home Network IDs map from a Parcel.
             *
             * @param in The Parcel to read from
             * @return Map of home network IDs
             */
            private Map<String, Long> readHomeNetworkIds(Parcel in) {
                int size = in.readInt();
                if (size == NULL_VALUE) {
                    return null;
                }
                Map<String, Long> networkIds = new HashMap<>(size);
                for (int i = 0; i < size; i++) {
                    String key = in.readString();
                    Long value = null;
                    long readValue = in.readLong();
                    if (readValue != NULL_VALUE) {
                        value = Long.valueOf(readValue);
                    }
                    networkIds.put(key, value);
                }
                return networkIds;
            }
        };

    /**
     * Helper function for writing Home Network IDs map to a Parcel.
     *
     * @param dest The Parcel to write to
     * @param networkIds The map of home network IDs
     */
    private static void writeHomeNetworkIds(Parcel dest, Map<String, Long> networkIds) {
        if (networkIds == null) {
            dest.writeInt(NULL_VALUE);
            return;
        }
        dest.writeInt(networkIds.size());
        for (Map.Entry<String, Long> entry : networkIds.entrySet()) {
            dest.writeString(entry.getKey());
            if (entry.getValue() == null) {
                dest.writeLong(NULL_VALUE);
            } else {
                dest.writeLong(entry.getValue());
            }
        }
    }
}
