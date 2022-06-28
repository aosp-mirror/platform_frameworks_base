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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The UiccCardInfo represents information about a currently inserted UICC or embedded eUICC.
 */
public final class UiccCardInfo implements Parcelable {
    private final boolean mIsEuicc;
    private final int mCardId;
    private final String mEid;
    private final String mIccId;
    private final int mPhysicalSlotIndex;
    private final boolean mIsRemovable;
    private final boolean mIsMultipleEnabledProfilesSupported;
    private final List<UiccPortInfo> mPortList;
    private boolean mIccIdAccessRestricted = false;

    public static final @NonNull Creator<UiccCardInfo> CREATOR = new Creator<UiccCardInfo>() {
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
        mIsEuicc = in.readBoolean();
        mCardId = in.readInt();
        mEid = in.readString8();
        mIccId = in.readString8();
        mPhysicalSlotIndex = in.readInt();
        mIsRemovable = in.readBoolean();
        mIsMultipleEnabledProfilesSupported = in.readBoolean();
        mPortList = new ArrayList<UiccPortInfo>();
        in.readTypedList(mPortList, UiccPortInfo.CREATOR);
        mIccIdAccessRestricted = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsEuicc);
        dest.writeInt(mCardId);
        dest.writeString8(mEid);
        dest.writeString8(mIccId);
        dest.writeInt(mPhysicalSlotIndex);
        dest.writeBoolean(mIsRemovable);
        dest.writeBoolean(mIsMultipleEnabledProfilesSupported);
        dest.writeTypedList(mPortList, flags);
        dest.writeBoolean(mIccIdAccessRestricted);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Construct a UiccCardInfo.
     *
     * @param isEuicc is a flag to check is eUICC or not
     * @param cardId is unique ID used to identify a UiccCard.
     * @param eid is unique eUICC Identifier
     * @param physicalSlotIndex is unique index referring to a physical SIM slot.
     * @param isRemovable is a flag to check is removable or embedded
     * @param isMultipleEnabledProfilesSupported is a flag to check is MEP enabled or not
     * @param portList has the information regarding port, ICCID and its active status
     *
     * @hide
     */
    public UiccCardInfo(boolean isEuicc, int cardId, String eid, int physicalSlotIndex,
            boolean isRemovable, boolean isMultipleEnabledProfilesSupported,
            @NonNull List<UiccPortInfo> portList) {
        this.mIsEuicc = isEuicc;
        this.mCardId = cardId;
        this.mEid = eid;
        this.mIccId = null;
        this.mPhysicalSlotIndex = physicalSlotIndex;
        this.mIsRemovable = isRemovable;
        this.mIsMultipleEnabledProfilesSupported = isMultipleEnabledProfilesSupported;
        this.mPortList = portList;
    }

    /**
     * Return whether the UICC is an eUICC.
     *
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
     * (see {@link #isEuicc()}), or the EID is not available, returns null.
     * <p>
     * Note that this field may be omitted if the caller does not have the correct permissions
     * (see {@link TelephonyManager#getUiccCardsInfo()}).
     */
    @Nullable
    public String getEid() {
        if (!mIsEuicc) {
            return null;
        }
        return mEid;
    }

    /**
     * Get the ICCID of the UICC. If the ICCID is not availble, returns null.
     * <p>
     * Note that this field may be omitted if the caller does not have the correct permissions
     * (see {@link TelephonyManager#getUiccCardsInfo()}).
     *
     * @deprecated with support for MEP(multiple enabled profile)
     * {@link PackageManager#FEATURE_TELEPHONY_EUICC_MEP}, a SIM card can have more than one
     * ICCID active at the same time. Instead use {@link UiccPortInfo#getIccId()} to retrieve ICCID.
     * To find {@link UiccPortInfo} use {@link UiccCardInfo#getPorts()}.
     *
     * @throws UnsupportedOperationException if the calling app's target SDK is T and beyond.
     */
    @Nullable
    @Deprecated
    public String getIccId() {
        if (mIccIdAccessRestricted) {
            throw new UnsupportedOperationException("getIccId() is not supported by UiccCardInfo."
                + " Please Use UiccPortInfo API instead");
        }
        //always return ICCID from first port.
        return getPorts().stream().findFirst().get().getIccId();
    }

    /**
     * Gets the slot index for the slot that the UICC is currently inserted in.
     *
     * @deprecated use {@link #getPhysicalSlotIndex()}
     */
    @Deprecated
    public int getSlotIndex() {
        return mPhysicalSlotIndex;
    }

    /**
     * Gets the physical slot index for the slot that the UICC is currently inserted in.
     */
    public int getPhysicalSlotIndex() {
        return mPhysicalSlotIndex;
    }

    /**
     * Return whether the UICC or eUICC is removable.
     * <p>
     * UICCs are generally removable, but eUICCs may be removable or built in to the device.
     *
     * @return true if the UICC or eUICC is removable
     */
    public boolean isRemovable() {
        return mIsRemovable;
    }

    /*
     * Whether the UICC card supports multiple enabled profile(MEP)
     * UICCs are generally MEP disabled, there can be only one active profile on the physical
     * sim card.
     *
     * @return {@code true} if the UICC is supporting multiple enabled profile(MEP).
     */
    public boolean isMultipleEnabledProfilesSupported() {
        return mIsMultipleEnabledProfilesSupported;
    }

    /**
     * Get information regarding port, ICCID and its active status.
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
     * if the flag is set to {@code true} the calling app is not allowed to access deprecated
     * {@link #getIccId()}
     * @param iccIdAccessRestricted is the flag to check if app is allowed to access ICCID
     *
     * @hide
     */
    public void setIccIdAccessRestricted(boolean iccIdAccessRestricted) {
        this.mIccIdAccessRestricted = iccIdAccessRestricted;
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
                && (mPhysicalSlotIndex == that.mPhysicalSlotIndex)
                && (mIsRemovable == that.mIsRemovable)
                && (mIsMultipleEnabledProfilesSupported == that.mIsMultipleEnabledProfilesSupported)
                && (Objects.equals(mPortList, that.mPortList)));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsEuicc, mCardId, mEid, mIccId, mPhysicalSlotIndex, mIsRemovable,
                mIsMultipleEnabledProfilesSupported, mPortList);
    }

    @Override
    public String toString() {
        return "UiccCardInfo (mIsEuicc="
                + mIsEuicc
                + ", mCardId="
                + mCardId
                + ", mEid="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, mEid)
                + ", mPhysicalSlotIndex="
                + mPhysicalSlotIndex
                + ", mIsRemovable="
                + mIsRemovable
                + ", mIsMultipleEnabledProfilesSupported="
                + mIsMultipleEnabledProfilesSupported
                + ", mPortList="
                + mPortList
                + ", mIccIdAccessRestricted="
                + mIccIdAccessRestricted
                + ")";
    }
}