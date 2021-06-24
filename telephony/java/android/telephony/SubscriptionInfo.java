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
import android.compat.annotation.UnsupportedAppUsage;
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
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
     * The subscription carrier id.
     * @see TelephonyManager#getSimCarrierId()
     */
    private int mCarrierId;

    /**
     * The source of the name, NAME_SOURCE_DEFAULT_SOURCE, NAME_SOURCE_SIM_SPN,
     * NAME_SOURCE_SIM_PNN, or NAME_SOURCE_USER_INPUT.
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
     * Data roaming state, DATA_ROAMING_ENABLE, DATA_ROAMING_DISABLE
     */
    private int mDataRoaming;

    /**
     * SIM Icon bitmap
     */
    private Bitmap mIconBitmap;

    /**
     * Mobile Country Code
     */
    private String mMcc;

    /**
     * Mobile Network Code
     */
    private String mMnc;

    /**
     * EHPLMNs associated with the subscription
     */
    private String[] mEhplmns;

    /**
     * HPLMNs associated with the subscription
     */
    private String[] mHplmns;

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
     * This does not include access rules for non-embedded subscriptions.
     */
    @Nullable
    private UiccAccessRule[] mNativeAccessRules;

    /**
     * The carrier certificates for this subscription that are saved in carrier configs.
     * This does not include access rules from the Uicc, whether embedded or non-embedded.
     */
    @Nullable
    private UiccAccessRule[] mCarrierConfigAccessRules;

    /**
     * The string ID of the SIM card. It is the ICCID of the active profile for a UICC card and the
     * EID for an eUICC card.
     */
    private String mCardString;

    /**
     * The card ID of the SIM card. This maps uniquely to the card string.
     */
    private int mCardId;

    /**
     * Whether the subscription is opportunistic.
     */
    private boolean mIsOpportunistic;

    /**
     * A UUID assigned to the subscription group. It returns null if not assigned.
     * Check {@link SubscriptionManager#createSubscriptionGroup(List)} for more details.
     */
    @Nullable
    private ParcelUuid mGroupUUID;

    /**
     * A package name that specifies who created the group. Null if mGroupUUID is null.
     */
    private String mGroupOwner;

    /**
     * Whether group of the subscription is disabled.
     * This is only useful if it's a grouped opportunistic subscription. In this case, if all
     * primary (non-opportunistic) subscriptions in the group are deactivated (unplugged pSIM
     * or deactivated eSIM profile), we should disable this opportunistic subscription.
     */
    private boolean mIsGroupDisabled = false;

    /**
     * Profile class, PROFILE_CLASS_TESTING, PROFILE_CLASS_OPERATIONAL
     * PROFILE_CLASS_PROVISIONING, or PROFILE_CLASS_UNSET.
     * A profile on the eUICC can be defined as test, operational, provisioning, or unset.
     * The profile class will be populated from the profile metadata if present. Otherwise,
     * the profile class defaults to unset if there is no profile metadata or the subscription
     * is not on an eUICC ({@link #isEmbedded} returns false).
     */
    private int mProfileClass;

    /**
     * Type of subscription
     */
    private int mSubscriptionType;

    /**
     * Whether uicc applications are configured to enable or disable.
     * By default it's true.
     */
    private boolean mAreUiccApplicationsEnabled = true;

    /**
     * Public copy constructor.
     * @hide
     */
    public SubscriptionInfo(SubscriptionInfo info) {
        this(info.mId, info.mIccId, info.mSimSlotIndex, info.mDisplayName, info.mCarrierName,
                info.mNameSource, info.mIconTint, info.mNumber, info.mDataRoaming, info.mIconBitmap,
                info.mMcc, info.mMnc, info.mCountryIso, info.mIsEmbedded, info.mNativeAccessRules,
                info.mCardString, info.mCardId, info.mIsOpportunistic,
                info.mGroupUUID == null ? null : info.mGroupUUID.toString(), info.mIsGroupDisabled,
                info.mCarrierId, info.mProfileClass, info.mSubscriptionType, info.mGroupOwner,
                info.mCarrierConfigAccessRules, info.mAreUiccApplicationsEnabled);
    }

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString) {
        this(id, iccId, simSlotIndex, displayName, carrierName, nameSource, iconTint, number,
                roaming, icon, mcc, mnc, countryIso, isEmbedded, nativeAccessRules, cardString, -1,
                false, null, false, TelephonyManager.UNKNOWN_CARRIER_ID,
                SubscriptionManager.PROFILE_CLASS_UNSET,
                SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM, null, null, true);
    }

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString,
            boolean isOpportunistic, @Nullable String groupUUID, int carrierId, int profileClass) {
        this(id, iccId, simSlotIndex, displayName, carrierName, nameSource, iconTint, number,
                roaming, icon, mcc, mnc, countryIso, isEmbedded, nativeAccessRules, cardString, -1,
                isOpportunistic, groupUUID, false, carrierId, profileClass,
                SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM, null, null, true);
    }

    /**
     * @hide
     */
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString, int cardId,
            boolean isOpportunistic, @Nullable String groupUUID, boolean isGroupDisabled,
            int carrierId, int profileClass, int subType, @Nullable String groupOwner,
            @Nullable UiccAccessRule[] carrierConfigAccessRules,
            boolean areUiccApplicationsEnabled) {
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
        this.mNativeAccessRules = nativeAccessRules;
        this.mCardString = cardString;
        this.mCardId = cardId;
        this.mIsOpportunistic = isOpportunistic;
        this.mGroupUUID = groupUUID == null ? null : ParcelUuid.fromString(groupUUID);
        this.mIsGroupDisabled = isGroupDisabled;
        this.mCarrierId = carrierId;
        this.mProfileClass = profileClass;
        this.mSubscriptionType = subType;
        this.mGroupOwner = groupOwner;
        this.mCarrierConfigAccessRules = carrierConfigAccessRules;
        this.mAreUiccApplicationsEnabled = areUiccApplicationsEnabled;
    }

    /**
     * @return the subscription ID.
     */
    public int getSubscriptionId() {
        return this.mId;
    }

    /**
     * Returns the ICC ID.
     *
     * Starting with API level 30, returns the ICC ID if the calling app has been granted the
     * READ_PRIVILEGED_PHONE_STATE permission, has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}), or is a device owner or profile owner that
     * has been granted the READ_PHONE_STATE permission. The profile owner is an app that owns a
     * managed profile on the device; for more details see <a
     * href="https://developer.android.com/work/managed-profiles">Work profiles</a>. Profile
     * owner access is deprecated and will be removed in a future release.
     *
     * @return the ICC ID, or an empty string if one of these requirements is not met
     */
    public String getIccId() {
        return this.mIccId;
    }

    /**
     * @hide
     */
    public void clearIccId() {
        this.mIccId = "";
    }

    /**
     * @return the slot index of this Subscription's SIM card.
     */
    public int getSimSlotIndex() {
        return this.mSimSlotIndex;
    }

    /**
     * @return the carrier id of this Subscription carrier.
     * @see TelephonyManager#getSimCarrierId()
     */
    public int getCarrierId() {
        return this.mCarrierId;
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * @return the source of the name, eg NAME_SOURCE_DEFAULT_SOURCE, NAME_SOURCE_SIM_SPN or
     * NAME_SOURCE_USER_INPUT.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getNameSource() {
        return this.mNameSource;
    }

    /**
     * @hide
     */
    public void setAssociatedPlmns(String[] ehplmns, String[] hplmns) {
        mEhplmns = ehplmns;
        mHplmns = hplmns;
    }

    /**
     * Creates and returns an icon {@code Bitmap} to represent this {@code SubscriptionInfo} in a
     * user interface.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIconTint(int iconTint) {
        this.mIconTint = iconTint;
    }

    /**
     * Returns the number of this subscription.
     *
     * Starting with API level 30, returns the number of this subscription if the calling app meets
     * one of the following requirements:
     * <ul>
     *     <li>If the calling app's target SDK is API level 29 or lower and the app has been granted
     *     the READ_PHONE_STATE permission.
     *     <li>If the calling app has been granted any of READ_PRIVILEGED_PHONE_STATE,
     *     READ_PHONE_NUMBERS, or READ_SMS.
     *     <li>If the calling app has carrier privileges (see {@link
     *     TelephonyManager#hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder.
     * </ul>
     *
     * @return the number of this subscription, or an empty string if one of these requirements is
     * not met
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * @hide
     */
    public void clearNumber() {
        mNumber = "";
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
     * @deprecated Use {@link #getMccString()} instead.
     */
    @Deprecated
    public int getMcc() {
        try {
            return this.mMcc == null ? 0 : Integer.valueOf(this.mMcc);
        } catch (NumberFormatException e) {
            Log.w(SubscriptionInfo.class.getSimpleName(), "MCC string is not a number");
            return 0;
        }
    }

    /**
     * @return the MNC.
     * @deprecated Use {@link #getMncString()} instead.
     */
    @Deprecated
    public int getMnc() {
        try {
            return this.mMnc == null ? 0 : Integer.valueOf(this.mMnc);
        } catch (NumberFormatException e) {
            Log.w(SubscriptionInfo.class.getSimpleName(), "MNC string is not a number");
            return 0;
        }
    }

    /**
     * @return The MCC, as a string.
     */
    public @Nullable String getMccString() {
        return this.mMcc;
    }

    /**
     * @return The MNC, as a string.
     */
    public @Nullable String getMncString() {
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
     * An opportunistic subscription connects to a network that is
     * limited in functionality and / or coverage.
     *
     * @return whether subscription is opportunistic.
     */
    public boolean isOpportunistic() {
        return mIsOpportunistic;
    }

    /**
     * Used in scenarios where different subscriptions are bundled as a group.
     * It's typically a primary and an opportunistic subscription. (see {@link #isOpportunistic()})
     * Such that those subscriptions will have some affiliated behaviors such as opportunistic
     * subscription may be invisible to the user.
     *
     * @return group UUID a String of group UUID if it belongs to a group. Otherwise
     * it will return null.
     */
    public @Nullable ParcelUuid getGroupUuid() {
        return mGroupUUID;
    }

    /**
     * @hide
     */
    public List<String> getEhplmns() {
        return mEhplmns == null ? Collections.emptyList() : Arrays.asList(mEhplmns);
    }

    /**
     * @hide
     */
    public List<String> getHplmns() {
        return mHplmns == null ? Collections.emptyList() : Arrays.asList(mHplmns);
    }

    /**
     * Return owner package of group the subscription belongs to.
     *
     * @hide
     */
    public @Nullable String getGroupOwner() {
        return mGroupOwner;
    }

    /**
     * @return the profile class of this subscription.
     * @hide
     */
    @SystemApi
    public @SubscriptionManager.ProfileClass int getProfileClass() {
        return this.mProfileClass;
    }

    /**
     * This method returns the type of a subscription. It can be
     * {@link SubscriptionManager#SUBSCRIPTION_TYPE_LOCAL_SIM} or
     * {@link SubscriptionManager#SUBSCRIPTION_TYPE_REMOTE_SIM}.
     * @return the type of subscription
     */
    public @SubscriptionManager.SubscriptionType int getSubscriptionType() {
        return mSubscriptionType;
    }

    /**
     * Checks whether the app with the given context is authorized to manage this subscription
     * according to its metadata. Only supported for embedded subscriptions (if {@link #isEmbedded}
     * returns true).
     *
     * @param context Context of the application to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
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
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public boolean canManageSubscription(Context context, String packageName) {
        List<UiccAccessRule> allAccessRules = getAllAccessRules();
        if (allAccessRules == null) {
            return false;
        }
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName,
                PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d("SubscriptionInfo", "canManageSubscription: Unknown package: " + packageName, e);
            return false;
        }
        for (UiccAccessRule rule : allAccessRules) {
            if (rule.getCarrierPrivilegeStatus(packageInfo)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the {@link UiccAccessRule}s that are stored in Uicc, dictating who
     * is authorized to manage this subscription.
     * TODO and fix it properly in R / master: either deprecate this and have 3 APIs
     *  native + carrier + all, or have this return all by default.
     * @hide
     */
    @SystemApi
    public @Nullable List<UiccAccessRule> getAccessRules() {
        if (mNativeAccessRules == null) return null;
        return Arrays.asList(mNativeAccessRules);
    }

    /**
     * @return the {@link UiccAccessRule}s that are both stored on Uicc and in carrierConfigs
     * dictating who is authorized to manage this subscription.
     * @hide
     */
    public @Nullable List<UiccAccessRule> getAllAccessRules() {
        List<UiccAccessRule> merged = new ArrayList<>();
        if (mNativeAccessRules != null) {
            merged.addAll(getAccessRules());
        }
        if (mCarrierConfigAccessRules != null) {
            merged.addAll(Arrays.asList(mCarrierConfigAccessRules));
        }
        return merged.isEmpty() ? null : merged;
    }

    /**
     * Returns the card string of the SIM card which contains the subscription.
     *
     * Starting with API level 30, returns the card string if the calling app has been granted the
     * READ_PRIVILEGED_PHONE_STATE permission, has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}), or is a device owner or profile owner that
     * has been granted the READ_PHONE_STATE permission. The profile owner is an app that owns a
     * managed profile on the device; for more details see <a
     * href="https://developer.android.com/work/managed-profiles">Work profiles</a>. Profile
     * owner access is deprecated and will be removed in a future release.
     *
     * @return the card string of the SIM card which contains the subscription or an empty string
     * if these requirements are not met. The card string is the ICCID for UICCs or the EID for
     * eUICCs.
     * @hide
     * //TODO rename usages in LPA: UiccSlotUtil.java, UiccSlotsManager.java, UiccSlotInfoTest.java
     */
    public String getCardString() {
        return this.mCardString;
    }

    /**
     * @hide
     */
    public void clearCardString() {
        this.mCardString = "";
    }

    /**
     * Returns the card ID of the SIM card which contains the subscription (see
     * {@link UiccCardInfo#getCardId()}.
     * @return the cardId
     */
    public int getCardId() {
        return this.mCardId;
    }

    /**
     * Set whether the subscription's group is disabled.
     * @hide
     */
    public void setGroupDisabled(boolean isGroupDisabled) {
        this.mIsGroupDisabled = isGroupDisabled;
    }

    /**
     * Return whether the subscription's group is disabled.
     * @hide
     */
    @SystemApi
    public boolean isGroupDisabled() {
        return mIsGroupDisabled;
    }

    /**
     * Return whether uicc applications are set to be enabled or disabled.
     * @hide
     */
    @SystemApi
    public boolean areUiccApplicationsEnabled() {
        return mAreUiccApplicationsEnabled;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SubscriptionInfo> CREATOR = new Parcelable.Creator<SubscriptionInfo>() {
        @Override
        public SubscriptionInfo createFromParcel(Parcel source) {
            int id = source.readInt();
            String iccId = source.readString();
            int simSlotIndex = source.readInt();
            CharSequence displayName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            CharSequence carrierName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            int nameSource = source.readInt();
            int iconTint = source.readInt();
            String number = source.readString();
            int dataRoaming = source.readInt();
            String mcc = source.readString();
            String mnc = source.readString();
            String countryIso = source.readString();
            Bitmap iconBitmap = source.readParcelable(Bitmap.class.getClassLoader());
            boolean isEmbedded = source.readBoolean();
            UiccAccessRule[] nativeAccessRules = source.createTypedArray(UiccAccessRule.CREATOR);
            String cardString = source.readString();
            int cardId = source.readInt();
            boolean isOpportunistic = source.readBoolean();
            String groupUUID = source.readString();
            boolean isGroupDisabled = source.readBoolean();
            int carrierid = source.readInt();
            int profileClass = source.readInt();
            int subType = source.readInt();
            String[] ehplmns = source.createStringArray();
            String[] hplmns = source.createStringArray();
            String groupOwner = source.readString();
            UiccAccessRule[] carrierConfigAccessRules = source.createTypedArray(
                UiccAccessRule.CREATOR);
            boolean areUiccApplicationsEnabled = source.readBoolean();

            SubscriptionInfo info = new SubscriptionInfo(id, iccId, simSlotIndex, displayName,
                    carrierName, nameSource, iconTint, number, dataRoaming, iconBitmap, mcc, mnc,
                    countryIso, isEmbedded, nativeAccessRules, cardString, cardId, isOpportunistic,
                    groupUUID, isGroupDisabled, carrierid, profileClass, subType, groupOwner,
                    carrierConfigAccessRules, areUiccApplicationsEnabled);
            info.setAssociatedPlmns(ehplmns, hplmns);
            return info;
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
        TextUtils.writeToParcel(mDisplayName, dest, 0);
        TextUtils.writeToParcel(mCarrierName, dest, 0);
        dest.writeInt(mNameSource);
        dest.writeInt(mIconTint);
        dest.writeString(mNumber);
        dest.writeInt(mDataRoaming);
        dest.writeString(mMcc);
        dest.writeString(mMnc);
        dest.writeString(mCountryIso);
        dest.writeParcelable(mIconBitmap, flags);
        dest.writeBoolean(mIsEmbedded);
        dest.writeTypedArray(mNativeAccessRules, flags);
        dest.writeString(mCardString);
        dest.writeInt(mCardId);
        dest.writeBoolean(mIsOpportunistic);
        dest.writeString(mGroupUUID == null ? null : mGroupUUID.toString());
        dest.writeBoolean(mIsGroupDisabled);
        dest.writeInt(mCarrierId);
        dest.writeInt(mProfileClass);
        dest.writeInt(mSubscriptionType);
        dest.writeStringArray(mEhplmns);
        dest.writeStringArray(mHplmns);
        dest.writeString(mGroupOwner);
        dest.writeTypedArray(mCarrierConfigAccessRules, flags);
        dest.writeBoolean(mAreUiccApplicationsEnabled);
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
            if (iccId.length() > 9 && !TelephonyUtils.IS_DEBUGGABLE) {
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
        String cardStringToPrint = givePrintableIccid(mCardString);
        return "{id=" + mId + " iccId=" + iccIdToPrint + " simSlotIndex=" + mSimSlotIndex
                + " carrierId=" + mCarrierId + " displayName=" + mDisplayName
                + " carrierName=" + mCarrierName + " nameSource=" + mNameSource
                + " iconTint=" + mIconTint
                + " number=" + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, mNumber)
                + " dataRoaming=" + mDataRoaming + " iconBitmap=" + mIconBitmap + " mcc=" + mMcc
                + " mnc=" + mMnc + " countryIso=" + mCountryIso + " isEmbedded=" + mIsEmbedded
                + " nativeAccessRules=" + Arrays.toString(mNativeAccessRules)
                + " cardString=" + cardStringToPrint + " cardId=" + mCardId
                + " isOpportunistic=" + mIsOpportunistic + " groupUUID=" + mGroupUUID
                + " isGroupDisabled=" + mIsGroupDisabled
                + " profileClass=" + mProfileClass
                + " ehplmns=" + Arrays.toString(mEhplmns)
                + " hplmns=" + Arrays.toString(mHplmns)
                + " subscriptionType=" + mSubscriptionType
                + " groupOwner=" + mGroupOwner
                + " carrierConfigAccessRules=" + Arrays.toString(mCarrierConfigAccessRules)
                + " areUiccApplicationsEnabled=" + mAreUiccApplicationsEnabled + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mSimSlotIndex, mNameSource, mIconTint, mDataRoaming, mIsEmbedded,
                mIsOpportunistic, mGroupUUID, mIccId, mNumber, mMcc, mMnc, mCountryIso, mCardString,
                mCardId, mDisplayName, mCarrierName, mNativeAccessRules, mIsGroupDisabled,
                mCarrierId, mProfileClass, mGroupOwner, mAreUiccApplicationsEnabled);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;

        SubscriptionInfo toCompare;
        try {
            toCompare = (SubscriptionInfo) obj;
        } catch (ClassCastException ex) {
            return false;
        }

        return mId == toCompare.mId
                && mSimSlotIndex == toCompare.mSimSlotIndex
                && mNameSource == toCompare.mNameSource
                && mIconTint == toCompare.mIconTint
                && mDataRoaming == toCompare.mDataRoaming
                && mIsEmbedded == toCompare.mIsEmbedded
                && mIsOpportunistic == toCompare.mIsOpportunistic
                && mIsGroupDisabled == toCompare.mIsGroupDisabled
                && mAreUiccApplicationsEnabled == toCompare.mAreUiccApplicationsEnabled
                && mCarrierId == toCompare.mCarrierId
                && Objects.equals(mGroupUUID, toCompare.mGroupUUID)
                && Objects.equals(mIccId, toCompare.mIccId)
                && Objects.equals(mNumber, toCompare.mNumber)
                && Objects.equals(mMcc, toCompare.mMcc)
                && Objects.equals(mMnc, toCompare.mMnc)
                && Objects.equals(mCountryIso, toCompare.mCountryIso)
                && Objects.equals(mCardString, toCompare.mCardString)
                && Objects.equals(mCardId, toCompare.mCardId)
                && Objects.equals(mGroupOwner, toCompare.mGroupOwner)
                && TextUtils.equals(mDisplayName, toCompare.mDisplayName)
                && TextUtils.equals(mCarrierName, toCompare.mCarrierName)
                && Arrays.equals(mNativeAccessRules, toCompare.mNativeAccessRules)
                && mProfileClass == toCompare.mProfileClass
                && Arrays.equals(mEhplmns, toCompare.mEhplmns)
                && Arrays.equals(mHplmns, toCompare.mHplmns);
    }
}
