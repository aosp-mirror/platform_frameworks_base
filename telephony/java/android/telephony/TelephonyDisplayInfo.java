/*
 * Copyright 2020 The Android Open Source Project
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

package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.OverrideNetworkType;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * TelephonyDisplayInfo contains telephony-related information used for display purposes only. This
 * information is provided in accordance with carrier policy and branding preferences; it is not
 * necessarily a precise or accurate representation of the current state and should be treated
 * accordingly.
 * To be notified of changes in TelephonyDisplayInfo, use
 * {@link TelephonyManager#registerTelephonyCallback} with a {@link TelephonyCallback}
 * that implements {@link TelephonyCallback.DisplayInfoListener}.
 * Override the onDisplayInfoChanged() method to handle the broadcast.
 */
public final class TelephonyDisplayInfo implements Parcelable {
    /**
     * No override. {@link #getNetworkType()} should be used for display network
     * type.
     */
    public static final int OVERRIDE_NETWORK_TYPE_NONE = 0;

    /**
     * Override network type when the device is connected to
     * {@link TelephonyManager#NETWORK_TYPE_LTE} cellular network and is using carrier aggregation.
     */
    public static final int OVERRIDE_NETWORK_TYPE_LTE_CA = 1;

    /**
     * Override network type when the device is connected to advanced pro
     * {@link TelephonyManager#NETWORK_TYPE_LTE} cellular network.
     */
    public static final int OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO = 2;

    /**
     * Override network type when the device is connected to
     * {@link TelephonyManager#NETWORK_TYPE_LTE} network and has E-UTRA-NR Dual Connectivity(EN-DC)
     * capability or is currently connected to the secondary
     * {@link TelephonyManager#NETWORK_TYPE_NR} cellular network.
     */
    public static final int OVERRIDE_NETWORK_TYPE_NR_NSA = 3;

    /**
     * Override network type when the device is connected to
     * {@link TelephonyManager#NETWORK_TYPE_LTE} network and has E-UTRA-NR Dual Connectivity(EN-DC)
     * capability or is currently connected to the secondary
     * {@link TelephonyManager#NETWORK_TYPE_NR} cellular network on millimeter wave bands.
     * @deprecated Use{@link #OVERRIDE_NETWORK_TYPE_NR_ADVANCED} instead.
     */
    @Deprecated
    public static final int OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE = 4;

    /**
     * Override network type when the device is connected NR cellular network and the data rate is
     * higher than the generic 5G date rate.
     * Including but not limited to
     * <ul>
     *   <li>The device is connected to the NR cellular network on millimeter wave bands. </li>
     *   <li>The device is connected to the specific network which the carrier is using
     *   proprietary means to provide a faster overall data connection than would be otherwise
     *   possible.  This may include using other bands unique to the carrier, or carrier
     *   aggregation, for example.</li>
     * </ul>
     * One of the use case is that UX can show a different icon, for example, "5G+"
     */
    public static final int OVERRIDE_NETWORK_TYPE_NR_ADVANCED = 5;

    @NetworkType
    private final int mNetworkType;

    @OverrideNetworkType
    private final int mOverrideNetworkType;

    private final boolean mIsRoaming;

    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    private final boolean mIsNtn;

    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    private final boolean mIsSatelliteConstrainedData;

    /**
     * Constructor
     *
     * @param networkType Current packet-switching cellular network type
     * @param overrideNetworkType The override network type
     *
     * @deprecated will not use this constructor anymore.
     * @hide
     */
    @Deprecated
    public TelephonyDisplayInfo(@NetworkType int networkType,
            @OverrideNetworkType int overrideNetworkType) {
        this(networkType, overrideNetworkType, false, false, false);
    }

    /**
     * Constructor
     *
     * @param networkType Current packet-switching cellular network type
     * @param overrideNetworkType The override network type
     * @param isRoaming True if the device is roaming after override.
     *
     * @hide
     */
    @Deprecated
    public TelephonyDisplayInfo(@NetworkType int networkType,
            @OverrideNetworkType int overrideNetworkType,
            boolean isRoaming) {
        mNetworkType = networkType;
        mOverrideNetworkType = overrideNetworkType;
        mIsRoaming = isRoaming;
        mIsNtn = false;
        mIsSatelliteConstrainedData = false;
    }

    /**
     * Constructor
     *
     * @param networkType Current packet-switching cellular network type
     * @param overrideNetworkType The override network type
     * @param isRoaming True if the device is roaming after override.
     * @param isNtn True if the device is camped to non-terrestrial network.
     * @param isSatelliteConstrainedData True if the device satellite internet is bandwidth
     *        constrained.
     *
     * @hide
     */
    public TelephonyDisplayInfo(@NetworkType int networkType,
            @OverrideNetworkType int overrideNetworkType,
            boolean isRoaming, boolean isNtn, boolean isSatelliteConstrainedData) {
        mNetworkType = networkType;
        mOverrideNetworkType = overrideNetworkType;
        mIsRoaming = isRoaming;
        mIsNtn = isNtn;
        mIsSatelliteConstrainedData = isSatelliteConstrainedData;
    }

    /** @hide */
    public TelephonyDisplayInfo(Parcel p) {
        mNetworkType = p.readInt();
        mOverrideNetworkType = p.readInt();
        mIsRoaming = p.readBoolean();
        mIsNtn = p.readBoolean();
        mIsSatelliteConstrainedData = p.readBoolean();
    }

    /**
     * Get current packet-switching cellular network type. This is the actual network type the
     * device is camped on.
     *
     * @return The network type.
     */
    @NetworkType
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Get the override network type. Note the override network type is for market branding
     * or visualization purposes only. It cannot be treated as the actual network type device is
     * camped on.
     *
     * @return The override network type.
     */
    @OverrideNetworkType
    public int getOverrideNetworkType() {
        return mOverrideNetworkType;
    }

    /**
     * Get device is roaming or not. Note the isRoaming is for market branding or visualization
     * purposes only. It cannot be treated as the actual roaming device is camped on.
     *
     * @return True if the device is registered on roaming network overridden by config.
     * @see CarrierConfigManager#KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY
     * @see CarrierConfigManager#KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY
     * @see CarrierConfigManager#KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY
     * @see CarrierConfigManager#KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY
     */
    public boolean isRoaming() {
        return mIsRoaming;
    }

    /**
     * Get whether the satellite internet is with bandwidth constrained capability set.
     *
     * @return {@code true} if satellite internet is connected with bandwidth constrained
     *         capability else {@code false}.
     * @hide
     */
    public boolean isSatelliteConstrainedData() {
        return mIsSatelliteConstrainedData;
    }

    /**
     * Get whether the network is a non-terrestrial network.
     *
     * @return {@code true} if network is a non-terrestrial network else {@code false}.
     * @hide
     */
    public boolean isNtn() {
        return mIsNtn;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNetworkType);
        dest.writeInt(mOverrideNetworkType);
        dest.writeBoolean(mIsRoaming);
        dest.writeBoolean(mIsNtn);
        dest.writeBoolean(mIsSatelliteConstrainedData);
    }

    public static final @NonNull Parcelable.Creator<TelephonyDisplayInfo> CREATOR =
            new Parcelable.Creator<TelephonyDisplayInfo>() {
                @Override
                public TelephonyDisplayInfo createFromParcel(Parcel source) {
                    return new TelephonyDisplayInfo(source);
                }

                @Override
                public TelephonyDisplayInfo[] newArray(int size) {
                    return new TelephonyDisplayInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TelephonyDisplayInfo that = (TelephonyDisplayInfo) o;
        return mNetworkType == that.mNetworkType
                && mOverrideNetworkType == that.mOverrideNetworkType
                && mIsRoaming == that.mIsRoaming
                && mIsNtn == that.mIsNtn
                && mIsSatelliteConstrainedData == that.mIsSatelliteConstrainedData;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkType, mOverrideNetworkType, mIsRoaming, mIsNtn,
                mIsSatelliteConstrainedData);
    }

    /**
     * Convert override network type to string.
     *
     * @param type Override network type
     * @return Override network type in string format
     * @hide
     */
    public static String overrideNetworkTypeToString(@OverrideNetworkType int type) {
        switch (type) {
            case OVERRIDE_NETWORK_TYPE_NONE: return "NONE";
            case OVERRIDE_NETWORK_TYPE_LTE_CA: return "LTE_CA";
            case OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO: return "LTE_ADV_PRO";
            case OVERRIDE_NETWORK_TYPE_NR_NSA: return "NR_NSA";
            case OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE: return "NR_NSA_MMWAVE";
            case OVERRIDE_NETWORK_TYPE_NR_ADVANCED: return "NR_ADVANCED";
            default: return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return "TelephonyDisplayInfo {network=" + TelephonyManager.getNetworkTypeName(mNetworkType)
                + ", overrideNetwork=" + overrideNetworkTypeToString(mOverrideNetworkType)
                + ", isRoaming=" + mIsRoaming
                + ", isNtn=" + mIsNtn
                + ", isSatelliteConstrainedData=" + mIsSatelliteConstrainedData + "}";
    }
}
