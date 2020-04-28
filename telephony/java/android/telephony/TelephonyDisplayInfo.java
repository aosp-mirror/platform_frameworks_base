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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.OverrideNetworkType;

import java.util.Objects;

/**
 * TelephonyDisplayInfo contains telephony-related information used for display purposes only. This
 * information is provided in accordance with carrier policy and branding preferences; it is not
 * necessarily a precise or accurate representation of the current state and should be treated
 * accordingly.
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
     */
    public static final int OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE = 4;

    @NetworkType
    private final  int mNetworkType;

    @OverrideNetworkType
    private final  int mOverrideNetworkType;

    /**
     * Constructor
     *
     * @param networkType Current packet-switching cellular network type
     * @param overrideNetworkType The override network type
     *
     * @hide
     */
    public TelephonyDisplayInfo(@NetworkType int networkType,
            @OverrideNetworkType int overrideNetworkType) {
        mNetworkType = networkType;
        mOverrideNetworkType = overrideNetworkType;
    }

    /** @hide */
    public TelephonyDisplayInfo(Parcel p) {
        mNetworkType = p.readInt();
        mOverrideNetworkType = p.readInt();
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

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNetworkType);
        dest.writeInt(mOverrideNetworkType);
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
                && mOverrideNetworkType == that.mOverrideNetworkType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkType, mOverrideNetworkType);
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
            default: return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return "TelephonyDisplayInfo {network=" + TelephonyManager.getNetworkTypeName(mNetworkType)
                + ", override=" + overrideNetworkTypeToString(mOverrideNetworkType) + "}";
    }
}
