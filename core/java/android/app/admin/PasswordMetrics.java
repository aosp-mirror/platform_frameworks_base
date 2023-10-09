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

import static android.app.admin.DevicePolicyManager.MAX_PASSWORD_LENGTH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.MIN_LOCK_PASSWORD_SIZE;
import static com.android.internal.widget.LockPatternUtils.MIN_LOCK_PATTERN_SIZE;
import static com.android.internal.widget.PasswordValidationError.CONTAINS_INVALID_CHARACTERS;
import static com.android.internal.widget.PasswordValidationError.CONTAINS_SEQUENCE;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_DIGITS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_LETTERS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_LOWER_CASE;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_NON_DIGITS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_NON_LETTER;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_SYMBOLS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_UPPER_CASE;
import static com.android.internal.widget.PasswordValidationError.TOO_LONG;
import static com.android.internal.widget.PasswordValidationError.TOO_SHORT;
import static com.android.internal.widget.PasswordValidationError.TOO_SHORT_WHEN_ALL_NUMERIC;
import static com.android.internal.widget.PasswordValidationError.WEAK_CREDENTIAL_TYPE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager.PasswordComplexity;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils.CredentialType;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A class that represents the metrics of a credential that are used to decide whether or not a
 * credential meets the requirements.
 *
 * {@hide}
 */
public final class PasswordMetrics implements Parcelable {
    private static final String TAG = "PasswordMetrics";

    // Maximum allowed number of repeated or ordered characters in a sequence before we'll
    // consider it a complex PIN/password.
    public static final int MAX_ALLOWED_SEQUENCE = 3;

    // One of CREDENTIAL_TYPE_NONE, CREDENTIAL_TYPE_PATTERN, CREDENTIAL_TYPE_PIN or
    // CREDENTIAL_TYPE_PASSWORD.
    public @CredentialType int credType;
    // Fields below only make sense when credType is PASSWORD.
    public int length = 0;
    public int letters = 0;
    public int upperCase = 0;
    public int lowerCase = 0;
    public int numeric = 0;
    public int symbols = 0;
    public int nonLetter = 0;
    public int nonNumeric = 0;
    // MAX_VALUE is the most relaxed value, any sequence is ok, e.g. 123456789. 4 would forbid it.
    public int seqLength = Integer.MAX_VALUE;

    public PasswordMetrics(int credType) {
        this.credType = credType;
    }

    public PasswordMetrics(int credType , int length, int letters, int upperCase, int lowerCase,
            int numeric, int symbols, int nonLetter, int nonNumeric, int seqLength) {
        this.credType = credType;
        this.length = length;
        this.letters = letters;
        this.upperCase = upperCase;
        this.lowerCase = lowerCase;
        this.numeric = numeric;
        this.symbols = symbols;
        this.nonLetter = nonLetter;
        this.nonNumeric = nonNumeric;
        this.seqLength = seqLength;
    }

    private PasswordMetrics(PasswordMetrics other) {
        this(other.credType, other.length, other.letters, other.upperCase, other.lowerCase,
                other.numeric, other.symbols, other.nonLetter, other.nonNumeric, other.seqLength);
    }

    /**
     * Returns {@code complexityLevel} or {@link DevicePolicyManager#PASSWORD_COMPLEXITY_NONE}
     * if {@code complexityLevel} is not valid.
     *
     * TODO: move to PasswordPolicy
     */
    @PasswordComplexity
    public static int sanitizeComplexityLevel(@PasswordComplexity int complexityLevel) {
        switch (complexityLevel) {
            case PASSWORD_COMPLEXITY_HIGH:
            case PASSWORD_COMPLEXITY_MEDIUM:
            case PASSWORD_COMPLEXITY_LOW:
            case PASSWORD_COMPLEXITY_NONE:
                return complexityLevel;
            default:
                Log.w(TAG, "Invalid password complexity used: " + complexityLevel);
                return PASSWORD_COMPLEXITY_NONE;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(credType);
        dest.writeInt(length);
        dest.writeInt(letters);
        dest.writeInt(upperCase);
        dest.writeInt(lowerCase);
        dest.writeInt(numeric);
        dest.writeInt(symbols);
        dest.writeInt(nonLetter);
        dest.writeInt(nonNumeric);
        dest.writeInt(seqLength);
    }

    public static final @NonNull Parcelable.Creator<PasswordMetrics> CREATOR
            = new Parcelable.Creator<PasswordMetrics>() {
                @Override
                public PasswordMetrics createFromParcel(Parcel in) {
                    int credType = in.readInt();
                    int length = in.readInt();
                    int letters = in.readInt();
                    int upperCase = in.readInt();
                    int lowerCase = in.readInt();
                    int numeric = in.readInt();
                    int symbols = in.readInt();
                    int nonLetter = in.readInt();
                    int nonNumeric = in.readInt();
                    int seqLength = in.readInt();
                    return new PasswordMetrics(credType, length, letters, upperCase, lowerCase,
                            numeric, symbols, nonLetter, nonNumeric, seqLength);
                }

                @Override
                public PasswordMetrics[] newArray(int size) {
                    return new PasswordMetrics[size];
                }
    };

    /**
     * Returns the {@code PasswordMetrics} for the given credential.
     */
    public static PasswordMetrics computeForCredential(LockscreenCredential credential) {
        if (credential.isPassword() || credential.isPin()) {
            return computeForPasswordOrPin(credential.getCredential(), credential.isPin());
        } else if (credential.isPattern())  {
            PasswordMetrics metrics = new PasswordMetrics(CREDENTIAL_TYPE_PATTERN);
            metrics.length = credential.size();
            return metrics;
        } else if (credential.isNone()) {
            return new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        } else {
            throw new IllegalArgumentException("Unknown credential type " + credential.getType());
        }
    }

    /**
     * Returns the {@code PasswordMetrics} for the given password or pin.
     */
    private static PasswordMetrics computeForPasswordOrPin(byte[] credential, boolean isPin) {
        // Analyze the characters used.
        int letters = 0;
        int upperCase = 0;
        int lowerCase = 0;
        int numeric = 0;
        int symbols = 0;
        int nonLetter = 0;
        int nonNumeric = 0;
        final int length = credential.length;
        for (byte b : credential) {
            switch (categoryChar((char) b)) {
                case CHAR_LOWER_CASE:
                    letters++;
                    lowerCase++;
                    nonNumeric++;
                    break;
                case CHAR_UPPER_CASE:
                    letters++;
                    upperCase++;
                    nonNumeric++;
                    break;
                case CHAR_DIGIT:
                    numeric++;
                    nonLetter++;
                    break;
                case CHAR_SYMBOL:
                    symbols++;
                    nonLetter++;
                    nonNumeric++;
                    break;
            }
        }

        final int credType = isPin ? CREDENTIAL_TYPE_PIN : CREDENTIAL_TYPE_PASSWORD;
        final int seqLength = maxLengthSequence(credential);
        return new PasswordMetrics(credType, length, letters, upperCase, lowerCase,
                numeric, symbols, nonLetter, nonNumeric, seqLength);
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

    /**
     * Returns the weakest metrics that is stricter or equal to all given metrics.
     *
     * TODO: move to PasswordPolicy
     */
    public static PasswordMetrics merge(List<PasswordMetrics> metrics) {
        PasswordMetrics result = new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        for (PasswordMetrics m : metrics) {
            result.maxWith(m);
        }

        return result;
    }

    /**
     * Makes current metric at least as strong as {@code other} in every criterion.
     *
     * TODO: move to PasswordPolicy
     */
    public void maxWith(PasswordMetrics other) {
        credType = Math.max(credType, other.credType);
        if (credType != CREDENTIAL_TYPE_PASSWORD && credType != CREDENTIAL_TYPE_PIN) {
            return;
        }
        length = Math.max(length, other.length);
        letters = Math.max(letters, other.letters);
        upperCase = Math.max(upperCase, other.upperCase);
        lowerCase = Math.max(lowerCase, other.lowerCase);
        numeric = Math.max(numeric, other.numeric);
        symbols = Math.max(symbols, other.symbols);
        nonLetter = Math.max(nonLetter, other.nonLetter);
        nonNumeric = Math.max(nonNumeric, other.nonNumeric);
        seqLength = Math.min(seqLength, other.seqLength);
    }

    /**
     * Returns minimum password quality for a given complexity level.
     *
     * TODO: this function is used for determining allowed credential types, so it should return
     * credential type rather than 'quality'.
     *
     * TODO: move to PasswordPolicy
     */
    public static int complexityLevelToMinQuality(int complexity) {
        switch (complexity) {
            case PASSWORD_COMPLEXITY_HIGH:
            case PASSWORD_COMPLEXITY_MEDIUM:
                return PASSWORD_QUALITY_NUMERIC_COMPLEX;
            case PASSWORD_COMPLEXITY_LOW:
                return PASSWORD_QUALITY_SOMETHING;
            case PASSWORD_COMPLEXITY_NONE:
            default:
                return PASSWORD_QUALITY_UNSPECIFIED;
        }
    }

    /**
     * Enum representing requirements for each complexity level.
     *
     * TODO: move to PasswordPolicy
     */
    private enum ComplexityBucket {
        // Keep ordered high -> low.
        BUCKET_HIGH(PASSWORD_COMPLEXITY_HIGH) {
            @Override
            boolean canHaveSequence() {
                return false;
            }

            @Override
            int getMinimumLength(boolean containsNonNumeric) {
                return containsNonNumeric ? 6 : 8;
            }

            @Override
            boolean allowsCredType(int credType) {
                return credType == CREDENTIAL_TYPE_PASSWORD || credType == CREDENTIAL_TYPE_PIN;
            }
        },
        BUCKET_MEDIUM(PASSWORD_COMPLEXITY_MEDIUM) {
            @Override
            boolean canHaveSequence() {
                return false;
            }

            @Override
            int getMinimumLength(boolean containsNonNumeric) {
                return 4;
            }

            @Override
            boolean allowsCredType(int credType) {
                return credType == CREDENTIAL_TYPE_PASSWORD || credType == CREDENTIAL_TYPE_PIN;
            }
        },
        BUCKET_LOW(PASSWORD_COMPLEXITY_LOW) {
            @Override
            boolean canHaveSequence() {
                return true;
            }

            @Override
            int getMinimumLength(boolean containsNonNumeric) {
                return 0;
            }

            @Override
            boolean allowsCredType(int credType) {
                return credType != CREDENTIAL_TYPE_NONE;
            }
        },
        BUCKET_NONE(PASSWORD_COMPLEXITY_NONE) {
            @Override
            boolean canHaveSequence() {
                return true;
            }

            @Override
            int getMinimumLength(boolean containsNonNumeric) {
                return 0;
            }

            @Override
            boolean allowsCredType(int credType) {
                return true;
            }
        };

        int mComplexityLevel;

        abstract boolean canHaveSequence();
        abstract int getMinimumLength(boolean containsNonNumeric);
        abstract boolean allowsCredType(int credType);

        ComplexityBucket(int complexityLevel) {
            this.mComplexityLevel = complexityLevel;
        }

        static ComplexityBucket forComplexity(int complexityLevel) {
            for (ComplexityBucket bucket : values()) {
                if (bucket.mComplexityLevel == complexityLevel) {
                    return bucket;
                }
            }
            throw new IllegalArgumentException("Invalid complexity level: " + complexityLevel);
        }
    }

    /**
     * Returns whether current metrics satisfies a given complexity bucket.
     *
     * TODO: move inside ComplexityBucket.
     */
    private boolean satisfiesBucket(ComplexityBucket bucket) {
        if (!bucket.allowsCredType(credType)) {
            return false;
        }
        if (credType != CREDENTIAL_TYPE_PASSWORD && credType != CREDENTIAL_TYPE_PIN) {
            return true;
        }
        return (bucket.canHaveSequence() || seqLength <= MAX_ALLOWED_SEQUENCE)
                && length >= bucket.getMinimumLength(nonNumeric > 0 /* hasNonNumeric */);
    }

    /**
     * Returns the maximum complexity level satisfied by password with this metrics.
     *
     * TODO: move inside ComplexityBucket.
     */
    public int determineComplexity() {
        for (ComplexityBucket bucket : ComplexityBucket.values()) {
            if (satisfiesBucket(bucket)) {
                return bucket.mComplexityLevel;
            }
        }
        throw new IllegalStateException("Failed to figure out complexity for a given metrics");
    }

    /**
     * Validates a proposed lockscreen credential against minimum metrics and complexity.
     *
     * @param adminMetrics minimum metrics to satisfy admin requirements
     * @param minComplexity minimum complexity imposed by the requester
     * @param credential the proposed lockscreen credential
     *
     * @return a list of validation errors. An empty list means the credential is OK.
     *
     * TODO: move to PasswordPolicy
     */
    public static List<PasswordValidationError> validateCredential(
            PasswordMetrics adminMetrics, int minComplexity, LockscreenCredential credential) {
        if (credential.hasInvalidChars()) {
            return Collections.singletonList(
                    new PasswordValidationError(CONTAINS_INVALID_CHARACTERS, 0));
        }
        PasswordMetrics actualMetrics = computeForCredential(credential);
        return validatePasswordMetrics(adminMetrics, minComplexity, actualMetrics);
    }

    /**
     * Validates password metrics against minimum metrics and complexity
     *
     * @param adminMetrics - minimum metrics to satisfy admin requirements.
     * @param minComplexity - minimum complexity imposed by the requester.
     * @param actualMetrics - metrics for password to validate.
     * @return a list of password validation errors. An empty list means the password is OK.
     *
     * TODO: move to PasswordPolicy
     */
    public static List<PasswordValidationError> validatePasswordMetrics(
            PasswordMetrics adminMetrics, int minComplexity, PasswordMetrics actualMetrics) {
        final ComplexityBucket bucket = ComplexityBucket.forComplexity(minComplexity);

        // Make sure credential type is satisfactory.
        // TODO: stop relying on credential type ordering.
        if (actualMetrics.credType < adminMetrics.credType
                || !bucket.allowsCredType(actualMetrics.credType)) {
            return Collections.singletonList(new PasswordValidationError(WEAK_CREDENTIAL_TYPE, 0));
        }
        if (actualMetrics.credType == CREDENTIAL_TYPE_PATTERN) {
            // For pattern, only need to check the length against the hardcoded minimum.  If the
            // pattern length is unavailable (e.g., PasswordMetrics that was stored on-disk before
            // the pattern length started being included in it), assume it is okay.
            if (actualMetrics.length != 0 && actualMetrics.length < MIN_LOCK_PATTERN_SIZE) {
                return Collections.singletonList(new PasswordValidationError(TOO_SHORT,
                            MIN_LOCK_PATTERN_SIZE));
            }
            return Collections.emptyList();
        }
        if (actualMetrics.credType == CREDENTIAL_TYPE_NONE) {
            return Collections.emptyList(); // Nothing to check for none.
        }

        if (actualMetrics.credType == CREDENTIAL_TYPE_PIN && actualMetrics.nonNumeric > 0) {
            return Collections.singletonList(
                    new PasswordValidationError(CONTAINS_INVALID_CHARACTERS, 0));
        }

        final ArrayList<PasswordValidationError> result = new ArrayList<>();
        if (actualMetrics.length > MAX_PASSWORD_LENGTH) {
            result.add(new PasswordValidationError(TOO_LONG, MAX_PASSWORD_LENGTH));
        }

        final PasswordMetrics minMetrics = applyComplexity(adminMetrics,
                actualMetrics.credType == CREDENTIAL_TYPE_PIN, bucket);

        // Clamp required length between maximum and minimum valid values.
        minMetrics.length = Math.min(MAX_PASSWORD_LENGTH,
                Math.max(minMetrics.length, MIN_LOCK_PASSWORD_SIZE));
        minMetrics.removeOverlapping();

        comparePasswordMetrics(minMetrics, bucket, actualMetrics, result);

        return result;
    }

    /**
     * TODO: move to PasswordPolicy
     */
    private static void comparePasswordMetrics(PasswordMetrics minMetrics, ComplexityBucket bucket,
            PasswordMetrics actualMetrics, ArrayList<PasswordValidationError> result) {
        if (actualMetrics.length < minMetrics.length) {
            result.add(new PasswordValidationError(TOO_SHORT, minMetrics.length));
        }
        if (actualMetrics.nonNumeric == 0 && minMetrics.nonNumeric == 0 && minMetrics.letters == 0
                && minMetrics.lowerCase == 0 && minMetrics.upperCase == 0
                && minMetrics.symbols == 0) {
            // When provided password is all numeric and all numeric password is allowed.
            int allNumericMinimumLength = bucket.getMinimumLength(false);
            if (allNumericMinimumLength > minMetrics.length
                    && allNumericMinimumLength > minMetrics.numeric
                    && actualMetrics.length < allNumericMinimumLength) {
                result.add(new PasswordValidationError(
                        TOO_SHORT_WHEN_ALL_NUMERIC, allNumericMinimumLength));
            }
        }
        if (actualMetrics.letters < minMetrics.letters) {
            result.add(new PasswordValidationError(NOT_ENOUGH_LETTERS, minMetrics.letters));
        }
        if (actualMetrics.upperCase < minMetrics.upperCase) {
            result.add(new PasswordValidationError(NOT_ENOUGH_UPPER_CASE, minMetrics.upperCase));
        }
        if (actualMetrics.lowerCase < minMetrics.lowerCase) {
            result.add(new PasswordValidationError(NOT_ENOUGH_LOWER_CASE, minMetrics.lowerCase));
        }
        if (actualMetrics.numeric < minMetrics.numeric) {
            result.add(new PasswordValidationError(NOT_ENOUGH_DIGITS, minMetrics.numeric));
        }
        if (actualMetrics.symbols < minMetrics.symbols) {
            result.add(new PasswordValidationError(NOT_ENOUGH_SYMBOLS, minMetrics.symbols));
        }
        if (actualMetrics.nonLetter < minMetrics.nonLetter) {
            result.add(new PasswordValidationError(NOT_ENOUGH_NON_LETTER, minMetrics.nonLetter));
        }
        if (actualMetrics.nonNumeric < minMetrics.nonNumeric) {
            result.add(new PasswordValidationError(NOT_ENOUGH_NON_DIGITS, minMetrics.nonNumeric));
        }
        if (actualMetrics.seqLength > minMetrics.seqLength) {
            result.add(new PasswordValidationError(CONTAINS_SEQUENCE, 0));
        }
    }

    /**
     * Drop requirements that are superseded by others, e.g. if it is required to have 5 upper case
     * letters and 5 lower case letters, there is no need to require minimum number of letters to
     * be 10 since it will be fulfilled once upper and lower case requirements are fulfilled.
     *
     * TODO: move to PasswordPolicy
     */
    private void removeOverlapping() {
        // upperCase + lowerCase can override letters
        final int indirectLetters = upperCase + lowerCase;

        // numeric + symbols can override nonLetter
        final int indirectNonLetter = numeric + symbols;

        // letters + symbols can override nonNumeric
        final int effectiveLetters = Math.max(letters, indirectLetters);
        final int indirectNonNumeric = effectiveLetters + symbols;

        // letters + nonLetters can override length
        // numeric + nonNumeric can also override length, so max it with previous.
        final int effectiveNonLetter = Math.max(nonLetter, indirectNonLetter);
        final int effectiveNonNumeric = Math.max(nonNumeric, indirectNonNumeric);
        final int indirectLength = Math.max(effectiveLetters + effectiveNonLetter,
                numeric + effectiveNonNumeric);

        if (indirectLetters >= letters) {
            letters = 0;
        }
        if (indirectNonLetter >= nonLetter) {
            nonLetter = 0;
        }
        if (indirectNonNumeric >= nonNumeric) {
            nonNumeric = 0;
        }
        if (indirectLength >= length) {
            length = 0;
        }
    }

    /**
     * Combine minimum metrics, set by admin, complexity set by the requester and actual entered
     * password metrics to get resulting minimum metrics that the password has to satisfy. Always
     * returns a new PasswordMetrics object.
     *
     * TODO: move to PasswordPolicy
     */
    public static PasswordMetrics applyComplexity(PasswordMetrics adminMetrics, boolean isPin,
            int complexity) {
        return applyComplexity(adminMetrics, isPin, ComplexityBucket.forComplexity(complexity));
    }

    private static PasswordMetrics applyComplexity(PasswordMetrics adminMetrics, boolean isPin,
            ComplexityBucket bucket) {
        final PasswordMetrics minMetrics = new PasswordMetrics(adminMetrics);

        if (!bucket.canHaveSequence()) {
            minMetrics.seqLength = Math.min(minMetrics.seqLength, MAX_ALLOWED_SEQUENCE);
        }

        minMetrics.length = Math.max(minMetrics.length, bucket.getMinimumLength(!isPin));

        return minMetrics;
    }

    /**
     * Returns true if password is non-empty and contains digits only.
     * @param password
     * @return
     */
    public static boolean isNumericOnly(@NonNull String password) {
        if (password.length() == 0) return false;
        for (int i = 0; i < password.length(); i++) {
            if (categoryChar(password.charAt(i)) != CHAR_DIGIT) return false;
        }
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final PasswordMetrics that = (PasswordMetrics) o;
        return credType == that.credType
                && length == that.length
                && letters == that.letters
                && upperCase == that.upperCase
                && lowerCase == that.lowerCase
                && numeric == that.numeric
                && symbols == that.symbols
                && nonLetter == that.nonLetter
                && nonNumeric == that.nonNumeric
                && seqLength == that.seqLength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(credType, length, letters, upperCase, lowerCase, numeric, symbols,
                nonLetter, nonNumeric, seqLength);
    }
}
