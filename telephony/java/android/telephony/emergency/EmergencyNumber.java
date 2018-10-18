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
import android.hardware.radio.V1_3.EmergencyNumberSource;
import android.hardware.radio.V1_3.EmergencyServiceCategory;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * The source to tell where the corresponding @1.3::EmergencyNumber comes from.
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
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG);
        EMERGENCY_NUMBER_SOURCE_SET.add(EMERGENCY_NUMBER_SOURCE_DEFAULT);
    }

    private final String mNumber;
    private final String mCountryIso;
    private final int mEmergencyServiceCategoryBitmask;
    private final int mEmergencyNumberSourceBitmask;

    /** @hide */
    public EmergencyNumber(String number, String countryIso,
                           int emergencyServiceCategories,
                           int emergencyNumberSources) {
        this.mNumber = number;
        this.mCountryIso = countryIso;
        this.mEmergencyServiceCategoryBitmask = emergencyServiceCategories;
        this.mEmergencyNumberSourceBitmask = emergencyNumberSources;
    }

    /** @hide */
    public EmergencyNumber(Parcel source) {
        mNumber = source.readString();
        mCountryIso = source.readString();
        mEmergencyServiceCategoryBitmask = source.readInt();
        mEmergencyNumberSourceBitmask = source.readInt();
    }

    /**
     * Get the dialing number of the emergency number.
     *
     * The character in the number string is only the dial pad
     * character('0'-'9', '*', or '#'). For example: 911.
     *
     * @return the dialing number.
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * Get the country code string (lowercase character) in ISO 3166 format of the emergency number.
     *
     * @return the country code string (lowercase character) in ISO 3166 format.
     */
    public String getCountryIso() {
        return mCountryIso;
    }

    /**
     * Returns the bitmask of emergency service categories of the emergency number.
     *
     * @return bitmask of the emergency service categories
     */
    public @EmergencyServiceCategories int getEmergencyServiceCategoryBitmask() {
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
    public List<Integer> getEmergencyServiceCategories() {
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
     */
    public @EmergencyNumberSources int getEmergencyNumberSourceBitmask() {
        return mEmergencyNumberSourceBitmask;
    }

    /**
     * Returns a list of sources of the emergency number.
     *
     * @return a list of emergency number sources
     */
    public List<Integer> getEmergencyNumberSources() {
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

    @Override
    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mNumber);
        dest.writeString(mCountryIso);
        dest.writeInt(mEmergencyServiceCategoryBitmask);
        dest.writeInt(mEmergencyNumberSourceBitmask);
    }

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "EmergencyNumber = " + "[Number]" + mNumber + " / [CountryIso]" + mCountryIso
                + " / [ServiceCategories]"
                + Integer.toBinaryString(mEmergencyServiceCategoryBitmask)
                + " / [Sources]" + Integer.toBinaryString(mEmergencyNumberSourceBitmask);
    }

    @Override
    public boolean equals(Object o) {
        if (!EmergencyNumber.class.isInstance(o)) {
            return false;
        }
        return (o == this || toString().equals(o.toString()));
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
        // TODO add a score if the number comes from Google's emergency number database
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
    public int compareTo(EmergencyNumber emergencyNumber) {
        if (this.getDisplayPriorityScore()
                > emergencyNumber.getDisplayPriorityScore()) {
            return -1;
        } else if (this.getDisplayPriorityScore()
                < emergencyNumber.getDisplayPriorityScore()) {
            return 1;
        } else {
            /**
             * TODO if both numbers have the same display priority score, the number matches the
             * Google's emergency number database has a higher display priority.
             */
            return 0;
        }
    }

    public static final Parcelable.Creator<EmergencyNumber> CREATOR =
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
}
