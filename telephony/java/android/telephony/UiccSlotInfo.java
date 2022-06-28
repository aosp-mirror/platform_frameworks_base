/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class for the information of a UICC slot.
 * @hide
 */
@SystemApi
public class UiccSlotInfo implements Parcelable {
    /**
     * Card state.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CARD_STATE_INFO_" }, value = {
            CARD_STATE_INFO_ABSENT,
            CARD_STATE_INFO_PRESENT,
            CARD_STATE_INFO_ERROR,
            CARD_STATE_INFO_RESTRICTED
    })
    public @interface CardStateInfo {}

    /** Card state absent. */
    public static final int CARD_STATE_INFO_ABSENT = 1;

    /** Card state present. */
    public static final int CARD_STATE_INFO_PRESENT = 2;

    /** Card state error. */
    public static final int CARD_STATE_INFO_ERROR = 3;

    /** Card state restricted. */
    public static final int CARD_STATE_INFO_RESTRICTED = 4;

    private final boolean mIsActive;
    private final boolean mIsEuicc;
    private final String mCardId;
    private final @CardStateInfo int mCardStateInfo;
    private final int mLogicalSlotIdx;
    private final boolean mIsExtendedApduSupported;
    private final boolean mIsRemovable;
    private final List<UiccPortInfo> mPortList;
    private boolean mLogicalSlotAccessRestricted = false;

    public static final @NonNull Creator<UiccSlotInfo> CREATOR = new Creator<UiccSlotInfo>() {
        @Override
        public UiccSlotInfo createFromParcel(Parcel in) {
            return new UiccSlotInfo(in);
        }

        @Override
        public UiccSlotInfo[] newArray(int size) {
            return new UiccSlotInfo[size];
        }
    };

    private UiccSlotInfo(Parcel in) {
        mIsActive = in.readBoolean();
        mIsEuicc = in.readBoolean();
        mCardId = in.readString8();
        mCardStateInfo = in.readInt();
        mLogicalSlotIdx = in.readInt();
        mIsExtendedApduSupported = in.readBoolean();
        mIsRemovable = in.readBoolean();
        mPortList = new ArrayList<UiccPortInfo>();
        in.readTypedList(mPortList, UiccPortInfo.CREATOR);
        mLogicalSlotAccessRestricted = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsActive);
        dest.writeBoolean(mIsEuicc);
        dest.writeString8(mCardId);
        dest.writeInt(mCardStateInfo);
        dest.writeInt(mLogicalSlotIdx);
        dest.writeBoolean(mIsExtendedApduSupported);
        dest.writeBoolean(mIsRemovable);
        dest.writeTypedList(mPortList, flags);
        dest.writeBoolean(mLogicalSlotAccessRestricted);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Construct a UiccSlotInfo.
     * @deprecated apps should not be constructing UiccSlotInfo objects
     */
    @Deprecated
    public UiccSlotInfo(boolean isActive, boolean isEuicc, String cardId,
            @CardStateInfo int cardStateInfo, int logicalSlotIdx, boolean isExtendedApduSupported) {
        this.mIsActive = isActive;
        this.mIsEuicc = isEuicc;
        this.mCardId = cardId;
        this.mCardStateInfo = cardStateInfo;
        this.mLogicalSlotIdx = logicalSlotIdx;
        this.mIsExtendedApduSupported = isExtendedApduSupported;
        this.mIsRemovable = false;
        this.mPortList = null;
    }

    /**
     * Construct a UiccSlotInfo.
     * @hide
     */
    public UiccSlotInfo(boolean isEuicc, String cardId,
            @CardStateInfo int cardStateInfo, boolean isExtendedApduSupported,
            boolean isRemovable, @NonNull List<UiccPortInfo> portList) {
        this.mIsActive = portList.get(0).isActive();
        this.mIsEuicc = isEuicc;
        this.mCardId = cardId;
        this.mCardStateInfo = cardStateInfo;
        this.mLogicalSlotIdx = portList.get(0).getLogicalSlotIndex();
        this.mIsExtendedApduSupported = isExtendedApduSupported;
        this.mIsRemovable = isRemovable;
        this.mPortList = portList;
    }

    /**
     * @deprecated There is no longer isActive state for each slot because ports belonging
     * to the physical slot could have different states
     * we instead use {@link UiccPortInfo#isActive()}
     * To get UiccPortInfo use {@link UiccSlotInfo#getPorts()}
     *
     * @return {@code true} if status is active.
     * @throws UnsupportedOperationException if the calling app's target SDK is T and beyond.
     */
    @Deprecated
    public boolean getIsActive() {
        if (mLogicalSlotAccessRestricted) {
            throw new UnsupportedOperationException("getIsActive() is not supported by "
            + "UiccSlotInfo. Please Use UiccPortInfo API instead");
        }
        //always return status from first port.
        return getPorts().stream().findFirst().get().isActive();
    }

    public boolean getIsEuicc() {
        return mIsEuicc;
    }

    /**
     * Returns the ICCID of the card in the slot, or the EID of an active eUICC.
     * <p>
     * If the UICC slot is for an active eUICC, returns the EID.
     * If the UICC slot is for an inactive eUICC, returns the ICCID of the enabled profile, or the
     * root profile if all other profiles are disabled.
     * If the UICC slot is not an eUICC, returns the ICCID.
     */
    public String getCardId() {
        return mCardId;
    }

    @CardStateInfo
    public int getCardStateInfo() {
        return mCardStateInfo;
    }

    /**
     * @deprecated There is no longer getLogicalSlotIndex
     * There is no longer getLogicalSlotIdx as each port belonging to this physical slot could have
     * different logical slot index. Use {@link UiccPortInfo#getLogicalSlotIndex()} instead
     *
     * @throws UnsupportedOperationException if the calling app's target SDK is T and beyond.
     */
    @Deprecated
    public int getLogicalSlotIdx() {
        if (mLogicalSlotAccessRestricted) {
            throw new UnsupportedOperationException("getLogicalSlotIdx() is not supported by "
                + "UiccSlotInfo. Please use UiccPortInfo API instead");
        }
        //always return logical slot index from first port.
        //portList always have at least one element.
        return getPorts().stream().findFirst().get().getLogicalSlotIndex();
    }

    /**
     * @return {@code true} if this slot supports extended APDU from ATR, {@code false} otherwise.
     */
    public boolean getIsExtendedApduSupported() {
        return mIsExtendedApduSupported;
    }

    /**
     * Return whether the UICC slot is for a removable UICC.
     * <p>
     * UICCs are generally removable, but eUICCs may be removable or built in to the device.
     *
     * @return true if the slot is for removable UICCs
     */
    public boolean isRemovable() {
        return mIsRemovable;
    }

    /**
     * Get Information regarding port, iccid and its active status.
     *
     * For device which support {@link PackageManager#FEATURE_TELEPHONY_EUICC_MEP}, it should return
     * more than one {@link UiccPortInfo} object if the card is eUICC.
     *
     * @return Collection of {@link UiccPortInfo}
     */
    public @NonNull Collection<UiccPortInfo> getPorts() {
        return Collections.unmodifiableList(mPortList);
    }

    /**
     * Set the flag to check compatibility of the calling app's target SDK is T and beyond.
     *
     * @param logicalSlotAccessRestricted is the flag to check compatibility.
     *
     * @hide
     */
    public void setLogicalSlotAccessRestricted(boolean logicalSlotAccessRestricted) {
        this.mLogicalSlotAccessRestricted = logicalSlotAccessRestricted;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        UiccSlotInfo that = (UiccSlotInfo) obj;
        return (mIsActive == that.mIsActive)
                && (mIsEuicc == that.mIsEuicc)
                && (Objects.equals(mCardId, that.mCardId))
                && (mCardStateInfo == that.mCardStateInfo)
                && (mLogicalSlotIdx == that.mLogicalSlotIdx)
                && (mIsExtendedApduSupported == that.mIsExtendedApduSupported)
                && (mIsRemovable == that.mIsRemovable)
                && (Objects.equals(mPortList, that.mPortList));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsActive, mIsEuicc, mCardId, mCardStateInfo, mLogicalSlotIdx,
                mIsExtendedApduSupported, mIsRemovable, mPortList);
    }

    @NonNull
    @Override
    public String toString() {
        return "UiccSlotInfo ("
                + ", mIsEuicc="
                + mIsEuicc
                + ", mCardId="
                + SubscriptionInfo.givePrintableIccid(mCardId)
                + ", cardState="
                + mCardStateInfo
                + ", mIsExtendedApduSupported="
                + mIsExtendedApduSupported
                + ", mIsRemovable="
                + mIsRemovable
                + ", mPortList="
                + mPortList
                + ", mLogicalSlotAccessRestricted="
                + mLogicalSlotAccessRestricted
                + ")";
    }
}
