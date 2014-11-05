/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.graphics.drawable.BitmapDrawable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable class for Subscription Information.
 */
public class SubInfoRecord implements Parcelable {

    /**
     * Subscription Identifier, this is a device unique number
     * and not an index into an array
     */
    private int mId;

    /**
     * The GID for a SIM that maybe associated with this subscription, empty if unknown
     */
    private String mIccId;

    /**
     * The index of the slot that currently contains the subscription
     * and not necessarily unique and maybe INVALID_SLOT_ID if unknown
     */
    private int mSimSlotIndex;

    /**
     * The name displayed to the user that identifies this subscription
     */
    private CharSequence mDisplayName;

    /**
     * The source of the name, NAME_SOURCE_UNDEFINED, NAME_SOURCE_DEFAULT_SOURCE,
     * NAME_SOURCE_SIM_SOURCE or NAME_SOURCE_USER_INPUT.
     */
    private int mNameSource;

    /**
     * The color to be used for when displaying to the user
     */
    private int mColor;

    /**
     * A number presented to the user identify this subscription
     */
    private String mNumber;

    /**
     * Data roaming state, DATA_RAOMING_ENABLE, DATA_RAOMING_DISABLE
     */
    private int mDataRoaming;

    /**
     * SIM Icon resource identifiers. FIXME: Check with MTK what it really is
     */
    private int[] mSimIconRes;

    /**
     * Mobile Country Code
     */
    private int mMcc;

    /**
     * Mobile Network Code
     */
    private int mMnc;

    /**
     * @hide
    public SubInfoRecord() {
        this.mId = SubscriptionManager.INVALID_SUB_ID;
        this.mIccId = "";
        this.mSimSlotIndex = SubscriptionManager.INVALID_SLOT_ID;
        this.mDisplayName = "";
        this.mNameSource = 0;
        this.mColor = 0;
        this.mNumber = "";
        this.mDataRoaming = 0;
        this.mSimIconRes = new int[2];
        this.mMcc = 0;
        this.mMnc = 0;
    }
     */

    /**
     * @hide
     */
    public SubInfoRecord(int id, String iccId, int simSlotIndex, CharSequence displayName,
            int nameSource, int color, String number, int roaming, int[] iconRes, int mcc,
            int mnc) {
        this.mId = id;
        this.mIccId = iccId;
        this.mSimSlotIndex = simSlotIndex;
        this.mDisplayName = displayName;
        this.mNameSource = nameSource;
        this.mColor = color;
        this.mNumber = number;
        this.mDataRoaming = roaming;
        this.mSimIconRes = iconRes;
        this.mMcc = mcc;
        this.mMnc = mnc;
    }

    /**
     * Returns the subscription ID.
     */
    public int getSubscriptionId() {
        return this.mId;
    }

    /**
     * Returns the ICC ID.
     */
    public String getIccId() {
        return this.mIccId;
    }

    /**
     * Returns the slot index of this Subscription's SIM card.
     */
    public int getSimSlotIndex() {
        return this.mSimSlotIndex;
    }

    /**
     * Returns the name displayed to the user that identifies this subscription
     */
    public CharSequence getDisplayName() {
        return this.mDisplayName;
    }

    /**
     * Sets the name displayed to the user that identifies this subscription
     * @hide
     */
    public void setDisplayName(CharSequence name) {
        this.mDisplayName = name;
    }

    /**
     * Return the source of the name, eg NAME_SOURCE_UNDEFINED, NAME_SOURCE_DEFAULT_SOURCE,
     * NAME_SOURCE_SIM_SOURCE or NAME_SOURCE_USER_INPUT.
     */
    public int getNameSource() {
        return this.mNameSource;
    }

    /**
     * Return the color to be used for when displaying to the user. This is the value of the color.
     * ex: 0x00ff00
     */
    public int getColor() {
        // Note: This color is currently an index into a list of drawables, but this is soon to
        // change.
        return this.mColor;
    }

    /**
     * Sets the color displayed to the user that identifies this subscription
     * @hide
     */
    public void setColor(int color) {
        this.mColor = color;
    }

    /**
     * Returns the number of this subscription.
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * Return the data roaming value.
     */
    public int getDataRoaming() {
        return this.mDataRoaming;
    }

    /**
     * Return the icon used to identify this subscription.
     */
    public BitmapDrawable getIcon() {
        return new BitmapDrawable();
    }

    /**
     * Returns the MCC.
     */
    public int getMcc() {
        return this.mMcc;
    }

    /**
     * Returns the MNC.
     */
    public int getMnc() {
        return this.mMnc;
    }

    public static final Parcelable.Creator<SubInfoRecord> CREATOR = new Parcelable.Creator<SubInfoRecord>() {
        @Override
        public SubInfoRecord createFromParcel(Parcel source) {
            int id = source.readInt();
            String iccId = source.readString();
            int simSlotIndex = source.readInt();
            CharSequence displayName = source.readCharSequence();
            int nameSource = source.readInt();
            int color = source.readInt();
            String number = source.readString();
            int dataRoaming = source.readInt();
            int[] iconRes = new int[2];
            source.readIntArray(iconRes);
            int mcc = source.readInt();
            int mnc = source.readInt();

            return new SubInfoRecord(id, iccId, simSlotIndex, displayName, nameSource, color, number,
                    dataRoaming, iconRes, mcc, mnc);
        }

        @Override
        public SubInfoRecord[] newArray(int size) {
            return new SubInfoRecord[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mIccId);
        dest.writeInt(mSimSlotIndex);
        dest.writeCharSequence(mDisplayName);
        dest.writeInt(mNameSource);
        dest.writeInt(mColor);
        dest.writeString(mNumber);
        dest.writeInt(mDataRoaming);
        dest.writeIntArray(mSimIconRes);
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "{id=" + mId + ", iccId=" + mIccId + " simSlotIndex=" + mSimSlotIndex
                + " displayName=" + mDisplayName + " nameSource=" + mNameSource + " color=" + mColor
                + " number=" + mNumber + " dataRoaming=" + mDataRoaming + " simIconRes=" + mSimIconRes
                + " mcc " + mMcc + " mnc " + mMnc + "}";
    }
}
