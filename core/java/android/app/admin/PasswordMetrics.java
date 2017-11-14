/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that represents the metrics of a password that are used to decide whether or not a
 * password meets the requirements.
 *
 * {@hide}
 */
public class PasswordMetrics implements Parcelable {
    // Maximum allowed number of repeated or ordered characters in a sequence before we'll
    // consider it a complex PIN/password.
    public static final int MAX_ALLOWED_SEQUENCE = 3;

    public int quality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
    public int length = 0;
    public int letters = 0;
    public int upperCase = 0;
    public int lowerCase = 0;
    public int numeric = 0;
    public int symbols = 0;
    public int nonLetter = 0;

    public PasswordMetrics() {}

    public PasswordMetrics(int quality, int length) {
        this.quality = quality;
        this.length = length;
    }

    public PasswordMetrics(int quality, int length, int letters, int upperCase, int lowerCase,
            int numeric, int symbols, int nonLetter) {
        this(quality, length);
        this.letters = letters;
        this.upperCase = upperCase;
        this.lowerCase = lowerCase;
        this.numeric = numeric;
        this.symbols = symbols;
        this.nonLetter = nonLetter;
    }

    private PasswordMetrics(Parcel in) {
        quality = in.readInt();
        length = in.readInt();
        letters = in.readInt();
        upperCase = in.readInt();
        lowerCase = in.readInt();
        numeric = in.readInt();
        symbols = in.readInt();
        nonLetter = in.readInt();
    }

    public boolean isDefault() {
        return quality == DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
                && length == 0 && letters == 0 && upperCase == 0 && lowerCase == 0
                && numeric == 0 && symbols == 0 && nonLetter == 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(quality);
        dest.writeInt(length);
        dest.writeInt(letters);
        dest.writeInt(upperCase);
        dest.writeInt(lowerCase);
        dest.writeInt(numeric);
        dest.writeInt(symbols);
        dest.writeInt(nonLetter);
    }

    public static final Parcelable.Creator<PasswordMetrics> CREATOR
            = new Parcelable.Creator<PasswordMetrics>() {
        public PasswordMetrics createFromParcel(Parcel in) {
            return new PasswordMetrics(in);
        }

        public PasswordMetrics[] newArray(int size) {
            return new PasswordMetrics[size];
        }
    };

    public static PasswordMetrics computeForPassword(@NonNull String password) {
        // Analyse the characters used
        int letters = 0;
        int upperCase = 0;
        int lowerCase = 0;
        int numeric = 0;
        int symbols = 0;
        int nonLetter = 0;
        final int length = password.length();
        for (int i = 0; i < length; i++) {
            switch (categoryChar(password.charAt(i))) {
                case CHAR_LOWER_CASE:
                    letters++;
                    lowerCase++;
                    break;
                case CHAR_UPPER_CASE:
                    letters++;
                    upperCase++;
                    break;
                case CHAR_DIGIT:
                    numeric++;
                    nonLetter++;
                    break;
                case CHAR_SYMBOL:
                    symbols++;
                    nonLetter++;
                    break;
            }
        }

        // Determine the quality of the password
        final boolean hasNumeric = numeric > 0;
        final boolean hasNonNumeric = (letters + symbols) > 0;
        final int quality;
        if (hasNonNumeric && hasNumeric) {
            quality = DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
        } else if (hasNonNumeric) {
            quality = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
        } else if (hasNumeric) {
            quality = maxLengthSequence(password) > MAX_ALLOWED_SEQUENCE
                    ? DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    : DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
        } else {
            quality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        }

        return new PasswordMetrics(
                quality, length, letters, upperCase, lowerCase, numeric, symbols, nonLetter);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordMetrics)) {
            return false;
        }
        PasswordMetrics o = (PasswordMetrics) other;
        return this.quality == o.quality
                && this.length == o.length
                && this.letters == o.letters
                && this.upperCase == o.upperCase
                && this.lowerCase == o.lowerCase
                && this.numeric == o.numeric
                && this.symbols == o.symbols
                && this.nonLetter == o.nonLetter;
    }

    /*
     * Returns the maximum length of a sequential characters. A sequence is defined as
     * monotonically increasing characters with a constant interval or the same character repeated.
     *
     * For example:
     * maxLengthSequence("1234") == 4
     * maxLengthSequence("13579") == 5
     * maxLengthSequence("1234abc") == 4
     * maxLengthSequence("aabc") == 3
     * maxLengthSequence("qwertyuio") == 1
     * maxLengthSequence("@ABC") == 3
     * maxLengthSequence(";;;;") == 4 (anything that repeats)
     * maxLengthSequence(":;<=>") == 1  (ordered, but not composed of alphas or digits)
     *
     * @param string the pass
     * @return the number of sequential letters or digits
     */
    public static int maxLengthSequence(@NonNull String string) {
        if (string.length() == 0) return 0;
        char previousChar = string.charAt(0);
        @CharacterCatagory int category = categoryChar(previousChar); //current sequence category
        int diff = 0; //difference between two consecutive characters
        boolean hasDiff = false; //if we are currently targeting a sequence
        int maxLength = 0; //maximum length of a sequence already found
        int startSequence = 0; //where the current sequence started
        for (int current = 1; current < string.length(); current++) {
            char currentChar = string.charAt(current);
            @CharacterCatagory int categoryCurrent = categoryChar(currentChar);
            int currentDiff = (int) currentChar - (int) previousChar;
            if (categoryCurrent != category || Math.abs(currentDiff) > maxDiffCategory(category)) {
                maxLength = Math.max(maxLength, current - startSequence);
                startSequence = current;
                hasDiff = false;
                category = categoryCurrent;
            }
            else {
                if(hasDiff && currentDiff != diff) {
                    maxLength = Math.max(maxLength, current - startSequence);
                    startSequence = current - 1;
                }
                diff = currentDiff;
                hasDiff = true;
            }
            previousChar = currentChar;
        }
        maxLength = Math.max(maxLength, string.length() - startSequence);
        return maxLength;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CHAR_UPPER_CASE, CHAR_LOWER_CASE, CHAR_DIGIT, CHAR_SYMBOL})
    private @interface CharacterCatagory {}
    private static final int CHAR_LOWER_CASE = 0;
    private static final int CHAR_UPPER_CASE = 1;
    private static final int CHAR_DIGIT = 2;
    private static final int CHAR_SYMBOL = 3;

    @CharacterCatagory
    private static int categoryChar(char c) {
        if ('a' <= c && c <= 'z') return CHAR_LOWER_CASE;
        if ('A' <= c && c <= 'Z') return CHAR_UPPER_CASE;
        if ('0' <= c && c <= '9') return CHAR_DIGIT;
        return CHAR_SYMBOL;
    }

    private static int maxDiffCategory(@CharacterCatagory int category) {
        switch (category) {
            case CHAR_LOWER_CASE:
            case CHAR_UPPER_CASE:
                return 1;
            case CHAR_DIGIT:
                return 10;
            default:
                return 0;
        }
    }
}
