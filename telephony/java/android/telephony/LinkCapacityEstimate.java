/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Link Capacity Estimate from the modem
 * @hide
 */
@SystemApi
public final class LinkCapacityEstimate implements Parcelable {
    /** A value indicates that the capacity estimate is not available */
    public static final int INVALID = -1;

    /**
     * LCE for the primary network
     */
    public static final int LCE_TYPE_PRIMARY = 0;

    /**
     * LCE for the secondary network
     */
    public static final int LCE_TYPE_SECONDARY = 1;

    /**
     * Combined LCE for primary network and secondary network reported by the legacy modem
     */
    public static final int LCE_TYPE_COMBINED = 2;

    /** @hide */
    @IntDef(prefix = { "LCE_TYPE_" }, value = {
            LCE_TYPE_PRIMARY,
            LCE_TYPE_SECONDARY,
            LCE_TYPE_COMBINED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LceType {}

    private final @LceType int mType;

    /** Downlink capacity estimate in kbps */
    private final int mDownlinkCapacityKbps;

    /** Uplink capacity estimate in kbps */
    private final int mUplinkCapacityKbps;

    /**
     * Constructor for link capacity estimate
     */
    public LinkCapacityEstimate(@LceType int type,
            int downlinkCapacityKbps, int uplinkCapacityKbps) {
        mDownlinkCapacityKbps = downlinkCapacityKbps;
        mUplinkCapacityKbps = uplinkCapacityKbps;
        mType = type;
    }

    /**
     * @hide
     */
    public LinkCapacityEstimate(Parcel in) {
        mDownlinkCapacityKbps = in.readInt();
        mUplinkCapacityKbps = in.readInt();
        mType = in.readInt();
    }

    /**
     * Retrieves the type of LCE
     * @return The type of link capacity estimate
     */
    public @LceType int getType() {
        return mType;
    }

    /**
     * Retrieves the downlink bandwidth in Kbps.
     * This will be {@link #INVALID} if the network is not connected
     * @return The estimated first hop downstream (network to device) bandwidth.
     */
    public int getDownlinkCapacityKbps() {
        return mDownlinkCapacityKbps;
    }

    /**
     * Retrieves the uplink bandwidth in Kbps.
     * This will be {@link #INVALID} if the network is not connected
     *
     * @return The estimated first hop upstream (device to network) bandwidth.
     */
    public int getUplinkCapacityKbps() {
        return mUplinkCapacityKbps;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("{mType=")
                .append(mType)
                .append(", mDownlinkCapacityKbps=")
                .append(mDownlinkCapacityKbps)
                .append(", mUplinkCapacityKbps=")
                .append(mUplinkCapacityKbps)
                .append("}")
                .toString();
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     * @hide
     */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDownlinkCapacityKbps);
        dest.writeInt(mUplinkCapacityKbps);
        dest.writeInt(mType);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || !(o instanceof LinkCapacityEstimate) || hashCode() != o.hashCode())  {
            return false;
        }

        if (this == o) {
            return true;
        }

        LinkCapacityEstimate that = (LinkCapacityEstimate) o;
        return mDownlinkCapacityKbps == that.mDownlinkCapacityKbps
                && mUplinkCapacityKbps == that.mUplinkCapacityKbps
                && mType == that.mType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDownlinkCapacityKbps, mUplinkCapacityKbps, mType);
    }

    public static final
            @android.annotation.NonNull Parcelable.Creator<LinkCapacityEstimate> CREATOR =
            new Parcelable.Creator() {
        public LinkCapacityEstimate createFromParcel(Parcel in) {
            return new LinkCapacityEstimate(in);
        }

        public LinkCapacityEstimate[] newArray(int size) {
            return new LinkCapacityEstimate[size];
        }
    };
}
