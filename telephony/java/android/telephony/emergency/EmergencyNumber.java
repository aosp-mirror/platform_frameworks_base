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

package android.telephony.emergency;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.hardware.radio.V1_4.EmergencyNumberSource;
import android.hardware.radio.V1_4.EmergencyServiceCategory;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A parcelable class that wraps and retrieves the information of number, service category(s) and
 * country code for a specific emergency number.
 */
public final class EmergencyNumber implements Parcelable, Comparable<EmergencyNumber> {

    private static final String LOG_TAG = "EmergencyNumber";

    /**
     * Defining Emergency Service Category as follows:
     *  - General emergency call, all categories;
     *  - Police;
     *  - Ambulance;
     *  - Fire Brigade;
     *  - Marine Guard;
     *  - Mountain Rescue;
     *  - Manually Initiated eCall (MIeC);
     *  - Automatically Initiated eCall (AIeC);
     *
     * Category UNSPECIFIED (General emergency call, all categories) indicates that no specific
     * services are associated with this emergency number; if the emergency number is specified,
     * it has one or more defined emergency service categories.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "EMERGENCY_SERVICE_CATEGORY_" }, value = {
            EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
            EMERGENCY_SERVICE_CATEGORY_POLICE,
            EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
            EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE,
            EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD,
            EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE,
            EMERGENCY_SERVICE_CATEGORY_MIEC,
            EMERGENCY_SERVICE_CATEGORY_AIEC
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EmergencyServiceCategories {}

    /**
     * Emergency Service Category UNSPECIFIED (General emergency call, all categories) bit-field
     * indicates that no specific services are associated with this emergency number; if the
     * emergency number is specified, it has one or more defined emergency service categories.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED =
            EmergencyServiceCategory.UNSPECIFIED;
    /**
     * Bit-field that indicates Emergency Service Category for Police.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_POLICE = EmergencyServiceCategory.POLICE;
    /**
     * Bit-field that indicates Emergency Service Category for Ambulance.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_AMBULANCE =
            EmergencyServiceCategory.AMBULANCE;
    /**
     * Bit-field that indicates Emergency Service Category for Fire Brigade.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE =
            EmergencyServiceCategory.FIRE_BRIGADE;
    /**
     * Bit-field that indicates Emergency Service Category for Marine Guard.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD =
            EmergencyServiceCategory.MARINE_GUARD;
    /**
     * Bit-field that indicates Emergency Service Category for Mountain Rescue.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE =
            EmergencyServiceCategory.MOUNTAIN_RESCUE;
    /**
     * Bit-field that indicates Emergency Service Category for Manually Initiated eCall (MIeC)
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_MIEC = EmergencyServiceCategory.MIEC;
    /**
     * Bit-field that indicates Emergency Service Category for Automatically Initiated eCall (AIeC)
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_SERVICE_CATEGORY_AIEC = EmergencyServiceCategory.AIEC;

    private static final Set<Integer> EMERGENCY_SERVICE_CATEGORY_SET;
    static {
        EMERGENCY_SERVICE_CATEGORY_SET = new HashSet<Integer>();
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_POLICE);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_AMBULANCE);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_MIEC);
        EMERGENCY_SERVICE_CATEGORY_SET.add(EMERGENCY_SERVICE_CATEGORY_AIEC);
    }

    /**
     * The source to tell where the corresponding @1.4::EmergencyNumber comes from.
     *
     * The emergency number has one or more defined emergency number sources.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "EMERGENCY_NUMBER_SOURCE_" }, value = {
            EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
            EMERGENCY_NUMBER_SOURCE_SIM,
            EMERGENCY_NUMBER_SOURCE_DATABASE,
            EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG,
            EMERGENCY_NUMBER_SOURCE_DEFAULT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EmergencyNumberSources {}

    /**
     * Bit-field which indicates the number is from the network signaling.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING =
            EmergencyNumberSource.NETWORK_SIGNALING;
    /**
     * Bit-field which indicates the number is from the sim.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_NUMBER_SOURCE_SIM = EmergencyNumberSource.SIM;
    /**
     * Bit-field which indicates the number is from the platform-maintained database.
     */
    public static final int EMERGENCY_NUMBER_SOURCE_DATABASE =  1 << 4;
    /**
     * Bit-field which indicates the number is from test mode.
     *
     * @hide
     */
    public static final int EMERGENCY_NUMBER_SOURCE_TEST =  1 << 5;
    /** Bit-field which indicates the number is from the modem config. */
    public static final int EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG =
            EmergencyNumberSource.MODEM_CONFIG;
    /**
     * Bit-field which indicates the number is available as default.
     *
     * 112, 911 must always be available; additionally, 000, 08, 110, 999, 118 and 119 must be
     * available when sim is not present.
     *
     * Reference: 3gpp 22.101, Section 10 - Emergency Calls
     */
    public static final int EMERGENCY_NUMBER_SOURCE_DEFAULT = EmergencyNumberSource.DEFAULT;

    private static final Set<Integer> EMERGENCY_NUMBER_SOURCE_SET;
    static {
        EMERGENCY_NUMBER_SOURCE_SET = new HashSet<Integer>();
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING);
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_SIM);
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_DATABASE);
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG);
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_DEFAULT);
    }

    /**
     * Indicated the framework does not know whether an emergency call should be placed using
     * emergency or normal call routing. This means the underlying radio or IMS implementation is
     * free to determine for itself how to route the call.
     */
    public static final int EMERGENCY_CALL_ROUTING_UNKNOWN = 0;
    /**
     * Indicates the radio or IMS implementation must handle the call through emergency routing.
     */
    public static final int EMERGENCY_CALL_ROUTING_EMERGENCY = 1;
    /**
     * Indicates the radio or IMS implementation must handle the call through normal call routing.
     */
    public static final int EMERGENCY_CALL_ROUTING_NORMAL = 2;

    /**
     * The routing to tell how to handle the call for the corresponding emergency number.
     *
     * @hide
     */
    @IntDef(flag = false, prefix = { "EMERGENCY_CALL_ROUTING_" }, value = {
            EMERGENCY_CALL_ROUTING_UNKNOWN,
            EMERGENCY_CALL_ROUTING_EMERGENCY,
            EMERGENCY_CALL_ROUTING_NORMAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EmergencyCallRouting {}


    private final String mNumber;
    private final String mCountryIso;
    private final String mMnc;
    private final int mEmergencyServiceCategoryBitmask;
    private final List<String> mEmergencyUrns;
    private final int mEmergencyNumberSourceBitmask;
    private final int mEmergencyCallRouting;

    /** @hide */
    public EmergencyNumber(@NonNull String number, @NonNull String countryIso, @NonNull String mnc,
                           @EmergencyServiceCategories int emergencyServiceCategories,
                           @NonNull List<String> emergencyUrns,
                           @EmergencyNumberSources int emergencyNumberSources,
                           @EmergencyCallRouting int emergencyCallRouting) {
        this.mNumber = number;
        this.mCountryIso = countryIso;
        this.mMnc = mnc;
        this.mEmergencyServiceCategoryBitmask = emergencyServiceCategories;
        this.mEmergencyUrns = emergencyUrns;
        this.mEmergencyNumberSourceBitmask = emergencyNumberSources;
        this.mEmergencyCallRouting = emergencyCallRouting;
    }

    /** @hide */
    public EmergencyNumber(Parcel source) {
        mNumber = source.readString();
        mCountryIso = source.readString();
        mMnc = source.readString();
        mEmergencyServiceCategoryBitmask = source.readInt();
        mEmergencyUrns = source.createStringArrayList();
        mEmergencyNumberSourceBitmask = source.readInt();
        mEmergencyCallRouting = source.readInt();
    }

    @Override
    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mNumber);
        dest.writeString(mCountryIso);
        dest.writeString(mMnc);
        dest.writeInt(mEmergencyServiceCategoryBitmask);
        dest.writeStringList(mEmergencyUrns);
        dest.writeInt(mEmergencyNumberSourceBitmask);
        dest.writeInt(mEmergencyCallRouting);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<EmergencyNumber> CREATOR =
            new Parcelable.Creator<EmergencyNumber>() {
                @Override
                public EmergencyNumber createFromParcel(Parcel in) {
                    return new EmergencyNumber(in);
                }

                @Override
                public EmergencyNumber[] newArray(int size) {
                    return new EmergencyNumber[size];
                }
            };

    /**
     * Get the dialing number of the emergency number.
     *
     * The character in the number string is only the dial pad
     * character('0'-'9', '*', or '#'). For example: 911.
     *
     * @return the dialing number.
     */
    public @NonNull String getNumber() {
        return mNumber;
    }

    /**
     * Get the country code string (lowercase character) in ISO 3166 format of the emergency number.
     *
     * @return the country code string (lowercase character) in ISO 3166 format.
     */
    public @NonNull String getCountryIso() {
        return mCountryIso;
    }

    /**
     * Get the Mobile Network Code of the emergency number.
     *
     * @return the Mobile Network Code of the emergency number.
     */
    public @NonNull String getMnc() {
        return mMnc;
    }

    /**
     * Returns the bitmask of emergency service categories of the emergency number.
     *
     * @return bitmask of the emergency service categories
     *
     * @hide
     */
    public @EmergencyServiceCategories int getEmergencyServiceCategoryBitmask() {
        return mEmergencyServiceCategoryBitmask;
    }

    /**
     * Returns the bitmask of emergency service categories of the emergency number for
     * internal dialing.
     *
     * @return bitmask of the emergency service categories
     *
     * @hide
     */
    public @EmergencyServiceCategories int getEmergencyServiceCategoryBitmaskInternalDial() {
        if (mEmergencyNumberSourceBitmask == EMERGENCY_NUMBER_SOURCE_DATABASE) {
            return EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
        }
        return mEmergencyServiceCategoryBitmask;
    }

    /**
     * Returns the emergency service categories of the emergency number.
     *
     * Note: if the emergency number is in {@link #EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED}, only
     * {@link #EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED} is returned and it means the number is in
     * all categories.
     *
     * @return a list of the emergency service categories
     */
    public @NonNull List<Integer> getEmergencyServiceCategories() {
        List<Integer> categories = new ArrayList<>();
        if (serviceUnspecified()) {
            categories.add(EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED);
            return categories;
        }
        for (Integer category : EMERGENCY_SERVICE_CATEGORY_SET) {
            if (isInEmergencyServiceCategories(category)) {
                categories.add(category);
            }
        }
        return categories;
    }

    /**
     * Returns the list of emergency Uniform Resources Names (URN) of the emergency number.
     *
     * For example, {@code urn:service:sos} is the generic URN for contacting emergency services
     * of all type.
     *
     * Reference: 3gpp 24.503, Section 5.1.6.8.1 - General;
     *            RFC 5031
     *
     * @return list of emergency Uniform Resources Names (URN) or an empty list if the emergency
     *         number does not have a specified emergency Uniform Resource Name.
     */
    public @NonNull List<String> getEmergencyUrns() {
        return Collections.unmodifiableList(mEmergencyUrns);
    }

    /**
     * Checks if the emergency service category is unspecified for the emergency number
     * {@link #EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED}.
     *
     * @return {@code true} if the emergency service category is unspecified for the emergency
     * number {@link #EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED}; {@code false} otherwise.
     */
    private boolean serviceUnspecified() {
        return mEmergencyServiceCategoryBitmask == EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
    }

    /**
     * Checks if the emergency number is in the supplied emergency service category(s).
     *
     * @param categories - the supplied emergency service categories
     *
     * @return {@code true} if the emergency number is in the specified emergency service
     * category(s) or if its emergency service category is
     * {@link #EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED}; {@code false} otherwise.
     */
    public boolean isInEmergencyServiceCategories(@EmergencyServiceCategories int categories) {
        if (categories == EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED) {
            return serviceUnspecified();
        }
        if (serviceUnspecified()) {
            return true;
        }
        return (mEmergencyServiceCategoryBitmask & categories) == categories;
    }

    /**
     * Returns the bitmask of the sources of the emergency number.
     *
     * @return bitmask of the emergency number sources
     *
     * @hide
     */
    public @EmergencyNumberSources int getEmergencyNumberSourceBitmask() {
        return mEmergencyNumberSourceBitmask;
    }

    /**
     * Returns a list of sources of the emergency number.
     *
     * @return a list of emergency number sources
     */
    public @NonNull List<Integer> getEmergencyNumberSources() {
        List<Integer> sources = new ArrayList<>();
        for (Integer source : EMERGENCY_NUMBER_SOURCE_SET) {
            if ((mEmergencyNumberSourceBitmask & source) == source) {
                sources.add(source);
            }
        }
        return sources;
    }

    /**
     * Checks if the emergency number is from the specified emergency number source(s).
     *
     * @return {@code true} if the emergency number is from the specified emergency number
     * source(s); {@code false} otherwise.
     *
     * @param sources - the supplied emergency number sources
     */
    public boolean isFromSources(@EmergencyNumberSources int sources) {
        return (mEmergencyNumberSourceBitmask & sources) == sources;
    }

    /**
     * Returns the emergency call routing information.
     *
     * <p>Some regions require some emergency numbers which are not routed using typical emergency
     * call processing, but are instead placed as regular phone calls. The emergency call routing
     * field provides information about how an emergency call will be routed when it is placed.
     *
     * @return the emergency call routing requirement
     */
    public @EmergencyCallRouting int getEmergencyCallRouting() {
        return mEmergencyCallRouting;
    }

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "EmergencyNumber:" + "Number-" + mNumber + "|CountryIso-" + mCountryIso
                + "|Mnc-" + mMnc
                + "|ServiceCategories-" + Integer.toBinaryString(mEmergencyServiceCategoryBitmask)
                + "|Urns-" + mEmergencyUrns
                + "|Sources-" + Integer.toBinaryString(mEmergencyNumberSourceBitmask)
                + "|Routing-" + Integer.toBinaryString(mEmergencyCallRouting);
    }

    @Override
    public boolean equals(Object o) {
        if (!EmergencyNumber.class.isInstance(o)) {
            return false;
        }
        EmergencyNumber other = (EmergencyNumber) o;
        return mNumber.equals(other.mNumber)
                && mCountryIso.equals(other.mCountryIso)
                && mMnc.equals(other.mMnc)
                && mEmergencyServiceCategoryBitmask == other.mEmergencyServiceCategoryBitmask
                && mEmergencyUrns.equals(other.mEmergencyUrns)
                && mEmergencyNumberSourceBitmask == other.mEmergencyNumberSourceBitmask
                && mEmergencyCallRouting == other.mEmergencyCallRouting;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNumber, mCountryIso, mMnc, mEmergencyServiceCategoryBitmask,
                mEmergencyUrns, mEmergencyNumberSourceBitmask, mEmergencyCallRouting);
    }

    /**
     * Calculate the score for display priority.
     *
     * A higher display priority score means the emergency number has a higher display priority.
     * The score is higher if the source is defined for a higher display priority.
     *
     * The priority of sources are defined as follows:
     *     EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING >
     *     EMERGENCY_NUMBER_SOURCE_SIM >
     *     EMERGENCY_NUMBER_SOURCE_DATABASE >
     *     EMERGENCY_NUMBER_SOURCE_DEFAULT >
     *     EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG
     *
     */
    private int getDisplayPriorityScore() {
        int score = 0;
        if (this.isFromSources(EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING)) {
            score += 1 << 4;
        }
        if (this.isFromSources(EMERGENCY_NUMBER_SOURCE_SIM)) {
            score += 1 << 3;
        }
        if (this.isFromSources(EMERGENCY_NUMBER_SOURCE_DATABASE)) {
            score += 1 << 2;
        }
        if (this.isFromSources(EMERGENCY_NUMBER_SOURCE_DEFAULT)) {
            score += 1 << 1;
        }
        if (this.isFromSources(EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG)) {
            score += 1 << 0;
        }
        return score;
    }

    /**
     * Compare the display priority for this emergency number and the supplied emergency number.
     *
     * @param emergencyNumber the supplied emergency number
     * @return a negative value if the supplied emergency number has a lower display priority;
     *         a positive value if the supplied emergency number has a higher display priority;
     *         0 if both have equal display priority.
     */
    @Override
    public int compareTo(@NonNull EmergencyNumber emergencyNumber) {
        if (this.getDisplayPriorityScore()
                > emergencyNumber.getDisplayPriorityScore()) {
            return -1;
        } else if (this.getDisplayPriorityScore()
                < emergencyNumber.getDisplayPriorityScore()) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * In-place merge same emergency numbers in the emergency number list.
     *
     * A unique EmergencyNumber has a unique combination of ‘number’, ‘mcc’, 'mnc' and
     * 'categories' fields. Multiple Emergency Number Sources should be merged into one bitfield
     * for the same EmergencyNumber.
     *
     * @param emergencyNumberList the emergency number list to process
     *
     * @hide
     */
    public static void mergeSameNumbersInEmergencyNumberList(
            List<EmergencyNumber> emergencyNumberList) {
        if (emergencyNumberList == null) {
            return;
        }
        Set<EmergencyNumber> mergedEmergencyNumber = new HashSet<>();
        for (int i = 0; i < emergencyNumberList.size(); i++) {
            // Skip the check because it was merged.
            if (mergedEmergencyNumber.contains(emergencyNumberList.get(i))) {
                continue;
            }
            for (int j = i + 1; j < emergencyNumberList.size(); j++) {
                if (areSameEmergencyNumbers(
                        emergencyNumberList.get(i), emergencyNumberList.get(j))) {
                    Rlog.e(LOG_TAG, "Found unexpected duplicate numbers: "
                            + emergencyNumberList.get(i) + " vs " + emergencyNumberList.get(j));
                    // Set the merged emergency number in the current position
                    emergencyNumberList.set(i, mergeSameEmergencyNumbers(
                            emergencyNumberList.get(i), emergencyNumberList.get(j)));
                    // Mark the emergency number has been merged
                    mergedEmergencyNumber.add(emergencyNumberList.get(j));
                }
            }
        }
        // Remove the marked emergency number in the orignal list
        for (int i = 0; i < emergencyNumberList.size(); i++) {
            if (mergedEmergencyNumber.contains(emergencyNumberList.get(i))) {
                emergencyNumberList.remove(i--);
            }
        }
        Collections.sort(emergencyNumberList);
    }

    /**
     * Check if two emergency numbers are the same.
     *
     * A unique EmergencyNumber has a unique combination of ‘number’, ‘mcc’, 'mnc' and
     * 'categories', and 'routing' fields. Multiple Emergency Number Sources should be
     * merged into one bitfield for the same EmergencyNumber.
     *
     * @param first first EmergencyNumber to compare
     * @param second second EmergencyNumber to compare
     * @return true if they are the same EmergencyNumbers; false otherwise.
     *
     * @hide
     */
    public static boolean areSameEmergencyNumbers(@NonNull EmergencyNumber first,
                                                  @NonNull EmergencyNumber second) {
        if (!first.getNumber().equals(second.getNumber())) {
            return false;
        }
        if (!first.getCountryIso().equals(second.getCountryIso())) {
            return false;
        }
        if (!first.getMnc().equals(second.getMnc())) {
            return false;
        }
        if (first.getEmergencyServiceCategoryBitmask()
                != second.getEmergencyServiceCategoryBitmask()) {
            return false;
        }
        if (first.getEmergencyUrns().equals(second.getEmergencyUrns())) {
            return false;
        }
        if (first.getEmergencyCallRouting() != second.getEmergencyCallRouting()) {
            return false;
        }
        // Never merge two numbers if one of them is from test mode but the other one is not;
        // This supports to remove a number from the test mode.
        if (first.isFromSources(EMERGENCY_NUMBER_SOURCE_TEST)
                ^ second.isFromSources(EMERGENCY_NUMBER_SOURCE_TEST)) {
            return false;
        }
        return true;
    }

    /**
     * Get a merged EmergencyNumber from two same emergency numbers. Two emergency numbers are
     * the same if {@link #areSameEmergencyNumbers} returns {@code true}.
     *
     * @param first first EmergencyNumber to compare
     * @param second second EmergencyNumber to compare
     * @return a merged EmergencyNumber or null if they are not the same EmergencyNumber
     *
     * @hide
     */
    public static EmergencyNumber mergeSameEmergencyNumbers(@NonNull EmergencyNumber first,
                                                            @NonNull EmergencyNumber second) {
        if (areSameEmergencyNumbers(first, second)) {
            return new EmergencyNumber(first.getNumber(), first.getCountryIso(), first.getMnc(),
                    first.getEmergencyServiceCategoryBitmask(),
                    first.getEmergencyUrns(),
                    first.getEmergencyNumberSourceBitmask()
                            | second.getEmergencyNumberSourceBitmask(),
                    first.getEmergencyCallRouting());
        }
        return null;
    }

    /**
     * Validate Emergency Number address that only contains the dialable character
     * {@link PhoneNumberUtils#isDialable(char)}
     *
     * @hide
     */
    public static boolean validateEmergencyNumberAddress(String address) {
        if (address == null) {
            return false;
        }
        for (char c : address.toCharArray()) {
            if (!PhoneNumberUtils.isDialable(c)) {
                return false;
            }
        }
        return true;
    }
}
