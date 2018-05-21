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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * A Parcelable class for Subscription Information.
 */
public class SubscriptionInfo implements Parcelable {

    /**
     * Size of text to render on the icon.
     */
    private static final int TEXT_SIZE = 16;

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
     * String that identifies SPN/PLMN
     * TODO : Add a new field that identifies only SPN for a sim
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
     * ISO Country code for the subscription's provider
     */
    private String mCountryIso;

    /**
     * Whether the subscription is an embedded one.
     */
    private boolean mIsEmbedded;

    /**
     * The access rules for this subscription, if it is embedded and defines any.
     */
    @Nullable
    private UiccAccessRule[] mAccessRules;

    /**
     * The ID of the SIM card. It is the ICCID of the active profile for a UICC card and the EID
     * for an eUICC card.
     */
    private String mCardId;

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
        CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
        Bitmap icon, int mcc, int mnc, String countryIso) {
        this(id, iccId, simSlotIndex, displayName, carrierName, nameSource, iconTint, number,
            roaming, icon, mcc, mnc, countryIso, false /* isEmbedded */,
            null /* accessRules */, null /* accessRules */);
    }

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, int mcc, int mnc, String countryIso,  boolean isEmbedded,
            @Nullable UiccAccessRule[] accessRules) {
        this(id, iccId, simSlotIndex, displayName, carrierName, nameSource, iconTint, number,
                roaming, icon, mcc, mnc, countryIso, isEmbedded, accessRules, null /* cardId */);
    }

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, int mcc, int mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] accessRules, String cardId) {
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
        this.mCountryIso = countryIso;
        this.mIsEmbedded = isEmbedded;
        this.mAccessRules = accessRules;
        this.mCardId = cardId;
    }

    /**
     * @return the subscription ID.
     */
    public int getSubscriptionId() {
        return this.mId;
    }

    /**
     * @return the ICC ID.
     */
    public String getIccId() {
        return this.mIccId;
    }

    /**
     * @return the slot index of this Subscription's SIM card.
     */
    public int getSimSlotIndex() {
        return this.mSimSlotIndex;
    }

    /**
     * @return the name displayed to the user that identifies this subscription
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
     * @return the name displayed to the user that identifies Subscription provider name
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
     * @return the source of the name, eg NAME_SOURCE_UNDEFINED, NAME_SOURCE_DEFAULT_SOURCE,
     * NAME_SOURCE_SIM_SOURCE or NAME_SOURCE_USER_INPUT.
     * @hide
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
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();

        // Create a new bitmap of the same size because it will be modified.
        Bitmap workingBitmap = Bitmap.createBitmap(metrics, width, height, mIconBitmap.getConfig());

        Canvas canvas = new Canvas(workingBitmap);
        Paint paint = new Paint();

        // Tint the icon with the color.
        paint.setColorFilter(new PorterDuffColorFilter(mIconTint, PorterDuff.Mode.SRC_ATOP));
        canvas.drawBitmap(mIconBitmap, 0, 0, paint);
        paint.setColorFilter(null);

        // Write the sim slot index.
        paint.setAntiAlias(true);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setColor(Color.WHITE);
        // Set text size scaled by density
        paint.setTextSize(TEXT_SIZE * metrics.density);
        // Convert sim slot index to localized string
        final String index = String.format("%d", mSimSlotIndex + 1);
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
     * @return the number of this subscription.
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * @return the data roaming state for this subscription, either
     * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or {@link SubscriptionManager#DATA_ROAMING_DISABLE}.
     */
    public int getDataRoaming() {
        return this.mDataRoaming;
    }

    /**
     * @return the MCC.
     */
    public int getMcc() {
        return this.mMcc;
    }

    /**
     * @return the MNC.
     */
    public int getMnc() {
        return this.mMnc;
    }

    /**
     * @return the ISO country code
     */
    public String getCountryIso() {
        return this.mCountryIso;
    }

    /** @return whether the subscription is an eUICC one. */
    public boolean isEmbedded() {
        return this.mIsEmbedded;
    }

    /**
     * Checks whether the app with the given context is authorized to manage this subscription
     * according to its metadata. Only supported for embedded subscriptions (if {@link #isEmbedded}
     * returns true).
     *
     * @param context Context of the application to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
     * @throws UnsupportedOperationException if this subscription is not embedded.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public boolean canManageSubscription(Context context) {
        return canManageSubscription(context, context.getPackageName());
    }

    /**
     * Checks whether the given app is authorized to manage this subscription according to its
     * metadata. Only supported for embedded subscriptions (if {@link #isEmbedded} returns true).
     *
     * @param context Any context.
     * @param packageName Package name of the app to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
     * @throws UnsupportedOperationException if this subscription is not embedded.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public boolean canManageSubscription(Context context, String packageName) {
        if (!isEmbedded()) {
            throw new UnsupportedOperationException("Not an embedded subscription");
        }
        if (mAccessRules == null) {
            return false;
        }
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package: " + packageName, e);
        }
        for (UiccAccessRule rule : mAccessRules) {
            if (rule.getCarrierPrivilegeStatus(packageInfo)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the {@link UiccAccessRule}s dictating who is authorized to manage this subscription.
     * @throws UnsupportedOperationException if this subscription is not embedded.
     * @hide
     */
    @SystemApi
    public @Nullable List<UiccAccessRule> getAccessRules() {
        if (!isEmbedded()) {
            throw new UnsupportedOperationException("Not an embedded subscription");
        }
        if (mAccessRules == null) return null;
        return Arrays.asList(mAccessRules);
    }

    /**
     * @return the ID of the SIM card which contains the subscription.
     * @hide
     */
    public String getCardId() {
        return this.mCardId;
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
            String countryIso = source.readString();
            Bitmap iconBitmap = Bitmap.CREATOR.createFromParcel(source);
            boolean isEmbedded = source.readBoolean();
            UiccAccessRule[] accessRules = source.createTypedArray(UiccAccessRule.CREATOR);
            String cardId = source.readString();

            return new SubscriptionInfo(id, iccId, simSlotIndex, displayName, carrierName,
                    nameSource, iconTint, number, dataRoaming, iconBitmap, mcc, mnc, countryIso,
                    isEmbedded, accessRules, cardId);
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
        dest.writeString(mCountryIso);
        mIconBitmap.writeToParcel(dest, flags);
        dest.writeBoolean(mIsEmbedded);
        dest.writeTypedArray(mAccessRules, flags);
        dest.writeString(mCardId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public static String givePrintableIccid(String iccId) {
        String iccIdToPrint = null;
        if (iccId != null) {
            if (iccId.length() > 9 && !Build.IS_DEBUGGABLE) {
                iccIdToPrint = iccId.substring(0, 9) + Rlog.pii(false, iccId.substring(9));
            } else {
                iccIdToPrint = iccId;
            }
        }
        return iccIdToPrint;
    }

    @Override
    public String toString() {
        String iccIdToPrint = givePrintableIccid(mIccId);
        String cardIdToPrint = givePrintableIccid(mCardId);
        return "{id=" + mId + ", iccId=" + iccIdToPrint + " simSlotIndex=" + mSimSlotIndex
                + " displayName=" + mDisplayName + " carrierName=" + mCarrierName
                + " nameSource=" + mNameSource + " iconTint=" + mIconTint
                + " dataRoaming=" + mDataRoaming + " iconBitmap=" + mIconBitmap + " mcc " + mMcc
                + " mnc " + mMnc + " isEmbedded " + mIsEmbedded
                + " accessRules " + Arrays.toString(mAccessRules)
                + " cardId=" + cardIdToPrint + "}";
    }
}
