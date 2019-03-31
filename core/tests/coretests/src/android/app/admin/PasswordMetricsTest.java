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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC,
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
                new PasswordMetrics(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, 4));

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
}
