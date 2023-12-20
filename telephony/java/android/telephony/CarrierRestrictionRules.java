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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.carrier.CarrierIdentifier;
import android.telephony.TelephonyManager.CarrierRestrictionStatus;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Contains the list of carrier restrictions.
 * Allowed list: it indicates the list of carriers that are allowed.
 * Excluded list: it indicates the list of carriers that are excluded.
 * Default carrier restriction: it indicates the default behavior and the priority between the two
 * lists:
 *  - not allowed: the device only allows usage of carriers that are present in the allowed list
 *    and not present in the excluded list. This implies that if a carrier is not present in either
 *    list, it is not allowed.
 *  - allowed: the device allows all carriers, except those present in the excluded list and not
 *    present in the allowed list. This implies that if a carrier is not present in either list,
 *    it is allowed.
 * MultiSim policy: it indicates the behavior in case of devices with two or more SIM cards.
 *  - MULTISIM_POLICY_NONE: the same configuration is applied to all SIM slots independently. This
 *    is the default value if none is set.
 *  - MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT: it indicates that any SIM card can be used
 *    as far as one SIM card matching the configuration is present in the device.
 *
 * Both lists support the character '?' as wild character. For example, an entry indicating
 * MCC=310 and MNC=??? will match all networks with MCC=310.
 *
 * Example 1: Allowed list contains MCC and MNC of operator A. Excluded list contains operator B,
 *            which has same MCC and MNC, but also GID1 value. The priority allowed list is set
 *            to true. Only SIM cards of operator A are allowed, but not those of B or any other
 *            operator.
 * Example 2: Allowed list contains MCC and MNC of operator A. Excluded list contains an entry
 *            with same MCC, and '???' as MNC. The priority allowed list is set to false.
 *            SIM cards of operator A and all SIM cards with a different MCC value are allowed.
 *            SIM cards of operators with same MCC value and different MNC are not allowed.
 * @hide
 */
@SystemApi
public final class CarrierRestrictionRules implements Parcelable {
    /**
     * The device only allows usage of carriers that are present in the allowed list and not
     * present in the excluded list. This implies that if a carrier is not present in either list,
     * it is not allowed.
     */
    public static final int CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED = 0;

    /**
     * The device allows all carriers, except those present in the excluded list and not present
     * in the allowed list. This implies that if a carrier is not present in either list, it is
     * allowed.
     */
    public static final int CARRIER_RESTRICTION_DEFAULT_ALLOWED = 1;

    /** The same configuration is applied to all SIM slots independently. */
    public static final int MULTISIM_POLICY_NONE = 0;

    /**
     * Indicates that any SIM card can be used as far as one valid card is present in the device.
     * For the modem, a SIM card is valid when its content (i.e. MCC, MNC, GID, SPN) matches the
     * carrier restriction configuration.
     */
    public static final int MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT = 1;

    /**
     * Indicates that the SIM lock policy applies uniformly to all sim slots.
     * @hide
     */
    public static final int MULTISIM_POLICY_APPLY_TO_ALL_SLOTS = 2;

    /**
     * The SIM lock configuration applies exclusively to sim slot 1, leaving
     * all other sim slots unlocked irrespective of the SIM card in slot 1
     * @hide
     */
    public static final int MULTISIM_POLICY_APPLY_TO_ONLY_SLOT_1 = 3;

    /**
     * Valid sim cards must be present on sim slot1 in order
     * to use other sim slots.
     * @hide
     */
    public static final int MULTISIM_POLICY_VALID_SIM_MUST_PRESENT_ON_SLOT_1 = 4;

    /**
     * Valid sim card must be present on slot1 and it must be in full service
     * in order to use other sim slots.
     * @hide
     */
    public static final int MULTISIM_POLICY_ACTIVE_SERVICE_ON_SLOT_1_TO_UNBLOCK_OTHER_SLOTS = 5;

    /**
     * Valid sim card be present on any slot and it must be in full service
     * in order to use other sim slots.
     * @hide
     */
    public static final int MULTISIM_POLICY_ACTIVE_SERVICE_ON_ANY_SLOT_TO_UNBLOCK_OTHER_SLOTS = 6;

    /**
     * Valid sim cards must be present on all slots. If any SIM cards become
     * invalid then device would set other SIM cards as invalid as well.
     * @hide
     */
    public static final int MULTISIM_POLICY_ALL_SIMS_MUST_BE_VALID = 7;

    /**
     * In case there is no match policy listed above.
     * @hide
     */
    public static final int MULTISIM_POLICY_SLOT_POLICY_OTHER = 8;



    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MULTISIM_POLICY_",
            value = {MULTISIM_POLICY_NONE,
                    MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT,
                    MULTISIM_POLICY_APPLY_TO_ALL_SLOTS,
                    MULTISIM_POLICY_APPLY_TO_ONLY_SLOT_1,
                    MULTISIM_POLICY_VALID_SIM_MUST_PRESENT_ON_SLOT_1,
                    MULTISIM_POLICY_ACTIVE_SERVICE_ON_SLOT_1_TO_UNBLOCK_OTHER_SLOTS,
                    MULTISIM_POLICY_ACTIVE_SERVICE_ON_ANY_SLOT_TO_UNBLOCK_OTHER_SLOTS,
                    MULTISIM_POLICY_ALL_SIMS_MUST_BE_VALID,
                    MULTISIM_POLICY_SLOT_POLICY_OTHER
            })
    public @interface MultiSimPolicy {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CARRIER_RESTRICTION_DEFAULT_",
            value = {CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED, CARRIER_RESTRICTION_DEFAULT_ALLOWED})
    public @interface CarrierRestrictionDefault {}

    /* Wild character for comparison */
    private static final char WILD_CHARACTER = '?';

    private List<CarrierIdentifier> mAllowedCarriers;
    private List<CarrierIdentifier> mExcludedCarriers;
    private List<CarrierInfo> mAllowedCarrierInfo;
    private List<CarrierInfo> mExcludedCarrierInfo;
    @CarrierRestrictionDefault
    private int mCarrierRestrictionDefault;
    @MultiSimPolicy
    private int mMultiSimPolicy;
    @CarrierRestrictionStatus
    private int mCarrierRestrictionStatus;

    private CarrierRestrictionRules() {
        mAllowedCarriers = new ArrayList<CarrierIdentifier>();
        mExcludedCarriers = new ArrayList<CarrierIdentifier>();
        mAllowedCarrierInfo = new ArrayList<CarrierInfo>();
        mExcludedCarrierInfo = new ArrayList<CarrierInfo>();
        mCarrierRestrictionDefault = CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED;
        mMultiSimPolicy = MULTISIM_POLICY_NONE;
        mCarrierRestrictionStatus = TelephonyManager.CARRIER_RESTRICTION_STATUS_UNKNOWN;
    }

    private CarrierRestrictionRules(Parcel in) {
        mAllowedCarriers = new ArrayList<CarrierIdentifier>();
        mExcludedCarriers = new ArrayList<CarrierIdentifier>();
        mAllowedCarrierInfo = new ArrayList<CarrierInfo>();
        mExcludedCarrierInfo = new ArrayList<CarrierInfo>();
        in.readTypedList(mAllowedCarriers, CarrierIdentifier.CREATOR);
        in.readTypedList(mExcludedCarriers, CarrierIdentifier.CREATOR);
        mCarrierRestrictionDefault = in.readInt();
        mMultiSimPolicy = in.readInt();
        mCarrierRestrictionStatus = in.readInt();
        if (Flags.carrierRestrictionRulesEnhancement()) {
            in.readTypedList(mAllowedCarrierInfo, CarrierInfo.CREATOR);
            in.readTypedList(mExcludedCarrierInfo, CarrierInfo.CREATOR);
        }
    }

    /**
     * Creates a new builder for this class
     * @hide
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Indicates if all carriers are allowed
     */
    public boolean isAllCarriersAllowed() {
        return (mAllowedCarriers.isEmpty() && mExcludedCarriers.isEmpty()
                && mCarrierRestrictionDefault == CARRIER_RESTRICTION_DEFAULT_ALLOWED);
    }

    /**
     * Retrieves list of allowed carriers
     *
     * @return the list of allowed carriers
     */
    public @NonNull List<CarrierIdentifier> getAllowedCarriers() {
        return mAllowedCarriers;
    }

    /**
     * Retrieves list of excluded carriers
     *
     * @return the list of excluded carriers
     */
    public @NonNull List<CarrierIdentifier> getExcludedCarriers() {
        return mExcludedCarriers;
    }

    /**
     * Retrieves list of excluded carrierInfos
     *
     * @return the list of excluded carrierInfos
     * @hide
     */
    public @NonNull List<CarrierInfo> getExcludedCarriersInfoList() {
        return mExcludedCarrierInfo;
    }

    /**
     * Retrieves list of excluded carrierInfos
     *
     * @return the list of excluded carrierInfos
     * @hide
     */
    public @NonNull List<CarrierInfo> getAllowedCarriersInfoList() {
        return mAllowedCarrierInfo;
    }
    /**
     * Retrieves the default behavior of carrier restrictions
     */
    public @CarrierRestrictionDefault int getDefaultCarrierRestriction() {
        return mCarrierRestrictionDefault;
    }

    /**
     * @return The policy used for multi-SIM devices
     */
    public @MultiSimPolicy int getMultiSimPolicy() {
        return mMultiSimPolicy;
    }

    /**
     * Tests an array of carriers with the carrier restriction configuration. The list of carrier
     * ids passed as argument does not need to be the same as currently present in the device.
     *
     * @param carrierIds list of {@link CarrierIdentifier}, one for each SIM slot on the device
     * @return a list of boolean with the same size as input, indicating if each
     * {@link CarrierIdentifier} is allowed or not.
     */
    public @NonNull List<Boolean> areCarrierIdentifiersAllowed(
            @NonNull List<CarrierIdentifier> carrierIds) {
        ArrayList<Boolean> result = new ArrayList<>(carrierIds.size());

        // First calculate the result for each slot independently
        for (int i = 0; i < carrierIds.size(); i++) {
            boolean inAllowedList = isCarrierIdInList(carrierIds.get(i), mAllowedCarriers);
            boolean inExcludedList = isCarrierIdInList(carrierIds.get(i), mExcludedCarriers);
            if (mCarrierRestrictionDefault == CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED) {
                result.add((inAllowedList && !inExcludedList) ? true : false);
            } else {
                result.add((inExcludedList && !inAllowedList) ? false : true);
            }
        }
        // Apply the multi-slot policy, if needed.
        if (mMultiSimPolicy == MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT) {
            for (boolean b : result) {
                if (b) {
                    result.replaceAll(x -> true);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Indicates if a certain carrier {@code id} is present inside a {@code list}
     *
     * @return true if the carrier {@code id} is present, false otherwise
     */
    private static boolean isCarrierIdInList(CarrierIdentifier id, List<CarrierIdentifier> list) {
        for (CarrierIdentifier listItem : list) {
            // Compare MCC and MNC
            if (!patternMatch(id.getMcc(), listItem.getMcc())
                    || !patternMatch(id.getMnc(), listItem.getMnc())) {
                continue;
            }

            // Compare SPN. Comparison is on the complete strings, case insensitive and with wild
            // characters.
            String listItemValue = convertNullToEmpty(listItem.getSpn());
            String idValue = convertNullToEmpty(id.getSpn());
            if (!listItemValue.isEmpty()) {
                if (!patternMatch(idValue, listItemValue)) {
                    continue;
                }
            }

            // The IMSI of the configuration can be shorter than actual IMSI in the SIM card.
            listItemValue = convertNullToEmpty(listItem.getImsi());
            idValue = convertNullToEmpty(id.getImsi());
            if (!patternMatch(
                    idValue.substring(0, Math.min(idValue.length(), listItemValue.length())),
                    listItemValue)) {
                continue;
            }

            // The GID1 of the configuration can be shorter than actual GID1 in the SIM card.
            listItemValue = convertNullToEmpty(listItem.getGid1());
            idValue = convertNullToEmpty(id.getGid1());
            if (!patternMatch(
                    idValue.substring(0, Math.min(idValue.length(), listItemValue.length())),
                    listItemValue)) {
                continue;
            }

            // The GID2 of the configuration can be shorter than actual GID2 in the SIM card.
            listItemValue = convertNullToEmpty(listItem.getGid2());
            idValue = convertNullToEmpty(id.getGid2());
            if (!patternMatch(
                    idValue.substring(0, Math.min(idValue.length(), listItemValue.length())),
                    listItemValue)) {
                continue;
            }

            // Valid match was found in the list
            return true;
        }
        return false;
    }

    private static String convertNullToEmpty(String value) {
        return Objects.toString(value, "");
    }

    /**
     * Performs a case insensitive string comparison against a given pattern. The character '?'
     * is used in the pattern as wild character in the comparison. The string must have the same
     * length as the pattern.
     *
     * @param str string to match
     * @param pattern string containing the pattern
     * @return true in case of match, false otherwise
     */
    private static boolean patternMatch(String str, String pattern) {
        if (str.length() != pattern.length()) {
            return false;
        }
        String lowerCaseStr = str.toLowerCase(Locale.ROOT);
        String lowerCasePattern = pattern.toLowerCase(Locale.ROOT);

        for (int i = 0; i < lowerCasePattern.length(); i++) {
            if (lowerCasePattern.charAt(i) != lowerCaseStr.charAt(i)
                    && lowerCasePattern.charAt(i) != WILD_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the carrier restriction status of the device.
     * The return value of the API is as follows.
     * <ul>
     *      <li>return {@link TelephonyManager#CARRIER_RESTRICTION_STATUS_RESTRICTED_TO_CALLER}
     *      if the caller and the device locked by the network are same</li>
     *      <li>return {@link TelephonyManager#CARRIER_RESTRICTION_STATUS_RESTRICTED} if the
     *      caller and the device locked by the network are different</li>
     *      <li>return {@link TelephonyManager#CARRIER_RESTRICTION_STATUS_NOT_RESTRICTED} if the
     *      device is not locked</li>
     *      <li>return {@link TelephonyManager#CARRIER_RESTRICTION_STATUS_UNKNOWN} if the device
     *      locking state is unavailable or radio does not supports the feature</li>
     * </ul>
     */
    @FlaggedApi(Flags.FLAG_CARRIER_RESTRICTION_STATUS)
    public @CarrierRestrictionStatus int getCarrierRestrictionStatus() {
        return mCarrierRestrictionStatus;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(mAllowedCarriers);
        out.writeTypedList(mExcludedCarriers);
        out.writeInt(mCarrierRestrictionDefault);
        out.writeInt(mMultiSimPolicy);
        out.writeInt(mCarrierRestrictionStatus);
        if (Flags.carrierRestrictionRulesEnhancement()) {
            out.writeTypedList(mAllowedCarrierInfo);
            out.writeTypedList(mExcludedCarrierInfo);
        }
    }

    /**
     * {@link Parcelable#describeContents}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     */
    public static final @android.annotation.NonNull Creator<CarrierRestrictionRules> CREATOR =
            new Creator<CarrierRestrictionRules>() {
        @Override
        public CarrierRestrictionRules createFromParcel(Parcel in) {
            return new CarrierRestrictionRules(in);
        }

        @Override
        public CarrierRestrictionRules[] newArray(int size) {
            return new CarrierRestrictionRules[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "CarrierRestrictionRules(allowed:" + mAllowedCarriers + ", excluded:"
                + mExcludedCarriers + ", default:" + mCarrierRestrictionDefault
                + ", multisim policy:" + mMultiSimPolicy + getCarrierInfoList() + ")";
    }

    private String getCarrierInfoList() {
        if (Flags.carrierRestrictionRulesEnhancement()) {
            return ",  allowedCarrierInfoList:" + mAllowedCarrierInfo
                    + ", excludedCarrierInfoList:" + mExcludedCarrierInfo;
        } else {
            return "";
        }
    }

    /**
     * Builder for a {@link CarrierRestrictionRules}.
     */
    public static final class Builder {
        private final CarrierRestrictionRules mRules;

        public Builder() {
            mRules = new CarrierRestrictionRules();
        }

        /** build command */
        public @NonNull CarrierRestrictionRules build() {
            return mRules;
        }

        /**
         * Indicate that all carriers are allowed.
         */
        public @NonNull Builder setAllCarriersAllowed() {
            mRules.mAllowedCarriers.clear();
            mRules.mExcludedCarriers.clear();
            mRules.mCarrierRestrictionDefault = CARRIER_RESTRICTION_DEFAULT_ALLOWED;
            if (Flags.carrierRestrictionRulesEnhancement()) {
                mRules.mCarrierRestrictionStatus =
                        TelephonyManager.CARRIER_RESTRICTION_STATUS_NOT_RESTRICTED;
                mRules.mAllowedCarrierInfo.clear();
                mRules.mExcludedCarrierInfo.clear();
            }
            return this;
        }

        /**
         * Set list of allowed carriers.
         *
         * @param allowedCarriers list of allowed carriers
         */
        public @NonNull Builder setAllowedCarriers(
                @NonNull List<CarrierIdentifier> allowedCarriers) {
            mRules.mAllowedCarriers = new ArrayList<CarrierIdentifier>(allowedCarriers);
            return this;
        }

        /**
         * Set list of excluded carriers.
         *
         * @param excludedCarriers list of excluded carriers
         */
        public @NonNull Builder setExcludedCarriers(
                @NonNull List<CarrierIdentifier> excludedCarriers) {
            mRules.mExcludedCarriers = new ArrayList<CarrierIdentifier>(excludedCarriers);
            return this;
        }

        /**
         * Set the default behavior of the carrier restrictions
         *
         * @param carrierRestrictionDefault prioritized carrier list
         */
        public @NonNull Builder setDefaultCarrierRestriction(
                @CarrierRestrictionDefault int carrierRestrictionDefault) {
            mRules.mCarrierRestrictionDefault = carrierRestrictionDefault;
            return this;
        }

        /**
         * Set the policy to be used for multi-SIM devices
         *
         * @param multiSimPolicy multi SIM policy
         */
        public @NonNull Builder setMultiSimPolicy(@MultiSimPolicy int multiSimPolicy) {
            mRules.mMultiSimPolicy = multiSimPolicy;
            return this;
        }

        /**
         * Set the device's carrier restriction status
         *
         * @param carrierRestrictionStatus device restriction status
         * @hide
         */
        public @NonNull
        Builder setCarrierRestrictionStatus(int carrierRestrictionStatus) {
            mRules.mCarrierRestrictionStatus = carrierRestrictionStatus;
            return this;
        }

        /**
         * Set list of allowed carrierInfo
         *
         * @param allowedCarrierInfo list of allowed CarrierInfo
         * @hide
         */
        public @NonNull Builder setAllowedCarrierInfo(
                @NonNull List<CarrierInfo> allowedCarrierInfo) {
            mRules.mAllowedCarrierInfo = new ArrayList<CarrierInfo>(allowedCarrierInfo);
            return this;
        }

        /**
         * Set list of allowed carrierInfo
         *
         * @param excludedCarrierInfo list of allowed CarrierInfo
         * @hide
         */
        public @NonNull Builder setExcludedCarrierInfo(
                @NonNull List<CarrierInfo> excludedCarrierInfo) {
            mRules.mExcludedCarrierInfo = new ArrayList<CarrierInfo>(excludedCarrierInfo);
            return this;
        }
    }
}
