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

import static android.text.TextUtils.formatSimple;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.telephony.SubscriptionManager.ProfileClass;
import android.telephony.SubscriptionManager.SimDisplayNameSource;
import android.telephony.SubscriptionManager.SubscriptionType;
import android.telephony.SubscriptionManager.UsageSetting;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.internal.telephony.flags.Flags;
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
    private final int mId;

    /**
     * The ICCID of the SIM that is associated with this subscription, empty if unknown.
     */
    @NonNull
    private final String mIccId;

    /**
     * The index of the SIM slot that currently contains the subscription and not necessarily unique
     * and maybe {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if unknown or the subscription
     * is inactive.
     */
    private final int mSimSlotIndex;

    /**
     * The name displayed to the user that identifies this subscription. This name is used
     * in Settings page and can be renamed by the user.
     */
    @NonNull
    private final CharSequence mDisplayName;

    /**
     * The name displayed to the user that identifies subscription provider name. This name is the
     * SPN displayed in status bar and many other places. Can't be renamed by the user.
     */
    @NonNull
    private final CharSequence mCarrierName;

    /**
     * The source of the {@link #mDisplayName}.
     */
    @SimDisplayNameSource
    private final int mDisplayNameSource;

    /**
     * The color to be used for tinting the icon when displaying to the user.
     */
    private final int mIconTint;

    /**
     * The number presented to the user identify this subscription.
     */
    @NonNull
    private final String mNumber;

    /**
     * Whether user enables data roaming for this subscription or not. Either
     * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or
     * {@link SubscriptionManager#DATA_ROAMING_DISABLE}.
     */
    private final int mDataRoaming;

    /**
     * Mobile Country Code.
     */
    @Nullable
    private final String mMcc;

    /**
     * Mobile Network Code.
     */
    @Nullable
    private final String mMnc;

    /**
     * EHPLMNs associated with the subscription.
     */
    @NonNull
    private final String[] mEhplmns;

    /**
     * HPLMNs associated with the subscription.
     */
    @NonNull
    private final String[] mHplmns;

    /**
     * Whether the subscription is from eSIM.
     */
    private final boolean mIsEmbedded;

    /**
     * The string ID of the SIM card. It is the ICCID of the active profile for a UICC card and the
     * EID for an eUICC card.
     */
    @NonNull
    private final String mCardString;

    /**
     * The access rules for this subscription, if it is embedded and defines any. This does not
     * include access rules for non-embedded subscriptions.
     */
    @Nullable
    private final UiccAccessRule[] mNativeAccessRules;

    /**
     * The carrier certificates for this subscription that are saved in carrier configs.
     * This does not include access rules from the Uicc, whether embedded or non-embedded.
     */
    @Nullable
    private final UiccAccessRule[] mCarrierConfigAccessRules;

    /**
     * Whether the subscription is opportunistic.
     */
    private final boolean mIsOpportunistic;

    /**
     * A UUID assigned to the subscription group. {@code null} if not assigned.
     *
     * @see SubscriptionManager#createSubscriptionGroup(List)
     */
    @Nullable
    private final ParcelUuid mGroupUuid;

    /**
     * ISO Country code for the subscription's provider.
     */
    @NonNull
    private final String mCountryIso;

    /**
     * The subscription carrier id.
     *
     * @see TelephonyManager#getSimCarrierId()
     */
    private final int mCarrierId;

    /**
     * The profile class populated from the profile metadata if present. Otherwise,
     * the profile class defaults to {@link SubscriptionManager#PROFILE_CLASS_UNSET} if there is no
     * profile metadata or the subscription is not on an eUICC ({@link #isEmbedded} returns
     * {@code false}).
     */
    @ProfileClass
    private final int mProfileClass;

    /**
     * Type of the subscription.
     */
    @SubscriptionType
    private final int mType;

    /**
     * A package name that specifies who created the group. Empty if not available.
     */
    @NonNull
    private final String mGroupOwner;

    /**
     * Whether uicc applications are configured to enable or disable.
     * By default it's true.
     */
    private final boolean mAreUiccApplicationsEnabled;

    /**
     * The port index of the Uicc card.
     */
    private final int mPortIndex;

    /**
     * Subscription's preferred usage setting.
     */
    @UsageSetting
    private final int mUsageSetting;

    // Below are the fields that do not exist in the database.

    /**
     * SIM icon bitmap cache.
     */
    @Nullable
    private Bitmap mIconBitmap;

    /**
     * The card ID of the SIM card. This maps uniquely to {@link #mCardString}.
     */
    private final int mCardId;

    /**
     * Whether group of the subscription is disabled. This is only useful if it's a grouped
     * opportunistic subscription. In this case, if all primary (non-opportunistic) subscriptions
     * in the group are deactivated (unplugged pSIM or deactivated eSIM profile), we should disable
     * this opportunistic subscription.
     */
    private final boolean mIsGroupDisabled;

    /**
     * Whether this subscription is used for communicating with non-terrestrial networks.
     */
    private final boolean mIsOnlyNonTerrestrialNetwork;

    /**
     * @hide
     *
     * @deprecated Use {@link SubscriptionInfo.Builder}.
     */
    // TODO: Clean up after external usages moved to builder model.
    @Deprecated
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
     *
     * @deprecated Use {@link SubscriptionInfo.Builder}.
     */
    // TODO: Clean up after external usages moved to builder model.
    @Deprecated
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
     *
     * @deprecated Use {@link SubscriptionInfo.Builder}.
     */
    // TODO: Clean up after external usages moved to builder model.
    @Deprecated
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString, int cardId,
            boolean isOpportunistic, @Nullable String groupUUID, boolean isGroupDisabled,
            int carrierId, int profileClass, int subType, @Nullable String groupOwner,
            @Nullable UiccAccessRule[] carrierConfigAccessRules,
            boolean areUiccApplicationsEnabled) {
        this(id, iccId, simSlotIndex, displayName, carrierName, nameSource, iconTint, number,
                roaming, icon, mcc, mnc, countryIso, isEmbedded, nativeAccessRules, cardString,
                cardId, isOpportunistic, groupUUID, isGroupDisabled, carrierId, profileClass,
                subType, groupOwner, carrierConfigAccessRules, areUiccApplicationsEnabled, 0);
    }

    /**
     * @hide
     *
     * @deprecated Use {@link SubscriptionInfo.Builder}.
     */
    // TODO: Clean up after external usages moved to builder model.
    @Deprecated
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int displayNameSource, int iconTint, String number,
            int roaming, Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString, int cardId,
            boolean isOpportunistic, @Nullable String groupUUID, boolean isGroupDisabled,
            int carrierId, int profileClass, int subType, @Nullable String groupOwner,
            @Nullable UiccAccessRule[] carrierConfigAccessRules,
            boolean areUiccApplicationsEnabled, int portIndex) {
        this(id, iccId, simSlotIndex, displayName, carrierName, displayNameSource, iconTint, number,
                roaming, icon, mcc, mnc, countryIso, isEmbedded, nativeAccessRules, cardString,
                cardId, isOpportunistic, groupUUID, isGroupDisabled, carrierId, profileClass,
                subType, groupOwner, carrierConfigAccessRules, areUiccApplicationsEnabled,
                portIndex, SubscriptionManager.USAGE_SETTING_DEFAULT);
    }

    /**
     * @hide
     *
     * @deprecated Use {@link SubscriptionInfo.Builder}.
     */
    // TODO: Clean up after external usages moved to builder model.
    @Deprecated
    public SubscriptionInfo(int id, String iccId, int simSlotIndex, CharSequence displayName,
            CharSequence carrierName, int nameSource, int iconTint, String number, int roaming,
            Bitmap icon, String mcc, String mnc, String countryIso, boolean isEmbedded,
            @Nullable UiccAccessRule[] nativeAccessRules, String cardString, int cardId,
            boolean isOpportunistic, @Nullable String groupUuid, boolean isGroupDisabled,
            int carrierId, int profileClass, int subType, @Nullable String groupOwner,
            @Nullable UiccAccessRule[] carrierConfigAccessRules,
            boolean areUiccApplicationsEnabled, int portIndex, @UsageSetting int usageSetting) {
        this.mId = id;
        this.mIccId = iccId;
        this.mSimSlotIndex = simSlotIndex;
        this.mDisplayName =  displayName;
        this.mCarrierName = carrierName;
        this.mDisplayNameSource = nameSource;
        this.mIconTint = iconTint;
        this.mNumber = number;
        this.mDataRoaming = roaming;
        this.mIconBitmap = icon;
        this.mMcc = TextUtils.emptyIfNull(mcc);
        this.mMnc = TextUtils.emptyIfNull(mnc);
        this.mHplmns = null;
        this.mEhplmns = null;
        this.mCountryIso = TextUtils.emptyIfNull(countryIso);
        this.mIsEmbedded = isEmbedded;
        this.mNativeAccessRules = nativeAccessRules;
        this.mCardString = TextUtils.emptyIfNull(cardString);
        this.mCardId = cardId;
        this.mIsOpportunistic = isOpportunistic;
        this.mGroupUuid = groupUuid == null ? null : ParcelUuid.fromString(groupUuid);
        this.mIsGroupDisabled = isGroupDisabled;
        this.mCarrierId = carrierId;
        this.mProfileClass = profileClass;
        this.mType = subType;
        this.mGroupOwner = TextUtils.emptyIfNull(groupOwner);
        this.mCarrierConfigAccessRules = carrierConfigAccessRules;
        this.mAreUiccApplicationsEnabled = areUiccApplicationsEnabled;
        this.mPortIndex = portIndex;
        this.mUsageSetting = usageSetting;
        this.mIsOnlyNonTerrestrialNetwork = false;
    }

    /**
     * Constructor from builder.
     *
     * @param builder Builder of {@link SubscriptionInfo}.
     */
    private SubscriptionInfo(@NonNull Builder builder) {
        this.mId = builder.mId;
        this.mIccId = builder.mIccId;
        this.mSimSlotIndex = builder.mSimSlotIndex;
        this.mDisplayName = builder.mDisplayName;
        this.mCarrierName = builder.mCarrierName;
        this.mDisplayNameSource = builder.mDisplayNameSource;
        this.mIconTint = builder.mIconTint;
        this.mNumber = builder.mNumber;
        this.mDataRoaming = builder.mDataRoaming;
        this.mIconBitmap = builder.mIconBitmap;
        this.mMcc = builder.mMcc;
        this.mMnc = builder.mMnc;
        this.mEhplmns = builder.mEhplmns;
        this.mHplmns = builder.mHplmns;
        this.mCountryIso = builder.mCountryIso;
        this.mIsEmbedded = builder.mIsEmbedded;
        this.mNativeAccessRules = builder.mNativeAccessRules;
        this.mCardString = builder.mCardString;
        this.mCardId = builder.mCardId;
        this.mIsOpportunistic = builder.mIsOpportunistic;
        this.mGroupUuid = builder.mGroupUuid;
        this.mIsGroupDisabled = builder.mIsGroupDisabled;
        this.mCarrierId = builder.mCarrierId;
        this.mProfileClass = builder.mProfileClass;
        this.mType = builder.mType;
        this.mGroupOwner = builder.mGroupOwner;
        this.mCarrierConfigAccessRules = builder.mCarrierConfigAccessRules;
        this.mAreUiccApplicationsEnabled = builder.mAreUiccApplicationsEnabled;
        this.mPortIndex = builder.mPortIndex;
        this.mUsageSetting = builder.mUsageSetting;
        this.mIsOnlyNonTerrestrialNetwork = builder.mIsOnlyNonTerrestrialNetwork;
    }

    /**
     * @return The subscription ID.
     */
    public int getSubscriptionId() {
        return mId;
    }

    /**
     * Returns the ICC ID.
     *
     * Starting with API level 29 Security Patch 2021-04-05, returns the ICC ID if the calling app
     * has been granted the READ_PRIVILEGED_PHONE_STATE permission, has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}), or is a device owner or profile owner that
     * has been granted the READ_PHONE_STATE permission. The profile owner is an app that owns a
     * managed profile on the device; for more details see <a
     * href="https://developer.android.com/work/managed-profiles">Work profiles</a>. Profile
     * owner access is deprecated and will be removed in a future release.
     *
     * @return the ICC ID, or an empty string if one of these requirements is not met
     */
    public String getIccId() {
        return mIccId;
    }

    /**
     * @return The index of the SIM slot that currently contains the subscription and not
     * necessarily unique and maybe {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if unknown or
     * the subscription is inactive.
     */
    public int getSimSlotIndex() {
        return mSimSlotIndex;
    }

    /**
     * @return The carrier id of this subscription carrier.
     *
     * @see TelephonyManager#getSimCarrierId()
     */
    public int getCarrierId() {
        return mCarrierId;
    }

    /**
     * @return The name displayed to the user that identifies this subscription. This name is
     * used in Settings page and can be renamed by the user.
     *
     * @see #getCarrierName()
     */
    public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return The name displayed to the user that identifies subscription provider name. This name
     * is the SPN displayed in status bar and many other places. Can't be renamed by the user.
     *
     * @see #getDisplayName()
     */
    public CharSequence getCarrierName() {
        return mCarrierName;
    }

    /**
     * @return The source of the {@link #getDisplayName()}.
     *
     * @hide
     */
    @SimDisplayNameSource
    public int getDisplayNameSource() {
        return mDisplayNameSource;
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
        if (mIconBitmap == null) {
            mIconBitmap = BitmapFactory.decodeResource(context.getResources(),
                    com.android.internal.R.drawable.ic_sim_card_multi_24px_clr);
        }
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
        final String index = formatSimple("%d", mSimSlotIndex + 1);
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
     * @deprecated use {@link SubscriptionManager#getPhoneNumber(int)} instead, which takes a
     *             {@link #getSubscriptionId() subscription ID}.
     */
    @Deprecated
    public String getNumber() {
        return mNumber;
    }

    /**
     * Whether user enables data roaming for this subscription or not. Either
     * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or
     * {@link SubscriptionManager#DATA_ROAMING_DISABLE}.
     */
    public int getDataRoaming() {
        return mDataRoaming;
    }

    /**
     * @return The mobile country code.
     *
     * @deprecated Use {@link #getMccString()} instead.
     */
    @Deprecated
    public int getMcc() {
        try {
            return mMcc == null ? 0 : Integer.parseInt(mMcc);
        } catch (NumberFormatException e) {
            Log.w(SubscriptionInfo.class.getSimpleName(), "MCC string is not a number");
            return 0;
        }
    }

    /**
     * @return The mobile network code.
     *
     * @deprecated Use {@link #getMncString()} instead.
     */
    @Deprecated
    public int getMnc() {
        try {
            return mMnc == null ? 0 : Integer.parseInt(mMnc);
        } catch (NumberFormatException e) {
            Log.w(SubscriptionInfo.class.getSimpleName(), "MNC string is not a number");
            return 0;
        }
    }

    /**
     * @return The mobile country code.
     */
    @Nullable
    public String getMccString() {
        return mMcc;
    }

    /**
     * @return The mobile network code.
     */
    @Nullable
    public String getMncString() {
        return mMnc;
    }

    /**
     * @return The ISO country code. Empty if not available.
     */
    public String getCountryIso() {
        return mCountryIso;
    }

    /**
     * @return {@code true} if the subscription is from eSIM.
     */
    public boolean isEmbedded() {
        return mIsEmbedded;
    }

    /**
     * An opportunistic subscription connects to a network that is
     * limited in functionality and / or coverage.
     *
     * @return Whether subscription is opportunistic.
     */
    public boolean isOpportunistic() {
        return mIsOpportunistic;
    }

    /**
     * @return {@code true} if the subscription is from the actively used SIM.
     *
     * @hide
     */
    public boolean isActive() {
        return mSimSlotIndex >= 0 || mType == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM;
    }

    /**
     * Used in scenarios where different subscriptions are bundled as a group.
     * It's typically a primary and an opportunistic subscription. (see {@link #isOpportunistic()})
     * Such that those subscriptions will have some affiliated behaviors such as opportunistic
     * subscription may be invisible to the user.
     *
     * @return Group UUID a String of group UUID if it belongs to a group. Otherwise
     * {@code null}.
     */
    @Nullable
    public ParcelUuid getGroupUuid() {
        return mGroupUuid;
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getEhplmns() {
        return Collections.unmodifiableList(mEhplmns == null
                ? Collections.emptyList() : Arrays.asList(mEhplmns));
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getHplmns() {
        return Collections.unmodifiableList(mHplmns == null
                ? Collections.emptyList() : Arrays.asList(mHplmns));
    }

    /**
     * @return The owner package of group the subscription belongs to.
     *
     * @hide
     */
    @NonNull
    public String getGroupOwner() {
        return mGroupOwner;
    }

    /**
     * @return The profile class populated from the profile metadata if present. Otherwise,
     * the profile class defaults to {@link SubscriptionManager#PROFILE_CLASS_UNSET} if there is no
     * profile metadata or the subscription is not on an eUICC ({@link #isEmbedded} return
     * {@code false}).
     *
     * @hide
     */
    @SystemApi
    @ProfileClass
    public int getProfileClass() {
        return mProfileClass;
    }

    /**
     * This method returns the type of a subscription. It can be
     * {@link SubscriptionManager#SUBSCRIPTION_TYPE_LOCAL_SIM} or
     * {@link SubscriptionManager#SUBSCRIPTION_TYPE_REMOTE_SIM}.
     *
     * @return The type of the subscription.
     */
    @SubscriptionType
    public int getSubscriptionType() {
        return mType;
    }

    /**
     * Checks whether the app with the given context is authorized to manage this subscription
     * according to its metadata. Only supported for embedded subscriptions (if {@link #isEmbedded}
     * returns true).
     *
     * @param context Context of the application to check.
     * @return Whether the app is authorized to manage this subscription per its metadata.
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
        List<UiccAccessRule> allAccessRules = getAccessRules();
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
     * @return The {@link UiccAccessRule}s that are stored in Uicc, dictating who is authorized to
     * manage this subscription.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public List<UiccAccessRule> getAccessRules() {
        List<UiccAccessRule> merged = new ArrayList<>();
        if (mNativeAccessRules != null) {
            merged.addAll(Arrays.asList(mNativeAccessRules));
        }
        if (mCarrierConfigAccessRules != null) {
            merged.addAll(Arrays.asList(mCarrierConfigAccessRules));
        }
        return merged.isEmpty() ? null : Collections.unmodifiableList(merged);
    }

    /**
     * Returns the card string of the SIM card which contains the subscription.
     *
     * Starting with API level 29 Security Patch 2021-04-05, returns the card string if the calling
     * app has been granted the READ_PRIVILEGED_PHONE_STATE permission, has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}), or is a device owner or profile owner that
     * has been granted the READ_PHONE_STATE permission. The profile owner is an app that owns a
     * managed profile on the device; for more details see <a
     * href="https://developer.android.com/work/managed-profiles">Work profiles</a>. Profile
     * owner access is deprecated and will be removed in a future release.
     *
     * @return The card string of the SIM card which contains the subscription or an empty string
     * if these requirements are not met. The card string is the ICCID for UICCs or the EID for
     * eUICCs.
     *
     * @hide
     */
    @NonNull
    public String getCardString() {
        return mCardString;
    }

    /**
     * @return The card ID of the SIM card which contains the subscription.
     *
     * @see UiccCardInfo#getCardId().
     */
    public int getCardId() {
        return mCardId;
    }
    /**
     * @return The port index of the SIM card which contains the subscription.
     */
    public int getPortIndex() {
        return mPortIndex;
    }

    /**
     * @return {@code true} if the group of the subscription is disabled. This is only useful if
     * it's a grouped opportunistic subscription. In this case, if all primary (non-opportunistic)
     * subscriptions in the group are deactivated (unplugged pSIM or deactivated eSIM profile), we
     * should disable this opportunistic subscription.
     *
     * @hide
     */
    @SystemApi
    public boolean isGroupDisabled() {
        return mIsGroupDisabled;
    }

    /**
     * @return {@code true} if Uicc applications are set to be enabled or disabled.
     * @hide
     */
    @SystemApi
    public boolean areUiccApplicationsEnabled() {
        return mAreUiccApplicationsEnabled;
    }

    /**
     * Get the usage setting for this subscription.
     *
     * @return The usage setting used for this subscription.
     */
    @UsageSetting
    public int getUsageSetting() {
        return mUsageSetting;
    }

    /**
     * Check if the subscription is exclusively for non-terrestrial networks.
     *
     * @return {@code true} if it is a non-terrestrial network subscription, {@code false}
     * otherwise.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public boolean isOnlyNonTerrestrialNetwork() {
        return mIsOnlyNonTerrestrialNetwork;
    }

    @NonNull
    public static final Parcelable.Creator<SubscriptionInfo> CREATOR =
            new Parcelable.Creator<SubscriptionInfo>() {
        @Override
        public SubscriptionInfo createFromParcel(Parcel source) {
            return new Builder()
                    .setId(source.readInt())
                    .setIccId(source.readString())
                    .setSimSlotIndex(source.readInt())
                    .setDisplayName(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source))
                    .setCarrierName(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source))
                    .setDisplayNameSource(source.readInt())
                    .setIconTint(source.readInt())
                    .setNumber(source.readString())
                    .setDataRoaming(source.readInt())
                    .setMcc(source.readString())
                    .setMnc(source.readString())
                    .setCountryIso(source.readString())
                    .setEmbedded(source.readBoolean())
                    .setNativeAccessRules(source.createTypedArray(UiccAccessRule.CREATOR))
                    .setCardString(source.readString())
                    .setCardId(source.readInt())
                    .setPortIndex(source.readInt())
                    .setOpportunistic(source.readBoolean())
                    .setGroupUuid(source.readString8())
                    .setGroupDisabled(source.readBoolean())
                    .setCarrierId(source.readInt())
                    .setProfileClass(source.readInt())
                    .setType(source.readInt())
                    .setEhplmns(source.createStringArray())
                    .setHplmns(source.createStringArray())
                    .setGroupOwner(source.readString())
                    .setCarrierConfigAccessRules(source.createTypedArray(
                            UiccAccessRule.CREATOR))
                    .setUiccApplicationsEnabled(source.readBoolean())
                    .setUsageSetting(source.readInt())
                    .setOnlyNonTerrestrialNetwork(source.readBoolean())
                    .build();
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
        dest.writeInt(mDisplayNameSource);
        dest.writeInt(mIconTint);
        dest.writeString(mNumber);
        dest.writeInt(mDataRoaming);
        dest.writeString(mMcc);
        dest.writeString(mMnc);
        dest.writeString(mCountryIso);
        // Do not write mIconBitmap since it should be lazily loaded on first usage
        dest.writeBoolean(mIsEmbedded);
        dest.writeTypedArray(mNativeAccessRules, flags);
        dest.writeString(mCardString);
        dest.writeInt(mCardId);
        dest.writeInt(mPortIndex);
        dest.writeBoolean(mIsOpportunistic);
        dest.writeString8(mGroupUuid == null ? null : mGroupUuid.toString());
        dest.writeBoolean(mIsGroupDisabled);
        dest.writeInt(mCarrierId);
        dest.writeInt(mProfileClass);
        dest.writeInt(mType);
        dest.writeStringArray(mEhplmns);
        dest.writeStringArray(mHplmns);
        dest.writeString(mGroupOwner);
        dest.writeTypedArray(mCarrierConfigAccessRules, flags);
        dest.writeBoolean(mAreUiccApplicationsEnabled);
        dest.writeInt(mUsageSetting);
        dest.writeBoolean(mIsOnlyNonTerrestrialNetwork);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Get stripped PII information from the id.
     *
     * @param id The raw id (e.g. ICCID, IMSI, etc...).
     * @return The stripped string.
     *
     * @hide
     */
    @Nullable
    public static String getPrintableId(@Nullable String id) {
        String idToPrint = null;
        if (id != null) {
            if (id.length() > 9 && !TelephonyUtils.IS_DEBUGGABLE) {
                idToPrint = id.substring(0, 9) + Rlog.pii(false, id.substring(9));
            } else {
                idToPrint = id;
            }
        }
        return idToPrint;
    }

    @Override
    public String toString() {
        String iccIdToPrint = getPrintableId(mIccId);
        String cardStringToPrint = getPrintableId(mCardString);
        return "[SubscriptionInfo: id=" + mId
                + " iccId=" + iccIdToPrint
                + " simSlotIndex=" + mSimSlotIndex
                + " portIndex=" + mPortIndex
                + " isEmbedded=" + mIsEmbedded
                + " carrierId=" + mCarrierId
                + " displayName=" + mDisplayName
                + " carrierName=" + mCarrierName
                + " isOpportunistic=" + mIsOpportunistic
                + " groupUuid=" + mGroupUuid
                + " groupOwner=" + mGroupOwner
                + " isGroupDisabled=" + mIsGroupDisabled
                + " displayNameSource="
                + SubscriptionManager.displayNameSourceToString(mDisplayNameSource)
                + " iconTint=" + mIconTint
                + " number=" + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, mNumber)
                + " dataRoaming=" + mDataRoaming
                + " mcc=" + mMcc
                + " mnc=" + mMnc
                + " ehplmns=" + Arrays.toString(mEhplmns)
                + " hplmns=" + Arrays.toString(mHplmns)
                + " cardString=" + cardStringToPrint
                + " cardId=" + mCardId
                + " nativeAccessRules=" + Arrays.toString(mNativeAccessRules)
                + " carrierConfigAccessRules=" + Arrays.toString(mCarrierConfigAccessRules)
                + " countryIso=" + mCountryIso
                + " profileClass=" + mProfileClass
                + " mType=" + SubscriptionManager.subscriptionTypeToString(mType)
                + " areUiccApplicationsEnabled=" + mAreUiccApplicationsEnabled
                + " usageSetting=" + SubscriptionManager.usageSettingToString(mUsageSetting)
                + " isOnlyNonTerrestrialNetwork=" + mIsOnlyNonTerrestrialNetwork
                + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubscriptionInfo that = (SubscriptionInfo) o;
        return mId == that.mId && mSimSlotIndex == that.mSimSlotIndex
                && mDisplayNameSource == that.mDisplayNameSource && mIconTint == that.mIconTint
                && mDataRoaming == that.mDataRoaming && mIsEmbedded == that.mIsEmbedded
                && mIsOpportunistic == that.mIsOpportunistic && mCarrierId == that.mCarrierId
                && mProfileClass == that.mProfileClass && mType == that.mType
                && mAreUiccApplicationsEnabled == that.mAreUiccApplicationsEnabled
                && mPortIndex == that.mPortIndex && mUsageSetting == that.mUsageSetting
                && mCardId == that.mCardId && mIsGroupDisabled == that.mIsGroupDisabled
                && mIccId.equals(that.mIccId) && mDisplayName.equals(that.mDisplayName)
                && mCarrierName.equals(that.mCarrierName) && mNumber.equals(that.mNumber)
                && Objects.equals(mMcc, that.mMcc) && Objects.equals(mMnc,
                that.mMnc) && Arrays.equals(mEhplmns, that.mEhplmns)
                && Arrays.equals(mHplmns, that.mHplmns) && mCardString.equals(
                that.mCardString) && Arrays.equals(mNativeAccessRules,
                that.mNativeAccessRules) && Arrays.equals(mCarrierConfigAccessRules,
                that.mCarrierConfigAccessRules) && Objects.equals(mGroupUuid, that.mGroupUuid)
                && mCountryIso.equals(that.mCountryIso) && mGroupOwner.equals(that.mGroupOwner)
                && mIsOnlyNonTerrestrialNetwork == that.mIsOnlyNonTerrestrialNetwork;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mId, mIccId, mSimSlotIndex, mDisplayName, mCarrierName,
                mDisplayNameSource, mIconTint, mNumber, mDataRoaming, mMcc, mMnc, mIsEmbedded,
                mCardString, mIsOpportunistic, mGroupUuid, mCountryIso, mCarrierId, mProfileClass,
                mType, mGroupOwner, mAreUiccApplicationsEnabled, mPortIndex, mUsageSetting, mCardId,
                mIsGroupDisabled, mIsOnlyNonTerrestrialNetwork);
        result = 31 * result + Arrays.hashCode(mEhplmns);
        result = 31 * result + Arrays.hashCode(mHplmns);
        result = 31 * result + Arrays.hashCode(mNativeAccessRules);
        result = 31 * result + Arrays.hashCode(mCarrierConfigAccessRules);
        return result;
    }

    /**
     * The builder class of {@link SubscriptionInfo}.
     *
     * @hide
     */
    public static class Builder {
        /**
         * The subscription id.
         */
        private int mId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        /**
         * The ICCID of the SIM that is associated with this subscription, empty if unknown.
         */
        @NonNull
        private String mIccId = "";

        /**
         * The index of the SIM slot that currently contains the subscription and not necessarily
         * unique and maybe {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if unknown or the
         * subscription is inactive.
         */
        private int mSimSlotIndex = SubscriptionManager.INVALID_SIM_SLOT_INDEX;

        /**
         * The name displayed to the user that identifies this subscription. This name is used
         * in Settings page and can be renamed by the user.
         */
        @NonNull
        private CharSequence mDisplayName = "";

        /**
         * The name displayed to the user that identifies subscription provider name. This name
         * is the SPN displayed in status bar and many other places. Can't be renamed by the user.
         */
        @NonNull
        private CharSequence mCarrierName = "";

        /**
         * The source of the display name.
         */
        @SimDisplayNameSource
        private int mDisplayNameSource = SubscriptionManager.NAME_SOURCE_UNKNOWN;

        /**
         * The color to be used for tinting the icon when displaying to the user.
         */
        private int mIconTint = 0;

        /**
         * The number presented to the user identify this subscription.
         */
        @NonNull
        private String mNumber = "";

        /**
         * Whether user enables data roaming for this subscription or not. Either
         * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or
         * {@link SubscriptionManager#DATA_ROAMING_DISABLE}.
         */
        private int mDataRoaming = SubscriptionManager.DATA_ROAMING_DISABLE;

        /**
         * SIM icon bitmap cache.
         */
        @Nullable
        private Bitmap mIconBitmap = null;

        /**
         * The mobile country code.
         */
        @Nullable
        private String mMcc = null;

        /**
         * The mobile network code.
         */
        @Nullable
        private String mMnc = null;

        /**
         * EHPLMNs associated with the subscription.
         */
        @NonNull
        private String[] mEhplmns = new String[0];

        /**
         * HPLMNs associated with the subscription.
         */
        @NonNull
        private String[] mHplmns = new String[0];

        /**
         * The ISO Country code for the subscription's provider.
         */
        @NonNull
        private String mCountryIso = "";

        /**
         * Whether the subscription is from eSIM.
         */
        private boolean mIsEmbedded = false;

        /**
         * The native access rules for this subscription, if it is embedded and defines any. This
         * does not include access rules for non-embedded subscriptions.
         */
        @Nullable
        private UiccAccessRule[] mNativeAccessRules = null;

        /**
         * The card string of the SIM card.
         */
        @NonNull
        private String mCardString = "";

        /**
         * The card ID of the SIM card which contains the subscription.
         */
        private int mCardId = TelephonyManager.UNINITIALIZED_CARD_ID;

        /**
         * Whether the subscription is opportunistic or not.
         */
        private boolean mIsOpportunistic = false;

        /**
         * The group UUID of the subscription group.
         */
        @Nullable
        private ParcelUuid mGroupUuid = null;

        /**
         * Whether group of the subscription is disabled. This is only useful if it's a grouped
         * opportunistic subscription. In this case, if all primary (non-opportunistic)
         * subscriptions in the group are deactivated (unplugged pSIM or deactivated eSIM profile),
         * we should disable this opportunistic subscription.
         */
        private boolean mIsGroupDisabled = false;

        /**
         * The carrier id.
         *
         * @see TelephonyManager#getSimCarrierId()
         */
        private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;

        /**
         * The profile class populated from the profile metadata if present. Otherwise, the profile
         * class defaults to {@link SubscriptionManager#PROFILE_CLASS_UNSET} if there is no profile
         * metadata or the subscription is not on an eUICC ({@link #isEmbedded} returns
         * {@code false}).
         */
        @ProfileClass
        private int mProfileClass = SubscriptionManager.PROFILE_CLASS_UNSET;

        /**
         * The subscription type.
         */
        @SubscriptionType
        private int mType = SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM;

        /**
         * The owner package of group the subscription belongs to.
         */
        @NonNull
        private String mGroupOwner = "";

        /**
         * The carrier certificates for this subscription that are saved in carrier configs.
         * This does not include access rules from the Uicc, whether embedded or non-embedded.
         */
        @Nullable
        private UiccAccessRule[] mCarrierConfigAccessRules = null;

        /**
         * Whether Uicc applications are configured to enable or not.
         */
        private boolean mAreUiccApplicationsEnabled = true;

        /**
         * the port index of the Uicc card.
         */
        private int mPortIndex = TelephonyManager.INVALID_PORT_INDEX;

        /**
         * Subscription's preferred usage setting.
         */
        @UsageSetting
        private int mUsageSetting = SubscriptionManager.USAGE_SETTING_UNKNOWN;

        /**
         * {@code true} if it is a non-terrestrial network subscription, {@code false} otherwise.
         */
        private boolean mIsOnlyNonTerrestrialNetwork = false;

        /**
         * Default constructor.
         */
        public Builder() {
        }

        /**
         * Constructor from {@link SubscriptionInfo}.
         *
         * @param info The subscription info.
         */
        public Builder(@NonNull SubscriptionInfo info) {
            mId = info.mId;
            mIccId = info.mIccId;
            mSimSlotIndex = info.mSimSlotIndex;
            mDisplayName = info.mDisplayName;
            mCarrierName = info.mCarrierName;
            mDisplayNameSource = info.mDisplayNameSource;
            mIconTint = info.mIconTint;
            mNumber = info.mNumber;
            mDataRoaming = info.mDataRoaming;
            mIconBitmap = info.mIconBitmap;
            mMcc = info.mMcc;
            mMnc = info.mMnc;
            mEhplmns = info.mEhplmns;
            mHplmns = info.mHplmns;
            mCountryIso = info.mCountryIso;
            mIsEmbedded = info.mIsEmbedded;
            mNativeAccessRules = info.mNativeAccessRules;
            mCardString = info.mCardString;
            mCardId = info.mCardId;
            mIsOpportunistic = info.mIsOpportunistic;
            mGroupUuid = info.mGroupUuid;
            mIsGroupDisabled = info.mIsGroupDisabled;
            mCarrierId = info.mCarrierId;
            mProfileClass = info.mProfileClass;
            mType = info.mType;
            mGroupOwner = info.mGroupOwner;
            mCarrierConfigAccessRules = info.mCarrierConfigAccessRules;
            mAreUiccApplicationsEnabled = info.mAreUiccApplicationsEnabled;
            mPortIndex = info.mPortIndex;
            mUsageSetting = info.mUsageSetting;
            mIsOnlyNonTerrestrialNetwork = info.mIsOnlyNonTerrestrialNetwork;
        }

        /**
         * Set the subscription id.
         *
         * @param id The subscription id.
         * @return The builder.
         */
        @NonNull
        public Builder setId(int id) {
            mId = id;
            return this;
        }

        /**
         * Set the ICCID of the SIM that is associated with this subscription.
         *
         * @param iccId The ICCID of the SIM that is associated with this subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setIccId(@Nullable String iccId) {
            mIccId = TextUtils.emptyIfNull(iccId);
            return this;
        }

        /**
         * Set the SIM index of the slot that currently contains the subscription. Set to
         * {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if the subscription is inactive.
         *
         * @param simSlotIndex The SIM slot index.
         * @return The builder.
         */
        @NonNull
        public Builder setSimSlotIndex(int simSlotIndex) {
            mSimSlotIndex = simSlotIndex;
            return this;
        }

        /**
         * The name displayed to the user that identifies this subscription. This name is used
         * in Settings page and can be renamed by the user.
         *
         * @param displayName The display name.
         * @return The builder.
         */
        @NonNull
        public Builder setDisplayName(@Nullable CharSequence displayName) {
            mDisplayName = displayName == null ? "" : displayName;
            return this;
        }

        /**
         * The name displayed to the user that identifies subscription provider name. This name
         * is the SPN displayed in status bar and many other places. Can't be renamed by the user.
         *
         * @param carrierName The carrier name.
         * @return The builder.
         */
        @NonNull
        public Builder setCarrierName(@Nullable CharSequence carrierName) {
            mCarrierName = carrierName == null ? "" : carrierName;
            return this;
        }

        /**
         * Set the source of the display name.
         *
         * @param displayNameSource The source of the display name.
         * @return The builder.
         *
         * @see SubscriptionInfo#getDisplayName()
         */
        @NonNull
        public Builder setDisplayNameSource(@SimDisplayNameSource int displayNameSource) {
            mDisplayNameSource = displayNameSource;
            return this;
        }

        /**
         * Set the color to be used for tinting the icon when displaying to the user.
         *
         * @param iconTint The color to be used for tinting the icon when displaying to the user.
         * @return The builder.
         */
        @NonNull
        public Builder setIconTint(int iconTint) {
            mIconTint = iconTint;
            return this;
        }

        /**
         * Set the number presented to the user identify this subscription.
         *
         * @param number the number presented to the user identify this subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setNumber(@Nullable String number) {
            mNumber = TextUtils.emptyIfNull(number);
            return this;
        }

        /**
         * Set whether user enables data roaming for this subscription or not.
         *
         * @param dataRoaming Data roaming mode. Either
         * {@link SubscriptionManager#DATA_ROAMING_ENABLE} or
         * {@link SubscriptionManager#DATA_ROAMING_DISABLE}
         * @return The builder.
         */
        @NonNull
        public Builder setDataRoaming(int dataRoaming) {
            mDataRoaming = dataRoaming;
            return this;
        }

        /**
         * Set SIM icon bitmap cache.
         *
         * @param iconBitmap SIM icon bitmap cache.
         * @return The builder.
         */
        @NonNull
        public Builder setIcon(@Nullable Bitmap iconBitmap) {
            mIconBitmap = iconBitmap;
            return this;
        }

        /**
         * Set the mobile country code.
         *
         * @param mcc The mobile country code.
         * @return The builder.
         */
        @NonNull
        public Builder setMcc(@Nullable String mcc) {
            mMcc = mcc;
            return this;
        }

        /**
         * Set the mobile network code.
         *
         * @param mnc Mobile network code.
         * @return The builder.
         */
        @NonNull
        public Builder setMnc(@Nullable String mnc) {
            mMnc = mnc;
            return this;
        }

        /**
         * Set EHPLMNs associated with the subscription.
         *
         * @param ehplmns EHPLMNs associated with the subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setEhplmns(@Nullable String[] ehplmns) {
            mEhplmns = ehplmns == null ? new String[0] : ehplmns;
            return this;
        }

        /**
         * Set HPLMNs associated with the subscription.
         *
         * @param hplmns HPLMNs associated with the subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setHplmns(@Nullable String[] hplmns) {
            mHplmns = hplmns == null ? new String[0] : hplmns;
            return this;
        }

        /**
         * Set the ISO country code for the subscription's provider.
         *
         * @param countryIso The ISO country code for the subscription's provider.
         * @return The builder.
         */
        @NonNull
        public Builder setCountryIso(@Nullable String countryIso) {
            mCountryIso = TextUtils.emptyIfNull(countryIso);
            return this;
        }

        /**
         * Set whether the subscription is from eSIM or not.
         *
         * @param isEmbedded {@code true} if the subscription is from eSIM.
         * @return The builder.
         */
        @NonNull
        public Builder setEmbedded(boolean isEmbedded) {
            mIsEmbedded = isEmbedded;
            return this;
        }

        /**
         * Set the native access rules for this subscription, if it is embedded and defines any.
         * This does not include access rules for non-embedded subscriptions.
         *
         * @param nativeAccessRules The native access rules for this subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setNativeAccessRules(@Nullable UiccAccessRule[] nativeAccessRules) {
            mNativeAccessRules = nativeAccessRules;
            return this;
        }

        /**
         * Set the card string of the SIM card.
         *
         * @param cardString The card string of the SIM card.
         * @return The builder.
         *
         * @see #getCardString()
         */
        @NonNull
        public Builder setCardString(@Nullable String cardString) {
            mCardString = TextUtils.emptyIfNull(cardString);
            return this;
        }

        /**
         * Set the card ID of the SIM card which contains the subscription.
         *
         * @param cardId The card ID of the SIM card which contains the subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setCardId(int cardId) {
            mCardId = cardId;
            return this;
        }

        /**
         * Set whether the subscription is opportunistic or not.
         *
         * @param isOpportunistic {@code true} if the subscription is opportunistic.
         * @return The builder.
         */
        @NonNull
        public Builder setOpportunistic(boolean isOpportunistic) {
            mIsOpportunistic = isOpportunistic;
            return this;
        }

        /**
         * Set the group UUID of the subscription group.
         *
         * @param groupUuid The group UUID.
         * @return The builder.
         *
         * @see #getGroupUuid()
         */
        @NonNull
        public Builder setGroupUuid(@Nullable String groupUuid) {
            mGroupUuid = TextUtils.isEmpty(groupUuid) ? null : ParcelUuid.fromString(groupUuid);
            return this;
        }

        /**
         * Whether group of the subscription is disabled. This is only useful if it's a grouped
         * opportunistic subscription. In this case, if all primary (non-opportunistic)
         * subscriptions in the group are deactivated (unplugged pSIM or deactivated eSIM profile),
         * we should disable this opportunistic subscription.
         *
         * @param isGroupDisabled {@code true} if group of the subscription is disabled.
         * @return The builder.
         */
        @NonNull
        public Builder setGroupDisabled(boolean isGroupDisabled) {
            mIsGroupDisabled = isGroupDisabled;
            return this;
        }

        /**
         * Set the subscription carrier id.
         *
         * @param carrierId The carrier id.
         * @return The builder
         *
         * @see TelephonyManager#getSimCarrierId()
         */
        @NonNull
        public Builder setCarrierId(int carrierId) {
            mCarrierId = carrierId;
            return this;
        }

        /**
         * Set the profile class populated from the profile metadata if present.
         *
         * @param profileClass the profile class populated from the profile metadata if present.
         * @return The builder
         *
         * @see #getProfileClass()
         */
        @NonNull
        public Builder setProfileClass(@ProfileClass int profileClass) {
            mProfileClass = profileClass;
            return this;
        }

        /**
         * Set the subscription type.
         *
         * @param type Subscription type.
         * @return The builder.
         */
        @NonNull
        public Builder setType(@SubscriptionType int type) {
            mType = type;
            return this;
        }

        /**
         * Set the owner package of group the subscription belongs to.
         *
         * @param groupOwner Owner package of group the subscription belongs to.
         * @return The builder.
         */
        @NonNull
        public Builder setGroupOwner(@Nullable String groupOwner) {
            mGroupOwner = TextUtils.emptyIfNull(groupOwner);
            return this;
        }

        /**
         * Set the carrier certificates for this subscription that are saved in carrier configs.
         * This does not include access rules from the Uicc, whether embedded or non-embedded.
         *
         * @param carrierConfigAccessRules The carrier certificates for this subscription.
         * @return The builder.
         */
        @NonNull
        public Builder setCarrierConfigAccessRules(
                @Nullable UiccAccessRule[] carrierConfigAccessRules) {
            mCarrierConfigAccessRules = carrierConfigAccessRules;
            return this;
        }

        /**
         * Set whether Uicc applications are configured to enable or not.
         *
         * @param uiccApplicationsEnabled {@code true} if Uicc applications are configured to
         * enable.
         * @return The builder.
         */
        @NonNull
        public Builder setUiccApplicationsEnabled(boolean uiccApplicationsEnabled) {
            mAreUiccApplicationsEnabled = uiccApplicationsEnabled;
            return this;
        }

        /**
         * Set the port index of the Uicc card.
         *
         * @param portIndex The port index of the Uicc card.
         * @return The builder.
         */
        @NonNull
        public Builder setPortIndex(int portIndex) {
            mPortIndex = portIndex;
            return this;
        }

        /**
         * Set subscription's preferred usage setting.
         *
         * @param usageSetting Subscription's preferred usage setting.
         * @return The builder.
         */
        @NonNull
        public Builder setUsageSetting(@UsageSetting int usageSetting) {
            mUsageSetting = usageSetting;
            return this;
        }

        /**
         * Set whether the subscription is exclusively used for non-terrestrial networks or not.
         *
         * @param isOnlyNonTerrestrialNetwork {@code true} if the subscription is for NTN,
         * {@code false} otherwise.
         * @return The builder.
         */
        @NonNull
        public Builder setOnlyNonTerrestrialNetwork(boolean isOnlyNonTerrestrialNetwork) {
            mIsOnlyNonTerrestrialNetwork = isOnlyNonTerrestrialNetwork;
            return this;
        }

        /**
         * Build the {@link SubscriptionInfo}.
         *
         * @return The {@link SubscriptionInfo} instance.
         */
        public SubscriptionInfo build() {
            return new SubscriptionInfo(this);
        }
    }
}
