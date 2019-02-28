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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * This class is used to represent a range of phone numbers. Each range corresponds to a contiguous
 * block of phone numbers.
 *
 * Example:
 * {@code
 * {
 *     mCountryCode = "1"
 *     mPrefix = "650555"
 *     mLowerBound = "0055"
 *     mUpperBound = "0899"
 * }
 * }
 * would match 16505550089 and 6505550472, but not 63827593759 or 16505550900
 * @hide
 */
@SystemApi
public final class PhoneNumberRange implements Parcelable {
    public static final @android.annotation.NonNull Creator<PhoneNumberRange> CREATOR = new Creator<PhoneNumberRange>() {
        @Override
        public PhoneNumberRange createFromParcel(Parcel in) {
            return new PhoneNumberRange(in);
        }

        @Override
        public PhoneNumberRange[] newArray(int size) {
            return new PhoneNumberRange[size];
        }
    };

    private final String mCountryCode;
    private final String mPrefix;
    private final String mLowerBound;
    private final String mUpperBound;

    /**
     * @param countryCode The country code, omitting the leading "+"
     * @param prefix A prefix that all numbers matching the range must have.
     * @param lowerBound When concatenated with the prefix, represents the lower bound of phone
     *                   numbers that match this range.
     * @param upperBound When concatenated with the prefix, represents the upper bound of phone
     *                   numbers that match this range.
     */
    public PhoneNumberRange(@NonNull String countryCode, @NonNull String prefix,
            @NonNull String lowerBound, @NonNull String upperBound) {
        validateLowerAndUpperBounds(lowerBound, upperBound);
        if (!Pattern.matches("[0-9]*", countryCode)) {
            throw new IllegalArgumentException("Country code must be all numeric");
        }
        if (!Pattern.matches("[0-9]*", prefix)) {
            throw new IllegalArgumentException("Prefix must be all numeric");
        }
        mCountryCode = countryCode;
        mPrefix = prefix;
        mLowerBound = lowerBound;
        mUpperBound = upperBound;
    }

    private PhoneNumberRange(Parcel in) {
        mCountryCode = in.readStringNoHelper();
        mPrefix = in.readStringNoHelper();
        mLowerBound = in.readStringNoHelper();
        mUpperBound = in.readStringNoHelper();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringNoHelper(mCountryCode);
        dest.writeStringNoHelper(mPrefix);
        dest.writeStringNoHelper(mLowerBound);
        dest.writeStringNoHelper(mUpperBound);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhoneNumberRange that = (PhoneNumberRange) o;
        return Objects.equals(mCountryCode, that.mCountryCode)
                && Objects.equals(mPrefix, that.mPrefix)
                && Objects.equals(mLowerBound, that.mLowerBound)
                && Objects.equals(mUpperBound, that.mUpperBound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCountryCode, mPrefix, mLowerBound, mUpperBound);
    }

    @Override
    public String toString() {
        return "PhoneNumberRange{"
                + "mCountryCode='" + mCountryCode + '\''
                + ", mPrefix='" + mPrefix + '\''
                + ", mLowerBound='" + mLowerBound + '\''
                + ", mUpperBound='" + mUpperBound + '\''
                + '}';
    }

    private void validateLowerAndUpperBounds(String lowerBound, String upperBound) {
        if (lowerBound.length() != upperBound.length()) {
            throw new IllegalArgumentException("Lower and upper bounds must have the same length");
        }
        if (!Pattern.matches("[0-9]*", lowerBound)) {
            throw new IllegalArgumentException("Lower bound must be all numeric");
        }
        if (!Pattern.matches("[0-9]*", upperBound)) {
            throw new IllegalArgumentException("Upper bound must be all numeric");
        }
        if (Integer.parseInt(lowerBound) > Integer.parseInt(upperBound)) {
            throw new IllegalArgumentException("Lower bound must be lower than upper bound");
        }
    }

    /**
     * Checks to see if the provided phone number matches this range.
     * @param number A phone number, with or without separators or a country code.
     * @return {@code true} if the number matches, {@code false} otherwise.
     */
    public boolean matches(String number) {
        // Check the prefix, make sure it matches either with or without the country code.
        String normalizedNumber = number.replaceAll("[^0-9]", "");
        String prefixWithCountryCode = mCountryCode + mPrefix;
        String numberPostfix;
        if (normalizedNumber.startsWith(prefixWithCountryCode)) {
            numberPostfix = normalizedNumber.substring(prefixWithCountryCode.length());
        } else if (normalizedNumber.startsWith(mPrefix)) {
            numberPostfix = normalizedNumber.substring(mPrefix.length());
        } else {
            return false;
        }

        // Next check the postfix to make sure it lies within the bounds.
        try {
            int lower = Integer.parseInt(mLowerBound);
            int upper = Integer.parseInt(mUpperBound);
            int numberToCheck = Integer.parseInt(numberPostfix);
            return numberToCheck <= upper && numberToCheck >= lower;
        } catch (NumberFormatException e) {
            Log.e(PhoneNumberRange.class.getSimpleName(), "Invalid bounds or number.", e);
            return false;
        }
    }
}
