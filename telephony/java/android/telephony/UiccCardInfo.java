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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The UiccCardInfo represents information about a currently inserted UICC or embedded eUICC.
 */
public final class UiccCardInfo implements Parcelable {

    private final boolean mIsEuicc;
    private final int mCardId;
    private final String mEid;
    private final String mIccId;
    private final int mSlotIndex;
    private final boolean mIsRemovable;

    public static final Creator<UiccCardInfo> CREATOR = new Creator<UiccCardInfo>() {
        @Override
        public UiccCardInfo createFromParcel(Parcel in) {
            return new UiccCardInfo(in);
        }

        @Override
        public UiccCardInfo[] newArray(int size) {
            return new UiccCardInfo[size];
        }
    };

    private UiccCardInfo(Parcel in) {
        mIsEuicc = in.readByte() != 0;
        mCardId = in.readInt();
        mEid = in.readString();
        mIccId = in.readString();
        mSlotIndex = in.readInt();
        mIsRemovable = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (mIsEuicc ? 1 : 0));
        dest.writeInt(mCardId);
        dest.writeString(mEid);
        dest.writeString(mIccId);
        dest.writeInt(mSlotIndex);
        dest.writeByte((byte) (mIsRemovable ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public UiccCardInfo(boolean isEuicc, int cardId, String eid, String iccId, int slotIndex,
            boolean isRemovable) {
        this.mIsEuicc = isEuicc;
        this.mCardId = cardId;
        this.mEid = eid;
        this.mIccId = iccId;
        this.mSlotIndex = slotIndex;
        this.mIsRemovable = isRemovable;
    }

    /**
     * Return whether the UICC is an eUICC.
     * @return true if the UICC is an eUICC.
     */
    public boolean isEuicc() {
        return mIsEuicc;
    }

    /**
     * Get the card ID of the UICC. See {@link TelephonyManager#getCardIdForDefaultEuicc()} for more
     * details on card ID.
     */
    public int getCardId() {
        return mCardId;
    }

    /**
     * Get the embedded ID (EID) of the eUICC. If the UiccCardInfo is not an eUICC
     * (see {@link #isEuicc()}), returns null.
     * <p>
     * Note that this field may be omitted if the caller does not have the correct permissions
     * (see {@link TelephonyManager#getUiccCardsInfo()}).
     */
    public String getEid() {
        if (!mIsEuicc) {
            return null;
        }
        return mEid;
    }

    /**
     * Get the ICCID of the UICC.
     * <p>
     * Note that this field may be omitted if the caller does not have the correct permissions
     * (see {@link TelephonyManager#getUiccCardsInfo()}).
     */
    public String getIccId() {
        return mIccId;
    }

    /**
     * Gets the slot index for the slot that the UICC is currently inserted in.
     */
    public int getSlotIndex() {
        return mSlotIndex;
    }

    /**
     * Returns a copy of the UiccCardinfo with the clears the EID and ICCID set to null. These
     * values are generally private and require carrier privileges to view.
     *
     * @hide
     */
    public UiccCardInfo getUnprivileged() {
        return new UiccCardInfo(mIsEuicc, mCardId, null, null, mSlotIndex, mIsRemovable);
    }

    /**
     * Return whether the UICC or eUICC is removable.
     * <p>
     * UICCs are generally removable, but eUICCs may be removable or built in to the device.
     * @return true if the UICC or eUICC is removable
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

        UiccCardInfo that = (UiccCardInfo) obj;
        return ((mIsEuicc == that.mIsEuicc)
                && (mCardId == that.mCardId)
                && (Objects.equals(mEid, that.mEid))
                && (Objects.equals(mIccId, that.mIccId))
                && (mSlotIndex == that.mSlotIndex)
                && (mIsRemovable == that.mIsRemovable));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsEuicc, mCardId, mEid, mIccId, mSlotIndex, mIsRemovable);
    }

    @Override
    public String toString() {
        return "UiccCardInfo (mIsEuicc="
                + mIsEuicc
                + ", mCardId="
                + mCardId
                + ", mEid="
                + mEid
                + ", mIccId="
                + mIccId
                + ", mSlotIndex="
                + mSlotIndex
                + ", mIsRemovable="
                + mIsRemovable
                + ")";
    }
}
