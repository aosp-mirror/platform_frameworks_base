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

import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager.PasswordComplexity;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

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

    // TODO(b/120536847): refactor isActivePasswordSufficient logic so that the actual password
    // quality is not overwritten
    public int quality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
    public int length = 0;
    public int letters = 0;
    public int upperCase = 0;
    public int lowerCase = 0;
    public int numeric = 0;
    public int symbols = 0;
    public int nonLetter = 0;

    public PasswordMetrics() {}

    public PasswordMetrics(int quality) {
        this.quality = quality;
    }

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

    /** Returns the min quality allowed by {@code complexityLevel}. */
    public static int complexityLevelToMinQuality(@PasswordComplexity int complexityLevel) {
        // this would be the quality of the first metrics since mMetrics is sorted in ascending
        // order of quality
        return PasswordComplexityBucket
                .complexityLevelToBucket(complexityLevel).mMetrics[0].quality;
    }

    /**
     * Returns a merged minimum {@link PasswordMetrics} requirements that a new password must meet
     * to fulfil {@code requestedQuality}, {@code requiresNumeric} and {@code
     * requiresLettersOrSymbols}, which are derived from {@link DevicePolicyManager} requirements,
     * and {@code complexityLevel}.
     *
     * <p>Note that we are taking {@code userEnteredPasswordQuality} into account because there are
     * more than one set of metrics to meet the minimum complexity requirement and inspecting what
     * the user has entered can help determine whether the alphabetic or alphanumeric set of metrics
     * should be used. For example, suppose minimum complexity requires either ALPHABETIC(8+), or
     * ALPHANUMERIC(6+). If the user has entered "a", the length requirement displayed on the UI
     * would be 8. Then the user appends "1" to make it "a1". We now know the user is entering
     * an alphanumeric password so we would update the min complexity required min length to 6.
     */
    public static PasswordMetrics getMinimumMetrics(@PasswordComplexity int complexityLevel,
            int userEnteredPasswordQuality, int requestedQuality, boolean requiresNumeric,
            boolean requiresLettersOrSymbols) {
        int targetQuality = Math.max(
                userEnteredPasswordQuality,
                getActualRequiredQuality(
                        requestedQuality, requiresNumeric, requiresLettersOrSymbols));
        return getTargetQualityMetrics(complexityLevel, targetQuality);
    }

    /**
     * Returns the {@link PasswordMetrics} at {@code complexityLevel} which the metrics quality
     * is the same as {@code targetQuality}.
     *
     * <p>If {@code complexityLevel} does not allow {@code targetQuality}, returns the metrics
     * with the min quality at {@code complexityLevel}.
     */
    // TODO(bernardchau): update tests to test getMinimumMetrics and change this to be private
    @VisibleForTesting
    public static PasswordMetrics getTargetQualityMetrics(
            @PasswordComplexity int complexityLevel, int targetQuality) {
        PasswordComplexityBucket targetBucket =
                PasswordComplexityBucket.complexityLevelToBucket(complexityLevel);
        for (PasswordMetrics metrics : targetBucket.mMetrics) {
            if (targetQuality == metrics.quality) {
                return metrics;
            }
        }
        // none of the metrics at complexityLevel has targetQuality, return metrics with min quality
        // see test case testGetMinimumMetrics_actualRequiredQualityStricter for an example, where
        // min complexity allows at least NUMERIC_COMPLEX, user has not entered anything yet, and
        // requested quality is NUMERIC
        return targetBucket.mMetrics[0];
    }

    /**
     * Finds out the actual quality requirement based on whether quality is {@link
     * DevicePolicyManager#PASSWORD_QUALITY_COMPLEX} and whether digits, letters or symbols are
     * required.
     */
    @VisibleForTesting
    // TODO(bernardchau): update tests to test getMinimumMetrics and change this to be private
    public static int getActualRequiredQuality(
            int requestedQuality, boolean requiresNumeric, boolean requiresLettersOrSymbols) {
        if (requestedQuality != PASSWORD_QUALITY_COMPLEX) {
            return requestedQuality;
        }

        // find out actual password quality from complex requirements
        if (requiresNumeric && requiresLettersOrSymbols) {
            return PASSWORD_QUALITY_ALPHANUMERIC;
        }
        if (requiresLettersOrSymbols) {
            return PASSWORD_QUALITY_ALPHABETIC;
        }
        if (requiresNumeric) {
            // cannot specify numeric complex using complex quality so this must be numeric
            return PASSWORD_QUALITY_NUMERIC;
        }

        // reaching here means dpm sets quality to complex without specifying any requirements
        return PASSWORD_QUALITY_UNSPECIFIED;
    }

    /**
     * Returns {@code complexityLevel} or {@link DevicePolicyManager#PASSWORD_COMPLEXITY_NONE}
     * if {@code complexityLevel} is not valid.
     */
    @PasswordComplexity
    public static int sanitizeComplexityLevel(@PasswordComplexity int complexityLevel) {
        return PasswordComplexityBucket.complexityLevelToBucket(complexityLevel).mComplexityLevel;
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

    /**
     * Returns the {@code PasswordMetrics} for a given password
     */
    public static PasswordMetrics computeForPassword(@NonNull byte[] password) {
        // Analyse the characters used
        int letters = 0;
        int upperCase = 0;
        int lowerCase = 0;
        int numeric = 0;
        int symbols = 0;
        int nonLetter = 0;
        final int length = password.length;
        for (int i = 0; i < length; i++) {
            switch (categoryChar((char) password[i])) {
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

    private boolean satisfiesBucket(PasswordMetrics... bucket) {
        for (PasswordMetrics metrics : bucket) {
            if (this.quality == metrics.quality) {
                return this.length >= metrics.length;
            }
        }
        return false;
    }

    /**
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
     * @param bytes the pass
     * @return the number of sequential letters or digits
     */
    public static int maxLengthSequence(@NonNull byte[] bytes) {
        if (bytes.length == 0) return 0;
        char previousChar = (char) bytes[0];
        @CharacterCatagory int category = categoryChar(previousChar); //current sequence category
        int diff = 0; //difference between two consecutive characters
        boolean hasDiff = false; //if we are currently targeting a sequence
        int maxLength = 0; //maximum length of a sequence already found
        int startSequence = 0; //where the current sequence started
        for (int current = 1; current < bytes.length; current++) {
            char currentChar = (char) bytes[current];
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
        maxLength = Math.max(maxLength, bytes.length - startSequence);
        return maxLength;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CHAR_" }, value = {
            CHAR_UPPER_CASE,
            CHAR_LOWER_CASE,
            CHAR_DIGIT,
            CHAR_SYMBOL
    })
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

    /** Determines the {@link PasswordComplexity} of this {@link PasswordMetrics}. */
    @PasswordComplexity
    public int determineComplexity() {
        for (PasswordComplexityBucket bucket : PasswordComplexityBucket.BUCKETS) {
            if (satisfiesBucket(bucket.mMetrics)) {
                return bucket.mComplexityLevel;
            }
        }
        return PASSWORD_COMPLEXITY_NONE;
    }

    /**
     * Requirements in terms of {@link PasswordMetrics} for each {@link PasswordComplexity}.
     */
    private static class PasswordComplexityBucket {
        /**
         * Definition of {@link DevicePolicyManager#PASSWORD_COMPLEXITY_HIGH} in terms of
         * {@link PasswordMetrics}.
         */
        private static final PasswordComplexityBucket HIGH =
                new PasswordComplexityBucket(
                        PASSWORD_COMPLEXITY_HIGH,
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX, /* length= */
                                8),
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC, /* length= */ 6),
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC, /* length= */
                                6));

        /**
         * Definition of {@link DevicePolicyManager#PASSWORD_COMPLEXITY_MEDIUM} in terms of
         * {@link PasswordMetrics}.
         */
        private static final PasswordComplexityBucket MEDIUM =
                new PasswordComplexityBucket(
                        PASSWORD_COMPLEXITY_MEDIUM,
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX, /* length= */
                                4),
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC, /* length= */ 4),
                        new PasswordMetrics(
                                DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC, /* length= */
                                4));

        /**
         * Definition of {@link DevicePolicyManager#PASSWORD_COMPLEXITY_LOW} in terms of
         * {@link PasswordMetrics}.
         */
        private static final PasswordComplexityBucket LOW =
                new PasswordComplexityBucket(
                        PASSWORD_COMPLEXITY_LOW,
                        new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING),
                        new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC),
                        new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX),
                        new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC),
                        new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC));

        /**
         * A special bucket to represent {@link DevicePolicyManager#PASSWORD_COMPLEXITY_NONE}.
         */
        private static final PasswordComplexityBucket NONE =
                new PasswordComplexityBucket(PASSWORD_COMPLEXITY_NONE, new PasswordMetrics());

        /** Array containing all buckets from high to low. */
        private static final PasswordComplexityBucket[] BUCKETS =
                new PasswordComplexityBucket[] {HIGH, MEDIUM, LOW};

        @PasswordComplexity
        private final int mComplexityLevel;
        private final PasswordMetrics[] mMetrics;

        /**
         * @param metricsArray must be sorted in ascending order of {@link #quality}.
         */
        private PasswordComplexityBucket(@PasswordComplexity int complexityLevel,
                PasswordMetrics... metricsArray) {
            int previousQuality = PASSWORD_QUALITY_UNSPECIFIED;
            for (PasswordMetrics metrics : metricsArray) {
                if (metrics.quality < previousQuality) {
                    throw new IllegalArgumentException("metricsArray must be sorted in ascending"
                            + " order of quality");
                }
                previousQuality = metrics.quality;
            }

            this.mMetrics = metricsArray;
            this.mComplexityLevel = complexityLevel;

        }

        /** Returns the bucket that {@code complexityLevel} represents. */
        private static PasswordComplexityBucket complexityLevelToBucket(
                @PasswordComplexity int complexityLevel) {
            for (PasswordComplexityBucket bucket : BUCKETS) {
                if (bucket.mComplexityLevel == complexityLevel) {
                    return bucket;
                }
            }
            return NONE;
        }
    }
}
