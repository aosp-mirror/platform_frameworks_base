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

import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_HESSID_VALUE;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_NUMBER_OF_ENTRIES;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_NUMBER_OF_OI;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_OI_VALUE;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_STRING_LENGTH;
import static android.net.wifi.hotspot2.PasspointConfiguration.MAX_URL_BYTES;

import android.os.Parcel;
import android.os.Parcelable;
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
 */
public final class HomeSp implements Parcelable {
    private static final String TAG = "HomeSp";

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
    /**
     * Set the FQDN (Fully Qualified Domain Name) associated with this home service provider.
     *
     * @param fqdn The FQDN to set to
     */
    public void setFqdn(String fqdn) {
        mFqdn = fqdn;
    }
    /**
     * Get the FQDN (Fully Qualified Domain Name) associated with this home service provider.
     *
     * @return the FQDN associated with this home service provider
     */
    public String getFqdn() {
        return mFqdn;
    }

    /**
     * Friendly name of this home service provider.
     */
    private String mFriendlyName = null;
    /**
     * Set the friendly name associated with this home service provider.
     *
     * @param friendlyName The friendly name to set to
     */
    public void setFriendlyName(String friendlyName) {
        mFriendlyName = friendlyName;
    }
    /**
     * Get the friendly name associated with this home service provider.
     *
     * @return the friendly name associated with this home service provider
     */
    public String getFriendlyName() {
        return mFriendlyName;
    }

    /**
     * Icon URL of this home service provider.
     */
    private String mIconUrl = null;
    /**
     * @hide
     */
    public void setIconUrl(String iconUrl) {
        mIconUrl = iconUrl;
    }
    /**
     * @hide
     */
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
    /**
     * @hide
     */
    public void setHomeNetworkIds(Map<String, Long> homeNetworkIds) {
        mHomeNetworkIds = homeNetworkIds;
    }
    /**
     * @hide
     */
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
    private long[] mMatchAllOis = null;
    /**
     * @hide
     */
    public void setMatchAllOis(long[] matchAllOis) {
        mMatchAllOis = matchAllOis;
    }
    /**
     * @hide
     */
    public long[] getMatchAllOis() {
        return mMatchAllOis;
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
    private long[] mMatchAnyOis = null;
    /**
     * @hide
     */
    public void setMatchAnyOis(long[] matchAnyOis) {
        mMatchAnyOis = matchAnyOis;
    }
    /**
     * @hide
     */
    public long[] getMatchAnyOis() {
        return mMatchAnyOis;
    }

    /**
     * List of FQDN (Fully Qualified Domain Name) of partner providers.
     * These providers should also be regarded as home Hotspot operators.
     * This relationship is most likely achieved via a commercial agreement or
     * operator merges between the providers.
     */
    private String[] mOtherHomePartners = null;
    /**
     * @hide
     */
    public void setOtherHomePartners(String[] otherHomePartners) {
        mOtherHomePartners = otherHomePartners;
    }
    /**
     * @hide
     */
    public String[] getOtherHomePartners() {
        return mOtherHomePartners;
    }

    /**
     * List of Organization Identifiers (OIs) identifying a roaming consortium of
     * which this provider is a member.
     */
    private long[] mRoamingConsortiumOis = null;
    /**
     * Set the Organization Identifiers (OIs) identifying a roaming consortium of which this
     * provider is a member.
     *
     * @param roamingConsortiumOis Array of roaming consortium OIs
     */
    public void setRoamingConsortiumOis(long[] roamingConsortiumOis) {
        mRoamingConsortiumOis = roamingConsortiumOis;
    }
    /**
     * Get the Organization Identifiers (OIs) identifying a roaming consortium of which this
     * provider is a member.
     *
     * @return array of roaming consortium OIs
     */
    public long[] getRoamingConsortiumOis() {
        return mRoamingConsortiumOis;
    }

    /**
     * Constructor for creating HomeSp with default values.
     */
    public HomeSp() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public HomeSp(HomeSp source) {
        if (source == null) {
            return;
        }
        mFqdn = source.mFqdn;
        mFriendlyName = source.mFriendlyName;
        mIconUrl = source.mIconUrl;
        if (source.mHomeNetworkIds != null) {
            mHomeNetworkIds = Collections.unmodifiableMap(source.mHomeNetworkIds);
        }
        if (source.mMatchAllOis != null) {
            mMatchAllOis = Arrays.copyOf(source.mMatchAllOis, source.mMatchAllOis.length);
        }
        if (source.mMatchAnyOis != null) {
            mMatchAnyOis = Arrays.copyOf(source.mMatchAnyOis, source.mMatchAnyOis.length);
        }
        if (source.mOtherHomePartners != null) {
            mOtherHomePartners = Arrays.copyOf(source.mOtherHomePartners,
                    source.mOtherHomePartners.length);
        }
        if (source.mRoamingConsortiumOis != null) {
            mRoamingConsortiumOis = Arrays.copyOf(source.mRoamingConsortiumOis,
                    source.mRoamingConsortiumOis.length);
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
        dest.writeLongArray(mMatchAllOis);
        dest.writeLongArray(mMatchAnyOis);
        dest.writeStringArray(mOtherHomePartners);
        dest.writeLongArray(mRoamingConsortiumOis);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HomeSp)) {
            return false;
        }
        HomeSp that = (HomeSp) thatObject;

        return TextUtils.equals(mFqdn, that.mFqdn)
                && TextUtils.equals(mFriendlyName, that.mFriendlyName)
                && TextUtils.equals(mIconUrl, that.mIconUrl)
                && (mHomeNetworkIds == null ? that.mHomeNetworkIds == null
                        : mHomeNetworkIds.equals(that.mHomeNetworkIds))
                && Arrays.equals(mMatchAllOis, that.mMatchAllOis)
                && Arrays.equals(mMatchAnyOis, that.mMatchAnyOis)
                && Arrays.equals(mOtherHomePartners, that.mOtherHomePartners)
                && Arrays.equals(mRoamingConsortiumOis, that.mRoamingConsortiumOis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFqdn, mFriendlyName, mIconUrl,
                mHomeNetworkIds, Arrays.hashCode(mMatchAllOis),
                Arrays.hashCode(mMatchAnyOis), Arrays.hashCode(mOtherHomePartners),
                Arrays.hashCode(mRoamingConsortiumOis));
    }

    /**
     * Get a unique identifier for HomeSp. This identifier depends only on items that remain
     * constant throughout the lifetime of a subscription.
     *
     * @hide
     * @return a Unique identifier for a HomeSp object
     */
    public int getUniqueId() {
        return Objects.hash(mFqdn);
    }


    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("FQDN: ").append(mFqdn).append("\n");
        builder.append("FriendlyName: ").append(mFriendlyName).append("\n");
        builder.append("IconURL: ").append(mIconUrl).append("\n");
        builder.append("HomeNetworkIDs: ").append(mHomeNetworkIds).append("\n");
        builder.append("MatchAllOIs: ").append(mMatchAllOis).append("\n");
        builder.append("MatchAnyOIs: ").append(mMatchAnyOis).append("\n");
        builder.append("OtherHomePartners: ").append(mOtherHomePartners).append("\n");
        builder.append("RoamingConsortiumOIs: ").append(mRoamingConsortiumOis).append("\n");
        return builder.toString();
    }

    /**
     * Validate HomeSp data.
     *
     * @return true on success or false on failure
     * @hide
     */
    public boolean validate() {
        if (TextUtils.isEmpty(mFqdn)) {
            Log.d(TAG, "Missing FQDN");
            return false;
        }
        if (mFqdn.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_LENGTH) {
            Log.d(TAG, "FQDN is too long");
            return false;
        }
        if (TextUtils.isEmpty(mFriendlyName)) {
            Log.d(TAG, "Missing friendly name");
            return false;
        }
        if (mFriendlyName.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_LENGTH) {
            Log.d(TAG, "Friendly name is too long");
            return false;
        }
        // Verify SSIDs specified in the NetworkID
        if (mHomeNetworkIds != null) {
            if (mHomeNetworkIds.size() > MAX_NUMBER_OF_ENTRIES) {
                Log.d(TAG, "too many SSID in HomeNetworkIDs");
                return false;
            }
            for (Map.Entry<String, Long> entry : mHomeNetworkIds.entrySet()) {
                if (entry.getKey() == null ||
                        entry.getKey().getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
                    Log.d(TAG, "SSID is too long in HomeNetworkIDs");
                    return false;
                }
                if (entry.getValue() != null
                        && (entry.getValue() > MAX_HESSID_VALUE || entry.getValue() < 0)) {
                    Log.d(TAG, "HESSID is out of range");
                    return false;
                }
            }
        }
        if (mIconUrl != null && mIconUrl.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
            Log.d(TAG, "Icon URL is too long");
            return false;
        }
        if (mMatchAllOis != null) {
            if (mMatchAllOis.length > MAX_NUMBER_OF_OI) {
                Log.d(TAG, "too many match all Organization Identifiers in the profile");
                return false;
            }
            for (long oi : mMatchAllOis) {
                if (oi > MAX_OI_VALUE || oi < 0) {
                    Log.d(TAG, "Organization Identifiers is out of range");
                    return false;
                }
            }
        }
        if (mMatchAnyOis != null) {
            if (mMatchAnyOis.length > MAX_NUMBER_OF_OI) {
                Log.d(TAG, "too many match any Organization Identifiers in the profile");
                return false;
            }
            for (long oi : mMatchAnyOis) {
                if (oi > MAX_OI_VALUE || oi < 0) {
                    Log.d(TAG, "Organization Identifiers is out of range");
                    return false;
                }
            }
        }
        if (mRoamingConsortiumOis != null) {
            if (mRoamingConsortiumOis.length > MAX_NUMBER_OF_OI) {
                Log.d(TAG, "too many Roaming Consortium Organization Identifiers in the "
                        + "profile");
                return false;
            }
            for (long oi : mRoamingConsortiumOis) {
                if (oi > MAX_OI_VALUE || oi < 0) {
                    Log.d(TAG, "Organization Identifiers is out of range");
                    return false;
                }
            }
        }
        if (mOtherHomePartners != null) {
            if (mOtherHomePartners.length > MAX_NUMBER_OF_ENTRIES) {
                Log.d(TAG, "too many other home partners in the profile");
                return false;
            }
            for (String fqdn : mOtherHomePartners) {
                if (fqdn.length() > MAX_STRING_LENGTH) {
                    Log.d(TAG, "FQDN is too long in OtherHomePartners");
                    return false;
                }
            }
        }
        return true;
    }

    public static final @android.annotation.NonNull Creator<HomeSp> CREATOR =
        new Creator<HomeSp>() {
            @Override
            public HomeSp createFromParcel(Parcel in) {
                HomeSp homeSp = new HomeSp();
                homeSp.setFqdn(in.readString());
                homeSp.setFriendlyName(in.readString());
                homeSp.setIconUrl(in.readString());
                homeSp.setHomeNetworkIds(readHomeNetworkIds(in));
                homeSp.setMatchAllOis(in.createLongArray());
                homeSp.setMatchAnyOis(in.createLongArray());
                homeSp.setOtherHomePartners(in.createStringArray());
                homeSp.setRoamingConsortiumOis(in.createLongArray());
                return homeSp;
            }

            @Override
            public HomeSp[] newArray(int size) {
                return new HomeSp[size];
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
