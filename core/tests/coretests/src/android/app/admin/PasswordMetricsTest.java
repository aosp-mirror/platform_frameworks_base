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
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
import static android.app.admin.PasswordMetrics.complexityLevelToMinQuality;
import static android.app.admin.PasswordMetrics.getActualRequiredQuality;
import static android.app.admin.PasswordMetrics.getMinimumMetrics;
import static android.app.admin.PasswordMetrics.getTargetQualityMetrics;
import static android.app.admin.PasswordMetrics.sanitizeComplexityLevel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PasswordMetrics}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PasswordMetricsTest {

    @Test
    public void testIsDefault() {
        final PasswordMetrics metrics = new PasswordMetrics();
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, metrics.quality);
        assertEquals(0, metrics.length);
        assertEquals(0, metrics.letters);
        assertEquals(0, metrics.upperCase);
        assertEquals(0, metrics.lowerCase);
        assertEquals(0, metrics.numeric);
        assertEquals(0, metrics.symbols);
        assertEquals(0, metrics.nonLetter);
    }

    @Test
    public void testParceling() {
        final int quality = 0;
        final int length = 1;
        final int letters = 2;
        final int upperCase = 3;
        final int lowerCase = 4;
        final int numeric = 5;
        final int symbols = 6;
        final int nonLetter = 7;

        final Parcel parcel = Parcel.obtain();
        final PasswordMetrics metrics;
        try {
            new PasswordMetrics(
                    quality, length, letters, upperCase, lowerCase, numeric, symbols, nonLetter)
                    .writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            metrics = PasswordMetrics.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }

        assertEquals(quality, metrics.quality);
        assertEquals(length, metrics.length);
        assertEquals(letters, metrics.letters);
        assertEquals(upperCase, metrics.upperCase);
        assertEquals(lowerCase, metrics.lowerCase);
        assertEquals(numeric, metrics.numeric);
        assertEquals(symbols, metrics.symbols);
        assertEquals(nonLetter, metrics.nonLetter);

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
    public void testComputeForPassword_quality() {
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC,
                PasswordMetrics.computeForPassword("a1".getBytes()).quality);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC,
                PasswordMetrics.computeForPassword("a".getBytes()).quality);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC,
                PasswordMetrics.computeForPassword("*~&%$".getBytes()).quality);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX,
                PasswordMetrics.computeForPassword("1".getBytes()).quality);
        // contains a long sequence so isn't complex
        assertEquals(PASSWORD_QUALITY_NUMERIC,
                PasswordMetrics.computeForPassword("1234".getBytes()).quality);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                PasswordMetrics.computeForPassword("".getBytes()).quality);
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
    public void testEquals() {
        PasswordMetrics metrics0 = new PasswordMetrics();
        PasswordMetrics metrics1 = new PasswordMetrics();
        assertNotEquals(metrics0, null);
        assertNotEquals(metrics0, new Object());
        assertEquals(metrics0, metrics0);
        assertEquals(metrics0, metrics1);

        assertEquals(new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, 4),
                new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, 4));

        assertNotEquals(new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, 4),
                new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, 5));

        assertNotEquals(new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, 4),
                new PasswordMetrics(PASSWORD_QUALITY_COMPLEX, 4));

        metrics0 = PasswordMetrics.computeForPassword("1234abcd,./".getBytes());
        metrics1 = PasswordMetrics.computeForPassword("1234abcd,./".getBytes());
        assertEquals(metrics0, metrics1);
        metrics1.letters++;
        assertNotEquals(metrics0, metrics1);
        metrics1.letters--;
        metrics1.upperCase++;
        assertNotEquals(metrics0, metrics1);
        metrics1.upperCase--;
        metrics1.lowerCase++;
        assertNotEquals(metrics0, metrics1);
        metrics1.lowerCase--;
        metrics1.numeric++;
        assertNotEquals(metrics0, metrics1);
        metrics1.numeric--;
        metrics1.symbols++;
        assertNotEquals(metrics0, metrics1);
        metrics1.symbols--;
        metrics1.nonLetter++;
        assertNotEquals(metrics0, metrics1);
        metrics1.nonLetter--;
        assertEquals(metrics0, metrics1);


    }

    @Test
    public void testConstructQuality() {
        PasswordMetrics expected = new PasswordMetrics();
        expected.quality = PASSWORD_QUALITY_COMPLEX;

        PasswordMetrics actual = new PasswordMetrics(PASSWORD_QUALITY_COMPLEX);

        assertEquals(expected, actual);
    }

    @Test
    public void testDetermineComplexity_none() {
        assertEquals(PASSWORD_COMPLEXITY_NONE,
                PasswordMetrics.computeForPassword("".getBytes()).determineComplexity());
    }

    @Test
    public void testDetermineComplexity_lowSomething() {
        assertEquals(PASSWORD_COMPLEXITY_LOW,
                new PasswordMetrics(PASSWORD_QUALITY_SOMETHING).determineComplexity());
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
    public void testGetTargetQualityMetrics_noneComplexityReturnsDefaultMetrics() {
        PasswordMetrics metrics =
                getTargetQualityMetrics(PASSWORD_COMPLEXITY_NONE, PASSWORD_QUALITY_ALPHANUMERIC);

        assertTrue(metrics.isDefault());
    }

    @Test
    public void testGetTargetQualityMetrics_qualityNotAllowedReturnsMinQualityMetrics() {
        PasswordMetrics metrics =
                getTargetQualityMetrics(PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_QUALITY_NUMERIC);

        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX, metrics.quality);
        assertEquals(/* expected= */ 4, metrics.length);
    }

    @Test
    public void testGetTargetQualityMetrics_highComplexityNumericComplex() {
        PasswordMetrics metrics = getTargetQualityMetrics(
                PASSWORD_COMPLEXITY_HIGH, PASSWORD_QUALITY_NUMERIC_COMPLEX);

        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX, metrics.quality);
        assertEquals(/* expected= */ 8, metrics.length);
    }

    @Test
    public void testGetTargetQualityMetrics_mediumComplexityAlphabetic() {
        PasswordMetrics metrics = getTargetQualityMetrics(
                PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_QUALITY_ALPHABETIC);

        assertEquals(PASSWORD_QUALITY_ALPHABETIC, metrics.quality);
        assertEquals(/* expected= */ 4, metrics.length);
    }

    @Test
    public void testGetTargetQualityMetrics_lowComplexityAlphanumeric() {
        PasswordMetrics metrics = getTargetQualityMetrics(
                PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_QUALITY_ALPHANUMERIC);

        assertEquals(PASSWORD_QUALITY_ALPHANUMERIC, metrics.quality);
        assertEquals(/* expected= */ 4, metrics.length);
    }

    @Test
    public void testGetActualRequiredQuality_nonComplex() {
        int actual = getActualRequiredQuality(
                PASSWORD_QUALITY_NUMERIC_COMPLEX,
                /* requiresNumeric= */ false,
                /* requiresLettersOrSymbols= */ false);

        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX, actual);
    }

    @Test
    public void testGetActualRequiredQuality_complexRequiresNone() {
        int actual = getActualRequiredQuality(
                PASSWORD_QUALITY_COMPLEX,
                /* requiresNumeric= */ false,
                /* requiresLettersOrSymbols= */ false);

        assertEquals(PASSWORD_QUALITY_UNSPECIFIED, actual);
    }

    @Test
    public void testGetActualRequiredQuality_complexRequiresNumeric() {
        int actual = getActualRequiredQuality(
                PASSWORD_QUALITY_COMPLEX,
                /* requiresNumeric= */ true,
                /* requiresLettersOrSymbols= */ false);

        assertEquals(PASSWORD_QUALITY_NUMERIC, actual);
    }

    @Test
    public void testGetActualRequiredQuality_complexRequiresLetters() {
        int actual = getActualRequiredQuality(
                PASSWORD_QUALITY_COMPLEX,
                /* requiresNumeric= */ false,
                /* requiresLettersOrSymbols= */ true);

        assertEquals(PASSWORD_QUALITY_ALPHABETIC, actual);
    }

    @Test
    public void testGetActualRequiredQuality_complexRequiresNumericAndLetters() {
        int actual = getActualRequiredQuality(
                PASSWORD_QUALITY_COMPLEX,
                /* requiresNumeric= */ true,
                /* requiresLettersOrSymbols= */ true);

        assertEquals(PASSWORD_QUALITY_ALPHANUMERIC, actual);
    }

    @Test
    public void testGetMinimumMetrics_userInputStricter() {
        PasswordMetrics metrics = getMinimumMetrics(
                PASSWORD_COMPLEXITY_HIGH,
                PASSWORD_QUALITY_ALPHANUMERIC,
                PASSWORD_QUALITY_NUMERIC,
                /* requiresNumeric= */ false,
                /* requiresLettersOrSymbols= */ false);

        assertEquals(PASSWORD_QUALITY_ALPHANUMERIC, metrics.quality);
        assertEquals(/* expected= */ 6, metrics.length);
    }

    @Test
    public void testGetMinimumMetrics_actualRequiredQualityStricter() {
        PasswordMetrics metrics = getMinimumMetrics(
                PASSWORD_COMPLEXITY_HIGH,
                PASSWORD_QUALITY_UNSPECIFIED,
                PASSWORD_QUALITY_NUMERIC,
                /* requiresNumeric= */ false,
                /* requiresLettersOrSymbols= */ false);

        assertEquals(PASSWORD_QUALITY_NUMERIC_COMPLEX, metrics.quality);
        assertEquals(/* expected= */ 8, metrics.length);
    }
}
