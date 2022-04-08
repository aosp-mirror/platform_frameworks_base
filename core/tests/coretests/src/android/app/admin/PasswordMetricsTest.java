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
 * limitations under the License
 */

package android.app.admin;

import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
import static android.app.admin.PasswordMetrics.complexityLevelToMinQuality;
import static android.app.admin.PasswordMetrics.sanitizeComplexityLevel;
import static android.app.admin.PasswordMetrics.validatePasswordMetrics;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.PasswordValidationError;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Unit tests for {@link PasswordMetrics}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class PasswordMetricsTest {
    @Test
    public void testParceling() {
        final int credType = CREDENTIAL_TYPE_PASSWORD;
        final int length = 1;
        final int letters = 2;
        final int upperCase = 3;
        final int lowerCase = 4;
        final int numeric = 5;
        final int symbols = 6;
        final int nonLetter = 7;
        final int nonNumeric = 8;
        final int seqLength = 9;

        final Parcel parcel = Parcel.obtain();
        PasswordMetrics metrics = new PasswordMetrics(credType, length, letters, upperCase,
                lowerCase, numeric, symbols, nonLetter, nonNumeric, seqLength);
        try {
            metrics.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            metrics = PasswordMetrics.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }

        assertEquals(credType, metrics.credType);
        assertEquals(length, metrics.length);
        assertEquals(letters, metrics.letters);
        assertEquals(upperCase, metrics.upperCase);
        assertEquals(lowerCase, metrics.lowerCase);
        assertEquals(numeric, metrics.numeric);
        assertEquals(symbols, metrics.symbols);
        assertEquals(nonLetter, metrics.nonLetter);
        assertEquals(nonNumeric, metrics.nonNumeric);
        assertEquals(seqLength, metrics.seqLength);
    }

    @Test
    public void testComputeForPassword_metrics() {
        final PasswordMetrics metrics =
                PasswordMetrics.computeForPassword("6B~0z1Z3*8A".getBytes());
        assertEquals(11, metrics.length);
        assertEquals(4, metrics.letters);
        assertEquals(3, metrics.upperCase);
        assertEquals(1, metrics.lowerCase);
        assertEquals(5, metrics.numeric);
        assertEquals(2, metrics.symbols);
        assertEquals(7, metrics.nonLetter);
    }

    @Test
    public void testMaxLengthSequence() {
        assertEquals(4, PasswordMetrics.maxLengthSequence("1234".getBytes()));
        assertEquals(5, PasswordMetrics.maxLengthSequence("13579".getBytes()));
        assertEquals(4, PasswordMetrics.maxLengthSequence("1234abd".getBytes()));
        assertEquals(3, PasswordMetrics.maxLengthSequence("aabc".getBytes()));
        assertEquals(1, PasswordMetrics.maxLengthSequence("qwertyuio".getBytes()));
        assertEquals(3, PasswordMetrics.maxLengthSequence("@ABC".getBytes()));
        // anything that repeats
        assertEquals(4, PasswordMetrics.maxLengthSequence(";;;;".getBytes()));
        // ordered, but not composed of alphas or digits
        assertEquals(1, PasswordMetrics.maxLengthSequence(":;<=>".getBytes()));
    }

    @Test
    public void testDetermineComplexity_none() {
        assertEquals(PASSWORD_COMPLEXITY_NONE,
                new PasswordMetrics(CREDENTIAL_TYPE_NONE).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowSomething() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                new PasswordMetrics(CREDENTIAL_TYPE_PATTERN).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowNumeric() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                PasswordMetrics.computeForPassword("1234".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowNumericComplex() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                PasswordMetrics.computeForPassword("124".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowAlphabetic() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                PasswordMetrics.computeForPassword("a!".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowAlphanumeric() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                PasswordMetrics.computeForPassword("a!1".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_mediumNumericComplex() {
        assertEquals(PASSWORD_COMPLEXITY_MEDIUM,
                PasswordMetrics.computeForPassword("1238".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_mediumAlphabetic() {
        assertEquals(PASSWORD_COMPLEXITY_MEDIUM,
                PasswordMetrics.computeForPassword("ab!c".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_mediumAlphanumeric() {
        assertEquals(PASSWORD_COMPLEXITY_MEDIUM,
                PasswordMetrics.computeForPassword("ab!1".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_highNumericComplex() {
        assertEquals(PASSWORD_COMPLEXITY_HIGH,
                PasswordMetrics.computeForPassword("12389647!".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_highAlphabetic() {
        assertEquals(PASSWORD_COMPLEXITY_HIGH,
                PasswordMetrics.computeForPassword("alphabetic!".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_highAlphanumeric() {
        assertEquals(PASSWORD_COMPLEXITY_HIGH, PasswordMetrics.computeForPassword(
                "alphanumeric123!".getBytes()).determineComplexity());
    }

    @Test
    public void testSanitizeComplexityLevel_none() {
        assertEquals(PASSWORD_COMPLEXITY_NONE, sanitizeComplexityLevel(PASSWORD_COMPLEXITY_NONE));

    }

    @Test
    public void testSanitizeComplexityLevel_low() {
        assertEquals(PASSWORD_COMPLEXITY_LOW, sanitizeComplexityLevel(PASSWORD_COMPLEXITY_LOW));
    }

    @Test
    public void testSanitizeComplexityLevel_medium() {
        assertEquals(
                PASSWORD_COMPLEXITY_MEDIUM, sanitizeComplexityLevel(PASSWORD_COMPLEXITY_MEDIUM));
    }

    @Test
    public void testSanitizeComplexityLevel_high() {
        assertEquals(PASSWORD_COMPLEXITY_HIGH, sanitizeComplexityLevel(PASSWORD_COMPLEXITY_HIGH));
    }

    @Test
    public void testSanitizeComplexityLevel_invalid() {
        assertEquals(PASSWORD_COMPLEXITY_NONE, sanitizeComplexityLevel(-1));
    }

    @Test
    public void testComplexityLevelToMinQuality_none() {
        assertEquals(PASSWORD_QUALITY_UNSPECIFIED,
                complexityLevelToMinQuality(PASSWORD_COMPLEXITY_NONE));
    }

    @Test
    public void testComplexityLevelToMinQuality_low() {
        assertEquals(PASSWORD_QUALITY_SOMETHING,
                complexityLevelToMinQuality(PASSWORD_COMPLEXITY_LOW));
    }

    @Test
    public void testComplexityLevelToMinQuality_medium() {
        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX,
                complexityLevelToMinQuality(PASSWORD_COMPLEXITY_MEDIUM));
    }

    @Test
    public void testComplexityLevelToMinQuality_high() {
        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX,
                complexityLevelToMinQuality(PASSWORD_COMPLEXITY_HIGH));
    }

    @Test
    public void testComplexityLevelToMinQuality_invalid() {
        assertEquals(PASSWORD_QUALITY_UNSPECIFIED, complexityLevelToMinQuality(-1));
    }

    @Test
    public void testMerge_single() {
        PasswordMetrics metrics = new PasswordMetrics(CREDENTIAL_TYPE_PASSWORD);
        assertEquals(CREDENTIAL_TYPE_PASSWORD,
                PasswordMetrics.merge(Collections.singletonList(metrics)).credType);
    }

    @Test
    public void testMerge_credentialTypes() {
        PasswordMetrics none = new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        PasswordMetrics pattern = new PasswordMetrics(CREDENTIAL_TYPE_PATTERN);
        PasswordMetrics password = new PasswordMetrics(CREDENTIAL_TYPE_PASSWORD);
        assertEquals(CREDENTIAL_TYPE_PATTERN,
                PasswordMetrics.merge(Arrays.asList(new PasswordMetrics[]{none, pattern}))
                        .credType);
        assertEquals(CREDENTIAL_TYPE_PASSWORD,
                PasswordMetrics.merge(Arrays.asList(new PasswordMetrics[]{none, password}))
                        .credType);
        assertEquals(CREDENTIAL_TYPE_PASSWORD,
                PasswordMetrics.merge(Arrays.asList(new PasswordMetrics[]{password, pattern}))
                        .credType);
    }

    @Test
    public void testValidatePasswordMetrics_credentialTypes() {
        PasswordMetrics none = new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        PasswordMetrics pattern = new PasswordMetrics(CREDENTIAL_TYPE_PATTERN);
        PasswordMetrics password = new PasswordMetrics(CREDENTIAL_TYPE_PASSWORD);

        // To pass minimal length check.
        password.length = 4;

        // No errors expected, credential is of stronger or equal type.
        assertValidationErrors(
                validatePasswordMetrics(none, PASSWORD_COMPLEXITY_NONE, false, none));
        assertValidationErrors(
                validatePasswordMetrics(none, PASSWORD_COMPLEXITY_NONE, false, pattern));
        assertValidationErrors(
                validatePasswordMetrics(none, PASSWORD_COMPLEXITY_NONE, false, password));
        assertValidationErrors(
                validatePasswordMetrics(pattern, PASSWORD_COMPLEXITY_NONE, false, pattern));
        assertValidationErrors(
                validatePasswordMetrics(pattern, PASSWORD_COMPLEXITY_NONE, false, password));
        assertValidationErrors(
                validatePasswordMetrics(password, PASSWORD_COMPLEXITY_NONE, false, password));

        // Now actual credential type is weaker than required:
        assertValidationErrors(
                validatePasswordMetrics(pattern, PASSWORD_COMPLEXITY_NONE, false, none),
                PasswordValidationError.WEAK_CREDENTIAL_TYPE, 0);
        assertValidationErrors(
                validatePasswordMetrics(password, PASSWORD_COMPLEXITY_NONE, false, none),
                PasswordValidationError.WEAK_CREDENTIAL_TYPE, 0);
        assertValidationErrors(
                validatePasswordMetrics(password, PASSWORD_COMPLEXITY_NONE, false, pattern),
                PasswordValidationError.WEAK_CREDENTIAL_TYPE, 0);
    }

    /**
     * @param expected sequense of validation error codes followed by requirement values, must have
     *                even number of elements. Empty means no errors.
     */
    private void assertValidationErrors(
            List<PasswordValidationError> actualErrors, int... expected) {
        assertEquals("Test programming error: content shoud have even number of elements",
                0, expected.length % 2);
        assertEquals("wrong number of validation errors", expected.length / 2, actualErrors.size());
        HashMap<Integer, Integer> errorMap = new HashMap<>();
        for (PasswordValidationError error : actualErrors) {
            errorMap.put(error.errorCode, error.requirement);
        }

        for (int i = 0; i < expected.length / 2; i++) {
            final int expectedError = expected[i * 2];
            final int expectedRequirement = expected[i * 2 + 1];
            assertTrue("error expected but not reported: " + expectedError,
                    errorMap.containsKey(expectedError));
            assertEquals("unexpected requirement for error: " + expectedError,
                    Integer.valueOf(expectedRequirement), errorMap.get(expectedError));
        }
    }
}
