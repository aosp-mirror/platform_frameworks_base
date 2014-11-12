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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable class for Subscription Information.
 */
public class SubscriptionInfo implements Parcelable {

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
     * The string displayed to the user that identifies Subscription Provider Name
     */
    private CharSequence mCarrierName;

    /**
     * The source of the name, NAME_SOURCE_UNDEFINED, NAME_SOURCE_DEFAULT_SOURCE,
     * NAME_SOURCE_SIM_SOURCE or NAME_SOURCE_USER_INPUT.
     */
    private int mNameSource;

    /**
     * The color to be used for tinting the icon when displaying to the user
     */
    private int mIconTint;

    /**
     * A number presented to the user identify this subscription
     */
    private String mNumber;

    /**
     * Data roaming state, DATA_RAOMING_ENABLE, DATA_RAOMING_DISABLE
     */
    private int mDataRoaming;

    /**
     * SIM Icon bitmap
     */
    private Bitmap mIconBitmap;

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
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, int mcc, int mnc) {
        this.mId = id;
        this.mIccId = iccId;
        this.mSimSlotIndex = simSlotIndex;
        this.mDisplayName = displayName;
        this.mCarrierName = carrierName;
        this.mNameSource = nameSource;
        this.mIconTint = iconTint;
        this.mNumber = number;
        this.mDataRoaming = roaming;
        this.mIconBitmap = icon;
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
     * Returns the name displayed to the user that identifies Subscription provider name
     * @hide
     */
    public CharSequence getCarrierName() {
        return this.mCarrierName;
    }

    /**
     * Sets the name displayed to the user that identifies Subscription provider name
     * @hide
     */
    public void setCarrierName(CharSequence name) {
        this.mCarrierName = name;
    }

    /**
     * Return the source of the name, eg NAME_SOURCE_UNDEFINED, NAME_SOURCE_DEFAULT_SOURCE,
     * NAME_SOURCE_SIM_SOURCE or NAME_SOURCE_USER_INPUT.
     */
    public int getNameSource() {
        return this.mNameSource;
    }

    /**
     * Creates and returns an icon {@code Bitmap} to represent this {@code SubscriptionInfo} in a user
     * interface.
     *
     * @param context A {@code Context} to get the {@code DisplayMetrics}s from.
     *
     * @return A bitmap icon for this {@code SubscriptionInfo}.
     */
    public Bitmap createIconBitmap(Context context) {
        int width = mIconBitmap.getWidth();
        int height = mIconBitmap.getHeight();

        // Create a new bitmap of the same size because it will be modified.
        Bitmap workingBitmap = Bitmap.createBitmap(context.getResources().getDisplayMetrics(),
                width, height, mIconBitmap.getConfig());

        Canvas canvas = new Canvas(workingBitmap);
        Paint paint = new Paint();

        // Tint the icon with the color.
        paint.setColorFilter(new PorterDuffColorFilter(mIconTint, PorterDuff.Mode.SRC_ATOP));
        canvas.drawBitmap(mIconBitmap, 0, 0, paint);
        paint.setColorFilter(null);

        // Write the sim slot index.
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setColor(Color.WHITE);
        paint.setTextSize(12);
        final String index = Integer.toString(mSimSlotIndex);
        final Rect textBound = new Rect();
        paint.getTextBounds(index, 0, 1, textBound);
        final float xOffset = (width / 2.f) - textBound.centerX();
        final float yOffset = (height / 2.f) - textBound.centerY();
        canvas.drawText(index, xOffset, yOffset, paint);

        return workingBitmap;
    }

    /**
     * A highlight color to use in displaying information about this {@code PhoneAccount}.
     *
     * @return A hexadecimal color value.
     */
    public int getIconTint() {
        return mIconTint;
    }

    /**
     * Sets the color displayed to the user that identifies this subscription
     * @hide
     */
    public void setIconTint(int iconTint) {
        this.mIconTint = iconTint;
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

    public static final Parcelable.Creator<SubscriptionInfo> CREATOR = new Parcelable.Creator<SubscriptionInfo>() {
        @Override
        public SubscriptionInfo createFromParcel(Parcel source) {
            int id = source.readInt();
            String iccId = source.readString();
            int simSlotIndex = source.readInt();
            CharSequence displayName = source.readCharSequence();
            CharSequence carrierName = source.readCharSequence();
            int nameSource = source.readInt();
            int iconTint = source.readInt();
            String number = source.readString();
            int dataRoaming = source.readInt();
            int mcc = source.readInt();
            int mnc = source.readInt();
            Bitmap iconBitmap = Bitmap.CREATOR.createFromParcel(source);

            return new SubscriptionInfo(id, iccId, simSlotIndex, displayName, carrierName, nameSource,
                    iconTint, number, dataRoaming, iconBitmap, mcc, mnc);
        }

        @Override
        public SubscriptionInfo[] newArray(int size) {
            return new SubscriptionInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mIccId);
        dest.writeInt(mSimSlotIndex);
        dest.writeCharSequence(mDisplayName);
        dest.writeCharSequence(mCarrierName);
        dest.writeInt(mNameSource);
        dest.writeInt(mIconTint);
        dest.writeString(mNumber);
        dest.writeInt(mDataRoaming);
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
        mIconBitmap.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "{id=" + mId + ", iccId=" + mIccId + " simSlotIndex=" + mSimSlotIndex
                + " displayName=" + mDisplayName + " carrierName=" + mCarrierName
                + " nameSource=" + mNameSource + " iconTint=" + mIconTint + " number=" + mNumber
                + " dataRoaming=" + mDataRoaming + " iconBitmap=" + mIconBitmap + " mcc " + mMcc
                + " mnc " + mMnc + "}";
    }
}
