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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * UiccPortInfo class represents information about a single port contained on {@link UiccCardInfo}.
 * Per GSMA SGP.22 V3.0, a port is a logical entity to which an active UICC profile can be bound on
 * a UICC card. If UICC supports 2 ports, then the port index is numbered 0,1.
 * Each port index is unique within an UICC, but not necessarily unique across UICC’s.
 * For UICC's does not support MEP(Multi-enabled profile)
 * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY_EUICC_MEP}, just return the default
 * port index 0.
 */
public final class UiccPortInfo implements Parcelable{
    private final String mIccId;
    private final int mPortIndex;
    private final int mLogicalSlotIndex;
    private final boolean mIsActive;

    /**
     * A redacted String if caller does not have permission to read ICCID.
     */
    public static final String ICCID_REDACTED = "FFFFFFFFFFFFFFFFFFFF";

    public static final @NonNull Creator<UiccPortInfo> CREATOR =
            new Creator<UiccPortInfo>() {
                @Override
                public UiccPortInfo createFromParcel(Parcel in) {
                    return new UiccPortInfo(in);
                }
                @Override
                public UiccPortInfo[] newArray(int size) {
                    return new UiccPortInfo[size];
                }
            };

    private UiccPortInfo(Parcel in) {
        mIccId = in.readString8();
        mPortIndex = in.readInt();
        mLogicalSlotIndex = in.readInt();
        mIsActive = in.readBoolean();
    }

    @Override
    public void writeToParcel(@Nullable Parcel dest, int flags) {
        dest.writeString8(mIccId);
        dest.writeInt(mPortIndex);
        dest.writeInt(mLogicalSlotIndex);
        dest.writeBoolean(mIsActive);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Construct a UiccPortInfo.
     *
     * @param iccId The ICCID of the profile.
     * @param portIndex The port index is an enumeration of the ports available on the UICC.
     * @param logicalSlotIndex is unique index referring to a logical SIM slot.
     * @param isActive is flag to check if port was tied to a modem stack.
     *
     * @hide
     */
    public UiccPortInfo(String iccId, int portIndex, int logicalSlotIndex, boolean isActive) {
        this.mIccId = iccId;
        this.mPortIndex = portIndex;
        this.mLogicalSlotIndex = logicalSlotIndex;
        this.mIsActive = isActive;
    }

    /**
     * Get the ICCID of the profile associated with this port.
     * If this port is not {@link #isActive()}, returns {@code null}.
     * If the caller does not have access to the ICCID for this port, it will be redacted and
     * {@link #ICCID_REDACTED} will be returned.
     */
    public @Nullable String getIccId() {
        return mIccId;
    }

    /**
     * The port index is an enumeration of the ports available on the UICC.
     * Example: if eUICC1 supports 2 ports, then the port index is numbered 0,1.
     * Each port index is unique within an UICC, but not necessarily unique across UICC’s.
     * For UICC's does not support MEP(Multi-enabled profile), just return the default port index 0.
     */
    @IntRange(from = 0)
    public int getPortIndex() {
        return mPortIndex;
    }

    /**
     * @return {@code true} if port was tied to a modem stack.
     */
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * Gets logical slot index for the slot that the UICC is currently attached.
     * Logical slot index or ID: unique index referring to a logical SIM slot.
     * Logical slot IDs start at 0 and go up depending on the number of supported active slots on
     * a device.
     * For example, a dual-SIM device typically has slot 0 and slot 1.
     * If a device has multiple physical slots but only supports one active slot,
     * it will have only the logical slot ID 0.
     *
     * @return the logical slot index for UICC port, if there is no logical slot index it returns
     * {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX}
     */
    @IntRange(from = 0)
    public int getLogicalSlotIndex() {
        return mLogicalSlotIndex;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        UiccPortInfo that = (UiccPortInfo) obj;
        return (Objects.equals(mIccId, that.mIccId))
                && (mPortIndex == that.mPortIndex)
                && (mLogicalSlotIndex == that.mLogicalSlotIndex)
                && (mIsActive == that.mIsActive);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIccId, mPortIndex, mLogicalSlotIndex, mIsActive);
    }

    @NonNull
    @Override
    public String toString() {
        return "UiccPortInfo (isActive="
                + mIsActive
                + ", iccId="
                + SubscriptionInfo.givePrintableIccid(mIccId)
                + ", portIndex="
                + mPortIndex
                + ", mLogicalSlotIndex="
                + mLogicalSlotIndex
                + ")";
    }
}
