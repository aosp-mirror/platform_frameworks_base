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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    public static final @android.annotation.NonNull Creator<UiccSlotInfo> CREATOR = new Creator<UiccSlotInfo>() {
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
        mIsActive = in.readByte() != 0;
        mIsEuicc = in.readByte() != 0;
        mCardId = in.readString();
        mCardStateInfo = in.readInt();
        mLogicalSlotIdx = in.readInt();
        mIsExtendedApduSupported = in.readByte() != 0;
        mIsRemovable = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (mIsActive ? 1 : 0));
        dest.writeByte((byte) (mIsEuicc ? 1 : 0));
        dest.writeString(mCardId);
        dest.writeInt(mCardStateInfo);
        dest.writeInt(mLogicalSlotIdx);
        dest.writeByte((byte) (mIsExtendedApduSupported ? 1 : 0));
        dest.writeByte((byte) (mIsRemovable ? 1 : 0));
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
    }

    /**
     * @hide
     */
    public UiccSlotInfo(boolean isActive, boolean isEuicc, String cardId,
            @CardStateInfo int cardStateInfo, int logicalSlotIdx, boolean isExtendedApduSupported,
            boolean isRemovable) {
        this.mIsActive = isActive;
        this.mIsEuicc = isEuicc;
        this.mCardId = cardId;
        this.mCardStateInfo = cardStateInfo;
        this.mLogicalSlotIdx = logicalSlotIdx;
        this.mIsExtendedApduSupported = isExtendedApduSupported;
        this.mIsRemovable = isRemovable;
    }

    public boolean getIsActive() {
        return mIsActive;
    }

    public boolean getIsEuicc() {
        return mIsEuicc;
    }

    public String getCardId() {
        return mCardId;
    }

    @CardStateInfo
    public int getCardStateInfo() {
        return mCardStateInfo;
    }

    public int getLogicalSlotIdx() {
        return mLogicalSlotIdx;
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
     * @return true if the slot is for removable UICCs
     */
    public boolean isRemovable() {
        return mIsRemovable;
    }

    @Override
    public boolean equals(Object obj) {
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
                && (mIsRemovable == that.mIsRemovable);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + (mIsActive ? 1 : 0);
        result = 31 * result + (mIsEuicc ? 1 : 0);
        result = 31 * result + Objects.hashCode(mCardId);
        result = 31 * result + mCardStateInfo;
        result = 31 * result + mLogicalSlotIdx;
        result = 31 * result + (mIsExtendedApduSupported ? 1 : 0);
        result = 31 * result + (mIsRemovable ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "UiccSlotInfo (mIsActive="
                + mIsActive
                + ", mIsEuicc="
                + mIsEuicc
                + ", mCardId="
                + mCardId
                + ", cardState="
                + mCardStateInfo
                + ", phoneId="
                + mLogicalSlotIdx
                + ", mIsExtendedApduSupported="
                + mIsExtendedApduSupported
                + ", mIsRemovable="
                + mIsRemovable
                + ")";
    }
}
